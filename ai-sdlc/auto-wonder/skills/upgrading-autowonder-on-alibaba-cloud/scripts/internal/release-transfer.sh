#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
DEPLOY_SKILL_DIR=$(cd -- "$SCRIPT_DIR/../../../deploying-autowonder-on-alibaba-cloud" && pwd)
source "$DEPLOY_SKILL_DIR/scripts/lib.sh"

transfer_scope=${AUTOWONDER_TRANSFER_SCOPE:-}
[[ "$transfer_scope" == deployment || "$transfer_scope" == upgrade ]] || \
  die "internal transfer requires a bounded skill entrypoint"

usage() { cat <<'EOF'
Usage: deploy-via-cloud-assistant.sh --manifest FILE --env-file FILE [--release-dir DIR]
       [--unit-file FILE] [--java-archive FILE] [--config-only|--stage-only] [--dry-run]
Deploys through private OSS and Alibaba Cloud Assistant. Secret values belong only in the mode-600 env file.
EOF
}

manifest= release_dir= env_file= unit_file="$DEPLOY_SKILL_DIR/assets/systemd/autowonder.service" unit_file_explicit=false java_archive= config_only=false stage_only=false dry_run=false
require_no_secret_args "$@"
while (($#)); do
  case "$1" in
    --manifest) manifest=${2:-}; shift 2;;
    --release-dir) release_dir=${2:-}; shift 2;;
    --env-file) env_file=${2:-}; shift 2;;
    --unit-file) unit_file=${2:-}; unit_file_explicit=true; shift 2;;
    --java-archive) java_archive=${2:-}; shift 2;;
    --config-only) config_only=true; shift;;
    --stage-only) stage_only=true; shift;;
    --dry-run) dry_run=true; shift;;
    --help|-h) usage; exit 0;;
    *) die "unknown argument";;
  esac
done
[[ "$config_only" == false || "$stage_only" == false ]] || die "config-only and stage-only are mutually exclusive"
[[ "$transfer_scope" != deployment || "$stage_only" == false ]] || die "stage-only is outside the deployment skill boundary"
[[ "$transfer_scope" != upgrade || "$stage_only" == true ]] || die "upgrade transfer must be stage-only"
require_file "$manifest"; require_file "$env_file"
if [[ -z "$release_dir" ]]; then release_dir=$(jq -r '.artifacts.releaseDirectory // empty' "$manifest"); fi
if [[ -z ${unit_file_explicit:-} && -n "$release_dir" && -f "$release_dir/autowonder.service" ]]; then
  unit_file="$release_dir/autowonder.service"
fi
if [[ "$config_only" == false ]]; then
  require_file "$release_dir/auto-wonder.jar"; require_file "$release_dir/autowonder-schema.sql"
  require_file "$release_dir/autowonder-community-templates.sql"
  require_file "$release_dir/autowonder-migrations.tar.gz"; require_file "$unit_file"
fi
require_mode_600 "$env_file"; require_command jq
json_validate "$manifest"; reject_secret_keys "$manifest"
if [[ "$stage_only" == true && $(jq -r '.mode // empty' "$manifest") == upgrade ]]; then
  [[ $(jq -r '.upgrade.environmentContractChecked // false' "$manifest") == true ]] || die "candidate upgrade environment must be checked by plan-upgrade.sh --env-file before staging"
  [[ $(jq -r '.upgrade.environmentValidated // false' "$manifest") == true ]] || die "candidate upgrade environment must be validated by plan-upgrade.sh --env-file before staging"
  [[ $(jq -r '(.upgrade.blockedReasons // []) | length' "$manifest") == 0 ]] || die "blocked upgrade plan cannot be staged"
  [[ $(jq -r '.upgrade.rollbackBackup.status // empty' "$manifest") == passed ]] || die "verified per-ECS rollback backup is required before upgrade staging"
  [[ $(jq -r '.upgrade.rollbackBackup.targetCommit // empty' "$manifest") == $(jq -r '.repositoryCommit' "$manifest") ]] || die "rollback backup does not match the upgrade target"
fi
configure_cloud_profile "$manifest"
region=$(json_string "$manifest" '.region'); deployment_id=$(json_string "$manifest" '.deploymentId')
commit=$(json_string "$manifest" '.repositoryCommit'); short_commit=${commit:0:12}
if [[ "$transfer_scope" == upgrade ]]; then
  UPGRADE_SKILL_DIR=$(cd -- "$SCRIPT_DIR/../../../upgrading-autowonder-on-alibaba-cloud" && pwd)
  source "$UPGRADE_SKILL_DIR/scripts/upgrade-lib.sh"
  require_upgrade_approval "$manifest"
fi
bucket=$(jq -er '.resources.package_bucket // .resources.packageBucket // empty' "$manifest") || die "package bucket missing from inventory"
all_instances=()
while IFS= read -r instance; do all_instances+=("$instance"); done < <(jq -er '(.resources.ecs_instance_ids // .resources.ecsInstanceIds)[]' "$manifest")
((${#all_instances[@]} > 0)) || die "ECS inventory is empty"
instances=()
while IFS= read -r instance; do instances+=("$instance"); done < <(jq -r '(.scaling.pendingInstanceIds // [])[]' "$manifest")
target_scope=all-inventory
if [[ "$transfer_scope" == upgrade && ${#instances[@]} -gt 0 ]]; then
  die "upgrade cannot start while scale-out has pending instances"
fi
if ((${#instances[@]} > 0)); then
  target_scope=pending-scale-out
  for instance in "${instances[@]}"; do
    jq -e --arg id "$instance" '(.resources.ecs_instance_ids // .resources.ecsInstanceIds) | [.[]] | index($id) != null' "$manifest" >/dev/null \
      || die "pending scale-out node is absent from current ECS inventory"
  done
else
  instances=("${all_instances[@]}")
fi
jar_hash= unit_hash= schema_hash= templates_hash= migrations_hash= java_hash=
env_hash=$(sha256_file "$env_file")
if [[ "$stage_only" == true && $(jq -r '.mode // empty' "$manifest") == upgrade ]]; then
  [[ $(jq -r '.upgrade.environmentCandidateSha256 // empty' "$manifest") == "$env_hash" ]] || die "candidate upgrade environment changed after validation"
fi
if [[ "$config_only" == false ]]; then
  jar_hash=$(sha256_file "$release_dir/auto-wonder.jar"); unit_hash=$(sha256_file "$unit_file")
  schema_hash=$(sha256_file "$release_dir/autowonder-schema.sql")
  templates_hash=$(sha256_file "$release_dir/autowonder-community-templates.sql")
  migrations_hash=$(sha256_file "$release_dir/autowonder-migrations.tar.gz")
  if [[ "$transfer_scope" == upgrade ]]; then
    [[ $(jq -r '.artifacts.systemdUnit.sha256 // empty' "$manifest") == "$unit_hash" ]] || \
      die "staged systemd unit does not match the target release artifact"
    [[ $(jq -r '.artifacts.systemdUnit.source // empty' "$manifest") == target-source ]] || \
      die "upgrade systemd unit was not built from the exact target source"
  fi
  [[ -z "$java_archive" ]] || { require_file "$java_archive"; java_hash=$(sha256_file "$java_archive"); }
fi
prefix="deployments/${deployment_id}/${short_commit}-$(date -u +%Y%m%dT%H%M%SZ)-$$"
control_endpoint=$(jq -r '.resources.oss.control_endpoint // .resources.oss.public_endpoint // empty' "$manifest")
runtime_endpoint=$(jq -r '.resources.oss.runtime_endpoint // .resources.oss.vpc_endpoint // empty' "$manifest")
control_endpoint=${control_endpoint:-"oss-${region}.aliyuncs.com"}
runtime_endpoint=${runtime_endpoint:-"oss-${region}-internal.aliyuncs.com"}

if [[ "$dry_run" == true ]]; then
  mode=full
  if [[ "$config_only" == true ]]; then mode=config-only
  elif [[ "$stage_only" == true ]]; then mode=stage-only; fi
  jq -n --arg prefix "$prefix" --arg controlEndpoint "$control_endpoint" --arg runtimeEndpoint "$runtime_endpoint" --arg mode "$mode" --arg targetScope "$target_scope" --argjson nodes "${#instances[@]}" \
    '{phase:"deploy",status:"planned",mode:$mode,targetScope:$targetScope,privateStagingPrefix:$prefix,controlEndpoint:$controlEndpoint,runtimeEndpoint:$runtimeEndpoint,nodeCount:$nodes}'
  exit 0
fi
require_command aliyun; require_command ossutil; export ALIBABA_CLOUD_REGION_ID="$region"
ossutil_preflight "$region"

STAGING_TARGETS=()
CLEANUP_STAGING_ON_EXIT=false
cleanup_staging_objects() {
  local target
  set +e
  for target in ${STAGING_TARGETS[@]+"${STAGING_TARGETS[@]}"}; do
    ossutil_remove "$target" "$control_endpoint" "$region" >/dev/null 2>&1 || true
  done
  set -e
}
transfer_cleanup() {
  if [[ "$CLEANUP_STAGING_ON_EXIT" == true ]]; then
    cleanup_staging_objects
  elif [[ -n ${STAGING_TARGETS+x} ]] && ((${#STAGING_TARGETS[@]} > 0)) && [[ -f ${manifest:-} ]]; then
    atomic_jq "$manifest" --arg prefix "$prefix" \
      '.deployment.stagingRecovery={prefix:$prefix,stagingCleaned:false,cleanupRequired:true,reason:"remote-state-unknown-or-transfer-failed"}' || true
  fi
  cleanup
}
trap transfer_cleanup EXIT INT TERM

objects=("autowonder.env"); files=("$env_file")
if [[ "$config_only" == false ]]; then
  objects=("auto-wonder.jar" "autowonder-schema.sql" "autowonder-community-templates.sql" "autowonder-migrations.tar.gz" "autowonder.service" "autowonder.env")
  files=("$release_dir/auto-wonder.jar" "$release_dir/autowonder-schema.sql" "$release_dir/autowonder-community-templates.sql" "$release_dir/autowonder-migrations.tar.gz" "$unit_file" "$env_file")
  if [[ -n "$java_archive" ]]; then objects+=("temurin21-linux-amd64.tar.gz"); files+=("$java_archive"); fi
fi
for idx in "${!objects[@]}"; do
  ossutil_upload "${files[$idx]}" "oss://$bucket/$prefix/${objects[$idx]}" "$control_endpoint" "$region" >/dev/null
  STAGING_TARGETS+=("oss://$bucket/$prefix/${objects[$idx]}")
done

run_cloud_command() {
  local instance=$1 script=$2 response invocation result status exit_code deadline encoded_script command_content
  encoded_script=$(printf '%s' "$script" | base64 | tr -d '\r\n')
  command_content="printf '%s' '$encoded_script' | base64 -d | /usr/bin/env bash"
  response=$(aliyun_cli ecs RunCommand --region "$region" --RegionId "$region" --InstanceId.1 "$instance" \
    --Type RunShellScript --Timeout 1800 --CommandContent "$command_content") || die "Cloud Assistant submission failed"
  unset encoded_script command_content
  invocation=$(cloud_assistant_invocation_id <<<"$response") || die "Cloud Assistant invocation ID missing"
  atomic_jq "$manifest" --arg id "$invocation" --arg instance "$instance" \
    '.remoteInvocations=((.remoteInvocations // []) + [{invokeId:$id,instanceId:$instance,status:"submitted",submittedAt:(now|todateiso8601)}])'
  deadline=$((SECONDS + 1860))
  while ((SECONDS < deadline)); do
    sleep 2
    result=$(aliyun_cli ecs DescribeInvocationResults --region "$region" --RegionId "$region" --InvokeId "$invocation") || continue
    while IFS='=' read -r key value; do
      case "$key" in *PASSWORD*|*SECRET*|*TOKEN*|*KEY*)
        value=${value#\'}; value=${value%\'}; value=${value#\"}; value=${value%\"}
        if [[ ${#value} -ge 6 ]] && grep -Fq -- "$value" <<<"$result"; then die "secret detected in Cloud Assistant result"; fi;;
      esac
    done <"$env_file"
    status=$(cloud_assistant_status <<<"$result")
    case "$status" in
      Finished|Success)
        exit_code=$(cloud_assistant_exit_code <<<"$result")
        [[ "$exit_code" == 0 ]] || die "Cloud Assistant command failed"
        atomic_jq "$manifest" --arg id "$invocation" '(.remoteInvocations[] | select(.invokeId==$id)).status="finished"'
        printf '%s' "$invocation"; return 0;;
      Failed|PartialFailed|Stopped|Stopping|TimedOut|Cancelled|Invalid|Aborted|Terminated) die "Cloud Assistant invocation reached terminal failure";;
    esac
  done
  atomic_jq "$manifest" --arg id "$invocation" '(.remoteInvocations[] | select(.invokeId==$id)).status="poll-timeout"'
  die "Cloud Assistant polling timed out; remote state is unknown, inspect the recorded invocation before retrying"
}

invocations=()
jar_url= schema_url= templates_url= migrations_url= unit_url= java_url=
generate_node_urls() {
  ossutil_presign "oss://$bucket/$prefix/autowonder.env" "$runtime_endpoint" "$region"; env_url=$OSSUTIL_PRESIGNED_URL
  if [[ "$config_only" == false ]]; then
    ossutil_presign "oss://$bucket/$prefix/auto-wonder.jar" "$runtime_endpoint" "$region"; jar_url=$OSSUTIL_PRESIGNED_URL
    ossutil_presign "oss://$bucket/$prefix/autowonder-schema.sql" "$runtime_endpoint" "$region"; schema_url=$OSSUTIL_PRESIGNED_URL
    ossutil_presign "oss://$bucket/$prefix/autowonder-community-templates.sql" "$runtime_endpoint" "$region"; templates_url=$OSSUTIL_PRESIGNED_URL
    ossutil_presign "oss://$bucket/$prefix/autowonder-migrations.tar.gz" "$runtime_endpoint" "$region"; migrations_url=$OSSUTIL_PRESIGNED_URL
    ossutil_presign "oss://$bucket/$prefix/autowonder.service" "$runtime_endpoint" "$region"; unit_url=$OSSUTIL_PRESIGNED_URL
    if [[ -n "$java_archive" ]]; then
      ossutil_presign "oss://$bucket/$prefix/temurin21-linux-amd64.tar.gz" "$runtime_endpoint" "$region"; java_url=$OSSUTIL_PRESIGNED_URL
    fi
  fi
}
for instance in "${instances[@]}"; do
  generate_node_urls
  if [[ "$config_only" == true ]]; then
    remote=$(cat <<EOF
set -euo pipefail
umask 077
cleanup_env_tmp() { rm -f /etc/autowonder/autowonder.env.tmp; }
trap cleanup_env_tmp EXIT
install -d -o root -g root -m 0755 /etc/autowonder
curl --fail --silent --show-error '$env_url' -o /etc/autowonder/autowonder.env.tmp
echo '$env_hash  /etc/autowonder/autowonder.env.tmp' | sha256sum -c -
chown root:autowonder /etc/autowonder/autowonder.env.tmp && chmod 0640 /etc/autowonder/autowonder.env.tmp
mv /etc/autowonder/autowonder.env.tmp /etc/autowonder/autowonder.env
trap - EXIT
EOF
)
  else
  activation=
  if [[ "$stage_only" == false ]]; then
    activation="ln -sfn /opt/autowonder/releases/$short_commit /opt/autowonder/current.new
mv -Tf /opt/autowonder/current.new /opt/autowonder/current"
  fi
  remote=$(cat <<EOF
set -euo pipefail
umask 077
cleanup_env_tmp() { rm -f /etc/autowonder/autowonder.env.tmp; }
trap cleanup_env_tmp EXIT
test "\$(uname -m)" = x86_64
id autowonder >/dev/null 2>&1 || useradd --system --home /var/lib/autowonder --shell /sbin/nologin autowonder
if command -v apt-get >/dev/null 2>&1; then
  if ! command -v mysql >/dev/null 2>&1 || ! command -v redis-cli >/dev/null 2>&1 || ! command -v jq >/dev/null 2>&1; then
    export DEBIAN_FRONTEND=noninteractive
    apt-get update -y
    apt-get install -y default-mysql-client redis-tools jq
  fi
elif command -v dnf >/dev/null 2>&1; then
  if ! command -v mysql >/dev/null 2>&1; then dnf install -y mariadb105 || dnf install -y mariadb; fi
  if ! command -v redis-cli >/dev/null 2>&1; then dnf install -y redis6 || dnf install -y redis; fi
  if ! command -v jq >/dev/null 2>&1; then dnf install -y jq; fi
else
  echo 'supported package manager not found (apt-get or dnf)' >&2
  exit 1
fi
install -d -m 0755 /opt/autowonder
if ! /opt/autowonder/runtime/bin/java -version 2>&1 | grep -q 'version "21'; then
  if command -v apt-get >/dev/null 2>&1; then
    export DEBIAN_FRONTEND=noninteractive
    apt-get install -y openjdk-21-jre-headless
    java_bin=\$(readlink -f "\$(command -v java)")
    java_home=\${java_bin%/bin/java}
    test -x "\$java_home/bin/java"
    rm -rf /opt/autowonder/runtime
    ln -s "\$java_home" /opt/autowonder/runtime
  elif dnf list available java-21-alibaba-dragonwell-headless >/dev/null 2>&1; then
    dnf install -y java-21-alibaba-dragonwell-headless
    java_bin=\$(readlink -f "\$(command -v java)")
    java_home=\${java_bin%/bin/java}
    test -x "\$java_home/bin/java"
    rm -rf /opt/autowonder/runtime
    ln -s "\$java_home" /opt/autowonder/runtime
  fi
fi
install -d -o root -g root -m 0755 /opt/autowonder/releases/$short_commit /etc/autowonder
install -d -o autowonder -g autowonder -m 0750 /var/lib/autowonder /var/lib/autowonder/logs
if ! /opt/autowonder/runtime/bin/java -version 2>&1 | grep -q 'version "21'; then
  test -n '$java_url'
  curl --fail --silent --show-error '$java_url' -o /tmp/autowonder-java.tar.gz
  echo '$java_hash  /tmp/autowonder-java.tar.gz' | sha256sum -c -
  rm -rf /opt/autowonder/runtime/*
  tar -xzf /tmp/autowonder-java.tar.gz --strip-components=1 -C /opt/autowonder/runtime
  rm -f /tmp/autowonder-java.tar.gz
fi
curl --fail --silent --show-error '$jar_url' -o /opt/autowonder/releases/$short_commit/auto-wonder.jar.tmp
echo '$jar_hash  /opt/autowonder/releases/$short_commit/auto-wonder.jar.tmp' | sha256sum -c -
mv /opt/autowonder/releases/$short_commit/auto-wonder.jar.tmp /opt/autowonder/releases/$short_commit/auto-wonder.jar
chmod 0444 /opt/autowonder/releases/$short_commit/auto-wonder.jar
curl --fail --silent --show-error '$schema_url' -o /opt/autowonder/releases/$short_commit/autowonder-schema.sql.tmp
echo '$schema_hash  /opt/autowonder/releases/$short_commit/autowonder-schema.sql.tmp' | sha256sum -c -
mv /opt/autowonder/releases/$short_commit/autowonder-schema.sql.tmp /opt/autowonder/releases/$short_commit/autowonder-schema.sql
chmod 0444 /opt/autowonder/releases/$short_commit/autowonder-schema.sql
curl --fail --silent --show-error '$templates_url' -o /opt/autowonder/releases/$short_commit/autowonder-community-templates.sql.tmp
echo '$templates_hash  /opt/autowonder/releases/$short_commit/autowonder-community-templates.sql.tmp' | sha256sum -c -
mv /opt/autowonder/releases/$short_commit/autowonder-community-templates.sql.tmp /opt/autowonder/releases/$short_commit/autowonder-community-templates.sql
chmod 0444 /opt/autowonder/releases/$short_commit/autowonder-community-templates.sql
curl --fail --silent --show-error '$migrations_url' -o /opt/autowonder/releases/$short_commit/autowonder-migrations.tar.gz.tmp
echo '$migrations_hash  /opt/autowonder/releases/$short_commit/autowonder-migrations.tar.gz.tmp' | sha256sum -c -
mv /opt/autowonder/releases/$short_commit/autowonder-migrations.tar.gz.tmp /opt/autowonder/releases/$short_commit/autowonder-migrations.tar.gz
chmod 0444 /opt/autowonder/releases/$short_commit/autowonder-migrations.tar.gz
rm -rf /opt/autowonder/releases/$short_commit/migration
install -d -o root -g root -m 0755 /opt/autowonder/releases/$short_commit/migration
tar -xzf /opt/autowonder/releases/$short_commit/autowonder-migrations.tar.gz -C /opt/autowonder/releases/$short_commit/migration
previous_env=/opt/autowonder/releases/$short_commit/autowonder.env.previous
if test '$transfer_scope' != upgrade && test -f /etc/autowonder/autowonder.env && ! test -f "\$previous_env"; then
  install -m 0640 -o root -g autowonder /etc/autowonder/autowonder.env "\$previous_env"
fi
curl --fail --silent --show-error '$env_url' -o /etc/autowonder/autowonder.env.tmp
echo '$env_hash  /etc/autowonder/autowonder.env.tmp' | sha256sum -c -
chown root:autowonder /etc/autowonder/autowonder.env.tmp && chmod 0640 /etc/autowonder/autowonder.env.tmp
mv /etc/autowonder/autowonder.env.tmp /etc/autowonder/autowonder.env
trap - EXIT
previous_unit=/opt/autowonder/releases/$short_commit/autowonder.service.previous
if test '$transfer_scope' != upgrade && test -f /etc/systemd/system/autowonder.service && ! test -f "\$previous_unit"; then
  install -m 0644 -o root -g root /etc/systemd/system/autowonder.service "\$previous_unit"
fi
curl --fail --silent --show-error '$unit_url' -o /etc/systemd/system/autowonder.service.tmp
echo '$unit_hash  /etc/systemd/system/autowonder.service.tmp' | sha256sum -c -
chmod 0644 /etc/systemd/system/autowonder.service.tmp
mv /etc/systemd/system/autowonder.service.tmp /etc/systemd/system/autowonder.service
$activation
systemctl daemon-reload
EOF
)
  fi
  invocations+=("$(run_cloud_command "$instance" "$remote")")
done

CLEANUP_STAGING_ON_EXIT=true
for object in "${objects[@]}"; do ossutil_remove "oss://$bucket/$prefix/$object" "$control_endpoint" "$region" >/dev/null; done
STAGING_TARGETS=()
unset jar_url schema_url templates_url migrations_url unit_url env_url java_url remote
mode=full
if [[ "$config_only" == true ]]; then mode=config-only
elif [[ "$stage_only" == true ]]; then mode=stage-only; fi
atomic_jq "$manifest" --argjson invocations "$(printf '%s\n' "${invocations[@]}" | jq -R . | jq -s .)" \
  --arg prefix "$prefix" --arg mode "$mode" --arg envHash "$env_hash" '.phase="deploy" | .status="installed" | .deployment.lastRun={mode:$mode,invocationIds:$invocations,stagingPrefix:$prefix,stagingCleaned:true,envSha256:$envHash,credentialTransport:"time-limited private intranet presign",transportExceptionRecorded:true}'
log "release installed on ${#instances[@]} node(s); private staging objects removed"
