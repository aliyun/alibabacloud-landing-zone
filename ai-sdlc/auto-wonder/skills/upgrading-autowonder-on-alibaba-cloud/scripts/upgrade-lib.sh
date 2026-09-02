#!/usr/bin/env bash

UPGRADE_SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
UPGRADE_DEPLOY_SKILL_DIR=$(cd -- "$UPGRADE_SCRIPT_DIR/../../deploying-autowonder-on-alibaba-cloud" && pwd)
declare -F die >/dev/null 2>&1 || source "$UPGRADE_DEPLOY_SKILL_DIR/scripts/lib.sh"

current_resource_set_fingerprint() {
  local manifest=$1 recorded material
  recorded=$(jq -r '.upgradeInfo.resourceSetFingerprint // empty' "$manifest")
  if [[ "$recorded" =~ ^[0-9a-f]{64}$ ]]; then
    printf '%s\n' "$recorded"
    return 0
  fi
  material=$(mktemp); TEMP_FILES+=("$material")
  jq -cS '{deploymentId,region,vpcId:(.resources.vpc_id // ""),
    ecsInstanceIds:((.resources.ecs_instance_ids // .resources.ecsInstanceIds // {}) | [.[]] | unique | sort)}' \
    "$manifest" >"$material"
  sha256_file "$material"
}

resolve_upgrade_project_source_dir() {
  local supplied=$1 root marker project
  root=$(cd -- "$supplied" && pwd)
  marker="skills/deploying-autowonder-on-alibaba-cloud/assets/systemd/autowonder.service"
  if [[ -f "$root/$marker" ]]; then
    printf '%s\n' "$root"
    return 0
  fi
  local candidates=()
  while IFS= read -r project; do
    [[ -f "$project/VERSION" && -f "$project/pom.xml" ]] && candidates+=("$project")
  done < <(
    find "$root" -type f -path "*/$marker" \
      -not -path '*/.git/*' -not -path '*/target/*' -not -path '*/node_modules/*' \
      -print | sed "s#/$marker\$##"
  )
  case ${#candidates[@]} in
    0) printf '%s\n' "$root" ;;
    1) printf '%s\n' "${candidates[0]}" ;;
    *) die "multiple AutoWonder project directories found in source worktree" ;;
  esac
}

workspace_content_identity() {
  local supplied=$1 root material
  root=$(cd -- "$supplied" && pwd)
  material=$(mktemp); TEMP_FILES+=("$material")
  find "$root" -type f \
    -not -path '*/.git/*' -not -path '*/target/*' -not -path '*/node_modules/*' \
    -not -path '*/frontend/dist/*' -not -path '*/upgrade-info/*' \
    -not -path '*/__pycache__/*' -not -name '.DS_Store' -print |
    LC_ALL=C sort | while IFS= read -r file; do
      printf '%s\t%s\n' "${file#"$root"/}" "$(sha256_file "$file")"
    done >"$material"
  sha256_file "$material" | cut -c1-40
}

resolve_active_commit_from_prefix() {
  local manifest=$1 prefix=$2 expected source_dir project_root resolved
  [[ "$prefix" =~ ^[0-9a-f]{12}$ ]] || die "active release directory is not an expected commit prefix"
  expected=$(jq -r '.deployment.activeCommit // .upgrade.fromCommit // .repositoryCommit // empty' "$manifest")
  if [[ "$expected" =~ ^[0-9a-f]{40}$ ]]; then
    [[ "${expected:0:12}" == "$prefix" ]] || die "manifest active commit does not match ECS active release"
    printf '%s\n' "$expected"
    return 0
  fi
  source_dir=$(jq -r '.localContext.sourceDirectory // empty' "$manifest")
  [[ -n "$source_dir" ]] || die "source repository is required to resolve the active commit"
  if [[ "$source_dir" != /* ]]; then
    project_root=$(cd -- "$(dirname -- "$manifest")/../.." && pwd)
    source_dir="$project_root/$source_dir"
  fi
  [[ -d "$source_dir/.git" || -f "$source_dir/.git" ]] || die "source repository is unavailable"
  resolved=$(git -C "$source_dir" rev-parse --verify "${prefix}^{commit}" 2>/dev/null) || \
    die "active commit prefix cannot be resolved in the source repository"
  [[ "$resolved" =~ ^[0-9a-f]{40}$ && "${resolved:0:12}" == "$prefix" ]] || \
    die "active commit prefix resolved unexpectedly"
  printf '%s\n' "$resolved"
}

calculate_upgrade_plan_fingerprint() {
  local manifest=$1 material
  material=$(mktemp); TEMP_FILES+=("$material")
  jq -cS '.upgrade | ({
    fromCommit,toCommit,targetRef,remote,forceRedeploy,commits,changedFiles,
    environment,pendingMigrations,blockedReasons,confirmationRequired,environmentContractChecked,
    environmentPlanSha256,databaseDestructive:.databaseCompatibility.destructive,
    targetVerificationFingerprint:.targetVerification.fingerprint
  } + (if .resourceSetFingerprint then {resourceSetFingerprint} else {} end))' "$manifest" >"$material"
  sha256_file "$material"
}

calculate_target_verification_fingerprint() {
  local manifest=$1 nodes=$2 material
  material=$(mktemp); TEMP_FILES+=("$material")
  jq -ncS --arg region "$(jq -r '.region' "$manifest")" \
    --arg deploymentId "$(jq -r '.deploymentId' "$manifest")" \
    --arg vpcId "$(jq -r '.resources.vpc_id // empty' "$manifest")" \
    --argjson tags "$(jq -c '.resources.expected_tags // .tags // {}' "$manifest")" \
    --argjson manifestInstanceIds "$(jq -c '(.resources.ecs_instance_ids // .resources.ecsInstanceIds // {}) | [.[]] | unique | sort' "$manifest")" \
    --argjson nodes "$nodes" \
    '{region:$region,deploymentId:$deploymentId,vpcId:$vpcId,tags:$tags,
      manifestInstanceIds:$manifestInstanceIds,nodes:($nodes|sort_by(.instanceId))}' >"$material"
  sha256_file "$material"
}

require_current_target_verification() {
  local manifest=$1 nodes recorded actual age resource_set verified_resource_set
  [[ $(jq -r '.upgrade.targetVerification.status // empty' "$manifest") == verified ]] || \
    die "current live target verification is required"
  nodes=$(jq -c '.upgrade.targetVerification.nodes // []' "$manifest")
  recorded=$(jq -r '.upgrade.targetVerification.fingerprint // empty' "$manifest")
  actual=$(calculate_target_verification_fingerprint "$manifest" "$nodes")
  [[ -n "$recorded" && "$recorded" == "$actual" ]] || die "live target verification is stale"
  resource_set=$(current_resource_set_fingerprint "$manifest")
  verified_resource_set=$(jq -r '.upgrade.targetVerification.resourceSetFingerprint // empty' "$manifest")
  if [[ -n $(jq -r '.upgradeInfo.resourceSetFingerprint // empty' "$manifest") ]]; then
    [[ "$verified_resource_set" == "$resource_set" ]] || die "live target verification resource set is stale"
  fi
  age=$(jq -r '((now - (.upgrade.targetVerification.verifiedEpoch // 0)) | floor)' "$manifest")
  [[ "$age" =~ ^[0-9]+$ && "$age" -le 1800 ]] || die "live target verification expired"
}

require_upgrade_plan_approval() {
  local manifest=$1 recorded actual
  [[ $(jq -r '.upgrade.approval.status // empty' "$manifest") == approved ]] || \
    die "explicit approval of the current upgrade plan is required"
  recorded=$(jq -r '.upgrade.planFingerprint // empty' "$manifest")
  actual=$(calculate_upgrade_plan_fingerprint "$manifest")
  [[ -n "$recorded" && "$recorded" == "$actual" ]] || die "approved upgrade plan fingerprint is stale"
  [[ $(jq -r '.upgrade.approval.planFingerprint // empty' "$manifest") == "$recorded" ]] || \
    die "upgrade approval does not match the current plan"
  [[ $(jq -r '(.upgrade.blockedReasons // []) | length' "$manifest") == 0 ]] || \
    die "blocked upgrade plan cannot be executed"
}

require_upgrade_approval() {
  local manifest=$1
  require_current_target_verification "$manifest"
  require_upgrade_plan_approval "$manifest"
}

require_upgrade_acceptance_state() {
  local manifest=$1
  jq -e '
    (.resources.ecs_instance_ids // .resources.ecsInstanceIds) as $inventory |
    (if ($inventory | type) == "object" then [$inventory[]]
     elif ($inventory | type) == "array" then $inventory
     else [] end | unique | sort) as $expected |
    ((.rollingUpgrade.nodes // []) |
      map(select(.status == "passed") | .instanceId) | unique | sort) as $passed |
    ($expected | length) > 0 and $passed == $expected and
    .rollingUpgrade.status == "passed" and
    .rollingUpgrade.targetCommit == .repositoryCommit and
    .deployment.activeCommit == .repositoryCommit
  ' "$manifest" >/dev/null || \
    die "upgrade acceptance requires the approved target to be active on every node"
}

refresh_target_verification() {
  local manifest=$1
  bash "$UPGRADE_SCRIPT_DIR/verify-deployment-targets.sh" --manifest "$manifest" >/dev/null
}

refresh_and_require_upgrade_approval() {
  local manifest=$1
  require_upgrade_plan_approval "$manifest"
  refresh_target_verification "$manifest"
  require_upgrade_approval "$manifest"
}
