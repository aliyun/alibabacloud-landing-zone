-- AutoWonder 外部工单协作。
-- 迁移版本 V038。
-- 存量数据库执行一次本迁移；新建数据库使用 docs/autowonder-schema.sql。

CREATE TABLE IF NOT EXISTS `external_principal` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `provider` VARCHAR(32) NOT NULL COMMENT '来源平台，例如 AONE 或 JIRA',
  `subject_id` VARCHAR(128) NOT NULL COMMENT '来源侧主体稳定 ID',
  `display_name` VARCHAR(256) DEFAULT NULL COMMENT '来源侧展示名称',
  `gmt_create` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `gmt_modified` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_external_principal` (`provider`, `subject_id`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='外部平台身份主体';

ALTER TABLE `external_project_binding`
  ADD COLUMN `reconcile_cursor` VARCHAR(512) DEFAULT NULL COMMENT '已关联工单分批对账游标' AFTER `last_success_at`;

ALTER TABLE `external_workitem_link`
  ADD COLUMN `external_url` VARCHAR(1024) DEFAULT NULL COMMENT '外部工单原始链接' AFTER `workitem_id`,
  ADD COLUMN `source_status_id` VARCHAR(64) DEFAULT NULL COMMENT '来源业务状态 ID' AFTER `external_url`,
  ADD COLUMN `source_status_name` VARCHAR(128) DEFAULT NULL COMMENT '来源业务状态名称' AFTER `source_status_id`,
  ADD COLUMN `source_lifecycle` VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' COMMENT '来源生命周期：ACTIVE、CLOSED、DELETED 或 UNAVAILABLE' AFTER `source_status_name`,
  ADD COLUMN `reporter_principal_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '归一化后的需求提出者身份主体' AFTER `source_lifecycle`,
  ADD COLUMN `business_owner_principal_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '归一化后的当前业务负责人身份主体' AFTER `reporter_principal_id`,
  ADD COLUMN `principal_relations_json` JSON DEFAULT NULL COMMENT '来源系统定义的身份参与关系组' AFTER `business_owner_principal_id`,
  ADD COLUMN `last_sync_at` DATETIME(3) DEFAULT NULL COMMENT '当前工单最后成功同步时间' AFTER `last_sync_direction`,
  ADD COLUMN `sync_status` VARCHAR(32) NOT NULL DEFAULT 'HEALTHY' COMMENT '同步状态：HEALTHY、DELAYED 或 ACTION_REQUIRED' AFTER `last_sync_at`,
  ADD COLUMN `last_error_code` VARCHAR(64) DEFAULT NULL COMMENT '工单级稳定错误码' AFTER `sync_status`,
  ADD COLUMN `last_error` TEXT DEFAULT NULL COMMENT '脱敏后的工单同步错误摘要' AFTER `last_error_code`,
  ADD UNIQUE KEY `uk_external_workitem_scope` (`tenant_id`, `binding_id`, `external_workitem_id`),
  DROP INDEX `uk_external_workitem`,
  ADD KEY `idx_workitem_binding_reconcile` (`binding_id`, `id`);

ALTER TABLE `external_comment_link`
  ADD COLUMN `source_updated_at` DATETIME(3) DEFAULT NULL COMMENT '来源侧评论更新时间' AFTER `direction`,
  ADD COLUMN `source_status` VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' COMMENT '来源评论状态：ACTIVE 或 DELETED' AFTER `source_updated_at`,
  ADD COLUMN `gmt_modified` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) AFTER `gmt_create`,
  ADD UNIQUE KEY `uk_external_comment_scope`
    (`tenant_id`, `binding_id`, `external_workitem_id`, `external_comment_id`),
  DROP INDEX `uk_external_comment`;
