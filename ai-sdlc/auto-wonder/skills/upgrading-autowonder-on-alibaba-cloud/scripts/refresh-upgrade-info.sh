#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
source "$SCRIPT_DIR/upgrade-lib.sh"

project_root= manifest=
while (($#)); do
  case "$1" in
    --project-root) project_root=${2:-}; shift 2 ;;
    --manifest) manifest=${2:-}; shift 2 ;;
    --help|-h)
      printf 'Usage: refresh-upgrade-info.sh --project-root DIR --manifest FILE\n'
      exit 0
      ;;
    *) printf 'Unknown argument\n' >&2; exit 2 ;;
  esac
done

[[ -n "$project_root" && -n "$manifest" ]] || {
  printf 'Project root and manifest are required\n' >&2
  exit 2
}

require_file "$manifest"
require_command jq
require_command aliyun
require_command python3
json_validate "$manifest"
bootstrap=$(bash "$UPGRADE_SCRIPT_DIR/bootstrap-control-host.sh" --manifest "$manifest")
# A child bootstrap cannot change this shell's PATH. Carry its selected tools
# forward explicitly instead of falling back to the original system Python.
for name in AUTOWONDER_PYTHON JAVA_HOME PATH PYTHONUTF8 PYTHONDONTWRITEBYTECODE; do
  value=$(jq -er --arg name "$name" '.runtimeEnvironment[$name]' <<<"$bootstrap")
  export "$name=$value"
done
configure_cloud_profile "$manifest"
region=$(json_string "$manifest" '.region')
load_alicloud_profile_credentials "$region"

python3 "$SCRIPT_DIR/upgrade_info.py" refresh \
  --project-root "$project_root" --manifest "$manifest"
