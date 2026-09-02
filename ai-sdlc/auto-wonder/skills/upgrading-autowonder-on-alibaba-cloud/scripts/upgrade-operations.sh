#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
LOCAL_OPERATIONS="$SCRIPT_DIR/internal/operations.sh"
source "$SCRIPT_DIR/upgrade-lib.sh"

case ${1:-} in
  upgrade-inventory)
    manifest=
    previous=
    for argument in "$@"; do
      [[ "$previous" != --manifest ]] || manifest=$argument
      previous=$argument
    done
    [[ -n "$manifest" && -f "$manifest" ]] || die "upgrade inventory requires --manifest FILE"
    refresh_target_verification "$manifest"
    ;;
  upgrade-backup|rollback-upgrade|database-migrate|rolling-upgrade|runtime-config|acceptance)
    manifest=
    previous=
    for argument in "$@"; do
      [[ "$previous" != --manifest ]] || manifest=$argument
      previous=$argument
    done
    [[ -n "$manifest" && -f "$manifest" ]] || die "upgrade operation requires --manifest FILE"
    refresh_and_require_upgrade_approval "$manifest"
    ;;
  *)
    printf 'Unsupported upgrade operation: %s\n' "${1:-<missing>}" >&2
    exit 2
    ;;
esac

export AUTOWONDER_OPERATION_SCOPE=upgrade
bash "$LOCAL_OPERATIONS" "$@"

manifest_path=$(cd -- "$(dirname -- "$manifest")" && pwd)/$(basename -- "$manifest")
if [[ "$manifest_path" == */upgrade-info/*/manifest.json ]]; then
  project_root=$(cd -- "$(dirname -- "$manifest_path")/../.." && pwd)
  python3 "$SCRIPT_DIR/upgrade_info.py" sync-manifest \
    --project-root "$project_root" --manifest "$manifest_path" --operation "${1:-unknown}" >/dev/null
fi
