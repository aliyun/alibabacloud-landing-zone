-- Workspace environment-variable library. Values are always encrypted before persistence.
CREATE TABLE IF NOT EXISTS `environment_variable` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id`      BIGINT UNSIGNED NOT NULL,
  `name`           VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `credential_ref` TEXT            NOT NULL COMMENT 'SecretCrypto 密文引用；禁止返回客户端或写入日志',
  `description`    VARCHAR(512)    DEFAULT NULL,
  `gmt_create`     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `gmt_modified`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `creator_id`     BIGINT UNSIGNED DEFAULT NULL,
  `modifier_id`    BIGINT UNSIGNED DEFAULT NULL,
  `is_deleted`     BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `version`        INT             NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_environment_variable_name` (`tenant_id`, `name`, `is_deleted`),
  KEY `idx_environment_variable_tenant` (`tenant_id`, `is_deleted`, `name`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='工作空间环境变量库';

CREATE TABLE IF NOT EXISTS `agent_environment_variable_ref` (
  `id`                      BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id`               BIGINT UNSIGNED NOT NULL,
  `agent_version_id`        BIGINT UNSIGNED NOT NULL,
  `environment_variable_id` BIGINT UNSIGNED NOT NULL,
  `gmt_create`              DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_environment_variable` (`tenant_id`, `agent_version_id`, `environment_variable_id`),
  KEY `idx_environment_variable_ref` (`tenant_id`, `environment_variable_id`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='数字员工版本-环境变量引用';
