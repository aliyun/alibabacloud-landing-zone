#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
source "$SCRIPT_DIR/lib.sh"

usage() { cat <<'EOF'
Usage: sanitize-evidence.sh --input FILE --output FILE
Creates a shareable evidence report. The command fails closed if a forbidden value remains.
EOF
}
input= output=
require_no_secret_args "$@"
while (($#)); do
  case "$1" in
    --input) input=${2:-}; shift 2;;
    --output) output=${2:-}; shift 2;;
    --help|-h) usage; exit 0;;
    *) die "unknown argument";;
  esac
done
require_file "$input"; [[ -n "$output" && "$output" != "$input" ]] || die "output must differ from input"
require_command jq
tmp=$(mktemp); TEMP_FILES+=("$tmp")

if jq -e . "$input" >/dev/null 2>&1; then
  jq '
    def sensitive_key:
      ascii_downcase | gsub("[-_.]"; "") |
      test("^(password|passwordhash|secret|accesskey|accesskeyid|accesskeysecret|masterkey|jwt|jwtsecret|token|authorization|presignedurl|credential|commandoutput)$");
    def identifying_key: ascii_downcase | test("(^|_)(uid|accountid|instanceid|invocationid|publicip|privateip|bucket|project|endpoint|address|resourceid)(_|$)");
    walk(
      if type == "object" then
        with_entries(
          if (.key | sensitive_key) then empty
          elif (.key | identifying_key) then .value = "[REDACTED]"
          else . end
        )
      elif type == "string" then
        gsub("([?&](token|access_token|x-oss-security-token|signature)=[^&[:space:]]+)"; "[REDACTED]") |
        gsub("(?i)(password|secret|authorization|token)[=:][^ ,;]+"; "[REDACTED]") |
        gsub("(https?://)[^/[:space:]]+"; "\\1[REDACTED]") |
        gsub("\\b([0-9]{1,3}\\.){3}[0-9]{1,3}\\b"; "[REDACTED]") |
        gsub("\\b(i|vpc|vsw|sg|rm|r|nlb|lb|t)-[A-Za-z0-9-]{8,}\\b"; "[REDACTED]") |
        gsub("\\b[0-9]{12,20}\\b"; "[REDACTED]")
      else . end
    )
  ' "$input" >"$tmp" || die "structured evidence sanitization failed"
else
  sed -E \
    -e 's#https?://[^[:space:]]+#https://[REDACTED]#g' \
    -e 's#([0-9]{1,3}\.){3}[0-9]{1,3}#[REDACTED]#g' \
    -e 's#(password|secret|authorization|token)[=:][^ ,;]+#[REDACTED]#Ig' \
    -e 's#\b(i|vpc|vsw|sg|rm|r|nlb|lb|t)-[A-Za-z0-9-]{8,}\b#[REDACTED]#g' \
    "$input" >"$tmp" || die "text evidence sanitization failed"
fi

forbidden='LTAI[A-Za-z0-9]{12,}|[?&](token|access_token|x-oss-security-token|signature)=|(^|[^A-Za-z])(password|secret|authorization|token)[=:][^ ]+|([0-9]{1,3}\.){3}[0-9]{1,3}|\b(i|vpc|vsw|sg|rm|nlb|lb|t)-[A-Za-z0-9-]{8,}\b|\b[0-9]{12,20}\b|TEST_SECRET_DO_NOT_PRINT'
if grep -Eiq "$forbidden" "$tmp"; then
  rm -f -- "$tmp"
  die "sanitization failed closed: forbidden evidence remains"
fi
install -m 0600 "$tmp" "$output"
log "sanitized evidence written"
