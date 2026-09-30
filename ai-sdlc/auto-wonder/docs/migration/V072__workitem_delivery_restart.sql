-- 工单交付重启操作表：每次用户发起的重启一个持久化行（幂等轮次 + 处理进度 + 审计字段）。
-- restart_round 对 (tenant_id, workitem_id) 唯一递增：再次主动重新指派分配下一轮；
-- restart_token 对 (tenant_id, workitem_id) 唯一（可空）：同一请求的重复提交/网络重试
-- 映射到同一轮次，不产生重复交付。
-- 持久化该轮次的 SDLC 入口步骤与数字人，供 STOPPING_OLD 轮次在停止确认回调
-- 或服务重启后的补偿扫描中继续启动，避免依赖可能已被其他流转改写的工单当前步骤。
CREATE TABLE IF NOT EXISTS `workitem_delivery_restart` (
  `id`                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id`          BIGINT UNSIGNED NOT NULL,
  `workitem_id`        BIGINT UNSIGNED NOT NULL,
  `restart_round`      INT UNSIGNED    NOT NULL,
  `restart_token`      VARCHAR(64)     NULL,
  `requested_by`       BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `operator_type`      VARCHAR(16)     NOT NULL DEFAULT 'SYSTEM',
  `scheduled_start_at` DATETIME(3)     NULL,
  `sdlc_step_id`       BIGINT UNSIGNED NULL COMMENT '重启轮次要启动的 SDLC 入口步骤',
  `agent_id`           BIGINT UNSIGNED NULL COMMENT '重启轮次要派发的数字人',
  `status`             VARCHAR(32)     NOT NULL DEFAULT 'STARTING' COMMENT 'STARTING/STOPPING_OLD/STARTED/FAILED',
  `stop_reason`        VARCHAR(512)    NULL,
  `dispatch_id`        BIGINT UNSIGNED NULL COMMENT '新正式交付派发 id',
  `gmt_create`         DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `gmt_modified`       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_workitem_restart_round` (`tenant_id`, `workitem_id`, `restart_round`),
  UNIQUE KEY `uk_workitem_restart_token` (`tenant_id`, `workitem_id`, `restart_token`),
  KEY `idx_workitem_restart_recent` (`tenant_id`, `workitem_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工单交付重启操作（统一重新指派语义）';
