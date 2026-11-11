-- Minimal standalone `user` table for the platform user-management filter test (H2, MySQL mode).
CREATE TABLE IF NOT EXISTS `user` (
    id BIGINT NOT NULL AUTO_INCREMENT,
    username VARCHAR(64) NOT NULL,
    email VARCHAR(128),
    password_hash VARCHAR(100),
    nickname VARCHAR(64),
    avatar_url VARCHAR(512),
    phone VARCHAR(32),
    status TINYINT NOT NULL DEFAULT 0,
    is_admin TINYINT NOT NULL DEFAULT 0,
    workspace_creation_granted TINYINT NOT NULL DEFAULT 0,
    last_login_at TIMESTAMP,
    gmt_create TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_deleted TINYINT NOT NULL DEFAULT 0,
    deleted_username VARCHAR(64),
    deleted_email VARCHAR(128),
    deleted_at TIMESTAMP,
    deleted_by BIGINT,
    PRIMARY KEY (id)
);
