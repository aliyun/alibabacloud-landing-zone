-- =============================================================================
-- AutoWonder 全库 DDL（MySQL 8 / InnoDB / utf8mb4）
-- 用途：社区版新环境的完整数据库初始化基线
-- 说明：
--   1. 全局约定（详设 01 §1.1）—— 业务表统一含基础字段：
--        gmt_create / gmt_modified / creator_id / modifier_id / is_deleted / version（并发敏感表）
--   2. 除全局表（user）外，所有业务表含 tenant_id（= org.id），行级租户隔离。
--   3. 主键为 BIGINT UNSIGNED AUTO_INCREMENT（从 10000 起）。
--   4. 外键关系以索引表达，不建物理 FK（便于分库与软删）。
-- =============================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- =============================================================================
-- 详设 01 — 身份、租户与组织访问等级
-- =============================================================================

-- 用户（全局表，无 tenant_id）
CREATE TABLE IF NOT EXISTS `user` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `username`      VARCHAR(64)     NOT NULL COMMENT '登录名，全局唯一',
  `email`         VARCHAR(128)    DEFAULT NULL COMMENT '邮箱，全局唯一（可空）',
  `password_hash` VARCHAR(100)    NOT NULL COMMENT 'BCrypt 哈希（含盐）',
  `nickname`      VARCHAR(64)     DEFAULT NULL COMMENT '昵称',
  `avatar_url`    VARCHAR(512)    DEFAULT NULL COMMENT '头像（OSS 引用）',
  `phone`         VARCHAR(32)     DEFAULT NULL COMMENT '联系方式',
  `status`        TINYINT         NOT NULL DEFAULT 0 COMMENT '0 正常 / 1 禁用',
  `deactivated_at`          DATETIME(3) DEFAULT NULL COMMENT '注销申请时间',
  `cooling_off_expires_at`  DATETIME(3) DEFAULT NULL COMMENT '冷静期截止时间（7天后）',
  `deactivation_revoked_at` DATETIME(3) DEFAULT NULL COMMENT '撤销注销时间',
  `last_login_at` DATETIME(3)     DEFAULT NULL COMMENT '最近登录',
  `gmt_create`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `gmt_modified`  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `is_deleted`    TINYINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`),
  UNIQUE KEY `uk_email` (`email`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='用户/员工（全局）';

-- 平台品牌配置（全局表，无 tenant_id；私有化部署使用）
CREATE TABLE IF NOT EXISTS `platform_branding_config` (
  `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `platform_name`     VARCHAR(128)    NOT NULL DEFAULT 'AutoWonder' COMMENT '平台展示名称',
  `logo_oss_ref`      VARCHAR(512)    DEFAULT NULL COMMENT 'Logo 对象存储引用',
  `logo_content_type` VARCHAR(128)    DEFAULT NULL COMMENT 'Logo MIME 类型',
  `theme_key`         VARCHAR(64)     NOT NULL DEFAULT 'aliyun-orange' COMMENT '主题配色 key',
  `primary_color`     VARCHAR(16)     NOT NULL DEFAULT '#f97316' COMMENT '主品牌色',
  `domain`            VARCHAR(512)    DEFAULT NULL COMMENT '私有化部署访问域名',
  `creator_id`        BIGINT UNSIGNED DEFAULT NULL,
  `modifier_id`       BIGINT UNSIGNED DEFAULT NULL,
  `gmt_create`        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `gmt_modified`      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `is_deleted`        TINYINT         NOT NULL DEFAULT 0,
  `version`           INT             NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='平台品牌与私有化部署一致性配置';

INSERT INTO `platform_branding_config`
    (`id`, `platform_name`, `theme_key`, `primary_color`, `domain`, `is_deleted`, `version`)
VALUES
    (1, 'AutoWonder', 'aliyun-orange', '#f97316', NULL, 0, 0)
ON DUPLICATE KEY UPDATE `id` = `id`;

-- 平台 IM 通知通道配置（全局表，无 tenant_id）
CREATE TABLE IF NOT EXISTS `platform_im_channel_config` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `provider`       VARCHAR(32)     NOT NULL COMMENT 'IM provider canonical key',
  `enabled`        TINYINT         NOT NULL DEFAULT 0 COMMENT '是否启用',
  `app_key`        VARCHAR(128)    DEFAULT NULL COMMENT 'Provider application key',
  `credential_ref` TEXT            DEFAULT NULL COMMENT 'SecretCrypto 加密密文',
  `robot_code`     VARCHAR(128)    DEFAULT NULL COMMENT '机器人编码',
  `base_url`       VARCHAR(512)    DEFAULT NULL COMMENT 'Provider API base URL',
  `creator_id`     BIGINT UNSIGNED DEFAULT NULL,
  `modifier_id`    BIGINT UNSIGNED DEFAULT NULL,
  `gmt_create`     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `gmt_modified`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `is_deleted`     TINYINT         NOT NULL DEFAULT 0,
  `version`        INT             NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_platform_im_provider` (`provider`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='平台级 IM 指派通知通道配置';

-- 用户 IM 身份（全局表，无 tenant_id）
CREATE TABLE IF NOT EXISTS `user_im_identity` (
  `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id`          BIGINT UNSIGNED NOT NULL COMMENT '全局 user_id',
  `provider`         VARCHAR(32)     NOT NULL COMMENT 'IM provider canonical key',
  `external_user_id` VARCHAR(256)    NOT NULL COMMENT '用户在 IM provider 中的身份',
  `creator_id`       BIGINT UNSIGNED DEFAULT NULL,
  `modifier_id`      BIGINT UNSIGNED DEFAULT NULL,
  `gmt_create`       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `gmt_modified`     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `is_deleted`       TINYINT         NOT NULL DEFAULT 0,
  `version`          INT             NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_im_provider` (`user_id`, `provider`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='用户 IM 身份（全局）';

-- 组织（租户）
CREATE TABLE IF NOT EXISTS `org` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '组织ID = tenant_id',
  `name`         VARCHAR(128)    NOT NULL COMMENT '组织名',
  `slug`         VARCHAR(64)     DEFAULT NULL COMMENT '唯一短标识（邀请链接用）',
  `description`  VARCHAR(512)    DEFAULT NULL COMMENT '描述',
  `background`   TEXT            DEFAULT NULL COMMENT '组织背景',
  `owner_id`     BIGINT UNSIGNED NOT NULL COMMENT '创建者/负责人 user_id',
  `status`       TINYINT         NOT NULL DEFAULT 0 COMMENT '0 正常 / 1 停用',
  `gmt_create`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `gmt_modified` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `creator_id`   BIGINT UNSIGNED DEFAULT NULL,
  `modifier_id`  BIGINT UNSIGNED DEFAULT NULL,
  `is_deleted`   TINYINT         NOT NULL DEFAULT 0,
  `version`      INT             NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_name` (`name`),
  UNIQUE KEY `uk_slug` (`slug`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='组织（租户）';

-- 组织成员
CREATE TABLE IF NOT EXISTS `org_member` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id`    BIGINT UNSIGNED NOT NULL COMMENT '= org_id',
  `user_id`      BIGINT UNSIGNED NOT NULL COMMENT '成员',
  `status`       TINYINT         NOT NULL DEFAULT 0 COMMENT '0 正常 / 1 待审批 / 2 已移除',
  `access_level` VARCHAR(16)     NOT NULL DEFAULT 'READ_ONLY' COMMENT 'READ_ONLY/READ_WRITE/ADMIN',
  `identity_tags` JSON           DEFAULT NULL COMMENT '成员业务身份标签；仅用于协作上下文，不参与鉴权',
  `joined_at`    DATETIME(3)     DEFAULT NULL,
  `gmt_create`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `gmt_modified` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `creator_id`   BIGINT UNSIGNED DEFAULT NULL,
  `modifier_id`  BIGINT UNSIGNED DEFAULT NULL,
  `is_deleted`   TINYINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_org_user` (`tenant_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='组织成员';

-- 组织邀请
CREATE TABLE IF NOT EXISTS `org_invite` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id`    BIGINT UNSIGNED NOT NULL,
  `code`         VARCHAR(64)     NOT NULL COMMENT '邀请码，唯一',
  `inviter_id`   BIGINT UNSIGNED NOT NULL,
  `target_email` VARCHAR(128)    DEFAULT NULL COMMENT '定向邀请邮箱（可空）',
  `expire_at`    DATETIME(3)     DEFAULT NULL,
  `status`       TINYINT         NOT NULL DEFAULT 0 COMMENT '0 有效 / 1 已用 / 2 失效',
  `gmt_create`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `gmt_modified` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `is_deleted`   TINYINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code` (`code`),
  KEY `idx_tenant` (`tenant_id`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='组织邀请';

-- 审计日志（详设 01 §1.7；详设 08 §2 消费查询，只读不可改）
CREATE TABLE IF NOT EXISTS `audit_log` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id`   BIGINT UNSIGNED NOT NULL,
  `actor_id`    BIGINT UNSIGNED DEFAULT NULL COMMENT '操作人 user_id',
  `module`      VARCHAR(64)     NOT NULL COMMENT '模块',
  `action`      VARCHAR(64)     NOT NULL COMMENT '动作',
  `target_type` VARCHAR(64)     DEFAULT NULL,
  `target_id`   BIGINT UNSIGNED DEFAULT NULL,
  `detail_json` JSON            DEFAULT NULL COMMENT '细节（密钥/凭据已脱敏）',
  `gmt_create`  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_tenant_time` (`tenant_id`, `gmt_create`),
  KEY `idx_module_action` (`tenant_id`, `module`, `action`),
  KEY `idx_actor` (`tenant_id`, `actor_id`),
  KEY `idx_target` (`tenant_id`, `target_type`, `target_id`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='审计/操作日志';

-- =============================================================================
-- 详设 02 — 核心执行闭环（状态机 + 工单 + 澄清 + 执行器 + 调度 + 产物）
-- =============================================================================

-- 状态模版
CREATE TABLE IF NOT EXISTS `status_template` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id`    BIGINT UNSIGNED NOT NULL,
  `work_type`    VARCHAR(16)     NOT NULL COMMENT 'REQ/TASK/BUG',
  `name`         VARCHAR(128)    NOT NULL,
  `is_default`   TINYINT         NOT NULL DEFAULT 0 COMMENT '该类型默认模版',
  `gmt_create`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `gmt_modified` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `creator_id`   BIGINT UNSIGNED DEFAULT NULL,
  `modifier_id`  BIGINT UNSIGNED DEFAULT NULL,
  `is_deleted`   TINYINT         NOT NULL DEFAULT 0,
  `version`      INT             NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_tenant_type` (`tenant_id`, `work_type`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='状态模版';

-- 状态节点
CREATE TABLE IF NOT EXISTS `status_node` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id`   BIGINT UNSIGNED NOT NULL,
  `template_id` BIGINT UNSIGNED NOT NULL,
  `code`        VARCHAR(64)     NOT NULL COMMENT '如 new/developing/verifying/released',
  `name`        VARCHAR(128)    NOT NULL,
  `category`    VARCHAR(16)     NOT NULL COMMENT 'INIT/IN_PROGRESS/DONE/CANCELED',
  `sort`        INT             NOT NULL DEFAULT 0,
  `gmt_create`  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_template_code` (`template_id`, `code`),
  KEY `idx_tenant_template` (`tenant_id`, `template_id`),
  KEY `idx_status_node_participation` (`template_id`, `code`, `category`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='状态节点';

-- 状态迁移（允许的有向边）
CREATE TABLE IF NOT EXISTS `status_transition` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id`    BIGINT UNSIGNED NOT NULL,
  `template_id`  BIGINT UNSIGNED NOT NULL,
  `from_node_id` BIGINT UNSIGNED NOT NULL,
  `to_node_id`   BIGINT UNSIGNED NOT NULL,
  `name`         VARCHAR(128)    DEFAULT NULL,
  `gmt_create`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_transition` (`template_id`, `from_node_id`, `to_node_id`),
  KEY `idx_tenant_template` (`tenant_id`, `template_id`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='状态迁移边';

-- 工单
CREATE TABLE IF NOT EXISTS `workitem` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id`       BIGINT UNSIGNED NOT NULL,
  `work_type`       VARCHAR(16)     NOT NULL COMMENT 'REQ/TASK/BUG',
  `title`           VARCHAR(256)    NOT NULL,
  `content_md`      MEDIUMTEXT      DEFAULT NULL COMMENT '正文（Markdown）',
  `template_id`     BIGINT UNSIGNED DEFAULT NULL COMMENT '引用状态模版',
  `status_node_id`  BIGINT UNSIGNED DEFAULT NULL COMMENT '当前状态',
  `sdlc_id`         BIGINT UNSIGNED DEFAULT NULL COMMENT '绑定 SDLC（指派数字员工时必需）',
  `current_step_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '当前 SDLC 步骤',
  `assignee_type`   VARCHAR(16)     DEFAULT NULL COMMENT 'HUMAN/AGENT',
  `assignee_ref`    BIGINT UNSIGNED DEFAULT NULL COMMENT 'user_id 或 agent_id',
  `assign_operator_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '指派操作人（触发指派动作的真人 user_id；用于交接无下一跳时兜底路由，可空）',
  `priority`        TINYINT         NOT NULL DEFAULT 0,
  `gmt_create`      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `gmt_modified`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `creator_id`      BIGINT UNSIGNED DEFAULT NULL,
  `modifier_id`     BIGINT UNSIGNED DEFAULT NULL,
  `is_deleted`      TINYINT         NOT NULL DEFAULT 0,
  `version`         INT             NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_status` (`tenant_id`, `status_node_id`),
  KEY `idx_assignee` (`tenant_id`, `assignee_type`, `assignee_ref`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='工单';

-- 工单评论
CREATE TABLE IF NOT EXISTS `workitem_comment` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id`   BIGINT UNSIGNED NOT NULL,
  `workitem_id` BIGINT UNSIGNED NOT NULL,
  `author_type` VARCHAR(16)     NOT NULL COMMENT 'HUMAN/AGENT',
  `author_ref`  BIGINT UNSIGNED NOT NULL,
  `content_md`  MEDIUMTEXT      DEFAULT NULL,
  `gmt_create`  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_workitem` (`tenant_id`, `workitem_id`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='工单评论';

-- 工单评论mention明细
CREATE TABLE IF NOT EXISTS `workitem_comment_mention` (
  `id`                    BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id`             BIGINT UNSIGNED NOT NULL,
  `workitem_id`           BIGINT UNSIGNED NOT NULL,
  `comment_id`            BIGINT UNSIGNED NOT NULL,
  `target_type`           VARCHAR(16)     NOT NULL COMMENT 'AGENT/HUMAN',
  `target_ref`            BIGINT UNSIGNED NOT NULL,
  `display_name_snapshot` VARCHAR(128)    DEFAULT NULL,
  `gmt_create`            DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_comment_mention_target` (`tenant_id`, `comment_id`, `target_type`, `target_ref`),
  KEY `idx_workitem_mention_target` (`tenant_id`, `workitem_id`, `target_type`, `target_ref`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='工单评论mention明细';

-- 工单事件（时间线/审计）
CREATE TABLE IF NOT EXISTS `workitem_event` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id`   BIGINT UNSIGNED NOT NULL,
  `workitem_id` BIGINT UNSIGNED NOT NULL,
  `event_type`  VARCHAR(32)     NOT NULL COMMENT 'CREATE/EDIT/STATUS_CHANGE/ASSIGN/DISPATCH/RESULT/COMMENT',
  `from_val`    VARCHAR(256)    DEFAULT NULL,
  `to_val`      VARCHAR(256)    DEFAULT NULL,
  `actor_type`  VARCHAR(16)     DEFAULT NULL COMMENT 'HUMAN/AGENT/SYSTEM',
  `actor_ref`   BIGINT UNSIGNED DEFAULT NULL,
  `detail_json` JSON            DEFAULT NULL,
  `gmt_create`  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_workitem_time` (`tenant_id`, `workitem_id`, `gmt_create`),
  KEY `idx_workitem_event_participation` (`tenant_id`, `workitem_id`, `event_type`, `gmt_create`, `id`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='工单事件时间线';

-- 需求澄清材料（对话历史见 ai_message，详设 04）
CREATE TABLE IF NOT EXISTS `clarification` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id`    BIGINT UNSIGNED NOT NULL,
  `workitem_id`  BIGINT UNSIGNED NOT NULL,
  `content_md`   MEDIUMTEXT      DEFAULT NULL COMMENT '澄清材料（结构化结论）',
  `version`      INT             NOT NULL DEFAULT 0,
  `gmt_create`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `gmt_modified` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_workitem` (`tenant_id`, `workitem_id`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='需求澄清材料';

-- 执行器接入
CREATE TABLE IF NOT EXISTS `executor` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id`      BIGINT UNSIGNED NOT NULL,
  `agent_id`       BIGINT UNSIGNED NOT NULL COMMENT '归属数字员工',
  `name`           VARCHAR(128)    NOT NULL,
  `token_ref`      VARCHAR(256)    NULL     COMMENT '可解析的 WS 鉴权 token 引用',
  `status`         VARCHAR(16)     NOT NULL DEFAULT 'OFFLINE' COMMENT 'OFFLINE/ONLINE/BUSY',
  `last_heartbeat` DATETIME(3)     DEFAULT NULL,
  `last_connect_ip` VARCHAR(64)    DEFAULT NULL COMMENT '最近一次成功 WebSocket 接入 IP',
  `client_kind`    VARCHAR(64)     DEFAULT NULL COMMENT '客户端形态（claude-cli/qoder-cli）',
  `gmt_create`     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `gmt_modified`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `creator_id`     BIGINT UNSIGNED DEFAULT NULL,
  `modifier_id`    BIGINT UNSIGNED DEFAULT NULL,
  `is_deleted`     TINYINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_agent` (`tenant_id`, `agent_id`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='执行器接入';

-- 调度派发
CREATE TABLE IF NOT EXISTS `dispatch` (
  `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id`        BIGINT UNSIGNED NOT NULL,
  `workitem_id`      BIGINT UNSIGNED NOT NULL,
  `sdlc_step_id`     BIGINT UNSIGNED DEFAULT NULL COMMENT '当前 SDLC 步骤',
  `agent_id`         BIGINT UNSIGNED NOT NULL COMMENT '目标数字员工',
  `agent_version_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '本次派发使用的在线版本（冻结装配依据）',
  `executor_id`      BIGINT UNSIGNED DEFAULT NULL COMMENT '选中执行器（派发后填）',
  `package_oss_ref`  VARCHAR(512)    DEFAULT NULL COMMENT '任务包 zip 的 OSS 引用',
  `status`           VARCHAR(32)     NOT NULL DEFAULT 'PENDING'
                     COMMENT 'PENDING/PACKAGING/DISPATCHED/ACKED/RUNNING/PAUSING/PAUSED/PAUSE_FAILED/WAITING_FOR_PAUSE/SUCCEEDED/FAILED/TIMEOUT/CANCELED',
  `attempt`          INT             NOT NULL DEFAULT 0 COMMENT '重试次数',
  `idempotency_key`  VARCHAR(128)    NOT NULL COMMENT '幂等键 = workitemId+stepId+attempt',
  `result_summary`   MEDIUMTEXT      DEFAULT NULL COMMENT '执行结论/总结（无 CONCLUSION 产物时作队友结论）',
  `error`            VARCHAR(512)    DEFAULT NULL COMMENT '失败原因',
  `resume_from_dispatch_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '恢复或返工复用的来源派发',
  `delivery_source_dispatch_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '前序权威交付派发，仅用于继承结论、产物与源码版本',
  `resume_mode`      VARCHAR(32)     DEFAULT NULL COMMENT 'RECOVERY/RETURNING_WORKER',
  `gmt_create`       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `gmt_modified`     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `creator_id`       BIGINT UNSIGNED DEFAULT NULL,
  `modifier_id`      BIGINT UNSIGNED DEFAULT NULL,
  `is_deleted`       TINYINT         NOT NULL DEFAULT 0,
  `version`          INT             NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_idempotency` (`tenant_id`, `idempotency_key`),
  KEY `idx_workitem` (`tenant_id`, `workitem_id`),
  KEY `idx_status` (`tenant_id`, `status`),
  KEY `idx_resume_from` (`tenant_id`, `resume_from_dispatch_id`),
  KEY `idx_delivery_source` (`tenant_id`, `delivery_source_dispatch_id`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='调度派发';

CREATE TABLE IF NOT EXISTS `dispatch_recovery_checkpoint` (
  `id`                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id`           BIGINT UNSIGNED NOT NULL,
  `workitem_id`         BIGINT UNSIGNED NOT NULL,
  `dispatch_id`         BIGINT UNSIGNED NOT NULL,
  `agent_id`            BIGINT UNSIGNED NOT NULL,
  `checkpoint_seq`      BIGINT NOT NULL,
  `provider`            VARCHAR(32) DEFAULT NULL,
  `provider_session_id` VARCHAR(256) DEFAULT NULL,
  `runtime_id`          VARCHAR(128) DEFAULT NULL,
  `executor_id`         BIGINT UNSIGNED DEFAULT NULL,
  `active_step_id`      VARCHAR(128) DEFAULT NULL,
  `oss_ref`             VARCHAR(512) NOT NULL,
  `sha256`              VARCHAR(80) NOT NULL,
  `size_bytes`          BIGINT NOT NULL,
  `gmt_create`          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dispatch_seq` (`tenant_id`, `dispatch_id`, `checkpoint_seq`),
  KEY `idx_dispatch_latest` (`tenant_id`, `dispatch_id`, `id`),
  KEY `idx_worker_latest` (`tenant_id`, `workitem_id`, `agent_id`, `id`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='Runtime 可恢复检查点';

-- 派发运行时事件（客户端 TASK_PROGRESS 中的 step.* / agent.progress 等白名单事件）
CREATE TABLE IF NOT EXISTS `dispatch_runtime_event` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id`   BIGINT UNSIGNED NOT NULL,
  `workitem_id` BIGINT UNSIGNED NOT NULL,
  `dispatch_id` BIGINT UNSIGNED NOT NULL COMMENT '来源派发',
  `agent_id`    BIGINT UNSIGNED NOT NULL COMMENT '来源数字员工',
	`event_id`    VARCHAR(128)    DEFAULT NULL COMMENT 'Runtime 幂等事件 ID',
	`seq`         BIGINT          DEFAULT NULL COMMENT 'Runtime dispatch 内单调序号',
  `event_type`  VARCHAR(64)     NOT NULL COMMENT 'step.started/step.completed/agent.progress/dispatch.* 等',
  `step_id`     BIGINT UNSIGNED DEFAULT NULL COMMENT '客户端上报的步骤ID（可空）',
  `step_key`    VARCHAR(128)    DEFAULT NULL COMMENT '客户端上报的步骤编码/键（可空）',
  `step_order`  INT             DEFAULT NULL COMMENT '客户端上报的步骤序号（可空）',
  `step_name`   VARCHAR(128)    DEFAULT NULL COMMENT '客户端上报的步骤名称（可空）',
  `message`     VARCHAR(1024)   DEFAULT NULL COMMENT '进度摘要/明细',
  `error`       VARCHAR(1024)   DEFAULT NULL COMMENT '错误摘要',
  `detail_json` JSON            DEFAULT NULL COMMENT '完整 runtime event 原始 JSON',
  `event_time`  DATETIME(3)     DEFAULT NULL COMMENT '客户端事件时间（可空）',
  `gmt_create`  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_workitem` (`tenant_id`, `workitem_id`, `id`),
  KEY `idx_dispatch` (`tenant_id`, `dispatch_id`, `id`),
	UNIQUE KEY `uk_dispatch_event` (`tenant_id`, `dispatch_id`, `event_id`),
  KEY `idx_agent_step` (`tenant_id`, `agent_id`, `step_order`, `id`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='派发运行时事件';

-- 产物中心
CREATE TABLE IF NOT EXISTS `artifact` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id`   BIGINT UNSIGNED NOT NULL,
  `workitem_id` BIGINT UNSIGNED NOT NULL,
  `dispatch_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '来源派发',
  `name`        VARCHAR(256)    NOT NULL,
  `type`        VARCHAR(32)     NOT NULL COMMENT 'FILE/LOG/PATCH/REPORT/CONCLUSION...',
  `oss_ref`     VARCHAR(512)    NOT NULL,
  `size`        BIGINT UNSIGNED DEFAULT NULL,
  `meta_json`   JSON            DEFAULT NULL,
  `gmt_create`  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_workitem` (`tenant_id`, `workitem_id`),
  KEY `idx_dispatch` (`tenant_id`, `dispatch_id`),
  UNIQUE KEY `uk_artifact_dispatch_name` (`tenant_id`, `dispatch_id`, `name`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='产物';

-- =============================================================================
-- 详设 03 — 数字员工体系（Agent 版本化 + 小队）
-- =============================================================================

-- 数字员工（稳定身份）
CREATE TABLE IF NOT EXISTS `agent` (
  `id`                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id`          BIGINT UNSIGNED NOT NULL,
  `name`               VARCHAR(128)    NOT NULL COMMENT '展示名',
  `avatar_url`         VARCHAR(512)    DEFAULT NULL,
  `status`             VARCHAR(20)     NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/PENDING_REVIEW/ONLINE/OFFLINE',
  `online_version_id`  BIGINT UNSIGNED DEFAULT NULL COMMENT '当前在线版本（调度只读此版本）',
  `editing_version_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '当前草稿版本',
  `latest_version_no`  INT             NOT NULL DEFAULT 0 COMMENT '最近版本号（发号用）',
  `gmt_create`         DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `gmt_modified`       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `creator_id`         BIGINT UNSIGNED DEFAULT NULL,
  `modifier_id`        BIGINT UNSIGNED DEFAULT NULL,
  `is_deleted`         TINYINT         NOT NULL DEFAULT 0,
  `version`            INT             NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_status` (`tenant_id`, `status`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='数字员工（身份）';

-- 数字员工版本（配置快照头）
CREATE TABLE IF NOT EXISTS `agent_version` (
  `id`                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id`           BIGINT UNSIGNED NOT NULL,
  `agent_id`            BIGINT UNSIGNED NOT NULL,
  `version_no`          INT             NOT NULL COMMENT '该 agent 内单调递增',
  `status`              VARCHAR(20)     NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/PENDING_REVIEW/APPROVED/REJECTED',
  `role_name`           VARCHAR(128)    DEFAULT NULL COMMENT '角色名/职责标识（队友目录组织）',
  `role_code`           VARCHAR(64)     DEFAULT NULL COMMENT '角色机器码（按角色路由）',
  `business_background` MEDIUMTEXT      DEFAULT NULL COMMENT 'SOUL.md 内容（Markdown；REST 字段 businessBackground）',
  `responsibilities`    MEDIUMTEXT      DEFAULT NULL COMMENT 'AGENT.md 内容（Markdown；REST 字段 responsibilities）',
  `sdlc_id`             BIGINT UNSIGNED DEFAULT NULL COMMENT '绑定 SDLC',
  `identity_json`       JSON            DEFAULT NULL COMMENT '冻结身份快照（供 identity.json）',
  `reviewer_id`         BIGINT UNSIGNED DEFAULT NULL,
  `review_comment`      VARCHAR(512)    DEFAULT NULL,
  `reviewed_at`         DATETIME(3)     DEFAULT NULL,
  `gmt_create`          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `gmt_modified`        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `creator_id`          BIGINT UNSIGNED DEFAULT NULL,
  `modifier_id`         BIGINT UNSIGNED DEFAULT NULL,
  `is_deleted`          TINYINT         NOT NULL DEFAULT 0,
  `version`             INT             NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_ver` (`agent_id`, `version_no`),
  KEY `idx_agent_status` (`tenant_id`, `agent_id`, `status`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='数字员工版本';

-- 版本维度：仓库权限
CREATE TABLE IF NOT EXISTS `agent_repo_perm` (
  `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id`        BIGINT UNSIGNED NOT NULL,
  `agent_version_id` BIGINT UNSIGNED NOT NULL,
  `repo_id`          BIGINT UNSIGNED NOT NULL,
  `perm_level`       VARCHAR(16)     NOT NULL DEFAULT 'READ' COMMENT 'READ/WRITE',
  `gmt_create`       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ver_repo` (`agent_version_id`, `repo_id`),
  KEY `idx_repo` (`tenant_id`, `repo_id`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='版本-仓库权限';

-- 版本维度：技能清单
CREATE TABLE IF NOT EXISTS `agent_skill` (
  `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id`        BIGINT UNSIGNED NOT NULL,
  `agent_version_id` BIGINT UNSIGNED NOT NULL,
  `skill_id`         BIGINT UNSIGNED NOT NULL,
  `gmt_create`       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ver_skill` (`agent_version_id`, `skill_id`),
  KEY `idx_skill` (`tenant_id`, `skill_id`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='版本-技能';

-- 版本维度：记忆引用
CREATE TABLE IF NOT EXISTS `agent_memory_ref` (
  `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id`        BIGINT UNSIGNED NOT NULL,
  `agent_version_id` BIGINT UNSIGNED NOT NULL,
  `memory_id`        BIGINT UNSIGNED NOT NULL,
  `source`           VARCHAR(20)     NOT NULL DEFAULT 'DIRECT' COMMENT 'DIRECT/ORG_IMPORT/SQUAD_IMPORT/AGENT_IMPORT',
  `gmt_create`       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ver_memory` (`agent_version_id`, `memory_id`),
  KEY `idx_memory` (`tenant_id`, `memory_id`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='版本-记忆引用';

-- 小队
CREATE TABLE IF NOT EXISTS `squad` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id`    BIGINT UNSIGNED NOT NULL,
  `name`         VARCHAR(128)    NOT NULL,
  `description`  VARCHAR(512)    DEFAULT NULL,
  `owner_id`     BIGINT UNSIGNED DEFAULT NULL COMMENT '负责人（可空）',
  `status`       TINYINT         NOT NULL DEFAULT 0 COMMENT '0 正常 / 1 解散',
  `gmt_create`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `gmt_modified` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `creator_id`   BIGINT UNSIGNED DEFAULT NULL,
  `modifier_id`  BIGINT UNSIGNED DEFAULT NULL,
  `is_deleted`   TINYINT         NOT NULL DEFAULT 0,
  `version`      INT             NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_tenant` (`tenant_id`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='小队';

-- 小队成员（agent ↔ squad 多对多）
CREATE TABLE IF NOT EXISTS `squad_member` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id`  BIGINT UNSIGNED NOT NULL,
  `squad_id`   BIGINT UNSIGNED NOT NULL,
  `agent_id`   BIGINT UNSIGNED NOT NULL,
  `gmt_create` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_squad_agent` (`tenant_id`, `squad_id`, `agent_id`),
  KEY `idx_agent` (`tenant_id`, `agent_id`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='小队成员';

-- =============================================================================
-- 详设 04 — AI 协作引擎（会话 + 消息）
-- =============================================================================

-- AI 会话
CREATE TABLE IF NOT EXISTS `ai_session` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id`       BIGINT UNSIGNED NOT NULL,
  `scene`           VARCHAR(20)     NOT NULL COMMENT 'REPO_SCAN/MEMORY_IMPORT/SDLC_GEN/CLARIFICATION',
  `biz_ref_type`    VARCHAR(16)     DEFAULT NULL COMMENT 'REPO/MEMORY/SDLC/WORKITEM/NONE',
  `biz_ref_id`      BIGINT UNSIGNED DEFAULT NULL,
  `status`          VARCHAR(16)     NOT NULL DEFAULT 'QUEUED'
                    COMMENT 'QUEUED/RUNNING/WAIT_USER/COMPLETED/FAILED/CANCELED',
  `cli_session_ref` VARCHAR(128)    DEFAULT NULL COMMENT 'CLI 侧会话标识（--resume）',
  `node_id`         VARCHAR(64)     DEFAULT NULL COMMENT '执行节点',
  `result_json`     JSON            DEFAULT NULL COMMENT '最新结构化结果（待确认）',
  `error`           VARCHAR(512)    DEFAULT NULL,
  `gmt_create`      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `gmt_modified`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `creator_id`      BIGINT UNSIGNED DEFAULT NULL,
  `modifier_id`     BIGINT UNSIGNED DEFAULT NULL,
  `is_deleted`      TINYINT         NOT NULL DEFAULT 0,
  `version`         INT             NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_scene_ref` (`tenant_id`, `scene`, `biz_ref_id`),
  KEY `idx_status` (`tenant_id`, `status`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='AI 会话';

-- AI 消息（四场景通用对话历史）
CREATE TABLE IF NOT EXISTS `ai_message` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id`  BIGINT UNSIGNED NOT NULL,
  `session_id` BIGINT UNSIGNED NOT NULL,
  `seq`        INT             NOT NULL COMMENT '会话内序号',
  `role`       VARCHAR(16)     NOT NULL COMMENT 'USER/AI/SYSTEM',
  `content`    MEDIUMTEXT      DEFAULT NULL,
  `meta_json`  JSON            DEFAULT NULL COMMENT '引用/附件/结构片段',
  `gmt_create` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_session_seq` (`session_id`, `seq`),
  KEY `idx_tenant` (`tenant_id`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='AI 消息';

-- =============================================================================
-- 详设 05 — 资产与知识（仓库 + 记忆 + 技能）
-- =============================================================================

-- 代码仓库
CREATE TABLE IF NOT EXISTS `repo` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id`      BIGINT UNSIGNED NOT NULL,
  `name`           VARCHAR(128)    NOT NULL,
  `url`            VARCHAR(512)    NOT NULL COMMENT '仓库地址（https/ssh）',
  `default_branch` VARCHAR(128)    DEFAULT NULL COMMENT '默认分支',
  `description`    VARCHAR(512)    DEFAULT NULL,
  `scan_status`    VARCHAR(16)     NOT NULL DEFAULT 'UNSCANNED' COMMENT 'UNSCANNED/SCANNING/CONCLUDED',
  `gmt_create`     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `gmt_modified`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `creator_id`     BIGINT UNSIGNED DEFAULT NULL,
  `modifier_id`    BIGINT UNSIGNED DEFAULT NULL,
  `is_deleted`     TINYINT         NOT NULL DEFAULT 0,
  `version`        INT             NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_name` (`tenant_id`, `name`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='代码仓库';

-- 仓库结论（AI 扫描 + 人确认定稿）
CREATE TABLE IF NOT EXISTS `repo_conclusion` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id`     BIGINT UNSIGNED NOT NULL,
  `repo_id`       BIGINT UNSIGNED NOT NULL,
  `purpose`       TEXT            DEFAULT NULL COMMENT '仓库作用',
  `key_business`  JSON            DEFAULT NULL COMMENT '关键业务信息（数组）',
  `upstreams`     JSON            DEFAULT NULL COMMENT '业务上游',
  `downstreams`   JSON            DEFAULT NULL COMMENT '业务下游',
  `summary_md`    MEDIUMTEXT      DEFAULT NULL COMMENT '结论正文（Markdown）',
  `ai_session_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '来源 AI 会话（可空：手工）',
  `version`       INT             NOT NULL DEFAULT 0 COMMENT '结论版本',
  `gmt_create`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `gmt_modified`  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `creator_id`    BIGINT UNSIGNED DEFAULT NULL,
  `modifier_id`   BIGINT UNSIGNED DEFAULT NULL,
  `is_deleted`    TINYINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_repo` (`tenant_id`, `repo_id`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='仓库结论';

-- 仓库关系（repo-map）
CREATE TABLE IF NOT EXISTS `repo_relation` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id`     BIGINT UNSIGNED NOT NULL,
  `from_repo_id`  BIGINT UNSIGNED NOT NULL,
  `to_repo_id`    BIGINT UNSIGNED NOT NULL,
  `relation_type` VARCHAR(32)     NOT NULL COMMENT 'FRONTEND_OF/BACKEND_OF/GATEWAY_OF/DEPENDS_ON/RELATED',
  `description`   VARCHAR(512)    DEFAULT NULL,
  `ai_session_id` BIGINT UNSIGNED DEFAULT NULL,
  `gmt_create`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `gmt_modified`  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `creator_id`    BIGINT UNSIGNED DEFAULT NULL,
  `modifier_id`   BIGINT UNSIGNED DEFAULT NULL,
  `is_deleted`    TINYINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_relation` (`tenant_id`, `from_repo_id`, `to_repo_id`, `relation_type`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='仓库关系（repo-map）';

-- 记忆
CREATE TABLE IF NOT EXISTS `memory` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id`    BIGINT UNSIGNED NOT NULL,
  `scope`        VARCHAR(16)     NOT NULL COMMENT 'AGENT/SQUAD/ORG',
  `owner_ref`    BIGINT UNSIGNED DEFAULT NULL COMMENT 'AGENT 为 agent_id，SQUAD 为 squad_id，ORG 为空',
  `type`         VARCHAR(32)     DEFAULT NULL COMMENT '项目知识/工程规则/经验/偏好/避坑/组织知识...',
  `title`        VARCHAR(256)    NOT NULL,
  `content_md`   MEDIUMTEXT      DEFAULT NULL COMMENT '记忆正文（Markdown）',
  `status`       VARCHAR(16)     NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/PENDING/ADOPTED/REJECTED',
  `source`       VARCHAR(20)     NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL/AI_IMPORT/EXECUTOR_LEARNED/ARTIFACT',
  `source_ref`   JSON            DEFAULT NULL COMMENT '来源引用（ai_session_id/dispatch_id/artifact_id/链接）',
  `source_dedupe_key` VARCHAR(256) DEFAULT NULL COMMENT '自动导入来源的幂等键',
  `gmt_create`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `gmt_modified` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `creator_id`   BIGINT UNSIGNED DEFAULT NULL,
  `modifier_id`  BIGINT UNSIGNED DEFAULT NULL,
  `is_deleted`   TINYINT         NOT NULL DEFAULT 0,
  `version`      INT             NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_scope_owner` (`tenant_id`, `scope`, `owner_ref`, `status`),
  KEY `idx_type` (`tenant_id`, `type`),
  UNIQUE KEY `uk_memory_source_dedupe` (`tenant_id`, `source`, `source_dedupe_key`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='记忆';

-- 记忆审核记录
CREATE TABLE IF NOT EXISTS `memory_review` (
  `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id`         BIGINT UNSIGNED NOT NULL,
  `memory_id`         BIGINT UNSIGNED NOT NULL,
  `reviewer_id`       BIGINT UNSIGNED NOT NULL,
  `decision`          VARCHAR(16)     NOT NULL COMMENT 'ADOPT/REJECT',
  `edited_content_md` MEDIUMTEXT      DEFAULT NULL COMMENT '审核时可编辑后再采纳',
  `comment`           VARCHAR(512)    DEFAULT NULL,
  `gmt_create`        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_memory` (`tenant_id`, `memory_id`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='记忆审核记录';

-- 技能
CREATE TABLE IF NOT EXISTS `skill` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id`    BIGINT UNSIGNED NOT NULL,
  `type`         VARCHAR(16)     NOT NULL COMMENT 'MCP/SKILLS/PLUGIN',
  `name`         VARCHAR(128)    NOT NULL COMMENT '真实名称（执行器据此加载）',
  `install_spec` JSON            DEFAULT NULL COMMENT '安装与下载规格',
  `description`  VARCHAR(512)    DEFAULT NULL,
  `source_type`  VARCHAR(32)     NOT NULL DEFAULT 'INSTALL_SPEC' COMMENT 'INSTALL_SPEC/OSS_ZIP',
  `package_oss_ref`   VARCHAR(512) DEFAULT NULL COMMENT '目录上传 skill zip 的 OSS 引用',
  `package_file_name` VARCHAR(255) DEFAULT NULL COMMENT '上传 zip 文件名',
  `package_size`      BIGINT       DEFAULT NULL COMMENT '上传 zip 字节数',
  `package_md5`       VARCHAR(64)  DEFAULT NULL COMMENT '上传 zip md5',
  `gmt_create`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `gmt_modified` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `creator_id`   BIGINT UNSIGNED DEFAULT NULL,
  `modifier_id`  BIGINT UNSIGNED DEFAULT NULL,
  `is_deleted`   TINYINT         NOT NULL DEFAULT 0,
  `version`      INT             NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_type_name` (`tenant_id`, `type`, `name`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='技能';

-- 自进化候选控制点（lean v1）
CREATE TABLE IF NOT EXISTS `evolution_proposal` (
  `id`                   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id`            BIGINT UNSIGNED NOT NULL,
  `asset_type`           VARCHAR(32)     NOT NULL COMMENT 'MEMORY/REPO_RELATION/SKILL',
  `asset_id`             BIGINT UNSIGNED DEFAULT NULL COMMENT '修订已有资产时的资产 ID',
  `trigger_type`         VARCHAR(64)     NOT NULL COMMENT 'USER_CORRECTION/SOURCE_INVALIDATED/MOTIF_FAILURE/MANUAL',
  `root_evidence_json`   JSON            NOT NULL COMMENT '可追溯 evidence refs，不允许为空',
  `policy_json`          JSON            DEFAULT NULL COMMENT 'Bayesian policy action 与 campaign 上下文',
  `candidate_patch_json` JSON            NOT NULL COMMENT '资产专属候选 patch，不是 active 状态',
  `status`               VARCHAR(24)     NOT NULL DEFAULT 'PROPOSED'
      COMMENT 'PROPOSED/TRIAL/VALIDATED/REPLAY_PASSED/REPLAY_FAIL/REPLAY_INCONCLUSIVE/APPROVED/REJECTED/RELEASED/ROLLED_BACK',
  `lifecycle_json`       JSON            DEFAULT NULL COMMENT 'Trial/validation/replay/gates/release/rollback lifecycle payloads',
  `gmt_create`           DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `gmt_modified`         DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `creator_id`           BIGINT UNSIGNED DEFAULT NULL,
  `modifier_id`          BIGINT UNSIGNED DEFAULT NULL,
  `is_deleted`           TINYINT         NOT NULL DEFAULT 0,
  `version`              INT             NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_evolution_proposal_status` (`tenant_id`, `status`, `asset_type`),
  KEY `idx_evolution_proposal_asset` (`tenant_id`, `asset_type`, `asset_id`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='自进化候选控制点（lean v1）';

-- 自进化证据与 Bayesian Lite 后验快照
CREATE TABLE IF NOT EXISTS `evolution_evidence` (
  `id`                    BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id`             BIGINT UNSIGNED NOT NULL,
  `asset_type`            VARCHAR(32)     NOT NULL COMMENT 'MEMORY/REPO_RELATION/SKILL',
  `asset_id`              BIGINT UNSIGNED NOT NULL,
  `posterior_type`        VARCHAR(32)     NOT NULL COMMENT 'TRUTH/UTILITY/APPLICABILITY/UPLIFT',
  `context_key`           VARCHAR(256)    NOT NULL COMMENT '稀疏 V1 context bucket',
  `source_type`           VARCHAR(64)     NOT NULL COMMENT 'HUMAN_REVIEW/DETERMINISTIC_TEST/REPLAY_RESULT/...',
  `source_ref`            VARCHAR(512)    NOT NULL COMMENT '可追溯 source ref，如 comment:77 或 artifact:test-log',
  `outcome`               VARCHAR(16)     NOT NULL COMMENT 'POSITIVE/NEGATIVE',
  `weight`                DOUBLE          NOT NULL,
  `evidence_json`         JSON            DEFAULT NULL,
  `dependency_group`      VARCHAR(256)    DEFAULT NULL COMMENT '同源证据分组，用于去重/折扣',
  `idempotency_key`       VARCHAR(256)    DEFAULT NULL COMMENT 'Ledger 幂等键',
  `alpha`                 DOUBLE          NOT NULL,
  `beta`                  DOUBLE          NOT NULL,
  `posterior_mean`        DOUBLE          NOT NULL,
  `effective_sample_size` DOUBLE          NOT NULL,
  `gmt_create`            DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `creator_id`            BIGINT UNSIGNED DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_evolution_evidence_latest`
      (`tenant_id`, `asset_type`, `asset_id`, `posterior_type`, `context_key`, `id`),
  KEY `idx_evolution_evidence_source` (`tenant_id`, `source_type`, `source_ref`),
  UNIQUE KEY `uk_evolution_evidence_idempotency` (`tenant_id`, `idempotency_key`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='自进化证据与 Bayesian Lite 后验快照';

-- =============================================================================
-- 详设 06 — SDLC 编排（流程 + 步骤）
-- =============================================================================

-- SDLC 流程定义头
CREATE TABLE IF NOT EXISTS `sdlc` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id`     BIGINT UNSIGNED NOT NULL,
  `name`          VARCHAR(128)    NOT NULL,
  `description`   VARCHAR(512)    DEFAULT NULL,
  `work_type`     VARCHAR(16)     DEFAULT NULL COMMENT 'REQ/TASK/BUG（可空=通用）',
  `status`        VARCHAR(16)     NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/ENABLED/DISABLED',
  `is_default`    TINYINT         NOT NULL DEFAULT 0,
  `entry_step_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '入口步骤（= 最小 order 步）',
  `gmt_create`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `gmt_modified`  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `creator_id`    BIGINT UNSIGNED DEFAULT NULL,
  `modifier_id`   BIGINT UNSIGNED DEFAULT NULL,
  `is_deleted`    TINYINT         NOT NULL DEFAULT 0,
  `version`       INT             NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_type_status` (`tenant_id`, `work_type`, `status`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='SDLC 流程定义';

-- SDLC 步骤
CREATE TABLE IF NOT EXISTS `sdlc_step` (
  `id`                   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id`            BIGINT UNSIGNED NOT NULL,
  `sdlc_id`              BIGINT UNSIGNED NOT NULL,
  `step_order`           INT             NOT NULL COMMENT '步序（同 sdlc 内唯一、单调）',
  `name`                 VARCHAR(128)    NOT NULL,
  `kind`                 VARCHAR(32)     DEFAULT NULL COMMENT '内部步骤类型：analysis/implementation/test/handoff 等',
  `instruction_md`       MEDIUMTEXT      DEFAULT NULL COMMENT '给数字员工执行本步骤的详细说明',
  `checklist_json`       JSON            DEFAULT NULL COMMENT '执行检查项数组',
  `gate_policy_json`     JSON            DEFAULT NULL COMMENT '步骤准入/准出策略',
  `required`             TINYINT         NOT NULL DEFAULT 1 COMMENT '是否必需步骤',
  `timeout_seconds`      INT             DEFAULT NULL COMMENT '步骤建议超时时间',
  `retry_budget`         INT             DEFAULT NULL COMMENT '步骤建议重试预算',
  `code`                 VARCHAR(64)     DEFAULT NULL COMMENT '废弃：旧步骤编码',
  `handler_type`         VARCHAR(16)     DEFAULT NULL COMMENT '废弃：旧 AGENT/HUMAN 路由字段',
  `handler_role_ref`     VARCHAR(64)     DEFAULT NULL COMMENT '废弃：旧目标角色码',
  `status_on_enter_code` VARCHAR(64)     DEFAULT NULL COMMENT '废弃：旧进入状态',
  `on_success`           JSON            DEFAULT NULL COMMENT '废弃：旧成功流转',
  `on_fail`              JSON            DEFAULT NULL COMMENT '废弃：旧失败流转',
  `gmt_create`           DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `gmt_modified`         DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `creator_id`           BIGINT UNSIGNED DEFAULT NULL,
  `modifier_id`          BIGINT UNSIGNED DEFAULT NULL,
  `is_deleted`           TINYINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sdlc_order` (`tenant_id`, `sdlc_id`, `step_order`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='SDLC 步骤';

-- =============================================================================
-- 详设 08 — 横切（通知 + AI 用量配额 + 系统设置）
-- =============================================================================

-- 通知
CREATE TABLE IF NOT EXISTS `notification` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id`     BIGINT UNSIGNED NOT NULL,
  `recipient_id`  BIGINT UNSIGNED NOT NULL COMMENT '接收用户',
  `type`          VARCHAR(32)     NOT NULL COMMENT 'MEMORY_REVIEW/AGENT_REVIEW/HUMAN_HANDOFF/DISPATCH_ALERT/MENTION/AI_DONE',
  `title`         VARCHAR(256)    NOT NULL,
  `content`       VARCHAR(1024)   DEFAULT NULL COMMENT '摘要文本',
  `link`          VARCHAR(512)    DEFAULT NULL COMMENT '前端跳转路由',
  `ref_type`      VARCHAR(64)     DEFAULT NULL,
  `ref_id`        BIGINT UNSIGNED DEFAULT NULL,
  `status`        VARCHAR(16)     NOT NULL DEFAULT 'UNREAD' COMMENT 'UNREAD/READ',
  `channels_json` JSON            DEFAULT NULL COMMENT '已投递渠道与结果',
  `gmt_create`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `gmt_modified`  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_recipient` (`tenant_id`, `recipient_id`, `status`, `gmt_create`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='通知';

-- 通知偏好
CREATE TABLE IF NOT EXISTS `notify_pref` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id`  BIGINT UNSIGNED NOT NULL,
  `user_id`    BIGINT UNSIGNED NOT NULL,
  `type`       VARCHAR(32)     NOT NULL COMMENT '通知类型',
  `in_app`     TINYINT         NOT NULL DEFAULT 1 COMMENT '站内（0/1）',
  `dingtalk`   TINYINT         NOT NULL DEFAULT 1 COMMENT '钉钉（0/1）',
  `gmt_create` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `gmt_modified` DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_type` (`tenant_id`, `user_id`, `type`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='通知偏好';

-- AI 用量（按租户+周期+场景聚合）
CREATE TABLE IF NOT EXISTS `ai_usage` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id`     BIGINT UNSIGNED NOT NULL,
  `period`        VARCHAR(7)      NOT NULL COMMENT '计量周期，如 2026-07',
  `scene`         VARCHAR(20)     NOT NULL COMMENT 'AI 场景或 ALL 汇总',
  `call_count`    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '调用次数',
  `input_tokens`  BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `output_tokens` BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `gmt_modified`  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_usage` (`tenant_id`, `period`, `scene`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='AI 用量计量';

-- 派发级 AI Token 用量明细（runtime usage.json / daemon usage 上报）
CREATE TABLE IF NOT EXISTS `dispatch_ai_usage` (
  `id`                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id`          BIGINT UNSIGNED NOT NULL,
  `workitem_id`        BIGINT UNSIGNED NOT NULL,
  `dispatch_id`        BIGINT UNSIGNED NOT NULL,
  `agent_id`           BIGINT UNSIGNED DEFAULT NULL,
  `executor_id`        BIGINT UNSIGNED DEFAULT NULL,
  `artifact_id`        BIGINT UNSIGNED DEFAULT NULL,
  `provider`           VARCHAR(64)     NOT NULL,
  `model`              VARCHAR(128)    NOT NULL,
  `input_tokens`       BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `output_tokens`      BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `cache_read_tokens`  BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `cache_write_tokens` BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `total_tokens`       BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `raw_json`           JSON            DEFAULT NULL,
  `usage_at`           DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `gmt_create`         DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `gmt_modified`       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dispatch_provider_model` (`tenant_id`, `dispatch_id`, `provider`, `model`),
  UNIQUE KEY `uk_artifact_provider_model` (`tenant_id`, `artifact_id`, `provider`, `model`),
  KEY `idx_usage_at` (`tenant_id`, `usage_at`),
  KEY `idx_agent_usage_at` (`tenant_id`, `agent_id`, `usage_at`),
  KEY `idx_workitem` (`tenant_id`, `workitem_id`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='派发级 AI Token 用量明细';

-- AI 配额
CREATE TABLE IF NOT EXISTS `ai_quota` (
  `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id`         BIGINT UNSIGNED NOT NULL,
  `period_type`       VARCHAR(16)     NOT NULL DEFAULT 'MONTH' COMMENT '计量周期类型',
  `max_calls`         BIGINT UNSIGNED DEFAULT NULL COMMENT '周期最大调用次数（空=系统默认）',
  `max_tokens`        BIGINT UNSIGNED DEFAULT NULL COMMENT '周期最大 token',
  `concurrency_limit` INT             DEFAULT NULL COMMENT '并发上限（驱动 ai:concur 信号量）',
  `gmt_create`        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `gmt_modified`      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant` (`tenant_id`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='AI 配额';

-- 系统设置（租户级键值，按组）
CREATE TABLE IF NOT EXISTS `system_setting` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id`      BIGINT UNSIGNED NOT NULL,
  `setting_group`  VARCHAR(32)     NOT NULL COMMENT 'AI/STORAGE/NOTIFY/DEFAULTS',
  `setting_key`    VARCHAR(128)    NOT NULL,
  `value_json`     JSON            DEFAULT NULL COMMENT 'is_secret=1 时不落明文',
  `is_secret`      TINYINT         NOT NULL DEFAULT 0,
  `credential_ref` TEXT            DEFAULT NULL COMMENT 'is_secret 时的 SecretCrypto 密文',
  `gmt_create`     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `gmt_modified`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `creator_id`     BIGINT UNSIGNED DEFAULT NULL,
  `modifier_id`    BIGINT UNSIGNED DEFAULT NULL,
  `is_deleted`     TINYINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_setting` (`tenant_id`, `setting_group`, `setting_key`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='系统设置';

-- 外部项目绑定
CREATE TABLE IF NOT EXISTS `external_project_binding` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id` BIGINT UNSIGNED NOT NULL,
  `provider` VARCHAR(32) NOT NULL,
  `external_project_id` VARCHAR(64) NOT NULL,
  `external_project_name` VARCHAR(256) DEFAULT NULL,
  `base_url` VARCHAR(512) NOT NULL,
  `client_key` VARCHAR(128) NOT NULL,
  `credential_ref` TEXT NOT NULL COMMENT 'SecretCrypto 加密密文',
  `region_id` VARCHAR(16) NOT NULL DEFAULT '1',
  `writeback_staff_id` VARCHAR(64) DEFAULT NULL,
  `poll_interval_seconds` INT NOT NULL DEFAULT 3,
  `enabled` TINYINT NOT NULL DEFAULT 1,
  `last_success_at` DATETIME(3) DEFAULT NULL,
  `last_error` TEXT DEFAULT NULL,
  `gmt_create` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `gmt_modified` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `creator_id` BIGINT UNSIGNED DEFAULT NULL,
  `modifier_id` BIGINT UNSIGNED DEFAULT NULL,
  `is_deleted` TINYINT NOT NULL DEFAULT 0,
  `version` INT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_provider_project` (`tenant_id`, `provider`, `external_project_id`, `is_deleted`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='外部项目绑定';

-- 外部工单映射
CREATE TABLE IF NOT EXISTS `external_workitem_link` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id` BIGINT UNSIGNED NOT NULL,
  `provider` VARCHAR(32) NOT NULL,
  `binding_id` BIGINT UNSIGNED NOT NULL,
  `external_project_id` VARCHAR(64) NOT NULL,
  `external_workitem_id` VARCHAR(64) NOT NULL,
  `external_work_type` VARCHAR(32) DEFAULT NULL,
  `workitem_id` BIGINT UNSIGNED NOT NULL,
  `remote_updated_at` DATETIME(3) DEFAULT NULL,
  `remote_version_hash` VARCHAR(64) DEFAULT NULL,
  `last_sync_direction` VARCHAR(16) DEFAULT NULL,
  `gmt_create` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `gmt_modified` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_external_workitem` (`tenant_id`, `provider`, `external_workitem_id`),
  UNIQUE KEY `uk_local_workitem` (`tenant_id`, `provider`, `workitem_id`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='外部工单映射';

-- 外部工单导入记录
CREATE TABLE IF NOT EXISTS `external_workitem_import_record` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id` BIGINT UNSIGNED NOT NULL,
  `source_system` VARCHAR(32) NOT NULL,
  `external_workitem_id` VARCHAR(128) NOT NULL,
  `workitem_id` BIGINT UNSIGNED DEFAULT NULL,
  `request_id` VARCHAR(128) DEFAULT NULL,
  `status` VARCHAR(32) NOT NULL,
  `failure_reason` TEXT DEFAULT NULL,
  `source_url` VARCHAR(512) DEFAULT NULL,
  `raw_payload_json` JSON DEFAULT NULL,
  `extensions_json` JSON DEFAULT NULL,
  `field_mappings_json` JSON DEFAULT NULL,
  `gmt_create` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `gmt_modified` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_external_import` (`tenant_id`, `source_system`, `external_workitem_id`, `gmt_create`),
  KEY `idx_import_request` (`tenant_id`, `request_id`),
  KEY `idx_import_status` (`tenant_id`, `status`, `gmt_create`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='外部工单导入记录';

-- 外部评论映射
CREATE TABLE IF NOT EXISTS `external_comment_link` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id` BIGINT UNSIGNED NOT NULL,
  `provider` VARCHAR(32) NOT NULL,
  `binding_id` BIGINT UNSIGNED NOT NULL,
  `external_workitem_id` VARCHAR(64) NOT NULL,
  `external_comment_id` VARCHAR(64) NOT NULL,
  `workitem_comment_id` BIGINT UNSIGNED NOT NULL,
  `direction` VARCHAR(16) NOT NULL,
  `gmt_create` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_external_comment` (`tenant_id`, `provider`, `external_comment_id`),
  KEY `idx_local_comment` (`tenant_id`, `workitem_comment_id`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='外部评论映射';

-- 外部状态映射
CREATE TABLE IF NOT EXISTS `external_status_mapping` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id` BIGINT UNSIGNED NOT NULL,
  `provider` VARCHAR(32) NOT NULL,
  `binding_id` BIGINT UNSIGNED NOT NULL,
  `external_issue_type_id` VARCHAR(64) DEFAULT NULL,
  `external_status_id` VARCHAR(64) DEFAULT NULL,
  `external_status_name` VARCHAR(128) NOT NULL,
  `work_type` VARCHAR(16) NOT NULL,
  `status_node_id` BIGINT UNSIGNED NOT NULL,
  `enabled` TINYINT NOT NULL DEFAULT 1,
  `gmt_create` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `gmt_modified` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_external_status` (`tenant_id`, `provider`, `binding_id`, `external_status_name`, `work_type`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='外部状态映射';

-- 外部系统写回队列
CREATE TABLE IF NOT EXISTS `integration_outbox` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id` BIGINT UNSIGNED NOT NULL,
  `provider` VARCHAR(32) NOT NULL,
  `binding_id` BIGINT UNSIGNED NOT NULL,
  `workitem_id` BIGINT UNSIGNED NOT NULL,
  `event_type` VARCHAR(32) NOT NULL,
  `payload_json` JSON NOT NULL,
  `status` VARCHAR(32) NOT NULL,
  `retry_count` INT NOT NULL DEFAULT 0,
  `next_retry_at` DATETIME(3) DEFAULT NULL,
  `last_error` TEXT DEFAULT NULL,
  `gmt_create` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `gmt_modified` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_pending` (`provider`, `status`, `next_retry_at`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='外部系统写回队列';

-- Aone OpenAPI 全局限流桶
CREATE TABLE IF NOT EXISTS `aone_rate_bucket` (
  `client_key` VARCHAR(128) NOT NULL COMMENT '限流客户端标识',
  `capacity` DECIMAL(10,3) NOT NULL COMMENT '桶容量',
  `tokens` DECIMAL(10,3) NOT NULL COMMENT '当前令牌数',
  `refill_per_sec` DECIMAL(10,6) NOT NULL COMMENT '每秒补充令牌数',
  `last_refill_ms` BIGINT NOT NULL COMMENT '最近补充时间，Unix 毫秒',
  `gmt_create` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `gmt_modified` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`client_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Aone OpenAPI 全局限流桶';

INSERT IGNORE INTO `aone_rate_bucket`
  (`client_key`, `capacity`, `tokens`, `refill_per_sec`, `last_refill_ms`)
VALUES
  ('auto-wonder', 100.000, 100.000, 1.666667, ROUND(UNIX_TIMESTAMP(NOW(3)) * 1000));

-- -----------------------------------------------------------------------------
-- 小队模版间
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS `squad_template` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id`    BIGINT UNSIGNED DEFAULT NULL COMMENT 'null=系统内置，非null=租户自建',
  `name`         VARCHAR(128)    NOT NULL COMMENT '模版名称',
  `description`  VARCHAR(512)    DEFAULT NULL COMMENT '模版描述',
  `squad_size`   INT             NOT NULL DEFAULT 1 COMMENT '小队人数',
  `icon`         VARCHAR(64)     DEFAULT NULL COMMENT '图标标识(solo/pair/team)',
  `tags`         VARCHAR(256)    DEFAULT NULL COMMENT '标签，逗号分隔',
  `content_json` MEDIUMTEXT      NOT NULL COMMENT '完整小队配置JSON(squad+agents+sdlc)',
  `status`       VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DISABLED',
  `gmt_create`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `gmt_modified` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `is_deleted`   TINYINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_tenant_status` (`tenant_id`, `status`, `is_deleted`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='小队模版间';

-- MCP 长效访问 Token（用户个人资产，不归属组织；权限按调用时传入的 orgId 实时解析）
CREATE TABLE IF NOT EXISTS `mcp_access_token` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT 'token 所属用户',
  `name` VARCHAR(128) NOT NULL,
  `token_hash` CHAR(64) NOT NULL,
  `token_prefix` VARCHAR(32) NOT NULL,
  `last_used_at` DATETIME(3) DEFAULT NULL,
  `revoked_at` DATETIME(3) DEFAULT NULL,
  `gmt_create` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `gmt_modified` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `creator_id` BIGINT UNSIGNED DEFAULT NULL,
  `modifier_id` BIGINT UNSIGNED DEFAULT NULL,
  `is_deleted` TINYINT NOT NULL DEFAULT 0,
  `version` INT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_mcp_token_hash` (`token_hash`),
  KEY `idx_mcp_token_user` (`user_id`, `is_deleted`),
  KEY `idx_mcp_token_prefix` (`token_prefix`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='MCP长效访问Token（个人资产）';

-- 工单评论定向 Worker 投递状态
CREATE TABLE IF NOT EXISTS `workitem_comment_delivery` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '投递记录ID',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '租户ID',
  `workitem_id` BIGINT UNSIGNED NOT NULL COMMENT '工单ID',
  `comment_id` BIGINT UNSIGNED NOT NULL COMMENT '来源评论ID，正文以workitem_comment为准',
  `target_agent_id` BIGINT UNSIGNED NOT NULL COMMENT '被@的目标数字员工ID',
  `dispatch_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '实际承载本次交互的派发ID',
  `executor_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '实际接收本次交互的执行器ID',
  `reply_comment_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '本次旁路交互生成的Agent回复评论ID',
  `status` VARCHAR(16) NOT NULL DEFAULT 'QUEUED' COMMENT '投递状态：QUEUED/DELIVERED/APPLIED/FAILED',
  `error` VARCHAR(1024) DEFAULT NULL COMMENT '投递或执行失败原因',
  `delivered_at` DATETIME(3) DEFAULT NULL COMMENT '发送给Runtime的时间',
  `applied_at` DATETIME(3) DEFAULT NULL COMMENT 'Runtime确认已处理的时间',
  `gmt_create` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `gmt_modified` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最后修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_comment_delivery_agent` (`tenant_id`, `comment_id`, `target_agent_id`),
  KEY `idx_comment_delivery_queue` (`tenant_id`, `workitem_id`, `target_agent_id`, `status`),
  KEY `idx_comment_delivery_dispatch` (`tenant_id`, `dispatch_id`, `status`),
  KEY `idx_comment_delivery_reply` (`tenant_id`, `reply_comment_id`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='工单评论定向 Worker 投递状态';

-- 钉钉数字人对话能力（V018__dingtalk_agent_conversation）：机器人绑定 + 工单无关会话 + turn。
CREATE TABLE IF NOT EXISTS `dingtalk_robot_binding` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id` BIGINT NOT NULL,
  `app_key` VARCHAR(128) NOT NULL,
  `credential_ref` TEXT NOT NULL COMMENT 'SecretCrypto 加密后的 appSecret',
  `robot_code` VARCHAR(128) NOT NULL,
  `agent_id` BIGINT NOT NULL COMMENT '关联数字人',
  `transport_mode` VARCHAR(32) NOT NULL DEFAULT 'HTTP_CALLBACK',
  `callback_token` VARCHAR(128) NULL,
  `base_url` VARCHAR(256) NULL,
  `region_id` VARCHAR(64) NULL,
  `stream_env` VARCHAR(32) NULL COMMENT 'DingTalk Stream environment: ONLINE/PRE/OVERSEA/OVERSEA_PRE',
  `last_success_at` DATETIME NULL,
  `last_error` VARCHAR(1024) NULL,
  `status` VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
  `gmt_create` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `gmt_modified` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `creator_id` BIGINT NULL,
  `modifier_id` BIGINT NULL,
  `is_deleted` TINYINT NOT NULL DEFAULT 0,
  `version` INT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_robot` (`robot_code`),
  KEY `idx_tenant_agent` (`tenant_id`, `agent_id`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='钉钉机器人绑定(一机器人一数字人)';

CREATE TABLE IF NOT EXISTS `agent_conversation` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id` BIGINT NOT NULL,
  `agent_id` BIGINT NOT NULL,
  `agent_version_id` BIGINT NULL COMMENT '最近一轮使用的在线 AgentVersion',
  `channel` VARCHAR(32) NOT NULL COMMENT 'DINGTALK / WORKITEM_CLARIFICATION etc.',
  `biz_ref_type` VARCHAR(32) NULL COMMENT 'WORKITEM etc.',
  `biz_ref_id` BIGINT NULL COMMENT 'workitem id etc.',
  `channel_conversation_id` VARCHAR(256) NOT NULL COMMENT '钉钉 openConversationId or opaque UUID',
  `cli_session_ref` VARCHAR(256) NULL COMMENT 'CLI 会话 id,用于 --resume',
  `executor_id` BIGINT NULL COMMENT '粘性 executor',
  `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  `last_turn_at` DATETIME NULL,
  `gmt_create` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `gmt_modified` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `version` INT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_conv` (`tenant_id`, `channel`, `channel_conversation_id`, `agent_id`),
  KEY `idx_conversation_agent_version` (`tenant_id`, `agent_version_id`),
  KEY `idx_biz_ref` (`tenant_id`, `channel`, `biz_ref_type`, `biz_ref_id`, `agent_id`, `gmt_create`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='数字人会话线程';

CREATE TABLE IF NOT EXISTS `agent_conversation_turn` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id` BIGINT NOT NULL,
  `conversation_id` BIGINT NOT NULL,
  `direction` VARCHAR(8) NOT NULL COMMENT 'IN / OUT',
  `content` MEDIUMTEXT NULL,
  `external_msg_id` VARCHAR(256) NULL COMMENT '入站幂等唯一键',
  `request_id` VARCHAR(128) NULL COMMENT '入站 HTTP requestId,用于异步回包日志串联',
  `source_context` MEDIUMTEXT NULL COMMENT 'JSON source context for inbound channel reply delivery',
  `status` VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  `error` VARCHAR(1024) NULL,
  `last_dispatch_at` DATETIME DEFAULT NULL COMMENT '最近一次向 runtime 投递该 turn 的时间',
  `dispatch_attempt` INT NOT NULL DEFAULT 0 COMMENT '向 runtime 投递该 turn 的次数',
  `gmt_create` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_external_msg` (`tenant_id`, `external_msg_id`),
  KEY `idx_conversation` (`conversation_id`),
  KEY `idx_conv_status_direction` (`tenant_id`, `conversation_id`, `direction`, `status`, `id`),
  KEY `idx_turn_processing_dispatch` (`status`, `direction`, `last_dispatch_at`, `gmt_create`, `id`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='会话 turn(入站/出站)';

CREATE TABLE IF NOT EXISTS `agent_conversation_turn_event` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id` BIGINT NOT NULL,
  `conversation_id` BIGINT NOT NULL,
  `turn_id` BIGINT NOT NULL,
  `dispatch_attempt` INT NOT NULL,
  `event_seq` BIGINT NOT NULL,
  `chunk_index` INT NOT NULL DEFAULT 0,
  `chunk_count` INT NOT NULL DEFAULT 1,
  `event_type` VARCHAR(32) NOT NULL,
  `payload_fragment` MEDIUMTEXT NOT NULL,
  `gmt_create` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_turn_event_chunk` (`tenant_id`, `turn_id`, `dispatch_attempt`, `event_seq`, `chunk_index`),
  KEY `idx_replay` (`tenant_id`, `conversation_id`, `id`),
  KEY `idx_cleanup` (`gmt_create`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='Provider event chunks for conversation turns';

SET FOREIGN_KEY_CHECKS = 1;

-- =============================================================================
-- 表清单（58 张）
-- 详设01: user, platform_branding_config, org, org_member, org_invite, audit_log
-- 详设02: status_template, status_node, status_transition, workitem,
--         workitem_comment, workitem_event, clarification, executor, dispatch, artifact,
--         workitem_comment_delivery, workitem_comment_mention
-- 详设03: agent, agent_version, agent_repo_perm, agent_skill, agent_memory_ref,
--         squad, squad_member
-- 详设04: ai_session, ai_message
-- 详设05: repo, repo_conclusion, repo_relation, memory, memory_review, skill
-- 详设06: sdlc, sdlc_step
-- 详设08: notification, notify_pref, ai_usage, ai_quota, system_setting
-- 模版间: squad_template
-- 集成: external_project_binding, external_workitem_link, external_comment_link,
--       external_status_mapping, integration_outbox
-- MCP: mcp_access_token（个人资产表，无 tenant_id，不在租户表白名单内）
-- 平台 IM: platform_im_channel_config, user_im_identity
-- 钉钉: dingtalk_robot_binding, agent_conversation, agent_conversation_turn
-- =============================================================================
