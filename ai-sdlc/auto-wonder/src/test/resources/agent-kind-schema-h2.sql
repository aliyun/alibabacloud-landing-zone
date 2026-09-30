-- H2 版 agent 建表语句，镜像 docs/autowonder-schema.sql（含 V055__agent_kind_platform.sql 的 kind 列），
-- 用于模板创建 agent.kind 初始化的真实 NOT NULL 约束回归测试。
-- 用 BIGINT 代替 BIGINT UNSIGNED、TIMESTAMP 代替 DATETIME(3)，省略 ENGINE/CHARSET/COMMENT，保留主键与约束。
CREATE TABLE IF NOT EXISTS agent (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id          BIGINT       NOT NULL,
    name               VARCHAR(128) NOT NULL,
    avatar_url         VARCHAR(512) DEFAULT NULL,
    status             VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    kind               VARCHAR(20)  NOT NULL DEFAULT 'STANDARD',
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
