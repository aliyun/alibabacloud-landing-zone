#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
source "$SCRIPT_DIR/upgrade-lib.sh"

manifest= fingerprint= automatic=false
while (($#)); do
  case "$1" in
    --manifest) manifest=${2:-}; shift 2 ;;
    --fingerprint) fingerprint=${2:-}; shift 2 ;;
    --automatic) automatic=true; shift ;;
    --help|-h) printf 'Usage: approve-upgrade-plan.sh --manifest FILE --fingerprint SHA256 [--automatic]\n'; exit 0 ;;
    *) die "unknown argument" ;;
  esac
done
require_file "$manifest"; json_validate "$manifest"; reject_secret_keys "$manifest"
require_current_target_verification "$manifest"
recorded=$(jq -r '.upgrade.planFingerprint // empty' "$manifest")
actual=$(calculate_upgrade_plan_fingerprint "$manifest")
[[ "$fingerprint" =~ ^[0-9a-f]{64}$ && "$fingerprint" == "$recorded" && "$fingerprint" == "$actual" ]] || \
  die "approved fingerprint does not match the current upgrade plan"
[[ $(jq -r '(.upgrade.blockedReasons // []) | length' "$manifest") == 0 ]] || die "blocked upgrade plan cannot be approved"
if [[ "$automatic" == true && $(jq -r 'if .upgrade | has("confirmationRequired") then .upgrade.confirmationRequired else true end' "$manifest") == true ]]; then
  die "upgrade plan requires impact confirmation and cannot be approved automatically"
fi
approval_mode=human
[[ "$automatic" == true ]] && approval_mode=automatic
atomic_jq "$manifest" --arg fingerprint "$fingerprint" --arg mode "$approval_mode" '
  .upgrade.approval={status:"approved",mode:$mode,planFingerprint:$fingerprint,approvedAt:(now|todateiso8601)}
'
jq '{status:.upgrade.approval.status,mode:.upgrade.approval.mode,planFingerprint:.upgrade.approval.planFingerprint}' "$manifest"
