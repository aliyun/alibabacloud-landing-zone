-- Apply before deploying lifecycle recovery. Additive; supports both execution-source schemas.
CREATE TABLE IF NOT EXISTS workitem_execution_control (
 tenant_id BIGINT NOT NULL, workitem_id BIGINT NOT NULL,
 closed TINYINT NOT NULL DEFAULT 0, modifier_id BIGINT NOT NULL DEFAULT 0,
 gmt_modified DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 PRIMARY KEY (tenant_id, workitem_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS dispatch_recovery (
 tenant_id BIGINT NOT NULL, dispatch_id BIGINT NOT NULL,
 cancel_requested TINYINT NOT NULL DEFAULT 0,
 stop_pending TINYINT NOT NULL DEFAULT 0,
 forced TINYINT NOT NULL DEFAULT 0,
 retry_count INT NOT NULL DEFAULT 0,
 next_retry_at DATETIME(3) NULL,
 phase VARCHAR(32) NULL, reason VARCHAR(512) NULL,
 requested_at DATETIME(3) NULL, last_sent_at DATETIME(3) NULL,
 modifier_id BIGINT NOT NULL DEFAULT 0,
 gmt_modified DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 PRIMARY KEY (tenant_id, dispatch_id),
 KEY idx_recovery_stop (stop_pending, last_sent_at),
 KEY idx_recovery_retry (next_retry_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE workitem_comment_delivery
 ADD COLUMN retry_dispatch_id BIGINT NOT NULL DEFAULT 0,
 DROP INDEX uk_comment_delivery_agent,
 ADD UNIQUE KEY uk_comment_delivery_agent (tenant_id,comment_id,target_agent_id,retry_dispatch_id),
 ADD KEY idx_delivery_recovery (status,gmt_modified,id);
