-- 跨工作空间复制小队：复制操作记录表。
-- 唯一约束保证同一用户、源空间、请求标识及目标空间仅一条记录；
-- 成功记录与目标业务写入同事务提交，失败记录的 resource_cleanup_json
-- 记录事务外资源及补偿状态。不保存明文密钥或执行器 Token。
CREATE TABLE IF NOT EXISTS squad_copy_operation (
 id BIGINT NOT NULL AUTO_INCREMENT,
 source_workspace_id BIGINT NOT NULL,
 source_squad_id BIGINT NOT NULL,
 user_id BIGINT NOT NULL,
 request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 target_workspace_id BIGINT NOT NULL,
 request_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 source_snapshot_ref CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 status VARCHAR(16) NOT NULL,
 target_squad_id BIGINT NULL,
 error_code VARCHAR(32) NULL,
 error_message VARCHAR(512) NULL,
 resource_cleanup_json TEXT NULL,
 gmt_create DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 gmt_modified DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 PRIMARY KEY (id),
 UNIQUE KEY uk_squad_copy_request (source_workspace_id, user_id, request_id, target_workspace_id),
 KEY idx_squad_copy_source (source_workspace_id, source_squad_id),
 KEY idx_squad_copy_target (target_workspace_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
