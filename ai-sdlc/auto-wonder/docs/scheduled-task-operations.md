# 7×24 数字人任务运行手册

本文用于在多个客户环境灰度发布 V037。「V037」是本次灰度的代号，与代码中的 `V037_READY`、`V037CompatibilityMatrixTest` 等标识一致；社区版对应的增量迁移文件是 `docs/migration/V041__scheduled_task.sql`。目标是在数据库变更前、变更中和变更后保持普通工单可用，并且只在整个集群完成升级后开放 Scheduled Task。

## 三个启动时开关

| 配置项 | 环境变量 | 默认值 | 作用 |
|---|---|---:|---|
| `autowonder.scheduled-task.enabled` | `AUTOWONDER_SCHEDULED_TASK_ENABLED` | `false` | 开放 Scheduled API、回调和 manual Run |
| `autowonder.scheduled-task.scanner-enabled` | `AUTOWONDER_SCHEDULED_TASK_SCANNER_ENABLED` | `false` | 允许 Cron Scanner 认领到期任务；只有模块可用时才生效 |
| `autowonder.scheduled-task.cluster-ready-attestation` | `AUTOWONDER_SCHEDULED_TASK_CLUSTER_READY` | `false` | 发布平台对整个集群已升级完成的外部证明 |

三个值都由 `ScheduledTaskCapabilityGuard` 在 Bean 构造时复制并冻结，**不支持热更新**。每一次开关变化都必须发布配置、滚动重启每一个服务节点，并按节点验证新配置版本和能力结果；只改环境变量、配置中心值或部分节点不生效也不安全。

`CLUSTER_READY` 绝不能由单节点根据本机 Schema 自动推断。只有发布平台确认每个承载业务流量的节点都报告 `mode=V037_READY`、`mapper_mode=SOURCE_AWARE`，且没有 Legacy、Partial 或未知节点后，才能设置 `AUTOWONDER_SCHEDULED_TASK_CLUSTER_READY=true`。

## 每个客户环境的上线步骤

客户环境必须分别执行、分别验收；不得复用另一环境的备份、阈值或 attestation。

### 1. 备份、恢复与变更阈值

发布单必须记录数据库实例、PITR/快照 ID、可恢复时间点、备份校验结果、恢复演练 ID/时间/RTO/RPO 和 DBA 签字。恢复演练至少验证能够启动兼容二进制并读取普通 Workitem。数据库回退会丢失快照之后仍在产生的普通 Workitem 写入，因此 V037 故障优先前向修复；只有事故负责人接受数据损失范围并完成写入冻结/补偿方案后才能恢复快照。

每个客户必须在发布单填写并批准以下值，不能沿用示例或留空：

| 信号 | 客户批准阈值 | 超阈值动作 | 负责人 |
|---|---|---|---|
| Metadata lock 等待 | `${MAX_METADATA_LOCK_WAIT_SECONDS}` 秒 | 不执行/停止后续 DDL；定位并结束或等待阻塞事务，重新排期 | DBA |
| 最老未提交事务 | `${MAX_LONG_TX_SECONDS}` 秒 | 暂停变更；业务 Owner 确认提交/回滚 | DBA + 应用 Owner |
| Replica lag | `${MAX_REPLICA_LAG_SECONDS}` 秒 | 停止后续语句，等待追平；超时进入事故流程 | DBA |
| Workitem 写入 p95 | `${MAX_WORKITEM_WRITE_P95_MS}` ms | 停止后续语句；扩容、限流或改期 | SRE + 应用 Owner |
| Workitem 5xx/业务失败率 | `${MAX_WORKITEM_ERROR_RATE_PERCENT}`% | 立即停止发布并保留普通工单证据 | SRE |

在等量数据的预发/影子库演练 `docs/migration/V041__scheduled_task.sql`，记录每条语句的耗时、锁等待、磁盘增量和复制延迟。生产执行前确认 MySQL 8.0、DDL/`information_schema` 权限、磁盘余量、执行器在线和上述信号均低于阈值。

每次发布使用唯一证据目录，并在执行任何 SQL 前确认数据库身份。MySQL 凭证只放在专用、权限 `0600` 的 option file；使用 `--defaults-file`（不是 `--defaults-extra-file`）作为 `mysql` 的**第一个选项**。runner 必须在专用 OS 用户或一次性容器中运行，以权限 `0700` 的隔离 HOME 启动，并证明其中没有 `.mylogin.cnf`，防止登录路径覆盖目标；不要添加未经 MySQL 8.0 验证的 client option。以下变量来自审批后的发布单，不能临时猜测：

```bash
set -euo pipefail
: "${CUSTOMER_ID:?}"
: "${RELEASE_ID:?}"
: "${EXECUTION_ATTEMPT_ID:?}"
: "${EXPECTED_DATABASE:?}"
: "${EXPECTED_DB_HOSTNAME:?}"
: "${EXPECTED_DB_PORT:?}"
: "${EXPECTED_DB_SERVER_UUID:?}"
: "${RELEASE_EVIDENCE_ROOT:?}"
: "${MYSQL_DEFAULTS_FILE:?}"

[[ "${CUSTOMER_ID}" =~ ^[A-Za-z0-9._-]+$ ]]
[[ "${RELEASE_ID}" =~ ^[A-Za-z0-9._-]+$ ]]
[[ "${EXECUTION_ATTEMPT_ID}" =~ ^[A-Za-z0-9._-]+$ ]]
test -f "${MYSQL_DEFAULTS_FILE}"
mysql_cnf_mode="$(stat -f '%Lp' "${MYSQL_DEFAULTS_FILE}" 2>/dev/null \
  || stat -c '%a' "${MYSQL_DEFAULTS_FILE}")"
test "${mysql_cnf_mode}" = "600"

umask 077
RELEASE_DIR="${RELEASE_EVIDENCE_ROOT}/${CUSTOMER_ID}-${RELEASE_ID}"
case "${V037_RESUME:-false}" in
  false) test ! -e "${RELEASE_DIR}"; install -d -m 700 "${RELEASE_DIR}" ;;
  true) test -d "${RELEASE_DIR}"; test -f "${RELEASE_DIR}/v037-ledger.tsv" ;;
  *) printf 'V037_RESUME must be true or false\n' >&2; exit 2 ;;
esac
EVIDENCE_DIR="${RELEASE_DIR}/attempt-${EXECUTION_ATTEMPT_ID}"
test ! -e "${EVIDENCE_DIR}"
install -d -m 700 "${EVIDENCE_DIR}"
RUNNER_HOME="${EVIDENCE_DIR}/isolated-home"
install -d -m 700 "${RUNNER_HOME}"
test ! -e "${RUNNER_HOME}/.mylogin.cnf"
MYSQL=(env "HOME=${RUNNER_HOME}" mysql "--defaults-file=${MYSQL_DEFAULTS_FILE}" \
  "--database=${EXPECTED_DATABASE}" --batch --raw)

mysql_client_version="$("${MYSQL[@]}" --version)"
printf '%s\n' "${mysql_client_version}" | tee "${EVIDENCE_DIR}/mysql-client-version.txt"
[[ "${mysql_client_version}" == *' Ver 8.0.'* ]]

db_identity="$("${MYSQL[@]}" --skip-column-names \
  --connect-timeout=5 \
  -e 'SELECT @@hostname, @@port, DATABASE(), @@server_uuid')"
printf '%s\n' "${db_identity}" | tee "${EVIDENCE_DIR}/database-identity.tsv"
IFS=$'\t' read -r actual_hostname actual_port actual_database actual_uuid <<<"${db_identity}"
test "${actual_hostname}" = "${EXPECTED_DB_HOSTNAME}"
test "${actual_port}" = "${EXPECTED_DB_PORT}"
test "${actual_database}" = "${EXPECTED_DATABASE}"
test "${actual_uuid}" = "${EXPECTED_DB_SERVER_UUID}"

approval="${CUSTOMER_ID}|${actual_hostname}|${actual_port}|${actual_database}|${actual_uuid}"
printf 'Type exactly to approve target: %s\n' "${approval}" >/dev/tty
IFS= read -r typed_approval </dev/tty
test "${typed_approval}" = "${approval}"
printf 'APPROVED\t%s\n' "${approval}" >> "${EVIDENCE_DIR}/database-identity.tsv"
```

若身份不匹配或没有人工逐字确认，脚本以非 0 退出；不得执行预检或 DDL。后续命令复用同一受控 Shell 中的 `MYSQL`、`RELEASE_DIR` 与 `EVIDENCE_DIR`。初次执行必须使用 `V037_RESUME=false`；恢复时使用相同 customer/release、新的唯一 attempt ID 和 `V037_RESUME=true`，重新核对身份与碰撞门禁，但沿用 release 级 append-only ledger，不能覆盖旧证据。

发布平台应在每个 runner 镜像升级时执行以下无凭证 contract check；本手册修订时已用 `mysql:8.0`（client 8.0.46）验证该 option 形态成功：

```bash
docker run --rm mysql:8.0 \
  mysql --defaults-file=/dev/null --version \
| tee "${EVIDENCE_DIR}/mysql8-option-contract.txt"
grep -F ' Ver 8.0.' "${EVIDENCE_DIR}/mysql8-option-contract.txt"
```

### 2. 归一化幂等键冲突预检

永久兼容约束：所有版本、所有 Schema/Mapper 模式下，`WORKITEM` 新写入必须始终使用历史数字格式 `workitemId:stepId:attempt`；只有 `SCHEDULED_TASK_RUN` 等非 Workitem 来源使用带来源的命名空间键。应用对 raw 与 `WORKITEM:<raw>` 的双读只容忍曾经错误产生或导入的 prefixed 存量行，不授权新节点写 prefixed Workitem 键。该约束保证兼容二进制与旧节点并存或回滚时仍争用 pre-V037 的同一唯一键。

pre-V037 中以下两行可能因历史错误数据或导入数据而在旧唯一键下共存，但 V037 会把它们归一化为同一个值：同租户的原始数字键 `1:2:3` 与命名空间键 `WORKITEM:1:2:3`。DDL 前必须执行 COUNT 门禁并归档结果；只有精确整数 `0` 才能继续。发生碰撞时脚本额外保存明细并以非 0 退出，由数据 Owner 判定正确记录、审计去重后使用新的 release ID 重做全部预检。

```bash
collision_count="$("${MYSQL[@]}" --skip-column-names --connect-timeout=5 <<'SQL'
SELECT COUNT(*)
FROM dispatch AS raw_key
JOIN dispatch AS namespaced
  ON namespaced.tenant_id = raw_key.tenant_id
 AND namespaced.idempotency_key = CONCAT('WORKITEM:', raw_key.idempotency_key)
WHERE raw_key.idempotency_key REGEXP '^[0-9]+:[0-9]+:[0-9]+$';
SQL
)"
printf 'normalized_collision_count\t%s\n' "${collision_count}" \
  | tee "${EVIDENCE_DIR}/normalized-collision-count.tsv"
[[ "${collision_count}" =~ ^[0-9]+$ ]]
if (( collision_count != 0 )); then
  "${MYSQL[@]}" --column-names --connect-timeout=5 <<'SQL' \
    | tee "${EVIDENCE_DIR}/normalized-collision-detail.tsv"
SELECT raw_key.tenant_id,
       raw_key.id AS raw_dispatch_id,
       raw_key.idempotency_key AS raw_idempotency_key,
       namespaced.id AS namespaced_dispatch_id,
       namespaced.idempotency_key AS namespaced_idempotency_key
FROM dispatch AS raw_key
JOIN dispatch AS namespaced
  ON namespaced.tenant_id = raw_key.tenant_id
 AND namespaced.idempotency_key = CONCAT('WORKITEM:', raw_key.idempotency_key)
WHERE raw_key.idempotency_key REGEXP '^[0-9]+:[0-9]+:[0-9]+$'
ORDER BY raw_key.tenant_id, raw_key.id;
SQL
  exit 10
fi
test "${collision_count}" = "0"
```

### 3. 先部署兼容二进制，三个开关关闭

在 pre-V037 Schema 上设置三个环境变量为 `false`，发布配置并滚动重启每个节点。验证全部节点都运行同一目标镜像/commit 和配置 revision，报告 `LEGACY/LEGACY`；随后冒烟普通工单的创建、派发、ACK/Result、评论、附件和 Dashboard。Scheduled UI 应隐藏入口或显示升级中，能力 API 返回 `available=false`，且不能产生 Scheduled 数据。

### 4. 用校验清单逐语句执行 V037

V037 含多个 DDL；MySQL 会逐条提交，整个文件**不是一个事务**。两个 `CREATE TABLE` 带 `IF NOT EXISTS`，后续 `ALTER TABLE` 是无条件语句，部分失败后盲目重跑整份文件会遇到重复列/索引错误，甚至掩盖物理定义不一致。

禁止把整个 migration 直接交给 `mysql`，也禁止用 `;`、正则或通用文本工具猜测 SQL 语句边界。生成列表达式、字符串或注释内都可能出现分号。当前 V037 的审批制品必须精确匹配：

- 完整文件 SHA-256：`e2e4180cdcc1bb6c1099b2f21d0ab559d66754490e9f6d9f33542ba1a5e652a8`
- 审批 manifest（行号含首尾；分片包含结尾换行）：

| ID | 行 | SQL | 分片 SHA-256 |
|---|---:|---|---|
| `01` | 1–29 | `CREATE TABLE scheduled_task` | `f8f1d513982a065e1b2e2ecd54be8d842c250d5dcd20683756e2bbbb4cc23d29` |
| `02` | 31–68 | `CREATE TABLE scheduled_task_run` | `c27f1180d6d08408b30ec022eebc34cb63358d067bd26a78d14d299cc5997705` |
| `03` | 70–81 | `ALTER TABLE dispatch` | `6929e12184bec98aace885db01e899c24a75a7f752b970baa24f1377a08ccc89` |
| `04` | 83–85 | `ALTER TABLE artifact` | `c07af9466200c4a74d557d3799176733f6128557f5af1d6943740848d60d1906` |
| `05` | 87–89 | `ALTER TABLE workitem_comment` | `593ea06d7093ed93ac8ccf4882b128c7a7c7f07d6515087d6852a252dfd8f444` |
| `06` | 91–93 | `ALTER TABLE workitem_comment_mention` | `7a4cd8e01fb16a826772755092cdfa68aa4dce2ef0d5b8384f3b409fdff33ee0` |
| `07` | 95–97 | `ALTER TABLE workitem_comment_delivery` | `eb47d0456415e26c566dcd7fa76d2a0fbebbb2b7182715db5860e39b37902fe9` |
| `08` | 99–102 | `ALTER TABLE workitem` | `358edadf636d4ba6dd377718257cd18038de28b74d7c0206cf330e62e90a19c4` |

若完整文件或任一分片哈希不同，停止发布并重新评审 manifest；不得自动接受新版。发布平台的 approved V037 runner 必须实现以下机制：

1. 验证完整文件哈希，再按上述**已审定行区间**提取一个分片到 `0600` 临时文件并验证分片哈希；绝不动态按分号拆分。
2. 在读取共享 ledger/选择 ID **之前**，通过发布平台或分布式协调服务取得 V037 lease。lease 包含唯一 owner、attempt 和单调 fencing token；TTL 必须大于客户批准的最大单条 DDL + reconciliation 时间，runner 持续 heartbeat。无法 acquire/renew 时禁止执行。lease 服务必须实现 `ACTIVE → LOST/QUARANTINED` 状态机：TTL 到期、heartbeat 丢失或 release 结果不确定时只能进入隔离，**严禁因 TTL 到期自动重新分配 owner/token**。
3. 在持有 lease 的情况下读取 ledger，ID 必须是 manifest 顺序的第一个非 `SUCCESS` 项；一次 invocation 只允许一个 ID。`RELEASE_DIR`/ledger 必须位于跨 runner 可见的耐久证据存储，不能把单机文件锁或本地目录称为跨主机互斥。
4. 执行客户批准的 threshold gate，且 gate 自身的路径与 SHA-256 已在发布单固定。gate 在语句前后检查 metadata lock、长事务、replica lag、Workitem p95/错误率；任一超阈值都返回非 0，runner 记录 `BLOCKED` 并停止。
5. 将 `STARTED`、UTC 时间、owner 和 fencing token 写入 append-only ledger，然后仅执行该临时 SQL 文件。MySQL 非 0 时记录 `FAILED`、归档 stderr 并立即停止；不得继续下一 ID。
6. MySQL 成功只记录 `APPLIED_UNRECONCILED`。随后运行审批过的 reconciliation gate，将本 ID 的 `information_schema` 物理定义与 V037 精确合同核对；只有 gate 与语句后 threshold gate 都成功才能追加 `SUCCESS`。lease 必须一直持有到最终 ledger append 完成，再用相同 owner/token 条件释放。

安全 runner 的核心形态如下；`${V037_THRESHOLD_GATE}` 与 `${V037_RECONCILE_GATE}` 必须是平台已审批、校验过 SHA-256 的**绝对可执行文件**，不得使用 `eval` 或从发布单拼接 Shell：

```bash
set -euo pipefail
: "${NEXT_STATEMENT_ID:?set one of 01..08}"
: "${V037_THRESHOLD_GATE:?}"
: "${V037_RECONCILE_GATE:?}"
: "${EXPECTED_THRESHOLD_GATE_SHA256:?}"
: "${EXPECTED_RECONCILE_GATE_SHA256:?}"
: "${V037_LEASE_ACQUIRE:?}"
: "${V037_LEASE_RENEW:?}"
: "${V037_LEASE_RELEASE:?}"
: "${V037_LEDGER_APPEND:?}"
: "${EXPECTED_LEASE_ACQUIRE_SHA256:?}"
: "${EXPECTED_LEASE_RENEW_SHA256:?}"
: "${EXPECTED_LEASE_RELEASE_SHA256:?}"
: "${EXPECTED_LEDGER_APPEND_SHA256:?}"
: "${LEASE_TTL_SECONDS:?}"
: "${LEASE_HEARTBEAT_SECONDS:?}"
: "${MAX_V037_CRITICAL_SECTION_SECONDS:?}"
SOURCE_MIGRATION=docs/migration/V041__scheduled_task.sql
MIGRATION="${RELEASE_DIR}/V037__scheduled_task.approved.sql"
LEDGER="${RELEASE_DIR}/v037-ledger.tsv"
LEASE_OWNER_ID="${CUSTOMER_ID}-${RELEASE_ID}-${EXECUTION_ATTEMPT_ID}"
LEASE_KEY="autowonder:v037:${EXPECTED_DB_SERVER_UUID}:${EXPECTED_DATABASE}"

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

[[ "${V037_THRESHOLD_GATE}" = /* && -x "${V037_THRESHOLD_GATE}" ]]
[[ "${V037_RECONCILE_GATE}" = /* && -x "${V037_RECONCILE_GATE}" ]]
[[ "${V037_LEASE_ACQUIRE}" = /* && -x "${V037_LEASE_ACQUIRE}" ]]
[[ "${V037_LEASE_RENEW}" = /* && -x "${V037_LEASE_RENEW}" ]]
[[ "${V037_LEASE_RELEASE}" = /* && -x "${V037_LEASE_RELEASE}" ]]
[[ "${V037_LEDGER_APPEND}" = /* && -x "${V037_LEDGER_APPEND}" ]]
test "$(sha256_file "${V037_THRESHOLD_GATE}")" = "${EXPECTED_THRESHOLD_GATE_SHA256}"
test "$(sha256_file "${V037_RECONCILE_GATE}")" = "${EXPECTED_RECONCILE_GATE_SHA256}"
test "$(sha256_file "${V037_LEASE_ACQUIRE}")" = "${EXPECTED_LEASE_ACQUIRE_SHA256}"
test "$(sha256_file "${V037_LEASE_RENEW}")" = "${EXPECTED_LEASE_RENEW_SHA256}"
test "$(sha256_file "${V037_LEASE_RELEASE}")" = "${EXPECTED_LEASE_RELEASE_SHA256}"
test "$(sha256_file "${V037_LEDGER_APPEND}")" = "${EXPECTED_LEDGER_APPEND_SHA256}"
[[ "${LEASE_OWNER_ID}" =~ ^[A-Za-z0-9._-]+$ ]]
[[ "${LEASE_TTL_SECONDS}" =~ ^[0-9]+$ ]]
[[ "${LEASE_HEARTBEAT_SECONDS}" =~ ^[0-9]+$ ]]
[[ "${MAX_V037_CRITICAL_SECTION_SECONDS}" =~ ^[0-9]+$ ]]
(( LEASE_TTL_SECONDS > MAX_V037_CRITICAL_SECTION_SECONDS ))
(( LEASE_HEARTBEAT_SECONDS > 0 ))
(( LEASE_HEARTBEAT_SECONDS * 3 < LEASE_TTL_SECONDS ))
if test ! -e "${MIGRATION}"; then
  install -m 400 "${SOURCE_MIGRATION}" "${MIGRATION}"
fi
test "$(sha256_file "${MIGRATION}")" \
  = "e2e4180cdcc1bb6c1099b2f21d0ab559d66754490e9f6d9f33542ba1a5e652a8"
manifest_row="$(awk -v id="${NEXT_STATEMENT_ID}" '$1 == id { print; found=1 }
  END { if (!found) exit 1 }' <<'MANIFEST'
01 1 29 f8f1d513982a065e1b2e2ecd54be8d842c250d5dcd20683756e2bbbb4cc23d29
02 31 68 c27f1180d6d08408b30ec022eebc34cb63358d067bd26a78d14d299cc5997705
03 70 81 6929e12184bec98aace885db01e899c24a75a7f752b970baa24f1377a08ccc89
04 83 85 c07af9466200c4a74d557d3799176733f6128557f5af1d6943740848d60d1906
05 87 89 593ea06d7093ed93ac8ccf4882b128c7a7c7f07d6515087d6852a252dfd8f444
06 91 93 7a4cd8e01fb16a826772755092cdfa68aa4dce2ef0d5b8384f3b409fdff33ee0
07 95 97 eb47d0456415e26c566dcd7fa76d2a0fbebbb2b7182715db5860e39b37902fe9
08 99 102 358edadf636d4ba6dd377718257cd18038de28b74d7c0206cf330e62e90a19c4
MANIFEST
)"
read -r statement_id first_line last_line expected_sha <<<"${manifest_row}"

# Commands below call a distributed lease API and must compare owner+fencing token.
# Acquire MUST fail while the key is LOST/QUARANTINED; TTL expiry is not takeover.
fencing_token="$("${V037_LEASE_ACQUIRE}" --key "${LEASE_KEY}" \
  --owner "${LEASE_OWNER_ID}" --ttl "${LEASE_TTL_SECONDS}")"
[[ "${fencing_token}" =~ ^[A-Za-z0-9._:-]+$ ]]
printf '%s\t%s\t%s\n' "${LEASE_OWNER_ID}" "${fencing_token}" "${LEASE_KEY}" \
  > "${EVIDENCE_DIR}/lease-acquired.tsv"
lease_lost="${EVIDENCE_DIR}/lease-lost"
runner_pid="$$"
(
  while sleep "${LEASE_HEARTBEAT_SECONDS}"; do
    if ! "${V037_LEASE_RENEW}" --key "${LEASE_KEY}" \
      --owner "${LEASE_OWNER_ID}" --fencing-token "${fencing_token}" \
      --ttl "${LEASE_TTL_SECONDS}"; then
      : > "${lease_lost}"
      # TERM may be deferred while mysql is the foreground process. It prevents
      # later shell statements but does not prove that an in-flight DDL stopped.
      kill -TERM "${runner_pid}"
      exit 1
    fi
  done
) &
lease_renewer_pid="$!"
lease_released=false
cleanup_lease() {
  kill "${lease_renewer_pid}" 2>/dev/null || true
  wait "${lease_renewer_pid}" 2>/dev/null || true
  if test "${lease_released}" = false; then
    if ! "${V037_LEASE_RELEASE}" --key "${LEASE_KEY}" \
      --owner "${LEASE_OWNER_ID}" --fencing-token "${fencing_token}"; then
      : > "${EVIDENCE_DIR}/lease-release-failed"
      return 1
    fi
    lease_released=true
  fi
}
trap cleanup_lease EXIT
trap 'exit 32' HUP INT TERM

# Lease is held before the first shared-ledger read below.
"${V037_LEDGER_APPEND}" --initialize --ledger "${LEDGER}" --key "${LEASE_KEY}" \
  --owner "${LEASE_OWNER_ID}" --fencing-token "${fencing_token}"
test -f "${LEDGER}"
append_ledger() {
  local status="$1"
  "${V037_LEDGER_APPEND}" --ledger "${LEDGER}" --key "${LEASE_KEY}" \
    --owner "${LEASE_OWNER_ID}" --fencing-token "${fencing_token}" \
    --statement "${statement_id}" --status "${status}" --utc "$(date -u +%FT%TZ)"
}
first_non_success="$(for id in 01 02 03 04 05 06 07 08; do
  last="$(awk -F '\t' -v id="${id}" '$1 == id { status=$2 }
    END { print status }' "${LEDGER}")"
  if test "${last}" != SUCCESS; then printf '%s\n' "${id}"; break; fi
done)"
test "${statement_id}" = "${first_non_success}"
last_status="$(awk -F '\t' -v id="${statement_id}" '$1 == id { status=$2 }
  END { print status }' "${LEDGER}")"
case "${last_status}" in
  ""|SAFE_TO_RETRY) ;;
  *) printf 'statement %s requires reconciliation; last status=%s\n' \
       "${statement_id}" "${last_status}" >&2; exit 18 ;;
esac

statement_file="$(mktemp "${EVIDENCE_DIR}/statement-${statement_id}.sql.XXXXXX")"
chmod 600 "${statement_file}"
sed -n "${first_line},${last_line}p" "${MIGRATION}" > "${statement_file}"
test "$(sha256_file "${statement_file}")" = "${expected_sha}"

if ! "${V037_THRESHOLD_GATE}" --customer "${CUSTOMER_ID}" \
  --release "${RELEASE_ID}" --statement "${statement_id}" --phase before \
  | tee "${EVIDENCE_DIR}/${statement_id}-threshold-before.log"; then
  append_ledger BLOCKED
  exit 19
fi
test ! -e "${lease_lost}"
append_ledger STARTED
if ! "${MYSQL[@]}" --connect-timeout=5 < "${statement_file}" \
  2> >(tee "${EVIDENCE_DIR}/${statement_id}-mysql.stderr" >&2); then
  append_ledger FAILED
  exit 20
fi
test ! -e "${lease_lost}"
append_ledger APPLIED_UNRECONCILED
"${V037_RECONCILE_GATE}" --customer "${CUSTOMER_ID}" \
  --release "${RELEASE_ID}" --statement "${statement_id}" \
  --evidence "${EVIDENCE_DIR}"
if ! "${V037_THRESHOLD_GATE}" --customer "${CUSTOMER_ID}" \
  --release "${RELEASE_ID}" --statement "${statement_id}" --phase after \
  | tee "${EVIDENCE_DIR}/${statement_id}-threshold-after.log"; then
  append_ledger BLOCKED_AFTER_APPLY
  exit 21
fi
test ! -e "${lease_lost}"
append_ledger SUCCESS
rm -f -- "${statement_file}"
cleanup_lease
trap - EXIT HUP INT TERM
```

每次只运行一个 ID，例如 `NEXT_STATEMENT_ID=03`，人工审核 ledger 与证据后再启动下一次。生产 runner 还必须用 trap 清理临时 SQL；上面示例保留失败分片供事故取证。

lease/ledger 命令必须内置客户批准的连接/请求总超时，任何 acquire、renew、release 或 append 不可用都按失败处理。`V037_LEASE_ACQUIRE/RENEW/RELEASE` 及服务端必须实现 quarantine 语义：heartbeat 丢失、TTL 到期或 release 不确定后，key 保持 `LOST/QUARANTINED`，普通 acquire 永远失败；release 也不能清除 quarantine。不能依赖本地 `kill`、trap 或 fencing token 杀死 MySQL DDL——Shell 在前台 `mysql` 进程返回前可能延迟处理 TERM，旧 runner/数据库语句可能在 lease 丢失后继续执行。heartbeat/trap 只保证 runner 一旦重新取得控制就不再开始后续语句，**不证明正在执行的 DDL 已停止**。

发生 quarantine 后禁止自动接管。DBA 必须以 `SHOW FULL PROCESSLIST`、`performance_schema.events_statements_current`、`performance_schema.metadata_locks`（以及客户平台的会话/审计记录）证明旧连接和该 DDL 已不存在或已由 DBA 明确终止，归档连接 ID、SQL digest、metadata-lock 状态与采集时间；同时把 ledger、分片 checksum、`information_schema` 物理结构和当前 threshold 全部对账。若任何连接/DDL 状态未知，保持隔离并升级事故。只有上述证据闭合后，才允许两个不同人员（DBA 与 SRE/发布 Owner）审批一次带原 owner、attempt、最后 token 和证据哈希的 fenced force-clear/reissue；lease 服务原子记录审批与更高 token 后才解除 quarantine。新 runner 取得新 token 后还要在 lease 内再次 reconciliation，不能因 force-clear 推断旧 DDL 未生效。发布平台 helper/API 必须强制这些状态和双人审批，不能把它们只写成 runbook 约定。

release 失败时保留 `lease-release-failed` 证据并立即 quarantine；即使 TTL 已过也不得启动下一 attempt。`V037_LEDGER_APPEND` 必须由发布平台实现原子 append，输出固定为 `statement_id<TAB>status<TAB>utc<TAB>owner<TAB>fencing_token`，并在每次写入时向 lease 服务校验 owner 与 fencing token 仍为当前值；只把 token 记录到本地文件不构成 fencing。任何非 `SUCCESS` 状态都必须恢复，包括 `STARTED`（进程中断，数据库结果未知）、`FAILED`、`BLOCKED`、`APPLIED_UNRECONCILED`、`BLOCKED_AFTER_APPLY` 以及未知状态。普通恢复仅适用于 lease 已正常释放；若是 LOST/QUARANTINED，必须先完成上一段的数据库活动证明、schema/ledger/threshold reconciliation 和双人 force-clear/reissue。lease 过期本身绝不授权重跑。

恢复结论只能是：物理合同完整且阈值通过 → 追加 `SUCCESS`；确认完全未生效且阈值通过 → 追加 `SAFE_TO_RETRY`；阈值不通过或证据不足 → 保持/追加 `BLOCKED`；非预期/混合定义 → 执行评审过的定向 repair SQL，重新核对后才可 `SUCCESS`。`STARTED` 和 `BLOCKED_AFTER_APPLY` 尤其不能按失败推断为“未执行”。随后从 ledger 中**第一个非 SUCCESS** ID 继续，绝不盲目重跑全文件或已成功语句。

任一语句或阈值失败时保持三个开关关闭，通知 DBA、SRE、应用 Owner，保存错误、`SHOW ENGINE INNODB STATUS`、长事务、metadata lock、复制与延迟证据；不要删除已成功的加法对象。

DBA 必须用 `information_schema.tables`、`information_schema.columns` 和 `information_schema.statistics` 将实际表、列类型/nullable/default、生成列表达式、索引唯一性及列顺序与迁移文件逐项核对：

```sql
SELECT table_name, engine
FROM information_schema.tables
WHERE table_schema = DATABASE()
  AND table_name IN ('scheduled_task', 'scheduled_task_run');

SELECT table_name, column_name, column_type, is_nullable,
       column_default, extra, generation_expression
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name IN ('scheduled_task', 'scheduled_task_run', 'dispatch', 'artifact',
                     'workitem_comment', 'workitem_comment_mention',
                     'workitem_comment_delivery', 'workitem')
ORDER BY table_name, ordinal_position;

SELECT table_name, index_name, non_unique, seq_in_index, column_name
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND table_name IN ('scheduled_task', 'scheduled_task_run', 'dispatch', 'artifact',
                     'workitem_comment', 'workitem_comment_mention',
                     'workitem_comment_delivery', 'workitem')
ORDER BY table_name, index_name, seq_in_index;
```

物理状态与账本一致后，修正失败原因并从**确切失败的那一条语句**继续；若失败语句已有部分预期对象或定义不同，DBA 先生成并评审针对当前状态的补齐 SQL，不能直接重跑该 `ALTER`。无法证明一致时按事故升级处理，节点保持在兼容模式且 Scheduled 不得启用。

### 5. DDL 后重启所有节点

Schema capability、MyBatis `databaseId` 和三个开关都在启动时冻结。运行中的 Legacy 节点不会因 DDL 完成自动切换。滚动重启每个节点，三个开关仍为 false；滚动期间只处理普通 Workitem。每个节点必须重新选择 `autowonder-source-aware`。

### 6. 形成可审计的集群 attestation

从编排/发布平台导出**所有 serving 节点**清单和期望副本数；不要通过负载均衡地址反复抽样，因为抽样不能证明每个节点。清单至少包含客户、node/pod UID、直接地址、镜像 digest/commit、配置 revision、启动时间和 readiness。

对清单中的每个直接节点调用能力 API。令牌放在环境变量或受控 secret 注入中，不写入发布文件；完整 `Result` envelope 的成功示例为：

```json
{
  "success": true,
  "code": "0",
  "message": "",
  "data": {
    "available": false,
    "mode": "V037_READY",
    "clusterReady": false,
    "reason": "FEATURE_DISABLED"
  },
  "traceId": "...",
  "request_id": "..."
}
```

由编排平台生成 `serving-nodes.tsv`，每行是 `node_uid<TAB>direct_node_url<TAB>startup_log_file`；日志文件必须来自同一 UID 的当前启动。每次采集使用发布内唯一的 attempt ID。curl 的认证 Header 写入临时 `0600` config，不出现在进程参数；连接 5 秒、单节点请求总时长 15 秒，超时即失败且不重试到其它节点。以下门禁会直接访问每个节点而不是 LB，并同时证明 `V037_READY` 与 `SOURCE_AWARE`：

```bash
set -euo pipefail
sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}
: "${EXPECTED_READY_REPLICAS:?missing expected replica count}"
: "${ATTESTATION_ATTEMPT_ID:?missing unique attempt id}"
: "${SERVING_NODES_SNAPSHOT:?missing orchestrator snapshot}"
: "${EXPECTED_SERVING_SNAPSHOT_SHA256:?missing approved snapshot checksum}"
: "${ALLOWED_INTERNAL_DNS_SUFFIX:?missing internal DNS suffix}"
: "${APPROVED_DIRECT_PORT:?missing direct port}"
: "${APPROVED_CAPABILITY_PATH:?missing capability path}"
[[ "${ATTESTATION_ATTEMPT_ID}" =~ ^[A-Za-z0-9._-]+$ ]]
[[ "${ALLOWED_INTERNAL_DNS_SUFFIX}" =~ ^[a-z0-9.-]+$ ]]
[[ "${EXPECTED_READY_REPLICAS}" =~ ^[0-9]+$ ]]
(( EXPECTED_READY_REPLICAS > 0 ))
[[ "${APPROVED_DIRECT_PORT}" =~ ^[0-9]+$ ]]
(( APPROVED_DIRECT_PORT >= 1 && APPROVED_DIRECT_PORT <= 65535 ))
[[ "${EXPECTED_SERVING_SNAPSHOT_SHA256}" =~ ^[0-9a-f]{64}$ ]]
test "${APPROVED_CAPABILITY_PATH}" = "/api/capabilities/scheduled-task"
CAPABILITY_EXPECTATION="${CAPABILITY_EXPECTATION:-pre_activation}"
case "${CAPABILITY_EXPECTATION}" in
  pre_activation)
    expected_available=false; expected_cluster_ready=false
    expected_reason_json='"FEATURE_DISABLED"' ;;
  activated)
    expected_available=true; expected_cluster_ready=true
    expected_reason_json=null ;;
  *) printf 'invalid CAPABILITY_EXPECTATION\n' >&2; exit 30 ;;
esac
ATTESTATION_DIR="${RELEASE_DIR}/attestation-${ATTESTATION_ATTEMPT_ID}"
test ! -e "${ATTESTATION_DIR}"
install -d -m 700 "${ATTESTATION_DIR}"
snapshot_file="${ATTESTATION_DIR}/serving-nodes.tsv"
test "$(sha256_file "${SERVING_NODES_SNAPSHOT}")" = "${EXPECTED_SERVING_SNAPSHOT_SHA256}"
install -m 400 "${SERVING_NODES_SNAPSHOT}" "${snapshot_file}"
test "$(sha256_file "${snapshot_file}")" = "${EXPECTED_SERVING_SNAPSHOT_SHA256}"
awk -F '\t' 'NF != 3 || $1 == "" || $2 == "" || $3 == "" { exit 1 }' "${snapshot_file}"
test -z "$(cut -f1 "${snapshot_file}" | LC_ALL=C sort | uniq -d)"
test -z "$(cut -f2 "${snapshot_file}" | LC_ALL=C sort | uniq -d)"
serving_count="$(awk -F '\t' 'NF == 3 { count++ } END { print count + 0 }' "${snapshot_file}")"
test "${serving_count}" -eq "${EXPECTED_READY_REPLICAS}"

# Validate the immutable inventory before putting the bearer secret in memory/file.
while IFS=$'\t' read -r node_uid direct_node_url startup_log_file; do
  [[ "${node_uid}" =~ ^[A-Za-z0-9._-]+$ ]]
  test -f "${startup_log_file}"
  [[ "${direct_node_url}" =~ ^https://[^/@?#]+:[0-9]+$ ]]
  authority="${direct_node_url#https://}"
  direct_host="${authority%:*}"
  direct_port="${authority##*:}"
  [[ "${direct_host}" =~ ^[a-z0-9]([a-z0-9.-]*[a-z0-9])?$ ]]
  [[ "${direct_host}" == "${ALLOWED_INTERNAL_DNS_SUFFIX}" \
     || "${direct_host}" == *."${ALLOWED_INTERNAL_DNS_SUFFIX}" ]]
  test "${direct_port}" = "${APPROVED_DIRECT_PORT}"
done < "${snapshot_file}"
test "$(sha256_file "${snapshot_file}")" = "${EXPECTED_SERVING_SNAPSHOT_SHA256}"

: "${AUTOWONDER_OPS_TOKEN:?missing ops token}"
[[ "${AUTOWONDER_OPS_TOKEN}" != *$'\n'* && "${AUTOWONDER_OPS_TOKEN}" != *$'\r'* ]]
[[ "${AUTOWONDER_OPS_TOKEN}" != *'"'* && "${AUTOWONDER_OPS_TOKEN}" != *'\\'* ]]
curl_config="$(mktemp "${TMPDIR:-/tmp}/autowonder-curl-auth.XXXXXX")"
printf 'header = "Authorization: Bearer %s"\nconnect-timeout = 5\nmax-time = 15\nretry = 0\n' \
  "${AUTOWONDER_OPS_TOKEN}" > "${curl_config}"
chmod 600 "${curl_config}"
cleanup_curl_config() { rm -f -- "${curl_config}"; }
trap cleanup_curl_config EXIT
trap 'exit 130' HUP INT TERM

validated_count=0
while IFS=$'\t' read -r node_uid direct_node_url startup_log_file; do
  curl --config "${curl_config}" --fail --silent --show-error \
    --url "${direct_node_url}${APPROVED_CAPABILITY_PATH}" \
    --output "${ATTESTATION_DIR}/${node_uid}-capability.json"
  jq -e --argjson available "${expected_available}" \
        --argjson cluster_ready "${expected_cluster_ready}" \
        --argjson reason "${expected_reason_json}" \
         '.success == true and .code == "0"
          and .data.available == $available
          and .data.mode == "V037_READY"
          and .data.clusterReady == $cluster_ready
          and .data.reason == $reason' \
    "${ATTESTATION_DIR}/${node_uid}-capability.json" >/dev/null
  grep -F 'mode=V037_READY, mapper_mode=SOURCE_AWARE, scheduled_available=true' \
    "${startup_log_file}" > "${ATTESTATION_DIR}/${node_uid}-startup-capability.log"
  validated_count=$((validated_count + 1))
done < "${snapshot_file}"

test "${validated_count}" -eq "${serving_count}"
test "${validated_count}" -eq "${EXPECTED_READY_REPLICAS}"
test "$(sha256_file "${snapshot_file}")" = "${EXPECTED_SERVING_SNAPSHOT_SHA256}"
rm -f -- "${curl_config}"
trap - EXIT HUP INT TERM
```

snapshot 必须由编排平台一次性导出并审批 SHA-256；脚本在 secret 加载前和请求结束后都复核同一归档副本。`node_uid` 和 direct URL 分别必须全局唯一。direct URL 只允许 `https://内部 DNS 后缀:批准端口`，不允许 userinfo、path、query 或 fragment；脚本只追加固定批准路径 `/api/capabilities/scheduled-task`。也可用同 UID 的节点级指标 `autowonder_schema_mode{mode=V037_READY,mapper_mode=SOURCE_AWARE}=1` 替代启动日志，但必须归档原始抓取结果。确认无额外、未知或未 ready 节点；将清单、响应、日志/指标、镜像与配置 revision 保存为发布制品，再由发布平台签发 attestation。

从开始采集到 Scheduled 启用完成期间冻结扩缩容和节点替换。若必须扩缩容/替换，新节点必须使用同一已验证镜像和配置，通过上述逐节点检查后重新生成整个集群的证据；任一节点状态未知立即停止启用流程。发布平台应把这条规则配置为部署准入策略，而不是依赖人工记忆。

### 7. 通过集群屏障原子开放模块，Scanner 保持关闭

在滚动重启前，先由网关/Service Mesh 以一个原子配置 revision 冻结**可独立路由**的 Scheduled 写入口和 UI：`/scheduled-tasks*` 页面，以及 Scheduled Task 的 create/update/enable/run-now HTTP 路由；读取路由是否冻结由客户维护页策略决定。不要声称能在共享 Daemon、通用 MCP tool 或 executor WebSocket 通道中按来源做网关过滤：这些通道和 dispatch credential 都由服务端按持久化来源解析，继续为普通 Workitem 开放。首次启用在第一条 Run 之前不会签发 Scheduled dispatch credential，也不会有 Scheduled callback；屏障通过禁止创建/Run 来保证这一点，而不是虚构 Scheduled 专用 MCP tool 的发布或撤销。

在写入口/变更冻结已经生效后、改变 enabled/attestation **紧邻之前**，必须运行并归档数据库级零数据门禁。以下查询覆盖 Scheduled Task、Run、Dispatch、Artifact、评论、Mention、Guidance/Delivery，以及防御性检查 `workitem.origin_type`；结果必须每行都是规范整数 `0`，Shell 总和也必须严格为 `0`：

```bash
set -euo pipefail
scheduled_zero_gate() {
  local zero_gate_file="$1"
  test ! -e "${zero_gate_file}"
  "${MYSQL[@]}" --skip-column-names --connect-timeout=5 <<'SQL' \
    | tee "${zero_gate_file}"
SELECT 'scheduled_task', COUNT(*) FROM scheduled_task
UNION ALL SELECT 'scheduled_task_run', COUNT(*) FROM scheduled_task_run
UNION ALL SELECT 'dispatch', COUNT(*) FROM dispatch WHERE source_type = 'SCHEDULED_TASK_RUN'
UNION ALL SELECT 'artifact', COUNT(*) FROM artifact WHERE source_type IN ('SCHEDULED_TASK','SCHEDULED_TASK_RUN')
UNION ALL SELECT 'workitem_comment', COUNT(*) FROM workitem_comment WHERE source_type = 'SCHEDULED_TASK_RUN'
UNION ALL SELECT 'workitem_comment_mention', COUNT(*) FROM workitem_comment_mention WHERE source_type = 'SCHEDULED_TASK_RUN'
UNION ALL SELECT 'workitem_comment_delivery', COUNT(*) FROM workitem_comment_delivery WHERE source_type = 'SCHEDULED_TASK_RUN'
UNION ALL SELECT 'workitem_origin', COUNT(*) FROM workitem WHERE origin_type = 'SCHEDULED_TASK_RUN';
SQL
  awk -F '\t' 'NF != 2 || $1 == "" || $2 !~ /^[0-9]+$/ { exit 2 }
    { total += $2 } END { if (NR != 8 || total != 0) exit 3 }' \
    "${zero_gate_file}"
}
scheduled_zero_gate \
  "${EVIDENCE_DIR}/scheduled-zero-gate-before-activation.tsv"
```

若任一计数非 0，禁止混合节点激活，也禁止使用“无数据快速回退”：保持三个开关关闭和写入口冻结，先确保**所有** serving 节点都是 source-aware-compatible，再按已有数据恢复/排空方案对账处理。不得删除数据来通过门禁。

保持路由屏障，设置 `AUTOWONDER_SCHEDULED_TASK_CLUSTER_READY=true`、`AUTOWONDER_SCHEDULED_TASK_ENABLED=true`、`AUTOWONDER_SCHEDULED_TASK_SCANNER_ENABLED=false`，发布配置并滚动重启**每一个**节点。按直接节点验证配置 revision；用新的唯一 `ATTESTATION_ATTEMPT_ID` 和 `CAPABILITY_EXPECTATION=activated` 对完整 serving UID 清单重新运行上节同一门禁。它会逐节点断言 `available=true, mode=V037_READY, clusterReady=true, reason=null`。每个节点都必须返回：

```json
{"success":true,"code":"0","message":"","data":{"available":true,"mode":"V037_READY","clusterReady":true,"reason":null},"traceId":"...","request_id":"..."}
```

只有 `已验证 UID 数 == serving UID 数 == 期望 ready 副本数` 后，才能考虑解除屏障。解除前，在同一写入口/变更冻结下紧邻执行同一机器门禁并归档；两次门禁之间的任何非发布写入都使证据失效：

```bash
scheduled_zero_gate \
  "${EVIDENCE_DIR}/scheduled-zero-gate-before-unfreeze.tsv"
```

第二次八项总和仍为 0 后，以一次原子网关/Service Mesh revision 解除 Scheduled UI/写路由冻结，不能逐 route、逐节点开放。确认所有网关实例已收敛到同一 revision 后再进入 manual Run。共享 MCP/Daemon/executor callback 通道始终没有被关闭；Scheduled dispatch credential 只会在后续 Run 创建派发时由服务端签发。若任一节点或第二次零数据门禁失败，屏障保持冻结；仅当零数据证据仍成立，才可把 enabled/attestation 恢复为 false、重启并验证所有节点后使用无数据快速回退。

### 8. 内部 manual Run

原子解除屏障后，仍以网关/RBAC 限制普通用户创建/enable/run-now，由发布身份立即创建内部测试任务并执行第一次 manual Run。验证派发、ACK/Result、评论、Artifact、Guidance、实时事件，以及 Workitem/Scheduled 相同数字 ID 的来源隔离。确认 `scheduled_task_run_created_total{trigger_type=MANUAL}` 增加且没有 `SCHEDULED` 触发；Scanner 仍保持关闭。第一条 Scheduled 数据产生后，任何回退都必须走“安全关闭”，不能再使用上一步的无数据快速回退。

### 9. 最后启用 Scanner

设置 `AUTOWONDER_SCHEDULED_TASK_SCANNER_ENABLED=true`，再次发布配置、滚动重启每个节点并逐节点核对 revision。观察至少两个扫描周期和首个 Cron Run 后才完成该客户发布。

## Schema 状态与 fail-closed 行为

| 本机状态 | Mapper | 普通工单 | Scheduled | 运维动作 |
|---|---|---|---|---|
| `LEGACY` | `LEGACY` | 正常 | 不可用 | 保持开关关闭；部署兼容二进制或执行 V037 |
| `V037_PARTIAL`，共享字段不完整 | `LEGACY` | 正常 | 不可用 | 完成 DDL；禁止产生 Scheduled 数据 |
| `V037_PARTIAL`，共享字段完整 | `SOURCE_AWARE` | 正常且来源隔离 | 不可用 | 补齐 Scheduled 表、约束和索引 |
| `V037_READY` | `SOURCE_AWARE` | 正常 | 仍需 enabled 与 attestation | 验证全节点后启用 |
| `INCONSISTENT` | 不提供流量 | 节点不得加入服务 | 不可用 | 修复物理定义后重启 |

请求/回调边界（HTTP、MCP、Daemon callback、WebSocket inbound、realtime 授权）在能力不可用时抛出业务错误 `30006`（“当前环境尚未完成 7×24 能力升级”），并且不访问 Scheduled Mapper。后台 Scanner 和 Run compensation **不会抛出 `30006`**；它们在 Redis 锁和 DAO 之前直接 no-op。普通 Workitem 路径不受门禁影响。

`GET /api/capabilities/scheduled-task` 只读取冻结快照，不访问 Scheduled Mapper，返回 `Result<{available,mode,clusterReady,reason}>`。`reason` 为 `DATABASE_UPGRADE_REQUIRED`、`FEATURE_DISABLED` 或 `CLUSTER_NOT_READY`；前端仅在 `available=true`、`mode=V037_READY`、`clusterReady=true`、`reason=null` 时显示入口，网络/解析失败也按不可用处理。

探测失败、元数据权限不足、结构矛盾，或共享来源字段缺失但已有 Scheduled 数据，会判为 `INCONSISTENT` 并使启动失败。不能用开关绕过。

## 指标、阈值与回退触发器

节点启动日志为 `V037 schema capability: mode=..., mapper_mode=..., scheduled_available=..., missing_count=...`。兼容性指标包括：

- `autowonder_schema_mode{mode,mapper_mode}=1`
- `scheduled_task_schema_available=0|1`
- `scheduled_task_capability_available=0|1`
- `scheduled_task_schema_not_ready_total{entry}`，其中 `entry` 只取 `http/scheduler/compensation/mcp/daemon/realtime/other`

运行指标包括：`scheduled_task_active_runs`、`scheduled_task_run_created_total{trigger_type}`、`scheduled_task_run_status_total{status,reason}`、`scheduled_task_scheduler_due_lag_seconds`、`scheduled_task_run_queue_wait_seconds`、`scheduled_task_run_duration_seconds`、`scheduled_task_resume_degraded_total{reason}`。

上线前还必须填写 Scheduled 观察窗口和阈值：

| 信号 | 上线门禁/客户批准阈值 |
|---|---|
| `scheduled_task_capability_available` | 每个 serving 节点恒为 `1` |
| `scheduled_task_schema_not_ready_total` | 观察窗口增量必须为 `0` |
| `scheduled_task_scheduler_due_lag_seconds` p95 | `${MAX_SCHEDULED_DUE_LAG_P95_SECONDS}` 秒 |
| `scheduled_task_run_queue_wait_seconds` p95 | `${MAX_SCHEDULED_QUEUE_WAIT_P95_SECONDS}` 秒 |
| `scheduled_task_run_status_total{status=FAILED/TIMED_OUT}` 占比 | `${MAX_SCHEDULED_FAILURE_RATE_PERCENT}`% |
| `scheduled_task_resume_degraded_total` 增量 | 每 `${SCHEDULED_OBSERVATION_WINDOW_MINUTES}` 分钟不超过 `${MAX_SCHEDULED_DEGRADED_COUNT}` |

出现下列任一情况立即将 Scanner 回退为 false 并按“安全关闭”逐节点发布：任一节点 capability 变为 0 或 mode 非 READY、schema-not-ready 计数增加、失败/超时率或 degraded 增量超阈值、due lag/queue wait 超阈值持续两个扫描周期、普通 Workitem SLO 超阈值、节点清单不再闭合。SRE 负责触发回退，应用 Owner 负责 Run 处置，DBA 负责 Schema 证据；阈值未填写或未批准即不得启用 Scanner。

注意：能力关闭后，`scheduled_task_active_runs` 的实现会返回 0 且不查询 DAO；这个 0 只表示能力门禁关闭，**不能证明数据库没有非终态 Run**。关闭模块前必须在能力仍开启时观察指标，并用下节的数据库查询作最终门禁。

## DDL 兼容性与性能

V037 是加法迁移，保留旧字段和旧索引。`dispatch.normalized_idempotency_key` 使用 MySQL 8.0 支持的 `GENERATED ALWAYS AS (...) STORED`、`REGEXP` 和 `CASE/CONCAT`，把 Workitem 的历史数字键规范为 `WORKITEM:<原键>`，其它键保持不变。

生成列只统一唯一性表示，不改变上述写入协议：Workitem 永久写 raw 数字键，非 Workitem 来源才写命名空间键；prefixed Workitem 仅作为兼容存量读取。

`VARCHAR(137)` 是最多 137 个字符，不是 137 字节；在 `utf8mb4` 下数据部分最多 548 字节，另有 `tenant_id` 和索引开销。`uk_dispatch_normalized_idempotency` 的职责是对 `(tenant_id, normalized_idempotency_key)` 强制唯一性，防止新旧格式绕过幂等约束；当前业务 SQL 不读取该生成列，不能把它描述为查询加速索引。

添加 STORED 生成列和唯一索引可能重建表或等待 metadata lock。之后每次相关 `dispatch` 写入会执行正则/拼接、存储生成值并维护唯一索引，增加 CPU、存储及索引写放大。必须用客户数据量演练，并依批准阈值监控锁、复制、磁盘和写延迟。

## 安全关闭与二进制回滚

所有配置变化都必须“发布配置 → 滚动重启每一个节点 → 逐节点验证”，不存在热关闭捷径。

1. 在网关/RBAC/变更窗口冻结新的 Scheduled 创建、修改、enable 和 manual Run 请求，但保持共享通用 MCP、Daemon/executor WebSocket callback 通道开放。通用 MCP tool 与 dispatch credential 均由服务端按来源解析；排空期间必须保留已有 Scheduled dispatch credential/MCP 访问，使在途 ACK/Result/评论/Artifact/Guidance 能够完成。设置 `AUTOWONDER_SCHEDULED_TASK_SCANNER_ENABLED=false`，但保持 `AUTOWONDER_SCHEDULED_TASK_ENABLED=true` 和 `AUTOWONDER_SCHEDULED_TASK_CLUSTER_READY=true`；发布并重启每一个节点。验证每个 serving UID 的配置 revision 均为 Scanner false，并观察至少两个原扫描周期没有新增 `trigger_type=SCHEDULED` Run。
2. 保持 enabled 与 attestation 为 true，使 ACK/Result、评论/Artifact/Guidance 回调和 Run compensation 继续工作。暂停所有任务定义；让正常 Run 排空，对不能完成的 `QUEUED/STARTING/WAITING_EXECUTOR/RUNNING/WAITING_HUMAN/PAUSED` Run 逐一处置。`PAUSED` 只是中间态，不算排空；最终仍必须恢复后完成或转为 `CANCELED` 等终态。Run 终态为 `SUCCEEDED/FAILED/TIMED_OUT/CANCELED/SKIPPED`；Dispatch 终态依据 `DispatchStatus.isTerminal` 只有 `SUCCEEDED/FAILED/TIMEOUT/CANCELED`，因此 `PAUSED/PAUSE_FAILED/WAITING_FOR_PAUSE` 等状态仍会阻断关闭。

   在撤销任何 credential/MCP 访问或把 enabled/attestation 设为 false **紧邻之前**，运行以下机器门禁。数据库查询独立检查 Run、Dispatch 和 Guidance，不能用“Run 已终态”推断下游已经排空。Guidance 复用历史物理字段 `(tenant_id, source_type, workitem_id)` 识别 Scheduled Run；`LEFT JOIN` 特意保留孤儿 Guidance，工作空间安全关联只使用 `r.workspace_id=g.tenant_id AND r.id=g.workitem_id`，绝不能只按可能碰撞的数字 ID 关联。

   ```bash
   set -euo pipefail
   : "${SAFE_DRAIN_ATTEMPT_ID:?missing unique drain attempt id}"
   : "${SCHEDULED_TRANSPORT_DRAIN_GATE:?missing approved platform gate}"
   : "${EXPECTED_SCHEDULED_TRANSPORT_DRAIN_GATE_SHA256:?missing gate checksum}"
   [[ "${SAFE_DRAIN_ATTEMPT_ID}" =~ ^[A-Za-z0-9._-]+$ ]]
   [[ "${SCHEDULED_TRANSPORT_DRAIN_GATE}" = /* \
      && -x "${SCHEDULED_TRANSPORT_DRAIN_GATE}" ]]
   [[ "${EXPECTED_SCHEDULED_TRANSPORT_DRAIN_GATE_SHA256}" =~ ^[0-9a-f]{64}$ ]]
   drain_sha256() {
     if command -v sha256sum >/dev/null 2>&1; then
       sha256sum "$1" | awk '{print $1}'
     else
       shasum -a 256 "$1" | awk '{print $1}'
     fi
   }
   test "$(drain_sha256 "${SCHEDULED_TRANSPORT_DRAIN_GATE}")" \
     = "${EXPECTED_SCHEDULED_TRANSPORT_DRAIN_GATE_SHA256}"
   SAFE_DRAIN_DIR="${RELEASE_DIR}/safe-drain-${SAFE_DRAIN_ATTEMPT_ID}"
   test ! -e "${SAFE_DRAIN_DIR}"
   install -d -m 700 "${SAFE_DRAIN_DIR}" "${SAFE_DRAIN_DIR}/platform-raw"

   drain_db_file="${SAFE_DRAIN_DIR}/database-pending-counts.tsv"
   "${MYSQL[@]}" --skip-column-names --connect-timeout=5 <<'SQL' \
     | tee "${drain_db_file}"
   SELECT 'nonterminal_runs', COUNT(*)
   FROM scheduled_task_run
   WHERE status NOT IN ('SUCCEEDED','FAILED','TIMED_OUT','CANCELED','SKIPPED')
   UNION ALL
   SELECT 'nonterminal_dispatches', COUNT(*)
   FROM dispatch
   WHERE source_type = 'SCHEDULED_TASK_RUN'
     AND status NOT IN ('SUCCEEDED','FAILED','TIMEOUT','CANCELED')
   UNION ALL
   SELECT 'pending_guidance', COUNT(*)
   FROM workitem_comment_delivery g
   LEFT JOIN scheduled_task_run r
     ON r.workspace_id = g.tenant_id AND r.id = g.workitem_id
   WHERE g.source_type = 'SCHEDULED_TASK_RUN'
     AND g.status IN ('QUEUED','DELIVERED');
   SQL
   awk -F '\t' 'NF != 2 || $1 == "" || $2 !~ /^[0-9]+$/ { exit 2 }
     { total += $2 } END { if (NR != 3 || total != 0) exit 3 }' \
     "${drain_db_file}"

   transport_file="${SAFE_DRAIN_DIR}/platform-transport-pending-counts.tsv"
   "${SCHEDULED_TRANSPORT_DRAIN_GATE}" \
     --customer "${CUSTOMER_ID}" --release "${RELEASE_ID}" \
     --evidence-dir "${SAFE_DRAIN_DIR}/platform-raw" --format tsv \
     | tee "${transport_file}"
   test -n "$(find "${SAFE_DRAIN_DIR}/platform-raw" -type f -print -quit)"
   awk -F '\t' '
     BEGIN { expected["executor_callback_pending"]=1; expected["outbox_pending"]=1;
             expected["transport_pending"]=1 }
     NF != 2 || !($1 in expected) || $2 !~ /^[0-9]+$/ || seen[$1] { exit 4 }
     { seen[$1]=1; unique += 1; total += $2 }
     END { if (NR != 3 || unique != 3 || total != 0) exit 5 }
   ' "${transport_file}"
   ```

   `SCHEDULED_TRANSPORT_DRAIN_GATE` 是发布平台审批并固定 SHA-256 的绝对路径命令，必须从目标客户的执行器 callback 队列、耐久 outbox 和实际 transport pending 状态读取一致快照，在 `platform-raw/` 归档队列/分区标识、watermark、采集时间和原始响应，并只向 stdout 输出上面三个固定 TSV 计数。命令不可用、超时、目标身份不一致、原始证据缺失、输出未知/重复/非整数或任一计数非 0，都必须非 0 退出并保持 shared MCP/WS/Daemon transport 与已有 Scheduled credential/source-bound 访问开放。指标采样或应用内 `scheduled_task_active_runs=0` 不能替代这些门禁。

3. 只有第 2 步数据库三项和发布平台三项门禁全部为 0 后，才撤销已排空 Run 的 Scheduled dispatch credential/source-bound 访问，并设置 `AUTOWONDER_SCHEDULED_TASK_ENABLED=false`、仍保持 attestation true；共享通用 MCP tool、MCP/WS/Daemon transport 继续为 Workitem 开放，不存在撤销 Scheduled 专用 MCP tool 的动作。发布配置、滚动重启每一个节点并逐节点验证。能力 API 应为 `available=false, mode=V037_READY, clusterReady=true, reason=FEATURE_DISABLED`，`scheduled_task_capability_available=0`。此后 `scheduled_task_active_runs=0` 不能替代第 2 步归档证据。
4. 撤销 attestation 前，发布流水线必须使用新的唯一 `SAFE_DRAIN_ATTEMPT_ID` 再执行第 2 步**完全相同**的数据库与平台命令，生成新的 `safe-drain-*` 归档并再次机器断言六项全为 0；禁止人工沿用上一次结果。全部通过后才设置 `AUTOWONDER_SCHEDULED_TASK_CLUSTER_READY=false`，发布配置、滚动重启每一个节点并验证 `clusterReady=false` 和配置 revision。共享通用 MCP/WS/Daemon transport 仍不关闭。由于 unavailable reason 优先报告 `FEATURE_DISABLED`，此时不能要求它变为 `CLUSTER_NOT_READY`。

**紧急硬关闭：** 若安全/数据完整性事故要求立即把三个开关都设为 false 并重启，Scheduled 请求/回调会收到 `30006`，Scanner 和补偿会 no-op，非终态 Run 可能被搁置。这不是正常回滚完成。恢复时只能部署 source-aware-compatible 二进制；先重新证明所有 serving 节点均为 `V037_READY/SOURCE_AWARE`，由发布平台恢复 attestation，再以 `scanner=false, enabled=true, cluster-ready=true` 重启并验证所有节点。回调/补偿恢复后按数据库记录对账、重驱或取消至非终态为 0，再重新执行上述安全关闭流程。

- 尚未存在任何 Scheduled Task/Run/Dispatch/评论/Artifact/Guidance 数据时，只有确认目标旧版本兼容加法 DDL，pre-source-type 二进制才可运行于已执行 V037 的数据库。
- 一旦存在任意上述 Scheduled 数据，只能回滚到仍识别 `source_type`、使用 source-aware 查询和能力门禁的二进制；pre-source-type 二进制永久禁止。不要删除 `scheduled_task*` 数据、来源列、生成列或索引来“回滚”。

## 发布门禁

```bash
AUTOWONDER_DOCKER_RELEASE_GATE=true ./scripts/verify-v037-docker-gates.sh
```

当前门禁覆盖 8 个真实 MySQL/Redis 测试类。测试总数由脚本根据当次新生成的 Surefire 报告动态汇总；成功输出必须满足 `tests>0 failures=0 errors=0 skipped=0`，不得用历史固定数量代替当次报告。任何 0 tests、failure、error、skip、Docker 不可达或未显式 opt-in 都不是通过。

```bash
mvn -DskipFrontend=true -DskipGitCommitId=true \
  -Dtest=ScheduledTaskEndToEndTest,ScheduledTaskConcurrencyTest,ScheduledTaskSpringMybatisIntegrationTest test
```
