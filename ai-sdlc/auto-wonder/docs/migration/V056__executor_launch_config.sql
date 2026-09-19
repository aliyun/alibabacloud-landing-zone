-- 执行器启动配置服务端持久化（model / reasoningEffort / contextWindow / memoryMode）
-- launch_config: JSON，所有字段可选，未设置时为 NULL；页面「启动命令」弹窗与 MCP build_executor_launch_command 共享同一份配置
-- config_version: 启动配置乐观锁版本号，每次更新 +1，并发更新时校验不通过返回冲突
ALTER TABLE `executor`
  ADD COLUMN `launch_config` JSON NULL COMMENT '启动配置 JSON: {model, reasoningEffort, contextWindow, memoryMode}' AFTER `client_kind`,
  ADD COLUMN `config_version` INT NOT NULL DEFAULT 1 COMMENT '启动配置乐观锁版本号' AFTER `launch_config`;
