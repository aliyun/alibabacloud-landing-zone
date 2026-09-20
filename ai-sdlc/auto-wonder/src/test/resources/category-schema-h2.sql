-- H2 版分类建表语句，镜像 docs/migration/V068__skill_category.sql 的列与唯一键
-- （uk_tenant_parent_name / uk_tenant_asset），仅用于 DAO 层回归测试。
CREATE TABLE IF NOT EXISTS asset_category (
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    tenant_id    BIGINT        NOT NULL,
    parent_id    BIGINT        DEFAULT NULL,
    name         VARCHAR(128)  NOT NULL,
    description  VARCHAR(2048) DEFAULT NULL,
    gmt_create   TIMESTAMP     DEFAULT NULL,
    gmt_modified TIMESTAMP     DEFAULT NULL,
    creator_id   BIGINT        DEFAULT NULL,
    modifier_id  BIGINT        DEFAULT NULL,
    is_deleted   TINYINT       NOT NULL DEFAULT 0,
    version      INT           NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_tenant_parent_name UNIQUE (tenant_id, parent_id, name)
);

-- 与线上一致：自增从 10000 起步
ALTER TABLE asset_category ALTER COLUMN id RESTART WITH 10000;

CREATE TABLE IF NOT EXISTS asset_category_ref (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id    BIGINT       NOT NULL,
    asset_type   VARCHAR(32)  NOT NULL DEFAULT 'SKILL',
    asset_id     BIGINT       NOT NULL,
    category_id  BIGINT       NOT NULL,
    gmt_create   TIMESTAMP    DEFAULT NULL,
    gmt_modified TIMESTAMP    DEFAULT NULL,
    creator_id   BIGINT       DEFAULT NULL,
    modifier_id  BIGINT       DEFAULT NULL,
    is_deleted   TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_tenant_asset UNIQUE (tenant_id, asset_type, asset_id)
);

ALTER TABLE asset_category_ref ALTER COLUMN id RESTART WITH 20000;
