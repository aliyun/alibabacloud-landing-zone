-- 自助注册审核与空间创建权限（工单 #58158）。
-- 存量数据库执行一次本迁移；新建数据库使用 docs/autowonder-schema.sql。
-- 不会自动执行迁移：请在发布代码前手动执行；先迁移、再发布服务端与前端。
--
-- 模型：
--   user.status           增加取值 2 = 待审核（0 正常、1 停用/注销）；不回填历史账号。
--   user.workspace_creation_granted  平台级个人空间创建授权位，缺省 0；已有与新用户均适用。
--   notification.scope    WORKSPACE（既有行为，缺省）或 PLATFORM；平台级通知 tenant_id 固定 0。
--   workspace_creation_request  空间创建权限申请；pending_marker 唯一键保证同一用户仅一条待处理申请，
--                         处理完成后置 NULL，允许日后撤权再次申请。
--
-- 重复执行：加列前先查 information_schema；CREATE TABLE 带 IF NOT EXISTS，可安全重跑。

SET @ddl = (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `user` ADD COLUMN `workspace_creation_granted` TINYINT NOT NULL DEFAULT 0 COMMENT ''0 未单独授权 / 1 平台管理员已授权创建空间'' AFTER `is_admin`',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user' AND COLUMN_NAME = 'workspace_creation_granted'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `notification` ADD COLUMN `scope` VARCHAR(16) NOT NULL DEFAULT ''WORKSPACE'' COMMENT ''WORKSPACE 空间内通知 / PLATFORM 平台级通知'' AFTER `tenant_id`',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'notification' AND COLUMN_NAME = 'scope'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `notification` ADD KEY `idx_recipient_scope` (`recipient_id`, `scope`, `status`, `gmt_create`)',
    'SELECT 1')
  FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'notification' AND INDEX_NAME = 'idx_recipient_scope'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS `workspace_creation_request` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id`        BIGINT UNSIGNED NOT NULL COMMENT '申请人',
  `status`         VARCHAR(16)     NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / FULFILLED',
  `pending_marker` TINYINT         DEFAULT 1 COMMENT '待处理为 1，处理后置 NULL；唯一键限同一用户一条待处理',
  `gmt_create`     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `gmt_modified`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_pending` (`user_id`, `pending_marker`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='空间创建权限申请';
