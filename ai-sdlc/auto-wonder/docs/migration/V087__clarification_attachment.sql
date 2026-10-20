-- 工单澄清会话附件（Community V087）。纯增量：
-- 1) 消息附件先以 turn_id = NULL 落在会话下，发送时绑定到 Turn，绑定后不可删除；
-- 2) 未绑定附件由定时任务在 24 小时后清理，工单删除时按 workitem_id 级联清理；
-- 3) previewable 只标记服务端按魔数嗅探确认的位图，内联预览不信任客户端声明的 Content-Type；
-- 4) deleting_at 非 NULL 表示删除流程已认领该行：认领行不可再绑定轮次，对象删除成功后删行，
--    失败时保留认领态由孤儿清理重试，避免并发发送被误删或残留对象不可追踪。
CREATE TABLE IF NOT EXISTS `clarification_attachment` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `tenant_id` BIGINT NOT NULL,
  `workitem_id` BIGINT NOT NULL,
  `conversation_id` BIGINT NOT NULL,
  `turn_id` BIGINT NULL COMMENT 'NULL 表示已上传未发送',
  `uploader_user_id` BIGINT NOT NULL,
  `name` VARCHAR(256) NOT NULL,
  `content_type` VARCHAR(128) NOT NULL DEFAULT 'application/octet-stream',
  `size_bytes` BIGINT NOT NULL,
  `oss_ref` VARCHAR(512) NOT NULL,
  `previewable` TINYINT NOT NULL DEFAULT 0 COMMENT '1 = 服务端嗅探确认的位图，可内联预览',
  `deleting_at` DATETIME(3) NULL COMMENT '非 NULL 表示删除认领中，禁止绑定，删除失败由清理任务重试',
  `gmt_create` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_clarification_attachment_oss_ref` (`oss_ref`),
  KEY `idx_clarification_attachment_turn` (`tenant_id`, `conversation_id`, `turn_id`),
  KEY `idx_clarification_attachment_orphan` (`turn_id`, `gmt_create`),
  KEY `idx_clarification_attachment_deleting` (`deleting_at`),
  KEY `idx_clarification_attachment_workitem` (`tenant_id`, `workitem_id`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='工单澄清会话附件';
