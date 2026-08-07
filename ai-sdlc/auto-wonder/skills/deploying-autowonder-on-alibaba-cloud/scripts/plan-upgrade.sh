#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
source "$SCRIPT_DIR/lib.sh"

usage() { cat <<'EOF'
Usage: plan-upgrade.sh --manifest FILE --source-dir DIR --target-ref REF
       [--remote NAME] [--current-commit SHA] [--env-file FILE]
Fetches and analyzes an exact upgrade target. It never pulls, merges, checks out, builds, or deploys.
EOF
}

manifest= source_dir= target_ref= remote=origin current_commit= env_file=
require_no_secret_args "$@"
while (($#)); do
  case "$1" in
    --manifest) manifest=${2:-}; shift 2;;
    --source-dir) source_dir=${2:-}; shift 2;;
    --target-ref) target_ref=${2:-}; shift 2;;
    --remote) remote=${2:-}; shift 2;;
    --current-commit) current_commit=${2:-}; shift 2;;
    --env-file) env_file=${2:-}; shift 2;;
    --help|-h) usage; exit 0;;
    *) die "unknown argument";;
  esac
done

require_file "$manifest"
require_command git; require_command jq
[[ $(git -C "$source_dir" rev-parse --is-inside-work-tree 2>/dev/null) == true ]] || die "source is not a Git worktree"
[[ -n "$target_ref" ]] || die "target ref is required"
json_validate "$manifest"; reject_secret_keys "$manifest"
[[ $(jq -r '.upgradeInventory.status // empty' "$manifest") == verified ]] || die "verified active release inventory is required before planning"
[[ -z $(git -C "$source_dir" status --porcelain --untracked-files=no) ]] || die "source has tracked changes"
[[ -z "$env_file" ]] || { require_file "$env_file"; require_mode_600 "$env_file"; }

git -C "$source_dir" remote get-url "$remote" >/dev/null 2>&1 || die "Git remote not found: $remote"
git -C "$source_dir" fetch "$remote" --prune >/dev/null 2>&1 || die "failed to fetch Git remote"

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

if target_commit=$(git -C "$source_dir" rev-parse "refs/remotes/${remote}/${target_ref}^{commit}" 2>/dev/null); then :
elif target_commit=$(git -C "$source_dir" rev-parse "${target_ref}^{commit}" 2>/dev/null); then :
else die "target commit is unavailable"; fi

work_dir=$(mktemp -d); TEMP_DIRS+=("$work_dir")
old_env="$work_dir/old-env.tsv"; new_env="$work_dir/new-env.tsv"
old_keys="$work_dir/old-keys"; new_keys="$work_dir/new-keys"
changed_files="$work_dir/changed-files.json"; commits="$work_dir/commits.json"
pending="$work_dir/pending.json"; blocked="$work_dir/blocked.txt"
: >"$blocked"

collect_env_contract() {
  local commit=$1 output=$2 raw="$work_dir/env-raw-$3" path key key_file
  : >"$raw"; : >"$output"
  while IFS= read -r path; do
    case "$path" in
      src/main/resources/application*.yml|docs/community/application.env.example|skills/deploying-autowonder-on-alibaba-cloud/assets/templates/*|skills/deploying-autowonder-on-alibaba-cloud/assets/systemd/*|skills/deploying-autowonder-on-alibaba-cloud/scripts/*.sh)
        git -C "$source_dir" show "$commit:./$path" 2>/dev/null |
          grep -Eo '\$\{[A-Z][A-Z0-9_]*(:[^}]*)?\}|^[A-Z][A-Z0-9_]*=.*$' 2>/dev/null |
          sed -E 's/^\$\{//; s/\}$//' |
          awk -F '[:=]' 'NF {print $1 "\t" $0}' >>"$raw" || true;;
    esac
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

if [[ -n "$env_file" ]]; then
  while IFS= read -r key; do
    [[ -n "$key" ]] || continue
    raw=$(env_raw_value "$env_file" "$key")
    if [[ -z "$raw" || "$raw" == "''" || "$raw" == '""' ]]; then
      printf 'required environment value missing: %s\n' "$key" >>"$blocked"
    fi
  done < <(jq -r '.[]' <<<"$added_env")
fi
environment_contract_checked=false; environment_plan_sha=
if [[ -n "$env_file" ]]; then environment_contract_checked=true; environment_plan_sha=$(sha256_file "$env_file"); fi

linear=false
if git -C "$source_dir" merge-base --is-ancestor "$current_commit" "$target_commit"; then
  linear=true
else
  printf '%s\n' 'target is not a descendant of the active commit' >>"$blocked"
fi

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
confirmation_required=$(jq -n --argjson added "$added_env" --argjson removed "$removed_env" \
  --argjson changed "$changed_env" --argjson migrations "$pending_json" --argjson linear "$linear" \
  '($added|length)>0 or ($removed|length)>0 or ($changed|length)>0 or ($migrations|length)>0 or ($linear|not)')
status=planned; [[ $(jq 'length' <<<"$blocked_json") == 0 ]] || status=blocked

atomic_jq "$manifest" \
  --arg from "$current_commit" --arg to "$target_commit" --arg targetRef "$target_ref" --arg remote "$remote" \
  --arg status "$status" --argjson linear "$linear" --argjson commits "$(cat "$commits")" \
  --argjson files "$(cat "$changed_files")" --argjson added "$added_env" --argjson removed "$removed_env" \
  --argjson changed "$changed_env" --argjson migrations "$pending_json" --argjson blocked "$blocked_json" \
  --argjson confirmation "$confirmation_required" --argjson envChecked "$environment_contract_checked" \
  --arg envPlanSha "$environment_plan_sha" \
  --arg compatibilityStatus "$compatibility_status" --argjson rollingAllowed "$rolling_allowed" \
  --argjson destructive "$destructive_migration" '
  .mode="upgrade" | .deployment.activeCommit=(.deployment.activeCommit // $from) |
  .repositoryRef=$targetRef | .repositoryCommit=$to |
  .upgrade={fromCommit:$from,toCommit:$to,targetRef:$targetRef,remote:$remote,linearHistory:$linear,
    commits:$commits,changedFiles:$files,environment:{added:$added,removed:$removed,changed:$changed},
    pendingMigrations:$migrations,blockedReasons:$blocked,confirmationRequired:$confirmation,
    environmentContractChecked:$envChecked,environmentPlanSha256:$envPlanSha,environmentValidated:false,
    databaseCompatibility:{status:$compatibilityStatus,rollingAllowed:$rollingAllowed,destructive:$destructive},
    databaseBackup:{status:"pending"},migrationApproved:false} |
  .phase="upgrade-plan" | .status=$status'

if [[ "$status" == blocked ]]; then die "upgrade plan is blocked; inspect sanitized manifest findings"; fi
jq '{phase,status,mode,upgrade:{fromCommit:.upgrade.fromCommit,toCommit:.upgrade.toCommit,linearHistory:.upgrade.linearHistory,environment:.upgrade.environment,pendingMigrations:.upgrade.pendingMigrations,confirmationRequired:.upgrade.confirmationRequired,blockedReasons:.upgrade.blockedReasons}}' "$manifest"
