CREATE TABLE IF NOT EXISTS `scheduled_task` (
  `id`                       BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `workspace_id`                BIGINT UNSIGNED NOT NULL,
  `name`                     VARCHAR(256)    NOT NULL,
  `instruction_md`           MEDIUMTEXT      NOT NULL,
  `squad_id`                 BIGINT UNSIGNED NOT NULL,
  `initial_agent_id`         BIGINT UNSIGNED NOT NULL,
  `schedule_type`            VARCHAR(16)     NOT NULL COMMENT 'ONCE/CRON',
  `run_at`                   DATETIME(3)     DEFAULT NULL COMMENT 'ONCE UTC instant',
  `cron_expression`          VARCHAR(128)    DEFAULT NULL COMMENT 'Canonical six-field Cron',
  `timezone`                 VARCHAR(64)     NOT NULL COMMENT 'IANA timezone',
  `session_mode`             VARCHAR(16)     NOT NULL DEFAULT 'ISOLATED' COMMENT 'ISOLATED/CONTINUOUS',
  `overlap_policy`           VARCHAR(16)     NOT NULL DEFAULT 'SKIP' COMMENT 'SKIP/QUEUE/ALLOW',
  `misfire_policy`           VARCHAR(16)     NOT NULL DEFAULT 'FIRE_LATEST' COMMENT 'FIRE_LATEST/FIRE_ALL/SKIP_ALL',
  `start_deadline_seconds`   INT             NOT NULL DEFAULT 21600,
  `affinity_timeout_seconds` INT             NOT NULL DEFAULT 1800,
  `status`                   VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/PAUSED/EXHAUSTED/ARCHIVED',
  `next_fire_at`             DATETIME(3)     DEFAULT NULL COMMENT 'UTC scheduling cursor',
  `last_fire_at`             DATETIME(3)     DEFAULT NULL COMMENT 'Last claimed scheduled instant in UTC',
  `gmt_create`               DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `gmt_modified`             DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `creator_id`               BIGINT UNSIGNED NOT NULL COMMENT 'Task owner',
  `modifier_id`              BIGINT UNSIGNED DEFAULT NULL,
  `is_deleted`               TINYINT         NOT NULL DEFAULT 0,
  `version`                  INT             NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_scheduled_task_due` (`status`, `is_deleted`, `next_fire_at`, `id`),
  KEY `idx_scheduled_task_owner` (`workspace_id`, `creator_id`, `status`, `id`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='7x24 scheduled task definition';

CREATE TABLE IF NOT EXISTS `scheduled_task_run` (
  `id`                      BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `workspace_id`               BIGINT UNSIGNED NOT NULL,
  `scheduled_task_id`       BIGINT UNSIGNED NOT NULL,
  `trigger_key`             VARCHAR(256)    NOT NULL,
  `trigger_type`            VARCHAR(16)     NOT NULL COMMENT 'SCHEDULED/MANUAL/MISFIRE',
  `scheduled_at`            DATETIME(3)     NOT NULL COMMENT 'Planned UTC instant',
  `started_at`              DATETIME(3)     DEFAULT NULL,
  `finished_at`             DATETIME(3)     DEFAULT NULL,
  `status`                  VARCHAR(32)     NOT NULL COMMENT 'QUEUED/STARTING/WAITING_EXECUTOR/RUNNING/WAITING_HUMAN/PAUSED/SUCCEEDED/FAILED/TIMED_OUT/CANCELED/SKIPPED',
  `skip_reason`             VARCHAR(32)     DEFAULT NULL COMMENT 'OVERLAP/MISFIRE_POLICY/START_DEADLINE',
  `squad_id`                BIGINT UNSIGNED NOT NULL,
  `initial_agent_id`        BIGINT UNSIGNED NOT NULL,
  `current_agent_id`        BIGINT UNSIGNED DEFAULT NULL,
  `sdlc_id`                 BIGINT UNSIGNED DEFAULT NULL,
  `current_step_id`         BIGINT UNSIGNED DEFAULT NULL,
  `session_mode`            VARCHAR(16)     NOT NULL COMMENT 'ISOLATED/CONTINUOUS',
  `resume_from_run_id`      BIGINT UNSIGNED DEFAULT NULL,
  `degraded_resume`         TINYINT         NOT NULL DEFAULT 0,
  `degraded_reason`         VARCHAR(512)    DEFAULT NULL,
  `execution_snapshot_json` JSON            NOT NULL,
  `result_summary`          MEDIUMTEXT      DEFAULT NULL,
  `error`                   VARCHAR(1024)   DEFAULT NULL,
  `owner_id`                BIGINT UNSIGNED NOT NULL,
  `gmt_create`              DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `gmt_modified`            DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `creator_id`              BIGINT UNSIGNED NOT NULL,
  `modifier_id`             BIGINT UNSIGNED DEFAULT NULL,
  `version`                 INT             NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_scheduled_task_trigger` (`workspace_id`, `trigger_key`),
  KEY `idx_scheduled_task_run_task` (`workspace_id`, `scheduled_task_id`, `id`),
  KEY `idx_scheduled_task_run_status` (`workspace_id`, `status`, `scheduled_at`, `id`),
  KEY `idx_scheduled_task_run_resume` (`workspace_id`, `resume_from_run_id`),
  KEY `idx_scheduled_task_run_recovery` (`status`, `gmt_modified`, `id`),
  KEY `idx_scheduled_task_run_queue` (`workspace_id`, `scheduled_task_id`, `status`, `scheduled_at`, `id`)
  ,KEY `idx_scheduled_task_run_health` (`workspace_id`, `scheduled_task_id`, `finished_at`, `status`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='7x24 scheduled task execution occurrence';

ALTER TABLE `dispatch`
  ADD COLUMN `source_type` VARCHAR(32) NOT NULL DEFAULT 'WORKITEM' AFTER `tenant_id`,
  ADD COLUMN `normalized_idempotency_key` VARCHAR(137) GENERATED ALWAYS AS (
    CASE
      WHEN source_type = 'WORKITEM'
        AND idempotency_key REGEXP '^[0-9]+:[0-9]+:[0-9]+$'
        THEN CONCAT('WORKITEM:', idempotency_key)
      ELSE idempotency_key
    END
  ) STORED AFTER `idempotency_key`,
  ADD UNIQUE KEY `uk_dispatch_normalized_idempotency` (`tenant_id`, `normalized_idempotency_key`),
  ADD KEY `idx_dispatch_source` (`tenant_id`, `source_type`, `workitem_id`, `id`);

ALTER TABLE `artifact`
  ADD COLUMN `source_type` VARCHAR(32) NOT NULL DEFAULT 'WORKITEM' AFTER `tenant_id`,
  ADD KEY `idx_artifact_source` (`tenant_id`, `source_type`, `workitem_id`, `id`);

ALTER TABLE `workitem_comment`
  ADD COLUMN `source_type` VARCHAR(32) NOT NULL DEFAULT 'WORKITEM' AFTER `tenant_id`,
  ADD KEY `idx_comment_source` (`tenant_id`, `source_type`, `workitem_id`, `id`);

ALTER TABLE `workitem_comment_mention`
  ADD COLUMN `source_type` VARCHAR(32) NOT NULL DEFAULT 'WORKITEM' AFTER `tenant_id`,
  ADD KEY `idx_mention_source` (`tenant_id`, `source_type`, `workitem_id`, `id`);

ALTER TABLE `workitem_comment_delivery`
  ADD COLUMN `source_type` VARCHAR(32) NOT NULL DEFAULT 'WORKITEM' AFTER `tenant_id`,
  ADD KEY `idx_delivery_source` (`tenant_id`, `source_type`, `workitem_id`, `id`);

ALTER TABLE `workitem`
  ADD COLUMN `origin_type` VARCHAR(32) DEFAULT NULL,
  ADD COLUMN `origin_id` BIGINT UNSIGNED DEFAULT NULL,
  ADD KEY `idx_workitem_origin` (`tenant_id`, `origin_type`, `origin_id`);
