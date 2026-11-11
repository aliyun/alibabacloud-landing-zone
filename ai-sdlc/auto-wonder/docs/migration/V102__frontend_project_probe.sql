-- =============================================================================
-- V102 — 前端项目探测：探测任务与画像快照
-- =============================================================================
-- 背景：PRD 设计能力需要先从当前空间配置的仓库中识别出目标前端应用
--      （仓库 + 提交 + 应用根目录 三元组），并把探测结论连同证据持久化。
--
-- 变更类型：新增表（无数据迁移，无历史数据升级）
-- 影响对象：frontend_project_probe、frontend_project_profile（均为新表）
-- 兼容性：纯新增。既有表与既有交付路径不受影响；未部署本迁移时，
--        FrontendProjectSchemaBootstrap 会在启动时自愈建表（见类注释）。
--
-- 幂等：使用 CREATE TABLE IF NOT EXISTS，可重复执行。
-- 排序：Community 为每个迁移分配唯一递增版本；按 docs/migration/README.md 执行。
-- =============================================================================

-- 探测任务：持久化异步任务，刷新/重连后可恢复状态
CREATE TABLE IF NOT EXISTS `frontend_project_probe` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `workspace_id` BIGINT UNSIGNED NOT NULL COMMENT '所属工作空间（行隔离）',
  `repo_id` BIGINT UNSIGNED NOT NULL COMMENT '被探测的仓库',
  `status` VARCHAR(24) NOT NULL COMMENT 'QUEUED/RUNNING/SUCCEEDED/FAILED/CANCELED',
  `detector_version` VARCHAR(64) NOT NULL COMMENT '探测器版本，参与画像缓存键',
  `branch` VARCHAR(255) DEFAULT NULL COMMENT '实际探测的分支',
  `commit_sha` VARCHAR(64) DEFAULT NULL COMMENT '实际探测到的提交 SHA；失败时为空',
  `candidate_count` INT DEFAULT NULL COMMENT '识别出的候选前端应用数量',
  `error` VARCHAR(1000) DEFAULT NULL COMMENT '失败原因（已截断）',
  `node_id` VARCHAR(64) DEFAULT NULL COMMENT '执行节点，用于卡死任务补偿',
  `started_at` DATETIME(3) DEFAULT NULL,
  `finished_at` DATETIME(3) DEFAULT NULL,
  `version` INT NOT NULL DEFAULT 0 COMMENT '乐观并发版本',
  `creator_id` BIGINT UNSIGNED DEFAULT NULL,
  `modifier_id` BIGINT UNSIGNED DEFAULT NULL,
  `gmt_create` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `gmt_modified` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_probe_repo` (`workspace_id`, `repo_id`, `id`),
  KEY `idx_probe_status` (`workspace_id`, `status`, `gmt_modified`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='前端项目探测任务';

-- 画像快照：按 仓库+提交+应用根目录+探测器版本 缓存，历史快照不可变
CREATE TABLE IF NOT EXISTS `frontend_project_profile` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `workspace_id` BIGINT UNSIGNED NOT NULL COMMENT '所属工作空间（行隔离）',
  `repo_id` BIGINT UNSIGNED NOT NULL COMMENT '所属仓库',
  `probe_id` BIGINT UNSIGNED NOT NULL COMMENT '产生该画像的探测任务',
  `branch` VARCHAR(255) DEFAULT NULL,
  `commit_sha` VARCHAR(64) NOT NULL COMMENT '画像对应的提交',
  `app_root` VARCHAR(512) NOT NULL COMMENT '应用根目录（相对仓库根），仓库根为 .',
  `app_root_hash` CHAR(64) NOT NULL COMMENT 'app_root 的 sha256，用于定长唯一键',
  `package_name` VARCHAR(255) DEFAULT NULL COMMENT 'package.json 的 name',
  `detector_version` VARCHAR(64) NOT NULL COMMENT '探测器版本',
  `profile_json` JSON NOT NULL COMMENT '技术栈与工程约束、事实/推断/未知项',
  `evidence_json` JSON NOT NULL COMMENT '证据：结论 + 来源文件路径',
  `looks_like_library` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '推断为组件库而非独立应用',
  `version` INT NOT NULL DEFAULT 0,
  `creator_id` BIGINT UNSIGNED DEFAULT NULL,
  `modifier_id` BIGINT UNSIGNED DEFAULT NULL,
  `gmt_create` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `gmt_modified` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_profile` (`workspace_id`, `repo_id`, `commit_sha`, `app_root_hash`, `detector_version`),
  KEY `idx_profile_repo` (`workspace_id`, `repo_id`, `id`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='前端项目画像快照';
