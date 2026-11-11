-- Chief 引导式工作空间初始化：引导草稿、稳定步骤、冻结快照、幂等批次与只读就绪检查。
-- 所有表都以 tenant_id 隔离；引导草稿与批次只在同一空间内可见。

CREATE TABLE IF NOT EXISTS `workspace_onboarding` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `tenant_id` BIGINT NOT NULL,
    `workspace_id` BIGINT NOT NULL,
    `owner_user_id` BIGINT NOT NULL,
    `template_key` VARCHAR(64) DEFAULT NULL,
    `template_version` VARCHAR(32) DEFAULT NULL,
    `template_sha256` CHAR(64) DEFAULT NULL,
    `stage` VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    `paused` TINYINT NOT NULL DEFAULT 0,
    `config_version` INT NOT NULL DEFAULT 0,
    `steps_total` INT NOT NULL DEFAULT 0,
    `steps_estimated` TINYINT NOT NULL DEFAULT 1,
    `current_step_index` INT NOT NULL DEFAULT 0,
    `request_key` VARCHAR(128) DEFAULT NULL,
    `draft_json` MEDIUMTEXT,
    `version` INT NOT NULL DEFAULT 0,
    `creator_id` BIGINT DEFAULT NULL,
    `modifier_id` BIGINT DEFAULT NULL,
    `gmt_create` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `gmt_modified` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_onboarding_request` (`tenant_id`, `owner_user_id`, `request_key`),
    KEY `idx_onboarding_owner` (`tenant_id`, `workspace_id`, `owner_user_id`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='工作空间初始化引导草稿';

CREATE TABLE IF NOT EXISTS `workspace_onboarding_step` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `tenant_id` BIGINT NOT NULL,
    `onboarding_id` BIGINT NOT NULL,
    `step_key` VARCHAR(160) NOT NULL,
    `name` VARCHAR(255) NOT NULL,
    `kind` VARCHAR(32) NOT NULL,
    `step_order` INT NOT NULL,
    `status` VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    `reason` VARCHAR(512) DEFAULT NULL,
    `evidence` VARCHAR(1024) DEFAULT NULL,
    `version` INT NOT NULL DEFAULT 0,
    `gmt_create` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `gmt_modified` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_onboarding_step` (`tenant_id`, `onboarding_id`, `step_key`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='引导步骤稳定ID与真实完成状态';

CREATE TABLE IF NOT EXISTS `harness_snapshot` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `tenant_id` BIGINT NOT NULL,
    `workspace_id` BIGINT NOT NULL,
    `onboarding_id` BIGINT NOT NULL,
    `snapshot_type` VARCHAR(24) NOT NULL,
    `onboarding_version` INT NOT NULL,
    `template_key` VARCHAR(64) DEFAULT NULL,
    `template_version` VARCHAR(32) DEFAULT NULL,
    `template_sha256` CHAR(64) DEFAULT NULL,
    `content_json` MEDIUMTEXT NOT NULL,
    `content_sha256` CHAR(64) NOT NULL,
    `expires_at` DATETIME NOT NULL,
    `consumed_at` DATETIME DEFAULT NULL,
    `creator_id` BIGINT DEFAULT NULL,
    `gmt_create` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_snapshot_onboarding` (`tenant_id`, `onboarding_id`, `snapshot_type`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='初始化/配置预检冻结快照（30分钟）';

CREATE TABLE IF NOT EXISTS `harness_run` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `tenant_id` BIGINT NOT NULL,
    `workspace_id` BIGINT NOT NULL,
    `onboarding_id` BIGINT NOT NULL,
    `snapshot_id` BIGINT NOT NULL,
    `run_type` VARCHAR(24) NOT NULL,
    `status` VARCHAR(16) NOT NULL,
    `result_json` MEDIUMTEXT,
    `failure_json` TEXT,
    `version` INT NOT NULL DEFAULT 0,
    `creator_id` BIGINT DEFAULT NULL,
    `gmt_create` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `gmt_modified` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_harness_run_snapshot` (`tenant_id`, `snapshot_id`),
    KEY `idx_harness_run_onboarding` (`tenant_id`, `onboarding_id`, `run_type`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='初始化/配置执行批次，一个快照唯一一个批次';

CREATE TABLE IF NOT EXISTS `harness_run_step` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `tenant_id` BIGINT NOT NULL,
    `run_id` BIGINT NOT NULL,
    `step_no` INT NOT NULL,
    `object_key` VARCHAR(200) NOT NULL,
    `object_type` VARCHAR(32) NOT NULL,
    `status` VARCHAR(16) NOT NULL,
    `object_id` BIGINT DEFAULT NULL,
    `error_message` VARCHAR(1024) DEFAULT NULL,
    `gmt_create` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `gmt_modified` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_harness_run_step` (`tenant_id`, `run_id`, `object_key`),
    KEY `idx_harness_run_step_object` (`tenant_id`, `object_key`, `status`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='批次内对象级幂等记录';

CREATE TABLE IF NOT EXISTS `workspace_readiness_check` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `tenant_id` BIGINT NOT NULL,
    `workspace_id` BIGINT NOT NULL,
    `onboarding_id` BIGINT NOT NULL,
    `config_version` INT NOT NULL,
    `status` VARCHAR(16) NOT NULL,
    `evidence_json` MEDIUMTEXT,
    `checked_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `creator_id` BIGINT DEFAULT NULL,
    `gmt_create` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_readiness_onboarding` (`tenant_id`, `onboarding_id`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='只读就绪检查结果与证据';
