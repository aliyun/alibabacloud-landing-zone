#!/usr/bin/env bash
set -euo pipefail

umask 077
TEMP_FILES=()
TEMP_DIRS=()

cleanup() {
  local file
  set +u
  for file in "${TEMP_FILES[@]}"; do
    if [[ -n "$file" ]]; then rm -f -- "$file"; fi
  done
  for file in "${TEMP_DIRS[@]}"; do
    if [[ -n "$file" ]]; then rm -rf -- "$file"; fi
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
validate_env_file_syntax() {
  local file=$1
  require_file "$file"
  grep -Eq '^[A-Z][A-Z0-9_]*=' "$file" || die "environment file is malformed"
  if grep -Ev '^$|^#[^!].*$|^[A-Z][A-Z0-9_]*=.*$' "$file" | grep -q .; then
    die "environment file contains an invalid line"
  fi
  if grep -Eq '[`;]|\$\(' "$file"; then
    die "environment file contains executable shell syntax"
  fi
  awk -F= '/^[A-Z][A-Z0-9_]*=/{if (++seen[$1] > 1) exit 1}' "$file" ||
    die "environment file contains duplicate keys"
}
json_string() { jq -er "$2 // empty" "$1"; }
configure_cloud_profile() {
  local file=$1 cli_config_path profile_json
  CLOUD_PROFILE=$(jq -r '.cloudProfile // empty' "$file")
  if [[ -n "$CLOUD_PROFILE" ]]; then
    export ALICLOUD_PROFILE="$CLOUD_PROFILE"
    cli_config_path=${ALIBABA_CLOUD_CLI_CONFIG_FILE:-${HOME}/.aliyun/config.json}
    if [[ -f "$cli_config_path" ]]; then
      profile_json=$(jq -cer --arg profile "$CLOUD_PROFILE" '
        (.profiles // []) | map(select(.name == $profile)) | first |
        select(.access_key_id != null and .access_key_id != "" and
               .access_key_secret != null and .access_key_secret != "")
      ' "$cli_config_path" 2>/dev/null || true)
      if [[ -n "$profile_json" ]]; then
        export ALICLOUD_ACCESS_KEY ALICLOUD_SECRET_KEY ALICLOUD_SECURITY_TOKEN
        ALICLOUD_ACCESS_KEY=$(jq -r '.access_key_id' <<<"$profile_json")
        ALICLOUD_SECRET_KEY=$(jq -r '.access_key_secret' <<<"$profile_json")
        ALICLOUD_SECURITY_TOKEN=$(jq -r '.sts_token // empty' <<<"$profile_json")
      fi
      unset profile_json
    fi
  fi
}
load_alicloud_profile_credentials() {
  local region=${1:-} cli_config_path profile_json
  [[ -n ${CLOUD_PROFILE:-} ]] || return 0
  aliyun sts GetCallerIdentity --profile "$CLOUD_PROFILE" ${region:+--region "$region"} >/dev/null ||
    die "Alibaba Cloud profile identity is unavailable"
  cli_config_path=${ALIBABA_CLOUD_CLI_CONFIG_FILE:-${HOME}/.aliyun/config.json}
  require_file "$cli_config_path"
  profile_json=$(jq -cer --arg profile "$CLOUD_PROFILE" '
    (.profiles // []) | map(select(.name == $profile)) | first |
    select(.access_key_id != null and .access_key_id != "" and
           .access_key_secret != null and .access_key_secret != "")
  ' "$cli_config_path" 2>/dev/null || true)
  [[ -n "$profile_json" ]] || die "Alibaba Cloud profile did not provide temporary credentials"
  export ALICLOUD_ACCESS_KEY ALICLOUD_SECRET_KEY ALICLOUD_SECURITY_TOKEN
  ALICLOUD_ACCESS_KEY=$(jq -r '.access_key_id' <<<"$profile_json")
  ALICLOUD_SECRET_KEY=$(jq -r '.access_key_secret' <<<"$profile_json")
  ALICLOUD_SECURITY_TOKEN=$(jq -r '.sts_token // empty' <<<"$profile_json")
  unset profile_json
}
aliyun_cli() {
  if [[ -n ${CLOUD_PROFILE:-} ]]; then aliyun "$@" --profile "$CLOUD_PROFILE"; else aliyun "$@"; fi
}
ossutil_cli() {
  local cli_config_path profile_json access_key_id access_key_secret sts_token temp_config command_status
  cli_config_path=${ALIBABA_CLOUD_CLI_CONFIG_FILE:-${HOME}/.aliyun/config.json}
  require_file "$cli_config_path"
  profile_json=$(jq -cer --arg profile "${CLOUD_PROFILE:-}" '
    (.current // "default") as $current |
    (.profiles // []) |
    map(select(.name == (if $profile == "" then $current else $profile end))) | first |
    select(.access_key_id != null and .access_key_id != "" and .access_key_secret != null and .access_key_secret != "")
  ' "$cli_config_path") || die "Alibaba Cloud CLI profile has no ossutil-compatible temporary credentials"
  access_key_id=$(jq -r '.access_key_id' <<<"$profile_json")
  access_key_secret=$(jq -r '.access_key_secret' <<<"$profile_json")
  sts_token=$(jq -r '.sts_token // empty' <<<"$profile_json")
  if [[ ${OSSUTIL_CONTRACT:-legacy} == v2 ]]; then
    OSS_ACCESS_KEY_ID=$access_key_id OSS_ACCESS_KEY_SECRET=$access_key_secret OSS_SESSION_TOKEN=$sts_token \
      "$OSSUTIL_BIN" "$@" || command_status=$?
  else
    temp_config=$(mktemp "${TMPDIR:-/tmp}/autowonder-ossutil.XXXXXX")
    TEMP_FILES+=("$temp_config")
    chmod 600 "$temp_config"
    {
      printf '[Credentials]\naccessKeyID=%s\naccessKeySecret=%s\n' "$access_key_id" "$access_key_secret"
      [[ -z "$sts_token" ]] || printf 'stsToken=%s\n' "$sts_token"
    } >"$temp_config"
    "$OSSUTIL_BIN" -c "$temp_config" "$@" || command_status=$?
    unlink "$temp_config"
  fi
  unset profile_json access_key_id access_key_secret sts_token
  return "${command_status:-0}"
}
cloud_assistant_invocation_id() {
  jq -er '.InvokeId // .InvocationId // .invokeId // .invocationId'
}
cloud_assistant_status() {
  jq -r '[.. | objects | .InvokeRecordStatus? // .InvocationStatus? // .Status? // empty][0] // "Pending"'
}
cloud_assistant_exit_code() {
  jq -r '[.. | objects | .ExitCode? // empty][0] // -1'
}

ossutil_command_help() {
  local command_name=$1 output
  if output=$("$OSSUTIL_BIN" help "$command_name" 2>&1); then
    printf '%s' "$output"
  elif output=$("$OSSUTIL_BIN" "$command_name" --help 2>&1); then
    printf '%s' "$output"
  else
    return 1
  fi
}
ossutil_has_flag() {
  local help_text=$1 long_flag=$2 short_flag=${3:-}
  grep -Fq -- "$long_flag" <<<"$help_text" && return 0
  [[ -n "$short_flag" ]] && grep -Eq "(^|[[:space:],])${short_flag}([=,[:space:]]|$)" <<<"$help_text"
}
ossutil_endpoint_flag() {
  local help_text=$1
  if grep -Fq -- --endpoint <<<"$help_text"; then printf '%s' --endpoint
  elif ossutil_has_flag "$help_text" __not_present__ -e; then printf '%s' -e
  else return 1
  fi
}
ossutil_force_flag() {
  local help_text=$1
  if grep -Fq -- --force <<<"$help_text"; then printf '%s' --force
  elif ossutil_has_flag "$help_text" __not_present__ -f; then printf '%s' -f
  else return 1
  fi
}
ossutil_preflight() {
  local region=$1 version_output cp_help rm_help presign_help= sign_help= sign_command_help
  OSSUTIL_BIN=$(type -P ossutil) || die "required command missing: ossutil"
  version_output=$("$OSSUTIL_BIN" version 2>&1 || "$OSSUTIL_BIN" --version 2>&1) || die "cannot determine ossutil version"
  OSSUTIL_VERSION=$(tr '\n' ' ' <<<"$version_output" | sed -E 's/[^A-Za-z0-9._ -]+/ /g; s/[[:space:]]+/ /g; s/^ //; s/ $//')
  [[ -n "$OSSUTIL_VERSION" ]] || die "cannot determine ossutil version"
  cp_help=$(ossutil_command_help cp) || die "installed ossutil does not support cp"
  rm_help=$(ossutil_command_help rm) || die "installed ossutil does not support rm"
  presign_help=$(ossutil_command_help presign 2>/dev/null || true)
  sign_help=$(ossutil_command_help sign 2>/dev/null || true)
  if [[ -n "$presign_help" ]] && ossutil_has_flag "$presign_help" --expires-duration; then
    OSSUTIL_CONTRACT=v2; OSSUTIL_SIGN_COMMAND=presign; OSSUTIL_EXPIRY_FLAG=--expires-duration; OSSUTIL_EXPIRY_VALUE=15m
  elif [[ -n "$sign_help" ]] && ossutil_has_flag "$sign_help" --timeout; then
    OSSUTIL_CONTRACT=legacy; OSSUTIL_SIGN_COMMAND=sign; OSSUTIL_EXPIRY_FLAG=--timeout; OSSUTIL_EXPIRY_VALUE=900
  else
    die "installed ossutil has no supported presign/sign contract"
  fi
  [[ "$OSSUTIL_SIGN_COMMAND" == presign ]] && sign_command_help=$presign_help || sign_command_help=$sign_help
  OSSUTIL_CP_ENDPOINT_FLAG=$(ossutil_endpoint_flag "$cp_help") || die "ossutil cp has no supported endpoint flag"
  OSSUTIL_RM_ENDPOINT_FLAG=$(ossutil_endpoint_flag "$rm_help") || die "ossutil rm has no supported endpoint flag"
  OSSUTIL_SIGN_ENDPOINT_FLAG=$(ossutil_endpoint_flag "$sign_command_help") || die "ossutil signing command has no supported endpoint flag"
  OSSUTIL_CP_REGION_FLAG=; OSSUTIL_RM_REGION_FLAG=; OSSUTIL_SIGN_REGION_FLAG=
  ossutil_has_flag "$cp_help" --region && OSSUTIL_CP_REGION_FLAG=--region
  ossutil_has_flag "$rm_help" --region && OSSUTIL_RM_REGION_FLAG=--region
  ossutil_has_flag "$sign_command_help" --region && OSSUTIL_SIGN_REGION_FLAG=--region
  if [[ "$OSSUTIL_CONTRACT" == v2 ]]; then
    [[ -n "$OSSUTIL_CP_REGION_FLAG" && -n "$OSSUTIL_RM_REGION_FLAG" && -n "$OSSUTIL_SIGN_REGION_FLAG" ]] || die "ossutil v2 command contract is missing --region"
  fi
  OSSUTIL_CP_FORCE_FLAG=$(ossutil_force_flag "$cp_help" 2>/dev/null || true)
  OSSUTIL_RM_FORCE_FLAG=$(ossutil_force_flag "$rm_help") || die "ossutil rm command has no non-interactive force flag"
  OSSUTIL_REGION=$region
  if [[ -n ${OSS_SESSION_TOKEN:-} ]]; then log "ossutil uses an STS credential; token value suppressed"; fi
}
ossutil_upload() {
  local source_file=$1 target=$2 endpoint=$3 region=$4
  local -a args=(cp)
  [[ -z "$OSSUTIL_CP_FORCE_FLAG" ]] || args+=("$OSSUTIL_CP_FORCE_FLAG")
  args+=("$source_file" "$target" "$OSSUTIL_CP_ENDPOINT_FLAG" "$endpoint")
  [[ -z "$OSSUTIL_CP_REGION_FLAG" ]] || args+=("$OSSUTIL_CP_REGION_FLAG" "$region")
  ossutil_cli "${args[@]}"
}
ossutil_remove() {
  local target=$1 endpoint=$2 region=$3
  local -a args=(rm "$OSSUTIL_RM_FORCE_FLAG" "$target" "$OSSUTIL_RM_ENDPOINT_FLAG" "$endpoint")
  [[ -z "$OSSUTIL_RM_REGION_FLAG" ]] || args+=("$OSSUTIL_RM_REGION_FLAG" "$region")
  ossutil_cli "${args[@]}"
}
ossutil_presign() {
  local target=$1 endpoint=$2 region=$3 result url
  local -a args=("$OSSUTIL_SIGN_COMMAND" "$target" "$OSSUTIL_EXPIRY_FLAG" "$OSSUTIL_EXPIRY_VALUE" "$OSSUTIL_SIGN_ENDPOINT_FLAG" "$endpoint")
  [[ -z "$OSSUTIL_SIGN_REGION_FLAG" ]] || args+=("$OSSUTIL_SIGN_REGION_FLAG" "$region")
  result=$(ossutil_cli "${args[@]}" 2>/dev/null) || die "failed to create staging URL"
  url=$(grep -Eo 'https?://[^[:space:]]+' <<<"$result" | head -1)
  [[ "$url" == http://*\?* || "$url" == https://*\?* ]] || die "ossutil returned an invalid staging URL"
  OSSUTIL_PRESIGNED_URL=$url
  unset result url
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
