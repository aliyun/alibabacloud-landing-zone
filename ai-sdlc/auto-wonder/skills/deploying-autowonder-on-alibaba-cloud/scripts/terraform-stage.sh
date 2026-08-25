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
  terraform-stage.sh destroy-apply --manifest FILE --work-dir DIR --approved-plan-sha256 HASH
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
export TF_CLI_CONFIG_FILE
TF_CLI_CONFIG_FILE=$(bash "$SCRIPT_DIR/configure-terraform-acceleration.sh")
plan_path="$work_dir/reviewed.tfplan"

load_or_create_terraform_secrets() {
  local secrets_file="$work_dir/terraform-secrets.env" generated
  if [[ ! -f "$secrets_file" ]]; then
    require_command openssl
    generated="Aa1!$(openssl rand -hex 12)"
    {
      printf 'TF_VAR_ecs_password=%q\n' "$generated"
      generated="Aa1!$(openssl rand -hex 12)"
      printf 'TF_VAR_rds_password=%q\n' "$generated"
      generated="Aa1!$(openssl rand -hex 12)"
      printf 'TF_VAR_redis_password=%q\n' "$generated"
    } >"$secrets_file"
    chmod 600 "$secrets_file"
    unset generated
  fi
  require_mode_600 "$secrets_file"
  set -a
  # shellcheck disable=SC1090
  source "$secrets_file"
  set +a
}

load_or_create_terraform_secrets

write_tfvars() {
  local tfvars="$work_dir/deployment.auto.tfvars.json"
  jq '{region,environment,deployment_id:.deploymentId,
       zone_a_id:.availabilityZones[0],zone_b_id:.availabilityZones[1],
       lifecycle_mode:.lifecycle,public_source_cidrs:.publicSourceCidrs,
       billing_strategy:.billing.strategy,
       purchase_period_months:.billing.purchasePeriodMonths,
       auto_renew:.billing.autoRenew,
       auto_renew_period_months:.billing.autoRenewPeriodMonths,
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
  local state_mode state_reference backend_dir backend_file="$work_dir/backend.tf"
  state_mode=$(json_string "$manifest" '.stateMode')
  if [[ "$state_mode" == remote ]]; then
    state_reference=$(jq -r '.terraform.stateReference // ""' "$manifest")
    backend_dir=$(jq -r '.terraform.backendDirectory // ""' "$manifest")
    [[ $(jq -r '.terraform.backendStatus // ""' "$manifest") == ready ]] || die "automatic remote state backend is not ready"
    [[ -n "$state_reference" && "$state_reference" == "$backend_dir/backend.hcl" ]] || die "backend path differs from the fixed automatic path"
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
      '.terraform.planFingerprint=$hash | .terraform.planPath=$path | .phase="terraform-plan" | .status="awaiting-machine-review"'
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
      load_balancer_id: .load_balancer_id.value,
      load_balancer_address: .load_balancer_address.value,
      rds: .rds.value,
      redis: .redis.value,
      package_bucket: .oss.value.package_bucket,
      artifact_bucket: .oss.value.artifact_bucket,
      oss: .oss.value,
      oss_endpoint: .oss.value.runtime_endpoint,
      oss_public_endpoint: .oss.value.control_endpoint,
      sls: .sls.value
    }' "$raw" >"$work_dir/inventory.json"
    chmod 600 "$work_dir/inventory.json"
    atomic_jq "$manifest" --slurpfile inventory "$work_dir/inventory.json" '
      .resources=$inventory[0] |
      .applicationBaseUrl=(if (.ingressScenario | startswith("domain-"))
        then "http://" + .domain
        else "http://" + $inventory[0].load_balancer_address
      end)'
    ;;
  destroy-plan)
    lifecycle=$(json_string "$manifest" '.lifecycle')
    if [[ "$lifecycle" != temporary ]]; then
      require_file "$confirmation"
      deployment_id=$(json_string "$manifest" '.deploymentId')
      grep -Fxq "DESTROY $deployment_id" "$confirmation" || die "teardown confirmation does not match deployment"
    fi
    write_tfvars
    terraform_init
    terraform -chdir="$work_dir" plan -destroy -out="$work_dir/destroy.tfplan"
    fingerprint=$(sha256_file "$work_dir/destroy.tfplan")
    atomic_jq "$manifest" --arg hash "$fingerprint" '.terraform.destroyPlanFingerprint=$hash | .terraform.mainDestroyVerified=false | .phase="terraform-destroy-plan" | .status="awaiting-approval"'
    printf 'Destroy plan SHA256: %s\n' "$fingerprint"
    ;;
  destroy-apply)
    destroy_plan="$work_dir/destroy.tfplan"; require_file "$destroy_plan"
    recorded=$(json_string "$manifest" '.terraform.destroyPlanFingerprint')
    actual=$(sha256_file "$destroy_plan")
    [[ -n "$approved" && "$approved" == "$recorded" && "$approved" == "$actual" ]] || die "approved destroy plan fingerprint mismatch"
    terraform_init
    terraform -chdir="$work_dir" apply "$destroy_plan"
    remaining=$(terraform -chdir="$work_dir" state list)
    [[ -z "$remaining" ]] || die "main Terraform destroy postcondition failed"
    atomic_jq "$manifest" '.terraform.mainDestroyVerified=true | .phase="terraform-destroy" | .status="destroyed"'
    "$SCRIPT_DIR/terraform-backend.sh" destroy --manifest "$manifest"
    ;;
  *) die "unsupported Terraform stage";;
esac
