#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)

# Keep the public deployment entrypoint intentionally thin. The shared
# implementation rejects operations outside the deployment boundary and is
# also used by the upgrade skill through its own scoped wrapper.
export AUTOWONDER_OPERATION_SCOPE=deployment
exec bash "$SCRIPT_DIR/internal/operations.sh" "$@"
