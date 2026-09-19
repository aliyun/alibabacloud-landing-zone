#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
source "$SCRIPT_DIR/lib.sh"

usage() { cat <<'EOF'
Usage: build-release.sh --manifest FILE --source-dir DIR --output-dir DIR
Builds and seals an approved upgrade target; workspace and Git identities remain verified.
EOF
}
manifest= source_dir= output_dir=
require_no_secret_args "$@"
while (($#)); do
  case "$1" in
    --manifest) manifest=${2:-}; shift 2;;
    --source-dir) source_dir=${2:-}; shift 2;;
    --output-dir) output_dir=${2:-}; shift 2;;
    --help|-h) usage; exit 0;;
    *) die "unknown argument";;
  esac
done
require_file "$manifest"; require_command jq; require_command mvn
python=${AUTOWONDER_PYTHON:-python3}; require_command "$python"
json_validate "$manifest"; reject_secret_keys "$manifest"
mode=$(jq -r '.mode // "new"' "$manifest")
[[ "$mode" == upgrade ]] || die "release builder is restricted to upgrades"
actual=
if [[ "$mode" == upgrade ]]; then
  expected=$(json_string "$manifest" '.repositoryCommit')
  approved_plan=$(jq -c '.upgrade // {}' "$manifest")
  if [[ $(jq -r '.upgrade.sourceMode // empty' "$manifest") == workspace-current-content ]]; then
    source "$SCRIPT_DIR/upgrade-lib.sh"
    actual=$(workspace_content_identity "$source_dir")
    [[ "$actual" == "$expected" ]] || die "workspace content changed after upgrade approval; regenerate the plan"
  else
    require_command git
    [[ $(git -C "$source_dir" rev-parse --is-inside-work-tree 2>/dev/null) == true ]] || die "upgrade source is not a Git worktree"
    [[ -z $(git -C "$source_dir" status --porcelain --untracked-files=no) ]] || die "upgrade source has tracked changes"
    actual=$(git -C "$source_dir" rev-parse HEAD)
    [[ "$actual" == "$expected" ]] || die "upgrade source commit does not match manifest"
    git -C "$source_dir" merge-base --is-ancestor "$actual" "$actual" || die "upgrade commit is unavailable"
  fi
fi
version_file="$source_dir/VERSION"; require_file "$version_file"
release_version=$(tr -d '\r\n' <"$version_file")
[[ "$release_version" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$ ]] \
  || die "VERSION must contain a semantic version"
recommended_runtime_version=$(recommended_runtime_version_from_source "$source_dir") || \
  die "unable to resolve recommended runtime version from source application.yml"
build_result=$("$python" -B "$SCRIPT_DIR/release_build.py" --source-dir "$source_dir" --output-dir "$output_dir")
output_dir=$(jq -r '.directory' <<<"$build_result")
if [[ "$mode" == upgrade ]]; then
  [[ $(json_string "$manifest" '.repositoryCommit') == "$expected" && $(jq -c '.upgrade // {}' "$manifest") == "$approved_plan" ]] \
    || die "approved upgrade plan changed during build"
  if [[ $(jq -r '.upgrade.sourceMode // empty' "$manifest") == workspace-current-content ]]; then
    [[ $(workspace_content_identity "$source_dir") == "$expected" ]] || die "workspace source changed during build"
  else
    [[ $(git -C "$source_dir" rev-parse HEAD) == "$expected" && -z $(git -C "$source_dir" status --porcelain --untracked-files=no) ]] \
      || die "target Git source changed during build"
  fi
fi
read -r jar_hash schema_hash templates_hash migrations_hash < <(jq -r '[.artifacts["auto-wonder.jar"].sha256,.artifacts["autowonder-schema.sql"].sha256,.artifacts["autowonder-community-templates.sql"].sha256,.artifacts["autowonder-migrations.tar.gz"].sha256] | join(" ")' <<<"$build_result")
read -r jar_size schema_size templates_size migrations_size < <(jq -r '[.artifacts["auto-wonder.jar"].size,.artifacts["autowonder-schema.sql"].size,.artifacts["autowonder-community-templates.sql"].size,.artifacts["autowonder-migrations.tar.gz"].size] | map(tostring) | join(" ")' <<<"$build_result")
if [[ "$mode" != upgrade ]]; then actual=${jar_hash:0:40}; fi
atomic_jq "$manifest" --arg commit "$actual" --arg mode "$mode" --arg releaseVersion "$release_version" --arg recommendedRuntimeVersion "$recommended_runtime_version" --arg jarHash "$jar_hash" --arg schemaHash "$schema_hash" --arg templatesHash "$templates_hash" --arg migrationsHash "$migrations_hash" \
  --argjson jarSize "$jar_size" --argjson schemaSize "$schema_size" --argjson templatesSize "$templates_size" --argjson migrationsSize "$migrations_size" --arg dir "$output_dir" \
  '.repositoryCommit=$commit | .source=(if $mode == "upgrade" and (.upgrade.sourceMode // "") == "workspace-current-content" then {kind:"workspace",releaseId:$commit,gitValidation:"disabled",contentIdentity:"sha256-file-set"} elif $mode == "upgrade" then {kind:"git",releaseId:$commit,gitValidation:"required"} else {kind:"workspace",releaseId:$commit,gitValidation:"disabled"} end) | .releaseVersion=$releaseVersion | .recommendedRuntimeVersion=$recommendedRuntimeVersion | .artifacts={releaseDirectory:$dir,jar:{name:"auto-wonder.jar",sha256:$jarHash,size:$jarSize},schema:{name:"autowonder-schema.sql",sha256:$schemaHash,size:$schemaSize},templates:{name:"autowonder-community-templates.sql",sha256:$templatesHash,size:$templatesSize},migrations:{name:"autowonder-migrations.tar.gz",sha256:$migrationsHash,size:$migrationsSize}} | .phase="build" | .status="sealed"'
"$python" -B "$SCRIPT_DIR/upgrade_plan.py" \
  seal --manifest "$manifest" --source-dir "$source_dir"
printf 'JAR %s bytes SHA256 %s\nSchema %s bytes SHA256 %s\nTemplates %s bytes SHA256 %s\nMigrations %s bytes SHA256 %s\n' \
  "$jar_size" "$jar_hash" "$schema_size" "$schema_hash" "$templates_size" "$templates_hash" "$migrations_size" "$migrations_hash"
