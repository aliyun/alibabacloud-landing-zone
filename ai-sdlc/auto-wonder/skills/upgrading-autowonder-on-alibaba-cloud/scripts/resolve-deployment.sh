#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)

# Read the control-host directory without adding PWD to the application environment contract.
manifest= search_root=$(pwd) deployment_dir= region= deployment_id=
while (($#)); do
  case "$1" in
    --manifest) manifest=${2:-}; shift 2 ;;
    --search-root) search_root=${2:-}; shift 2 ;;
    --region) region=${2:-}; shift 2 ;;
    --deployment-id) deployment_id=${2:-}; shift 2 ;;
    --deployment-dir) deployment_dir=${2:-}; shift 2 ;;
    --help|-h)
      printf 'Usage: resolve-deployment.sh [--manifest FILE] [--search-root DIR] [--deployment-dir DIR] [--region REGION] [--deployment-id ID]\n'
      exit 0
      ;;
    *) printf 'Unknown argument\n' >&2; exit 2 ;;
  esac
done

arguments=(resolve --project-root "$search_root")
[[ -z "$manifest" ]] || arguments+=(--manifest "$manifest")
[[ -z "$deployment_dir" ]] || arguments+=(--deployment-dir "$deployment_dir")
[[ -z "$region" ]] || arguments+=(--region "$region")
[[ -z "$deployment_id" ]] || arguments+=(--deployment-id "$deployment_id")
exec python3 "$SCRIPT_DIR/operations-store.py" "${arguments[@]}"
