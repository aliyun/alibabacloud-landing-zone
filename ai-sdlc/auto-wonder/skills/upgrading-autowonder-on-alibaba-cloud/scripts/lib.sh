#!/usr/bin/env bash
set -euo pipefail

umask 077
AUTOWONDER_RUNTIME_HELPER="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)/runtime-env.sh"
if [[ -f "$AUTOWONDER_RUNTIME_HELPER" ]]; then
  source "$AUTOWONDER_RUNTIME_HELPER"
  if declare -F autowonder_restore_runtime_environment >/dev/null; then autowonder_restore_runtime_environment; fi
fi
AUTOWONDER_OPERATIONS_CLI="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)/operations-store.py"
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
json_validate() { jq -e . "$1" >/dev/null 2>&1 || die "invalid JSON file: $1"; operations_assert_current "$1"; }
require_remote_submission_settled() {
  "${AUTOWONDER_PYTHON:-python3}" "${AUTOWONDER_OPERATIONS_CLI%/*}/cloud_assistant.py" assert-settled --manifest "$1" ||
    die "previous remote operation is unresolved; reconcile before retrying"
}
operations_assert_current() {
  local file=$1
  if jq -e '.operationsStore != null' "$file" >/dev/null; then
    python3 "$AUTOWONDER_OPERATIONS_CLI" assert-current --manifest "$file" >/dev/null || die "operations revision is stale or unavailable"
  fi
}
require_secret_creation_allowed() {
  local file=$1 kind=${2:-runtime}
  case "$kind" in terraform|runtime) ;; *) die "unknown secret kind";; esac
  jq -e --arg recorded "${kind}SecretsRecorded" '
    .operationsStore == null or (
      .operationsStore.bootstrap == true and .operationsStore.ready == false and
      .operationsStore[$recorded] == false
    )' "$file" >/dev/null || die "bound deployment secrets are missing; restore the operations bundle"
}
operations_checkpoint() {
  local file=$1
  if jq -e ' .operationsStore != null' "$file" >/dev/null; then
    python3 "$AUTOWONDER_OPERATIONS_CLI" checkpoint --manifest "$file" >/dev/null || die "operations checkpoint failed; stop before further changes"
  fi
}
recommended_runtime_version_from_source() {
  local source_dir=$1 application_yml="$1/src/main/resources/application.yml"
  require_file "$application_yml"
  require_command python3
  python3 - "$application_yml" <<'PY'
import re
import sys

path = sys.argv[1]
parents = {}
value = None
with open(path, encoding="utf-8") as stream:
    for raw in stream:
        if not raw.strip() or raw.lstrip().startswith("#"):
            continue
        match = re.match(r"^( *)([A-Za-z0-9-]+)\s*:\s*(.*?)\s*$", raw.rstrip("\r\n"))
        if not match:
            continue
        indent, key, scalar = len(match.group(1)), match.group(2), match.group(3)
        parents = {level: name for level, name in parents.items() if level < indent}
        current = tuple(parents[level] for level in sorted(parents)) + (key,)
        if current == ("autowonder", "runtime", "recommended-version"):
            value = scalar.strip().strip('"\'')
            break
        if not scalar:
            parents[indent] = key

if value is None:
    raise SystemExit("autowonder.runtime.recommended-version is missing from application.yml")
placeholder = re.fullmatch(
    r"\$\{AUTOWONDER_RUNTIME_RECOMMENDED_VERSION:([^}]+)\}", value
)
if placeholder:
    value = placeholder.group(1)
if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?", value):
    raise SystemExit("autowonder.runtime.recommended-version must have a semantic-version default")
print(value)
PY
}
atomic_jq() {
  local file=$1; shift
  operations_assert_current "$file"
  local tmp
  tmp=$(mktemp "${file}.tmp.XXXXXX")
  TEMP_FILES+=("$tmp")
  jq "$@" "$file" >"$tmp" || die "failed to update manifest"
  chmod --reference="$file" "$tmp" 2>/dev/null || chmod 600 "$tmp"
  mv -f -- "$tmp" "$file"
  operations_checkpoint "$file"
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
AUTOWONDER_CLOUD_PROFILE=auto-wonder

clear_alicloud_credentials() {
  unset ALICLOUD_PROFILE ALICLOUD_ACCESS_KEY ALICLOUD_SECRET_KEY ALICLOUD_SECURITY_TOKEN
  unset ALICLOUD_ACCESS_KEY_ID ALICLOUD_ACCESS_KEY_SECRET
  unset ALIBABA_CLOUD_PROFILE ALIBABA_CLOUD_ACCESS_KEY_ID
  unset ALIBABA_CLOUD_ACCESS_KEY_SECRET ALIBABA_CLOUD_SECURITY_TOKEN
}

bind_auto_wonder_cloud_profile() {
  local cli_config_path profile_json
  CLOUD_PROFILE=$AUTOWONDER_CLOUD_PROFILE
  clear_alicloud_credentials
  export CLOUD_PROFILE ALICLOUD_PROFILE="$CLOUD_PROFILE"
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
}
read_auto_wonder_cloud_profile() {
  local cli_config_path profile_json
  cli_config_path=${ALIBABA_CLOUD_CLI_CONFIG_FILE:-${HOME}/.aliyun/config.json}
  if [[ -f "$cli_config_path" ]]; then
    profile_json=$(jq -cer --arg profile "$AUTOWONDER_CLOUD_PROFILE" '
      (.profiles // []) | map(select(.name == $profile)) | first |
      select(.access_key_id != null and .access_key_id != "" and
             .access_key_secret != null and .access_key_secret != "")
    ' "$cli_config_path" 2>/dev/null || true)
  fi
  if [[ -z ${profile_json:-} ]]; then
    profile_json=$(aliyun configure get --profile "$AUTOWONDER_CLOUD_PROFILE" 2>/dev/null || true)
    profile_json=$(jq -cer '
      select(.access_key_id != null and .access_key_id != "" and
             .access_key_secret != null and .access_key_secret != "")
    ' <<<"$profile_json" 2>/dev/null || true)
  fi
  [[ -n ${profile_json:-} ]] || return 1
  printf '%s\n' "$profile_json"
}
configure_cloud_profile() {
  local file=$1
  if [[ $(jq -r '.cloudProfile // empty' "$file") != "$AUTOWONDER_CLOUD_PROFILE" ]]; then
    atomic_jq "$file" --arg profile "$AUTOWONDER_CLOUD_PROFILE" '.cloudProfile=$profile'
  fi
  bind_auto_wonder_cloud_profile
}
load_alicloud_profile_credentials() {
  local region=${1:-} profile_json
  [[ ${CLOUD_PROFILE:-} == "$AUTOWONDER_CLOUD_PROFILE" ]] || die "AutoWonder cloud profile is not configured"
  profile_json=$(read_auto_wonder_cloud_profile) ||
    die "Alibaba Cloud profile did not provide temporary credentials"
  export ALICLOUD_ACCESS_KEY ALICLOUD_SECRET_KEY ALICLOUD_SECURITY_TOKEN
  ALICLOUD_ACCESS_KEY=$(jq -r '.access_key_id' <<<"$profile_json")
  ALICLOUD_SECRET_KEY=$(jq -r '.access_key_secret' <<<"$profile_json")
  ALICLOUD_SECURITY_TOKEN=$(jq -r '.sts_token // empty' <<<"$profile_json")
  unset profile_json
}
ensure_alicloud_profile_identity() {
  local region=${1:-} diagnostic cli_config_path
  [[ ${CLOUD_PROFILE:-} == "$AUTOWONDER_CLOUD_PROFILE" ]] || die "AutoWonder cloud profile is not configured"
  require_command aliyun
  diagnostic=$(mktemp); TEMP_FILES+=("$diagnostic")
  if ! AUTOWONDER_IDENTITY_JSON=$(aliyun_cli sts GetCallerIdentity ${region:+--region "$region"} 2>"$diagnostic"); then
    cat "$diagnostic" >&2
    if ! grep -q 'category=credential' "$diagnostic"; then
      cli_config_path=${ALIBABA_CLOUD_CLI_CONFIG_FILE:-${HOME}/.aliyun/config.json}
      # Only a confirmed missing profile can recover an unclassified CLI failure.
      # Invalid JSON/permissions/network failures must not become login prompts.
      if ! grep -q 'category=unknown' "$diagnostic" || ! {
        [[ ! -e "$cli_config_path" ]] || jq -e 'type == "object" and (.profiles | type == "array") and all(.profiles[]; .name != "auto-wonder")' "$cli_config_path" >/dev/null 2>&1;
      }; then
        die "account identity is unavailable; resolve the reported failure before retrying"
      fi
    fi
    aliyun configure --profile "$AUTOWONDER_CLOUD_PROFILE" --mode OAuth 2>/dev/null ||
      die "Alibaba Cloud OAuth login failed"
    AUTOWONDER_IDENTITY_JSON=$(aliyun_cli sts GetCallerIdentity ${region:+--region "$region"}) ||
      die "Alibaba Cloud profile identity is unavailable after OAuth login"
  fi
  clear_alicloud_credentials
  export CLOUD_PROFILE="$AUTOWONDER_CLOUD_PROFILE" ALICLOUD_PROFILE="$AUTOWONDER_CLOUD_PROFILE"
  load_alicloud_profile_credentials "$region"
}
aliyun_cli() {
  "${AUTOWONDER_PYTHON:-python3}" "${AUTOWONDER_OPERATIONS_CLI%/*}/cloud_diagnostics.py" "$@" --profile "$AUTOWONDER_CLOUD_PROFILE"
}

ossutil_cli() {
  local cli_config_path profile_json access_key_id access_key_secret sts_token temp_config command_status
  cli_config_path=${ALIBABA_CLOUD_CLI_CONFIG_FILE:-${HOME}/.aliyun/config.json}
  require_file "$cli_config_path"
  profile_json=$(jq -cer --arg profile "$AUTOWONDER_CLOUD_PROFILE" '
    (.profiles // []) |
    map(select(.name == $profile)) | first |
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
  "${AUTOWONDER_PYTHON:-python3}" "${AUTOWONDER_OPERATIONS_CLI%/*}/cloud_assistant.py" invocation-id
}
cloud_assistant_status() {
  "${AUTOWONDER_PYTHON:-python3}" "${AUTOWONDER_OPERATIONS_CLI%/*}/cloud_assistant.py" status
}
cloud_assistant_exit_code() {
  "${AUTOWONDER_PYTHON:-python3}" "${AUTOWONDER_OPERATIONS_CLI%/*}/cloud_assistant.py" exit-code
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
initialize_runtime_terraform() {
  local manifest=$1 terraform_dir=$2 state_mode backend_source private_dir workspace
  state_mode=$(jq -r '.terraform.stateMode // .stateMode // "local"' "$manifest")
  case "$state_mode" in remote|oss) ;; *) return 0;; esac
  backend_source=$(jq -er '.terraform.stateReference | select(type == "string" and length > 0)' "$manifest") ||
    die "runtime Terraform backend reference is missing"
  require_file "$backend_source"
  private_dir=$(mktemp -d "${TMPDIR:-/tmp}/autowonder-runtime-terraform.XXXXXX")
  TEMP_DIRS+=("$private_dir")
  chmod 700 "$private_dir"
  mkdir -m 700 "$private_dir/terraform-data"
  cp -- "$backend_source" "$private_dir/backend.hcl"
  chmod 600 "$private_dir/backend.hcl"
  export TF_DATA_DIR="$private_dir/terraform-data"
  TF_CLI_CONFIG_FILE=$("${AUTOWONDER_PYTHON:-python3}" "${AUTOWONDER_OPERATIONS_CLI%/*}/terraform_runtime.py" --config-dir "$private_dir") ||
    die "cannot prepare private Terraform provider configuration"
  export TF_CLI_CONFIG_FILE
  unset TF_WORKSPACE
  if ! terraform -chdir="$terraform_dir" init -reconfigure -input=false \
    -backend-config="$private_dir/backend.hcl" >"$private_dir/init.log" 2>&1; then
    die "cannot initialize protected runtime Terraform backend"
  fi
  workspace=$(jq -r '.terraform.workspace // "default"' "$manifest")
  if [[ "$workspace" != default ]]; then
    if ! terraform -chdir="$terraform_dir" workspace select "$workspace" >"$private_dir/workspace.log" 2>&1; then
      die "cannot select recorded runtime Terraform workspace"
    fi
  fi
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
