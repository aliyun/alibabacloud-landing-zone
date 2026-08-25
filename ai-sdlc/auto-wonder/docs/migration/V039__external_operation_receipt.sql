-- Extend the existing integration outbox with the two facts required for reliable
-- external comment delivery: semantic operation identity and numeric execution ownership.
-- No second ledger/table is introduced: the outbox row remains the durable source of truth.
ALTER TABLE `integration_outbox`
  ADD COLUMN `operation_key` VARCHAR(191) DEFAULT NULL AFTER `payload_json`,
  ADD COLUMN `lock_version` BIGINT UNSIGNED NOT NULL DEFAULT 0 AFTER `operation_key`;

-- Existing rows keep their original payload so an in-flight rollout can drain them. They receive
-- isolated legacy keys so they cannot collide with semantic operations created later.
UPDATE `integration_outbox`
SET `operation_key` = CONCAT('legacy:', `id`),
    `next_retry_at` = CASE
      WHEN `status` = 'FAILED_PERMANENT' THEN NULL
      WHEN `status` = 'FAILED_RETRYABLE' AND `next_retry_at` IS NULL THEN NOW(3)
      ELSE `next_retry_at`
    END,
    `status` = CASE
      WHEN `status` = 'FAILED_RETRYABLE' THEN 'FAILED'
      WHEN `status` = 'FAILED_PERMANENT' THEN 'FAILED'
      ELSE `status`
    END
WHERE `operation_key` IS NULL;

ALTER TABLE `integration_outbox`
  MODIFY COLUMN `operation_key` VARCHAR(191) NOT NULL,
  ADD UNIQUE KEY `uk_external_operation` (`tenant_id`, `provider`, `binding_id`, `operation_key`),
  ADD KEY `idx_receipt_recovery` (`status`, `gmt_modified`);
