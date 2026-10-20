-- Community V076: 工单回收站（垃圾站）能力——记录软删除时间
-- 工单删除为纯软删除（is_deleted=1），此前缺少删除时间字段，无法按删除时间排序/筛选。
-- 新增 deleted_at：softDelete 时写入当前时间，restore 时置回 NULL。
-- 存量已删除工单的 deleted_at 为 NULL，展示时以 DELETE 事件时间兜底。
ALTER TABLE `workitem`
    ADD COLUMN `deleted_at` DATETIME(3) DEFAULT NULL COMMENT '软删除时间（回收站排序/筛选；NULL 表示未删除）' AFTER `is_deleted`;

-- 回收站列表按租户维度查询 is_deleted=1 并按 deleted_at 倒序
ALTER TABLE `workitem`
    ADD KEY `idx_recycled` (`tenant_id`, `is_deleted`, `deleted_at`);
