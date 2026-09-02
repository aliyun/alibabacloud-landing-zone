-- 修复：skill 软删除后仍占用唯一键 uk_type_name(tenant_id, type, name)，
-- 导致删除后重新上传/创建同名 skill 时唯一键冲突报错。
--
-- 新约定：软删除时将 name 置为墓碑值 '#deleted-<id>'，释放名称占位（见 SkillDao.xml softDelete）。
-- 本迁移清理历史软删除数据的名称占位，与线上唯一键兼容（uk_type_name 不含 is_deleted，保持不变）。
UPDATE `skill` SET `name` = CONCAT('#deleted-', `id`) WHERE `is_deleted` = 1;
