#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
LOCAL_TRANSFER="$SCRIPT_DIR/internal/release-transfer.sh"
source "$SCRIPT_DIR/upgrade-lib.sh"

manifest=
previous=
for argument in "$@"; do
  [[ "$argument" != --config-only ]] || {
    printf 'Config-only deployment is not an upgrade staging operation.\n' >&2
    exit 2
  }
  [[ "$previous" != --manifest ]] || manifest=$argument
  previous=$argument
done

[[ -n "$manifest" && -f "$manifest" ]] || {
  printf 'Upgrade staging requires --manifest FILE.\n' >&2
  exit 2
}
command -v jq >/dev/null 2>&1 || { printf 'jq is required.\n' >&2; exit 2; }
[[ $(jq -r '.mode // empty' "$manifest") == upgrade ]] || {
  printf 'Upgrade staging requires a completed upgrade plan.\n' >&2
  exit 2
}
refresh_and_require_upgrade_approval "$manifest"

export AUTOWONDER_TRANSFER_SCOPE=upgrade
exec bash "$LOCAL_TRANSFER" "$@" --stage-only
