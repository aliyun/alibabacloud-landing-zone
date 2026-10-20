-- Upgrade Community V081 to workspace roles. Stop application writes and run once before starting the new build.
-- Back up org_member and workspace_access_request first. Keep legacy access_level/forbidden_domains
-- for recovery; authorization in the new build reads the joined role, never these legacy values.
-- READ_ONLY members stay read-only; READ_WRITE restrictions become read-only features.
-- ADMIN and workspace owners receive the unrestricted administrator role.
-- Rollback requires restoring the backup together with the old application; do not mix builds.

-- 工作空间角色；权限由角色统一维护，成员只保存角色绑定。
CREATE TABLE IF NOT EXISTS `workspace_role` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id` BIGINT UNSIGNED NOT NULL,
  `code` VARCHAR(64) DEFAULT NULL COMMENT '预置角色或迁移标识；自定义角色为空',
  `name` VARCHAR(80) DEFAULT NULL COMMENT '用户输入原文；系统角色按 code 本地化',
  `active_name_key` VARCHAR(80) DEFAULT NULL,
  `access_level` VARCHAR(16) NOT NULL COMMENT '内部校验能力上限；不作为成员配置项',
  `read_only_domains` JSON DEFAULT NULL COMMENT '只读功能域；NULL 表示没有额外写入限制',
  `version` INT NOT NULL DEFAULT 0,
  `gmt_create` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `gmt_modified` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `creator_id` BIGINT UNSIGNED DEFAULT NULL,
  `modifier_id` BIGINT UNSIGNED DEFAULT NULL,
  `is_deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_workspace_role_code` (`tenant_id`, `code`),
  UNIQUE KEY `uk_workspace_role_name` (`tenant_id`, `active_name_key`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='工作空间授权角色';

ALTER TABLE `org_member`
  ADD COLUMN `role_id` BIGINT UNSIGNED NULL AFTER `access_level`,
  ADD KEY `idx_member_role` (`tenant_id`, `role_id`, `is_deleted`);

START TRANSACTION;
-- 内置角色不持久化域目录：READ_ONLY 由级别语义全量只读（WorkspaceDomainGuard 按级别拒绝），
-- 目录由当前枚举推导，新增功能域无需再改历史迁移。
INSERT INTO workspace_role (tenant_id, code, access_level, read_only_domains, creator_id)
SELECT o.id, levels.code, levels.code, NULL, o.owner_id
FROM `org` o CROSS JOIN (
  SELECT 'READ_ONLY' AS code UNION ALL SELECT 'READ_WRITE' UNION ALL SELECT 'ADMIN'
) levels;

-- Preserve each existing member's write restrictions in an explicitly migrated role.
INSERT INTO workspace_role (tenant_id, code, access_level, read_only_domains, creator_id)
SELECT m.tenant_id, CONCAT('MIGRATED_', m.id), 'READ_WRITE', m.forbidden_domains, m.creator_id
FROM org_member m JOIN `org` o ON o.id = m.tenant_id
WHERE m.access_level = 'READ_WRITE' AND m.user_id <> o.owner_id
  AND m.forbidden_domains IS NOT NULL AND JSON_LENGTH(m.forbidden_domains) > 0;

-- gmt_modified 显式保持原值，避免回填触发 ON UPDATE 刷新 gmt_modified、污染成员历史。
UPDATE org_member m JOIN `org` o ON o.id = m.tenant_id
JOIN workspace_role r ON r.tenant_id = m.tenant_id AND r.code = CASE
    WHEN m.user_id = o.owner_id THEN 'ADMIN'
    WHEN m.access_level = 'READ_WRITE' AND m.forbidden_domains IS NOT NULL
         AND JSON_LENGTH(m.forbidden_domains) > 0 THEN CONCAT('MIGRATED_', m.id)
    ELSE m.access_level END
SET m.role_id = r.id, m.gmt_modified = m.gmt_modified;
COMMIT;

-- This deliberately fails on orphaned or invalid memberships instead of assigning broader access.
ALTER TABLE `org_member` MODIFY COLUMN `role_id` BIGINT UNSIGNED NOT NULL;
