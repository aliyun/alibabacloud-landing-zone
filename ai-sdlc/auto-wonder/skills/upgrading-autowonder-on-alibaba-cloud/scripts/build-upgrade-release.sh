#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
DEPLOY_BUILD="$SCRIPT_DIR/../../deploying-autowonder-on-alibaba-cloud/scripts/build-release.sh"
source "$SCRIPT_DIR/upgrade-lib.sh"

manifest=
source_dir=
output_dir=
require_no_secret_args "$@"
while (($#)); do
  case "$1" in
    --manifest) manifest=${2:-}; shift 2 ;;
    --source-dir) source_dir=${2:-}; shift 2 ;;
    --output-dir) output_dir=${2:-}; shift 2 ;;
    --help|-h) printf 'Usage: build-upgrade-release.sh --manifest FILE --source-dir DIR --output-dir DIR\n'; exit 0 ;;
    *) die "unknown argument" ;;
  esac
done
[[ -n "$manifest" && -f "$manifest" ]] || die "upgrade build requires --manifest FILE"
[[ -n "$source_dir" && -n "$output_dir" ]] || die "upgrade build requires source and output directories"
[[ -n "$source_dir" ]] && source_dir=$(resolve_upgrade_project_source_dir "$source_dir")
[[ -f "$source_dir/skills/deploying-autowonder-on-alibaba-cloud/assets/systemd/autowonder.service" ]] || \
  die "upgrade target source is missing its versioned systemd unit"
refresh_and_require_upgrade_approval "$manifest"
bash "$DEPLOY_BUILD" --manifest "$manifest" --source-dir "$source_dir" --output-dir "$output_dir"
unit_source="$source_dir/skills/deploying-autowonder-on-alibaba-cloud/assets/systemd/autowonder.service"
unit_target="$output_dir/autowonder.service"
install -m 0444 "$unit_source" "$unit_target"
unit_hash=$(sha256_file "$unit_target")
atomic_jq "$manifest" --arg hash "$unit_hash" '
  .artifacts.systemdUnit={name:"autowonder.service",sha256:$hash,source:"target-source"}
'
