-- Apply once after V097; additive metadata, legacy rows remain NULL.
ALTER TABLE `agent_conversation_elicitation`
  ADD COLUMN `answer_protocol_version` INT NULL,
  ADD COLUMN `dispatch_attempt` INT NULL,
  ADD COLUMN `executor_id` BIGINT NULL,
  ADD COLUMN `answer_id` VARCHAR(64) NULL,
  ADD COLUMN `delivery_status` VARCHAR(16) NULL,
  ADD COLUMN `delivery_deadline` DATETIME NULL,
  ADD COLUMN `next_delivery_at` DATETIME NULL,
  ADD KEY `idx_answer_delivery_due` (`delivery_status`, `next_delivery_at`, `id`),
  ADD KEY `idx_answer_conversation_recent` (`tenant_id`, `conversation_id`, `id`);
