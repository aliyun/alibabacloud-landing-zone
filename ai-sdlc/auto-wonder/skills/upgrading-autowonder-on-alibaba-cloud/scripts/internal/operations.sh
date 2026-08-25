#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
DEPLOY_SKILL_DIR=$(cd -- "$SCRIPT_DIR/../../../deploying-autowonder-on-alibaba-cloud" && pwd)
source "$DEPLOY_SKILL_DIR/scripts/lib.sh"

operation_scope=${AUTOWONDER_OPERATION_SCOPE:-}
[[ "$operation_scope" == deployment || "$operation_scope" == upgrade ]] || \
  die "internal operations require a bounded skill entrypoint"

usage() { cat <<'EOF'
Usage: initialize-and-verify.sh SUBCOMMAND --manifest FILE [options]
Subcommands: upgrade-inventory, upgrade-backup, rollback-upgrade, database, database-migrate, runtime-config, rolling-start, rolling-upgrade, business-init, acceptance, handoff
Options: --env-file FILE --terraform-dir DIR --handoff-file FILE --acceptance-evidence FILE --confirm-received --confirm-migrations --confirm-rolling-compatible --confirm-rollback
Secrets are read from protected files; they are never accepted as argument values.
EOF
}
[[ ${1:-} == --help || ${1:-} == -h ]] && { usage; exit 0; }
subcommand=${1:-}; [[ -n "$subcommand" ]] || { usage >&2; exit 2; }; shift
case "$operation_scope:$subcommand" in
  deployment:database|deployment:runtime-config|deployment:rolling-start|deployment:business-init|deployment:acceptance|deployment:handoff) ;;
  upgrade:upgrade-inventory|upgrade:upgrade-backup|upgrade:rollback-upgrade|upgrade:database-migrate|upgrade:runtime-config|upgrade:rolling-upgrade|upgrade:acceptance) ;;
  *) die "operation is outside the selected skill boundary" ;;
esac
manifest= env_file= terraform_dir= handoff_file= acceptance_evidence= confirm_received=false confirm_migrations=false confirm_rolling_compatible=false confirm_rollback=false
require_no_secret_args "$@"
while (($#)); do
  case "$1" in
    --manifest) manifest=${2:-}; shift 2;;
    --env-file) env_file=${2:-}; shift 2;;
    --terraform-dir) terraform_dir=${2:-}; shift 2;;
    --handoff-file) handoff_file=${2:-}; shift 2;;
    --acceptance-evidence) acceptance_evidence=${2:-}; shift 2;;
    --confirm-received) confirm_received=true; shift;;
    --confirm-migrations) confirm_migrations=true; shift;;
    --confirm-rolling-compatible) confirm_rolling_compatible=true; shift;;
    --confirm-rollback) confirm_rollback=true; shift;;
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

if [[ "$operation_scope" == upgrade ]]; then
  UPGRADE_SKILL_DIR=$(cd -- "$SCRIPT_DIR/../../../upgrading-autowonder-on-alibaba-cloud" && pwd)
  source "$UPGRADE_SKILL_DIR/scripts/upgrade-lib.sh"
  case "$subcommand" in
    upgrade-inventory) require_current_target_verification "$manifest" ;;
    *) require_upgrade_approval "$manifest" ;;
  esac
fi

decode_b64() { if printf '' | base64 -d >/dev/null 2>&1; then base64 -d; else base64 -D; fi; }
run_cloud() {
  local instance=$1 script=$2 response invocation result status exit_code output deadline encoded_script command_content
  require_command aliyun
  encoded_script=$(printf '%s' "$script" | base64 | tr -d '\r\n')
  command_content="printf '%s' '$encoded_script' | base64 -d | /usr/bin/env bash"
  response=$(aliyun_cli ecs RunCommand --region "$region" --RegionId "$region" --InstanceId.1 "$instance" --Type RunShellScript --Timeout 1800 --CommandContent "$command_content") || die "Cloud Assistant submission failed"
  unset encoded_script command_content
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

remote_db_prelude=$(cat <<'REMOTE_DB_PRELUDE'
set -euo pipefail
command -v python3 >/dev/null 2>&1
read_env_value() {
  python3 - "$1" <<'PY'
import re
import sys

key = sys.argv[1]
value = None
with open("/etc/autowonder/autowonder.env", encoding="utf-8") as env_file:
    for raw_line in env_file:
        line = raw_line.rstrip("\r\n")
        if not line or line.lstrip().startswith("#"):
            continue
        if not re.fullmatch(r"[A-Z][A-Z0-9_]*=.*", line):
            raise SystemExit("invalid environment file syntax")
        current_key, current_value = line.split("=", 1)
        if current_key == key:
            if value is not None:
                raise SystemExit("duplicate environment key")
            if len(current_value) >= 2 and current_value[0] == current_value[-1] and current_value[0] in "\"'":
                current_value = current_value[1:-1]
            value = current_value
if value is None:
    raise SystemExit("required environment key missing")
print(value, end="")
PY
}
SPRING_DATASOURCE_URL=$(read_env_value SPRING_DATASOURCE_URL)
SPRING_DATASOURCE_USERNAME=$(read_env_value SPRING_DATASOURCE_USERNAME)
SPRING_DATASOURCE_PASSWORD=$(read_env_value SPRING_DATASOURCE_PASSWORD)
connection=${SPRING_DATASOURCE_URL#jdbc:mysql://}
authority=${connection%%/*}
database=${connection#*/}; database=${database%%\?*}
host=${authority%%:*}; port=${authority##*:}; test "$host" != "$port" || port=3306
export MYSQL_PWD="$SPRING_DATASOURCE_PASSWORD"
REMOTE_DB_PRELUDE
)

case "$subcommand" in
  upgrade-inventory)
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
    expected_active=$(resolve_active_commit_from_prefix "$manifest" "$active_prefix")
    atomic_jq "$manifest" --arg commit "$expected_active" --argjson nodes "$node_json" \
      '.deployment.activeCommit=$commit | .repositoryCommit=$commit | .upgradeInventory={status:"verified",activeCommit:$commit,nodes:$nodes,targetVerificationFingerprint:.upgrade.targetVerification.fingerprint,resourceSetFingerprint:(.upgradeInfo.resourceSetFingerprint // ""),verifiedAt:(now|todateiso8601),verifiedEpoch:now} | .phase="upgrade-inventory" | .status="ready"'
    ;;
  upgrade-backup)
    [[ $(jq -r '.mode // empty' "$manifest") == upgrade ]] || die "upgrade backup requires upgrade mode"
    [[ $(jq -r '.upgradeInventory.status // empty' "$manifest") == verified ]] || die "verified upgrade inventory is required before backup"
    backup_nodes='[]'
    for instance in "${instances[@]}"; do
      result=$(run_cloud "$instance" 'set -euo pipefail
backup_archive=/opt/autowonder/upgrade-rollback-backup.tar.gz
backup_tmp="$backup_archive.tmp.$$"
backup_dir=$(mktemp -d /opt/autowonder/.upgrade-backup.XXXXXX)
verify_dir=$(mktemp -d /opt/autowonder/.upgrade-backup-verify.XXXXXX)
cleanup() { rm -rf "$backup_dir" "$verify_dir" "$backup_tmp"; }
trap cleanup EXIT
active=$(readlink -f /opt/autowonder/current)
test -d "$active"
test -f /etc/autowonder/autowonder.env
test -f /etc/systemd/system/autowonder.service
release_name=${active##*/}
test -n "$release_name"
install -d -m 0700 "$backup_dir/release"
cp -a "$active/." "$backup_dir/release/"
install -m 0640 -o root -g root /etc/autowonder/autowonder.env "$backup_dir/autowonder.env"
install -m 0644 -o root -g root /etc/systemd/system/autowonder.service "$backup_dir/autowonder.service"
printf "%s\n" "$release_name" >"$backup_dir/release-name"
(cd "$backup_dir" && find . -type f ! -name CHECKSUMS -print0 | LC_ALL=C sort -z | xargs -0 sha256sum >CHECKSUMS)
tar -C "$backup_dir" -czf "$backup_tmp" .
tar -tzf "$backup_tmp" >/dev/null
tar -xzf "$backup_tmp" -C "$verify_dir"
(cd "$verify_dir" && sha256sum -c CHECKSUMS >/dev/null)
chmod 0600 "$backup_tmp"
mv -f "$backup_tmp" "$backup_archive"
find /opt/autowonder/releases -type f \( -name autowonder.env.previous -o -name autowonder.service.previous \) -delete
backup_sha=$(sha256sum "$backup_archive" | awk "{print \$1}")
printf "BACKUP_STATUS=passed\nBACKUP_SHA256=%s\nACTIVE_RELEASE=%s\n" "$backup_sha" "$release_name"')
      invocation=$(jq -r '.invocationId' <<<"$result")
      output=$(jq -r '.output' <<<"$result")
      backup_status=$(sed -n 's/^BACKUP_STATUS=//p' <<<"$output" | tail -1)
      backup_sha=$(sed -n 's/^BACKUP_SHA256=//p' <<<"$output" | tail -1)
      active_release=$(sed -n 's/^ACTIVE_RELEASE=//p' <<<"$output" | tail -1)
      [[ "$backup_status" == passed && "$backup_sha" =~ ^[0-9a-f]{64}$ ]] || die "upgrade backup result is invalid"
      backup_nodes=$(jq --arg instance "$instance" --arg invocation "$invocation" --arg sha "$backup_sha" --arg release "$active_release" \
        '. + [{instanceId:$instance,invocationId:$invocation,sha256:$sha,activeRelease:$release,status:"passed"}]' <<<"$backup_nodes")
    done
    atomic_jq "$manifest" --argjson nodes "$backup_nodes" \
      '.upgrade.rollbackBackup={status:"passed",path:"/opt/autowonder/upgrade-rollback-backup.tar.gz",retentionPerEcs:1,nodes:$nodes,fromCommit:.upgrade.fromCommit,targetCommit:.upgrade.toCommit,createdAt:(now|todateiso8601)} | .phase="upgrade-backup" | .status="ready"'
    ;;
  rollback-upgrade)
    [[ "$confirm_rollback" == true ]] || die "explicit rollback confirmation is required; rerun with --confirm-rollback only after the user confirms"
    [[ $(jq -r '.upgrade.rollbackBackup.status // empty' "$manifest") == passed ]] || die "verified per-ECS rollback backup is required"
    [[ $(jq -r '.upgrade.rollbackBackup.targetCommit // empty' "$manifest") == "$commit" ]] || die "rollback backup does not belong to the current upgrade target"
    [[ $(jq -r '(.upgrade.databaseMigration.applied // []) | length' "$manifest") == 0 ]] || die "one-click application rollback is blocked after database migrations; use a reviewed recovery plan"
    rollback_nodes='[]'
    for instance in "${instances[@]}"; do
      if ! result=$(run_cloud "$instance" 'set -euo pipefail
backup_archive=/opt/autowonder/upgrade-rollback-backup.tar.gz
test -f "$backup_archive"
restore_dir=$(mktemp -d /opt/autowonder/.upgrade-restore.XXXXXX)
cleanup() { rm -rf "$restore_dir"; }
trap cleanup EXIT
tar -xzf "$backup_archive" -C "$restore_dir"
(cd "$restore_dir" && sha256sum -c CHECKSUMS >/dev/null)
release_name=$(cat "$restore_dir/release-name")
case "$release_name" in (*[!0-9a-f]*|"") exit 1;; esac
restore_target=/opt/autowonder/releases/$release_name
if test -d "$restore_target"; then
  test "$(sha256sum "$restore_target/auto-wonder.jar" | awk "{print \$1}")" = "$(sha256sum "$restore_dir/release/auto-wonder.jar" | awk "{print \$1}")"
else
  mv "$restore_dir/release" "$restore_target"
fi
install -m 0640 -o root -g autowonder "$restore_dir/autowonder.env" /etc/autowonder/autowonder.env.tmp
mv -f /etc/autowonder/autowonder.env.tmp /etc/autowonder/autowonder.env
install -m 0644 -o root -g root "$restore_dir/autowonder.service" /etc/systemd/system/autowonder.service.tmp
mv -f /etc/systemd/system/autowonder.service.tmp /etc/systemd/system/autowonder.service
ln -sfn "$restore_target" /opt/autowonder/current.new
mv -Tf /opt/autowonder/current.new /opt/autowonder/current
systemctl daemon-reload
systemctl restart autowonder.service
for attempt in $(seq 1 60); do
  body=$(curl --fail --silent --connect-timeout 2 --max-time 5 http://127.0.0.1:7001/checkpreload.htm 2>/dev/null || true)
  if systemctl is-active --quiet autowonder.service && test "$body" = success; then
    printf "ROLLBACK_STATUS=passed\nACTIVE_RELEASE=%s\n" "$restore_target"
    exit 0
  fi
  sleep 2
done
exit 1'); then
        rollback_nodes=$(jq --arg instance "$instance" '. + [{instanceId:$instance,status:"failed"}]' <<<"$rollback_nodes")
        atomic_jq "$manifest" --argjson nodes "$rollback_nodes" --arg failedInstanceId "$instance" \
          '.upgrade.rollback={status:"partial",nodes:$nodes,failedInstanceId:$failedInstanceId,confirmed:true,failedAt:(now|todateiso8601)} | .phase="rollback" | .status="failed"'
        die "rollback failed on one ECS; partial rollback state was recorded and no further nodes were changed"
      fi
      invocation=$(jq -r '.invocationId' <<<"$result")
      output=$(jq -r '.output' <<<"$result")
      rollback_status=$(sed -n 's/^ROLLBACK_STATUS=//p' <<<"$output" | tail -1)
      active_release=$(sed -n 's/^ACTIVE_RELEASE=//p' <<<"$output" | tail -1)
      if [[ "$rollback_status" != passed ]]; then
        rollback_nodes=$(jq --arg instance "$instance" '. + [{instanceId:$instance,status:"failed"}]' <<<"$rollback_nodes")
        atomic_jq "$manifest" --argjson nodes "$rollback_nodes" --arg failedInstanceId "$instance" \
          '.upgrade.rollback={status:"partial",nodes:$nodes,failedInstanceId:$failedInstanceId,confirmed:true,failedAt:(now|todateiso8601)} | .phase="rollback" | .status="failed"'
        die "rollback returned invalid evidence; partial rollback state was recorded"
      fi
      rollback_nodes=$(jq --arg instance "$instance" --arg invocation "$invocation" --arg release "$active_release" \
        '. + [{instanceId:$instance,invocationId:$invocation,activeRelease:$release,status:"passed"}]' <<<"$rollback_nodes")
    done
    atomic_jq "$manifest" --argjson nodes "$rollback_nodes" \
      '.upgrade.rollback={status:"passed",nodes:$nodes,confirmed:true,completedAt:(now|todateiso8601)} | .deployment.activeCommit=.upgrade.fromCommit | .upgradeInventory.status="stale" | .phase="rollback" | .status="ready"'
    ;;
  runtime-config)
    require_file "$env_file"; require_mode_600 "$env_file"; require_command openssl; require_command terraform
    [[ -d "$terraform_dir" ]] || die "runtime-config requires --terraform-dir"
    validate_env_file_syntax "$env_file"
    if [[ $(jq -r '.mode // empty' "$manifest") == upgrade ]]; then
      jq -e '
        .upgrade.environment as $environment |
        if (($environment.added | type) != "array" or
            ($environment.required | type) != "array") then false
        else
          (all($environment.required[];
            type == "string" and test("^[A-Z][A-Z0-9_]*$"))) and
          (($environment.required | unique | length) == ($environment.required | length)) and
          (($environment.required - $environment.added) | length == 0)
        end
      ' "$manifest" >/dev/null ||
        die "upgrade environment requirement contract is missing or invalid; regenerate the upgrade plan"
    fi
    load_alicloud_profile_credentials "$region"
    terraform_identity_file=$(mktemp); TEMP_FILES+=("$terraform_identity_file"); chmod 600 "$terraform_identity_file"
    terraform -chdir="$terraform_dir" output -json expected_tags >"$terraform_identity_file" || \
      die "cannot bind Terraform state to this deployment"
    jq -e --arg deployment "$(json_string "$manifest" '.deploymentId')" \
      --arg environment "$(json_string "$manifest" '.environment')" \
      '.DeploymentId == $deployment and .Environment == $environment and .Project == "AutoWonder" and .ManagedBy == "Terraform"' \
      "$terraform_identity_file" >/dev/null || die "Terraform state belongs to a different deployment"
    application_access_key_id_file=$(mktemp); application_access_key_secret_file=$(mktemp)
    TEMP_FILES+=("$application_access_key_id_file" "$application_access_key_secret_file")
    chmod 600 "$application_access_key_id_file" "$application_access_key_secret_file"
    terraform -chdir="$terraform_dir" output -raw application_access_key_id >"$application_access_key_id_file" || \
      die "cannot read Terraform application AccessKey ID output"
    terraform -chdir="$terraform_dir" output -raw application_access_key_secret >"$application_access_key_secret_file" || \
      die "cannot read Terraform application AccessKey secret output"
    [[ -s "$application_access_key_id_file" && -s "$application_access_key_secret_file" ]] || \
      die "Terraform application AccessKey outputs must be non-empty"
    application_access_key_id=$(<"$application_access_key_id_file")
    application_access_key_secret=$(<"$application_access_key_secret_file")
    [[ "$application_access_key_id" != *$'\n'* && "$application_access_key_id" != *$'\r'* ]] || \
      die "Terraform application AccessKey ID output is malformed"
    [[ "$application_access_key_secret" != *$'\n'* && "$application_access_key_secret" != *$'\r'* ]] || \
      die "Terraform application AccessKey secret output is malformed"
    application_access_key_id_quoted=$(printf '%s' "$application_access_key_id" | jq -Rr @sh)
    application_access_key_secret_quoted=$(printf '%s' "$application_access_key_secret" | jq -Rr @sh)
    normalized_env=$(mktemp "${env_file}.tmp.XXXXXX"); TEMP_FILES+=("$normalized_env")
    seen_oss_id=false; seen_oss_secret=false; seen_sls_id=false; seen_sls_secret=false
    while IFS= read -r line || [[ -n "$line" ]]; do
      case "$line" in
        OSS_ACCESS_KEY_ID=*) [[ "$seen_oss_id" == true ]] || printf 'OSS_ACCESS_KEY_ID=%s\n' "$application_access_key_id_quoted"; seen_oss_id=true;;
        OSS_ACCESS_KEY_SECRET=*) [[ "$seen_oss_secret" == true ]] || printf 'OSS_ACCESS_KEY_SECRET=%s\n' "$application_access_key_secret_quoted"; seen_oss_secret=true;;
        SLS_ACCESS_KEY_ID=*) [[ "$seen_sls_id" == true ]] || printf 'SLS_ACCESS_KEY_ID=%s\n' "$application_access_key_id_quoted"; seen_sls_id=true;;
        SLS_ACCESS_KEY_SECRET=*) [[ "$seen_sls_secret" == true ]] || printf 'SLS_ACCESS_KEY_SECRET=%s\n' "$application_access_key_secret_quoted"; seen_sls_secret=true;;
        *) printf '%s\n' "$line";;
      esac
    done <"$env_file" >"$normalized_env"
    [[ "$seen_oss_id" == true ]] || printf 'OSS_ACCESS_KEY_ID=%s\n' "$application_access_key_id_quoted" >>"$normalized_env"
    [[ "$seen_oss_secret" == true ]] || printf 'OSS_ACCESS_KEY_SECRET=%s\n' "$application_access_key_secret_quoted" >>"$normalized_env"
    [[ "$seen_sls_id" == true ]] || printf 'SLS_ACCESS_KEY_ID=%s\n' "$application_access_key_id_quoted" >>"$normalized_env"
    [[ "$seen_sls_secret" == true ]] || printf 'SLS_ACCESS_KEY_SECRET=%s\n' "$application_access_key_secret_quoted" >>"$normalized_env"
    chmod 600 "$normalized_env"
    mv -f -- "$normalized_env" "$env_file"
    unset application_access_key_id application_access_key_secret application_access_key_id_quoted application_access_key_secret_quoted
    unset application_access_key_id_file application_access_key_secret_file normalized_env line
    unset seen_oss_id seen_oss_secret seen_sls_id seen_sls_secret
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
    release_version=$(jq -er '
      .releaseVersion // empty |
      select(test("^[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?$"))
    ' "$manifest") || die "releaseVersion must be a semantic version"
    normalized_env=$(mktemp "${env_file}.tmp.XXXXXX"); TEMP_FILES+=("$normalized_env")
    awk -v key="AUTOWONDER_VERSION" -v value="$release_version" '
      index($0, key "=") == 1 { if (!written) print key "=" value; written=1; next }
      { print }
      END { if (!written) print key "=" value }
    ' "$env_file" >"$normalized_env"
    chmod 600 "$normalized_env"
    mv -f -- "$normalized_env" "$env_file"
    unset recommended_runtime_version release_version normalized_env
    for key in SPRING_DATASOURCE_URL SPRING_DATASOURCE_USERNAME SPRING_DATASOURCE_PASSWORD REDIS_HOST REDIS_PORT REDIS_PASSWORD OSS_ENDPOINT OSS_PUBLIC_ENDPOINT OSS_BUCKET OSS_ACCESS_KEY_ID OSS_ACCESS_KEY_SECRET AUTOWONDER_SECRET_MASTER_KEY AUTOWONDER_JWT_SECRET AUTOWONDER_PUBLIC_BASE_URL AUTOWONDER_VERSION AUTOWONDER_RUNTIME_RECOMMENDED_VERSION SLS_ENDPOINT SLS_PROJECT SLS_SYS_LOGSTORE SLS_BIZ_LOGSTORE SLS_METRIC_LOGSTORE SLS_ACCESS_KEY_ID SLS_ACCESS_KEY_SECRET; do
      grep -q "^${key}=" "$env_file" || die "required environment key missing: $key"
      require_nonempty_env "$env_file" "$key"
    done
    if [[ $(jq -r '.mode // empty' "$manifest") == upgrade ]]; then
      while IFS= read -r key; do
        [[ -n "$key" ]] || continue
        grep -q "^${key}=" "$env_file" || die "new required upgrade environment key missing: $key"
        require_nonempty_env "$env_file" "$key"
      done < <(jq -r '.upgrade.environment.required[]' "$manifest")
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
      --arg applicationVersion "$(unquote_simple "$(env_raw_value "$env_file" AUTOWONDER_VERSION)")" \
      --arg envHash "$(sha256_file "$env_file")" \
      --arg envFile "$(cd -- "$(dirname -- "$env_file")" && pwd -P)/$(basename -- "$env_file")" \
      --arg terraformDir "$(cd -- "$terraform_dir" && pwd -P)" \
      '.runtimeConfig={prepared:true,fileMode:"0600",valuesValidated:true,recommendedRuntimeVersion:$version,applicationVersion:$applicationVersion,applicationCredentialSource:"terraform-sensitive-outputs",envSha256:$envHash}
       | .localContext.protectedEnvFile=$envFile | .localContext.terraformDirectory=$terraformDir
       | (if .mode == "upgrade" then .upgrade.environmentValidated=true | .upgrade.environmentCandidateSha256=$envHash else . end)
       | .phase="runtime-config" | .status="prepared"'
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
    jq -e '
      .upgrade.databaseBackup.status == "verified" and
      (.upgrade.databaseBackup.backupId | type == "string" and length > 0) and
      (.upgrade.databaseBackup.rdsInstanceId == (.resources.rds_instance_id // .resources.rds.instance_id)) and
      ((now - (.upgrade.databaseBackup.verifiedEpoch // 0)) <= 86400)
    ' "$manifest" >/dev/null || die "recent live-verified database backup evidence for this RDS instance is required"
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
    done
    atomic_jq "$manifest" --argjson ids "$invocation_json" --argjson nodes "$node_json" \
      '.rollingUpgrade={status:"passed",invocationIds:$ids,nodes:$nodes,nodeOrder:"sequential",targetCommit:.repositoryCommit} |
       .deployment.activeCommit=.repositoryCommit | .deployment.acceptedCommit=.repositoryCommit |
       .deployment.acceptedAt=(now|todateiso8601) | .upgradeInventory.status="stale" |
       .acceptance={health:"passed",ecsLocalHealth:"passed"} | .phase="acceptance" | .status="accepted"'
    ;;
  rolling-start)
    base_url=$(jq -er '.applicationBaseUrl // empty' "$manifest") || die "applicationBaseUrl missing"
    alb_id=$(jq -er '.resources.alb_id // empty' "$manifest") || die "ALB ID is required for DNS-independent rolling checks"
    listeners_file=$(mktemp); TEMP_FILES+=("$listeners_file"); chmod 600 "$listeners_file"
    aliyun_cli alb ListListeners --region "$region" --MaxResults 100 >"$listeners_file" || die "ALB listener inventory failed"
    listener_id=$(jq -er --arg alb "$alb_id" '[.Listeners[] | select(.LoadBalancerId == $alb and .ListenerProtocol == "HTTP") | .ListenerId] | if length == 1 then .[0] else empty end' "$listeners_file") || die "exactly one HTTP listener is required"
    activation_instances=()
    while IFS= read -r instance; do activation_instances+=("$instance"); done < <(jq -r '(.scaling.pendingInstanceIds // [])[]' "$manifest")
    if ((${#activation_instances[@]} == 0)); then activation_instances=("${instances[@]}"); fi
    for instance in "${activation_instances[@]}"; do
      jq -e --arg id "$instance" '(.resources.ecs_instance_ids // .resources.ecsInstanceIds) | [.[]] | index($id) != null' "$manifest" >/dev/null \
        || die "pending scale-out node is absent from current ECS inventory"
    done
    invocation_json='[]'
    for instance in "${activation_instances[@]}"; do
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
      alb_healthy=false
      for attempt in $(seq 1 18); do
        health_file=$(mktemp); TEMP_FILES+=("$health_file"); chmod 600 "$health_file"
        if aliyun_cli alb GetListenerHealthStatus --region "$region" --ListenerId "$listener_id" >"$health_file" \
          && jq -e '.ListenerHealthStatus | length > 0 and all(.[]; all(.ServerGroupInfos[]?; ((.NonNormalServers // []) | length) == 0))' "$health_file" >/dev/null; then
          alb_healthy=true; break
        fi
        sleep 10
      done
      [[ "$alb_healthy" == true ]] || die "ALB listener health did not converge after node activation"
    done
    activated_json=$(printf '%s\n' "${activation_instances[@]}" | jq -Rsc 'split("\n") | map(select(length > 0))')
    atomic_jq "$manifest" --argjson ids "$invocation_json" --argjson activated "$activated_json" \
      '.rollingStart={status:"passed",invocationIds:$ids,nodeOrder:"sequential"}
       | .scaling.configuredInstanceIds=(((.scaling.configuredInstanceIds // []) + $activated) | unique)
       | .scaling.pendingInstanceIds=[]
       | .scaling.lastReconciledAt=(now|todateiso8601)
       | .scaling.lastEnvironmentSha256=(.runtimeConfig.envSha256 // .deployment.lastRun.envSha256 // null)
       | .deployment.activeCommit=.repositoryCommit | .phase="application" | .status="ready"'
    ;;
  business-init)
    require_command openssl
    jq -e '.applicationBaseUrl | type == "string" and length > 0' "$manifest" >/dev/null || die "applicationBaseUrl missing"
    handoff_file=${handoff_file:-"$(dirname "$manifest")/.autowonder-admin-handoff.json"}
    reconcile_orphan_admin() {
      local result deleted
      result=$(run_cloud "${instances[0]}" "$remote_db_prelude
deleted=\$(mysql -h \"\$host\" -P \"\$port\" -u \"\$SPRING_DATASOURCE_USERNAME\" \"\$database\" -N -e \"START TRANSACTION; DELETE u FROM user u WHERE u.username='admin' AND NOT EXISTS (SELECT 1 FROM org o WHERE o.owner_id=u.id) AND NOT EXISTS (SELECT 1 FROM org_member m WHERE m.user_id=u.id); SELECT ROW_COUNT(); COMMIT;\")
test \"\$deleted\" = 1
printf 'ORPHAN_ADMIN_RECONCILED=1\\n'")
      deleted=$(jq -r '.output' <<<"$result" | sed -n 's/^ORPHAN_ADMIN_RECONCILED=//p' | tail -1)
      [[ "$deleted" == 1 ]] || die "existing administrator is not a safe orphan; manual reconciliation required"
    }
    if [[ $(jq -r '.business.adminCreated // false' "$manifest") != true ]]; then
      private_key=$(mktemp); public_key=$(mktemp); TEMP_FILES+=("$private_key" "$public_key"); chmod 600 "$private_key" "$public_key"
      openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$private_key" >/dev/null 2>&1
      openssl pkey -in "$private_key" -pubout -out "$public_key" >/dev/null 2>&1
      public_key_b64=$(base64 <"$public_key" | tr -d '\r\n')
      organization_b64=$(printf '%s' "$(json_string "$manifest" '.organizationName')" | base64 | tr -d '\r\n')
      run_business_initialization() {
        run_cloud "${instances[0]}" "set -euo pipefail
work=\$(mktemp -d); trap 'rm -rf -- \"\$work\"' EXIT
printf '%s' '$public_key_b64' | base64 -d >\"\$work/public.pem\"
organization=\$(printf '%s' '$organization_b64' | base64 -d)
for attempt in \$(seq 1 20); do
  password=\$(openssl rand -base64 48 | tr -dc 'A-Za-z0-9!@#%^+=' | head -c 32)
  if [[ \"\$password\" =~ [A-Z] && \"\$password\" =~ [a-z] && \"\$password\" =~ [0-9] && \"\$password\" =~ [!@#%^+=] ]]; then break; fi
done
[[ \"\$password\" =~ [A-Z] && \"\$password\" =~ [a-z] && \"\$password\" =~ [0-9] && \"\$password\" =~ [!@#%^+=] ]]
jq -n --arg password \"\$password\" '{username:\"admin\",password:\$password,email:\"admin@localhost.invalid\",nickname:\"Administrator\"}' >\"\$work/register.json\"
code=\$(curl --silent --output \"\$work/register-response.json\" --write-out '%{http_code}' -H 'Content-Type: application/json' --data-binary @\"\$work/register.json\" http://127.0.0.1:7001/api/auth/register || true)
if test \"\$code\" = 409; then printf 'BUSINESS_STATUS=conflict\\n'; exit 0; fi
case \"\$code\" in 2??) ;; *) exit 1;; esac
jq -e '.success == true' \"\$work/register-response.json\" >/dev/null
jq '{username,password}' \"\$work/register.json\" >\"\$work/login.json\"
curl --fail --silent -H 'Content-Type: application/json' --data-binary @\"\$work/login.json\" http://127.0.0.1:7001/api/auth/login >\"\$work/login-response.json\"
token=\$(jq -er 'select(.success == true) | .data.accessToken' \"\$work/login-response.json\")
jq -n --arg name \"\$organization\" '{name:\$name,description:\"AutoWonder community deployment\",background:\"\"}' >\"\$work/org.json\"
curl --fail --silent -H 'Content-Type: application/json' -H \"Authorization: Bearer \$token\" --data-binary @\"\$work/org.json\" http://127.0.0.1:7001/api/orgs >\"\$work/org-response.json\"
jq -e '.success == true' \"\$work/org-response.json\" >/dev/null
jq -c '{username,password}' \"\$work/register.json\" | openssl pkeyutl -encrypt -pubin -inkey \"\$work/public.pem\" -pkeyopt rsa_padding_mode:oaep -pkeyopt rsa_oaep_md:sha256 | base64 | tr -d '\\r\\n' | sed 's/^/HANDOFF_CIPHERTEXT=/'
printf '\\nBUSINESS_STATUS=passed\\n'"
      }
      result=$(run_business_initialization)
      if [[ $(jq -r '.output' <<<"$result") == *BUSINESS_STATUS=conflict* ]]; then
        reconcile_orphan_admin
        result=$(run_business_initialization)
      fi
      output=$(jq -r '.output' <<<"$result")
      [[ "$output" == *BUSINESS_STATUS=passed* ]] || die "business initialization failed"
      ciphertext=$(sed -n 's/^HANDOFF_CIPHERTEXT=//p' <<<"$output" | tail -1)
      [[ "$ciphertext" =~ ^[A-Za-z0-9+/]+={0,2}$ ]] || die "encrypted administrator handoff is invalid"
      handoff_tmp=$(mktemp "${handoff_file}.tmp.XXXXXX"); TEMP_FILES+=("$handoff_tmp"); chmod 600 "$handoff_tmp"
      printf '%s' "$ciphertext" | decode_b64 | openssl pkeyutl -decrypt -inkey "$private_key" -pkeyopt rsa_padding_mode:oaep -pkeyopt rsa_oaep_md:sha256 >"$handoff_tmp"
      jq -e '.username == "admin" and (.password | type == "string" and length >= 20)' "$handoff_tmp" >/dev/null || die "decrypted administrator handoff is invalid"
      mv -f -- "$handoff_tmp" "$handoff_file"; chmod 600 "$handoff_file"
      unset ciphertext organization_b64 output public_key_b64 result
      atomic_jq "$manifest" '.business.adminCreated=true | .business.organizationCreated=true'
    else
      require_file "$handoff_file"; require_mode_600 "$handoff_file"
    fi
    atomic_jq "$manifest" '.phase="business-init" | .status="initialized" | .business.status="passed"'
    ;;
  acceptance)
    if [[ "$operation_scope" == upgrade ]]; then
      require_upgrade_acceptance_state "$manifest"
      atomic_jq "$manifest" '
        .acceptance={health:"passed",ecsLocalHealth:"passed"} |
        .phase="acceptance" | .status="accepted" |
        .deployment.acceptedCommit=.repositoryCommit |
        .deployment.acceptedAt=(.deployment.acceptedAt // (now|todateiso8601))'
      exit 0
    fi
    jq -e '.applicationBaseUrl | type == "string" and length > 0' "$manifest" >/dev/null || die "applicationBaseUrl missing"
    if [[ "$operation_scope" == deployment ]]; then
      public_ips=()
      while IFS= read -r public_ip; do public_ips+=("$public_ip"); done < <(jq -er '(.resources.alb_public_ipv4_addresses // .resources.albPublicIpv4Addresses) | unique[]' "$manifest")
      ((${#public_ips[@]} == 2)) || die "deployment acceptance requires exactly two distinct ALB public IPv4 addresses"
      for public_ip in "${public_ips[@]}"; do
        [[ "$public_ip" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]] || die "invalid ALB public IPv4 address"
        IFS=. read -r octet_a octet_b octet_c octet_d <<<"$public_ip"
        ((10#$octet_a <= 255 && 10#$octet_b <= 255 && 10#$octet_c <= 255 && 10#$octet_d <= 255)) || die "invalid ALB public IPv4 address"
        [[ $(curl --fail --silent --connect-timeout 5 --max-time 10 "http://$public_ip/checkpreload.htm") == success ]] || \
          die "ALB public IPv4 health check failed"
      done
      atomic_jq "$manifest" '
        .acceptance=((.acceptance // {}) + {health:"passed",albPublicIpv4Health:"passed"}) |
        .phase="acceptance" | .status="accepted" |
        .deployment.acceptedCommit=.repositoryCommit |
        .deployment.acceptedAt=(now|todateiso8601)'
      exit 0
    fi
    basic=$(run_cloud "${instances[0]}" 'set -euo pipefail
test "$(curl --fail --silent --connect-timeout 5 --max-time 10 http://127.0.0.1:7001/checkpreload.htm)" = success
curl --fail --silent --connect-timeout 5 --max-time 10 http://127.0.0.1:7001/api/platform/branding/public >/dev/null
capabilities=$(mktemp); trap '\''rm -f -- "$capabilities"'\'' EXIT
curl --fail --silent http://127.0.0.1:7001/api/integrations/capabilities >"$capabilities"
jq -e '\''[.aoneEnabled, .data.aoneEnabled] | any(. == false)'\'' "$capabilities" >/dev/null
printf '\''BASIC_ACCEPTANCE=passed\n'\''')
    [[ $(jq -r '.output' <<<"$basic") == *BASIC_ACCEPTANCE=passed* ]] || die "node-local basic acceptance failed"
    protocol=$(jq -r '.ingressScenario' "$manifest")
    tls=false
    if [[ "$protocol" == domain-with-certificate ]]; then
      alb_id=$(jq -er '.resources.alb_id // empty' "$manifest") || die "ALB ID missing for TLS acceptance"
      listeners_file=$(mktemp); TEMP_FILES+=("$listeners_file"); chmod 600 "$listeners_file"
      aliyun_cli alb ListListeners --region "$region" --MaxResults 100 >"$listeners_file" || die "ALB listener inventory failed"
      expected_certificate=$(jq -er '.tls.certificateId // empty' "$manifest") || die "certificate ID missing for TLS acceptance"
      https_listener=$(jq -er --arg alb "$alb_id" '[.Listeners[] | select(.LoadBalancerId == $alb and .ListenerProtocol == "HTTPS") | .ListenerId] | if length == 1 then .[0] else empty end' "$listeners_file") || die "exactly one HTTPS listener is required"
      listener_file=$(mktemp); TEMP_FILES+=("$listener_file"); chmod 600 "$listener_file"
      aliyun_cli alb GetListenerAttribute --region "$region" --ListenerId "$https_listener" >"$listener_file" || die "HTTPS listener lookup failed"
      jq -e --arg certificate "$expected_certificate" '.ListenerPort == 443 and (([.Certificates[]?.CertificateId] | index($certificate)) != null)' "$listener_file" >/dev/null || die "HTTPS listener certificate binding is invalid"
      tls=true
    fi
    runtime_status=$(jq -r '.acceptance.runtimeWebSocket // "pending"' "$manifest")
    if [[ -n ${AUTOWONDER_RUNTIME_PROBE:-} ]]; then require_file "$AUTOWONDER_RUNTIME_PROBE"; "$AUTOWONDER_RUNTIME_PROBE" "$manifest"; runtime_status=passed; fi
    evidence='{}'
    if [[ -n "$acceptance_evidence" ]]; then
      require_file "$acceptance_evidence"; require_mode_600 "$acceptance_evidence"; json_validate "$acceptance_evidence"; reject_secret_keys "$acceptance_evidence"
      jq -e '
        type == "object" and
        ([keys[] | select(. != "databasePersistence" and . != "redisPersistence" and . != "ossApplicationPath" and . != "encryptedCredentialRestart" and . != "slsThreeStores" and . != "rollingRestart" and . != "ecsReboot" and . != "runtimeWebSocket" and . != "tags" and . != "secretLogScan")] | length == 0) and
        all(.[]; . == "passed" or . == "failed" or . == "degraded" or . == "pending")
      ' "$acceptance_evidence" >/dev/null || die "acceptance evidence contains unsupported checks or statuses"
      evidence=$(jq -c . "$acceptance_evidence")
    fi
    atomic_jq "$manifest" --arg runtime "$runtime_status" --argjson tls "$tls" --argjson evidence "$evidence" '
      ({health:"pending",capabilities:"pending",databasePersistence:"pending",redisPersistence:"pending",ossApplicationPath:"pending",encryptedCredentialRestart:"pending",slsThreeStores:"pending",rollingRestart:"pending",ecsReboot:"pending",runtimeWebSocket:"pending",tags:"pending",secretLogScan:"pending",tlsAccepted:false} + (.acceptance // {}) + $evidence + {health:"passed",capabilities:"passed",runtimeWebSocket:($evidence.runtimeWebSocket // $runtime),tlsAccepted:$tls}) as $checks |
      .acceptance=$checks |
      .phase="acceptance" |
      .status=(
        [.acceptance | to_entries[] | select(.key != "tlsAccepted") | .value] as $statuses |
        if any($statuses[]; . == "failed" or . == "degraded") then "failed"
        elif all($statuses[]; . == "passed") then "accepted"
        else "partial" end
      ) |
      if .status == "accepted" then
        .deployment.acceptedCommit=.repositoryCommit | .deployment.acceptedAt=(now|todateiso8601)
      else . end'
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
