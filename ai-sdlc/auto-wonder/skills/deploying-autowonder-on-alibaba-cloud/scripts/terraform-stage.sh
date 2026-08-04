#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
source "$SCRIPT_DIR/lib.sh"

usage() { cat <<'EOF'
Usage:
  terraform-stage.sh plan --manifest FILE --work-dir DIR
  terraform-stage.sh apply --manifest FILE --work-dir DIR --approved-plan-sha256 HASH
  terraform-stage.sh inventory --manifest FILE --work-dir DIR
  terraform-stage.sh destroy-plan --manifest FILE --work-dir DIR [--confirmation-file FILE]
The apply command accepts only the exact saved, reviewed plan fingerprint.
EOF
}

[[ ${1:-} == --help || ${1:-} == -h ]] && { usage; exit 0; }
command=${1:-}; [[ -n "$command" ]] || { usage >&2; exit 2; }; shift
manifest= work_dir= approved= confirmation=
require_no_secret_args "$@"
while (($#)); do
  case "$1" in
    --manifest) manifest=${2:-}; shift 2;;
    --work-dir) work_dir=${2:-}; shift 2;;
    --approved-plan-sha256) approved=${2:-}; shift 2;;
    --confirmation-file) confirmation=${2:-}; shift 2;;
    --help|-h) usage; exit 0;;
    *) die "unknown argument";;
  esac
done
require_file "$manifest"; [[ -d "$work_dir" ]] || die "Terraform directory missing"
require_command jq; require_command terraform; json_validate "$manifest"; reject_secret_keys "$manifest"
configure_cloud_profile "$manifest"
plan_path="$work_dir/reviewed.tfplan"

write_tfvars() {
  local tfvars="$work_dir/deployment.auto.tfvars.json"
  jq '{region,environment,deployment_id:.deploymentId,
       zone_a_id:.availabilityZones[0],zone_b_id:.availabilityZones[1],
       lifecycle_mode:.lifecycle,public_source_cidrs:.publicSourceCidrs,
       vpc_cidr:.network.vpcCidr,zone_a_cidr:.network.zoneACidr,zone_b_cidr:.network.zoneBCidr,
       ecs_image_id:.resolvedInfrastructure.ecsImageId,
       ecs_instance_type:.resolvedInfrastructure.ecsInstanceType,
       rds_instance_type:.resolvedInfrastructure.rdsInstanceType,
       rds_category:.resolvedInfrastructure.rdsCategory,
       rds_storage_type:.resolvedInfrastructure.rdsStorageType,
       rds_storage_gb:.resolvedInfrastructure.rdsStorageGb,
       redis_instance_class:.resolvedInfrastructure.redisInstanceClass,
       common_tags:.tags}' "$manifest" >"$tfvars"
  chmod 600 "$tfvars"
}

terraform_init() {
  local state_mode state_reference backend_file="$work_dir/backend.tf"
  state_mode=$(json_string "$manifest" '.stateMode')
  if [[ "$state_mode" == remote ]]; then
    state_reference=$(jq -r '.terraform.stateReference // ""' "$manifest")
    [[ -n "$state_reference" ]] || die "remote state backend reference is required"
    require_file "$state_reference"
    printf 'terraform {\n  backend "oss" {}\n}\n' >"$backend_file"
    terraform -chdir="$work_dir" init -reconfigure -backend-config="$state_reference"
  else
    [[ ! -f "$backend_file" ]] || unlink "$backend_file"
    terraform -chdir="$work_dir" init -reconfigure
  fi
}

case "$command" in
  plan)
    write_tfvars
    terraform -chdir="$work_dir" fmt -check
    terraform_init
    terraform -chdir="$work_dir" validate
    terraform -chdir="$work_dir" plan -out="$plan_path"
    fingerprint=$(sha256_file "$plan_path")
    atomic_jq "$manifest" --arg hash "$fingerprint" --arg path "$plan_path" \
      '.terraform.planFingerprint=$hash | .terraform.planPath=$path | .phase="terraform-plan" | .status="awaiting-approval"'
    printf 'Plan SHA256: %s\n' "$fingerprint"
    ;;
  apply)
    require_file "$plan_path"
    recorded=$(json_string "$manifest" '.terraform.planFingerprint')
    actual=$(sha256_file "$plan_path")
    [[ -n "$approved" && "$approved" == "$recorded" && "$approved" == "$actual" ]] || die "approved plan fingerprint mismatch"
    terraform -chdir="$work_dir" apply "$plan_path"
    record_phase "$manifest" infrastructure applied
    ;;
  inventory)
    raw=$(mktemp); TEMP_FILES+=("$raw")
    terraform -chdir="$work_dir" output -json >"$raw"
    # Store only non-secret identifiers and endpoints needed by later phases.
    jq '{
      region: .region.value,
      ecs_instance_ids: .ecs_instance_ids.value,
      nlb_address: .nlb_dns_name.value,
      rds: .rds.value,
      redis: .redis.value,
      package_bucket: .oss.value.package_bucket,
      artifact_bucket: .oss.value.artifact_bucket,
      oss: .oss.value,
      oss_endpoint: .oss.value.runtime_endpoint,
      sls: .sls.value
    }' "$raw" >"$work_dir/inventory.json"
    chmod 600 "$work_dir/inventory.json"
    atomic_jq "$manifest" --slurpfile inventory "$work_dir/inventory.json" '
      .resources=$inventory[0] |
      .applicationBaseUrl=("http://" + $inventory[0].nlb_address + ":7001")'
    ;;
  destroy-plan)
    lifecycle=$(json_string "$manifest" '.lifecycle')
    if [[ "$lifecycle" != temporary ]]; then
      require_file "$confirmation"
      deployment_id=$(json_string "$manifest" '.deploymentId')
      grep -Fxq "DESTROY $deployment_id" "$confirmation" || die "teardown confirmation does not match deployment"
    fi
    write_tfvars
    terraform -chdir="$work_dir" plan -destroy -out="$work_dir/destroy.tfplan"
    printf 'Destroy plan SHA256: %s\n' "$(sha256_file "$work_dir/destroy.tfplan")"
    ;;
  *) die "unsupported Terraform stage";;
esac
