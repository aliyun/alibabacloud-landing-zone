-- V056__external_artifact_share.sql is required first. No Runtime upgrade is needed.
-- One immutable content declaration per dispatch/path. Replays retain the first digest.
CREATE TABLE IF NOT EXISTS `artifact_share_request` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `tenant_id` BIGINT UNSIGNED NOT NULL,
    `workitem_id` BIGINT UNSIGNED NOT NULL,
    `dispatch_id` BIGINT UNSIGNED NOT NULL,
    `name` VARCHAR(256) COLLATE utf8mb4_bin NOT NULL,
    `sha256` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `status` VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    `artifact_id` BIGINT UNSIGNED DEFAULT NULL,
    `comment_id` BIGINT UNSIGNED DEFAULT NULL,
    `error` VARCHAR(128) DEFAULT NULL,
    `next_attempt_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `gmt_create` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_share_request` (`tenant_id`, `dispatch_id`, `name`),
    KEY `idx_share_request_due` (`status`, `next_attempt_at`),
    KEY `idx_share_request_dispatch` (`dispatch_id`, `status`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4;
