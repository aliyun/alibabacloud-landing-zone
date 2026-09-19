#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
source "$SCRIPT_DIR/lib.sh"
manifest= confirmation=
require_no_secret_args "$@"
while (($#)); do
  case "$1" in
    --manifest) manifest=${2:-}; shift 2;;
    --confirmation-file) confirmation=${2:-}; shift 2;;
    --help|-h) printf 'Usage: bash reconcile-released-rds-database.sh --manifest FILE --confirmation-file FILE\n'; exit 0;;
    *) die "unknown argument";;
  esac
done
require_file "$manifest"; require_file "$confirmation"
json_validate "$manifest"; reject_secret_keys "$manifest"
require_remote_submission_settled "$manifest"
configure_cloud_profile "$manifest"
ensure_alicloud_profile_identity "$(json_string "$manifest" '.region')"
export TF_CLI_CONFIG_FILE
TF_CLI_CONFIG_FILE=$(bash "$SCRIPT_DIR/configure-terraform-acceleration.sh")
python3 "$SCRIPT_DIR/reconcile_released_rds_database.py" --manifest "$manifest" --confirmation-file "$confirmation"
