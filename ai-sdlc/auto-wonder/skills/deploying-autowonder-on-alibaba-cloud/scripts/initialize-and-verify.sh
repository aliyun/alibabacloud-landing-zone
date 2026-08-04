#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
source "$SCRIPT_DIR/lib.sh"

usage() { cat <<'EOF'
Usage: initialize-and-verify.sh SUBCOMMAND --manifest FILE [options]
Subcommands: database, runtime-config, rolling-start, business-init, acceptance, handoff
Options: --env-file FILE --handoff-file FILE --confirm-received
Secrets are read from protected files; they are never accepted as argument values.
EOF
}
[[ ${1:-} == --help || ${1:-} == -h ]] && { usage; exit 0; }
subcommand=${1:-}; [[ -n "$subcommand" ]] || { usage >&2; exit 2; }; shift
manifest= env_file= handoff_file= confirm_received=false
require_no_secret_args "$@"
while (($#)); do
  case "$1" in
    --manifest) manifest=${2:-}; shift 2;;
    --env-file) env_file=${2:-}; shift 2;;
    --handoff-file) handoff_file=${2:-}; shift 2;;
    --confirm-received) confirm_received=true; shift;;
    --help|-h) usage; exit 0;;
    *) die "unknown argument";;
  esac
done
require_file "$manifest"; require_command jq; require_command curl
json_validate "$manifest"; reject_secret_keys "$manifest"
configure_cloud_profile "$manifest"
region=$(json_string "$manifest" '.region'); commit=$(json_string "$manifest" '.repositoryCommit'); short_commit=${commit:0:12}
instances=()
while IFS= read -r instance; do instances+=("$instance"); done < <(jq -er '(.resources.ecs_instance_ids // .resources.ecsInstanceIds)[]' "$manifest")
((${#instances[@]} > 0)) || die "ECS inventory is empty"
export ALIBABA_CLOUD_REGION_ID="$region"

decode_b64() { if printf '' | base64 -d >/dev/null 2>&1; then base64 -d; else base64 -D; fi; }
run_cloud() {
  local instance=$1 script=$2 response invocation result status exit_code output attempts=0
  require_command aliyun
  response=$(aliyun_cli ecs RunCommand --region "$region" --RegionId "$region" --InstanceId.1 "$instance" --Type RunShellScript --Timeout 1800 --CommandContent "$script") || die "Cloud Assistant submission failed"
  invocation=$(jq -er '.InvokeId // .InvocationId // .invokeId // .invocationId' <<<"$response") || die "Cloud Assistant invocation ID missing"
  atomic_jq "$manifest" --arg id "$invocation" --arg instance "$instance" \
    '.remoteInvocations=((.remoteInvocations // []) + [{invokeId:$id,instanceId:$instance,status:"submitted",submittedAt:(now|todateiso8601)}])'
  while ((attempts++ < 180)); do
    sleep 2
    result=$(aliyun_cli ecs DescribeInvocationResults --region "$region" --RegionId "$region" --InvokeId "$invocation") || continue
    status=$(jq -r '[..|objects|.InvokeRecordStatus? // .InvocationStatus? // .Status? // empty][0] // "Pending"' <<<"$result")
    case "$status" in
      Finished|Success)
        exit_code=$(jq -r '[..|objects|.ExitCode? // empty][0] // -1' <<<"$result")
        [[ "$exit_code" == 0 ]] || die "Cloud Assistant command failed"
        atomic_jq "$manifest" --arg id "$invocation" '(.remoteInvocations[] | select(.invokeId==$id)).status="finished"'
        output=$(jq -r '[..|objects|.Output? // empty][0] // ""' <<<"$result" | decode_b64 2>/dev/null || true)
        jq -n --arg invocationId "$invocation" --arg output "$output" '{invocationId:$invocationId,output:$output}'
        return 0;;
      Failed|Stopped|Stopping|TimedOut|Cancelled) die "Cloud Assistant invocation reached terminal failure";;
    esac
  done
  die "Cloud Assistant invocation timed out"
}

remote_db_prelude='set -euo pipefail
source /etc/autowonder/autowonder.env
connection=${SPRING_DATASOURCE_URL#jdbc:mysql://}
authority=${connection%%/*}
database=${connection#*/}; database=${database%%\?*}
host=${authority%%:*}; port=${authority##*:}; test "$host" != "$port" || port=3306
export MYSQL_PWD="$SPRING_DATASOURCE_PASSWORD"'

case "$subcommand" in
  runtime-config)
    require_file "$env_file"; require_mode_600 "$env_file"; require_command openssl
    grep -Eq '^[A-Z][A-Z0-9_]*=' "$env_file" || die "environment file is malformed"
    if grep -Eq '[`;]|\$\(' "$env_file"; then die "environment file contains executable shell syntax"; fi
    if ! grep -q '^AUTOWONDER_SECRET_MASTER_KEY=' "$env_file"; then
      value=$(openssl rand -base64 32 | tr -d '\r\n' | jq -Rr @sh); printf 'AUTOWONDER_SECRET_MASTER_KEY=%s\n' "$value" >>"$env_file"; unset value
    fi
    if ! grep -q '^AUTOWONDER_JWT_SECRET=' "$env_file"; then
      value=$(openssl rand -base64 48 | tr -d '\r\n' | jq -Rr @sh); printf 'AUTOWONDER_JWT_SECRET=%s\n' "$value" >>"$env_file"; unset value
    fi
    if ! grep -q '^AUTOWONDER_PUBLIC_BASE_URL=' "$env_file"; then
      value=$(jq -er '.applicationBaseUrl // empty' "$manifest") || die "applicationBaseUrl missing"
      value=$(printf '%s' "$value" | jq -Rr @sh)
      printf 'AUTOWONDER_PUBLIC_BASE_URL=%s\n' "$value" >>"$env_file"
      unset value
    fi
    for key in SPRING_DATASOURCE_URL SPRING_DATASOURCE_USERNAME SPRING_DATASOURCE_PASSWORD REDIS_HOST OSS_ENDPOINT OSS_BUCKET OSS_ACCESS_KEY_ID OSS_ACCESS_KEY_SECRET AUTOWONDER_SECRET_MASTER_KEY AUTOWONDER_JWT_SECRET AUTOWONDER_PUBLIC_BASE_URL SLS_ENDPOINT SLS_PROJECT SLS_SYS_LOGSTORE SLS_BIZ_LOGSTORE SLS_METRIC_LOGSTORE SLS_ACCESS_KEY_ID SLS_ACCESS_KEY_SECRET; do
      grep -q "^${key}=" "$env_file" || die "required environment key missing: $key"
      require_nonempty_env "$env_file" "$key"
    done
    master_key=$(unquote_simple "$(env_raw_value "$env_file" AUTOWONDER_SECRET_MASTER_KEY)")
    [[ "$master_key" =~ ^[A-Za-z0-9+/]{43}=$ ]] || die "master key must be strict single-line Base64"
    [[ $(printf '%s' "$master_key" | decode_b64 | wc -c | tr -d ' ') == 32 ]] || die "master key must decode to 32 bytes"
    unset master_key
    public_base_url=$(unquote_simple "$(env_raw_value "$env_file" AUTOWONDER_PUBLIC_BASE_URL)")
    [[ "$public_base_url" =~ ^https?://[^[:space:]]+$ ]] || die "AUTOWONDER_PUBLIC_BASE_URL must be an absolute HTTP(S) URL"
    unset public_base_url
    grep -q '^AUTOWONDER_AONE_ENABLED=false$' "$env_file" || die "Aone must be disabled"
    grep -q '^AUTOWONDER_SLS_ENABLED=true$' "$env_file" || die "SLS must be enabled"
    grep -q '^AUTOWONDER_SIGAR_ENABLED=true$' "$env_file" || die "SIGAR must be enabled"
    atomic_jq "$manifest" '.runtimeConfig={prepared:true,fileMode:"0600",valuesValidated:true} | .phase="runtime-config" | .status="prepared"'
    ;;
  database)
    imported=$(jq -r '.database.imported // false' "$manifest")
    pre=$(run_cloud "${instances[0]}" "$remote_db_prelude
count=\$(mysql -h \"\$host\" -P \"\$port\" -u \"\$SPRING_DATASOURCE_USERNAME\" -Nse 'SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()' \"\$database\")
printf 'TABLE_COUNT=%s\\n' \"\$count\"")
    count=$(jq -r '.output' <<<"$pre" | sed -nE 's/^TABLE_COUNT=([0-9]+)$/\1/p' | tail -1)
    [[ "$count" =~ ^[0-9]+$ ]] || die "database precondition result is invalid"
    if [[ "$imported" != true ]]; then
      [[ "$count" == 0 ]] || die "database is not empty; import refused"
      import_result=$(run_cloud "${instances[0]}" "$remote_db_prelude
mysql -h \"\$host\" -P \"\$port\" -u \"\$SPRING_DATASOURCE_USERNAME\" \"\$database\" < /opt/autowonder/releases/$short_commit/autowonder-schema.sql
printf 'SCHEMA_IMPORTED\\n'")
      atomic_jq "$manifest" --arg invocation "$(jq -r '.invocationId' <<<"$import_result")" '.database={imported:true,importInvocationId:$invocation,postcheck:false}'
    fi
    post=$(run_cloud "${instances[0]}" "$remote_db_prelude
count=\$(mysql -h \"\$host\" -P \"\$port\" -u \"\$SPRING_DATASOURCE_USERNAME\" -Nse 'SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()' \"\$database\")
for table in user org workitem agent executor; do mysql -h \"\$host\" -P \"\$port\" -u \"\$SPRING_DATASOURCE_USERNAME\" -Nse \"SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='\$table'\" \"\$database\" | grep -qx 1; done
printf 'TABLE_COUNT=%s\\nPOSTCHECK=passed\\n' \"\$count\"")
    atomic_jq "$manifest" --arg invocation "$(jq -r '.invocationId' <<<"$post")" '.database.postcheck=true | .database.postcheckInvocationId=$invocation | .phase="database" | .status="initialized"'
    ;;
  rolling-start)
    invocation_json='[]'
    for instance in "${instances[@]}"; do
      result=$(run_cloud "$instance" 'set -euo pipefail
systemctl enable --now autowonder.service
for attempt in $(seq 1 60); do
  body=$(curl --fail --silent http://127.0.0.1:7001/checkpreload.htm 2>/dev/null || true)
  if [ "$body" = success ]; then systemctl is-active --quiet autowonder.service; exit 0; fi
  sleep 2
done
exit 1')
      invocation_json=$(jq --arg id "$(jq -r '.invocationId' <<<"$result")" '. + [$id]' <<<"$invocation_json")
    done
    atomic_jq "$manifest" --argjson ids "$invocation_json" '.rollingStart={status:"passed",invocationIds:$ids,nodeOrder:"sequential"} | .phase="application" | .status="ready"'
    ;;
  business-init)
    require_command openssl
    base_url=$(jq -er '.applicationBaseUrl // empty' "$manifest") || die "applicationBaseUrl missing"
    handoff_file=${handoff_file:-"$(dirname "$manifest")/.autowonder-admin-handoff.json"}
    if [[ $(jq -r '.business.adminCreated // false' "$manifest") != true ]]; then
      password=$(openssl rand -base64 36 | tr -dc 'A-Za-z0-9!@#%^+=' | head -c 32)
      [[ ${#password} -ge 20 && "$password" =~ [A-Z] && "$password" =~ [a-z] && "$password" =~ [0-9] ]] || die "generated administrator password failed policy"
      request=$(mktemp); response=$(mktemp); TEMP_FILES+=("$request" "$response")
      jq -n --arg password "$password" '{username:"admin",password:$password,email:"admin@localhost.invalid",nickname:"Administrator"}' >"$request"
      curl --fail --silent --show-error -H 'Content-Type: application/json' --data-binary "@$request" "$base_url/api/auth/register" >"$response"
      jq -e '.success == true' "$response" >/dev/null || die "administrator registration failed"
      jq -n --arg password "$password" '{username:"admin",password:$password}' >"$handoff_file"; chmod 600 "$handoff_file"
      unset password
      atomic_jq "$manifest" '.business.adminCreated=true'
    else
      require_file "$handoff_file"; require_mode_600 "$handoff_file"
    fi
    login=$(mktemp); login_response=$(mktemp); org_request=$(mktemp); org_response=$(mktemp); curl_config=$(mktemp)
    TEMP_FILES+=("$login" "$login_response" "$org_request" "$org_response" "$curl_config")
    jq '{username,password}' "$handoff_file" >"$login"
    curl --fail --silent --show-error -H 'Content-Type: application/json' --data-binary "@$login" "$base_url/api/auth/login" >"$login_response"
    token=$(jq -er 'select(.success == true) | .data.accessToken' "$login_response") || die "administrator login failed"
    if [[ $(jq -r '.business.organizationCreated // false' "$manifest") != true ]]; then
      organization=$(json_string "$manifest" '.organizationName')
      jq -n --arg name "$organization" '{name:$name,description:"AutoWonder community deployment",background:""}' >"$org_request"
      printf 'header = "Content-Type: application/json"\nheader = "Authorization: Bearer %s"\ndata-binary = "@%s"\n' "$token" "$org_request" >"$curl_config"
      curl --fail --silent --show-error --config "$curl_config" "$base_url/api/orgs" >"$org_response"
      jq -e '.success == true' "$org_response" >/dev/null || die "organization creation failed"
      atomic_jq "$manifest" '.business.organizationCreated=true'
    fi
    unset token
    atomic_jq "$manifest" '.phase="business-init" | .status="initialized" | .business.status="passed"'
    ;;
  acceptance)
    base_url=$(jq -er '.applicationBaseUrl // empty' "$manifest") || die "applicationBaseUrl missing"
    [[ $(curl --fail --silent "$base_url/checkpreload.htm") == success ]] || die "public health check failed"
    capabilities=$(mktemp); TEMP_FILES+=("$capabilities")
    curl --fail --silent "$base_url/api/integrations/capabilities" >"$capabilities"
    jq -e '[.aoneEnabled, .data.aoneEnabled] | any(. == false)' "$capabilities" >/dev/null || die "Aone capability is unexpectedly enabled"
    protocol=$(jq -r '.ingressScenario' "$manifest")
    tls=false
    if [[ "$protocol" == domain-with-certificate && "$base_url" == https://* ]]; then tls=true; fi
    runtime_status=$(jq -r '.acceptance.runtimeWebSocket // "pending"' "$manifest")
    if [[ -n ${AUTOWONDER_RUNTIME_PROBE:-} ]]; then require_file "$AUTOWONDER_RUNTIME_PROBE"; "$AUTOWONDER_RUNTIME_PROBE" "$manifest"; runtime_status=passed; fi
    atomic_jq "$manifest" --arg runtime "$runtime_status" --argjson tls "$tls" '
      ({health:"pending",capabilities:"pending",databasePersistence:"pending",redisPersistence:"pending",ossApplicationPath:"pending",encryptedCredentialRestart:"pending",slsThreeStores:"pending",rollingRestart:"pending",ecsReboot:"pending",runtimeWebSocket:"pending",tags:"pending",secretLogScan:"pending",tlsAccepted:false} + (.acceptance // {}) + {health:"passed",capabilities:"passed",runtimeWebSocket:$runtime,tlsAccepted:$tls}) as $checks |
      .acceptance=$checks |
      .phase="acceptance" | .status=(if ([.acceptance[] | select(. == "pending")] | length)==0 then "accepted" else "partial" end)'
    ;;
  handoff)
    handoff_file=${handoff_file:-"$(dirname "$manifest")/.autowonder-admin-handoff.json"}
    if [[ "$confirm_received" == true ]]; then
      if [[ -f "$handoff_file" ]]; then
        require_mode_600 "$handoff_file"
        rm -f -- "$handoff_file"
        atomic_jq "$manifest" '.business.handoffConfirmed=true | .business.handoffFileRemoved=true'
      else
        jq -e '.business.handoffConfirmed == true and .business.handoffFileRemoved == true' "$manifest" >/dev/null || die "handoff file is missing before receipt confirmation"
      fi
      log "administrator credential receipt confirmed; temporary handoff file removed"
    else
      require_file "$handoff_file"; require_mode_600 "$handoff_file"
      [[ $(jq -r '.business.handoffDisplayed // false' "$manifest") != true ]] || die "administrator credentials were already displayed"
      printf 'Username: %s\nPassword: %s\nRotate this password immediately, then confirm receipt to remove the handoff file.\n' \
        "$(jq -r '.username' "$handoff_file")" "$(jq -r '.password' "$handoff_file")"
      atomic_jq "$manifest" '.business.handoffDisplayed=true'
    fi
    ;;
  *) die "unsupported initialization subcommand";;
esac
