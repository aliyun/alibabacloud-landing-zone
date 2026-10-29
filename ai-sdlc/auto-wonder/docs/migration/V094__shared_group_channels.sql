-- 钉钉/飞书平台共享群聊机器人（工单 #56685）：六张既有表扩展，不新增业务表。
-- 旧库执行一次本迁移；全新库使用 docs/autowonder-schema.sql。
-- 不提供破坏性 down SQL：失败时保留数据，按 information_schema 判断已完成步骤后安全续跑。
-- MySQL DDL 非事务原子；执行前请按下面预检确认没有重复身份或空凭据，发现问题停止，不要删数据求成功。
--
-- 预检（任一条有结果都应先人工处理）：
--   SELECT robot_code, COUNT(*) FROM dingtalk_robot_binding GROUP BY robot_code HAVING COUNT(*) > 1;
--   SELECT app_id, COUNT(*) FROM feishu_robot_binding GROUP BY app_id HAVING COUNT(*) > 1;
--   SELECT id FROM dingtalk_robot_binding WHERE credential_ref IS NULL OR credential_ref = '';
--   SELECT id FROM feishu_robot_binding WHERE credential_ref IS NULL OR credential_ref = '';
--
-- agent_conversation_turn.source_inbound_turn_id 的重复预检必须等第 6 节补列后执行，见该节。
-- 顺序：先加新字段/新唯一键和普通索引，验证成功后删除旧唯一键，避免迁移中途失去唯一性保护。

-- =============================================================================
-- 1. platform_im_channel_config：用途隔离、TEXT 凭据、路由版本、复合唯一键
-- =============================================================================
SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'platform_im_channel_config' AND COLUMN_NAME = 'purpose') = 0,
  'ALTER TABLE `platform_im_channel_config` ADD COLUMN `purpose` VARCHAR(16) NOT NULL DEFAULT ''NOTIFY'' COMMENT ''NOTIFY 通知 / GROUP_CHAT 共享群聊'' AFTER `provider`',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'platform_im_channel_config' AND COLUMN_NAME = 'route_version') = 0,
  'ALTER TABLE `platform_im_channel_config` ADD COLUMN `route_version` BIGINT NOT NULL DEFAULT 0 COMMENT ''路由版本：启停/解绑等路由目标变化时递增'' AFTER `base_url`',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'platform_im_channel_config'
      AND COLUMN_NAME = 'credential_ref' AND DATA_TYPE <> 'text') > 0,
  'ALTER TABLE `platform_im_channel_config` MODIFY COLUMN `credential_ref` TEXT DEFAULT NULL COMMENT ''加密后的 application secret 引用''',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'platform_im_channel_config'
      AND INDEX_NAME = 'uk_platform_im_provider_purpose') = 0,
  'ALTER TABLE `platform_im_channel_config` ADD UNIQUE KEY `uk_platform_im_provider_purpose` (`provider`, `purpose`)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'platform_im_channel_config'
      AND INDEX_NAME = 'uk_platform_im_provider') > 0,
  'ALTER TABLE `platform_im_channel_config` DROP INDEX `uk_platform_im_provider`',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- =============================================================================
-- 2. platform_im_selection：切换代次与数据库生效时间（旧选择不变，generation=0）
-- =============================================================================
SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'platform_im_selection' AND COLUMN_NAME = 'generation') = 0,
  'ALTER TABLE `platform_im_selection` ADD COLUMN `generation` BIGINT NOT NULL DEFAULT 0 COMMENT ''渠道选择代次；0 表示升级前兼容代次''',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'platform_im_selection' AND COLUMN_NAME = 'activated_at') = 0,
  'ALTER TABLE `platform_im_selection` ADD COLUMN `activated_at` DATETIME(3) DEFAULT NULL COMMENT ''最近一次切换生效的数据库时间''',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- =============================================================================
-- 3. dingtalk_robot_binding：LEGACY/SHARED 模式、群占用、确认挑战、路由版本
-- =============================================================================
SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dingtalk_robot_binding' AND COLUMN_NAME = 'binding_mode') = 0,
  'ALTER TABLE `dingtalk_robot_binding` ADD COLUMN `binding_mode` VARCHAR(16) NOT NULL DEFAULT ''LEGACY'' COMMENT ''LEGACY / SHARED'' AFTER `tenant_id`',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dingtalk_robot_binding' AND COLUMN_NAME = 'platform_config_id') = 0,
  'ALTER TABLE `dingtalk_robot_binding` ADD COLUMN `platform_config_id` BIGINT UNSIGNED DEFAULT NULL COMMENT ''SHARED 引用的 platform_im_channel_config.id'' AFTER `binding_mode`',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dingtalk_robot_binding' AND COLUMN_NAME = 'group_id') = 0,
  'ALTER TABLE `dingtalk_robot_binding` ADD COLUMN `group_id` VARCHAR(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT ''SHARED 外部群 ID，去空白后精确匹配'' AFTER `platform_config_id`',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dingtalk_robot_binding' AND COLUMN_NAME = 'route_version') = 0,
  'ALTER TABLE `dingtalk_robot_binding` ADD COLUMN `route_version` BIGINT NOT NULL DEFAULT 0 COMMENT ''群/数字人/群确认/启停等路由变化时递增'' AFTER `stream_env`',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dingtalk_robot_binding' AND COLUMN_NAME = 'confirmed_at') = 0,
  'ALTER TABLE `dingtalk_robot_binding` ADD COLUMN `confirmed_at` DATETIME(3) DEFAULT NULL COMMENT ''SHARED 群确认完成时间'' AFTER `route_version`',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dingtalk_robot_binding' AND COLUMN_NAME = 'challenge_hash') = 0,
  'ALTER TABLE `dingtalk_robot_binding` ADD COLUMN `challenge_hash` CHAR(64) DEFAULT NULL COMMENT ''SHA-256 确认码哈希'' AFTER `confirmed_at`',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dingtalk_robot_binding' AND COLUMN_NAME = 'challenge_expires_at') = 0,
  'ALTER TABLE `dingtalk_robot_binding` ADD COLUMN `challenge_expires_at` DATETIME(3) DEFAULT NULL COMMENT ''确认码过期时间'' AFTER `challenge_hash`',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dingtalk_robot_binding' AND COLUMN_NAME = 'legacy_identity') = 0,
  'ALTER TABLE `dingtalk_robot_binding` ADD COLUMN `legacy_identity` VARCHAR(128) GENERATED ALWAYS AS (CASE WHEN `binding_mode` = ''LEGACY'' THEN `robot_code` ELSE NULL END) STORED',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dingtalk_robot_binding'
      AND COLUMN_NAME = 'credential_ref' AND IS_NULLABLE = 'NO') > 0,
  'ALTER TABLE `dingtalk_robot_binding` MODIFY COLUMN `credential_ref` TEXT NULL COMMENT ''LEGACY appSecret 密文引用(SecretCrypto)；SHARED 必须 NULL''',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dingtalk_robot_binding'
      AND INDEX_NAME = 'uk_dingtalk_legacy_identity') = 0,
  'ALTER TABLE `dingtalk_robot_binding` ADD UNIQUE KEY `uk_dingtalk_legacy_identity` (`legacy_identity`)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dingtalk_robot_binding'
      AND INDEX_NAME = 'uk_dingtalk_shared_group') = 0,
  'ALTER TABLE `dingtalk_robot_binding` ADD UNIQUE KEY `uk_dingtalk_shared_group` (`platform_config_id`, `group_id`)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dingtalk_robot_binding'
      AND INDEX_NAME = 'idx_dingtalk_shared_agent') = 0,
  'ALTER TABLE `dingtalk_robot_binding` ADD KEY `idx_dingtalk_shared_agent` (`tenant_id`, `agent_id`, `binding_mode`)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF((SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dingtalk_robot_binding'
      AND CONSTRAINT_NAME = 'chk_dingtalk_binding_mode') = 0,
  'ALTER TABLE `dingtalk_robot_binding` ADD CONSTRAINT `chk_dingtalk_binding_mode` CHECK ((`binding_mode` = ''LEGACY'' AND `platform_config_id` IS NULL AND `group_id` IS NULL AND `credential_ref` IS NOT NULL AND `credential_ref` <> '''') OR (`binding_mode` = ''SHARED'' AND `platform_config_id` IS NOT NULL AND `group_id` IS NOT NULL AND `credential_ref` IS NULL))',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 旧唯一键只约束 LEGACY；删除前先补普通索引，保证按 robot_code 的旧查询仍有索引可用。
SET @sql := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dingtalk_robot_binding'
      AND INDEX_NAME = 'idx_dingtalk_robot_code') = 0,
  'ALTER TABLE `dingtalk_robot_binding` ADD KEY `idx_dingtalk_robot_code` (`robot_code`)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dingtalk_robot_binding'
      AND INDEX_NAME = 'uk_robot') > 0,
  'ALTER TABLE `dingtalk_robot_binding` DROP INDEX `uk_robot`',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- =============================================================================
-- 4. feishu_robot_binding：与钉钉一致的模式扩展；旧绑定 ID 与收件箱关联不变
-- =============================================================================
SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'feishu_robot_binding' AND COLUMN_NAME = 'binding_mode') = 0,
  'ALTER TABLE `feishu_robot_binding` ADD COLUMN `binding_mode` VARCHAR(16) NOT NULL DEFAULT ''LEGACY'' COMMENT ''LEGACY / SHARED'' AFTER `tenant_id`',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'feishu_robot_binding' AND COLUMN_NAME = 'platform_config_id') = 0,
  'ALTER TABLE `feishu_robot_binding` ADD COLUMN `platform_config_id` BIGINT UNSIGNED DEFAULT NULL COMMENT ''SHARED 引用的 platform_im_channel_config.id'' AFTER `binding_mode`',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'feishu_robot_binding' AND COLUMN_NAME = 'group_id') = 0,
  'ALTER TABLE `feishu_robot_binding` ADD COLUMN `group_id` VARCHAR(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT ''SHARED chat_id，去空白后精确匹配'' AFTER `platform_config_id`',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'feishu_robot_binding' AND COLUMN_NAME = 'route_version') = 0,
  'ALTER TABLE `feishu_robot_binding` ADD COLUMN `route_version` BIGINT NOT NULL DEFAULT 0 COMMENT ''群/数字人/群确认/启停等路由变化时递增'' AFTER `agent_id`',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'feishu_robot_binding' AND COLUMN_NAME = 'confirmed_at') = 0,
  'ALTER TABLE `feishu_robot_binding` ADD COLUMN `confirmed_at` DATETIME(3) DEFAULT NULL COMMENT ''SHARED 群确认完成时间'' AFTER `route_version`',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'feishu_robot_binding' AND COLUMN_NAME = 'challenge_hash') = 0,
  'ALTER TABLE `feishu_robot_binding` ADD COLUMN `challenge_hash` CHAR(64) DEFAULT NULL COMMENT ''SHA-256 确认码哈希'' AFTER `confirmed_at`',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'feishu_robot_binding' AND COLUMN_NAME = 'challenge_expires_at') = 0,
  'ALTER TABLE `feishu_robot_binding` ADD COLUMN `challenge_expires_at` DATETIME(3) DEFAULT NULL COMMENT ''确认码过期时间'' AFTER `challenge_hash`',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'feishu_robot_binding' AND COLUMN_NAME = 'legacy_identity') = 0,
  'ALTER TABLE `feishu_robot_binding` ADD COLUMN `legacy_identity` VARCHAR(128) GENERATED ALWAYS AS (CASE WHEN `binding_mode` = ''LEGACY'' THEN `app_id` ELSE NULL END) STORED',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'feishu_robot_binding'
      AND COLUMN_NAME = 'credential_ref' AND IS_NULLABLE = 'NO') > 0,
  'ALTER TABLE `feishu_robot_binding` MODIFY COLUMN `credential_ref` TEXT NULL COMMENT ''SecretCrypto encrypted App Secret / Verification Token / Encrypt Key JSON；SHARED 必须 NULL''',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'feishu_robot_binding'
      AND INDEX_NAME = 'uk_feishu_legacy_identity') = 0,
  'ALTER TABLE `feishu_robot_binding` ADD UNIQUE KEY `uk_feishu_legacy_identity` (`legacy_identity`)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'feishu_robot_binding'
      AND INDEX_NAME = 'uk_feishu_shared_group') = 0,
  'ALTER TABLE `feishu_robot_binding` ADD UNIQUE KEY `uk_feishu_shared_group` (`platform_config_id`, `group_id`)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'feishu_robot_binding'
      AND INDEX_NAME = 'idx_feishu_shared_agent') = 0,
  'ALTER TABLE `feishu_robot_binding` ADD KEY `idx_feishu_shared_agent` (`tenant_id`, `agent_id`, `binding_mode`)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF((SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'feishu_robot_binding'
      AND CONSTRAINT_NAME = 'chk_feishu_binding_mode') = 0,
  'ALTER TABLE `feishu_robot_binding` ADD CONSTRAINT `chk_feishu_binding_mode` CHECK ((`binding_mode` = ''LEGACY'' AND `platform_config_id` IS NULL AND `group_id` IS NULL AND `credential_ref` IS NOT NULL AND `credential_ref` <> '''') OR (`binding_mode` = ''SHARED'' AND `platform_config_id` IS NOT NULL AND `group_id` IS NOT NULL AND `credential_ref` IS NULL))',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 旧唯一键只约束 LEGACY；删除前先补普通索引，保证按 app_id 的旧查询仍有索引可用。
SET @sql := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'feishu_robot_binding'
      AND INDEX_NAME = 'idx_feishu_app_id') = 0,
  'ALTER TABLE `feishu_robot_binding` ADD KEY `idx_feishu_app_id` (`app_id`)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'feishu_robot_binding'
      AND INDEX_NAME = 'uk_feishu_app') > 0,
  'ALTER TABLE `feishu_robot_binding` DROP INDEX `uk_feishu_app`',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- =============================================================================
-- 5. feishu_message_inbox：接收路由快照与共享群聊去重键（旧行为 NULL 兼容）
-- =============================================================================
SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'feishu_message_inbox' AND COLUMN_NAME = 'receipt_context') = 0,
  'ALTER TABLE `feishu_message_inbox` ADD COLUMN `receipt_context` MEDIUMTEXT NULL COMMENT ''接收时路由快照 JSON；NULL 表示升级前记录'' AFTER `payload`',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'feishu_message_inbox' AND COLUMN_NAME = 'platform_config_id') = 0,
  'ALTER TABLE `feishu_message_inbox` ADD COLUMN `platform_config_id` BIGINT UNSIGNED DEFAULT NULL COMMENT ''SHARED 接收时的平台群聊配置；NULL 表示独立应用'' AFTER `receipt_context`',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'feishu_message_inbox' AND COLUMN_NAME = 'group_id') = 0,
  'ALTER TABLE `feishu_message_inbox` ADD COLUMN `group_id` VARCHAR(128) DEFAULT NULL COMMENT ''SHARED 接收时的群 ID'' AFTER `platform_config_id`',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'feishu_message_inbox'
      AND INDEX_NAME = 'uk_feishu_shared_receipt') = 0,
  'ALTER TABLE `feishu_message_inbox` ADD UNIQUE KEY `uk_feishu_shared_receipt` (`platform_config_id`, `group_id`, `message_id`)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- =============================================================================
-- 6. agent_conversation_turn：IM 回复持久投递状态
-- =============================================================================
SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agent_conversation_turn' AND COLUMN_NAME = 'source_inbound_turn_id') = 0,
  'ALTER TABLE `agent_conversation_turn` ADD COLUMN `source_inbound_turn_id` BIGINT UNSIGNED DEFAULT NULL COMMENT ''IM 回复对应的入站 turn；NULL 表示非 IM 或升级前记录'' AFTER `error`',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agent_conversation_turn' AND COLUMN_NAME = 'delivery_status') = 0,
  'ALTER TABLE `agent_conversation_turn` ADD COLUMN `delivery_status` VARCHAR(16) DEFAULT NULL COMMENT ''PENDING/SENDING/SENT/FAILED/CANCELED/UNKNOWN；仅 IM OUT'' AFTER `source_inbound_turn_id`',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agent_conversation_turn' AND COLUMN_NAME = 'delivery_attempt') = 0,
  'ALTER TABLE `agent_conversation_turn` ADD COLUMN `delivery_attempt` INT NOT NULL DEFAULT 0 COMMENT ''回复投递尝试次数'' AFTER `delivery_status`',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agent_conversation_turn' AND COLUMN_NAME = 'delivery_available_at') = 0,
  'ALTER TABLE `agent_conversation_turn` ADD COLUMN `delivery_available_at` DATETIME(3) DEFAULT NULL COMMENT ''下次可领取时间'' AFTER `delivery_attempt`',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agent_conversation_turn' AND COLUMN_NAME = 'delivery_error') = 0,
  'ALTER TABLE `agent_conversation_turn` ADD COLUMN `delivery_error` TEXT DEFAULT NULL COMMENT ''投递诊断，不覆盖业务 error'' AFTER `delivery_available_at`',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agent_conversation_turn'
      AND INDEX_NAME = 'idx_turn_delivery_claim') = 0,
  'ALTER TABLE `agent_conversation_turn` ADD KEY `idx_turn_delivery_claim` (`delivery_status`, `delivery_available_at`, `id`)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 列已补，添加唯一键前确认没有重复的回复绑定；有结果时先人工处理，不要为通过迁移删数据：
--   SELECT tenant_id, source_inbound_turn_id, COUNT(*) FROM agent_conversation_turn
--     WHERE source_inbound_turn_id IS NOT NULL GROUP BY tenant_id, source_inbound_turn_id HAVING COUNT(*) > 1;
SET @sql := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agent_conversation_turn'
      AND INDEX_NAME = 'uk_turn_source_inbound') = 0,
  'ALTER TABLE `agent_conversation_turn` ADD UNIQUE KEY `uk_turn_source_inbound` (`tenant_id`, `source_inbound_turn_id`)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
