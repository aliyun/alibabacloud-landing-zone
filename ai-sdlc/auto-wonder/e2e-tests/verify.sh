#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/lifecycle.sh
source "$SCRIPT_DIR/lib/lifecycle.sh"
# shellcheck source=lib/bootstrap.sh
source "$SCRIPT_DIR/lib/bootstrap.sh"

usage() {
  cat <<'EOF'
Usage:
  e2e-tests/verify.sh --start --project-root PATH [--mode image] [--keep-on-failure] [--no-install] [--startup-timeout SECONDS]
  e2e-tests/verify.sh --status --project-root PATH
  e2e-tests/verify.sh --logs [--follow] --project-root PATH
  e2e-tests/verify.sh --check [--with-runtime] --project-root PATH
  e2e-tests/verify.sh --stop --project-root PATH
  e2e-tests/verify.sh --clean-up --project-root PATH

The script never fetches, checks out, merges, or modifies the target Git worktree.
Credentials are written to runtime.env with mode 0600 and are never printed.
EOF
}

operation=""
project_root=""
mode="image"
keep_on_failure=0
follow_logs=0
startup_timeout=180
bootstrap_install=1
with_runtime=0

set_operation() {
  [[ -z "$operation" ]] || {
    printf 'FATAL: choose exactly one lifecycle operation\n' >&2
    exit 2
  }
  operation="$1"
}

while [[ "$#" -gt 0 ]]; do
  case "$1" in
    --start) set_operation start; shift ;;
    --status) set_operation status; shift ;;
    --logs) set_operation logs; shift ;;
    --check) set_operation check; shift ;;
    --with-runtime) with_runtime=1; shift ;;
    --stop) set_operation stop; shift ;;
    --clean-up) set_operation clean-up; shift ;;
    --project-root)
      [[ "$#" -ge 2 ]] || { printf 'FATAL: --project-root requires a path\n' >&2; exit 2; }
      project_root="$2"; shift 2 ;;
    --mode)
      [[ "$#" -ge 2 ]] || { printf 'FATAL: --mode requires a value\n' >&2; exit 2; }
      mode="$2"; shift 2 ;;
    --keep-on-failure) keep_on_failure=1; shift ;;
    --no-install) bootstrap_install=0; shift ;;
    --follow) follow_logs=1; shift ;;
    --startup-timeout)
      [[ "$#" -ge 2 ]] || { printf 'FATAL: --startup-timeout requires seconds\n' >&2; exit 2; }
      startup_timeout="$2"; shift 2 ;;
    --help|-h) usage; exit 0 ;;
    *) printf 'FATAL: unknown argument: %s\n' "$1" >&2; usage >&2; exit 2 ;;
  esac
done

[[ -n "$operation" ]] || { printf 'FATAL: choose a lifecycle operation\n' >&2; usage >&2; exit 2; }
[[ "$with_runtime" == 0 || "$operation" == check ]] || { printf 'FATAL: --with-runtime requires --check\n' >&2; exit 2; }
[[ -n "$project_root" ]] || { printf 'FATAL: --project-root is required\n' >&2; exit 2; }
[[ "$mode" == "image" ]] || { printf 'FATAL: only --mode image is supported\n' >&2; exit 2; }
[[ "$startup_timeout" =~ ^[1-9][0-9]*$ ]] || { printf 'FATAL: startup timeout must be a positive integer\n' >&2; exit 2; }

write_result() {
  local verdict="$1" phase="$2" kind="$3"
  if ! command -v python3 >/dev/null 2>&1; then
    cat >"$AW_RESULT_JSON" <<EOF
{
  "verdict": "$verdict",
  "operation": "$operation",
  "failedPhase": "$phase",
  "failureKind": "$kind"
}
EOF
    return 0
  fi
  AW_RESULT_VERDICT="$verdict" AW_RESULT_PHASE="$phase" AW_RESULT_KIND="$kind" \
  AW_RESULT_OPERATION="$operation" python3 - "$AW_RESULT_JSON" <<'PY'
import json
import os
import sys
from datetime import datetime, timezone

path = sys.argv[1]
data = {}
if os.path.exists(path):
    try:
        with open(path, encoding="utf-8") as stream:
            data = json.load(stream)
    except Exception:
        data = {}
data.update({
    "verdict": os.environ["AW_RESULT_VERDICT"],
    "operation": os.environ["AW_RESULT_OPERATION"],
    "failedPhase": os.environ["AW_RESULT_PHASE"] or None,
    "failureKind": os.environ["AW_RESULT_KIND"] or None,
    "updatedAt": datetime.now(timezone.utc).isoformat(),
})
with open(path, "w", encoding="utf-8") as stream:
    json.dump(data, stream, ensure_ascii=False, indent=2)
    stream.write("\n")
PY
}

write_lifecycle_env() {
  local state_env="$AW_RUN_DIR/lifecycle.env"
  {
    printf 'AW_E2E_PROJECT=%q\n' "$AW_E2E_PROJECT"
    printf 'AW_E2E_NETWORK=%q\n' "$AW_E2E_NETWORK"
    printf 'AW_E2E_STATE_DIR=%q\n' "$AW_E2E_STATE_DIR"
    printf 'AW_E2E_APP_IMAGE=%q\n' "$AW_E2E_APP_IMAGE"
    printf 'AW_E2E_RUNTIME_ENV=%q\n' "$AW_APP_ENV"
    printf 'AW_E2E_LOG_DIR=%q\n' "$AW_LOG_DIR"
    printf 'AW_E2E_STARTUP_TIMEOUT=%q\n' "$startup_timeout"
    printf 'AW_E2E_KEEP_ON_FAILURE=%q\n' "$keep_on_failure"
    printf 'AW_E2E_DOCKER=%q\n' "${AW_E2E_DOCKER:-}"
    printf 'RESOLVED_MYSQL_PORT=%q\n' "$RESOLVED_MYSQL_PORT"
    printf 'RESOLVED_REDIS_PORT=%q\n' "$RESOLVED_REDIS_PORT"
    printf 'RESOLVED_MINIO_PORT=%q\n' "$RESOLVED_MINIO_PORT"
    printf 'RESOLVED_MINIO_CONSOLE_PORT=%q\n' "$RESOLVED_MINIO_CONSOLE_PORT"
    printf 'RESOLVED_APP_PORT=%q\n' "$RESOLVED_APP_PORT"
    printf 'AW_E2E_MYSQL_PORT=%q\n' "$RESOLVED_MYSQL_PORT"
    printf 'AW_E2E_REDIS_PORT=%q\n' "$RESOLVED_REDIS_PORT"
    printf 'AW_E2E_MINIO_PORT=%q\n' "$RESOLVED_MINIO_PORT"
    printf 'AW_E2E_MINIO_CONSOLE_PORT=%q\n' "$RESOLVED_MINIO_CONSOLE_PORT"
    printf 'AW_E2E_APP_PORT=%q\n' "$RESOLVED_APP_PORT"
  } >"$state_env"
  chmod 600 "$state_env"
}

load_current() {
  local require_runtime="${1:-1}"
  aw_lifecycle_load "$project_root"
  REPO_ROOT="$AW_PROJECT_ROOT"
  export REPO_ROOT
  if [[ ! -f "$AW_RUN_DIR/lifecycle.env" ]]; then
    [[ "$require_runtime" == "0" ]] && return 0
    printf 'FATAL: lifecycle state is incomplete: %s/lifecycle.env\n' "$AW_RUN_DIR" >&2
    exit 1
  fi
  # Contains only shell-escaped non-secret values written by this script.
  # shellcheck disable=SC1090
  source "$AW_RUN_DIR/lifecycle.env"
  export AW_E2E_PROJECT AW_E2E_NETWORK AW_E2E_STATE_DIR AW_E2E_APP_IMAGE
  export AW_E2E_RUNTIME_ENV AW_E2E_LOG_DIR AW_E2E_STARTUP_TIMEOUT
  export AW_E2E_DOCKER
  export RESOLVED_MYSQL_PORT RESOLVED_REDIS_PORT RESOLVED_MINIO_PORT
  export RESOLVED_MINIO_CONSOLE_PORT RESOLVED_APP_PORT
  export AW_E2E_MYSQL_PORT AW_E2E_REDIS_PORT AW_E2E_MINIO_PORT
  export AW_E2E_MINIO_CONSOLE_PORT AW_E2E_APP_PORT
  # shellcheck source=lib/common.sh
  source "$SCRIPT_DIR/lib/common.sh"
  if [[ -f "$AW_E2E_STATE_DIR/resolved.env" ]]; then
    aw_e2e_load_resolved
    export RESOLVED_MYSQL_PORT RESOLVED_REDIS_PORT RESOLVED_MINIO_PORT
    export RESOLVED_MINIO_CONSOLE_PORT RESOLVED_APP_PORT
    export AW_E2E_MINIO_PORT AW_E2E_MINIO_CONSOLE_PORT AW_E2E_APP_PORT
  fi
}

run_logged_phase() {
  local phase="$1" log_path="$2"; shift 2
  local started now command_pid status append=0 description
  if [[ "${1:-}" == "--append" ]]; then append=1; shift; fi
  started="$(date +%s)"
  case "$phase" in
    BUILD) description="Running Maven clean verify" ;;
    IMAGE_BUILD) description="Building the community application image" ;;
    DEPENDENCIES) description="Starting MySQL, Redis and MinIO; applying and verifying schema" ;;
    APPLICATION_START) description="Starting Spring Boot and waiting for health" ;;
    *) description="Running $phase" ;;
  esac
  aw_step_event "$phase" START "$description|log=$log_path"
  aw_phase "$phase" RUNNING
  if [[ "$append" == "1" ]]; then
    "$@" >>"$log_path" 2>&1 &
  else
    "$@" >"$log_path" 2>&1 &
  fi
  command_pid=$!
  while kill -0 "$command_pid" 2>/dev/null; do
    sleep 5
    now="$(date +%s)"
    if (( (now - started) % 10 < 5 )); then
      printf 'PROGRESS|%s|elapsed=%ss|log=%s\n' "$phase" "$((now - started))" "$log_path"
    fi
  done
  status=0
  wait "$command_pid" || status=$?
  if [[ "$status" != "0" ]]; then
    aw_step_event "$phase" FAIL "exit=$status|log=$log_path"
    aw_phase "$phase" FAIL "exit=$status|log=$log_path"
    tail -80 "$log_path" >&2 || true
    return "$status"
  fi
  now="$(date +%s)"
  aw_step_event "$phase" PASS "duration=$((now - started))s|log=$log_path"
  aw_phase "$phase" PASS "duration=$((now - started))s"
}

capture_dependency_logs() {
  local service
  for service in mysql redis minio; do
    aw_e2e_compose logs --no-color "$service" >"$AW_LOG_DIR/$service.log" 2>&1 || true
  done
}

load_resolved_if_present() {
  [[ -f "$AW_RUN_DIR/resolved.env" ]] || return 0
  # shellcheck disable=SC1090
  source "$AW_RUN_DIR/resolved.env"
  export RESOLVED_MYSQL_PORT RESOLVED_REDIS_PORT RESOLVED_MINIO_PORT
  export RESOLVED_MINIO_CONSOLE_PORT RESOLVED_APP_PORT
  export AW_E2E_MINIO_PORT AW_E2E_MINIO_CONSOLE_PORT AW_E2E_APP_PORT
}

write_log_checkpoint() {
  local app_log="$AW_LOG_DIR/spring-boot-console.log" file_log="$AW_LOG_DIR/auto-wonder.log"
  {
    printf 'AW_E2E_APP_LOG_START_LINE=%s\n' "$(wc -l <"$app_log" | tr -d ' ')"
    printf 'AW_E2E_FILE_LOG_START_LINE=%s\n' "$(wc -l <"$file_log" | tr -d ' ')"
  } >"$AW_RUN_DIR/log-checkpoint.env"
}

print_failure() {
  local phase="$1" kind="$2" summary="$3"
  write_result FAIL "$phase" "$kind"
  {
    printf 'VERDICT=FAIL\n'
    printf 'FAILED_PHASE=%s\n' "$phase"
    printf 'FAILURE_KIND=%s\n' "$kind"
    printf 'ROOT_CAUSE_SUMMARY=%s\n' "$summary"
    printf 'SPRING_BOOT_CONSOLE_LOG=%s/spring-boot-console.log\n' "$AW_LOG_DIR"
    printf 'STARTUP_DIAGNOSTIC_LOG=%s/startup-diagnostic.log\n' "$AW_LOG_DIR"
    printf 'RESULT_JSON=%s\n' "$AW_RESULT_JSON"
    printf 'NEXT_COMMAND=%s/e2e-tests/verify.sh --logs --project-root %s\n' "$AW_PROJECT_ROOT" "$AW_PROJECT_ROOT"
  } | tee "$AW_RUN_DIR/failure-report.txt"
}

finish_start_failure() {
  local phase="$1" kind="$2" summary="$3"
  print_failure "$phase" "$kind" "$summary"
  if [[ "$keep_on_failure" == "1" ]]; then
    printf 'FAILURE_RESOURCES=PRESERVED\n'
    printf 'CLEANUP_COMMAND=%s/e2e-tests/verify.sh --clean-up --project-root %s\n' "$AW_PROJECT_ROOT" "$AW_PROJECT_ROOT"
    aw_lifecycle_event END "FAIL|verdict=FAIL|phase=$phase|failureKind=$kind"
    return 0
  fi
  if [[ -n "${DOCKER:-}" ]] && "$DOCKER" version >/dev/null 2>&1; then
    load_resolved_if_present
    capture_dependency_logs
    aw_e2e_compose down -v --remove-orphans >>"$AW_LOG_DIR/compose.log" 2>&1 || true
  fi
  printf 'FAILURE_RESOURCES=REMOVED\n'
  printf 'FAILURE_EVIDENCE=PRESERVED_AT=%s\n' "$AW_RUN_DIR"
  aw_lifecycle_event END "FAIL|verdict=FAIL|phase=$phase|failureKind=$kind"
  return 0
}

write_runtime_manifest() {
  local branch commit dirty version
  branch="$(git -C "$AW_PROJECT_ROOT" branch --show-current)"
  commit="$(git -C "$AW_PROJECT_ROOT" rev-parse HEAD)"
  dirty=false
  [[ -z "$(git -C "$AW_PROJECT_ROOT" status --porcelain)" ]] || dirty=true
  version="$(tr -d '[:space:]' <"$AW_PROJECT_ROOT/VERSION")"
  AW_MANIFEST_BRANCH="$branch" AW_MANIFEST_COMMIT="$commit" AW_MANIFEST_DIRTY="$dirty" \
  AW_MANIFEST_VERSION="$version" python3 - "$AW_RUNTIME_JSON" <<'PY'
import json
import os
import shlex
import sys

data = {
    "runId": os.environ["AW_RUN_ID"],
    "projectRoot": os.environ["AW_PROJECT_ROOT"],
    "mode": "image",
    "git": {
        "branch": os.environ["AW_MANIFEST_BRANCH"],
        "commit": os.environ["AW_MANIFEST_COMMIT"],
        "dirty": os.environ["AW_MANIFEST_DIRTY"] == "true",
    },
    "image": os.environ["AW_E2E_APP_IMAGE"],
    "version": os.environ["AW_MANIFEST_VERSION"],
    "platformUrl": f"http://127.0.0.1:{os.environ['RESOLVED_APP_PORT']}",
    "mysql": {"host": "127.0.0.1", "port": int(os.environ["RESOLVED_MYSQL_PORT"]), "database": "autowonder", "username": "autowonder", "credentialsFile": os.environ["AW_RUNTIME_ENV"]},
    "redis": {"host": "127.0.0.1", "port": int(os.environ["RESOLVED_REDIS_PORT"]), "credentialsFile": os.environ["AW_RUNTIME_ENV"]},
    "minio": {"url": f"http://127.0.0.1:{os.environ['RESOLVED_MINIO_PORT']}", "consoleUrl": f"http://127.0.0.1:{os.environ['RESOLVED_MINIO_CONSOLE_PORT']}", "credentialsFile": os.environ["AW_RUNTIME_ENV"]},
    "logs": {
        "build": os.path.join(os.environ["AW_LOG_DIR"], "build.log"),
        "imageBuild": os.path.join(os.environ["AW_LOG_DIR"], "image-build.log"),
        "compose": os.path.join(os.environ["AW_LOG_DIR"], "compose.log"),
        "springBootConsole": os.path.join(os.environ["AW_LOG_DIR"], "spring-boot-console.log"),
        "springBootFile": os.path.join(os.environ["AW_LOG_DIR"], "auto-wonder.log"),
        "startupDiagnostic": os.path.join(os.environ["AW_LOG_DIR"], "startup-diagnostic.log"),
    },
    "agentGuide": os.path.join(os.environ["AW_PROJECT_ROOT"], "e2e-tests", "AGENT_GUIDE.md"),
    "resources": {"composeProject": os.environ["AW_E2E_PROJECT"], "network": os.environ["AW_E2E_NETWORK"]},
    "nextCommands": {
        "status": f"./e2e-tests/verify.sh --status --project-root {shlex.quote(os.environ['AW_PROJECT_ROOT'])}",
        "logs": f"./e2e-tests/verify.sh --logs --project-root {shlex.quote(os.environ['AW_PROJECT_ROOT'])}",
        "check": f"./e2e-tests/verify.sh --check --project-root {shlex.quote(os.environ['AW_PROJECT_ROOT'])}",
        "stop": f"./e2e-tests/verify.sh --stop --project-root {shlex.quote(os.environ['AW_PROJECT_ROOT'])}",
        "cleanUp": f"./e2e-tests/verify.sh --clean-up --project-root {shlex.quote(os.environ['AW_PROJECT_ROOT'])}",
    },
}
with open(sys.argv[1], "w", encoding="utf-8") as stream:
    json.dump(data, stream, ensure_ascii=False, indent=2)
    stream.write("\n")
PY
}

run_start() {
  aw_lifecycle_refuse_active "$project_root"
  aw_lifecycle_init "$project_root"
  aw_lifecycle_event START "operation=start|projectRoot=$AW_PROJECT_ROOT|mode=$mode|autoInstall=$bootstrap_install"
  REPO_ROOT="$AW_PROJECT_ROOT"
  export REPO_ROOT
  aw_publish_paths
  write_result RUNNING "" ""

  AW_E2E_PROJECT="$AW_RUN_ID"
  AW_E2E_NETWORK="$AW_RUN_ID-net"
  AW_E2E_STATE_DIR="$AW_RUN_DIR"
  AW_E2E_APP_IMAGE="autowonder-community-e2e:$AW_RUN_ID"
  AW_E2E_RUNTIME_ENV="$AW_APP_ENV"
  AW_E2E_LOG_DIR="$AW_LOG_DIR"
  AW_E2E_STARTUP_TIMEOUT="$startup_timeout"
  export AW_E2E_PROJECT AW_E2E_NETWORK AW_E2E_STATE_DIR AW_E2E_APP_IMAGE
  export AW_E2E_RUNTIME_ENV AW_E2E_LOG_DIR AW_E2E_STARTUP_TIMEOUT

  # shellcheck source=lib/common.sh
  source "$SCRIPT_DIR/lib/common.sh"
  # Persist enough state before Docker/build work that status, logs, stop and
  # cleanup can locate this run even when the first long phase fails.
  RESOLVED_MYSQL_PORT="$AW_E2E_MYSQL_PORT"
  RESOLVED_REDIS_PORT="$AW_E2E_REDIS_PORT"
  RESOLVED_MINIO_PORT="$AW_E2E_MINIO_PORT"
  RESOLVED_MINIO_CONSOLE_PORT="$AW_E2E_MINIO_CONSOLE_PORT"
  RESOLVED_APP_PORT="$AW_E2E_APP_PORT"
  export RESOLVED_MYSQL_PORT RESOLVED_REDIS_PORT RESOLVED_MINIO_PORT
  export RESOLVED_MINIO_CONSOLE_PORT RESOLVED_APP_PORT
  write_lifecycle_env
  AW_BOOTSTRAP_INSTALL="$bootstrap_install"
  AW_BOOTSTRAP_LOG="$AW_LOG_DIR/bootstrap.log"
  AW_BOOTSTRAP_STATE="$AW_RUN_DIR/bootstrap-state.tsv"
  export AW_BOOTSTRAP_INSTALL AW_BOOTSTRAP_LOG AW_BOOTSTRAP_STATE
  if ! aw_bootstrap_all; then
    finish_start_failure BOOTSTRAP "${AW_BOOTSTRAP_LAST_FAILURE_KIND:-BOOTSTRAP_FAILED}" \
      "${AW_BOOTSTRAP_LAST_FAILURE_SUMMARY:-Host prerequisite bootstrap failed; see bootstrap.log}"
    return 1
  fi
  write_lifecycle_env
  aw_e2e_init_runtime >>"$AW_LOG_DIR/compose.log" 2>&1
  if ! run_logged_phase BUILD "$AW_LOG_DIR/build.log" \
    mvn -B -DskipGitCommitId=true -f "$AW_PROJECT_ROOT/pom.xml" clean verify; then
    finish_start_failure BUILD BUILD_FAILED "Maven clean verify failed"
    return 1
  fi
  if ! run_logged_phase IMAGE_BUILD "$AW_LOG_DIR/image-build.log" \
    "$DOCKER" build --platform linux/amd64 -f "$AW_PROJECT_ROOT/APP-META/docker-config/Dockerfile" \
      -t "$AW_E2E_APP_IMAGE" "$AW_PROJECT_ROOT"; then
    finish_start_failure IMAGE_BUILD IMAGE_BUILD_FAILED "Community image build failed"
    return 1
  fi
  : >"$AW_RUN_DIR/compose-attempted"
  if ! run_logged_phase DEPENDENCIES "$AW_LOG_DIR/compose.log" \
    "$AW_PROJECT_ROOT/e2e-tests/up.sh"; then
    load_resolved_if_present
    capture_dependency_logs
    tail -80 "$AW_LOG_DIR/compose.log" >&2 || true
    finish_start_failure DEPENDENCIES DEPENDENCY_OR_SCHEMA_FAILED "Dependency startup or schema verification failed"
    return 1
  fi
  # up.sh persists the complete resolution for every later command.
  # shellcheck disable=SC1090
  load_resolved_if_present
  write_lifecycle_env
  if ! run_logged_phase APPLICATION_START "$AW_LOG_DIR/compose.log" --append \
    "$AW_PROJECT_ROOT/e2e-tests/smoke.sh"; then
    startup_failure_kind=STARTUP_FAILED
    if [[ -f "$AW_RUN_DIR/startup-status.env" ]]; then
      # Contains one enum value written by smoke.sh.
      # shellcheck disable=SC1090
      source "$AW_RUN_DIR/startup-status.env"
      startup_failure_kind="$STARTUP_FAILURE_KIND"
    fi
    tail -80 "$AW_LOG_DIR/spring-boot-console.log" >&2 || true
    finish_start_failure APPLICATION_START "$startup_failure_kind" "Spring Boot did not become healthy"
    return 1
  fi
  write_log_checkpoint
  {
    printf 'AGENT_PLATFORM_URL=http://127.0.0.1:%s\n' "$RESOLVED_APP_PORT"
    printf 'AGENT_MYSQL_HOST=127.0.0.1\nAGENT_MYSQL_PORT=%s\nAGENT_MYSQL_DATABASE=autowonder\n' "$RESOLVED_MYSQL_PORT"
    printf 'AGENT_MYSQL_USERNAME=autowonder\nAGENT_MYSQL_PASSWORD=change-me\n'
    printf 'AGENT_REDIS_HOST=127.0.0.1\nAGENT_REDIS_PORT=%s\nAGENT_REDIS_PASSWORD=\n' "$RESOLVED_REDIS_PORT"
    printf 'AGENT_MINIO_URL=http://127.0.0.1:%s\nAGENT_MINIO_CONSOLE_URL=http://127.0.0.1:%s\n' "$RESOLVED_MINIO_PORT" "$RESOLVED_MINIO_CONSOLE_PORT"
    printf 'AGENT_MINIO_USERNAME=%s\nAGENT_MINIO_PASSWORD=%s\n' "$AW_E2E_MINIO_ROOT_USER" "$(aw_e2e_secret AW_E2E_MINIO_ROOT_PASSWORD)"
  } >"$AW_RUNTIME_ENV"
  chmod 600 "$AW_RUNTIME_ENV"
  write_lifecycle_env
  write_runtime_manifest
  write_result STARTED "" ""
  printf 'VERDICT=STARTED\n'
  printf 'PLATFORM_URL=http://127.0.0.1:%s\n' "$RESOLVED_APP_PORT"
  printf 'RUNTIME_JSON=%s\nRUNTIME_ENV=%s\n' "$AW_RUNTIME_JSON" "$AW_RUNTIME_ENV"
  printf 'AGENT_NEXT_STEP_1=Read %s\n' "$AW_RUNTIME_JSON"
  printf 'AGENT_NEXT_STEP_2=Read credentials from %s when needed\n' "$AW_RUNTIME_ENV"
  printf 'AGENT_NEXT_STEP_3=Perform browser or API checks, then run --check\n'
  aw_lifecycle_event END "PASS|verdict=STARTED|platformUrl=http://127.0.0.1:$RESOLVED_APP_PORT"
}

run_status() {
  load_current
  aw_publish_paths
  [[ -f "$AW_RUNTIME_JSON" ]] && cat "$AW_RUNTIME_JSON"
  aw_e2e_init_runtime
  aw_e2e_compose ps
  curl --silent --show-error --max-time 5 "http://127.0.0.1:$RESOLVED_APP_PORT/checkpreload.htm"
  printf '\n'
}

run_logs() {
  load_current 0
  local console_log="$AW_LOG_DIR/spring-boot-console.log"
  [[ -f "$console_log" ]] || { printf 'FATAL: log not found: %s\n' "$console_log" >&2; return 1; }
  if [[ "$follow_logs" == "1" ]]; then tail -F "$console_log"; else cat "$console_log"; fi
}

run_check() {
  local app_start=0 file_start=0
  load_current
  aw_e2e_init_runtime
  if [[ "$with_runtime" == 1 ]] && ! python3 "$AW_PROJECT_ROOT/e2e-tests/runtime_dispatch.py" --preflight; then
    print_failure CHECK_RUNTIME RUNTIME_PREFLIGHT_FAILED "Real runtime prerequisites are not satisfied"
    return 1
  fi
  if [[ -f "$AW_RUN_DIR/log-checkpoint.env" ]]; then
    # Contains integer line counts written by this script.
    # shellcheck disable=SC1090
    source "$AW_RUN_DIR/log-checkpoint.env"
    app_start="$AW_E2E_APP_LOG_START_LINE"
    file_start="$AW_E2E_FILE_LOG_START_LINE"
  fi
  if ! aw_require_equal health.http 200 "$(curl -s -o "$AW_RUN_DIR/check-health.body" -w '%{http_code}' "http://127.0.0.1:$RESOLVED_APP_PORT/checkpreload.htm" || true)"; then
    print_failure CHECK_HEALTH HEALTH_FAILED "Application health endpoint is not ready"
    return 1
  fi
  if ! AW_E2E_APP_LOG="$AW_LOG_DIR/spring-boot-console.log" \
    AW_E2E_FILE_LOG="$AW_LOG_DIR/auto-wonder.log" \
      "$AW_PROJECT_ROOT/e2e-tests/authchain.sh"; then
    print_failure CHECK_AUTHENTICATED AUTHENTICATED_CHECK_FAILED "Authenticated community smoke chain failed"
    return 1
  fi
  if [[ "$with_runtime" == 1 ]] && ! python3 "$AW_PROJECT_ROOT/e2e-tests/runtime_dispatch.py" \
    --run --state-dir "$AW_RUN_DIR" --base-url "http://127.0.0.1:$RESOLVED_APP_PORT" \
    --storage-origin "http://127.0.0.1:$RESOLVED_MINIO_PORT"; then
    print_failure CHECK_RUNTIME RUNTIME_CHECK_FAILED "Real executor dispatch check failed"
    return 1
  fi
  if ! AW_E2E_APP_LOG="$AW_LOG_DIR/spring-boot-console.log" \
    AW_E2E_FILE_LOG="$AW_LOG_DIR/auto-wonder.log" \
    AW_E2E_APP_LOG_START_LINE="$app_start" \
    AW_E2E_FILE_LOG_START_LINE="$file_start" \
      "$AW_PROJECT_ROOT/e2e-tests/logscan.sh"; then
    print_failure CHECK_LOGS UNEXPECTED_DIAGNOSTICS "Unexpected or unattributed ERROR/WARN records were found"
    return 1
  fi
  write_log_checkpoint
  write_result PASS "" ""
  printf 'VERDICT=PASS\nRESULT_JSON=%s\n' "$AW_RESULT_JSON"
}

run_stop() {
  load_current
  aw_e2e_init_runtime
  aw_e2e_compose stop app mysql redis minio
  printf 'VERDICT=STOPPED\nRUN_STATE_DIR=%s\n' "$AW_RUN_DIR"
}

run_cleanup() {
  load_current
  local archive="$AW_PROJECT_ROOT/target/e2e-results/$AW_RUN_ID"
  local containers_left=0 volumes_left=0 network_present=no port port_listeners residue=0 report redacted
  local compose_attempted=0
  [[ -f "$AW_RUN_DIR/compose-attempted" ]] && compose_attempted=1
  aw_lifecycle_event START "operation=clean-up|projectRoot=$AW_PROJECT_ROOT|runId=$AW_RUN_ID"
  mkdir -p "$archive/logs"
  [[ -f "$AW_RUNTIME_JSON" ]] && cp "$AW_RUNTIME_JSON" "$archive/runtime.json"
  [[ -f "$AW_RESULT_JSON" ]] && cp "$AW_RESULT_JSON" "$archive/result.json"
  [[ -f "$AW_RUN_DIR/failure-report.txt" ]] && cp "$AW_RUN_DIR/failure-report.txt" "$archive/failure-report.txt"
  mkdir -p "$archive/checks/responses"
  for report in schema-verification.txt probes.txt authchain.txt log-scan-attributed.txt runtime-dispatch.json; do
    [[ -f "$AW_RUN_DIR/$report" ]] && cp "$AW_RUN_DIR/$report" "$archive/checks/$report"
  done
  for redacted in "$AW_RUN_DIR/authchain"/redacted-*.json; do
    [[ -f "$redacted" ]] && cp "$redacted" "$archive/checks/responses/"
  done
  for safe_log in bootstrap.log build.log image-build.log compose.log mysql.log redis.log minio.log spring-boot-console.log auto-wonder.log startup-diagnostic.log; do
    [[ -f "$AW_LOG_DIR/$safe_log" ]] && cp "$AW_LOG_DIR/$safe_log" "$archive/logs/$safe_log"
  done
  if [[ "$compose_attempted" == 1 ]]; then
    aw_step_event CLEANUP_DOCKER START "Removing owned Compose resources|log=$archive/logs/compose-down.log"
    if ! (aw_e2e_init_runtime) >>"$archive/logs/compose-down.log" 2>&1; then
      print_failure CLEANUP DOCKER_UNAVAILABLE "Docker is required because this run attempted to create Compose resources"
      aw_lifecycle_event END "FAIL|verdict=FAIL|phase=CLEANUP|failureKind=DOCKER_UNAVAILABLE"
      return 1
    fi
    aw_e2e_init_runtime >>"$archive/logs/compose-down.log" 2>&1
    capture_dependency_logs
    if ! aw_e2e_compose down -v --remove-orphans >>"$archive/logs/compose-down.log" 2>&1; then
      residue=1
    fi
    containers_left="$("$DOCKER" ps -aq --filter "label=com.docker.compose.project=${AW_E2E_PROJECT}" | wc -l | tr -d ' ')"
    volumes_left="$("$DOCKER" volume ls --format '{{.Name}}' | grep -c "^${AW_E2E_PROJECT}_" || true)"
    network_present="$("$DOCKER" network inspect "$AW_E2E_NETWORK" >/dev/null 2>&1 && printf yes || printf no)"
    aw_step_event CLEANUP_DOCKER PASS "containers=$containers_left|volumes=$volumes_left|network=$network_present"
  else
    aw_step_event CLEANUP_STATE PASS "No Compose resources were attempted; Docker is not required"
  fi
  {
    printf 'CLEANUP_MODE=%s\n' "$([[ "$compose_attempted" == 1 ]] && printf COMPOSE || printf STATE_ONLY)"
    printf 'CONTAINERS_LEFT=%s\n' "$containers_left"
    printf 'VOLUMES_LEFT=%s\n' "$volumes_left"
    printf 'NETWORK_PRESENT=%s\n' "$network_present"
    if [[ "$compose_attempted" == 1 ]]; then
      for port in "$RESOLVED_MYSQL_PORT" "$RESOLVED_REDIS_PORT" "$RESOLVED_MINIO_PORT" "$RESOLVED_MINIO_CONSOLE_PORT" "$RESOLVED_APP_PORT"; do
        port_listeners="$(aw_e2e_port_listeners "$port")"
        printf 'PORT_%s_LISTENERS=%s\n' "$port" "$port_listeners"
        [[ "$port_listeners" == "0" ]] || residue=1
      done
    fi
  } >"$archive/teardown.txt"
  cat "$archive/teardown.txt"
  [[ "$containers_left" == "0" ]] || residue=1
  [[ "$volumes_left" == "0" ]] || residue=1
  [[ "$network_present" == "no" ]] || residue=1
  if [[ "$residue" != "0" ]]; then
    print_failure CLEANUP RESIDUE_DETECTED "Owned container, volume, network, or port residue remains"
    return 1
  fi
  rm -rf "$AW_RUN_DIR"
  rm -f "$AW_CURRENT_RUN_FILE"
  printf 'VERDICT=CLEANED\nEVIDENCE_ARCHIVE=%s\n' "$archive"
  aw_lifecycle_event END "PASS|verdict=CLEANED|evidenceArchive=$archive"
}

case "$operation" in
  start) run_start ;;
  status) run_status ;;
  logs) run_logs ;;
  check) run_check ;;
  stop) run_stop ;;
  clean-up) run_cleanup ;;
esac
