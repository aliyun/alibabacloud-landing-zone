-- Shared cache and attempt state machine for squad workflow graphs. One row per
-- (workspace, squad, inputHash, analysisVersion); the unique key makes concurrent
-- first triggers converge on one generation and keeps every accepted graph immutable.
CREATE TABLE IF NOT EXISTS `squad_workflow_snapshot` (
 id BIGINT NOT NULL AUTO_INCREMENT,
 workspace_id BIGINT NOT NULL,
 squad_id BIGINT NOT NULL,
 input_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 analysis_version VARCHAR(64) NOT NULL,
 status VARCHAR(16) NOT NULL,
 attempt_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
 conversation_id BIGINT NULL,
 turn_id BIGINT NULL,
 requested_by BIGINT NULL,
 chief_version_id BIGINT NULL,
 input_json MEDIUMTEXT NOT NULL,
 graph_json MEDIUMTEXT NULL,
 submission_receipts_json MEDIUMTEXT NULL,
 submission_count INT NOT NULL DEFAULT 0,
 repair_count INT NOT NULL DEFAULT 0,
 deadline_at DATETIME(3) NULL,
 error_code VARCHAR(64) NULL,
 error_detail VARCHAR(1024) NULL,
 completed_at DATETIME(3) NULL,
 row_version INT NOT NULL DEFAULT 0,
 gmt_create DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 gmt_modified DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 PRIMARY KEY (id),
 UNIQUE KEY uk_squad_workflow_key (workspace_id, squad_id, input_hash, analysis_version),
 KEY idx_squad_workflow_recovery (status, deadline_at, id),
 KEY idx_squad_workflow_cleanup (status, gmt_modified, id),
 KEY idx_squad_workflow_history (workspace_id, squad_id, gmt_modified, id),
 KEY idx_squad_workflow_conversation (workspace_id, conversation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 AUTO_INCREMENT=10000;
