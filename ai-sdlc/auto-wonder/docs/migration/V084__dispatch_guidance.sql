CREATE TABLE IF NOT EXISTS dispatch_guidance (
 id BIGINT NOT NULL AUTO_INCREMENT,
 workspace_id BIGINT NOT NULL,
 workitem_id BIGINT NOT NULL,
 dispatch_id BIGINT NOT NULL,
 executor_id BIGINT NOT NULL,
 actor_id BIGINT NOT NULL,
 request_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 content LONGTEXT NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'QUEUED',
 error TEXT NULL,
 applied_at DATETIME(3) NULL,
 gmt_create DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 gmt_modified DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 PRIMARY KEY (id),
 UNIQUE KEY uk_dispatch_guidance_request (workspace_id, dispatch_id, request_key),
 KEY idx_dispatch_guidance_history (workspace_id, workitem_id, dispatch_id, id),
 KEY idx_dispatch_guidance_pending (status, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
