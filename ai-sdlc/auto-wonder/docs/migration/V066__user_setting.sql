-- 用户级偏好配置（全局表，与 `user` 同域，无 workspace 隔离）。
-- 请在发布代码前手动执行；不会自动执行迁移。
-- 首个使用方是需求澄清面板的发送方式偏好（setting_key = 'clarification_send_mode'），
-- 表结构保持通用 key-value，后续任意用户级偏好复用同一张表，不再新增列。
CREATE TABLE IF NOT EXISTS `user_setting` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id`      BIGINT UNSIGNED NOT NULL COMMENT '归属用户（user.id）',
  `setting_key`  VARCHAR(128)    NOT NULL COMMENT '配置键，例如 clarification_send_mode',
  `value_json`   JSON            DEFAULT NULL COMMENT '配置值（JSON，支持简单值与复杂结构）',
  `gmt_create`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `gmt_modified` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `creator_id`   BIGINT UNSIGNED DEFAULT NULL,
  `modifier_id`  BIGINT UNSIGNED DEFAULT NULL,
  `is_deleted`   TINYINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_setting` (`user_id`, `setting_key`),
  KEY `idx_user_setting_user` (`user_id`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='用户级偏好配置（全局）';
