#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
source "$SCRIPT_DIR/upgrade-lib.sh"

manifest=
while (($#)); do
  case "$1" in
    --manifest) manifest=${2:-}; shift 2 ;;
    --help|-h) printf 'Usage: verify-deployment-targets.sh --manifest FILE\n'; exit 0 ;;
    *) die "unknown argument" ;;
  esac
done

require_file "$manifest"; require_command jq; require_command aliyun; require_command python3
json_validate "$manifest"; reject_secret_keys "$manifest"
configure_cloud_profile "$manifest"
region=$(json_string "$manifest" '.region')
deployment_id=$(json_string "$manifest" '.deploymentId')
load_alicloud_profile_credentials "$region"

expected_tags=$(jq -c --arg deployment "$deployment_id" '
  (.resources.expected_tags // .tags // {}) + {
    Project:"AutoWonder", DeploymentId:$deployment, ManagedBy:"Terraform"
  }
' "$manifest")
jq -e '.Environment | type == "string" and length > 0' <<<"$expected_tags" >/dev/null || die "manifest Environment tag is missing"
jq -e '.Topology | type == "string" and length > 0' <<<"$expected_tags" >/dev/null || die "manifest Topology tag is missing"
expected_vpc=$(jq -r '.resources.vpc_id // empty' "$manifest")
verified='[]'
terraform_ids=$(jq -c '(.resources.ecs_instance_ids // .resources.ecsInstanceIds) | [.[]] | unique | sort' "$manifest")
verification_mode=$(jq -r '.upgradeInfo.tagVerificationMode // "strict"' "$manifest")

while IFS= read -r instance_id; do
  response=$(aliyun_cli ecs DescribeInstances --region "$region" --RegionId "$region" \
    --InstanceIds "[\"$instance_id\"]") || die "cannot inspect ECS target"
  instance=$(python3 -B "$SCRIPT_DIR/ecs_inventory.py" target --instance-id "$instance_id" <<<"$response") || die "cannot validate ECS target response"
  [[ $(jq -r '.InstanceId // .instanceId // empty' <<<"$instance") == "$instance_id" ]] || die "ECS target identity mismatch"
  live_vpc=$(jq -r '.VpcAttributes.VpcId // .vpcAttributes.vpcId // empty' <<<"$instance")
  [[ -z "$expected_vpc" || "$live_vpc" == "$expected_vpc" ]] || die "ECS target VPC mismatch"
  live_tags=$(jq -c '
    reduce ((.Tags.Tag // .tags.tag // .Tags // [])[]) as $tag
      ({}; .[($tag.TagKey // $tag.tagKey // $tag.Key)] = ($tag.TagValue // $tag.tagValue // $tag.Value))
  ' <<<"$instance")
  if [[ "$verification_mode" != identity-only ]]; then
    jq -ne --argjson expected "$expected_tags" --argjson live "$live_tags" '
      $expected | to_entries | all(. as $item | $live[$item.key] == $item.value)
    ' >/dev/null || die "ECS target tags do not match the deployment manifest"
  fi
  verified=$(jq --arg id "$instance_id" --arg vpc "$live_vpc" '. + [{instanceId:$id,vpcId:$vpc}]' <<<"$verified")
done < <(jq -er '(.resources.ecs_instance_ids // .resources.ecsInstanceIds) | [.[]] | unique[]' "$manifest")

count=$(jq 'length' <<<"$verified")
((count > 0)) || die "ECS inventory is empty"
cloud_ids=$terraform_ids
if [[ "$verification_mode" != identity-only ]]; then
  inventory_state=null; page=1
  while :; do
    response=$(aliyun_cli ecs DescribeInstances --region "$region" --RegionId "$region" \
      --PageNumber "$page" --PageSize 100 \
      --Tag.1.Key Project --Tag.1.Value AutoWonder \
      --Tag.2.Key DeploymentId --Tag.2.Value "$deployment_id") || die "cannot inspect tagged ECS deployment set"
    inventory_state=$(jq -nc --argjson response "$response" --argjson state "$inventory_state" \
      '{response:$response,state:$state}' | python3 -B "$SCRIPT_DIR/ecs_inventory.py" page) || die "cannot validate tagged ECS inventory"
    [[ $(jq -r '.done' <<<"$inventory_state") != true ]] || break
    page=$((page + 1))
  done
  cloud_ids=$(jq -c '.ids | sort' <<<"$inventory_state")
  jq -ne --argjson terraform "$terraform_ids" --argjson cloud "$cloud_ids" '$terraform == $cloud' >/dev/null || \
    die "Alibaba Cloud contains an ECS node outside Terraform inventory or Terraform contains a missing ECS node"
fi
fingerprint=$(calculate_target_verification_fingerprint "$manifest" "$verified")
resource_set_fingerprint=$(current_resource_set_fingerprint "$manifest")
atomic_jq "$manifest" --arg fingerprint "$fingerprint" --arg resourceSetFingerprint "$resource_set_fingerprint" \
  --argjson nodes "$verified" --argjson terraformIds "$terraform_ids" --argjson cloudIds "$cloud_ids" '
  .upgrade=((.upgrade // {}) + {targetVerification:{
    status:"verified",fingerprint:$fingerprint,nodes:$nodes,
    resourceSetFingerprint:$resourceSetFingerprint,
    terraformInstanceIds:$terraformIds,cloudInstanceIds:$cloudIds,
    verifiedAt:(now|todateiso8601),verifiedEpoch:now
  }})
'
jq -n --arg deploymentId "$deployment_id" --arg region "$region" --argjson nodes "$verified" \
  --arg fingerprint "$fingerprint" --arg resourceSetFingerprint "$resource_set_fingerprint" \
  '{status:"verified",deploymentId:$deploymentId,region:$region,nodes:$nodes,fingerprint:$fingerprint,resourceSetFingerprint:$resourceSetFingerprint}'
