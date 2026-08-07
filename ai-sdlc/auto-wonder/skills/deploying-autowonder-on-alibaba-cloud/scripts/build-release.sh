#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
source "$SCRIPT_DIR/lib.sh"

usage() { cat <<'EOF'
Usage: build-release.sh --manifest FILE --source-dir DIR --output-dir DIR
Builds and seals the exact manifest commit. The source must have no tracked changes.
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
require_file "$manifest"; require_command jq; require_command git; require_command mvn; require_command jar; require_command tar
[[ $(git -C "$source_dir" rev-parse --is-inside-work-tree 2>/dev/null) == true ]] || die "source is not a Git worktree"
json_validate "$manifest"; reject_secret_keys "$manifest"
[[ -z $(git -C "$source_dir" status --porcelain --untracked-files=no) ]] || die "source has tracked changes"
actual=$(git -C "$source_dir" rev-parse HEAD)
expected=$(json_string "$manifest" '.repositoryCommit')
[[ "$expected" == HEAD || "$actual" == "$expected" ]] || die "source commit does not match manifest"
git -C "$source_dir" merge-base --is-ancestor "$actual" "$actual" || die "commit is unavailable"
(cd "$source_dir" && mvn -DskipGitCommitId=true -DskipFrontend=false clean verify)
jar="$source_dir/target/auto-wonder.jar"; schema="$source_dir/docs/autowonder-schema.sql"
templates="$source_dir/docs/autowonder-community-templates.sql"
require_file "$jar"; require_file "$schema"; require_file "$templates"
jar tf "$jar" | awk '$0 == "BOOT-INF/classes/static/index.html" { found=1 } END { exit !found }' \
  || die "release JAR is missing frontend static/index.html"
jar tf "$jar" | awk 'index($0, "BOOT-INF/classes/static/assets/") == 1 && $0 !~ /\/$/ { found=1 } END { exit !found }' \
  || die "release JAR is missing frontend static assets"
mkdir -p "$output_dir"; chmod 700 "$output_dir"
install -m 0444 "$jar" "$output_dir/auto-wonder.jar"
install -m 0444 "$schema" "$output_dir/autowonder-schema.sql"
install -m 0444 "$templates" "$output_dir/autowonder-community-templates.sql"
LC_ALL=C tar -czf "$output_dir/autowonder-migrations.tar.gz" -C "$source_dir/docs/migration" .
chmod 0444 "$output_dir/autowonder-migrations.tar.gz"
jar_hash=$(sha256_file "$output_dir/auto-wonder.jar"); schema_hash=$(sha256_file "$output_dir/autowonder-schema.sql")
templates_hash=$(sha256_file "$output_dir/autowonder-community-templates.sql")
migrations_hash=$(sha256_file "$output_dir/autowonder-migrations.tar.gz")
jar_size=$(wc -c <"$output_dir/auto-wonder.jar" | tr -d ' '); schema_size=$(wc -c <"$output_dir/autowonder-schema.sql" | tr -d ' ')
templates_size=$(wc -c <"$output_dir/autowonder-community-templates.sql" | tr -d ' ')
migrations_size=$(wc -c <"$output_dir/autowonder-migrations.tar.gz" | tr -d ' ')
atomic_jq "$manifest" --arg commit "$actual" --arg jarHash "$jar_hash" --arg schemaHash "$schema_hash" --arg templatesHash "$templates_hash" --arg migrationsHash "$migrations_hash" \
  --argjson jarSize "$jar_size" --argjson schemaSize "$schema_size" --argjson templatesSize "$templates_size" --argjson migrationsSize "$migrations_size" --arg dir "$output_dir" \
  '.repositoryCommit=$commit | .artifacts={releaseDirectory:$dir,jar:{name:"auto-wonder.jar",sha256:$jarHash,size:$jarSize},schema:{name:"autowonder-schema.sql",sha256:$schemaHash,size:$schemaSize},templates:{name:"autowonder-community-templates.sql",sha256:$templatesHash,size:$templatesSize},migrations:{name:"autowonder-migrations.tar.gz",sha256:$migrationsHash,size:$migrationsSize}} | .phase="build" | .status="sealed"'
printf 'JAR %s bytes SHA256 %s\nSchema %s bytes SHA256 %s\nTemplates %s bytes SHA256 %s\nMigrations %s bytes SHA256 %s\n' \
  "$jar_size" "$jar_hash" "$schema_size" "$schema_hash" "$templates_size" "$templates_hash" "$migrations_size" "$migrations_hash"
