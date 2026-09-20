#!/usr/bin/env bash

UPGRADE_SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
UPGRADE_DEPLOY_SKILL_DIR=$(cd -- "$UPGRADE_SCRIPT_DIR/.." && pwd)
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
  python3 -B "$UPGRADE_SCRIPT_DIR/upgrade_plan.py" content-identity --source-dir "$1"
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
  python3 -B "$UPGRADE_SCRIPT_DIR/upgrade_plan.py" fingerprint --manifest "$1"
}

upsert_candidate_runtime_recommended_version() {
  local env_file=$1 version=$2 normalized
  [[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$ ]] || \
    die "target recommended runtime version must be semantic"
  normalized=$(mktemp "${env_file}.tmp.XXXXXX"); TEMP_FILES+=("$normalized")
  awk -v key="AUTOWONDER_RUNTIME_RECOMMENDED_VERSION" -v value="$version" '
    index($0, key "=") == 1 { if (!written) print key "=" value; written=1; next }
    { print }
    END { if (!written) print key "=" value }
  ' "$env_file" >"$normalized"
  chmod 600 "$normalized"
  mv -f -- "$normalized" "$env_file"
}

calculate_target_verification_fingerprint() {
  local manifest=$1 nodes=$2 material
  material=$(mktemp); TEMP_FILES+=("$material")
  jq -ncS --arg region "$(jq -r '.region' "$manifest")" \
    --arg deploymentId "$(jq -r '.deploymentId' "$manifest")" \
    --arg vpcId "$(jq -r '.resources.vpc_id // empty' "$manifest")" \
    --argjson tags "$(jq -c '.resources.expected_tags // .tags // {}' "$manifest")" \
    --argjson manifestInstanceIds "$(jq -c '(.resources.ecs_instance_ids // .resources.ecsInstanceIds // {}) | [.[]] | unique | sort' "$manifest")" \
    --arg tagVerificationMode "$(jq -r '.upgradeInfo.tagVerificationMode // empty' "$manifest")" \
    --argjson nodes "$nodes" \
    '{region:$region,deploymentId:$deploymentId,vpcId:$vpcId,tags:$tags,
      manifestInstanceIds:$manifestInstanceIds,nodes:($nodes|sort_by(.instanceId))} +
      (if $tagVerificationMode != "" then {tagVerificationMode:$tagVerificationMode} else {} end)' >"$material"
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

require_unmutated_database_for_rollback() {
  jq -e '
    (.upgrade.databaseMutationStarted != true) and
    (((.upgrade.databaseMigration.applied // []) | length) == 0) and
    ((.upgrade.databaseMigration.status // "pending") as $status |
      (["running", "failed", "passed", "applied"] | index($status)) == null)
  ' "$1" >/dev/null || die "application rollback is blocked after database migration starts; use a reviewed recovery plan"
}

require_current_upgrade_backup() {
  jq -e '
    (.resources.ecs_instance_ids // .resources.ecsInstanceIds | [.[]] | unique | sort) as $expected |
    .upgrade as $upgrade | $upgrade.rollbackBackup as $backup |
    ($expected | length) > 0 and $backup.status == "passed" and
    $backup.planFingerprint == $upgrade.planFingerprint and
    $backup.fromCommit == $upgrade.fromCommit and $backup.targetCommit == $upgrade.toCommit and
    ($backup.nodes | length) == ($expected | length) and
    ([$backup.nodes[] | select(.status == "passed" and (.sha256 | test("^[0-9a-f]{64}$"))) | .instanceId] | sort) == $expected
  ' "$1" >/dev/null || die "verified per-ECS rollback backup must match the current plan and every target"
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
