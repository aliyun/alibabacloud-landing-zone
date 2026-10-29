-- 会话级 AI Token 用量明细（所有对话渠道）。纯增量：
-- 1) runtime 在每轮对话结束时上报该轮的 token/credits 消耗，按 (tenant_id, conversation_id, turn_id)
--    唯一，重复上报（ACK 重投）只更新同一行；
-- 2) workitem_id 仅在会话 biz_ref_type = 'WORKITEM'（工单需求澄清）时有值，其他渠道的对话
--    只参与数据洞察统计，不进入工单详情；
-- 3) channel 记录会话渠道原值（WORKITEM_CLARIFICATION / PLATFORM_ASSISTANT / DINGTALK / ...），
--    数据洞察按渠道归并来源，未识别渠道归入 OTHER_CONVERSATION；
-- 4) credits 允许为空：provider 未返回计费信息时仍保留 token 用量。
CREATE TABLE IF NOT EXISTS `agent_conversation_usage` (
  `id`                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id`          BIGINT UNSIGNED NOT NULL,
  `workitem_id`        BIGINT UNSIGNED DEFAULT NULL COMMENT '仅 biz_ref_type=WORKITEM 的会话有值',
  `conversation_id`    BIGINT UNSIGNED NOT NULL,
  `turn_id`            BIGINT UNSIGNED NOT NULL,
  `agent_id`           BIGINT UNSIGNED DEFAULT NULL,
  `executor_id`        BIGINT UNSIGNED DEFAULT NULL,
  `channel`            VARCHAR(64)     NOT NULL DEFAULT '' COMMENT 'PLATFORM_ASSISTANT / WORKITEM_CLARIFICATION / ...',
  `provider`           VARCHAR(64)     NOT NULL DEFAULT '',
  `model`              VARCHAR(128)    NOT NULL DEFAULT '',
  `input_tokens`       BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `output_tokens`      BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `cache_read_tokens`  BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `cache_write_tokens` BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `reasoning_tokens`   BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `credits`            DECIMAL(12,4)   DEFAULT NULL,
  `usage_at`           DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `gmt_create`         DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `gmt_modified`       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_conversation_turn` (`tenant_id`, `conversation_id`, `turn_id`),
  KEY `idx_usage_at` (`tenant_id`, `usage_at`),
  KEY `idx_agent_usage_at` (`tenant_id`, `agent_id`, `usage_at`),
  KEY `idx_workitem` (`tenant_id`, `workitem_id`, `channel`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='会话级 AI Token 用量明细（所有对话渠道）';
