#!/usr/bin/env bash
# Walk a real authenticated chain against the running server and exercise this
# cycle's new endpoints, instead of only probing the health check.
#
#   e2e-tests/authchain.sh
#
# A 200 from /checkpreload.htm proves the process is up; it proves nothing about
# whether a user can register, sign in, own a workspace, or reach the features
# this release added. So this script walks:
#
#   register -> login -> platform-admin view -> create workspace -> switch
#   workspace -> new-feature endpoints -> work item -> clarification
#   conversation (the new elicitation feature) -> workspace edit / soft delete /
#   recycle bin / restore.
#
# The soft-delete cycle is also the decisive test of migration V051, which swaps
# the unique key `uk_name` for `uk_active_name` (a STORED generated column that
# is NULL once a row is soft-deleted). If the swap is wrong, either a deleted
# workspace still blocks its own name, or a restored one stops blocking it. Both
# directions are asserted here, against a live database, not against SQL text.
#
# Credentials handling: tokens and the test user's password are written to
# mode-600 curl config files and never appear in a command argument, in the
# report, or in stdout. Raw response bodies stay in the state directory, which
# down.sh deletes; redacted copies are what may be archived as evidence.

set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
# shellcheck source=lib/common.sh
source e2e-tests/lib/common.sh

aw_e2e_init_runtime
aw_e2e_prepare_state_dir
aw_e2e_load_resolved || die "no resolved.env - run e2e-tests/up.sh first"

BASE="http://127.0.0.1:${AW_E2E_APP_PORT}"
OUT="$AW_E2E_STATE_DIR/authchain"
# Start clean: the previous run's *.cfg files hold live bearer tokens and its
# *.json files would otherwise be redacted and archived as if they were this
# run's results.
rm -rf "$OUT"
umask 077
mkdir -p "$OUT"
chmod 700 "$OUT"
REPORT="$AW_E2E_STATE_DIR/authchain.txt"
PASS=0
FAIL=0

E2E_USER="aw-e2e-$(date +%s)"
RUN_SUFFIX="${E2E_USER##*-}"
# Newline-free so it can be interpolated into a JSON body without escaping.
E2E_PASS="$(openssl rand -base64 18 | tr -d '\n')"
printf 'AW_E2E_USER=%s\nAW_E2E_PASS=%s\n' "$E2E_USER" "$E2E_PASS" >"$OUT/user.env"
chmod 600 "$OUT/user.env"
umask 022

# --- helpers -----------------------------------------------------------------
# Writes a token into a mode-600 curl config so it never reaches argv.
token_cfg() {
  local name="$1"
  local token="$2"
  local cfg="$OUT/$name.cfg"
  umask 077
  printf 'header = "Authorization: Bearer %s"\n' "$token" >"$cfg"
  chmod 600 "$cfg"
  umask 022
  printf '%s' "$cfg"
}

# json_get <file> <dotted.path>   (list indices allowed, e.g. data.0.id)
json_get() {
  python3 - "$1" "$2" <<'PY'
import json, sys
path_file, dotted = sys.argv[1], sys.argv[2]
try:
    with open(path_file) as fh:
        cur = json.load(fh)
except Exception:
    print("")
    sys.exit(0)
for key in dotted.split("."):
    if isinstance(cur, list):
        try:
            cur = cur[int(key)]
        except Exception:
            cur = None
            break
    elif isinstance(cur, dict):
        cur = cur.get(key)
    else:
        cur = None
        break
if cur is None:
    print("")
elif isinstance(cur, bool):
    print("true" if cur else "false")
elif isinstance(cur, (dict, list)):
    print(json.dumps(cur, ensure_ascii=False))
else:
    print(cur)
PY
}

# Masks credential-shaped values in a JSON document; falls back to masking any
# long opaque run when the input is not parseable JSON. Response bodies from
# login and workspace switch carry live tokens, so nothing prints them raw.
#
# redact_file <path> [maxlen]
#
# Takes a path, not stdin. A `python3 - <<'PY'` heredoc *replaces* the enclosing
# function's stdin, so a helper written as `python3 - <<'PY' … sys.stdin.read()`
# and invoked as `helper <"$file"` silently reads an empty string.
redact_file() {
  python3 - "$1" "${2:-700}" <<'PY'
import json, re, sys
path, maxlen = sys.argv[1], int(sys.argv[2])
SENSITIVE = re.compile(r'(token|secret|password|credential)', re.I)
try:
    with open(path) as fh:
        raw = fh.read()
except OSError:
    print("")
    sys.exit(0)
def scrub(node):
    if isinstance(node, dict):
        return {k: (f"<REDACTED len={len(str(v))}>" if isinstance(v, str) and SENSITIVE.search(k)
                    else scrub(v)) for k, v in node.items()}
    if isinstance(node, list):
        return [scrub(v) for v in node]
    return node
try:
    print(json.dumps(scrub(json.loads(raw)), ensure_ascii=False)[:maxlen])
except Exception:
    print(re.sub(r'[A-Za-z0-9_.\-]{32,}', '<REDACTED>', raw)[:maxlen])
PY
}

# api <name> <METHOD> <path> [curl-config] [json-body]
api() {
  local name="$1" method="$2" path="$3" cfg="${4:-}" body="${5:-}"
  local out="$OUT/$name.json" code
  local args=(-sS -o "$out" -w '%{http_code}' -X "$method" "$BASE$path")
  [[ -n "$cfg" ]] && args+=(-K "$cfg")
  local body_file=""
  if [[ -n "$body" ]]; then
    args+=(-H 'Content-Type: application/json')
    # --data @file, never --data "$body": the register and login bodies contain
    # the test user's password, and argv is readable through ps.
    body_file="$OUT/.$name.req.tmp"
    umask 077
    printf '%s' "$body" >"$body_file"
    chmod 600 "$body_file"
    # The redacted request record is produced *from that file*, so the plaintext
    # body never exists in a world-readable copy.
    redact_file "$body_file" >"$OUT/$name.req.json"
    chmod 600 "$OUT/$name.req.json"
    umask 022
    args+=(--data "@$body_file")
  fi
  code="$(curl "${args[@]}" 2>"$OUT/$name.curlerr" || echo 000)"
  [[ -n "$body_file" ]] && rm -f "$body_file"
  printf '%s' "$code" >"$OUT/$name.code"
  LAST_CODE="$code"
  LAST_BODY="$(redact_file "$out" || true)"
  log "api $method $path -> $code"
}

# check <name> <expected-code> <expect-success:true|false|any> <note>
check() {
  local name="$1" want_code="$2" want_success="$3" note="$4"
  local got_success verdict
  got_success="$(json_get "$OUT/$name.json" success)"
  verdict=PASS
  if [[ "$LAST_CODE" != "$want_code" ]]; then
    verdict=FAIL
  elif [[ "$want_success" != "any" && "$got_success" != "$want_success" ]]; then
    verdict=FAIL
  fi
  if [[ "$verdict" == PASS ]]; then PASS=$((PASS+1)); else FAIL=$((FAIL+1)); fi
  printf 'CHECK|%s|%s|http=%s want=%s|success=%s want=%s|%s\n' \
    "$verdict" "$name" "$LAST_CODE" "$want_code" "${got_success:-<none>}" "$want_success" "$note"
  printf 'BODY|%s|%s\n' "$name" "$(printf '%s' "$LAST_BODY" | tr -d '\n')"
}

mysql_q() {
  AW_E2E_Q="$1" aw_e2e_compose exec -T -e AW_E2E_Q mysql sh -c \
    'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -B -e "$AW_E2E_Q"' \
    2>>"$AW_E2E_STATE_DIR/mysql-q.err"
}

# Produces an evidence-safe copy of every response body: token-shaped values are
# replaced by their length, so the redacted files can be archived.
redact_all() {
  python3 - "$OUT" <<'PY'
import json, os, sys, re
out = sys.argv[1]
SENSITIVE = re.compile(r'(token|secret|password|credential)', re.I)
def scrub(node):
    if isinstance(node, dict):
        return {k: (f"<REDACTED len={len(str(v))}>" if isinstance(v, str) and SENSITIVE.search(k)
                    else scrub(v)) for k, v in node.items()}
    if isinstance(node, list):
        return [scrub(v) for v in node]
    return node
for fn in sorted(os.listdir(out)):
    if not fn.endswith(".json") or fn.startswith("redacted-"):
        continue
    p = os.path.join(out, fn)
    try:
        with open(p) as fh:
            data = json.load(fh)
    except Exception:
        continue
    with open(os.path.join(out, "redacted-" + fn), "w") as fh:
        json.dump(scrub(data), fh, ensure_ascii=False, indent=1)
PY
}

{
echo "# authenticated chain on a fresh community install"
echo "BASE=$BASE"
echo "TEST_USER=$E2E_USER"
echo "TEST_USER_PASSWORD=<generated, not recorded>"

# --- 1. register -------------------------------------------------------------
# The bodies are built with printf, not by passing the password to a helper as an
# argument: anything in argv is readable through ps.
api register POST /api/auth/register "" \
  "{\"username\":\"$E2E_USER\",\"password\":\"$E2E_PASS\",\"email\":\"$E2E_USER@example.invalid\",\"nickname\":\"AW E2E\"}"
check register 200 true "first user on a fresh install"
USER_ID="$(json_get "$OUT/register.json" data.id)"
echo "REGISTERED_USER_ID=$USER_ID"

# --- 2. login ----------------------------------------------------------------
api login POST /api/auth/login "" "{\"username\":\"$E2E_USER\",\"password\":\"$E2E_PASS\"}"
check login 200 true "returns accessToken + refreshToken"
T0="$(json_get "$OUT/login.json" data.accessToken)"
[[ -n "$T0" ]] || { echo "FATAL: login returned no accessToken"; exit 1; }
CFG0="$(token_cfg t0 "$T0")"
echo "TOKEN0_PRESENT=yes"
echo "TOKEN0_LENGTH=${#T0}"

# --- 3. platform administrator (new this cycle, V050 + SystemAdminBootstrap) --
api platform_admins GET /api/platform/admins "$CFG0"
check platform_admins 200 true "platform admin list reachable"
# V050 promotes the *first active user* on a deployment to platform administrator.
# Whether this run's user is that user depends on whether the database is fresh,
# so the expectation is derived from the measured value rather than hard-coded.
IS_ADMIN="$(mysql_q "SELECT is_admin FROM autowonder.user WHERE username='$E2E_USER'")"
echo "SQL_user_is_admin=$IS_ADMIN"
echo "PLATFORM_ADMINS=$(redact_file "$OUT/platform_admins.json" 400)"
api platform_admin_candidates GET /api/platform/admins/candidates "$CFG0"
if [[ "$IS_ADMIN" == "1" ]]; then
  check platform_admin_candidates 200 true "administrator: candidate search allowed"
else
  check platform_admin_candidates 403 false "non-administrator: candidate search refused with 10403"
fi
api branding_authed GET /api/platform/branding/public "$CFG0"
# /branding/public is the unauthenticated endpoint: publicConfig() passes a literal
# false for canManage, so it can never reflect admin state. It is probed because it
# is what exposes the community defaults to a logged-out browser, not because of
# canManage.
check branding_authed 200 true "public branding exposes the community defaults"
echo "BRANDING_canManage=$(json_get "$OUT/branding_authed.json" data.canManage)"
echo "BRANDING_communityEdition=$(json_get "$OUT/branding_authed.json" data.communityEdition)"
echo "BRANDING_recommendedRuntimeVersion=$(json_get "$OUT/branding_authed.json" data.recommendedRuntimeVersion)"
echo "BRANDING_deploymentVersion=$(json_get "$OUT/branding_authed.json" data.deploymentVersion)"
# The admin mapping is separate and derives canManage from isFirstActiveUser, which
# is the V050 rule - a second live confirmation of it, from a different code path
# than /api/platform/admins.
api branding_admin GET /api/platform/branding "$CFG0"
if [[ "$IS_ADMIN" == "1" ]]; then
  check branding_admin 200 true "admin branding: canManage derived from isFirstActiveUser"
  echo "BRANDING_ADMIN_canManage=$(json_get "$OUT/branding_admin.json" data.canManage)"
else
  check branding_admin 200 any "admin branding for a non-first-active user"
  echo "BRANDING_ADMIN_canManage=$(json_get "$OUT/branding_admin.json" data.canManage)"
fi

# --- 4. create workspace -----------------------------------------------------
WS_NAME="AW E2E Smoke Workspace $RUN_SUFFIX"
WS_EDITED_NAME="$WS_NAME (edited)"
api ws_create POST /api/workspaces "$CFG0" \
  "{\"name\":\"$WS_NAME\",\"description\":\"created by e2e-tests/authchain.sh\",\"background\":\"fresh-install smoke\"}"
check ws_create 200 true "creator becomes owner and administrator"
WS_ID="$(json_get "$OUT/ws_create.json" data.id)"
[[ -n "$WS_ID" ]] || { echo "FATAL: workspace creation returned no id"; exit 1; }
echo "WORKSPACE_ID=$WS_ID"

# --- 5. switch workspace -----------------------------------------------------
api ws_switch POST "/api/workspaces/$WS_ID/switch" "$CFG0"
check ws_switch 200 true "issues a workspace-scoped token"
T1="$(json_get "$OUT/ws_switch.json" data.accessToken)"
[[ -n "$T1" ]] || { echo "FATAL: switch returned no accessToken"; exit 1; }
CFG1="$(token_cfg t1 "$T1")"
echo "TOKEN1_PRESENT=yes"
echo "TOKEN1_LENGTH=${#T1}"
echo "SWITCH_ACCESS_LEVEL=$(json_get "$OUT/ws_switch.json" data.accessLevel)"

# --- 6. workspace-scoped reads, including this cycle's new ones ---------------
api ws_current GET /api/workspaces/current "$CFG1"
check ws_current 200 true "workspace-scoped token accepted"
api ws_mine GET /api/workspaces/mine "$CFG1"
check ws_mine 200 true "membership list"
api ws_all GET /api/workspaces/all "$CFG1"
check ws_all 200 true "NEW: directory backing workspace access requests"
api ws_recycle_bin_empty GET /api/workspaces/recycle-bin "$CFG1"
check ws_recycle_bin_empty 200 true "NEW: recycle bin (V051) empty before any delete"
api ws_membership GET /api/workspaces/current/membership "$CFG1"
check ws_membership 200 true "current membership"
api ws_access_requests GET /api/workspaces/current/access-requests "$CFG1"
check ws_access_requests 200 true "NEW: workspace_access_request table reachable"
api cap_scheduled_task GET /api/capabilities/scheduled-task "$CFG1"
check cap_scheduled_task 200 true "scheduled-task capability, was 401 unauthenticated"
echo "SCHEDULED_TASK_CAPABILITY=$(redact_file "$OUT/cap_scheduled_task.json" 500)"
api st_summary GET /api/scheduled-tasks/summary "$CFG1"
check st_summary 200 any "scheduled-task module enabled by the community default"

# --- 7. workspace edit (new this cycle) --------------------------------------
# The endpoint is optimistic-lock guarded: `version` is mandatory. Omitting it
# yields 400 "version 不能为空", which reads like a product defect but is a
# malformed request, so the version is taken from the read-back above.
WS_VERSION="$(json_get "$OUT/ws_current.json" data.version)"
[[ -n "$WS_VERSION" ]] || { echo "FATAL: no workspace version to send"; exit 1; }
api ws_update PUT "/api/workspaces/$WS_ID" "$CFG1" \
  "{\"name\":\"$WS_EDITED_NAME\",\"description\":\"edited by authchain.sh\",\"version\":$WS_VERSION}"
check ws_update 200 true "NEW: workspace edit, optimistic-lock version=$WS_VERSION"
api ws_current_after_edit GET /api/workspaces/current "$CFG1"
echo "WS_NAME_AFTER_EDIT=$(json_get "$OUT/ws_current_after_edit.json" data.name)"

# --- 8. V051 decisive test: uk_name -> uk_active_name ------------------------
# V051 swaps the unique key `uk_name` for `uk_active_name`, a STORED generated
# column that is NULL once a row is soft-deleted. Three behaviours follow, and all
# three are asserted against the live database rather than against SQL text:
#   (a) a soft-deleted workspace stops blocking its own name;
#   (b) restore succeeds while the name is free, and is refused with 11007 once an
#       active row holds that name again;
#   (c) an active row blocks new creation with that name (11003).
# Order matters: restoring *after* re-creating the same name is behaviour (b)'s
# negative half, not a happy path, and asserting 200 there reports a false failure.
DUP_NAME="AW E2E Dup Name Check $RUN_SUFFIX"
api ws_dup1 POST /api/workspaces "$CFG1" "{\"name\":\"$DUP_NAME\",\"description\":\"v051 probe A\"}"
check ws_dup1 200 true "workspace A with the duplicate-probe name"
WS_DUP="$(json_get "$OUT/ws_dup1.json" data.id)"
[[ -n "$WS_DUP" ]] || { echo "FATAL: probe workspace A has no id"; exit 1; }
echo "WS_DUP_A_ID=$WS_DUP"

api ws_dup_delete DELETE "/api/workspaces/$WS_DUP" "$CFG1"
check ws_dup_delete 200 true "NEW: soft delete of A"
echo "SQL_A_deleted_at_NOT_NULL=$(mysql_q "SELECT deleted_at IS NOT NULL FROM autowonder.org WHERE id=$WS_DUP")"
echo "SQL_A_active_name_key_IS_NULL=$(mysql_q "SELECT active_name_key IS NULL FROM autowonder.org WHERE id=$WS_DUP")"

api ws_dup2 POST /api/workspaces "$CFG1" "{\"name\":\"$DUP_NAME\",\"description\":\"v051 probe B\"}"
check ws_dup2 200 true "DECISIVE (a): same name accepted while A is soft-deleted"
WS_DUP2="$(json_get "$OUT/ws_dup2.json" data.id)"
[[ -n "$WS_DUP2" ]] || { echo "FATAL: probe workspace B has no id"; exit 1; }
echo "WS_DUP_B_ID=$WS_DUP2"

api ws_recycle_bin_after GET /api/workspaces/recycle-bin "$CFG1"
check ws_recycle_bin_after 200 true "NEW: soft-deleted A appears in the recycle bin"
echo "RECYCLE_BIN_IDS=$(json_get "$OUT/ws_recycle_bin_after.json" data.list)"

api ws_dup_delete_b DELETE "/api/workspaces/$WS_DUP2" "$CFG1"
check ws_dup_delete_b 200 true "soft delete of B, freeing the name again"

api ws_dup_restore_b POST "/api/workspaces/$WS_DUP2/restore" "$CFG1" '{}'
check ws_dup_restore_b 200 true "DECISIVE (b+): restore succeeds while the name is free"

api ws_dup_restore_a POST "/api/workspaces/$WS_DUP/restore" "$CFG1" '{}'
check ws_dup_restore_a 200 false "DECISIVE (b-): restore refused once B holds the name again"
echo "RESTORE_CONFLICT_CODE=$(json_get "$OUT/ws_dup_restore_a.json" code)"
echo "RESTORE_CONFLICT_MESSAGE=$(json_get "$OUT/ws_dup_restore_a.json" message)"

api ws_dup3 POST /api/workspaces "$CFG1" "{\"name\":\"$DUP_NAME\",\"description\":\"v051 probe C\"}"
check ws_dup3 200 false "DECISIVE (c): name rejected while an active row holds it"
echo "WS_DUP3_REJECT_CODE=$(json_get "$OUT/ws_dup3.json" code)"
echo "WS_DUP3_REJECT_MESSAGE=$(json_get "$OUT/ws_dup3.json" message)"

# --- 9. agent + work item + clarification conversation (new elicitation) -----
api agent_create POST /api/agents "$CFG1" \
  '{"name":"AW E2E Agent","roleName":"E2E Probe","roleCode":"AW_E2E_PROBE","businessBackground":"created by authchain.sh","responsibilities":"exercise the clarification conversation endpoints"}'
check agent_create 200 true "agent needed as the conversation counterparty"
AGENT_ID="$(json_get "$OUT/agent_create.json" data.id)"
echo "AGENT_ID=$AGENT_ID"

# Formal conversations use an approved online version, not an editing draft.
# CFG1 belongs to this disposable workspace's owner, who may approve their own
# version. Exercise the public lifecycle APIs; never manufacture database state.
if [[ "$AGENT_ID" =~ ^[1-9][0-9]*$ ]]; then
  api agent_submit POST "/api/agents/$AGENT_ID/submit" "$CFG1" '{}'
  check agent_submit 200 true "submit conversation counterparty for review"
  api agent_approve POST "/api/agents/$AGENT_ID/approve" "$CFG1" \
    '{"comment":"Approve disposable E2E conversation counterparty"}'
  check agent_approve 200 true "workspace owner approves the agent version"
  api agent_online GET "/api/agents/$AGENT_ID" "$CFG1"
  check agent_online 200 true "read back the published conversation counterparty"
  AGENT_STATUS="$(json_get "$OUT/agent_online.json" data.status)"
  ONLINE_VERSION_ID="$(json_get "$OUT/agent_online.json" data.onlineVersionId)"
else
  AGENT_STATUS=""
  ONLINE_VERSION_ID=""
fi
if [[ "$AGENT_STATUS" == ONLINE && "$ONLINE_VERSION_ID" =~ ^[1-9][0-9]*$ ]]; then
  PASS=$((PASS+1))
  echo "CHECK|PASS|agent_online_version|status=ONLINE|onlineVersionId=$ONLINE_VERSION_ID"
else
  FAIL=$((FAIL+1))
  echo "CHECK|FAIL|agent_online_version|expected ONLINE and a positive onlineVersionId"
fi

api wi_create POST /api/workitems "$CFG1" \
  '{"workType":"TASK","title":"AW E2E smoke work item","contentMd":"created by e2e-tests/authchain.sh to reach the new clarification-conversation endpoints","priority":2}'
check wi_create 200 true "work item creation in the switched workspace"
WI_ID="$(json_get "$OUT/wi_create.json" data.id)"
echo "WORKITEM_ID=$WI_ID"

api wi_get GET "/api/workitems/$WI_ID" "$CFG1"
check wi_get 200 true "read back the created work item"
api wi_timeline GET "/api/workitems/$WI_ID/timeline" "$CFG1"
check wi_timeline 200 true "timeline"
api wi_unified_timeline GET "/api/workitems/$WI_ID/unified-timeline" "$CFG1"
check wi_unified_timeline 200 any "NEW: runtime activity timeline"
api wi_delivery GET "/api/workitems/$WI_ID/delivery-progress" "$CFG1"
check wi_delivery 200 any "delivery progress"

if [[ -n "$AGENT_ID" ]]; then
  api conv_create POST "/api/workitems/$WI_ID/clarification-conversations" "$CFG1" \
    "{\"agentId\":$AGENT_ID}"
  check conv_create 200 true "NEW: ACP clarification conversation created"
  CONV_ID="$(json_get "$OUT/conv_create.json" data.id)"
  echo "CONVERSATION_ID=$CONV_ID"

  api conv_list GET "/api/workitems/$WI_ID/clarification-conversations?agentId=$AGENT_ID" "$CFG1"
  check conv_list 200 true "NEW: conversation list by agent"

  if [[ -n "$CONV_ID" ]]; then
    # Submitting a turn needs a connected runtime, and a fresh install has none by
    # definition - conv_create reports executorOnline. The expectation is branched
    # on that measured flag instead of a permissive `any`, so the offline refusal is
    # asserted rather than silently waved through.
    #
    # Offline, the refusal surfaces as an unhandled IllegalStateException
    # ("RUNTIME_OFFLINE") at WorkitemClarificationConversationService.java:163,
    # which GlobalExceptionHandler logs at ERROR and maps to code 10000. That file
    # is byte-identical to origin/master, so it is an upstream observability wart
    # (a foreseeable business condition reported as an internal error), recorded as
    # a finding and deliberately NOT "fixed" during a sync.
    EXECUTOR_ONLINE="$(json_get "$OUT/conv_create.json" data.executorOnline)"
    echo "CONV_EXECUTOR_ONLINE=$EXECUTOR_ONLINE"
    api conv_turn POST "/api/workitems/$WI_ID/clarification-conversations/$CONV_ID/turns" "$CFG1" \
      '{"content":"AW E2E clarification probe","clientMessageId":"aw-e2e-probe-1"}'
    echo "CONV_TURN_BIZ_CODE=$(json_get "$OUT/conv_turn.json" code)"
    echo "CONV_TURN_BIZ_MESSAGE=$(json_get "$OUT/conv_turn.json" message)"
    if [[ "$EXECUTOR_ONLINE" == "true" ]]; then
      check conv_turn 200 true "runtime online: clarification turn accepted"
    else
      check conv_turn 200 false "EXPECTED NEGATIVE: no runtime connected, turn refused (RUNTIME_OFFLINE)"
    fi
    api conv_events GET "/api/workitems/$WI_ID/clarification-conversations/$CONV_ID/events" "$CFG1"
    check conv_events 200 true "NEW: conversation event stream (where elicitations surface)"
    echo "CONV_EVENTS_BIZ_CODE=$(json_get "$OUT/conv_events.json" code)"
    echo "CONV_EVENTS_BODY=$(redact_file "$OUT/conv_events.json" 600)"
  else
    echo "CONVERSATION_TURNS_SKIPPED=conversation creation returned no id"
  fi
  echo "SQL_elicitation_rows=$(mysql_q 'SELECT COUNT(*) FROM autowonder.agent_conversation_elicitation')"
  echo "SQL_elicitation_table_exists=$(mysql_q "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='autowonder' AND table_name='agent_conversation_elicitation'")"
else
  echo "CONVERSATION_SKIPPED=agent creation did not return an id"
fi

echo "SQL_workspace_access_request_rows=$(mysql_q 'SELECT COUNT(*) FROM autowonder.workspace_access_request')"
echo "SQL_org_rows=$(mysql_q 'SELECT COUNT(*) FROM autowonder.org')"
echo "SQL_user_rows=$(mysql_q 'SELECT COUNT(*) FROM autowonder.user')"

redact_all
echo "REDACTED_BODIES=$(find "$OUT" -name 'redacted-*.json' | wc -l | tr -d ' ')"
echo "PASS_COUNT=$PASS"
echo "FAIL_COUNT=$FAIL"
echo "VERDICT=$([[ "$FAIL" == 0 ]] && echo ALL_CHECKS_PASSED || echo SOME_CHECKS_FAILED)"
} 2>&1 | tee "$REPORT"

log "authchain.sh done: report=$REPORT"
aw_report_require_zero "$REPORT" FAIL_COUNT
