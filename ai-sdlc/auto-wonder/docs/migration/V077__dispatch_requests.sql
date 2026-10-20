-- Apply before the platform and Runtime dispatch interaction release.
CREATE TABLE IF NOT EXISTS dispatch_request (
 id BIGINT NOT NULL AUTO_INCREMENT,
 tenant_id BIGINT NOT NULL,
 dispatch_id BIGINT NOT NULL,
 kind VARCHAR(16) NOT NULL,
 request_key VARCHAR(191) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 body_json LONGTEXT NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
 answer_json LONGTEXT NULL,
 actor_id BIGINT NULL,
 expires_at DATETIME(3) NULL,
 suspend_requested TINYINT NOT NULL DEFAULT 0,
 resume_dispatch_id BIGINT NULL,
 gmt_create DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 gmt_modified DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 PRIMARY KEY (id),
 UNIQUE KEY uk_dispatch_request (tenant_id, dispatch_id, request_key),
 KEY idx_request_sweep (status, expires_at),
 KEY idx_request_dispatch (tenant_id, dispatch_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
