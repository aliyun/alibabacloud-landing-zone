-- Existing probe/profile history and confirmed design versions are retained.
ALTER TABLE prd_design ADD COLUMN target_config_json MEDIUMTEXT DEFAULT NULL
  COMMENT '用户手工配置的目标项目快照' AFTER target_commit_sha;
