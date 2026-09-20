#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
source "$SCRIPT_DIR/lib.sh"

usage() {
  printf 'Usage: bash prepare-teardown.sh --manifest FILE --confirmation-file FILE [--reconcile]\n'
}
manifest= confirmation= reconcile=false
require_no_secret_args "$@"
while (($#)); do
  case "$1" in
    --manifest) manifest=${2:-}; shift 2;;
    --confirmation-file) confirmation=${2:-}; shift 2;;
    --reconcile) reconcile=true; shift;;
    --help|-h) usage; exit 0;;
    *) die "unknown argument";;
  esac
done
require_file "$manifest"; require_file "$confirmation"
require_command jq; require_command terraform; require_command python3
json_validate "$manifest"; reject_secret_keys "$manifest"
deployment_id=$(json_string "$manifest" '.deploymentId')
grep -Fxq "DESTROY $deployment_id" "$confirmation" || die "teardown confirmation does not match deployment"
preparation_status=$(jq -r '.teardownPreparation.status // ""' "$manifest")
if [[ "$reconcile" == true ]]; then
  [[ "$preparation_status" == pending ]] || die "reconciliation requires a pending teardown preparation"
else
  [[ "$preparation_status" != pending ]] || die "previous teardown preparation outcome is unknown; reconcile before retrying"
fi
require_remote_submission_settled "$manifest"
configure_cloud_profile "$manifest"
region=$(json_string "$manifest" '.region')
ensure_alicloud_profile_identity "$region"
[[ $(jq -er '.AccountId' <<<"$AUTOWONDER_IDENTITY_JSON") == $(json_string "$manifest" '.accountUid') ]] || die "Alibaba Cloud account identity mismatch"
coordinates=$(python3 "$SCRIPT_DIR/teardown_plan.py" binding --manifest "$manifest")
work_dir=$(jq -er '.workDir' <<<"$coordinates")
backend_file=$(jq -er '.backendFile' <<<"$coordinates")
secrets_file="$work_dir/terraform-secrets.env"
require_file "$secrets_file"; require_mode_600 "$secrets_file"
set -a
# shellcheck disable=SC1090
source "$secrets_file"
set +a
export TF_CLI_CONFIG_FILE
TF_CLI_CONFIG_FILE=$(bash "$SCRIPT_DIR/configure-terraform-acceleration.sh")

# Restrict preparation to protections and their Terraform dependencies. An
# unrelated provider computed-set diff must never enter this mutation plan.
targets=$(python3 "$SCRIPT_DIR/teardown_plan.py" targets | jq -er '.[]')
target_args=()
while IFS= read -r target; do
  target_args+=("-target=$target")
done <<<"$targets"

# All Terraform output and JSON may contain original application passwords.
# Keep it private; never stream it to the agent or a public evidence log.
log_file="$work_dir/teardown-preparation.log"
plan_path="$work_dir/teardown-preparation.tfplan"
if [[ "$reconcile" == true ]]; then
  log_file="$work_dir/teardown-reconciliation.log"
  [[ $(jq -r '.teardownPreparation.planPath // ""' "$manifest") == "$plan_path" ]] || die "recorded preparation plan path differs from this deployment"
  require_file "$plan_path"
  fingerprint=$(jq -er '.teardownPreparation.planFingerprint' "$manifest")
  [[ $(sha256_file "$plan_path") == "$fingerprint" ]] || die "recorded preparation plan fingerprint mismatch"
fi
plan_json=$(mktemp "$work_dir/teardown-preparation.XXXXXX")
reviewed_json=$(mktemp "$work_dir/teardown-reviewed.XXXXXX")
post_plan=$(mktemp "$work_dir/teardown-post.XXXXXX")
TEMP_FILES+=("$plan_json" "$reviewed_json" "$post_plan")
: >"$log_file"; chmod 600 "$log_file"
terraform -chdir="$work_dir" init -reconfigure -backend-config="$backend_file" >>"$log_file" 2>&1 || die "teardown backend initialization failed; inspect protected local log"
if [[ "$reconcile" != true ]]; then
  terraform -chdir="$work_dir" plan -var=lifecycle_mode=temporary "${target_args[@]}" -out="$plan_path" >>"$log_file" 2>&1 || die "teardown preparation planning failed; inspect protected local log"
  chmod 600 "$plan_path"
  fingerprint=$(sha256_file "$plan_path")
fi
terraform -chdir="$work_dir" show -json "$plan_path" >"$reviewed_json" 2>>"$log_file" || die "cannot inspect teardown preparation plan"
python3 "$SCRIPT_DIR/teardown_plan.py" review --manifest "$manifest" --plan-json "$reviewed_json" >/dev/null
[[ $(sha256_file "$plan_path") == "$fingerprint" ]] || die "teardown preparation plan fingerprint changed"
if [[ "$reconcile" != true ]]; then
  atomic_jq "$manifest" --arg hash "$fingerprint" --arg path "$plan_path" \
    '.teardownPreparation={status:"pending",planFingerprint:$hash,planPath:$path}'
  [[ $(sha256_file "$plan_path") == "$fingerprint" ]] || die "teardown preparation plan fingerprint changed"
  terraform -chdir="$work_dir" apply "$plan_path" >>"$log_file" 2>&1 || die "teardown preparation apply failed or is uncertain; pending state retained"
fi
# Reconciliation only observes a fresh targeted plan; it never repeats apply.
# Post review checks actual BEFORE values, not merely the desired AFTER values.
terraform -chdir="$work_dir" plan -var=lifecycle_mode=temporary "${target_args[@]}" -out="$post_plan" >>"$log_file" 2>&1 || die "teardown preparation postcondition is uncertain; pending state retained"
terraform -chdir="$work_dir" show -json "$post_plan" >"$plan_json" 2>>"$log_file" || die "cannot inspect teardown preparation postcondition"
post_review=$(python3 "$SCRIPT_DIR/teardown_plan.py" post --manifest "$manifest" --plan-json "$plan_json" --reviewed-plan-json "$reviewed_json")
[[ $(sha256_file "$plan_path") == "$fingerprint" ]] || die "teardown preparation plan fingerprint changed"
post_fingerprint=$(sha256_file "$post_plan")
atomic_jq "$manifest" --arg hash "$post_fingerprint" --argjson review "$post_review" \
  '.teardownPreparation.status="complete" | .teardownPreparation.postPlanFingerprint=$hash | .teardownPreparation.postcheck=$review'
printf '{"phase":"teardown-preparation","status":"complete","planFingerprint":"%s","postcheck":%s}\n' "$fingerprint" "$post_review"
