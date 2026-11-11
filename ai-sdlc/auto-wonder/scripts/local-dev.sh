#!/usr/bin/env bash
# Persistent development service. Stop never removes containers, volumes or credentials.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export AW_E2E_TEST_STATE_BASE="${AW_LOCAL_STATE_DIR:-$HOME/workspace/auto-wonder-local-data}"
export AW_E2E_LOCAL_S3=1
export PATH="/Applications/Docker.app/Contents/Resources/bin:$PATH"
action="${1:-status}"
if [[ "$action" == init ]]; then
  exec bash "$root/e2e-tests/verify.sh" --start --project-root "$root" --keep-on-failure --startup-timeout 600
fi
source "$root/e2e-tests/lib/lifecycle.sh"
aw_lifecycle_load "$root" ""
set -a
source "$AW_RUN_DIR/lifecycle.env"
set +a
export REPO_ROOT="$root"
source "$root/e2e-tests/lib/common.sh"
aw_e2e_init_runtime
case "$action" in
  start)
    aw_e2e_start_dependencies
    aw_e2e_storage_start
    count="$(aw_e2e_compose exec -T mysql sh -ec 'MYSQL_PWD="$(cat /run/e2e-secrets/mysql-password)" exec mysql -N -B -uroot autowonderdev -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()"')"
    # Bootstrap only an empty database. Existing data is updated by application migrations.
    if [[ "$count" == 0 ]]; then
      aw_e2e_compose exec -T mysql sh -ec 'MYSQL_PWD="$(cat /run/e2e-secrets/mysql-password)" exec mysql -uroot autowonderdev' < "$root/docs/autowonder-schema.sql"
      aw_e2e_compose exec -T mysql sh -ec 'MYSQL_PWD="$(cat /run/e2e-secrets/mysql-password)" exec mysql -uroot autowonderdev' < "$root/e2e-tests/bootstrap-admin.sql"
    fi
    aw_e2e_compose up -d app
    printf 'Local URL: http://127.0.0.1:%s\nData: %s\n' "$RESOLVED_APP_PORT" "$AW_RUN_DIR"
    ;;
  stop) aw_e2e_compose stop app minio mysql redis ;;
  status) aw_e2e_compose ps; printf 'Local URL: http://127.0.0.1:%s\n' "$RESOLVED_APP_PORT" ;;
  rebuild|backend)
    skip_frontend=false
    [[ "$action" != backend ]] || skip_frontend=true
    env -u NODE_OPTIONS mvn -B -DskipTests -DskipFrontend="$skip_frontend" -DskipGitCommitId=true -f "$root/pom.xml" package
    "$DOCKER" build --platform "$AW_E2E_PLATFORM" --build-arg "AW_E2E_JRE_IMAGE=$AW_E2E_JRE_IMAGE" \
      -f "$root/e2e-tests/Dockerfile.internal" -t "$AW_E2E_APP_IMAGE" --load "$root"
    aw_e2e_compose up -d --force-recreate --no-deps app
    ;;
  ui)
    cd "$root/frontend"
    export AW_LOCAL_API_URL="http://127.0.0.1:$RESOLVED_APP_PORT"
    exec npm run dev -- --host 127.0.0.1
    ;;
  *) printf 'Usage: bash scripts/local-dev.sh {init|start|stop|status|rebuild|backend|ui}\n' >&2; exit 2 ;;
esac
