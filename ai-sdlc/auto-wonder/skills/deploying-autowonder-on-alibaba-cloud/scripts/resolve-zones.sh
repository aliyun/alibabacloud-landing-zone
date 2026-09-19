#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# Only Python is needed here; do not initialize build/cloud dependencies.
case $(uname -s) in
  MINGW*|MSYS*|CYGWIN*) exec powershell.exe -NoProfile -File "$(cygpath -w "$SCRIPT_DIR/windows/resolve-zones.ps1")" "$@" ;;
esac
python=${AUTOWONDER_PYTHON:-}
[[ -n "$python" ]] || python=$(sh "$SCRIPT_DIR/runtime-bootstrap.sh")
# The documented entrypoint is `resolve-zones.sh --manifest FILE`; default to the
# resolve subcommand so that call works. `validate` and help stay explicit.
case "${1:-}" in
  resolve|validate|discover|check-plan|check-binding|-h|--help) ;;
  *) set -- resolve "$@" ;;
esac
exec "$python" "$SCRIPT_DIR/resolve_zones.py" "$@"
