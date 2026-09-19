-- V068: 项目级能力分类（工单 55376）
-- 分类定义与资产关联分离：asset_category 只存树节点，asset_category_ref 存资产打标，
-- 为未来记忆等资产类型复用同一棵分类树预留 asset_type 维度，本期仅接入 SKILL 能力资产。
CREATE TABLE IF NOT EXISTS `asset_category` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id`    BIGINT UNSIGNED NOT NULL COMMENT '项目/工作空间归属，复用平台既有隔离边界',
  `parent_id`    BIGINT UNSIGNED DEFAULT NULL COMMENT '父分类 id；NULL 表示顶级分类',
  `name`         VARCHAR(128)    NOT NULL COMMENT '分类名称，同级（同一父节点下）不允许重复',
  `description`  VARCHAR(2048)   DEFAULT NULL COMMENT '分类说明（选填）',
  `gmt_create`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `gmt_modified` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `creator_id`   BIGINT UNSIGNED DEFAULT NULL,
  `modifier_id`  BIGINT UNSIGNED DEFAULT NULL,
  `is_deleted`   TINYINT         NOT NULL DEFAULT 0,
  `version`      INT             NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_parent_name` (`tenant_id`, `parent_id`, `name`),
  KEY `idx_tenant_parent` (`tenant_id`, `parent_id`, `is_deleted`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='项目级资产分类树节点';

CREATE TABLE IF NOT EXISTS `asset_category_ref` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id`    BIGINT UNSIGNED NOT NULL,
  `asset_type`   VARCHAR(32)     NOT NULL DEFAULT 'SKILL' COMMENT '资产类型：SKILL（能力）；未来可扩展 MEMORY 等',
  `asset_id`     BIGINT UNSIGNED NOT NULL COMMENT '资产 id，SKILL 资产对应 skill.id',
  `category_id`  BIGINT UNSIGNED NOT NULL COMMENT '主分类 asset_category.id；每个资产最多一条在用关联',
  `gmt_create`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `gmt_modified` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `creator_id`   BIGINT UNSIGNED DEFAULT NULL,
  `modifier_id`  BIGINT UNSIGNED DEFAULT NULL,
  `is_deleted`   TINYINT         NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_asset` (`tenant_id`, `asset_type`, `asset_id`),
  KEY `idx_tenant_category` (`tenant_id`, `category_id`, `is_deleted`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='资产与分类的主分类关联（一资产一分类，物理删除即取消打标）';
