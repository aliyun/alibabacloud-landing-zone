-- 工作空间成员功能域限制（细粒度权限的最小形态）。
--
-- 背景：三级访问级别（READ_ONLY/READ_WRITE/ADMIN）是全局递进的，无法表达
-- 「允许读写工单，但禁止修改 SDLC 流程」这类按功能域收回能力的诉求。
--
-- 本迁移为 org_member 增加 forbidden_domains JSON 列，存放被禁用的功能域
-- （WorkspaceDomain 枚举名数组，如 ["SDLC","EXECUTOR"]）。语义对标 Cedar 的
-- forbid 覆盖 permit：成员先按访问级别获得能力，再按本列逐域收回。
-- NULL 表示无限制（存量行无需回填）。
--
-- 锁死防护：所有者不可被修改（WORKSPACE_OWNER_MUTATION_PROTECTED）、平台管理员
-- 不经过成员行鉴权，两条既有路径保证成员管理入口恒可恢复，无需额外守卫。
--
-- 新安装无需本迁移：docs/autowonder-schema.sql 已包含该列。
ALTER TABLE `org_member`
    ADD COLUMN `forbidden_domains` JSON NULL COMMENT '禁用的功能域（WorkspaceDomain 枚举名数组）；NULL 表示无限制'
    AFTER `identity_tags`;
