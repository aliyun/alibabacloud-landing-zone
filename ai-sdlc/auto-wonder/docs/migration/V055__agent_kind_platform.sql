-- 平台数字人（Chief of Staff）：agent 增加 kind 字段区分普通数字员工与平台智能体
ALTER TABLE `agent`
  ADD COLUMN `kind` VARCHAR(20) NOT NULL DEFAULT 'STANDARD' COMMENT 'STANDARD=普通数字员工 / PLATFORM=平台数字人',
  ADD KEY `idx_tenant_kind` (`tenant_id`, `kind`);
