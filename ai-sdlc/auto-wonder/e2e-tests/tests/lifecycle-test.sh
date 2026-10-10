#!/usr/bin/env bash

set -euo pipefail

TEST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
E2E_DIR="$(cd "$TEST_DIR/.." && pwd)"
# shellcheck source=../lib/lifecycle.sh
source "$E2E_DIR/lib/lifecycle.sh"

fail() {
  printf 'FAIL %s\n' "$*" >&2
  exit 1
}

assert_file() {
  [[ -f "$1" ]] || fail "expected file: $1"
}

assert_equal() {
  [[ "$1" == "$2" ]] || fail "expected '$1' to equal '$2'"
}

assert_not_equal() {
  [[ "$1" != "$2" ]] || fail "expected '$1' to differ from '$2'"
}

assert_contains() {
  local path="$1" expected="$2"
  grep -Fq -- "$expected" "$path" || fail "expected $path to contain: $expected"
}

run_expect_success() {
  "$@" >/dev/null 2>&1 || fail "expected command success: $*"
}

run_expect_failure() {
  if "$@" >/dev/null 2>&1; then
    fail "expected command failure: $*"
  fi
}

assert_mode() {
  local expected="$1" path="$2" actual
  if stat -f '%Lp' "$path" >/dev/null 2>&1; then
    actual="$(stat -f '%Lp' "$path")"
  else
    actual="$(stat -c '%a' "$path")"
  fi
  assert_equal "$expected" "$actual"
}

test_state() {
  local sandbox fixture_root_a fixture_root_b first_namespace
  sandbox="$(mktemp -d "${TMPDIR:-/tmp}/aw-lifecycle-test.XXXXXX")"
  trap "rm -rf '$sandbox'" EXIT
  fixture_root_a="$sandbox/project-a"
  fixture_root_b="$sandbox/project-b"
  mkdir -p "$fixture_root_a" "$fixture_root_b"
  git -C "$fixture_root_a" init -q
  git -C "$fixture_root_b" init -q

  # shellcheck source=../lib/lifecycle.sh
  source "$E2E_DIR/lib/lifecycle.sh"
  AW_E2E_TEST_STATE_BASE="$sandbox/state"
  export AW_E2E_TEST_STATE_BASE

  aw_lifecycle_init "$fixture_root_a"
  for name in bootstrap build image-build compose mysql redis minio spring-boot-console auto-wonder startup-diagnostic; do
    assert_file "$AW_LOG_DIR/$name.log"
  done
  assert_file "$AW_RUNTIME_ENV"
  assert_file "$AW_APP_ENV"
  assert_file "$AW_RUN_DIR/failure-report.txt"
  assert_mode 600 "$AW_RUNTIME_ENV"
  assert_mode 600 "$AW_APP_ENV"
  first_namespace="$AW_PROJECT_STATE_ROOT"

  if aw_lifecycle_refuse_active "$fixture_root_a" >/dev/null 2>&1; then
    fail "active lifecycle was not rejected"
  fi
  aw_lifecycle_load "$fixture_root_a"
  assert_equal "$first_namespace" "$AW_PROJECT_STATE_ROOT"

  aw_lifecycle_init "$fixture_root_b"
  assert_not_equal "$first_namespace" "$AW_PROJECT_STATE_ROOT"
  printf 'PASS lifecycle state\n'
}

test_events() {
  local actual expected
  actual="$(
    aw_lifecycle_event START 'operation=start|projectRoot=/tmp/project'
    aw_step_event BUILD START 'Running Maven clean verify|log=/tmp/build.log'
    aw_step_event BUILD PASS 'duration=12s'
    aw_lifecycle_event END 'PASS|verdict=STARTED|platformUrl=http://127.0.0.1:7001'
  )"
  expected="$(printf '%s\n' \
    'LIFECYCLE|START|operation=start|projectRoot=/tmp/project' \
    'STEP|BUILD|START|Running Maven clean verify|log=/tmp/build.log' \
    'STEP|BUILD|PASS|duration=12s' \
    'LIFECYCLE|END|PASS|verdict=STARTED|platformUrl=http://127.0.0.1:7001')"
  assert_equal "$expected" "$actual"
  assert_contains "$E2E_DIR/lib/lifecycle.sh" 'BOOTSTRAP_LOG=%s/bootstrap.log'
  printf 'PASS lifecycle events\n'
}

test_ports() {
  local sandbox project_root derived known_free unique_count
  sandbox="$(mktemp -d "${TMPDIR:-/tmp}/aw-ports-test.XXXXXX")"
  trap "rm -rf '$sandbox'" EXIT
  project_root="$(cd "$E2E_DIR/.." && pwd -P)"

  # shellcheck source=../lib/common.sh
  source "$E2E_DIR/lib/common.sh"
  REPO_ROOT="$project_root"
  AW_E2E_STATE_DIR="$sandbox/state"
  AW_E2E_BASE_COMPOSE="$project_root/docs/community/docker-compose.dependencies.yml"
  AW_E2E_LAYER_COMPOSE="$project_root/e2e-tests/compose.e2e.yml"
  AW_E2E_COMPOSE_ARGS=()
  mkdir -p "$AW_E2E_STATE_DIR"
  known_free="$(aw_e2e_first_free_port 45900)"
  assert_equal 0 "$(aw_e2e_port_listeners "$known_free")"
  aw_e2e_port_listeners() {
    case "$1" in
      33060|63790|9000|9001|7001) printf '1' ;;
      *) printf '0' ;;
    esac
  }

  aw_e2e_resolve_compose
  derived="$AW_E2E_STATE_DIR/dependencies.derived.yml"
  assert_file "$derived"
  assert_contains "$derived" "$project_root/docs/autowonder-schema.sql:/docker-entrypoint-initdb.d/001-autowonder-schema.sql:ro"
  assert_not_equal 33060 "$RESOLVED_MYSQL_PORT"
  assert_not_equal 63790 "$RESOLVED_REDIS_PORT"
  assert_not_equal 9000 "${RESOLVED_MINIO_PORT:-9000}"
  assert_not_equal 9001 "${RESOLVED_MINIO_CONSOLE_PORT:-9001}"
  assert_not_equal 7001 "${RESOLVED_APP_PORT:-7001}"
  AW_E2E_MINIO_PORT=19000
  assert_equal 9000 "$(aw_e2e_minio_internal_port)"

  AW_E2E_COMPOSE_ARGS=()
  AW_E2E_MYSQL_PORT=45000
  AW_E2E_REDIS_PORT=45000
  AW_E2E_MINIO_PORT=45000
  AW_E2E_MINIO_CONSOLE_PORT=45000
  AW_E2E_APP_PORT=45000
  aw_e2e_port_listeners() { printf '0'; }
  aw_e2e_resolve_compose
  unique_count="$(printf '%s\n' "$RESOLVED_MYSQL_PORT" "$RESOLVED_REDIS_PORT" \
    "$RESOLVED_MINIO_PORT" "$RESOLVED_MINIO_CONSOLE_PORT" "$RESOLVED_APP_PORT" | sort -u | wc -l | tr -d ' ')"
  assert_equal 5 "$unique_count"
  printf 'PASS lifecycle ports\n'
}

test_verdicts() {
  local sandbox json_file report_file scan_state
  sandbox="$(mktemp -d "${TMPDIR:-/tmp}/aw-verdict-test.XXXXXX")"
  trap "rm -rf '$sandbox'" EXIT
  # shellcheck source=../lib/lifecycle.sh
  source "$E2E_DIR/lib/lifecycle.sh"

  aw_require_equal schema.tables 65 65
  if aw_require_equal schema.tables 65 64 >/dev/null 2>&1; then
    fail "mismatched schema assertion returned success"
  fi
  aw_require_int_ge schema.tables 65 66
  if aw_require_int_ge schema.tables 65 64 >/dev/null 2>&1; then
    fail "schema minimum assertion returned success below its minimum"
  fi

  json_file="$sandbox/response.json"
  printf '%s\n' '{"success":true,"data":{"communityEdition":true,"version":"0.8.0"}}' >"$json_file"
  aw_json_require "$json_file" success true
  aw_json_require "$json_file" data.communityEdition true
  if aw_json_require "$json_file" data.version 0.9.0 >/dev/null 2>&1; then
    fail "mismatched JSON assertion returned success"
  fi

  report_file="$sandbox/report.txt"
  printf 'PASS_COUNT=3\nFAIL_COUNT=0\n' >"$report_file"
  aw_report_require_zero "$report_file" FAIL_COUNT
  printf 'PASS_COUNT=2\nFAIL_COUNT=1\n' >"$report_file"
  if aw_report_require_zero "$report_file" FAIL_COUNT >/dev/null 2>&1; then
    fail "nonzero report failure count returned success"
  fi
  assert_contains "$E2E_DIR/lib/common.sh" 'source "$E2E_DIR/lib/lifecycle.sh"'
  assert_contains "$E2E_DIR/up.sh" 'aw_require_int_ge schema.table_count'
  assert_contains "$E2E_DIR/smoke.sh" 'aw_json_require "$AW_E2E_STATE_DIR/probe-branding.json" data.communityEdition true'
  assert_contains "$E2E_DIR/authchain.sh" 'aw_report_require_zero "$REPORT" FAIL_COUNT'
  assert_contains "$E2E_DIR/logscan.sh" 'sys.exit(0 if ok else 1)'

  scan_state="$sandbox/scan-state"
  mkdir -p "$scan_state/authchain"
  printf '%s\n' '2026-09-08 00:00:00,000|ERROR|||Test|main|old error' \
    '2026-09-08 00:00:01,000|INFO|||Test|main|new clean line' >"$scan_state/app.log"
  : >"$scan_state/file.log"
  run_expect_failure env AW_E2E_STATE_DIR="$scan_state" \
    AW_E2E_APP_LOG="$scan_state/app.log" AW_E2E_FILE_LOG="$scan_state/file.log" \
    "$E2E_DIR/logscan.sh"
  run_expect_success env AW_E2E_STATE_DIR="$scan_state" \
    AW_E2E_APP_LOG="$scan_state/app.log" AW_E2E_FILE_LOG="$scan_state/file.log" \
    AW_E2E_APP_LOG_START_LINE=1 "$E2E_DIR/logscan.sh"
  printf 'PASS truthful verdicts\n'
}

test_compose() {
  local compose_file="$E2E_DIR/compose.e2e.yml"
  assert_contains "$compose_file" '  app:'
  assert_contains "$compose_file" 'image: ${AW_E2E_APP_IMAGE:?built by verify.sh}'
  assert_contains "$compose_file" 'jdbc:mysql://mysql:3306/autowonder'
  assert_contains "$compose_file" 'REDIS_HOST: redis'
  assert_contains "$compose_file" 'S3_ENDPOINT: http://minio:9000'
  assert_contains "$compose_file" '${AW_E2E_LOG_DIR:?created by verify.sh}:/app/logs'
  assert_contains "$compose_file" 'spring-boot-console.log'
  assert_contains "$E2E_DIR/smoke.sh" 'aw_e2e_compose up -d app'
  assert_contains "$E2E_DIR/up.sh" 'aw_e2e_compose up -d --wait mysql redis minio'
  if grep -Fq 'exec java -jar "$jar"' "$E2E_DIR/smoke.sh"; then
    fail "smoke.sh still owns a host background JVM"
  fi
  printf 'PASS compose application contract\n'
}

test_cli() {
  local verify="$E2E_DIR/verify.sh" project_root sandbox fixture fake_bin state_root run_id output
  project_root="$(cd "$E2E_DIR/.." && pwd -P)"
  run_expect_success "$verify" --help
  run_expect_failure "$verify" --start
  run_expect_failure "$verify" --start --stop --project-root "$project_root"
  assert_contains "$verify" '--project-root'
  assert_contains "$verify" '--keep-on-failure'
  assert_contains "$verify" '--startup-timeout'
  assert_contains "$verify" '--no-install'
  assert_contains "$verify" 'source "$SCRIPT_DIR/lib/bootstrap.sh"'
  assert_contains "$verify" 'compose-attempted'
  assert_contains "$verify" 'aw_lifecycle_event START'
  assert_contains "$verify" 'aw_lifecycle_event END'
  assert_contains "$verify" 'run_logged_phase DEPENDENCIES'
  assert_contains "$verify" 'run_logged_phase APPLICATION_START'
  assert_contains "$verify" 'print_failure CHECK_HEALTH'
  assert_contains "$verify" 'print_failure CHECK_AUTHENTICATED'
  assert_contains "$verify" 'print_failure CHECK_LOGS'
  assert_contains "$verify" 'log-checkpoint.env'
  assert_contains "$verify" 'redacted-*.json'
  assert_contains "$verify" 'FAILURE_RESOURCES=PRESERVED'
  assert_contains "$verify" 'FAILURE_RESOURCES=REMOVED'
  assert_contains "$E2E_DIR/smoke.sh" 'startup_failure_kind=APPLICATION_EXITED'
  assert_contains "$E2E_DIR/smoke.sh" 'STARTUP_FAILURE_KIND=%s'
  assert_contains "$E2E_DIR/authchain.sh" 'RUN_SUFFIX="${E2E_USER##*-}"'
  assert_contains "$E2E_DIR/authchain.sh" 'AW E2E Smoke Workspace $RUN_SUFFIX'
  assert_contains "$E2E_DIR/authchain.sh" 'AW E2E Dup Name Check $RUN_SUFFIX'
  if sed -n '/run_stop()/,/^}/p' "$verify" | grep -Fq 'write_result'; then
    fail "stop operation overwrites the verification verdict"
  fi
  if grep -Fq "pgrep -f 'auto-wonder\\.jar'" "$E2E_DIR/down.sh"; then
    fail "down.sh still contains broad JVM process matching"
  fi

  sandbox="$(mktemp -d "${TMPDIR:-/tmp}/aw-cli-test.XXXXXX")"
  fixture="$sandbox/project"
  fake_bin="$sandbox/bin"
  mkdir -p "$fixture" "$fake_bin"
  git -C "$fixture" init -q
  printf '#!/usr/bin/env bash\n[[ "${1:-}" == --version ]] && { printf "Apache Maven 3.9.9\\n"; exit 0; }; exit 7\n' >"$fake_bin/mvn"
  printf '%s\n' '#!/usr/bin/env bash' \
    'case "${1:-}" in' \
    '  --version) printf "Docker version 28.0.1\\n" ;;' \
    '  version) printf "fake server\\n" ;;' \
    '  compose) [[ "${2:-}" == version ]] && printf "Docker Compose version v2.35.0\\n" ;;' \
    '  buildx) [[ "${2:-}" == version ]] && printf "github.com/docker/buildx v0.23.0\\n" ;;' \
    '  *) exit 0 ;;' \
    'esac' >"$fake_bin/docker"
  chmod +x "$fake_bin/mvn" "$fake_bin/docker"
  if output="$(env AW_E2E_TEST_STATE_BASE="$sandbox/state" \
    AW_E2E_DOCKER="$fake_bin/docker" PATH="$fake_bin:$PATH" \
    "$verify" --start --project-root "$fixture" --keep-on-failure 2>&1)"; then
    fail "build failure fixture unexpectedly started"
  fi
  printf '%s\n' "$output" >"$sandbox/start-output.txt"
  assert_contains "$sandbox/start-output.txt" 'LIFECYCLE|START|operation=start'
  assert_contains "$sandbox/start-output.txt" 'STEP|BUILD|START|'
  assert_contains "$sandbox/start-output.txt" 'STEP|BUILD|FAIL|'
  assert_contains "$sandbox/start-output.txt" 'LIFECYCLE|END|FAIL|verdict=FAIL|phase=BUILD|failureKind=BUILD_FAILED'
  state_root="$sandbox/state/$(aw_project_hash "$(cd "$fixture" && pwd -P)")"
  run_id="$(sed -n '1p' "$state_root/current")"
  assert_contains "$state_root/$run_id/lifecycle.env" 'AW_E2E_WITH_SLS=0'
  assert_contains "$state_root/$run_id/result.json" '"verdict": "FAIL"'
  assert_contains "$state_root/$run_id/failure-report.txt" 'FAILED_PHASE=BUILD'
  run_expect_success env AW_E2E_TEST_STATE_BASE="$sandbox/state" \
    "$verify" --logs --project-root "$fixture"
  run_expect_success env AW_E2E_TEST_STATE_BASE="$sandbox/state" \
    PATH="$fake_bin:$PATH" "$verify" --clean-up --project-root "$fixture"
  [[ ! -f "$state_root/current" ]] || fail "state-only cleanup left current pointer"

  if output="$(env AW_E2E_TEST_STATE_BASE="$sandbox/state-unsupported" \
    AW_BOOTSTRAP_OS_OVERRIDE=Plan9 "$verify" --start --project-root "$fixture" \
      --keep-on-failure --no-install --with-sls 2>&1)"; then
    fail "unsupported host unexpectedly started"
  fi
  printf '%s\n' "$output" >"$sandbox/unsupported-output.txt"
  assert_contains "$sandbox/unsupported-output.txt" 'FAILED_PHASE=BOOTSTRAP'
  assert_contains "$sandbox/unsupported-output.txt" 'FAILURE_KIND=UNSUPPORTED_HOST'
  assert_contains "$sandbox/unsupported-output.txt" 'STEP|HOST_DETECTION|FAIL|failureKind=UNSUPPORTED_HOST'
  assert_contains "$sandbox/unsupported-output.txt" 'LIFECYCLE|END|FAIL'
  state_root="$sandbox/state-unsupported/$(aw_project_hash "$(cd "$fixture" && pwd -P)")"
  run_id="$(sed -n '1p' "$state_root/current")"
  assert_contains "$state_root/$run_id/lifecycle.env" 'AW_E2E_WITH_SLS=1'
  run_expect_success env AW_E2E_TEST_STATE_BASE="$sandbox/state-unsupported" \
    PATH="$fake_bin:$PATH" "$verify" --clean-up --project-root "$fixture"
  rm -rf "$sandbox"
  printf 'PASS verify lifecycle CLI\n'
}

test_agent_docs() {
  local sync_guide="$E2E_DIR/../docs/community/upstream-sync-guide.md"
  assert_file "$E2E_DIR/AGENT_GUIDE.md"
  assert_file "$E2E_DIR/../AGENTS.md"
  assert_file "$sync_guide"
  assert_contains "$E2E_DIR/AGENT_GUIDE.md" './e2e-tests/verify.sh --start --project-root'
  assert_contains "$E2E_DIR/AGENT_GUIDE.md" 'runtime.env'
  assert_contains "$E2E_DIR/AGENT_GUIDE.md" '0600'
  assert_contains "$E2E_DIR/AGENT_GUIDE.md" 'Never print, paste, or copy credential values'
  assert_contains "$E2E_DIR/AGENT_GUIDE.md" 'spring-boot-console.log'
  assert_contains "$E2E_DIR/AGENT_GUIDE.md" 'BOOTSTRAP_LOG'
  assert_contains "$E2E_DIR/AGENT_GUIDE.md" 'SUDO_UNAVAILABLE'
  assert_contains "$E2E_DIR/AGENT_GUIDE.md" 'STATE_ONLY'
  assert_contains "$E2E_DIR/README.md" 'MySQL, Redis, MinIO and the MinIO client are image references'
  assert_contains "$E2E_DIR/README.md" '--no-install'
  assert_contains "$E2E_DIR/AGENT_GUIDE.md" './e2e-tests/verify.sh --check --project-root'
  assert_contains "$E2E_DIR/../AGENTS.md" 'e2e-tests/AGENT_GUIDE.md'
  assert_contains "$sync_guide" 'Mandatory Agent E2E lifecycle'
  assert_contains "$sync_guide" './e2e-tests/verify.sh --start --project-root "$(pwd -P)" --mode image --keep-on-failure'
  assert_contains "$sync_guide" './e2e-tests/verify.sh --status --project-root "$(pwd -P)"'
  assert_contains "$sync_guide" './e2e-tests/verify.sh --check --project-root "$(pwd -P)"'
  assert_contains "$sync_guide" './e2e-tests/verify.sh --stop --project-root "$(pwd -P)"'
  assert_contains "$sync_guide" './e2e-tests/verify.sh --clean-up --project-root "$(pwd -P)"'
  assert_contains "$sync_guide" 'runtime.json'
  assert_contains "$sync_guide" 'runtime.env'
  assert_contains "$sync_guide" 'mode `0600`'
  assert_contains "$sync_guide" 'BOOTSTRAP_LOG'
  assert_contains "$sync_guide" 'SPRING_BOOT_CONSOLE_LOG'
  assert_contains "$sync_guide" 'schema-verification.txt'
  assert_contains "$sync_guide" 'authchain.txt'
  assert_contains "$sync_guide" 'log-scan-attributed.txt'
  assert_contains "$sync_guide" 'VERDICT=PASS'
  printf 'PASS agent documentation contract\n'
}

case "${1:-all}" in
  state) test_state ;;
  events) test_events ;;
  ports) test_ports ;;
  verdicts) test_verdicts ;;
  compose) test_compose ;;
  cli) test_cli ;;
  docs) test_agent_docs ;;
  all) test_state; test_events; test_ports; test_verdicts; test_compose; test_cli; test_agent_docs ;;
  *) fail "unknown test group: $1" ;;
esac
