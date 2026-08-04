#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
source "$SCRIPT_DIR/lib.sh"

usage() { cat <<'EOF'
Usage: deploy-via-cloud-assistant.sh --manifest FILE --env-file FILE [--release-dir DIR]
       [--unit-file FILE] [--java-archive FILE] [--config-only] [--dry-run]
Deploys through private OSS and Alibaba Cloud Assistant. Secret values belong only in the mode-600 env file.
EOF
}

manifest= release_dir= env_file= unit_file="$SCRIPT_DIR/../assets/systemd/autowonder.service" java_archive= config_only=false dry_run=false
require_no_secret_args "$@"
while (($#)); do
  case "$1" in
    --manifest) manifest=${2:-}; shift 2;;
    --release-dir) release_dir=${2:-}; shift 2;;
    --env-file) env_file=${2:-}; shift 2;;
    --unit-file) unit_file=${2:-}; shift 2;;
    --java-archive) java_archive=${2:-}; shift 2;;
    --config-only) config_only=true; shift;;
    --dry-run) dry_run=true; shift;;
    --help|-h) usage; exit 0;;
    *) die "unknown argument";;
  esac
done
require_file "$manifest"; require_file "$env_file"
if [[ "$config_only" == false ]]; then
  require_file "$release_dir/auto-wonder.jar"; require_file "$release_dir/autowonder-schema.sql"; require_file "$unit_file"
fi
require_mode_600 "$env_file"; require_command jq
json_validate "$manifest"; reject_secret_keys "$manifest"
configure_cloud_profile "$manifest"
region=$(json_string "$manifest" '.region'); deployment_id=$(json_string "$manifest" '.deploymentId')
commit=$(json_string "$manifest" '.repositoryCommit'); short_commit=${commit:0:12}
bucket=$(jq -er '.resources.package_bucket // .resources.packageBucket // empty' "$manifest") || die "package bucket missing from inventory"
instances=()
while IFS= read -r instance; do instances+=("$instance"); done < <(jq -er '(.resources.ecs_instance_ids // .resources.ecsInstanceIds)[]' "$manifest")
((${#instances[@]} > 0)) || die "ECS inventory is empty"
jar_hash= unit_hash= schema_hash= java_hash=
env_hash=$(sha256_file "$env_file")
if [[ "$config_only" == false ]]; then
  jar_hash=$(sha256_file "$release_dir/auto-wonder.jar"); unit_hash=$(sha256_file "$unit_file")
  schema_hash=$(sha256_file "$release_dir/autowonder-schema.sql")
  [[ -z "$java_archive" ]] || { require_file "$java_archive"; java_hash=$(sha256_file "$java_archive"); }
fi
prefix="deployments/${deployment_id}/${short_commit}-$(date -u +%Y%m%dT%H%M%SZ)-$$"
control_endpoint=$(jq -r '.resources.oss.control_endpoint // .resources.oss.public_endpoint // empty' "$manifest")
runtime_endpoint=$(jq -r '.resources.oss.runtime_endpoint // .resources.oss.vpc_endpoint // empty' "$manifest")
control_endpoint=${control_endpoint:-"oss-${region}.aliyuncs.com"}
runtime_endpoint=${runtime_endpoint:-"oss-${region}-internal.aliyuncs.com"}

if [[ "$dry_run" == true ]]; then
  mode=full; [[ "$config_only" == false ]] || mode=config-only
  jq -n --arg prefix "$prefix" --arg controlEndpoint "$control_endpoint" --arg runtimeEndpoint "$runtime_endpoint" --arg mode "$mode" --argjson nodes "${#instances[@]}" \
    '{phase:"deploy",status:"planned",mode:$mode,privateStagingPrefix:$prefix,controlEndpoint:$controlEndpoint,runtimeEndpoint:$runtimeEndpoint,nodeCount:$nodes}'
  exit 0
fi
require_command aliyun; require_command ossutil; export ALIBABA_CLOUD_REGION_ID="$region"
if ossutil help presign >/dev/null 2>&1; then oss_sign_mode=presign
elif ossutil help sign >/dev/null 2>&1; then oss_sign_mode=sign
else die "installed ossutil supports neither presign nor sign"
fi

objects=("autowonder.env"); files=("$env_file")
if [[ "$config_only" == false ]]; then
  objects=("auto-wonder.jar" "autowonder-schema.sql" "autowonder.service" "autowonder.env")
  files=("$release_dir/auto-wonder.jar" "$release_dir/autowonder-schema.sql" "$unit_file" "$env_file")
  if [[ -n "$java_archive" ]]; then objects+=("temurin21-linux-amd64.tar.gz"); files+=("$java_archive"); fi
fi
for idx in "${!objects[@]}"; do
  ossutil cp -f "${files[$idx]}" "oss://$bucket/$prefix/${objects[$idx]}" --endpoint "$control_endpoint" >/dev/null
done

presign_object() {
  local object=$1 result
  if [[ "$oss_sign_mode" == presign ]]; then
    result=$(ossutil presign "oss://$bucket/$prefix/$object" --expires-duration 15m --endpoint "$runtime_endpoint" 2>/dev/null) || die "failed to presign staging object"
  else
    result=$(ossutil sign "oss://$bucket/$prefix/$object" --timeout 900 --endpoint "$runtime_endpoint" 2>/dev/null) || die "failed to sign staging object"
  fi
  printf '%s\n' "$result" | sed -nE 's#.*(https?://[^[:space:]]+).*#\1#p' | head -1
}

run_cloud_command() {
  local instance=$1 script=$2 response invocation result status exit_code attempts=0
  response=$(aliyun_cli ecs RunCommand --region "$region" --RegionId "$region" --InstanceId.1 "$instance" \
    --Type RunShellScript --Timeout 1800 --CommandContent "$script") || die "Cloud Assistant submission failed"
  invocation=$(jq -er '.InvokeId // .InvocationId // .invokeId // .invocationId' <<<"$response") || die "Cloud Assistant invocation ID missing"
  atomic_jq "$manifest" --arg id "$invocation" --arg instance "$instance" \
    '.remoteInvocations=((.remoteInvocations // []) + [{invokeId:$id,instanceId:$instance,status:"submitted",submittedAt:(now|todateiso8601)}])'
  while ((attempts++ < 180)); do
    sleep 2
    result=$(aliyun_cli ecs DescribeInvocationResults --region "$region" --RegionId "$region" --InvokeId "$invocation") || continue
    while IFS='=' read -r key value; do
      case "$key" in *PASSWORD*|*SECRET*|*TOKEN*|*KEY*)
        value=${value#\'}; value=${value%\'}; value=${value#\"}; value=${value%\"}
        if [[ ${#value} -ge 6 ]] && grep -Fq -- "$value" <<<"$result"; then die "secret detected in Cloud Assistant result"; fi;;
      esac
    done <"$env_file"
    status=$(jq -r '[..|objects|.InvokeRecordStatus? // .InvocationStatus? // .Status? // empty][0] // "Pending"' <<<"$result")
    case "$status" in
      Finished|Success)
        exit_code=$(jq -r '[..|objects|.ExitCode? // empty][0] // -1' <<<"$result")
        [[ "$exit_code" == 0 ]] || die "Cloud Assistant command failed"
        atomic_jq "$manifest" --arg id "$invocation" '(.remoteInvocations[] | select(.invokeId==$id)).status="finished"'
        printf '%s' "$invocation"; return 0;;
      Failed|Stopped|Stopping|TimedOut|Cancelled) die "Cloud Assistant invocation reached terminal failure";;
    esac
  done
  die "Cloud Assistant invocation timed out"
}

invocations=()
jar_url= schema_url= unit_url= java_url=
env_url=$(presign_object autowonder.env)
if [[ "$config_only" == false ]]; then
  jar_url=$(presign_object auto-wonder.jar); schema_url=$(presign_object autowonder-schema.sql); unit_url=$(presign_object autowonder.service)
  [[ -z "$java_archive" ]] || java_url=$(presign_object temurin21-linux-amd64.tar.gz)
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
curl --fail --silent --show-error '$env_url' -o /etc/autowonder/autowonder.env.tmp
echo '$env_hash  /etc/autowonder/autowonder.env.tmp' | sha256sum -c -
chown root:autowonder /etc/autowonder/autowonder.env.tmp && chmod 0640 /etc/autowonder/autowonder.env.tmp
mv /etc/autowonder/autowonder.env.tmp /etc/autowonder/autowonder.env
curl --fail --silent --show-error '$unit_url' -o /etc/systemd/system/autowonder.service.tmp
echo '$unit_hash  /etc/systemd/system/autowonder.service.tmp' | sha256sum -c -
chmod 0644 /etc/systemd/system/autowonder.service.tmp
mv /etc/systemd/system/autowonder.service.tmp /etc/systemd/system/autowonder.service
ln -sfn /opt/autowonder/releases/$short_commit /opt/autowonder/current.new
mv -Tf /opt/autowonder/current.new /opt/autowonder/current
systemctl daemon-reload
EOF
)
  fi
  invocations+=("$(run_cloud_command "$instance" "$remote")")
done

for object in "${objects[@]}"; do ossutil rm -f "oss://$bucket/$prefix/$object" --endpoint "$control_endpoint" >/dev/null; done
unset jar_url schema_url unit_url env_url java_url remote
mode=full; [[ "$config_only" == false ]] || mode=config-only
atomic_jq "$manifest" --argjson invocations "$(printf '%s\n' "${invocations[@]}" | jq -R . | jq -s .)" \
  --arg prefix "$prefix" --arg mode "$mode" '.phase="deploy" | .status="installed" | .deployment.lastRun={mode:$mode,invocationIds:$invocations,stagingPrefix:$prefix,stagingCleaned:true,credentialTransport:"time-limited private intranet presign",transportExceptionRecorded:true}'
log "release installed on ${#instances[@]} node(s); private staging objects removed"
