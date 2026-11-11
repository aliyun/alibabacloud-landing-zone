-- 新空间 Chief 初始化引导：创建者资格与四阶段进度（全局表，按 workspace_id + user_id 定位）。
-- 与 `user_setting` 同域：不随租户拦截器追加 tenant_id，读写一律带 workspace_id 与 user_id。
-- 空间创建时只为创建者写入一条 PENDING 记录；旧空间与受邀成员没有记录，因此不补弹。
-- conversation_id / client_message_id 用于发送成功后恢复：重试不会重复发送已接受的消息。
-- 请在发布代码前手动执行；不会自动执行迁移。
CREATE TABLE IF NOT EXISTS `workspace_guide_progress` (
  `id`                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `workspace_id`       BIGINT UNSIGNED NOT NULL COMMENT '所属工作空间（workspace.id）',
  `user_id`            BIGINT UNSIGNED NOT NULL COMMENT '被引导的用户，只登记空间创建者',
  `status`             VARCHAR(16)     NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/IN_PROGRESS/SKIPPED/COMPLETED',
  `stage`              TINYINT         NOT NULL DEFAULT 1 COMMENT '四阶段 1-4',
  `substep`            TINYINT         NOT NULL DEFAULT 0 COMMENT '阶段 1 的六个子步骤 0-5',
  `conversation_id`    BIGINT UNSIGNED DEFAULT NULL COMMENT '此次引导关联的 Chief 会话',
  `client_message_id`  VARCHAR(64)     DEFAULT NULL COMMENT '初始化提示词的去重标识，重试复用',
  `version`            INT             NOT NULL DEFAULT 0 COMMENT '乐观锁版本，避免多标签页互相覆盖',
  `gmt_create`         DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `gmt_modified`       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `creator_id`         BIGINT UNSIGNED DEFAULT NULL,
  `modifier_id`        BIGINT UNSIGNED DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_workspace_guide` (`workspace_id`, `user_id`),
  KEY `idx_workspace_guide_user` (`user_id`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='新空间 Chief 初始化引导资格与进度';
