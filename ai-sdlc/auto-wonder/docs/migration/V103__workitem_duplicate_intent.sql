-- 56680 重复工单检测：全局功能开关与检测任务表。
-- 均为加法变更；缺设置等价关闭，不回扫历史工单。
CREATE TABLE IF NOT EXISTS `platform_feature_switch` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `switch_key`   VARCHAR(64)     NOT NULL COMMENT '固定语义键，例如 duplicateWorkitemIntentEnabled',
  `enabled`      TINYINT         NOT NULL DEFAULT 0,
  `modifier_id`  BIGINT UNSIGNED DEFAULT NULL,
  `gmt_create`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `gmt_modified` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `version`      INT             NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_feature_switch_key` (`switch_key`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='平台级功能开关';

CREATE TABLE IF NOT EXISTS `workitem_duplicate_check` (
  `id`                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id`          BIGINT UNSIGNED NOT NULL,
  `operator_id`        BIGINT UNSIGNED NOT NULL COMMENT '发起检测的操作者，也是通知接收人',
  `workitem_id`        BIGINT UNSIGNED DEFAULT NULL COMMENT 'NULL 表示尚未提交的草稿预检',
  `form_request_id`    VARCHAR(64)     DEFAULT NULL COMMENT '前端表单会话标识，用于请求去重',
  `title`              VARCHAR(200)    NOT NULL,
  `title_fingerprint`  CHAR(64)        NOT NULL COMMENT '标题归一化后的 SHA-256',
  `exclude_workitem_id` BIGINT UNSIGNED DEFAULT NULL,
  `candidate_since`    DATETIME(3)     NOT NULL COMMENT '候选窗口起点，创建时固定',
  `candidate_total`    INT             NOT NULL DEFAULT 0,
  `next_batch_index`   INT             NOT NULL DEFAULT 0,
  `request_id`         VARCHAR(64)     DEFAULT NULL COMMENT '当前批次的 Chief 调用标识',
  `status`             VARCHAR(16)     NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/SUCCEEDED/FAILED/CANCELED/SKIPPED',
  `match_json`         TEXT            DEFAULT NULL COMMENT '[{"id":..,"reason":..}]',
  `error`              VARCHAR(128)    DEFAULT NULL,
  `deadline_at`        DATETIME(3)     DEFAULT NULL,
  `lease_until`        DATETIME(3)     DEFAULT NULL,
  `attempt`            INT             NOT NULL DEFAULT 0,
  `gmt_create`         DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `gmt_modified`       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_duplicate_check_scan` (`status`, `deadline_at`, `id`),
  KEY `idx_duplicate_check_owner` (`tenant_id`, `operator_id`, `form_request_id`, `title_fingerprint`),
  KEY `idx_duplicate_check_workitem` (`tenant_id`, `workitem_id`, `status`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='重复工单检测任务';
