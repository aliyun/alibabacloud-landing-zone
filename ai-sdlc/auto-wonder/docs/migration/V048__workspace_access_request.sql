-- V044__workspace_access_request.sql
CREATE TABLE IF NOT EXISTS `workspace_access_request` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id`       BIGINT UNSIGNED NOT NULL COMMENT '目标工作空间 ID',
  `requester_id`    BIGINT UNSIGNED NOT NULL COMMENT '申请人 user ID',
  `requested_level` VARCHAR(20)     NOT NULL COMMENT 'READ_ONLY / READ_WRITE / ADMIN',
  `status`          VARCHAR(20)     NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / APPROVED / REJECTED',
  `pending_marker`  TINYINT GENERATED ALWAYS AS (CASE WHEN status = 'PENDING' THEN 1 ELSE NULL END) STORED,
  `reviewer_id`     BIGINT UNSIGNED NULL     COMMENT '审批人 user ID',
  `reject_reason`   VARCHAR(512)    NULL     COMMENT '拒绝原因',
  `gmt_create`      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `gmt_modified`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_tenant_status` (`tenant_id`, `status`),
  KEY `idx_requester` (`requester_id`, `status`),
  UNIQUE KEY `uk_workspace_access_request_pending` (`tenant_id`, `requester_id`, `pending_marker`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='工作空间权限申请';
