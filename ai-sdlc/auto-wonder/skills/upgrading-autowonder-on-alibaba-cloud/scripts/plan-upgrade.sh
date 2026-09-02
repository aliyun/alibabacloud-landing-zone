#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
source "$SCRIPT_DIR/upgrade-lib.sh"

usage() { cat <<'EOF'
Usage: plan-upgrade.sh --manifest FILE --source-dir DIR
       [--remote NAME] [--current-commit SHA] [--env-file FILE] [--force-redeploy]
       [--workspace-current-content]
Analyzes exact remote master from either a fast-forwardable local master or a clean
detached worktree already pinned to remote master. It never merges divergent history.
EOF
}

manifest= source_dir= target_ref=master remote=origin current_commit= env_file= force_redeploy=false workspace_current_content=false
require_no_secret_args "$@"
while (($#)); do
  case "$1" in
    --manifest) manifest=${2:-}; shift 2;;
    --source-dir) source_dir=${2:-}; shift 2;;
    --remote) remote=${2:-}; shift 2;;
    --current-commit) current_commit=${2:-}; shift 2;;
    --env-file) env_file=${2:-}; shift 2;;
    --force-redeploy) force_redeploy=true; shift;;
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
if [[ "$workspace_current_content" == true ]]; then
  [[ "$force_redeploy" == true ]] || die "current-workspace planning is only permitted for an explicit forced redeployment"
  if [[ -z "$env_file" ]]; then env_file=$(jq -r '.localContext.protectedEnvFile // empty' "$manifest"); fi
  require_file "$env_file"; require_mode_600 "$env_file"; validate_env_file_syntax "$env_file"
  current_commit=$(jq -er '.upgradeInventory.activeCommit // .deployment.activeCommit // empty | select(test("^[0-9a-f]{40}$"))' "$manifest") || die "active release identity is unavailable"
  target_commit=$(workspace_content_identity "$source_dir")
  resource_set_fingerprint=$(current_resource_set_fingerprint "$manifest")
  env_hash=$(sha256_file "$env_file")
  atomic_jq "$manifest" --arg from "$current_commit" --arg to "$target_commit" --arg envHash "$env_hash" --arg resourceSet "$resource_set_fingerprint" '
    .mode="upgrade" | .repositoryRef="workspace-current-content" | .repositoryCommit=$to |
    .upgrade=((.upgrade // {}) + {
      fromCommit:$from,toCommit:$to,targetRef:"workspace-current-content",remote:"none",forceRedeploy:true,
      sourceMode:"workspace-current-content",commits:[],changedFiles:[],
      environment:{added:[],removed:[],defaultChanged:[],required:[]},
      pendingMigrations:[],blockedReasons:[],confirmationRequired:false,
      environmentContractChecked:true,environmentPlanSha256:$envHash,
      resourceSetFingerprint:$resourceSet,
      databaseCompatibility:{destructive:false,rollingCompatible:true,rollbackSafe:true}
    } | del(.approval,.planFingerprint)) |
    .phase="upgrade-plan" | .status="planned"
  '
  fingerprint=$(calculate_upgrade_plan_fingerprint "$manifest")
  atomic_jq "$manifest" --arg fingerprint "$fingerprint" '.upgrade.planFingerprint=$fingerprint'
  jq '{phase,status,mode,upgrade:{fromCommit:.upgrade.fromCommit,toCommit:.upgrade.toCommit,targetRef:.upgrade.targetRef,forceRedeploy:.upgrade.forceRedeploy,sourceMode:.upgrade.sourceMode,confirmationRequired:.upgrade.confirmationRequired,planFingerprint:.upgrade.planFingerprint,blockedReasons:.upgrade.blockedReasons}}' "$manifest"
  exit 0
fi
require_command git
[[ $(git -C "$source_dir" rev-parse --is-inside-work-tree 2>/dev/null) == true ]] || die "source is not a Git worktree"
[[ -z $(git -C "$source_dir" status --porcelain --untracked-files=no) ]] || die "source has tracked changes"
source_branch=$(git -C "$source_dir" branch --show-current)
[[ -z "$source_branch" || "$source_branch" == master ]] || die "source must be local master or a detached remote-master worktree before planning"
remote_url=$(git -C "$source_dir" remote get-url "$remote" 2>/dev/null) || die "Git remote not found: $remote"
expected_repository_url=$(jq -r '.repositoryUrl // empty' "$manifest")
if [[ -n "$expected_repository_url" ]]; then
  normalize_repository_url() {
    printf '%s' "$1" | sed -E 's#^git@([^:]+):#https://\1/#; s#\.git$##; s#/$##'
  }
  [[ $(normalize_repository_url "$remote_url") == $(normalize_repository_url "$expected_repository_url") ]] || \
    die "Git remote does not match the deployment manifest repository"
fi
git -C "$source_dir" fetch "$remote" master >/dev/null 2>&1 || die "failed to fetch remote master"
if [[ "$source_branch" == master ]]; then
  git -C "$source_dir" merge --ff-only "refs/remotes/${remote}/master" >/dev/null 2>&1 || \
    die "local master diverges from remote master; preserve it and plan from a clean detached remote-master worktree"
fi

if [[ -z "$current_commit" ]]; then
  current_commit=$(jq -r '
    if .mode == "upgrade" then
      (.deployment.activeCommit // .upgrade.fromCommit // empty)
    else
      (.deployment.activeCommit // .repositoryCommit // empty)
    end
  ' "$manifest")
fi
[[ -n "$current_commit" && "$current_commit" != HEAD ]] || current_commit=$(git -C "$source_dir" rev-parse HEAD)
current_commit=$(git -C "$source_dir" rev-parse "${current_commit}^{commit}" 2>/dev/null) || die "current commit is unavailable"
[[ $(jq -r '.upgradeInventory.activeCommit // empty' "$manifest") == "$current_commit" ]] || die "active release inventory is stale; verify ECS releases again"

target_commit=$(git -C "$source_dir" rev-parse "refs/remotes/${remote}/master^{commit}" 2>/dev/null) || \
  die "remote master commit is unavailable"
[[ $(git -C "$source_dir" rev-parse 'HEAD^{commit}') == "$target_commit" ]] || \
  die "planning worktree does not match the fetched remote master commit"

if [[ "$target_commit" == "$current_commit" && "$force_redeploy" != true ]]; then
  jq -n --arg activeCommit "$current_commit" --arg targetCommit "$target_commit" --arg targetRef "$target_ref" \
    '{status:"already-latest",activeCommit:$activeCommit,targetCommit:$targetCommit,targetRef:$targetRef}'
  exit 0
fi

if [[ -z "$env_file" ]]; then env_file=$(jq -r '.localContext.protectedEnvFile // empty' "$manifest"); fi
[[ -n "$env_file" ]] || die "candidate protected environment file is required before an upgrade plan can be approved"
require_file "$env_file"; require_mode_600 "$env_file"; validate_env_file_syntax "$env_file"

work_dir=$(mktemp -d); TEMP_DIRS+=("$work_dir")
old_env="$work_dir/old-env.tsv"; new_env="$work_dir/new-env.tsv"
old_keys="$work_dir/old-keys"; new_keys="$work_dir/new-keys"
changed_files="$work_dir/changed-files.json"; commits="$work_dir/commits.json"
pending="$work_dir/pending.json"; blocked="$work_dir/blocked.txt"
: >"$blocked"

collect_env_contract() {
  local commit=$1 output=$2 raw="$work_dir/env-raw-$3" path pattern key key_file
  : >"$raw"; : >"$output"
  while IFS= read -r path; do
    case "$path" in
      docs/community/application.env.example)
        pattern='\$\{[A-Z][A-Z0-9_]*(:[^}]*)?\}|^[A-Z][A-Z0-9_]*=.*$';;
      src/main/resources/application*.yml|skills/deploying-autowonder-on-alibaba-cloud/assets/templates/*|skills/deploying-autowonder-on-alibaba-cloud/assets/systemd/*|skills/deploying-autowonder-on-alibaba-cloud/scripts/*.sh|skills/upgrading-autowonder-on-alibaba-cloud/scripts/*.sh)
        pattern='\$\{[A-Z][A-Z0-9_]*(:[^}]*)?\}';;
      *) continue;;
    esac
    git -C "$source_dir" show "$commit:./$path" 2>/dev/null |
      grep -Eo "$pattern" 2>/dev/null |
      sed -E 's/^\$\{//; s/\}$//' |
      awk -F '[:=]' -v source="$path" 'NF {print $1 "\t" $0 "\t" source}' >>"$raw" || true
  done < <(git -C "$source_dir" ls-tree -r --name-only "$commit" -- .)
  cut -f1 "$raw" | LC_ALL=C sort -u | while IFS= read -r key; do
    [[ -n "$key" ]] || continue
    key_file=$(mktemp); TEMP_FILES+=("$key_file")
    awk -F '\t' -v wanted="$key" '$1 == wanted {print $2}' "$raw" | LC_ALL=C sort -u >"$key_file"
    printf '%s\t%s\n' "$key" "$(sha256_file "$key_file")" >>"$output"
  done
}

collect_env_contract "$current_commit" "$old_env" old
collect_env_contract "$target_commit" "$new_env" new
cut -f1 "$old_env" >"$old_keys"; cut -f1 "$new_env" >"$new_keys"
added_env=$(comm -13 "$old_keys" "$new_keys" | jq -Rsc 'split("\n") | map(select(length > 0))')
removed_env=$(comm -23 "$old_keys" "$new_keys" | jq -Rsc 'split("\n") | map(select(length > 0))')
changed_env=$(join -t $'\t' "$old_env" "$new_env" | awk -F '\t' '$2 != $3 {print $1}' |
  jq -Rsc 'split("\n") | map(select(length > 0))')

runtime_manages_env_key() {
  case "$1" in
    AUTOWONDER_SECRET_MASTER_KEY|AUTOWONDER_JWT_SECRET|AUTOWONDER_PUBLIC_BASE_URL|AUTOWONDER_RUNTIME_RECOMMENDED_VERSION|AUTOWONDER_VERSION)
      return 0;;
    *)
      return 1;;
  esac
}

target_key_has_required_occurrence() {
  awk -F '\t' -v wanted="$1" '
    $1 == wanted {
      token=$2
      source=$3
      if (source !~ /^skills\/(deploying|upgrading)-autowonder-on-alibaba-cloud\/scripts\/.*\.sh$/ ||
          token !~ ("^" wanted ":-")) {
        required=1
      }
    }
    END { exit(required ? 0 : 1) }
  ' "$work_dir/env-raw-new"
}

candidate_s3_enabled=false
if [[ -n "$env_file" ]]; then
  candidate_s3_enabled=$(unquote_simple "$(env_raw_value "$env_file" S3_ENABLED)" |
    tr '[:upper:]' '[:lower:]')
fi

upgrade_requires_env_key() {
  runtime_manages_env_key "$1" && return 1
  target_key_has_required_occurrence "$1" || return 1
  case "$1" in
    S3_ENDPOINT|S3_ACCESS_KEY_ID|S3_ACCESS_KEY_SECRET)
      [[ "$candidate_s3_enabled" == true ]];;
    S3_ENABLED|S3_PUBLIC_ENDPOINT|S3_REGION)
      return 1;;
    *)
      return 0;;
  esac
}

required_env=$(
  while IFS= read -r key; do
    [[ -n "$key" ]] || continue
    upgrade_requires_env_key "$key" || continue
    printf '%s\n' "$key"
  done < <(jq -r '.[]' <<<"$added_env") |
    jq -Rsc 'split("\n") | map(select(length > 0))'
)

if [[ -n "$env_file" ]]; then
  while IFS= read -r key; do
    raw=$(env_raw_value "$env_file" "$key")
    if [[ -z "$raw" || "$raw" == "''" || "$raw" == '""' ]]; then
      printf 'required environment value missing: %s\n' "$key" >>"$blocked"
    fi
  done < <(jq -r '.[]' <<<"$required_env")
fi
environment_contract_checked=false; environment_plan_sha=
if [[ -n "$env_file" ]]; then environment_contract_checked=true; environment_plan_sha=$(sha256_file "$env_file"); fi

git -C "$source_dir" diff --relative --name-status "$current_commit..$target_commit" -- . |
  jq -Rsc 'split("\n") | map(select(length > 0) | capture("^(?<status>[^\\t]+)\\t(?<path>.+)$"))' >"$changed_files"
git -C "$source_dir" log --reverse --format=$'%H\t%s' "$current_commit..$target_commit" -- . |
  jq -Rsc 'split("\n") | map(select(length > 0) | capture("^(?<commit>[^\\t]+)\\t(?<subject>.*)$"))' >"$commits"

target_migrations="$work_dir/target-migrations"; old_migrations="$work_dir/old-migrations"
git -C "$source_dir" ls-tree -r --name-only "$target_commit" -- docs/migration |
  grep -E '^docs/migration/.*\.sql$' | LC_ALL=C sort >"$target_migrations" || true
git -C "$source_dir" ls-tree -r --name-only "$current_commit" -- docs/migration |
  grep -E '^docs/migration/.*\.sql$' | LC_ALL=C sort >"$old_migrations" || true
versions="$work_dir/versions"; : >"$versions"
while IFS= read -r path; do
  name=${path##*/}
  if [[ "$name" =~ ^V(0*[1-9][0-9]*)__([a-z0-9]+(_[a-z0-9]+)*)\.sql$ ]]; then
    version=$((10#${BASH_REMATCH[1]}))
    printf '%s\t%s\n' "$version" "$path" >>"$versions"
  else
    printf 'invalid migration filename: %s\n' "$path" >>"$blocked"
  fi
done <"$target_migrations"
cut -f1 "$versions" | sort -n | uniq -d | while IFS= read -r version; do
  [[ -n "$version" ]] && printf 'duplicate migration version: %s\n' "$version" >>"$blocked"
done

max_old=0
while IFS= read -r path; do
  name=${path##*/}
  if [[ "$name" =~ ^V(0*[1-9][0-9]*)__ ]]; then
    version=$((10#${BASH_REMATCH[1]}))
    ((version > max_old)) && max_old=$version
  fi
done <"$old_migrations"

: >"$pending"
while IFS=$'\t' read -r status path extra; do
  [[ -n "$status" ]] || continue
  case "$status" in
    A)
      [[ "$path" == docs/migration/*.sql ]] || continue
      name=${path##*/}; [[ "$name" =~ ^V(0*[1-9][0-9]*)__ ]] || continue
      version=$((10#${BASH_REMATCH[1]}))
      if ((version <= max_old)); then
        printf 'new migration version is not greater than published versions: %s\n' "$version" >>"$blocked"
      fi
      content="$work_dir/migration-$version.sql"
      git -C "$source_dir" show "$target_commit:./$path" >"$content"
      risks=$(grep -Eio '\b(ALTER|DROP|TRUNCATE|RENAME|CREATE|UPDATE|DELETE|INSERT)\b' "$content" |
        tr '[:lower:]' '[:upper:]' | LC_ALL=C sort -u | jq -Rsc 'split("\n") | map(select(length > 0))' || printf '[]')
      jq -cn --argjson version "$version" --arg file "$path" --arg sha256 "$(sha256_file "$content")" \
        --argjson risks "$risks" '{version:$version,file:$file,sha256:$sha256,riskOperations:$risks}' >>"$pending";;
    M|D|R*|C*)
      migration_path=$path
      [[ "$status" == R* || "$status" == C* ]] && migration_path=${extra:-$path}
      [[ "$path" == docs/migration/*.sql || "$migration_path" == docs/migration/*.sql ]] || continue
      printf 'published migration changed: %s\n' "$path" >>"$blocked";;
  esac
done < <(git -C "$source_dir" diff --relative --name-status "$current_commit..$target_commit" -- docs/migration)
pending_json=$(jq -s 'sort_by(.version)' "$pending")
destructive_migration=$(jq '[.[].riskOperations[]? | select(. == "DROP" or . == "TRUNCATE" or . == "RENAME")] | length > 0' <<<"$pending_json")
compatibility_status=not-required; rolling_allowed=true
if [[ $(jq 'length' <<<"$pending_json") != 0 ]]; then compatibility_status=review-required; rolling_allowed=false; fi
blocked_json=$(LC_ALL=C sort -u "$blocked" | jq -Rsc 'split("\n") | map(select(length > 0))')
confirmation_required=$(jq 'length > 0' <<<"$pending_json")
status=planned; [[ $(jq 'length' <<<"$blocked_json") == 0 ]] || status=blocked

atomic_jq "$manifest" \
  --arg from "$current_commit" --arg to "$target_commit" --arg targetRef "$target_ref" --arg remote "$remote" \
  --arg status "$status" --argjson commits "$(cat "$commits")" \
  --argjson files "$(cat "$changed_files")" --argjson added "$added_env" --argjson removed "$removed_env" \
  --argjson changed "$changed_env" --argjson required "$required_env" \
  --argjson migrations "$pending_json" --argjson blocked "$blocked_json" \
  --argjson forceRedeploy "$force_redeploy" \
  --argjson confirmation "$confirmation_required" --argjson envChecked "$environment_contract_checked" \
  --arg envPlanSha "$environment_plan_sha" \
  --arg compatibilityStatus "$compatibility_status" --argjson rollingAllowed "$rolling_allowed" \
  --argjson destructive "$destructive_migration" --arg resourceSetFingerprint "$resource_set_fingerprint" '
  .upgrade.targetVerification as $targetVerification |
  .mode="upgrade" | .deployment.activeCommit=(.deployment.activeCommit // $from) |
  .repositoryRef=$targetRef | .repositoryCommit=$to |
  .upgrade={fromCommit:$from,toCommit:$to,targetRef:$targetRef,remote:$remote,forceRedeploy:$forceRedeploy,
    commits:$commits,changedFiles:$files,
    environment:{added:$added,removed:$removed,changed:$changed,required:$required},
    pendingMigrations:$migrations,blockedReasons:$blocked,confirmationRequired:$confirmation,
    environmentContractChecked:$envChecked,environmentPlanSha256:$envPlanSha,environmentValidated:false,
    resourceSetFingerprint:$resourceSetFingerprint,
    databaseCompatibility:{status:$compatibilityStatus,rollingAllowed:$rollingAllowed,destructive:$destructive},
    databaseBackup:{status:"pending"},migrationApproved:false,approval:{status:"pending"},
    targetVerification:$targetVerification} |
  .acceptance={} | .phase="upgrade-plan" | .status=$status'

plan_fingerprint=$(calculate_upgrade_plan_fingerprint "$manifest")
atomic_jq "$manifest" --arg fingerprint "$plan_fingerprint" '.upgrade.planFingerprint=$fingerprint'

if [[ "$status" == blocked ]]; then die "upgrade plan is blocked; inspect sanitized manifest findings"; fi
jq '{phase,status,mode,upgrade:{fromCommit:.upgrade.fromCommit,toCommit:.upgrade.toCommit,targetRef:.upgrade.targetRef,environment:.upgrade.environment,pendingMigrations:.upgrade.pendingMigrations,confirmationRequired:.upgrade.confirmationRequired,planFingerprint:.upgrade.planFingerprint,blockedReasons:.upgrade.blockedReasons}}' "$manifest"
