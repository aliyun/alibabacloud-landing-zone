-- 修复：SDLC 步骤软删除后仍占用唯一键 uk_sdlc_order(tenant_id, sdlc_id, step_order) 的正数序号，
-- 导致再次新增步骤时唯一键冲突并报 10000 系统内部错误。
--
-- 新约定：软删除步骤时将 step_order 置为 -id，释放正数序号（见 SdlcStepDao.xml softDelete/deleteAllBySdlc）。
-- 本迁移清理历史软删除数据的序号占位，与线上唯一键兼容（uk_sdlc_order 不含 is_deleted，保持不变）。
UPDATE `sdlc_step` SET `step_order` = -`id` WHERE `is_deleted` = 1 AND `step_order` > 0;
