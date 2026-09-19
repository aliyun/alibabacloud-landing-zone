# Executed on the first ECS by Cloud Assistant. Inputs contain no password.
set -euo pipefail
umask 077
deployment=$(printf '%s' "$deployment_b64" | base64 -d)
[[ -n "$deployment" ]]
[[ ! -L "$credential_file" && ! -L "${credential_file}.lock" ]]
exec 9>"${credential_file}.lock"
flock -w 30 9
work=$(mktemp -d)
trap 'rm -rf -- "$work"' EXIT
printf '%s' "$public_key_b64" | base64 -d >"$work/public.pem"
if [[ ! -e "$credential_file" ]]; then
  password="Aa1!$(openssl rand -hex 14)"
  # Persist before registering: a lost invocation response must not lose the password.
  jq -n --arg deployment "$deployment" --arg password "$password" \
    '{deploymentId:$deployment,username:"admin",password:$password}' >"$work/credentials.json"
  # Atomic publication on the same filesystem; never overwrite existing credentials.
  pending=$(mktemp "${credential_file}.XXXXXX")
  cat "$work/credentials.json" >"$pending"
  chmod 600 "$pending"
  ln "$pending" "$credential_file"
  rm -f -- "$pending"
  unset password
fi
[[ -f "$credential_file" && ! -L "$credential_file" ]]
[[ $(stat -c '%a' "$credential_file") == 600 && $(stat -c '%u' "$credential_file") == "$(id -u)" ]]
jq -e --arg deployment "$deployment" \
  '.deploymentId == $deployment and .username == "admin" and (.password | type == "string" and length >= 20)' "$credential_file" >/dev/null
jq '{username,password,email:"admin@localhost.invalid",nickname:"Administrator"}' "$credential_file" >"$work/register.json"
code=$(curl --silent --output "$work/register-response.json" --write-out '%{http_code}' \
  -H 'Content-Type: application/json' --data-binary @"$work/register.json" http://127.0.0.1:7001/api/auth/register)
case "$code" in
  2??) jq -e '.success == true' "$work/register-response.json" >/dev/null ;;
  409)
    # Recovery only: prove these saved credentials belong to the existing account.
    # Never delete/reset an admin merely because it has no workspace.
    jq '{username,password}' "$credential_file" >"$work/login.json"
    if ! curl --fail --silent -H 'Content-Type: application/json' --data-binary @"$work/login.json" \
      http://127.0.0.1:7001/api/auth/login >"$work/login-response.json"; then
      printf 'Existing admin does not match saved credentials; account preserved, manual reconciliation required.\n' >&2
      exit 1
    fi
    jq -e '.success == true and (.data.accessToken | type == "string" and length > 0)' "$work/login-response.json" >/dev/null ;;
  *) printf 'Administrator initialization unresolved; preserve account and saved credentials.\n' >&2; exit 1 ;;
esac
jq -c '{username,password}' "$credential_file" | openssl pkeyutl -encrypt -pubin -inkey "$work/public.pem" \
  -pkeyopt rsa_padding_mode:oaep -pkeyopt rsa_oaep_md:sha256 | base64 | tr -d '\r\n' | sed 's/^/HANDOFF_CIPHERTEXT=/'
printf '\nBUSINESS_STATUS=passed\n'
