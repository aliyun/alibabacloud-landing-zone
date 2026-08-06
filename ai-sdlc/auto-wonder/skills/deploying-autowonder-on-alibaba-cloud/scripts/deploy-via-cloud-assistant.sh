#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
source "$SCRIPT_DIR/lib.sh"

usage() { cat <<'EOF'
Usage: deploy-via-cloud-assistant.sh --manifest FILE --env-file FILE [--release-dir DIR]
       [--unit-file FILE] [--java-archive FILE] [--config-only|--stage-only] [--dry-run]
Deploys through private OSS and Alibaba Cloud Assistant. Secret values belong only in the mode-600 env file.
EOF
}

manifest= release_dir= env_file= unit_file="$SCRIPT_DIR/../assets/systemd/autowonder.service" java_archive= config_only=false stage_only=false dry_run=false
require_no_secret_args "$@"
while (($#)); do
  case "$1" in
    --manifest) manifest=${2:-}; shift 2;;
    --release-dir) release_dir=${2:-}; shift 2;;
    --env-file) env_file=${2:-}; shift 2;;
    --unit-file) unit_file=${2:-}; shift 2;;
    --java-archive) java_archive=${2:-}; shift 2;;
    --config-only) config_only=true; shift;;
    --stage-only) stage_only=true; shift;;
    --dry-run) dry_run=true; shift;;
    --help|-h) usage; exit 0;;
    *) die "unknown argument";;
  esac
done
[[ "$config_only" == false || "$stage_only" == false ]] || die "config-only and stage-only are mutually exclusive"
require_file "$manifest"; require_file "$env_file"
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
fi
configure_cloud_profile "$manifest"
region=$(json_string "$manifest" '.region'); deployment_id=$(json_string "$manifest" '.deploymentId')
commit=$(json_string "$manifest" '.repositoryCommit'); short_commit=${commit:0:12}
bucket=$(jq -er '.resources.package_bucket // .resources.packageBucket // empty' "$manifest") || die "package bucket missing from inventory"
instances=()
while IFS= read -r instance; do instances+=("$instance"); done < <(jq -er '(.resources.ecs_instance_ids // .resources.ecsInstanceIds)[]' "$manifest")
((${#instances[@]} > 0)) || die "ECS inventory is empty"
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
  jq -n --arg prefix "$prefix" --arg controlEndpoint "$control_endpoint" --arg runtimeEndpoint "$runtime_endpoint" --arg mode "$mode" --argjson nodes "${#instances[@]}" \
    '{phase:"deploy",status:"planned",mode:$mode,privateStagingPrefix:$prefix,controlEndpoint:$controlEndpoint,runtimeEndpoint:$runtimeEndpoint,nodeCount:$nodes}'
  exit 0
fi
require_command aliyun; require_command ossutil; export ALIBABA_CLOUD_REGION_ID="$region"
ossutil_preflight "$region"

objects=("autowonder.env"); files=("$env_file")
if [[ "$config_only" == false ]]; then
  objects=("auto-wonder.jar" "autowonder-schema.sql" "autowonder-community-templates.sql" "autowonder-migrations.tar.gz" "autowonder.service" "autowonder.env")
  files=("$release_dir/auto-wonder.jar" "$release_dir/autowonder-schema.sql" "$release_dir/autowonder-community-templates.sql" "$release_dir/autowonder-migrations.tar.gz" "$unit_file" "$env_file")
  if [[ -n "$java_archive" ]]; then objects+=("temurin21-linux-amd64.tar.gz"); files+=("$java_archive"); fi
fi
for idx in "${!objects[@]}"; do
  ossutil_upload "${files[$idx]}" "oss://$bucket/$prefix/${objects[$idx]}" "$control_endpoint" "$region" >/dev/null
done

run_cloud_command() {
  local instance=$1 script=$2 response invocation result status exit_code deadline
  response=$(aliyun_cli ecs RunCommand --region "$region" --RegionId "$region" --InstanceId.1 "$instance" \
    --Type RunShellScript --Timeout 1800 --CommandContent "$script") || die "Cloud Assistant submission failed"
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
for instance in "${instances[@]}"; do
  if [[ "$config_only" == true ]]; then
    remote=$(cat <<EOF
set -euo pipefail
install -d -o root -g root -m 0755 /etc/autowonder
curl --fail --silent --show-error '$env_url' -o /etc/autowonder/autowonder.env.tmp
echo '$env_hash  /etc/autowonder/autowonder.env.tmp' | sha256sum -c -
chown root:autowonder /etc/autowonder/autowonder.env.tmp && chmod 0640 /etc/autowonder/autowonder.env.tmp
mv /etc/autowonder/autowonder.env.tmp /etc/autowonder/autowonder.env
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
test "\$(uname -m)" = x86_64
id autowonder >/dev/null 2>&1 || useradd --system --home /var/lib/autowonder --shell /sbin/nologin autowonder
if ! command -v mysql >/dev/null 2>&1; then dnf install -y mariadb105 || dnf install -y mariadb; fi
if ! command -v redis-cli >/dev/null 2>&1; then dnf install -y redis6 || dnf install -y redis; fi
install -d -o root -g root -m 0755 /opt/autowonder/releases/$short_commit /opt/autowonder/runtime /etc/autowonder
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
if test -f /etc/autowonder/autowonder.env && ! test -f "\$previous_env"; then
  install -m 0640 -o root -g autowonder /etc/autowonder/autowonder.env "\$previous_env"
fi
curl --fail --silent --show-error '$env_url' -o /etc/autowonder/autowonder.env.tmp
echo '$env_hash  /etc/autowonder/autowonder.env.tmp' | sha256sum -c -
chown root:autowonder /etc/autowonder/autowonder.env.tmp && chmod 0640 /etc/autowonder/autowonder.env.tmp
mv /etc/autowonder/autowonder.env.tmp /etc/autowonder/autowonder.env
previous_unit=/opt/autowonder/releases/$short_commit/autowonder.service.previous
if test -f /etc/systemd/system/autowonder.service && ! test -f \"\$previous_unit\"; then
  install -m 0644 -o root -g root /etc/systemd/system/autowonder.service \"\$previous_unit\"
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

for object in "${objects[@]}"; do ossutil_remove "oss://$bucket/$prefix/$object" "$control_endpoint" "$region" >/dev/null; done
unset jar_url schema_url templates_url migrations_url unit_url env_url java_url remote
mode=full
if [[ "$config_only" == true ]]; then mode=config-only
elif [[ "$stage_only" == true ]]; then mode=stage-only; fi
atomic_jq "$manifest" --argjson invocations "$(printf '%s\n' "${invocations[@]}" | jq -R . | jq -s .)" \
  --arg prefix "$prefix" --arg mode "$mode" --arg envHash "$env_hash" '.phase="deploy" | .status="installed" | .deployment.lastRun={mode:$mode,invocationIds:$invocations,stagingPrefix:$prefix,stagingCleaned:true,envSha256:$envHash,credentialTransport:"time-limited private intranet presign",transportExceptionRecorded:true}'
log "release installed on ${#instances[@]} node(s); private staging objects removed"
