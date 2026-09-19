-- 小队级 debug 日志自动收集。
--
-- 1) squad.debug_log_enabled：小队级开关，小队管理页切换；
-- 2) dispatch.debug_log_enabled：派发打包时冻结（agent 属于任一开启小队即 1），
--    debug-log-upload 签发端点据此校验，不重查小队（开关中途变更不影响已创建的 dispatch，
--    即「下一轮生效」语义）；
-- 3) debug_log：一轮 dispatch 一行，uk_dispatch 唯一键保证重复申请/重复上报幂等。
--
-- 部署顺序：必须先应用本迁移，再发布新代码（硬门槛）。
-- squad.debug_log_enabled 各方言（legacy 与 source-aware）均引用；
-- dispatch.debug_log_enabled 仅 source-aware 方言读写（legacy 下自动关闭）。
-- 若先发代码后迁移，影响面是「调度链路整体不可用」，不止 squad 编辑：
--   source-aware 方言的 dispatch 列清单（DispatchDao.xml 的 cols 片段）已引用 debug_log_enabled，
--   而该 cols 被 findById/findByIdempotencyKey/listStuck/listByTenant/listOldestPendingByAgent 等
--   无 databaseId 的语句共用 → 生产（source-aware）下所有 dispatch SELECT 报 Unknown column，
--   派发、工单详情的派发列表、恢复链路一并失败；squad 编辑同样失败。
--   V037SchemaCapabilityDetector 只按 V037 列契约探测，不认识 debug_log_enabled，
--   缺本迁移时仍判 SOURCE_AWARE，不会自动降级到 legacy 兜底。
-- 重复执行风险：下面两条 ALTER ADD COLUMN 无 information_schema 守卫，重复跑报 1060 Duplicate column；
--   debug_log 建表带 IF NOT EXISTS 可重入。客户环境常无 schema 迁移历史，执行前先探测：
--   SELECT COUNT(*) FROM information_schema.COLUMNS
--     WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='squad' AND COLUMN_NAME='debug_log_enabled';
--   为 0 才执行 ALTER；已为 1 则跳过对应 ALTER，只补建 debug_log 表。

ALTER TABLE `squad`
  ADD COLUMN `debug_log_enabled` TINYINT NOT NULL DEFAULT 0 COMMENT '小队级 debug 日志收集开关' AFTER `status`;

ALTER TABLE `dispatch`
  ADD COLUMN `debug_log_enabled` TINYINT NOT NULL DEFAULT 0 COMMENT '打包时冻结：本轮是否收集全量 debug 日志' AFTER `resume_mode`;

CREATE TABLE IF NOT EXISTS `debug_log` (
  `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id`        BIGINT UNSIGNED NOT NULL,
  `source_type`      VARCHAR(32)     NOT NULL COMMENT 'WORKITEM / SCHEDULED_TASK_RUN',
  `source_id`        BIGINT UNSIGNED NOT NULL COMMENT 'workitemId 或 scheduledTaskRunId',
  `dispatch_id`      BIGINT UNSIGNED NOT NULL,
  `agent_id`         BIGINT UNSIGNED NOT NULL,
  `agent_version_id` BIGINT UNSIGNED DEFAULT NULL,
  `run_no`           INT             NOT NULL COMMENT '该 source 下该 agent 的第 n 轮',
  `dispatch_status`  VARCHAR(32)     NOT NULL COMMENT 'SUCCEEDED / FAILED / TIMEOUT / CANCELED',
  `object_key`       VARCHAR(512)    NOT NULL COMMENT 'debug/{workitemId}/{roleCode}-run-{n}.log.gz 或 debug/scheduled-{scheduledTaskId}-run-{runId}/{roleCode}-run-{n}.log.gz',
  `size_bytes`       BIGINT          DEFAULT NULL COMMENT 'gzip 后实际大小',
  `sha256`           VARCHAR(80)     DEFAULT NULL COMMENT '裸 64 位 hex；可能吸收 sha256: 前缀形态',
  `truncated`        TINYINT         NOT NULL DEFAULT 0,
  `upload_channel`   VARCHAR(16)     DEFAULT NULL COMMENT 'DIRECT / RELAY',
  `status`           VARCHAR(16)     NOT NULL COMMENT 'PENDING / UPLOADED / FAILED',
  `error_message`    VARCHAR(1024)   DEFAULT NULL,
  `gmt_create`       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `gmt_modified`     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dispatch` (`dispatch_id`),
  UNIQUE KEY `uk_source_agent_run` (`source_type`, `source_id`, `agent_id`, `run_no`),
  KEY `idx_source` (`tenant_id`, `source_type`, `source_id`),
  KEY `idx_agent` (`tenant_id`, `agent_id`, `gmt_create`),
  KEY `idx_pending_reconcile` (`status`, `gmt_modified`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='小队 debug 日志登记';
