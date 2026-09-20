-- 执行器一键更新/自动升级：升级任务表
-- （自动升级开关是全局且不需要运行时变更的部署配置，放在 application.yml 的 autowonder.runtime 下，不落库）
-- （执行器上报版本是 Redis presence 状态（exec:version:{id}，TTL 90s，心跳续期），不落库）
CREATE TABLE IF NOT EXISTS `executor_update_task` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id`       BIGINT UNSIGNED NOT NULL,
  `executor_id`     BIGINT UNSIGNED NOT NULL COMMENT '目标执行器',
  `request_id`      VARCHAR(64)     NOT NULL COMMENT '升级指令关联 ID，客户端幂等键',
  `current_version` VARCHAR(64)     DEFAULT NULL COMMENT '发起时执行器最近上报版本',
  `target_version`  VARCHAR(64)     NOT NULL COMMENT '目标版本（全局推荐版本）',
  `status`          VARCHAR(16)     NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/DRAINING/UPDATING/SUCCESS/FAILED',
  `attempt_count`   INT             NOT NULL DEFAULT 0 COMMENT '已执行的升级尝试次数',
  `max_attempts`    INT             NOT NULL DEFAULT 3,
  `next_attempt_at` DATETIME(3)     DEFAULT NULL COMMENT '本次尝试的截止时间；到期仍未收到客户端上报即视为一次失败',
  `delivered_at`    DATETIME(3)     DEFAULT NULL COMMENT '最近一次下发升级指令的时间；NULL 表示还在等待下发',
  `last_error`      VARCHAR(1024)   DEFAULT NULL COMMENT '最近一次失败原因',
  `source`          VARCHAR(16)     NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL/BATCH/AUTO',
  `requested_by`    BIGINT UNSIGNED DEFAULT NULL,
  `requested_at`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `completed_at`    DATETIME(3)     DEFAULT NULL,
  `gmt_create`      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `gmt_modified`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `creator_id`      BIGINT UNSIGNED DEFAULT NULL,
  `modifier_id`     BIGINT UNSIGNED DEFAULT NULL,
  `is_deleted`      TINYINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_request` (`request_id`),
  KEY `idx_executor` (`tenant_id`, `executor_id`, `is_deleted`),
  KEY `idx_status_next_attempt` (`status`, `next_attempt_at`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='执行器升级任务';
