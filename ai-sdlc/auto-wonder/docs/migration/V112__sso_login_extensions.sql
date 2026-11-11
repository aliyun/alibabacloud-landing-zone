-- 企业 SSO 登录能力扩展（工单 #57613）：默认登录、注册开关、首次登录自动建号、飞书用户 ID 映射。
-- 存量数据库执行一次本迁移；新建数据库使用 docs/autowonder-schema.sql。
-- 不会自动执行迁移：请在发布代码前手动执行；先迁移、再发布服务端与前端。
--
-- 模型：
--   platform_auth_config   平台级认证配置单行记录（id 固定 1）：
--                          registration_enabled 控制公开注册；default_provider_id 为全平台唯一默认连接。
--                          记录缺失时应用按「注册开放、无默认连接」处理，脚本预置一行等价默认值。
--   federation_connection  新增 auto_provision（首次登录自动建号，默认关闭）与
--                          feishu_user_id_claim（认证结果中飞书 user_id 的属性名，空表示不同步）。
--
-- 重复执行：CREATE TABLE 带 IF NOT EXISTS；加列前先查 information_schema，可安全重跑。

CREATE TABLE IF NOT EXISTS `platform_auth_config` (
  `id` TINYINT NOT NULL COMMENT '固定为 1：平台单行配置',
  `registration_enabled` TINYINT NOT NULL DEFAULT 1 COMMENT '1 允许公开注册 / 0 关闭注册入口与接口',
  `default_provider_id` BIGINT DEFAULT NULL COMMENT '全平台唯一默认 SSO 连接（federation_connection.id）',
  `gmt_create` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `gmt_modified` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='平台认证配置（注册开关与默认登录连接）';

INSERT IGNORE INTO `platform_auth_config` (`id`, `registration_enabled`, `default_provider_id`)
VALUES (1, 1, NULL);

SET @ddl = (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `federation_connection` ADD COLUMN `auto_provision` TINYINT NOT NULL DEFAULT 0 COMMENT ''0 关闭 / 1 首次登录自动创建内部普通账号'' AFTER `email_claim`',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'federation_connection' AND COLUMN_NAME = 'auto_provision'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `federation_connection` ADD COLUMN `feishu_user_id_claim` VARCHAR(128) DEFAULT NULL COMMENT ''认证结果中飞书 user_id 的属性名；空为不同步'' AFTER `auto_provision`',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'federation_connection' AND COLUMN_NAME = 'feishu_user_id_claim'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
