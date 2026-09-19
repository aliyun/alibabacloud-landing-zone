-- H2 版 agent / agent_version 建表语句，镜像 docs/autowonder-schema.sql 的相关列，
-- 仅用于 AgentVersionDao.listAgentIdsBySdlcId 的“当前生效版本引用”真实查询回归测试。
-- 用 BIGINT 代替 BIGINT UNSIGNED、TIMESTAMP 代替 DATETIME(3)、CLOB 代替 MEDIUMTEXT/JSON，
-- 省略 ENGINE/CHARSET/COMMENT 与内联 KEY 索引（H2 保留字），保留主键与唯一键。
CREATE TABLE IF NOT EXISTS agent (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id          BIGINT       NOT NULL,
    name               VARCHAR(128) NOT NULL,
    avatar_url         VARCHAR(512) DEFAULT NULL,
    status             VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    online_version_id  BIGINT       DEFAULT NULL,
    editing_version_id BIGINT       DEFAULT NULL,
    latest_version_no  INT          NOT NULL DEFAULT 0,
    gmt_create         TIMESTAMP    DEFAULT NULL,
    gmt_modified       TIMESTAMP    DEFAULT NULL,
    creator_id         BIGINT       DEFAULT NULL,
    modifier_id        BIGINT       DEFAULT NULL,
    is_deleted         TINYINT      NOT NULL DEFAULT 0,
    version            INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS agent_version (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id           BIGINT       NOT NULL,
    agent_id            BIGINT       NOT NULL,
    version_no          INT          NOT NULL,
    status              VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    role_name           VARCHAR(128) DEFAULT NULL,
    role_code           VARCHAR(64)  DEFAULT NULL,
    business_background CLOB         DEFAULT NULL,
    responsibilities    CLOB         DEFAULT NULL,
    sdlc_id             BIGINT       DEFAULT NULL,
    identity_json       CLOB         DEFAULT NULL,
    reviewer_id         BIGINT       DEFAULT NULL,
    review_comment      VARCHAR(512) DEFAULT NULL,
    reviewed_at         TIMESTAMP    DEFAULT NULL,
    gmt_create          TIMESTAMP    DEFAULT NULL,
    gmt_modified        TIMESTAMP    DEFAULT NULL,
    creator_id          BIGINT       DEFAULT NULL,
    modifier_id         BIGINT       DEFAULT NULL,
    is_deleted          TINYINT      NOT NULL DEFAULT 0,
    version             INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_agent_ver UNIQUE (agent_id, version_no)
);

-- 与线上一致：自增从 10000 起步（测试均显式指定 id，此处仅为对齐生产序列）。
ALTER TABLE agent ALTER COLUMN id RESTART WITH 10000;
ALTER TABLE agent_version ALTER COLUMN id RESTART WITH 10000;
