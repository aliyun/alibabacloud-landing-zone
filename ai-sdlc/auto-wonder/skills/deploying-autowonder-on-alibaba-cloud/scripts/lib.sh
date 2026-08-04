#!/usr/bin/env bash
set -euo pipefail

umask 077
TEMP_FILES=()

cleanup() {
  local file
  set +u
  for file in "${TEMP_FILES[@]}"; do
    if [[ -n "$file" ]]; then rm -f -- "$file"; fi
  done
  set -u
  return 0
}
trap cleanup EXIT INT TERM

die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
log() { printf 'INFO: %s\n' "$*" >&2; }
require_command() { command -v "$1" >/dev/null 2>&1 || die "required command missing: $1"; }
require_file() { [[ -f "$1" ]] || die "required file missing: $1"; }
json_required() { jq -er "$2 | select(. != null and . != \"\")" "$1" >/dev/null || die "manifest field missing"; }
sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{print $1}';
  else shasum -a 256 "$1" | awk '{print $1}'; fi
}
json_validate() { jq -e . "$1" >/dev/null 2>&1 || die "invalid JSON file: $1"; }
atomic_jq() {
  local file=$1; shift
  local tmp
  tmp=$(mktemp "${file}.tmp.XXXXXX")
  TEMP_FILES+=("$tmp")
  jq "$@" "$file" >"$tmp" || die "failed to update manifest"
  chmod --reference="$file" "$tmp" 2>/dev/null || chmod 600 "$tmp"
  mv -f -- "$tmp" "$file"
}
record_phase() {
  local file=$1 phase=$2 status=$3
  atomic_jq "$file" --arg phase "$phase" --arg status "$status" \
    '.phase=$phase | .status=$status | .phases=((.phases // []) + [{phase:$phase,status:$status,at:(now|todateiso8601)}])'
}
reject_secret_keys() {
  local file=$1
  jq -e '
    [paths(scalars) as $p | ($p[-1] | tostring | ascii_downcase) |
      gsub("[-_.]"; "") |
      select(test("^(password|passwordhash|accesskeysecret|masterkey|jwtsecret|presignedurl|executortoken)$"))] | length == 0
  ' "$file" >/dev/null || die "manifest contains a forbidden secret-bearing key"
}
require_no_secret_args() {
  local arg
  for arg in "$@"; do
    local lower
    lower=$(printf '%s' "$arg" | tr '[:upper:]' '[:lower:]')
    case "$lower" in *password=*|*secret=*|*accesskeysecret=*|*masterkey=*|*jwtsecret=*|*token=*)
      die "secret values are not accepted on the command line";; esac
  done
}
require_mode_600() {
  local file=$1 mode
  mode=$(stat -f '%Lp' "$file" 2>/dev/null || stat -c '%a' "$file")
  [[ "$mode" == "600" ]] || die "secret file must have mode 600"
}
json_string() { jq -er "$2 // empty" "$1"; }
configure_cloud_profile() {
  local file=$1
  CLOUD_PROFILE=$(jq -r '.cloudProfile // empty' "$file")
  if [[ -n "$CLOUD_PROFILE" ]]; then export ALICLOUD_PROFILE="$CLOUD_PROFILE"; fi
}
aliyun_cli() {
  if [[ -n ${CLOUD_PROFILE:-} ]]; then aliyun "$@" --profile "$CLOUD_PROFILE"; else aliyun "$@"; fi
}
env_raw_value() {
  local file=$1 key=$2
  awk -v key="$key" 'index($0, key "=") == 1 {print substr($0, length(key) + 2); exit}' "$file"
}
require_nonempty_env() {
  local file=$1 key=$2 raw
  raw=$(env_raw_value "$file" "$key")
  [[ -n "$raw" && "$raw" != "''" && "$raw" != '""' ]] || die "required environment value is empty: $key"
}
unquote_simple() {
  local value=$1
  value=${value#\'}; value=${value%\'}
  value=${value#\"}; value=${value%\"}
  printf '%s' "$value"
}
usage_common() { printf 'Secrets must be supplied through protected files or TF_VAR_* environment variables.\n'; }
