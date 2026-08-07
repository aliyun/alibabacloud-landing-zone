#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
source "$SCRIPT_DIR/lib.sh"

usage() { cat <<'EOF'
Usage: initialize-and-verify.sh SUBCOMMAND --manifest FILE [options]
Subcommands: upgrade-inventory, database, database-migrate, runtime-config, rolling-start, rolling-upgrade, business-init, acceptance, handoff
Options: --env-file FILE --handoff-file FILE --confirm-received --confirm-migrations --confirm-rolling-compatible
Secrets are read from protected files; they are never accepted as argument values.
EOF
}
[[ ${1:-} == --help || ${1:-} == -h ]] && { usage; exit 0; }
subcommand=${1:-}; [[ -n "$subcommand" ]] || { usage >&2; exit 2; }; shift
manifest= env_file= handoff_file= confirm_received=false confirm_migrations=false confirm_rolling_compatible=false
require_no_secret_args "$@"
while (($#)); do
  case "$1" in
    --manifest) manifest=${2:-}; shift 2;;
    --env-file) env_file=${2:-}; shift 2;;
    --handoff-file) handoff_file=${2:-}; shift 2;;
    --confirm-received) confirm_received=true; shift;;
    --confirm-migrations) confirm_migrations=true; shift;;
    --confirm-rolling-compatible) confirm_rolling_compatible=true; shift;;
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
  local instance=$1 script=$2 response invocation result status exit_code output deadline
  require_command aliyun
  response=$(aliyun_cli ecs RunCommand --region "$region" --RegionId "$region" --InstanceId.1 "$instance" --Type RunShellScript --Timeout 1800 --CommandContent "$script") || die "Cloud Assistant submission failed"
  invocation=$(cloud_assistant_invocation_id <<<"$response") || die "Cloud Assistant invocation ID missing"
  atomic_jq "$manifest" --arg id "$invocation" --arg instance "$instance" \
    '.remoteInvocations=((.remoteInvocations // []) + [{invokeId:$id,instanceId:$instance,status:"submitted",submittedAt:(now|todateiso8601)}])'
  deadline=$((SECONDS + 1860))
  while ((SECONDS < deadline)); do
    sleep 2
    result=$(aliyun_cli ecs DescribeInvocationResults --region "$region" --RegionId "$region" --InvokeId "$invocation") || continue
    status=$(cloud_assistant_status <<<"$result")
    case "$status" in
      Finished|Success)
        exit_code=$(cloud_assistant_exit_code <<<"$result")
        [[ "$exit_code" == 0 ]] || die "Cloud Assistant command failed"
        atomic_jq "$manifest" --arg id "$invocation" '(.remoteInvocations[] | select(.invokeId==$id)).status="finished"'
        output=$(jq -r '[..|objects|.Output? // empty][0] // ""' <<<"$result" | decode_b64 2>/dev/null || true)
        jq -n --arg invocationId "$invocation" --arg output "$output" '{invocationId:$invocationId,output:$output}'
        return 0;;
      Failed|PartialFailed|Stopped|Stopping|TimedOut|Cancelled|Invalid|Aborted|Terminated) die "Cloud Assistant invocation reached terminal failure";;
    esac
  done
  atomic_jq "$manifest" --arg id "$invocation" '(.remoteInvocations[] | select(.invokeId==$id)).status="poll-timeout"'
  die "Cloud Assistant polling timed out; remote state is unknown, inspect the recorded invocation before retrying"
}

remote_db_prelude='set -euo pipefail
source /etc/autowonder/autowonder.env
connection=${SPRING_DATASOURCE_URL#jdbc:mysql://}
authority=${connection%%/*}
database=${connection#*/}; database=${database%%\?*}
host=${authority%%:*}; port=${authority##*:}; test "$host" != "$port" || port=3306
export MYSQL_PWD="$SPRING_DATASOURCE_PASSWORD"'

case "$subcommand" in
  upgrade-inventory)
    expected_active=$(jq -er '.deployment.activeCommit // .upgrade.fromCommit // .repositoryCommit | select(test("^[0-9a-f]{40}$"))' "$manifest") || die "expected active commit is missing"
    node_json='[]'; active_prefix=
    for instance in "${instances[@]}"; do
      result=$(run_cloud "$instance" 'set -euo pipefail
active=$(readlink -f /opt/autowonder/current)
test -d "$active"
release=${active##*/}
printf "ACTIVE_RELEASE=%s\n" "$release"')
      node_prefix=$(jq -r '.output' <<<"$result" | sed -n 's/^ACTIVE_RELEASE=//p' | tail -1)
      [[ "$node_prefix" =~ ^[0-9a-f]{12}$ ]] || die "active release directory is not an expected commit prefix"
      [[ -z "$active_prefix" || "$node_prefix" == "$active_prefix" ]] || die "ECS nodes run different active releases"
      active_prefix=$node_prefix
      node_json=$(jq --arg instance "$instance" --arg prefix "$node_prefix" '. + [{instanceId:$instance,activeCommitPrefix:$prefix}]' <<<"$node_json")
    done
    [[ "${expected_active:0:12}" == "$active_prefix" ]] || die "manifest active commit does not match ECS active release"
    atomic_jq "$manifest" --arg commit "$expected_active" --argjson nodes "$node_json" \
      '.deployment.activeCommit=$commit | .upgradeInventory={status:"verified",activeCommit:$commit,nodes:$nodes,verifiedAt:(now|todateiso8601)} | .phase="upgrade-inventory" | .status="ready"'
    ;;
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
    recommended_runtime_version=$(jq -er '
      .recommendedRuntimeVersion // empty |
      select(test("^[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?$"))
    ' "$manifest") || die "recommendedRuntimeVersion must be a semantic version"
    normalized_env=$(mktemp "${env_file}.tmp.XXXXXX"); TEMP_FILES+=("$normalized_env")
    awk -v key="AUTOWONDER_RUNTIME_RECOMMENDED_VERSION" -v value="$recommended_runtime_version" '
      index($0, key "=") == 1 { if (!written) print key "=" value; written=1; next }
      { print }
      END { if (!written) print key "=" value }
    ' "$env_file" >"$normalized_env"
    chmod 600 "$normalized_env"
    mv -f -- "$normalized_env" "$env_file"
    unset recommended_runtime_version normalized_env
    for key in SPRING_DATASOURCE_URL SPRING_DATASOURCE_USERNAME SPRING_DATASOURCE_PASSWORD REDIS_HOST OSS_ENDPOINT OSS_PUBLIC_ENDPOINT OSS_BUCKET OSS_ACCESS_KEY_ID OSS_ACCESS_KEY_SECRET AUTOWONDER_SECRET_MASTER_KEY AUTOWONDER_JWT_SECRET AUTOWONDER_PUBLIC_BASE_URL AUTOWONDER_RUNTIME_RECOMMENDED_VERSION SLS_ENDPOINT SLS_PROJECT SLS_SYS_LOGSTORE SLS_BIZ_LOGSTORE SLS_METRIC_LOGSTORE SLS_ACCESS_KEY_ID SLS_ACCESS_KEY_SECRET; do
      grep -q "^${key}=" "$env_file" || die "required environment key missing: $key"
      require_nonempty_env "$env_file" "$key"
    done
    if [[ $(jq -r '.mode // empty' "$manifest") == upgrade ]]; then
      while IFS= read -r key; do
        [[ -n "$key" ]] || continue
        grep -q "^${key}=" "$env_file" || die "new upgrade environment key missing: $key"
        require_nonempty_env "$env_file" "$key"
      done < <(jq -r '.upgrade.environment.added[]? // empty' "$manifest")
    fi
    master_key=$(unquote_simple "$(env_raw_value "$env_file" AUTOWONDER_SECRET_MASTER_KEY)")
    [[ "$master_key" =~ ^[A-Za-z0-9+/]{43}=$ ]] || die "master key must be strict single-line Base64"
    [[ $(printf '%s' "$master_key" | decode_b64 | wc -c | tr -d ' ') == 32 ]] || die "master key must decode to 32 bytes"
    unset master_key
    public_base_url=$(unquote_simple "$(env_raw_value "$env_file" AUTOWONDER_PUBLIC_BASE_URL)")
    [[ "$public_base_url" =~ ^https?://[^[:space:]]+$ ]] || die "AUTOWONDER_PUBLIC_BASE_URL must be an absolute HTTP(S) URL"
    unset public_base_url
    oss_endpoint=$(unquote_simple "$(env_raw_value "$env_file" OSS_ENDPOINT)")
    oss_host=${oss_endpoint#http://}; oss_host=${oss_host#https://}; oss_host=${oss_host%/}
    [[ "$oss_host" == "oss-${region}-internal.aliyuncs.com" ]] || die "OSS_ENDPOINT must use the regional intranet endpoint oss-${region}-internal.aliyuncs.com"
    oss_public_endpoint=$(unquote_simple "$(env_raw_value "$env_file" OSS_PUBLIC_ENDPOINT)")
    [[ "$oss_public_endpoint" == "https://oss-${region}.aliyuncs.com" ]] || die "OSS_PUBLIC_ENDPOINT must use the regional public HTTPS endpoint https://oss-${region}.aliyuncs.com"
    unset oss_endpoint oss_host oss_public_endpoint
    grep -q '^AUTOWONDER_AONE_ENABLED=false$' "$env_file" || die "Aone must be disabled"
    grep -q '^AUTOWONDER_SLS_ENABLED=true$' "$env_file" || die "SLS must be enabled"
    grep -q '^AUTOWONDER_SIGAR_ENABLED=true$' "$env_file" || die "SIGAR must be enabled"
    atomic_jq "$manifest" --arg version "$(unquote_simple "$(env_raw_value "$env_file" AUTOWONDER_RUNTIME_RECOMMENDED_VERSION)")" \
      --arg envHash "$(sha256_file "$env_file")" \
      '.runtimeConfig={prepared:true,fileMode:"0600",valuesValidated:true,recommendedRuntimeVersion:$version} | (if .mode == "upgrade" then .upgrade.environmentValidated=true | .upgrade.environmentCandidateSha256=$envHash else . end) | .phase="runtime-config" | .status="prepared"'
    ;;
  database)
    imported=$(jq -r '.database.imported // false' "$manifest")
    templates_imported=$(jq -r '.database.templatesImported // false' "$manifest")
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
      atomic_jq "$manifest" --arg invocation "$(jq -r '.invocationId' <<<"$import_result")" '.database.imported=true | .database.importInvocationId=$invocation | .database.postcheck=false'
    fi
    if [[ "$templates_imported" != true ]]; then
      template_result=$(run_cloud "${instances[0]}" "$remote_db_prelude
mysql -h \"\$host\" -P \"\$port\" -u \"\$SPRING_DATASOURCE_USERNAME\" \"\$database\" < /opt/autowonder/releases/$short_commit/autowonder-community-templates.sql
printf 'TEMPLATES_IMPORTED\n'")
      atomic_jq "$manifest" --arg invocation "$(jq -r '.invocationId' <<<"$template_result")" '.database.templatesImported=true | .database.templatesImportInvocationId=$invocation | .database.postcheck=false'
    fi
    post=$(run_cloud "${instances[0]}" "$remote_db_prelude
count=\$(mysql -h \"\$host\" -P \"\$port\" -u \"\$SPRING_DATASOURCE_USERNAME\" -Nse 'SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()' \"\$database\")
for table in user org workitem agent executor; do mysql -h \"\$host\" -P \"\$port\" -u \"\$SPRING_DATASOURCE_USERNAME\" -Nse \"SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='\$table'\" \"\$database\" | grep -qx 1; done
template_count=\$(mysql -h \"\$host\" -P \"\$port\" -u \"\$SPRING_DATASOURCE_USERNAME\" -Nse \"SELECT COUNT(*) FROM squad_template WHERE tenant_id IS NULL AND status='ACTIVE' AND is_deleted=0 AND JSON_VALID(content_json)=1 AND ((name='独立开发者' AND JSON_LENGTH(content_json, '\$.agents')=1) OR (name='开发+评审双人组' AND JSON_LENGTH(content_json, '\$.agents')=2) OR (name='标准研发交付小队' AND JSON_LENGTH(content_json, '\$.agents')=3) OR (name='全链路研发协作小队' AND JSON_LENGTH(content_json, '\$.agents')=7))\" \"\$database\")
test \"\$template_count\" = 4
printf 'TABLE_COUNT=%s\\nTEMPLATE_COUNT=%s\\nPOSTCHECK=passed\\n' \"\$count\" \"\$template_count\"")
    atomic_jq "$manifest" --arg invocation "$(jq -r '.invocationId' <<<"$post")" '.database.postcheck=true | .database.postcheckInvocationId=$invocation | .phase="database" | .status="initialized"'
    ;;
  database-migrate)
    [[ $(jq -r '.mode // empty' "$manifest") == upgrade ]] || die "database migration requires upgrade mode"
    [[ $(jq -r '(.upgrade.blockedReasons // []) | length' "$manifest") == 0 ]] || die "upgrade plan has blocking findings"
    pending=$(jq -c '(.upgrade.pendingMigrations // []) | sort_by(.version)' "$manifest")
    pending_count=$(jq 'length' <<<"$pending")
    if [[ "$pending_count" == 0 ]]; then
      atomic_jq "$manifest" '.upgrade.databaseMigration={status:"not-required",applied:[]} | .phase="database-migrate" | .status="ready"'
      exit 0
    fi
    [[ "$confirm_migrations" == true ]] || die "explicit migration confirmation is required"
    [[ $(jq -r '.upgrade.databaseBackup.status // empty' "$manifest") == verified ]] || die "verified database backup is required"
    [[ $(jq '[.upgrade.pendingMigrations[].riskOperations[]? | select(. == "DROP" or . == "TRUNCATE" or . == "RENAME")] | length' "$manifest") == 0 ]] || die "destructive migration detected; a maintenance workflow is required instead of rolling activation"
    [[ "$confirm_rolling_compatible" == true ]] || die "explicit active-version compatibility confirmation is required"
    [[ "$commit" =~ ^[0-9a-f]{40}$ ]] || die "target commit must be an exact SHA"
    atomic_jq "$manifest" '.upgrade.migrationApproved=true | .upgrade.databaseCompatibility={status:"rolling-compatible",rollingAllowed:true,destructive:false} | .upgrade.databaseMigration={status:"running",applied:[]}'

    migration_remote="$remote_db_prelude
release=/opt/autowonder/releases/$short_commit
test -d \"\$release/migration\"
mysql -h \"\$host\" -P \"\$port\" -u \"\$SPRING_DATASOURCE_USERNAME\" \"\$database\" <<'SQL'
CREATE TABLE IF NOT EXISTS autowonder_schema_history (
  migration_version BIGINT NOT NULL PRIMARY KEY,
  filename VARCHAR(255) NOT NULL,
  checksum CHAR(64) NOT NULL,
  source_commit CHAR(40) NOT NULL,
  installed_on TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  execution_ms BIGINT NULL,
  success TINYINT(1) NOT NULL,
  error_message VARCHAR(512) NULL
);
SQL
coproc MIGRATION_LOCK { mysql -h \"\$host\" -P \"\$port\" -u \"\$SPRING_DATASOURCE_USERNAME\" --batch --skip-column-names \"\$database\"; }
lock_in=\${MIGRATION_LOCK[1]}; lock_out=\${MIGRATION_LOCK[0]}; lock_pid=\$MIGRATION_LOCK_PID
printf '%s\n' \"SELECT GET_LOCK('autowonder-community-migration', 30);\" >&\"\$lock_in\"
IFS= read -r -t 35 -u \"\$lock_out\" lock_acquired
test \"\$lock_acquired\" = 1
release_lock() {
  printf '%s\n' \"SELECT RELEASE_LOCK('autowonder-community-migration');\" >&\"\$lock_in\" 2>/dev/null || true
  IFS= read -r -t 5 -u \"\$lock_out\" lock_released 2>/dev/null || true
  printf '%s\n' quit >&\"\$lock_in\" 2>/dev/null || true
  wait \"\$lock_pid\" 2>/dev/null || true
}
trap release_lock EXIT"

    while IFS= read -r migration; do
      version=$(jq -er '.version | select(type == "number" and . > 0 and floor == .)' <<<"$migration") || die "invalid migration version"
      file=$(jq -er '.file | select(test("^docs/migration/V0*[1-9][0-9]*__[a-z0-9]+(?:_[a-z0-9]+)*\\.sql$"))' <<<"$migration") || die "invalid migration file"
      expected_sha=$(jq -er '.sha256 | select(test("^[0-9a-f]{64}$"))' <<<"$migration") || die "invalid migration checksum"
      filename=${file##*/}
      migration_remote+="
file=\"\$release/migration/$filename\"
test -f \"\$file\"
actual_sha=\$(sha256sum \"\$file\" | awk '{print \$1}')
test \"\$actual_sha\" = '$expected_sha'
existing=\$(mysql -h \"\$host\" -P \"\$port\" -u \"\$SPRING_DATASOURCE_USERNAME\" -Nse \"SELECT CONCAT(checksum, ' ', success) FROM autowonder_schema_history WHERE migration_version=$version\" \"\$database\")
if test -n \"\$existing\"; then
  recorded_checksum=\${existing%% *}; recorded_success=\${existing##* }
  test \"\$recorded_checksum\" = '$expected_sha' || { echo 'migration checksum mismatch' >&2; exit 1; }
  test \"\$recorded_success\" = 1 || { echo 'previous failed migration record requires reviewed repair' >&2; exit 1; }
else
  started=\$(date +%s)
  if mysql -h \"\$host\" -P \"\$port\" -u \"\$SPRING_DATASOURCE_USERNAME\" \"\$database\" < \"\$file\"; then
    elapsed=\$(( (\$(date +%s) - started) * 1000 ))
    mysql -h \"\$host\" -P \"\$port\" -u \"\$SPRING_DATASOURCE_USERNAME\" \"\$database\" -e \"INSERT INTO autowonder_schema_history(migration_version,filename,checksum,source_commit,execution_ms,success) VALUES($version,'$filename','$expected_sha','$commit',\$elapsed,1)\"
  else
    elapsed=\$(( (\$(date +%s) - started) * 1000 ))
    mysql -h \"\$host\" -P \"\$port\" -u \"\$SPRING_DATASOURCE_USERNAME\" \"\$database\" -e \"INSERT INTO autowonder_schema_history(migration_version,filename,checksum,source_commit,execution_ms,success,error_message) VALUES($version,'$filename','$expected_sha','$commit',\$elapsed,0,'migration command failed') ON DUPLICATE KEY UPDATE success=0,error_message='migration command failed'\" || true
    exit 1
  fi
fi"
    done < <(jq -c '.[]' <<<"$pending")
    migration_remote+="
printf 'MIGRATIONS_APPLIED=%s\\n' '$pending_count'"

    if migration_result=$(run_cloud "${instances[0]}" "$migration_remote"); then
      atomic_jq "$manifest" --arg invocation "$(jq -r '.invocationId' <<<"$migration_result")" --argjson applied "$pending" \
        '.upgrade.databaseMigration={status:"passed",invocationId:$invocation,applied:$applied} | .phase="database-migrate" | .status="ready"'
    else
      atomic_jq "$manifest" '.upgrade.databaseMigration.status="failed" | .phase="database-migrate" | .status="failed"'
      die "database migration failed; target application was not activated"
    fi
    ;;
  rolling-upgrade)
    [[ $(jq -r '.mode // empty' "$manifest") == upgrade ]] || die "rolling upgrade requires upgrade mode"
    [[ $(jq -r '(.upgrade.blockedReasons // []) | length' "$manifest") == 0 ]] || die "upgrade plan has blocking findings"
    [[ $(jq -r '.deployment.lastRun.mode // empty' "$manifest") == stage-only ]] || die "stage-only release must be installed before rolling upgrade"
    migration_status=$(jq -r '.upgrade.databaseMigration.status // empty' "$manifest")
    [[ "$migration_status" == passed || "$migration_status" == not-required ]] || die "database migration checkpoint is incomplete"
    if [[ "$migration_status" == passed ]]; then
      [[ $(jq -r '.upgrade.databaseCompatibility.rollingAllowed // false' "$manifest") == true ]] || die "database compatibility does not allow rolling activation"
    fi
    expected_jar=$(jq -er '.artifacts.jar.sha256 | select(test("^[0-9a-f]{64}$"))' "$manifest") || die "target JAR checksum is missing"
    base_url=$(jq -er '.applicationBaseUrl // empty' "$manifest") || die "applicationBaseUrl missing"
    invocation_json='[]'; node_json='[]'
    for instance in "${instances[@]}"; do
      result=$(run_cloud "$instance" "set -euo pipefail
target=/opt/autowonder/releases/$short_commit
test -f \"\$target/auto-wonder.jar\" || { echo 'expected target release is not staged' >&2; exit 1; }
actual_jar=\$(sha256sum \"\$target/auto-wonder.jar\" | awk '{print \$1}')
test \"\$actual_jar\" = '$expected_jar'
previous=\$(readlink -f /opt/autowonder/current 2>/dev/null || true)
if ln -sfn /opt/autowonder/releases/$short_commit /opt/autowonder/current.new \
  && mv -Tf /opt/autowonder/current.new /opt/autowonder/current \
  && systemctl daemon-reload \
  && systemctl enable autowonder.service \
  && systemctl restart autowonder.service; then
  for attempt in \$(seq 1 60); do
    body=\$(curl --fail --silent --connect-timeout 2 --max-time 5 http://127.0.0.1:7001/checkpreload.htm 2>/dev/null || true)
    if test \"\$(readlink -f /opt/autowonder/current)\" = \"\$target\" \
      && systemctl is-active --quiet autowonder.service \
      && ss -ltnH \"sport = :7001\" | grep -q . \
      && test \"\$body\" = success \
      && curl --fail --silent --connect-timeout 2 --max-time 5 http://127.0.0.1:7001/api/platform/branding/public >/dev/null; then
      printf 'ROLLING_STATUS=passed\\nPREVIOUS_RELEASE=%s\\nACTIVE_RELEASE=%s\\n' \"\$previous\" \"\$target\"
      exit 0
    fi
    sleep 2
  done
fi
printf 'ROLLING_STATUS=failed\\nPREVIOUS_RELEASE=%s\\nACTIVE_RELEASE=%s\\nRESOLUTION_REQUIRED=human-confirmation\\n' \"\$previous\" \"\$(readlink -f /opt/autowonder/current 2>/dev/null || true)\"
exit 0")
      invocation=$(jq -r '.invocationId' <<<"$result")
      output=$(jq -r '.output' <<<"$result")
      rollout_status=$(sed -n 's/^ROLLING_STATUS=//p' <<<"$output" | tail -1)
      previous=$(sed -n 's/^PREVIOUS_RELEASE=//p' <<<"$output" | tail -1)
      resolution_required=$(sed -n 's/^RESOLUTION_REQUIRED=//p' <<<"$output" | tail -1)
      [[ "$rollout_status" == passed || "$rollout_status" == failed ]] || die "rolling upgrade result is invalid"
      invocation_json=$(jq --arg id "$invocation" '. + [$id]' <<<"$invocation_json")
      node_json=$(jq --arg id "$invocation" --arg instance "$instance" --arg previous "$previous" --arg status "$rollout_status" --arg resolution "$resolution_required" \
        '. + [{instanceId:$instance,invocationId:$id,previousRelease:$previous,status:$status,resolutionRequired:(if $resolution == "" then null else $resolution end)}]' <<<"$node_json")
      atomic_jq "$manifest" --argjson ids "$invocation_json" --argjson nodes "$node_json" --arg status "$rollout_status" \
        '.rollingUpgrade={status:$status,invocationIds:$ids,nodes:$nodes,nodeOrder:"sequential",targetCommit:.repositoryCommit} | .phase="application" | .status=(if $status == "passed" then "running" else "failed" end)'
      [[ "$rollout_status" == passed ]] || die "node activation failed; stopped without remediation; read-only diagnosis and explicit human confirmation are required"
      [[ $(curl --fail --silent --connect-timeout 5 --max-time 10 "$base_url/checkpreload.htm") == success ]] || die "public health check failed after node activation"
      curl --fail --silent --connect-timeout 5 --max-time 10 "$base_url/api/platform/branding/public" >/dev/null || die "public branding probe failed after node activation"
    done
    atomic_jq "$manifest" --argjson ids "$invocation_json" --argjson nodes "$node_json" \
      '.rollingUpgrade={status:"passed",invocationIds:$ids,nodes:$nodes,nodeOrder:"sequential",targetCommit:.repositoryCommit} | .deployment.activeCommit=.repositoryCommit | .upgradeInventory.status="stale" | .phase="application" | .status="ready"'
    ;;
  rolling-start)
    invocation_json='[]'
    for instance in "${instances[@]}"; do
      result=$(run_cloud "$instance" 'set -euo pipefail
systemctl enable --now autowonder.service
for attempt in $(seq 1 60); do
  body=$(curl --fail --silent --connect-timeout 2 --max-time 5 http://127.0.0.1:7001/checkpreload.htm 2>/dev/null || true)
  if systemctl is-active --quiet autowonder.service \
    && ss -ltnH "sport = :7001" | grep -q . \
    && [ "$body" = success ] \
    && curl --fail --silent --connect-timeout 2 --max-time 5 http://127.0.0.1:7001/api/platform/branding/public >/dev/null; then
    exit 0
  fi
  sleep 2
done
exit 1')
      invocation_json=$(jq --arg id "$(jq -r '.invocationId' <<<"$result")" '. + [$id]' <<<"$invocation_json")
    done
    atomic_jq "$manifest" --argjson ids "$invocation_json" '.rollingStart={status:"passed",invocationIds:$ids,nodeOrder:"sequential"} | .deployment.activeCommit=.repositoryCommit | .phase="application" | .status="ready"'
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
    [[ $(curl --fail --silent --connect-timeout 5 --max-time 10 "$base_url/checkpreload.htm") == success ]] || die "public health check failed"
    curl --fail --silent --connect-timeout 5 --max-time 10 "$base_url/api/platform/branding/public" >/dev/null || die "public branding probe failed"
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
