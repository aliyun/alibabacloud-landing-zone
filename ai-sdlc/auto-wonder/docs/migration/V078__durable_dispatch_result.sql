-- Deploy before the v2 server/runtime pair. All checkpoint writers require this schema.
ALTER TABLE dispatch_recovery_checkpoint
  ADD COLUMN completion_result_id VARCHAR(36) DEFAULT NULL,
  ADD COLUMN gc_pending TINYINT NOT NULL DEFAULT 0,
  ADD KEY idx_checkpoint_gc (gc_pending, id),
  ADD UNIQUE KEY uk_checkpoint_completion (tenant_id, dispatch_id, completion_result_id);

CREATE TABLE IF NOT EXISTS dispatch_result_decision (
  tenant_id BIGINT UNSIGNED NOT NULL,
  dispatch_id BIGINT UNSIGNED NOT NULL,
  result_id VARCHAR(36) NOT NULL,
  executor_id BIGINT UNSIGNED NOT NULL,
  payload_sha256 CHAR(64) NOT NULL,
  checkpoint_seq BIGINT DEFAULT NULL,
  checkpoint_sha256 VARCHAR(80) DEFAULT NULL,
  failure_category VARCHAR(128) DEFAULT NULL,
  disposition VARCHAR(16) NOT NULL,
  reason_code VARCHAR(64) DEFAULT NULL,
  ack_json JSON NOT NULL,
  handoff_json JSON DEFAULT NULL,
  decided_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (tenant_id, dispatch_id, result_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Durable runtime result decisions; retained independently of checkpoint objects';
