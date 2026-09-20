-- Execute before deploying Feishu channel support; existing conversation tables are reused.
CREATE TABLE IF NOT EXISTS `feishu_robot_binding` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id` BIGINT NOT NULL,
  `app_id` VARCHAR(128) NOT NULL,
  `credential_ref` TEXT NOT NULL COMMENT 'SecretCrypto encrypted App Secret / Verification Token / Encrypt Key JSON',
  `agent_id` BIGINT NOT NULL,
  `status` VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
  `last_success_at` DATETIME NULL,
  `last_error` VARCHAR(1024) NULL,
  `creator_id` BIGINT NULL,
  `modifier_id` BIGINT NULL,
  `version` INT NOT NULL DEFAULT 0,
  `gmt_create` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `gmt_modified` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_feishu_app` (`app_id`),
  KEY `idx_feishu_tenant_agent` (`tenant_id`, `agent_id`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='飞书企业自建应用绑定';

CREATE TABLE IF NOT EXISTS `feishu_message_inbox` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `binding_id` BIGINT NOT NULL,
  `tenant_id` BIGINT NOT NULL,
  `agent_id` BIGINT NOT NULL COMMENT 'Agent at receipt time; never reroute after a binding change',
  `message_id` VARCHAR(128) NOT NULL,
  `payload` MEDIUMTEXT NOT NULL,
  `status` VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  `attempts` INT NOT NULL DEFAULT 0,
  `available_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `last_error` VARCHAR(1024) NULL,
  `gmt_create` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_feishu_inbound` (`binding_id`, `message_id`),
  KEY `idx_feishu_inbox_poll` (`status`, `available_at`, `id`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='飞书回调持久化收件箱';
