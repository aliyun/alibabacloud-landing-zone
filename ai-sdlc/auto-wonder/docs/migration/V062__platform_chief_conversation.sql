-- Chief Of Staff 平台管家对话（V062）。纯增量：
-- 1) agent_conversation 增加 Owner / 标题 / 归档 / 回收站字段，遗留渠道全部可空，行为不变；
-- 2) 新建分享、Turn 文件引用、动作计划、动作步骤四张表。
-- 动作表与 Owner 字段放在同一个迁移里，部署时一次原子升级，避免中间态出现「有 Owner 无确认边界」。
--
-- 升级注意（非 DDL，无需任何数据处理）：
-- 本次升级后，升级前签出的会话 MCP 令牌（awconversation_ 前缀）会全部认证失败。
-- 原因是令牌解析改为强制要求 agentId / agentVersionId 两个声明（会话切在线版本后旧身份必须立刻失效），
-- 而升级前用 signScoped 签出的令牌不带这两个声明。令牌 TTL 为 24 小时，因此升级后最长 24 小时内，
-- 仍持有旧令牌的在途会话会收到 UNAUTHORIZED；下一个 Turn 会重新签发令牌，自动恢复，无需人工干预。
-- authenticate() 是各渠道共用的，所以既有的 WORKITEM_CLARIFICATION 工单澄清渠道同样受影响，
-- 升级窗口内的在途澄清会话可能需要重发一次消息才能继续。

ALTER TABLE `agent_conversation`
  ADD COLUMN `owner_user_id` BIGINT NULL COMMENT 'PLATFORM_ASSISTANT immutable owner' AFTER `tenant_id`,
  ADD COLUMN `title` VARCHAR(255) NULL AFTER `channel_conversation_id`,
  ADD COLUMN `title_source` VARCHAR(16) NULL COMMENT 'AUTO/USER' AFTER `title`,
  ADD COLUMN `archived_at` DATETIME NULL AFTER `last_turn_at`,
  ADD COLUMN `deleted_at` DATETIME NULL AFTER `archived_at`,
  ADD KEY `idx_platform_owner_list` (`tenant_id`, `channel`, `owner_user_id`, `deleted_at`, `last_turn_at`, `id`);

CREATE TABLE IF NOT EXISTS `conversation_share` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id` BIGINT NOT NULL,
  `conversation_id` BIGINT NOT NULL,
  `grantee_user_id` BIGINT NOT NULL,
  `permission` VARCHAR(16) NOT NULL DEFAULT 'READ' COMMENT '本期只有 READ，分享用户不能续聊',
  `created_by` BIGINT NOT NULL COMMENT '只能是会话 Owner',
  `revoked_at` DATETIME NULL,
  `gmt_create` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_conversation_grantee` (`tenant_id`, `conversation_id`, `grantee_user_id`),
  KEY `idx_grantee_active` (`tenant_id`, `grantee_user_id`, `revoked_at`, `conversation_id`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='平台会话只读分享';

CREATE TABLE IF NOT EXISTS `conversation_turn_artifact` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id` BIGINT NOT NULL,
  `conversation_id` BIGINT NOT NULL,
  `turn_id` BIGINT NOT NULL,
  `artifact_id` BIGINT NOT NULL,
  `direction` VARCHAR(8) NOT NULL COMMENT 'INPUT / OUTPUT',
  `reference_mode` VARCHAR(16) NOT NULL COMMENT 'UPLOAD / SELECTED / MENTION / GENERATED',
  `display_order` INT NOT NULL DEFAULT 0,
  `manifest_json` MEDIUMTEXT NOT NULL COMMENT 'Turn 创建时固化的附件清单，后续同名文件更新不得回溯改写',
  `gmt_create` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_turn_artifact_direction` (`turn_id`, `artifact_id`, `direction`),
  KEY `idx_conversation_artifact` (`tenant_id`, `conversation_id`, `artifact_id`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='Turn 与文件的不可变引用';

CREATE TABLE IF NOT EXISTS `conversation_action_plan` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id` BIGINT NOT NULL,
  `workspace_id` BIGINT NOT NULL COMMENT '执行时复核权限用的工作空间',
  `conversation_id` BIGINT NOT NULL,
  `turn_id` BIGINT NOT NULL,
  `owner_user_id` BIGINT NOT NULL COMMENT '只有此人能确认，跨 Owner 确认必须失败',
  `status` VARCHAR(24) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/PENDING_CONFIRMATION/APPROVED/EXECUTING/SUCCEEDED/PARTIAL_FAILED/REJECTED/EXPIRED/CANCELED',
  `canonical_payload_json` MEDIUMTEXT NOT NULL COMMENT '确定性 JSON Canonicalization 后的冻结参数',
  `payload_sha256` CHAR(64) NOT NULL COMMENT '确认时必须回传同一哈希，防篡改',
  `expires_at` DATETIME NOT NULL COMMENT 'TTL 到期即 EXPIRED，不可确认',
  `approved_by` BIGINT NULL,
  `approved_at` DATETIME NULL,
  `consumed_at` DATETIME NULL COMMENT '原子消费标记，非空即不可再次执行',
  `gmt_create` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `gmt_modified` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `version` INT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_plan_hash` (`tenant_id`, `conversation_id`, `payload_sha256`),
  KEY `idx_plan_owner` (`tenant_id`, `owner_user_id`, `status`, `id`),
  KEY `idx_plan_expiry` (`status`, `expires_at`, `id`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='参数冻结的一次性平台动作计划';

CREATE TABLE IF NOT EXISTS `conversation_action_step` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id` BIGINT NOT NULL,
  `plan_id` BIGINT NOT NULL,
  `step_no` INT NOT NULL,
  `tool_name` VARCHAR(128) NOT NULL COMMENT '冻结的工具名，执行时不接受替换',
  `arguments_json` MEDIUMTEXT NOT NULL COMMENT '冻结的参数，来自计划而非 Runtime 回包',
  `arguments_sha256` CHAR(64) NOT NULL,
  `idempotency_key` VARCHAR(128) NOT NULL COMMENT '确定性幂等键，仅标记幂等的失败步骤可重试',
  `idempotent` TINYINT NOT NULL DEFAULT 0 COMMENT '1 表示失败后可安全重试',
  `status` VARCHAR(24) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/SUCCEEDED/FAILED/SKIPPED',
  `result_summary_json` MEDIUMTEXT NULL COMMENT '脱敏结果摘要，不含正文与 Secret',
  `error_category` VARCHAR(64) NULL,
  `started_at` DATETIME NULL,
  `finished_at` DATETIME NULL,
  `gmt_create` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `gmt_modified` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_plan_step` (`plan_id`, `step_no`),
  KEY `idx_step_idempotency` (`tenant_id`, `idempotency_key`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='动作计划的冻结步骤与真实执行结果';
