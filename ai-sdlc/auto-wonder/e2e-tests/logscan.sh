#!/usr/bin/env bash
# Scan the application logs and attribute every ERROR / WARN to the request that
# provoked it.
#
#   e2e-tests/logscan.sh
#
# "Zero ERROR and every WARN explained" is not something a script can assert by
# counting: a smoke run deliberately provokes business rejections (a duplicate
# name, a permission check, an offline runtime), and those legitimately produce
# WARN and even ERROR lines. Counting them as failures would push whoever runs
# this next to delete the probes; ignoring them would hide real problems.
#
# So attribution is measured instead. Every log line this application writes
# carries the request_id of the request it belongs to, and every response body
# the harness saved carries that same request_id. Joining the two answers, for
# each diagnostic line, "which call of mine caused this?" A line that joins to a
# harness call is explained by construction. A line that does not join is
# unattributed, and unattributed ERROR or WARN fails the scan - that is the part
# which must be zero, because it means the application complained about something
# nobody asked it to do.
#
#   e2e-tests/logscan.sh            # after smoke.sh / authchain.sh
#
# Reads only files under the state directory. Writes log-scan-attributed.txt.

set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
# shellcheck source=lib/common.sh
source e2e-tests/lib/common.sh

aw_e2e_prepare_state_dir

APP_LOG="${AW_E2E_APP_LOG:-$AW_E2E_STATE_DIR/app.log}"
FILE_LOG="${AW_E2E_FILE_LOG:-$AW_E2E_STATE_DIR/run/logs/auto-wonder.log}"
OUT="$AW_E2E_STATE_DIR/logscan"
rm -rf "$OUT"
mkdir -p "$OUT"
REPORT="$AW_E2E_STATE_DIR/log-scan-attributed.txt"

[[ -f "$APP_LOG" ]] || die "no application log at $APP_LOG - run e2e-tests/smoke.sh first"

# The response bodies holding request_ids live in several places depending on how
# far the run got: authchain's own directory, smoke's capability probes.
body_dirs=()
[[ -d "$AW_E2E_STATE_DIR/authchain" ]] && body_dirs+=("$AW_E2E_STATE_DIR/authchain")
body_dirs+=("$AW_E2E_STATE_DIR")

# --- 1. collect the request_ids the harness itself generated -----------------
# Kept in a file, not in argv: response bodies can contain live tokens, and this
# step only ever needs the request_id out of them.
rid_map="$OUT/request-ids.tsv"
: >"$rid_map"
for dir in "${body_dirs[@]}"; do
  AW_E2E_RID_DIR="$dir" AW_E2E_RID_OUT="$rid_map" python3 <<'PY'
import json, os, sys
d = os.environ["AW_E2E_RID_DIR"]
out = os.environ["AW_E2E_RID_OUT"]
rows = []
for fn in sorted(os.listdir(d)):
    if not fn.endswith(".json") or fn.startswith("redacted-"):
        continue
    p = os.path.join(d, fn)
    try:
        with open(p) as fh:
            data = json.load(fh)
    except Exception:
        continue
    if isinstance(data, dict):
        rid = data.get("request_id")
        if isinstance(rid, str) and rid:
            rows.append((rid, fn[:-5]))
with open(out, "a") as fh:
    for rid, call in rows:
        fh.write(f"{rid}\t{call}\n")
PY
done
sort -u -o "$rid_map" "$rid_map"

# --- 2. scan and attribute ----------------------------------------------------
AW_E2E_APP_LOG="$APP_LOG" AW_E2E_FILE_LOG="$FILE_LOG" \
AW_E2E_RID_MAP="$rid_map" AW_E2E_REPORT="$REPORT" \
AW_E2E_RUNTIME_EVIDENCE="$AW_E2E_STATE_DIR/runtime-dispatch.json" \
AW_E2E_APP_LOG_START_LINE="${AW_E2E_APP_LOG_START_LINE:-0}" \
AW_E2E_FILE_LOG_START_LINE="${AW_E2E_FILE_LOG_START_LINE:-0}" python3 <<'PY'
import json, os, re, sys

app_log = os.environ["AW_E2E_APP_LOG"]
file_log = os.environ["AW_E2E_FILE_LOG"]
rid_map_path = os.environ["AW_E2E_RID_MAP"]
report = os.environ["AW_E2E_REPORT"]
app_start = int(os.environ["AW_E2E_APP_LOG_START_LINE"])
file_start = int(os.environ["AW_E2E_FILE_LOG_START_LINE"])

calls = {}
with open(rid_map_path) as fh:
    for line in fh:
        rid, call = line.rstrip("\n").split("\t", 1)
        calls.setdefault(rid, call)

# timestamp|LEVEL|request_id|METHOD path|logger|thread|message
LINE = re.compile(
    r"^(?P<ts>\d{4}-\d\d-\d\d \d\d:\d\d:\d\d[,.]\d+)\|(?P<level>[A-Z]+)\|"
    r"(?P<rid>[^|]*)\|(?P<endpoint>[^|]*)\|(?P<logger>[^|]*)\|(?P<thread>[^|]*)\|(?P<msg>.*)$"
)
# The rolling file appender uses a space-separated layout; accept both so a format
# change in log4j2.xml cannot silently turn the scan into a no-op.
LINE_ALT = re.compile(
    r"^(?P<ts>\d{4}-\d\d-\d\d \d\d:\d\d:\d\d[,.]\d+)\s+(?P<level>ERROR|WARN|INFO|DEBUG|TRACE)\s+(?P<msg>.*)$"
)
STACK = re.compile(r"^\s*(at\s|Caused by:|\.\.\.\s\d+\smore)|Exception|Throwable")

def read_lines(path):
    if not path or not os.path.exists(path):
        return []
    with open(path, errors="replace") as fh:
        return fh.read().splitlines()

app_all_lines = read_lines(app_log)
file_all_lines = read_lines(file_log)
app_start = app_start if 0 <= app_start <= len(app_all_lines) else 0
file_start = file_start if 0 <= file_start <= len(file_all_lines) else 0
app_lines = app_all_lines[app_start:]
file_lines = file_all_lines[file_start:]

# Anything at these levels anywhere in the line, including the file appender's
# layout, so a diagnostic cannot slip past by being formatted differently.
def level_of(line):
    m = LINE.match(line)
    if m:
        return m.group("level"), m.group("rid"), m.group("endpoint"), m.group("msg")
    m = LINE_ALT.match(line)
    if m and m.group("level") in ("ERROR", "WARN"):
        return m.group("level"), "", "", m.group("msg")
    if re.search(r"\bERROR\b", line):
        return "ERROR", "", "", line
    if re.search(r"\bWARN\b", line):
        return "WARN", "", "", line
    return None, "", "", ""

# Runtime diagnostics have no saved HTTP response request_id. Permit only the
# measured successful scenario, exact protocol messages, and owned cleanup span.
try:
    with open(os.environ["AW_E2E_RUNTIME_EVIDENCE"]) as fh:
        runtime = json.load(fh)
except (OSError, ValueError):
    runtime = {}
required_checks = {"executor_online", "routing_matches", "server_acked", "server_running",
                   "dispatch_succeeded", "downloaded_artifact_matches"}
runtime_ok = (isinstance(runtime, dict) and runtime.get("verdict") == "PASS"
              and runtime.get("cleanup") is True
              and isinstance(runtime.get("checks"), list)
              and all(isinstance(check, str) for check in runtime["checks"])
              and required_checks.issubset(runtime["checks"])
              and all(type(runtime.get(key)) is int and runtime[key] > 0
                      for key in ("executorId", "workitemId", "dispatchId", "artifactId", "workspaceId")))

def runtime_attribution(line, source, line_number):
    match = LINE.fullmatch(line)
    if not runtime_ok or not match or match["level"] != "WARN":
        return None
    message = match["msg"].strip()
    executor, workitem, dispatch = (runtime[key] for key in ("executorId", "workitemId", "dispatchId"))
    if (match["logger"] == "DispatchAiUsageService"
            and match["endpoint"] == f"POST /api/daemon/dispatches/{dispatch}/artifacts"
            and re.fullmatch(
                rf"usage artifact ingest skipped artifactId=[1-9][0-9]* "
                rf"ossRef=[^\s/]+/t/{runtime['workspaceId']}/workitem/{workitem}/dispatch/{dispatch}/"
                rf"objects/sha256/[0-9a-f]{{64}}/observability/usage\.json "
                rf"workitemId={workitem} dispatchId={dispatch} reason=no_entries", message)):
        return "runtime_optional_usage_no_entries"
    if (match["logger"] == "ExecutorWsEndpoint" and not match["endpoint"]
            and message == f"WS error executorId={executor} java.io.EOFException"):
        starts, ends = runtime.get("cleanupLogStart"), runtime.get("cleanupLogEnd")
        if isinstance(starts, dict) and isinstance(ends, dict):
            start, end = starts.get(source), ends.get(source)
            if type(start) is int and type(end) is int and 0 <= start < line_number <= end:
                return "runtime_owned_teardown_eof"
    return None

attr_rows = []
unattr_rows = []
counts = {"ERROR": 0, "WARN": 0}
attr_counts = {"ERROR": 0, "WARN": 0}
stack = 0
startup_banner = []

for src, lines, start in (("stdout", app_lines, app_start), ("file", file_lines, file_start)):
    for ln, line in enumerate(lines, start + 1):
        if STACK.search(line):
            stack += 1
        if "Started Bootstrap" in line or "Tomcat started on port" in line:
            startup_banner.append(f"{src}:{ln}|{line}")
        level, rid, endpoint, msg = level_of(line)
        if level not in ("ERROR", "WARN"):
            continue
        counts[level] += 1
        call = calls.get(rid) or runtime_attribution(line, src, ln)
        if call:
            attr_counts[level] += 1
            attr_rows.append(f"ATTRIB|{level}|{src}:{ln}|rid={rid}|call={call}|endpoint={endpoint}|{msg}")
        else:
            unattr_rows.append(f"UNATTRIB|{level}|{src}:{ln}|rid={rid or '<none>'}|endpoint={endpoint or '<none>'}|{line}")

unattr_error = sum(1 for r in unattr_rows if r.startswith("UNATTRIB|ERROR"))
unattr_warn = sum(1 for r in unattr_rows if r.startswith("UNATTRIB|WARN"))

with open(report, "w") as fh:
    w = fh.write
    w("# post-chain log scan with request-id attribution\n")
    w(f"APP_LOG={app_log}\n")
    w(f"APP_LOG_LINES={len(app_lines)}\n")
    w(f"APP_LOG_START_LINE={app_start}\n")
    w(f"FILE_LOG={file_log}\n")
    w(f"FILE_LOG_LINES={len(file_lines)}\n")
    w(f"FILE_LOG_START_LINE={file_start}\n")
    w(f"HARNESS_REQUEST_IDS={len(calls)}\n")
    w(f"ERROR_TOTAL={counts['ERROR']}\n")
    w(f"WARN_TOTAL={counts['WARN']}\n")
    w(f"ERROR_ATTRIBUTED_TO_HARNESS={attr_counts['ERROR']}\n")
    w(f"WARN_ATTRIBUTED_TO_HARNESS={attr_counts['WARN']}\n")
    w(f"ERROR_UNATTRIBUTED={unattr_error}\n")
    w(f"WARN_UNATTRIBUTED={unattr_warn}\n")
    w(f"STACK_AND_EXCEPTION_LINES={stack}\n")
    w("STARTUP_BANNER:\n")
    for b in startup_banner:
        w(f"  {b}\n")
    w("ATTRIBUTED_DIAGNOSTICS:\n")
    for r in attr_rows:
        w(f"  {r}\n")
    w("UNATTRIBUTED_DIAGNOSTICS:\n")
    for r in unattr_rows:
        w(f"  {r}\n")
    ok = unattr_error == 0 and unattr_warn == 0
    w(f"VERDICT={'CLEAN_ALL_DIAGNOSTICS_ATTRIBUTED' if ok else 'UNEXPLAINED_DIAGNOSTICS_PRESENT'}\n")

print(open(report).read())
sys.exit(0 if ok else 1)
PY

log "logscan.sh done: report=$REPORT"
