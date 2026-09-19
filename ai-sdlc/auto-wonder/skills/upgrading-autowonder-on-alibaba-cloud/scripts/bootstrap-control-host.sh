#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)

# Git Bash is an entry surface, never the Windows deployment implementation.
case $(uname -s) in
  MINGW*|MSYS*|CYGWIN*)
    native_args=()
    while (($#)); do
      case "$1" in
        --manifest) native_args+=(-Manifest "$(cygpath -w "${2:?missing manifest}")"); shift 2;;
        --region) native_args+=(-Region "${2:?missing region}"); shift 2;;
        --expected-account-id) native_args+=(-ExpectedAccountId "${2:?missing account ID}"); shift 2;;
        --help|-h) printf 'Use bootstrap-control-host.ps1 -Manifest FILE -Region REGION\n'; exit 0;;
        *) printf 'unknown bootstrap argument\n' >&2; exit 2;;
      esac
    done
    exec powershell.exe -NoProfile -File "$(cygpath -w "$SCRIPT_DIR/windows/bootstrap-control-host.ps1")" "${native_args[@]}";;
esac

usage() {
  printf 'Usage: bootstrap-control-host.sh [--manifest FILE] [--region REGION] [--expected-account-id ID]\n'
}

manifest= region=cn-beijing expected_account_id=
while (($#)); do
  case "$1" in
    --manifest) manifest=${2:-}; shift 2;;
    --region) region=${2:-}; shift 2;;
    --expected-account-id) expected_account_id=${2:-}; shift 2;;
    --help|-h) usage; exit 0;;
    *) printf 'unknown argument\n' >&2; exit 2;;
  esac
done

# Managed dependencies are private, pinned downloads or validated system tools.
# Git and the OS cold-start utilities must already be available.
for tool in bash git openssl curl tar; do
  command -v "$tool" >/dev/null || { printf 'missing OS prerequisite: %s\n' "$tool" >&2; exit 1; }
done
source "$SCRIPT_DIR/runtime-env.sh"
autowonder_runtime_environment

source "$SCRIPT_DIR/lib.sh"
if [[ -n "$manifest" ]]; then
  require_file "$manifest"
  json_validate "$manifest"
  configure_cloud_profile "$manifest"
  region=$(json_string "$manifest" '.region')
  expected_account_id=${expected_account_id:-$(jq -r '.accountUid // empty' "$manifest")}
else
  bind_auto_wonder_cloud_profile
fi
ensure_alicloud_profile_identity "$region"
account_id=$(jq -er '.AccountId' <<<"$AUTOWONDER_IDENTITY_JSON") || die "Alibaba Cloud identity response has no account ID"
[[ -z "$expected_account_id" || "$account_id" == "$expected_account_id" ]] ||
  die "Alibaba Cloud account identity mismatch"
runtime_environment=$("$AUTOWONDER_PYTHON" -c 'import json,os; print(json.dumps({k:os.environ[k] for k in ("AUTOWONDER_PYTHON","JAVA_HOME","PATH","PYTHONUTF8","PYTHONDONTWRITEBYTECODE")}))')
jq -cn --arg profile "$AUTOWONDER_CLOUD_PROFILE" --arg region "$region" --arg accountId "$account_id" --argjson runtimeEnvironment "$runtime_environment" \
  '{platform:"posix",profile:$profile,region:$region,accountId:$accountId,validated:true,runtimeEnvironment:$runtimeEnvironment}'
