-- Apply before deploying the workflow snapshot / explicit latest-workflow restart feature.
-- Additive tables: historical dispatches, packages, checkpoints and receipts stay untouched.
CREATE TABLE IF NOT EXISTS dispatch_workflow_snapshot (
  dispatch_id BIGINT UNSIGNED NOT NULL,
  tenant_id BIGINT UNSIGNED NOT NULL,
  workitem_id BIGINT UNSIGNED NOT NULL,
  delivery_round_id BIGINT UNSIGNED NOT NULL,
  source_dispatch_id BIGINT UNSIGNED NULL,
  sdlc_id BIGINT UNSIGNED NULL,
  sdlc_step_id BIGINT UNSIGNED NULL,
  agent_version_id BIGINT UNSIGNED NULL,
  snapshot_json LONGTEXT NOT NULL,
  snapshot_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  fenced_by_restart_key VARCHAR(128) NULL,
  gmt_create DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (dispatch_id),
  KEY idx_workflow_subject (tenant_id,workitem_id,delivery_round_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS delivery_external_operation (
  tenant_id BIGINT UNSIGNED NOT NULL,
  workitem_id BIGINT UNSIGNED NOT NULL,
  operation_key VARCHAR(191) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  operation_kind VARCHAR(32) NOT NULL,
  request_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) NOT NULL,
  owner_dispatch_id BIGINT UNSIGNED NOT NULL,
  delivery_round_id BIGINT UNSIGNED NOT NULL,
  claim_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  receipt_ref VARCHAR(1024) NULL,
  readback_ref VARCHAR(1024) NULL,
  version INT NOT NULL DEFAULT 0,
  gmt_create DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  gmt_modified DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (tenant_id,workitem_id,operation_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS delivery_restart_snapshot (
  tenant_id BIGINT UNSIGNED NOT NULL,
  workitem_id BIGINT UNSIGNED NOT NULL,
  restart_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  source_dispatch_id BIGINT UNSIGNED NULL,
  sdlc_id BIGINT UNSIGNED NULL,
  sdlc_step_id BIGINT UNSIGNED NULL,
  agent_version_id BIGINT UNSIGNED NULL,
  snapshot_json LONGTEXT NOT NULL,
  snapshot_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  gmt_create DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (tenant_id,workitem_id,restart_key),
  KEY idx_restart_source (tenant_id,workitem_id,source_dispatch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
