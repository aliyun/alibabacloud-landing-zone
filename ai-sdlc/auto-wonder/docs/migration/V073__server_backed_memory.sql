-- Claude-compatible server-backed memory document store.
-- Legacy memory, memory_review and agent_memory_ref remain intact for rollback.

CREATE TABLE IF NOT EXISTS `memory_store` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id` BIGINT UNSIGNED NOT NULL,
  `scope` VARCHAR(16) NOT NULL COMMENT 'AGENT / SQUAD / ORG',
  `owner_ref` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT 'ORG uses normalized owner 0',
  `name` VARCHAR(128) NOT NULL,
  `current_revision` BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  `maintenance_lease_owner` VARCHAR(128) NULL,
  `maintenance_lease_until` DATETIME(3) NULL,
  `maintenance_lease_dispatch_id` BIGINT NULL,
  `creator_id` BIGINT UNSIGNED NULL,
  `modifier_id` BIGINT UNSIGNED NULL,
  `version` INT UNSIGNED NOT NULL DEFAULT 0,
  `gmt_create` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `gmt_modified` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_memory_store_owner` (`tenant_id`, `scope`, `owner_ref`),
  KEY `idx_memory_store_status` (`tenant_id`, `status`, `id`),
  KEY `idx_memory_store_maintenance_lease` (`tenant_id`, `maintenance_lease_until`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='Authoritative memory stores';

CREATE TABLE IF NOT EXISTS `memory_document` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id` BIGINT UNSIGNED NOT NULL,
  `store_id` BIGINT UNSIGNED NOT NULL,
  `path` VARCHAR(512) NOT NULL,
  `content_md` MEDIUMTEXT NOT NULL,
  `content_sha256` CHAR(64) NOT NULL,
  `byte_size` BIGINT UNSIGNED NOT NULL,
  `modified_at` DATETIME(3) NOT NULL,
  `deleted_at` DATETIME(3) NULL,
  `creator_type` VARCHAR(16) NOT NULL,
  `creator_ref` VARCHAR(128) NULL,
  `source_dispatch_id` BIGINT UNSIGNED NULL,
  `source_session_id` VARCHAR(128) NULL,
  `creator_id` BIGINT UNSIGNED NULL,
  `modifier_id` BIGINT UNSIGNED NULL,
  `version` INT UNSIGNED NOT NULL DEFAULT 1,
  `gmt_create` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `gmt_modified` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_memory_document_path` (`store_id`, `path`),
  KEY `idx_memory_document_store_live` (`tenant_id`, `store_id`, `deleted_at`, `path`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='Current memory document bodies';

CREATE TABLE IF NOT EXISTS `memory_change` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id` BIGINT UNSIGNED NOT NULL,
  `store_id` BIGINT UNSIGNED NOT NULL,
  `document_id` BIGINT UNSIGNED NOT NULL,
  `store_revision` BIGINT UNSIGNED NOT NULL,
  `operation` VARCHAR(16) NOT NULL,
  `path` VARCHAR(512) NOT NULL,
  `document_version` INT UNSIGNED NOT NULL,
  `content_sha256` CHAR(64) NULL,
  `actor_type` VARCHAR(16) NOT NULL,
  `actor_ref` VARCHAR(128) NULL,
  `dispatch_id` BIGINT UNSIGNED NULL,
  `provider_session_id` VARCHAR(128) NULL,
  `idempotency_key` VARCHAR(128) NOT NULL,
  `request_fingerprint` CHAR(64) NULL COMMENT 'SHA-256 of the complete mutation request',
  `gmt_create` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_memory_change_idempotency` (`tenant_id`, `store_id`, `idempotency_key`),
  UNIQUE KEY `uk_memory_change_revision` (`store_id`, `store_revision`),
  KEY `idx_memory_change_feed` (`tenant_id`, `store_id`, `store_revision`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='Body-free append-only memory change log';

CREATE TABLE IF NOT EXISTS `memory_store_acl` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id` BIGINT UNSIGNED NOT NULL,
  `store_id` BIGINT UNSIGNED NOT NULL,
  `subject_type` VARCHAR(16) NOT NULL COMMENT 'USER / AGENT / SQUAD / ROLE',
  `subject_ref` VARCHAR(128) NOT NULL,
  `permission` VARCHAR(16) NOT NULL COMMENT 'READ / WRITE / ADMIN',
  `creator_id` BIGINT UNSIGNED NULL,
  `modifier_id` BIGINT UNSIGNED NULL,
  `gmt_create` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `gmt_modified` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_memory_store_acl` (`tenant_id`, `store_id`, `subject_type`, `subject_ref`),
  KEY `idx_memory_store_acl_subject` (`tenant_id`, `subject_type`, `subject_ref`, `permission`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='Memory store access control';

CREATE TABLE IF NOT EXISTS `memory_import_source` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id` BIGINT UNSIGNED NOT NULL,
  `agent_id` BIGINT UNSIGNED NOT NULL,
  `target_store_id` BIGINT UNSIGNED NOT NULL,
  `provider_family` VARCHAR(32) NOT NULL,
  `logical_path` VARCHAR(64) NOT NULL,
  `installation_fingerprint` CHAR(64) NOT NULL,
  `last_observed_sanitized_sha256` CHAR(64) NULL,
  `last_imported_sanitized_sha256` CHAR(64) NULL,
  `last_observed_executor_id` BIGINT UNSIGNED NULL,
  `last_seen_at` DATETIME(3) NULL,
  `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  `version` INT UNSIGNED NOT NULL DEFAULT 0,
  `gmt_create` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `gmt_modified` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_memory_import_source` (`tenant_id`, `agent_id`, `provider_family`, `logical_path`, `installation_fingerprint`),
  KEY `idx_memory_import_source_agent` (`tenant_id`, `agent_id`, `status`, `last_seen_at`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='Observed legacy Qoder memory sources';

CREATE TABLE IF NOT EXISTS `memory_import_snapshot` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id` BIGINT UNSIGNED NOT NULL,
  `source_id` BIGINT UNSIGNED NOT NULL,
  `sanitized_content_sha256` CHAR(64) NOT NULL,
  `sanitized_content` MEDIUMTEXT NOT NULL,
  `byte_size` BIGINT UNSIGNED NOT NULL,
  `source_modified_at` DATETIME(3) NULL,
  `scan_summary_json` JSON NULL,
  `status` VARCHAR(16) NOT NULL DEFAULT 'QUEUED',
  `curation_lease_id` VARCHAR(64) NULL,
  `curation_lease_until` DATETIME(3) NULL,
  `gmt_create` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `gmt_modified` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_memory_import_snapshot_hash` (`tenant_id`, `source_id`, `sanitized_content_sha256`),
  KEY `idx_memory_import_snapshot_recent` (`tenant_id`, `source_id`, `id`),
  KEY `idx_memory_import_snapshot_lease` (`tenant_id`, `curation_lease_until`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='Bounded sanitized legacy memory snapshots';

CREATE TABLE IF NOT EXISTS `memory_import_receipt` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id` BIGINT UNSIGNED NOT NULL,
  `agent_id` BIGINT UNSIGNED NOT NULL,
  `source_id` BIGINT UNSIGNED NOT NULL,
  `snapshot_id` BIGINT UNSIGNED NOT NULL,
  `sanitized_content_sha256` CHAR(64) NOT NULL,
  `curation_dispatch_id` BIGINT UNSIGNED NULL,
  `outcome` VARCHAR(16) NOT NULL,
  `target_versions_json` JSON NULL,
  `redaction_count` INT UNSIGNED NOT NULL DEFAULT 0,
  `gmt_create` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_memory_import_receipt_hash` (`tenant_id`, `agent_id`, `sanitized_content_sha256`),
  KEY `idx_memory_import_receipt_source` (`tenant_id`, `source_id`, `id`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='Content-free legacy import outcomes';
