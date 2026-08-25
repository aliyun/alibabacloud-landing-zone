#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)

manifest= search_root=${PWD} deployment_dir=
while (($#)); do
  case "$1" in
    --manifest) manifest=${2:-}; shift 2 ;;
    --search-root) search_root=${2:-}; shift 2 ;;
    --deployment-dir) deployment_dir=${2:-}; shift 2 ;;
    --help|-h)
      printf 'Usage: resolve-deployment.sh [--manifest FILE] [--search-root DIR] [--deployment-dir DIR]\n'
      exit 0
      ;;
    *) printf 'Unknown argument\n' >&2; exit 2 ;;
  esac
done

arguments=(locate --project-root "$search_root")
[[ -z "$manifest" ]] || arguments+=(--manifest "$manifest")
[[ -z "$deployment_dir" ]] || arguments+=(--deployment-dir "$deployment_dir")
exec python3 "$SCRIPT_DIR/upgrade_info.py" "${arguments[@]}"
