-- V100: physically remove the retired group-confirmation columns from both binding tables.
--
-- Maintenance window (see docs/operations guidance): back up both binding tables, stop every
-- old web/consumer instance, deploy the version that no longer reads these columns, execute this
-- script, verify, then restore traffic. Never run it while an old binary is still writing:
-- dropping the columns is forward-only and an old binary cannot start against the new schema.
--
-- This script is idempotent: each column is dropped only while it still exists, and it never
-- recreates a dropped column. Binding ids, tenant/agent links, group ids, platform_config_id,
-- status, route_version and all routing data are untouched. Application startup does not add the
-- columns back.

SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dingtalk_robot_binding'
      AND COLUMN_NAME = 'confirmed_at') > 0,
  'ALTER TABLE `dingtalk_robot_binding` DROP COLUMN `confirmed_at`',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dingtalk_robot_binding'
      AND COLUMN_NAME = 'challenge_hash') > 0,
  'ALTER TABLE `dingtalk_robot_binding` DROP COLUMN `challenge_hash`',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dingtalk_robot_binding'
      AND COLUMN_NAME = 'challenge_expires_at') > 0,
  'ALTER TABLE `dingtalk_robot_binding` DROP COLUMN `challenge_expires_at`',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'feishu_robot_binding'
      AND COLUMN_NAME = 'confirmed_at') > 0,
  'ALTER TABLE `feishu_robot_binding` DROP COLUMN `confirmed_at`',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'feishu_robot_binding'
      AND COLUMN_NAME = 'challenge_hash') > 0,
  'ALTER TABLE `feishu_robot_binding` DROP COLUMN `challenge_hash`',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'feishu_robot_binding'
      AND COLUMN_NAME = 'challenge_expires_at') > 0,
  'ALTER TABLE `feishu_robot_binding` DROP COLUMN `challenge_expires_at`',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Route-version comments no longer mention confirmation (skipped when already migrated).
SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dingtalk_robot_binding'
      AND COLUMN_NAME = 'route_version' AND COLUMN_COMMENT LIKE '%确认%') > 0,
  'ALTER TABLE `dingtalk_robot_binding` MODIFY COLUMN `route_version` BIGINT NOT NULL DEFAULT 0 COMMENT ''群/数字人/机器人身份/启停等路由变化时递增''',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'feishu_robot_binding'
      AND COLUMN_NAME = 'route_version' AND COLUMN_COMMENT LIKE '%确认%') > 0,
  'ALTER TABLE `feishu_robot_binding` MODIFY COLUMN `route_version` BIGINT NOT NULL DEFAULT 0 COMMENT ''群/数字人/应用身份/启停等路由变化时递增''',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
