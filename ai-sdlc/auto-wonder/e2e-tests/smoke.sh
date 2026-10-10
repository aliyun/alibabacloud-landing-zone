#!/usr/bin/env bash
# Start the built community server against the dependencies up.sh created, and
# prove that a brand-new install actually serves traffic.
#
#   e2e-tests/smoke.sh
#
# Unit tests being green is not the same as the application starting. This script
# starts the real artefact, waits for the real health endpoint, and probes the
# public capability endpoints that carry the community-specific defaults.
#
# Two failure modes are designed out rather than hoped away:
#
#   * Environment values containing `&`. The JDBC URL in
#     docs/community/application.env.example contains four of them. Sourcing such
#     a file with `.` lets the shell treat `&` as "run in background", silently
#     truncating the URL; the server then fails deep inside datasource or cache
#     initialisation and looks like a product defect. This script exports each
#     line as a single quoted word, which cannot be re-parsed, and it prints a
#     side-by-side demonstration of the truncation so the trap is visible rather
#     than folklore.
#   * Storage. The community compose file intentionally ships only MySQL and
#     Redis, but object storage has no in-memory fallback - the task packager is
#     wired unconditionally - so a fresh install cannot start without a bucket.
#     MinIO is an explicitly supported S3 backend (see the s3: block in
#     application.yml), so this script points the server at the MinIO that
#     compose.e2e.yml added and at the bucket up.sh pre-created.
#
# The server is left running for authchain.sh. Set AW_E2E_SMOKE_STOP=1 to stop it
# at the end, or run down.sh, which stops it too.

set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
# shellcheck source=lib/common.sh
source e2e-tests/lib/common.sh

aw_e2e_init_runtime
aw_e2e_prepare_state_dir
if ! aw_e2e_load_resolved; then
  die "no resolved.env - run e2e-tests/up.sh first"
fi

run_dir="$AW_E2E_STATE_DIR/run"
mkdir -p "$run_dir"
AW_E2E_LOG_DIR="${AW_E2E_LOG_DIR:-$run_dir/logs}"
mkdir -p "$AW_E2E_LOG_DIR"
app_log="$AW_E2E_LOG_DIR/spring-boot-console.log"
file_log="$AW_E2E_LOG_DIR/auto-wonder.log"
run_env="${AW_E2E_RUNTIME_ENV:-$AW_E2E_STATE_DIR/app.env}"

jar="${AW_E2E_JAR:-$REPO_ROOT/target/auto-wonder.jar}"
[[ -f "$jar" ]] || die "artefact not found: $jar (build it first, or set AW_E2E_JAR)"

RESOLVED_APP_PORT="${RESOLVED_APP_PORT:-$AW_E2E_APP_PORT}"
RESOLVED_MINIO_PORT="${RESOLVED_MINIO_PORT:-$AW_E2E_MINIO_PORT}"
base_url="http://127.0.0.1:${RESOLVED_APP_PORT}"
log "artefact=$jar ($(wc -c <"$jar" | tr -d ' ') bytes)"
log "base_url=$base_url mysql=127.0.0.1:$RESOLVED_MYSQL_PORT redis=127.0.0.1:$RESOLVED_REDIS_PORT minio=127.0.0.1:$RESOLVED_MINIO_PORT"

# --- 1. the quoting trap, demonstrated --------------------------------------
# Run in a subshell so the deliberately broken export cannot affect this script.
jdbc_full='jdbc:mysql://127.0.0.1:3306/autowonder?useUnicode=true&characterEncoding=utf-8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai'
trap_demo="$AW_E2E_STATE_DIR/env-quoting-trap.txt"
trap_unquoted="$AW_E2E_STATE_DIR/.trap-unquoted.env"
trap_quoted="$AW_E2E_STATE_DIR/.trap-quoted.env"
probe_report="$AW_E2E_STATE_DIR/probes.txt"
{
  echo "# why the env file is exported line-by-line instead of sourced"
  echo "FULL_LENGTH=${#jdbc_full}"
  echo "FULL_VALUE=$jdbc_full"
  ( printf 'SPRING_DATASOURCE_URL=%s\n' "$jdbc_full" >"$trap_unquoted"
    set +e
    # shellcheck disable=SC1091
    . "$trap_unquoted" 2>/dev/null
    # Measured behaviour, and it is worse than truncation: each `&` makes the
    # assignment before it a *background subshell*, so the variable is never set
    # in the sourcing shell at all. Only the final fragment, which has no `&`
    # after it, survives - as its own unrelated variable. Test for set-ness with
    # ${VAR+x}; expanding the name directly aborts under `set -u`.
    if [[ -z "${SPRING_DATASOURCE_URL+x}" ]]; then
      echo "SOURCED_UNQUOTED_SET=no"
      echo "SOURCED_UNQUOTED_LENGTH=0"
    else
      echo "SOURCED_UNQUOTED_SET=yes"
      echo "SOURCED_UNQUOTED_LENGTH=${#SPRING_DATASOURCE_URL}"
      echo "SOURCED_UNQUOTED_VALUE=$SPRING_DATASOURCE_URL"
    fi
    echo "LEAKED_FRAGMENT_serverTimezone=${serverTimezone-<unset>}"
    echo "LEAKED_FRAGMENT_useSSL=${useSSL-<unset>}"
    wait 2>/dev/null
    true
  )
  ( printf "SPRING_DATASOURCE_URL='%s'\n" "$jdbc_full" >"$trap_quoted"
    # shellcheck disable=SC1091
    . "$trap_quoted"
    echo "SOURCED_QUOTED_SET=$([[ -n "${SPRING_DATASOURCE_URL+x}" ]] && echo yes || echo no)"
    echo "SOURCED_QUOTED_LENGTH=${#SPRING_DATASOURCE_URL}"
    echo "SOURCED_QUOTED_MATCHES_FULL=$([[ "$SPRING_DATASOURCE_URL" == "$jdbc_full" ]] && echo yes || echo no)"
  )
  ( line="SPRING_DATASOURCE_URL=$jdbc_full"
    export "$line"
    echo "EXPORT_LINE_LENGTH=${#SPRING_DATASOURCE_URL}"
    echo "EXPORT_LINE_MATCHES_FULL=$([[ "$SPRING_DATASOURCE_URL" == "$jdbc_full" ]] && echo yes || echo no)"
  )
} >"$trap_demo" 2>&1
rm -f "$trap_unquoted" "$trap_quoted"
cat "$trap_demo"
grep -q 'EXPORT_LINE_MATCHES_FULL=yes' "$trap_demo" \
  || die "the line-wise export did not preserve the JDBC URL; refusing to start with a possibly truncated value"

# --- 2. the run environment --------------------------------------------------
# Community-specific defaults are deliberately NOT set here: community-edition,
# the recommended runtime version, the Aone switch, the SIGAR switch and the four
# scheduled-task switches keep their shipped defaults so the probes below report
# what a real fresh install reports, not what this script asked for.
version_from_file="$(cat "$REPO_ROOT/VERSION" | tr -d '[:space:]')"
# Idempotent: reuses the secrets up.sh already generated for this project, and
# creates them if smoke.sh is run against a state directory that lacks them.
aw_e2e_generate_secrets
umask 077
{
  printf 'SPRING_PROFILES_ACTIVE=local\n'
  printf 'SERVER_PORT=7001\n'
  printf 'SPRING_DATASOURCE_URL=jdbc:mysql://mysql:3306/autowonder?useUnicode=true&characterEncoding=utf-8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai\n'
  printf 'SPRING_DATASOURCE_USERNAME=autowonder\n'
  printf 'SPRING_DATASOURCE_PASSWORD=change-me\n'
  printf 'REDIS_HOST=redis\n'
  printf 'REDIS_PORT=6379\n'
  printf 'AUTOWONDER_SECRET_MASTER_KEY=%s\n' "$(aw_e2e_secret AUTOWONDER_SECRET_MASTER_KEY)"
  printf 'AUTOWONDER_JWT_SECRET=%s\n' "$(aw_e2e_secret AUTOWONDER_JWT_SECRET)"
  printf 'AUTOWONDER_PUBLIC_BASE_URL=%s\n' "$base_url"
  printf 'AUTOWONDER_VERSION=%s\n' "$version_from_file"
  # Object storage: S3-compatible MinIO instead of OSS. The two are mutually
  # exclusive; bucket names are shared and live under OSS_BUCKET.
  printf 'OSS_ENABLED=false\n'
  printf 'S3_ENABLED=true\n'
  printf 'S3_ENDPOINT=http://minio:9000\n'
  printf 'S3_PUBLIC_ENDPOINT=http://127.0.0.1:%s\n' "$RESOLVED_MINIO_PORT"
  printf 'S3_REGION=us-east-1\n'
  printf 'S3_ACCESS_KEY_ID=%s\n' "$AW_E2E_MINIO_ROOT_USER"
  printf 'S3_ACCESS_KEY_SECRET=%s\n' "$(aw_e2e_secret AW_E2E_MINIO_ROOT_PASSWORD)"
  printf 'OSS_BUCKET=%s\n' "$AW_E2E_BUCKET"
  if [[ "${AW_E2E_WITH_SLS:-0}" == 1 ]]; then
    printf 'AUTOWONDER_SLS_ENABLED=true\n'
    printf 'SLS_ENDPOINT=http://sls-fixture.local\nSLS_PROJECT=e2e\n'
    printf 'SLS_SYS_LOGSTORE=e2e-system\nSLS_BIZ_LOGSTORE=e2e-business\nSLS_METRIC_LOGSTORE=e2e-metrics\n'
    printf 'SLS_ACCESS_KEY_ID=e2e-dummy\nSLS_ACCESS_KEY_SECRET=e2e-dummy-secret\n'
  else
    printf 'AUTOWONDER_SLS_ENABLED=false\n'
  fi
} >"$run_env"
chmod 600 "$run_env"
umask 022
log "wrote run environment ($(wc -l <"$run_env" | tr -d ' ') keys, mode 600, values not echoed)"

# Export line by line: each line is already one shell word, so `&` inside the
# JDBC URL cannot become a control operator.
set -a
while IFS= read -r env_line; do
  [[ -n "$env_line" ]] && export "$env_line"
done <"$run_env"
set +a
log "exported container JDBC URL length=${#SPRING_DATASOURCE_URL}"

# --- 3. start ----------------------------------------------------------------
AW_E2E_APP_IMAGE="${AW_E2E_APP_IMAGE:?set AW_E2E_APP_IMAGE to the image built by verify.sh}"
AW_E2E_RUNTIME_ENV="$run_env"
export AW_E2E_APP_IMAGE AW_E2E_RUNTIME_ENV AW_E2E_LOG_DIR RESOLVED_APP_PORT
if [[ "${AW_E2E_WITH_SLS:-0}" == 1 ]]; then
  aw_e2e_ensure_image python:3.12-alpine - "$(aw_e2e_official_mirror python:3.12-alpine)"
  aw_e2e_compose up -d --wait sls >>"$AW_E2E_STATE_DIR/compose-up.log" 2>&1 \
    || die "SLS protocol fixture failed to start"
fi
log "starting compose-owned server image=$AW_E2E_APP_IMAGE log=$app_log"
if ! aw_e2e_compose up -d app >>"$AW_E2E_STATE_DIR/compose-up.log" 2>&1; then
  tail -60 "$AW_E2E_STATE_DIR/compose-up.log"
  die "compose application start failed"
fi
app_container_id="$(aw_e2e_compose ps -q app)"
[[ -n "$app_container_id" ]] || die "compose returned no application container id"
log "container=$app_container_id log=$app_log"

# --- 4. wait for health ------------------------------------------------------
health_url="$base_url/checkpreload.htm"
ready=0
startup_timeout="${AW_E2E_STARTUP_TIMEOUT:-180}"
for i in $(seq 1 "$startup_timeout"); do
  if [[ "$("$DOCKER" inspect -f '{{.State.Running}}' "$app_container_id" 2>/dev/null || true)" != "true" ]]; then
    log "application container exited during startup"
    break
  fi
  code="$(curl -s -o "$AW_E2E_STATE_DIR/health.body" -w '%{http_code}' "$health_url" || true)"
  if [[ "$code" == "200" ]]; then
    ready=1
    log "healthy after ${i}s: GET $health_url -> 200 $(cat "$AW_E2E_STATE_DIR/health.body")"
    break
  fi
  if (( i % 10 == 0 )); then
    printf 'PROGRESS|APPLICATION_START|elapsed=%ss|container=running|health=%s\n' "$i" "$code"
  fi
  sleep 1
done

if [[ "$ready" != "1" ]]; then
  diagnostic_log="${AW_STARTUP_DIAGNOSTIC_LOG:-$AW_E2E_LOG_DIR/startup-diagnostic.log}"
  startup_failure_kind=STARTUP_TIMEOUT
  if [[ "$("$DOCKER" inspect -f '{{.State.Running}}' "$app_container_id" 2>/dev/null || true)" != "true" ]]; then
    startup_failure_kind=APPLICATION_EXITED
  fi
  printf 'STARTUP_FAILURE_KIND=%s\n' "$startup_failure_kind" >"$AW_E2E_STATE_DIR/startup-status.env"
  {
    echo "APPLICATION_CONTAINER=$app_container_id"
    "$DOCKER" inspect "$app_container_id" 2>&1 || true
    "$DOCKER" stats --no-stream "$app_container_id" 2>&1 || true
    aw_e2e_compose ps 2>&1 || true
  } >"$diagnostic_log"
  if [[ "$("$DOCKER" inspect -f '{{.State.Running}}' "$app_container_id" 2>/dev/null || true)" == "true" ]]; then
    "$DOCKER" kill --signal=QUIT "$app_container_id" >/dev/null 2>&1 || true
    sleep 1
  fi
  log "server did not become healthy; last 60 log lines follow"
  tail -60 "$app_log"
  die "health check failed for $health_url (logs=$app_log diagnostics=$diagnostic_log)"
fi

# --- 5. public capability probes --------------------------------------------
probe() {
  local name="$1" path="$2" out
  out="$AW_E2E_STATE_DIR/probe-${name}.json"
  local code
  code="$(curl -s -o "$out" -w '%{http_code}' "$base_url$path" || true)"
  echo "PROBE_${name}_HTTP=$code"
  echo "PROBE_${name}_PATH=$path"
  echo "PROBE_${name}_BODY=$(head -c 1200 "$out")"
}

{
  echo "# public endpoint probes on a fresh install"
  echo "ARTEFACT=$jar"
  echo "ARTEFACT_BYTES=$(wc -c <"$jar" | tr -d ' ')"
  echo "VERSION_FILE=$version_from_file"
  echo "HEALTH_HTTP=200"
  echo "HEALTH_BODY=$(cat "$AW_E2E_STATE_DIR/health.body")"
  probe capabilities /api/integrations/capabilities
  probe branding /api/platform/branding/public
  probe scheduled_task_capability /api/capabilities/scheduled-task
} | tee "$probe_report"

aw_require_equal probe.capabilities_http 200 "$(aw_report_value "$probe_report" PROBE_capabilities_HTTP)"
aw_json_require "$AW_E2E_STATE_DIR/probe-capabilities.json" success true
aw_json_require "$AW_E2E_STATE_DIR/probe-capabilities.json" data.aoneEnabled false
aw_require_equal probe.branding_http 200 "$(aw_report_value "$probe_report" PROBE_branding_HTTP)"
aw_json_require "$AW_E2E_STATE_DIR/probe-branding.json" success true
aw_json_require "$AW_E2E_STATE_DIR/probe-branding.json" data.communityEdition true
aw_json_require "$AW_E2E_STATE_DIR/probe-branding.json" data.deploymentVersion "$version_from_file"
aw_require_equal probe.scheduled_task_unauthenticated_http 401 \
  "$(aw_report_value "$probe_report" PROBE_scheduled_task_capability_HTTP)"

# --- 6. log scan -------------------------------------------------------------
scan_logs() {
  local label="$1" file="$2"
  [[ -f "$file" ]] || { echo "LOG_${label}_PRESENT=no"; return; }
  echo "LOG_${label}_PRESENT=yes"
  echo "LOG_${label}_LINES=$(wc -l <"$file" | tr -d ' ')"
  echo "LOG_${label}_ERROR_COUNT=$(grep -c ' ERROR ' "$file" || true)"
  echo "LOG_${label}_WARN_COUNT=$(grep -c ' WARN ' "$file" || true)"
  echo "LOG_${label}_EXCEPTION_COUNT=$(grep -cE '^[[:space:]]*(at |[A-Za-z0-9_.$]+(Exception|Error):)' "$file" || true)"
}
{
  echo "# log scan at healthy state"
  scan_logs stdout "$app_log"
  scan_logs file "$file_log"
  echo "# --- every ERROR line ---"
  grep -n ' ERROR ' "$app_log" 2>/dev/null | head -40 || echo "(none)"
  echo "# --- every WARN line ---"
  grep -n ' WARN ' "$app_log" 2>/dev/null | head -60 || echo "(none)"
  echo "# --- startup banner ---"
  grep -nE 'Started .*Application|Tomcat started|JVM running' "$app_log" 2>/dev/null | head -5 || echo "(not found)"
} | tee "$AW_E2E_STATE_DIR/log-scan.txt"

if [[ "${AW_E2E_SMOKE_STOP:-0}" == "1" ]]; then
  log "AW_E2E_SMOKE_STOP=1: stopping compose application"
  aw_e2e_compose stop app >/dev/null
else
  log "server left running (container=$app_container_id) for Agent checks"
fi
log "smoke.sh done"
