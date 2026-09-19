-- 项目配置备份历史。请在发布代码前手动执行；不会自动执行迁移。
CREATE TABLE IF NOT EXISTS `project_backup` (
  `id` CHAR(36) NOT NULL,
  `tenant_id` BIGINT NOT NULL,
  `creator_id` BIGINT NOT NULL,
  `format_version` INT NOT NULL,
  `status` VARCHAR(16) NOT NULL COMMENT 'RUNNING / SUCCEEDED / FAILED',
  `oss_ref` VARCHAR(1024) NULL,
  `size_bytes` BIGINT NULL,
  `sha256` CHAR(64) NULL,
  `error_message` VARCHAR(512) NULL,
  `gmt_create` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `gmt_finished` DATETIME(3) NULL,
  PRIMARY KEY (`id`),
  KEY `idx_project_backup_tenant_created` (`tenant_id`, `gmt_create`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目配置备份历史';
