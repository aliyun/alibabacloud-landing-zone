#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
source "$SCRIPT_DIR/lib.sh"

usage() { cat <<'EOF'
Usage: preflight.sh --manifest FILE --source-dir DIR [--profile PROFILE] [--dry-run]
Performs local validation and, unless dry-run, read-only Alibaba Cloud identity and inventory probes.
EOF
}

manifest= source_dir= profile= dry_run=false
require_no_secret_args "$@"
while (($#)); do
  case "$1" in
    --manifest) manifest=${2:-}; shift 2;;
    --source-dir) source_dir=${2:-}; shift 2;;
    --profile) profile=${2:-}; shift 2;;
    --dry-run) dry_run=true; shift;;
    --help|-h) usage; exit 0;;
    *) die "unknown argument";;
  esac
done
require_file "$manifest"; [[ -d "$source_dir" ]] || die "source directory missing"
require_command jq; require_command git
json_validate "$manifest"; reject_secret_keys "$manifest"
profile=${profile:-$(jq -r '.cloudProfile // empty' "$manifest")}
ossutil_contract=not-checked; ossutil_version=not-checked

region=$(json_string "$manifest" '.region')
case "$region" in cn-zhangjiakou|cn-hangzhou|cn-shanghai|cn-beijing) ;; *) die "unsupported region";; esac
jq -e '.schemaVersion == 1 and .slsEnabled == true and .aoneEnabled == false and .publicEgress == false and .adminUsername == "admin"' "$manifest" >/dev/null || die "fixed deployment choices are invalid"
jq -e '.topology == "multi-az-ha" or .topology == "experience"' "$manifest" >/dev/null || die "unsupported topology"
jq -e '.architecture == null or .architecture == "x86_64"' "$manifest" >/dev/null || die "only x86_64 is supported"
jq -e '.stateMode == "remote" or .stateMode == "local"' "$manifest" >/dev/null || die "invalid state mode"
jq -e '.lifecycle == "persistent" or .lifecycle == "temporary"' "$manifest" >/dev/null || die "invalid lifecycle"
jq -e '.executionMode == "staged" or .executionMode == "unattended"' "$manifest" >/dev/null || die "invalid execution mode"
jq -e '.ingressScenario as $s | ($s == "no-domain-no-certificate" or $s == "domain-no-certificate" or $s == "domain-with-certificate") and (if ($s|startswith("domain-")) then (.domain|length)>0 else true end)' "$manifest" >/dev/null || die "invalid ingress scenario"
jq -e '.publicSourceCidrs | type == "array" and length > 0 and all(test("^[0-9a-fA-F:.]+/[0-9]+$"))' "$manifest" >/dev/null || die "at least one valid public source CIDR is required"
jq -e 'if .topology == "multi-az-ha" then (.availabilityZones|type=="array" and length>=2 and (unique|length)>=2) else true end' "$manifest" >/dev/null || die "HA requires two distinct zones"
jq -e '.tags.Project=="AutoWonder" and .tags.ManagedBy=="Terraform" and .tags.Environment==.environment and .tags.DeploymentId==.deploymentId and .tags.Topology==.topology' "$manifest" >/dev/null || die "required system tags are invalid"
for query in '.organizationName' '.resolvedInfrastructure.ecsImageId' '.resolvedInfrastructure.ecsInstanceType' '.resolvedInfrastructure.rdsInstanceType' '.resolvedInfrastructure.rdsCategory' '.resolvedInfrastructure.rdsStorageType' '.resolvedInfrastructure.redisInstanceClass'; do
  json_required "$manifest" "$query"
done
jq -e '.resolvedInfrastructure.rdsStorageGb > 0' "$manifest" >/dev/null || die "resolved RDS storage is required"

commit=$(json_string "$manifest" '.repositoryCommit')
if [[ "$commit" != "HEAD" ]]; then
  actual=$(git -C "$source_dir" rev-parse HEAD)
  [[ "$actual" == "$commit" ]] || die "source commit does not match manifest"
fi

if [[ "$dry_run" == false ]]; then
  for command in aliyun terraform ossutil openssl curl; do require_command "$command"; done
  export ALIBABA_CLOUD_REGION_ID="$region"
  ossutil_preflight "$region"
  ossutil_contract=$OSSUTIL_CONTRACT; ossutil_version=$OSSUTIL_VERSION
  run_aliyun() {
    if [[ -n "$profile" ]]; then aliyun "$@" --profile "$profile"; else aliyun "$@"; fi
  }
  run_aliyun sts GetCallerIdentity --region "$region" >/dev/null || die "Alibaba Cloud identity probe failed"
  run_aliyun ecs DescribeZones --region "$region" --RegionId "$region" >/dev/null || die "zone inventory probe failed"
  if [[ -n "$profile" ]]; then atomic_jq "$manifest" --arg profile "$profile" '.cloudProfile=$profile'; fi
fi
jq -n --arg region "$region" --argjson dryRun "$dry_run" --arg ossutilContract "$ossutil_contract" --arg ossutilVersion "$ossutil_version" \
  '{phase:"preflight",status:"passed",region:$region,dryRun:$dryRun,ossutilContract:$ossutilContract,ossutilVersion:$ossutilVersion}'
