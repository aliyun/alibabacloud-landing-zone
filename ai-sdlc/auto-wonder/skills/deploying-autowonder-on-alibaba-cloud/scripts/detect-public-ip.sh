#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# Only Python is needed here; do not initialize build/cloud dependencies.
case $(uname -s) in
  MINGW*|MSYS*|CYGWIN*) exec powershell.exe -NoProfile -File "$(cygpath -w "$SCRIPT_DIR/windows/detect-public-ip.ps1")" "$@" ;;
esac
python=${AUTOWONDER_PYTHON:-}
[[ -n "$python" ]] || python=$(sh "$SCRIPT_DIR/runtime-bootstrap.sh")
exec "$python" "$SCRIPT_DIR/public_ip.py" "$@"
