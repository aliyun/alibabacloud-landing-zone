-- H2 版 skill 建表语句，镜像 docs/autowonder-schema.sql 的列与唯一键 uk_type_name，
-- 仅用于 DAO 层唯一键回归测试。
CREATE TABLE IF NOT EXISTS skill (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id         BIGINT       NOT NULL,
    type              VARCHAR(16)  NOT NULL,
    name              VARCHAR(128) NOT NULL,
    install_spec      CLOB         DEFAULT NULL,
    description       VARCHAR(2048) DEFAULT NULL,
    source_type       VARCHAR(32)  NOT NULL DEFAULT 'INSTALL_SPEC',
    package_oss_ref   VARCHAR(512) DEFAULT NULL,
    package_file_name VARCHAR(255) DEFAULT NULL,
    package_size      BIGINT       DEFAULT NULL,
    package_md5       VARCHAR(64)  DEFAULT NULL,
    gmt_create        TIMESTAMP    DEFAULT NULL,
    gmt_modified      TIMESTAMP    DEFAULT NULL,
    creator_id        BIGINT       DEFAULT NULL,
    modifier_id       BIGINT       DEFAULT NULL,
    is_deleted        TINYINT      NOT NULL DEFAULT 0,
    version           INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_type_name UNIQUE (tenant_id, type, name)
);

-- 与线上一致：自增从 10000 起步
ALTER TABLE skill ALTER COLUMN id RESTART WITH 10000;
