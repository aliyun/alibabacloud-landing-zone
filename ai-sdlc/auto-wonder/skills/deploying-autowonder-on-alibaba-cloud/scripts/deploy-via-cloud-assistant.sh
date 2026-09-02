#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)

# Bind the shared transfer implementation to deployment-only semantics.
export AUTOWONDER_TRANSFER_SCOPE=deployment
exec bash "$SCRIPT_DIR/internal/release-transfer.sh" "$@"
