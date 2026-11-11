-- =============================================================================
-- V104 — PRD 设计版本与确认
-- =============================================================================
-- 背景：工单需要一个可评审、可迭代、可确认的 HTML PRD，确认后的版本作为交付
--      的冻结输入。设计数据是唯一真相来源：HTML 由服务端从结构化设计数据确定性
--      渲染，二者不可能产生内容偏差。
--
-- 变更类型：新增表（无数据迁移，无历史数据升级）
-- 影响对象：prd_design、prd_design_version（均为新表）
-- 兼容性：纯新增。不修改既有表，不改变既有工单与交付路径；未启用 PRD 驱动模式
--        的工单继续按原有方式交付。未部署本迁移时 PrdDesignSchemaBootstrap 自愈建表。
--
-- 幂等：使用 CREATE TABLE IF NOT EXISTS，可重复执行。
-- =============================================================================

-- 一个工单的设计聚合根：目标项目上下文与当前确认版本
CREATE TABLE IF NOT EXISTS `prd_design` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `workspace_id` BIGINT UNSIGNED NOT NULL COMMENT '所属工作空间（行隔离）',
  `workitem_id` BIGINT UNSIGNED NOT NULL COMMENT '承载需求的工单',
  `target_repo_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '目标前端应用所属仓库；概念设计阶段可为空',
  `target_profile_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '目标前端应用画像快照',
  `target_app_root` VARCHAR(512) DEFAULT NULL COMMENT '目标应用根目录',
  `target_commit_sha` VARCHAR(64) DEFAULT NULL COMMENT '目标应用探测提交',
  `confirmed_version_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '已确认版本；NULL 表示尚未确认',
  `delivery_mode` VARCHAR(24) NOT NULL DEFAULT 'LEGACY' COMMENT 'LEGACY=原有交付路径，PRD_DRIVEN=强制校验确认版本',
  `latest_version_no` INT NOT NULL DEFAULT 0 COMMENT '已分配的最大版本号，用于乐观并发分配',
  `content_language` VARCHAR(16) DEFAULT NULL COMMENT '生成内容语言；NULL 表示未显式选择',
  `version` INT NOT NULL DEFAULT 0 COMMENT '乐观并发版本',
  `creator_id` BIGINT UNSIGNED DEFAULT NULL,
  `modifier_id` BIGINT UNSIGNED DEFAULT NULL,
  `gmt_create` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `gmt_modified` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_prd_design_workitem` (`workspace_id`, `workitem_id`),
  KEY `idx_prd_design_repo` (`workspace_id`, `target_repo_id`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='工单 PRD 设计聚合根';

-- 设计版本：结构化数据为真相来源，HTML 为其确定性渲染结果
CREATE TABLE IF NOT EXISTS `prd_design_version` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `workspace_id` BIGINT UNSIGNED NOT NULL COMMENT '所属工作空间（行隔离）',
  `design_id` BIGINT UNSIGNED NOT NULL COMMENT '所属设计',
  `version_no` INT NOT NULL COMMENT '版本序号，从 1 起递增',
  `status` VARCHAR(24) NOT NULL COMMENT 'DRAFT/PENDING_REVIEW/CONFIRMED',
  `source_version_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '派生自哪个版本；首个版本为空',
  `design_json` LONGTEXT NOT NULL COMMENT '版本化结构化设计数据（唯一真相来源）',
  `design_sha256` CHAR(64) NOT NULL COMMENT 'design_json 的 sha256，确认与交付校验用',
  `html_oss_ref` VARCHAR(512) DEFAULT NULL COMMENT '渲染出的 HTML PRD 对象存储引用',
  `html_sha256` CHAR(64) DEFAULT NULL COMMENT 'HTML 内容的 sha256',
  `html_size` BIGINT DEFAULT NULL COMMENT 'HTML 字节数',
  `renderer_version` VARCHAR(64) DEFAULT NULL COMMENT 'HTML 渲染器版本',
  `summary` VARCHAR(1024) DEFAULT NULL COMMENT '内容摘要',
  `change_summary` VARCHAR(2048) DEFAULT NULL COMMENT '相对来源版本的变化摘要',
  `requirement_count` INT DEFAULT NULL COMMENT '需求项数量',
  `open_question_count` INT DEFAULT NULL COMMENT '未解决的待确认问题数量',
  `blocking_question_count` INT DEFAULT NULL COMMENT '未解决的阻塞问题数量',
  `ai_session_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '产生该版本的生成任务',
  `confirmed_by` BIGINT UNSIGNED DEFAULT NULL COMMENT '确认人',
  `confirmed_at` DATETIME(3) DEFAULT NULL COMMENT '确认时间',
  `confirmed_sha256` CHAR(64) DEFAULT NULL COMMENT '确认时绑定的内容摘要',
  `is_deleted` TINYINT NOT NULL DEFAULT 0,
  `deleted_at` DATETIME(3) DEFAULT NULL,
  `version` INT NOT NULL DEFAULT 0 COMMENT '乐观并发版本',
  `creator_id` BIGINT UNSIGNED DEFAULT NULL,
  `modifier_id` BIGINT UNSIGNED DEFAULT NULL,
  `gmt_create` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `gmt_modified` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_prd_version` (`workspace_id`, `design_id`, `version_no`),
  KEY `idx_prd_version_design` (`workspace_id`, `design_id`, `status`),
  KEY `idx_prd_version_session` (`workspace_id`, `ai_session_id`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='PRD 设计版本';

-- 需求验收结果：按冻结 PRD 的稳定需求编号逐项回写实现结果与证据
CREATE TABLE IF NOT EXISTS `prd_requirement_acceptance` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `workspace_id` BIGINT UNSIGNED NOT NULL COMMENT '所属工作空间（行隔离）',
  `workitem_id` BIGINT UNSIGNED NOT NULL COMMENT '所属工单',
  `dispatch_id` BIGINT UNSIGNED NOT NULL COMMENT '产生该结果的交付',
  `design_version_id` BIGINT UNSIGNED NOT NULL COMMENT '冻结的设计版本',
  `requirement_key` VARCHAR(64) NOT NULL COMMENT '需求稳定编号，跨版本不变',
  `implementation` VARCHAR(24) NOT NULL COMMENT 'IMPLEMENTED/PARTIAL/NOT_IMPLEMENTED',
  `verdict` VARCHAR(24) NOT NULL COMMENT 'PASSED/FAILED/NOT_VERIFIED/NOT_APPLICABLE',
  `note` VARCHAR(2048) DEFAULT NULL COMMENT '偏差、阻塞、遗留问题及原因',
  `not_applicable_reason` VARCHAR(1024) DEFAULT NULL COMMENT '不适用时必须提供的理由',
  `evidence_json` JSON DEFAULT NULL COMMENT '证据引用：代码/MR/截图/测试/预览链接',
  `reported_at` DATETIME(3) DEFAULT NULL,
  `version` INT NOT NULL DEFAULT 0,
  `creator_id` BIGINT UNSIGNED DEFAULT NULL,
  `modifier_id` BIGINT UNSIGNED DEFAULT NULL,
  `gmt_create` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `gmt_modified` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_prd_acceptance` (`workspace_id`, `dispatch_id`, `requirement_key`),
  KEY `idx_prd_acceptance_workitem` (`workspace_id`, `workitem_id`, `id`),
  KEY `idx_prd_acceptance_version` (`workspace_id`, `design_version_id`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='PRD 需求验收结果';

-- 交付绑定：首次打包时钉住 dispatch 使用的确认版本，重试与恢复必须沿用
CREATE TABLE IF NOT EXISTS `prd_delivery_binding` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `workspace_id` BIGINT UNSIGNED NOT NULL COMMENT '所属工作空间（行隔离）',
  `workitem_id` BIGINT UNSIGNED NOT NULL COMMENT '关联工单',
  `dispatch_id` BIGINT UNSIGNED NOT NULL COMMENT '关联交付',
  `design_id` BIGINT UNSIGNED NOT NULL COMMENT '设计聚合根',
  `design_version_id` BIGINT UNSIGNED NOT NULL COMMENT '钉住的设计版本',
  `version_no` INT NOT NULL COMMENT '钉住的版本序号',
  `design_sha256` CHAR(64) NOT NULL COMMENT '钉住的内容摘要',
  `html_sha256` CHAR(64) DEFAULT NULL COMMENT '钉住的 HTML 摘要',
  `target_repo_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '目标仓库',
  `target_profile_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '目标应用画像',
  `target_app_root` VARCHAR(512) DEFAULT NULL COMMENT '目标应用根目录',
  `target_commit_sha` VARCHAR(64) DEFAULT NULL COMMENT '探测提交',
  `version` INT NOT NULL DEFAULT 0,
  `creator_id` BIGINT UNSIGNED DEFAULT NULL,
  `modifier_id` BIGINT UNSIGNED DEFAULT NULL,
  `gmt_create` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `gmt_modified` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_prd_binding_dispatch` (`workspace_id`, `dispatch_id`),
  KEY `idx_prd_binding_workitem` (`workspace_id`, `workitem_id`, `id`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='PRD 交付冻结绑定';
