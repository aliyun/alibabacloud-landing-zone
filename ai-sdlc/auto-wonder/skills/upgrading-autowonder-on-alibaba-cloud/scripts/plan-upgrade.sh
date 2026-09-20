#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
source "$SCRIPT_DIR/upgrade-lib.sh"

usage() { cat <<'EOF'
Usage: plan-upgrade.sh --manifest FILE --source-dir DIR
       [--remote NAME] [--current-commit SHA] [--env-file FILE] [--force-redeploy]
       [--workspace-current-content] [--baseline-dir DIR] [--target-ref BRANCH]
       [--repository-url URL --allow-repository-change]
Analyzes the exact selected remote ref from a clean matching source checkout.
Use prepare-upgrade-source.py to discover the repository default branch.
EOF
}

manifest= source_dir= target_ref=master remote=origin current_commit= env_file= force_redeploy=false workspace_current_content=false baseline_dir= repository_url= allow_repository_change=false
require_no_secret_args "$@"
while (($#)); do
  case "$1" in
    --manifest) manifest=${2:-}; shift 2;;
    --source-dir) source_dir=${2:-}; shift 2;;
    --target-ref) target_ref=${2:-}; shift 2;;
    --repository-url) repository_url=${2:-}; shift 2;;
    --allow-repository-change) allow_repository_change=true; shift;;
    --remote) remote=${2:-}; shift 2;;
    --current-commit) current_commit=${2:-}; shift 2;;
    --env-file) env_file=${2:-}; shift 2;;
    --force-redeploy) force_redeploy=true; shift;;
    --baseline-dir) baseline_dir=${2:-}; shift 2;;
    --workspace-current-content) workspace_current_content=true; shift;;
    --help|-h) usage; exit 0;;
    *) die "unknown argument";;
  esac
done

require_file "$manifest"
require_command jq
source_dir=$(resolve_upgrade_project_source_dir "$source_dir")
json_validate "$manifest"; reject_secret_keys "$manifest"
require_current_target_verification "$manifest"
[[ $(jq -r '.upgradeInventory.status // empty' "$manifest") == verified ]] || die "verified active release inventory is required before planning"
jq -e '
  .upgradeInventory.targetVerificationFingerprint == .upgrade.targetVerification.fingerprint and
  ((.upgradeInventory.nodes | map(.instanceId) | unique | sort) == (.upgrade.targetVerification.nodes | map(.instanceId) | unique | sort)) and
  ((now - (.upgradeInventory.verifiedEpoch // 0)) <= 1800)
' "$manifest" >/dev/null || die "active release inventory is stale or does not cover the verified target set"
resource_set_fingerprint=$(current_resource_set_fingerprint "$manifest")
if [[ -n $(jq -r '.upgradeInfo.resourceSetFingerprint // empty' "$manifest") ]]; then
  [[ $(jq -r '.upgradeInventory.resourceSetFingerprint // empty' "$manifest") == "$resource_set_fingerprint" ]] || \
    die "active release inventory resource set is stale"
fi
require_command python3
arguments=(plan --manifest "$manifest" --source-dir "$source_dir" --remote "$remote" --target-ref "$target_ref")
[[ -z "$repository_url" ]] || arguments+=(--repository-url "$repository_url")
[[ "$allow_repository_change" != true ]] || arguments+=(--allow-repository-change)
[[ "$workspace_current_content" != true ]] || arguments+=(--workspace-current-content)
[[ -z "$baseline_dir" ]] || arguments+=(--baseline-dir "$baseline_dir")
[[ -z "$current_commit" ]] || arguments+=(--current-commit "$current_commit")
[[ -z "$env_file" ]] || arguments+=(--env-file "$env_file")
[[ "$force_redeploy" != true ]] || arguments+=(--force-redeploy)
python3 -B "$SCRIPT_DIR/upgrade_plan.py" "${arguments[@]}"
