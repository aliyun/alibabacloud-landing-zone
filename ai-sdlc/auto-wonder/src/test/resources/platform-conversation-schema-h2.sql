-- H2 版平台管家会话建表语句，镜像 docs/migrations/V051 的列、唯一键与 Owner 索引，
-- 仅用于 DAO 层回归测试。生产 DDL 以 docs/migration/V062__platform_chief_conversation.sql 为准。
CREATE TABLE IF NOT EXISTS agent_conversation (
    id                      BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id               BIGINT       NOT NULL,
    owner_user_id           BIGINT       DEFAULT NULL,
    agent_id                BIGINT       NOT NULL,
    agent_version_id        BIGINT       DEFAULT NULL,
    channel                 VARCHAR(32)  NOT NULL,
    biz_ref_type            VARCHAR(32)  DEFAULT NULL,
    biz_ref_id              BIGINT       DEFAULT NULL,
    channel_conversation_id VARCHAR(256) NOT NULL,
    title                   VARCHAR(255) DEFAULT NULL,
    title_source            VARCHAR(16)  DEFAULT NULL,
    cli_session_ref         VARCHAR(256) DEFAULT NULL,
    executor_id             BIGINT       DEFAULT NULL,
    status                  VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    last_turn_at            TIMESTAMP    DEFAULT NULL,
    archived_at             TIMESTAMP    DEFAULT NULL,
    deleted_at              TIMESTAMP    DEFAULT NULL,
    gmt_create              TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified            TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version                 INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_conv UNIQUE (tenant_id, channel, channel_conversation_id, agent_id)
);

CREATE INDEX IF NOT EXISTS idx_platform_owner_list
    ON agent_conversation (tenant_id, channel, owner_user_id, deleted_at, last_turn_at, id);

CREATE TABLE IF NOT EXISTS conversation_share (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id       BIGINT      NOT NULL,
    conversation_id BIGINT      NOT NULL,
    grantee_user_id BIGINT      NOT NULL,
    permission      VARCHAR(16) NOT NULL DEFAULT 'READ',
    created_by      BIGINT      NOT NULL,
    revoked_at      TIMESTAMP   DEFAULT NULL,
    gmt_create      TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_conversation_grantee UNIQUE (tenant_id, conversation_id, grantee_user_id)
);

CREATE INDEX IF NOT EXISTS idx_grantee_active
    ON conversation_share (tenant_id, grantee_user_id, revoked_at, conversation_id);

CREATE TABLE IF NOT EXISTS conversation_turn_artifact (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id       BIGINT      NOT NULL,
    conversation_id BIGINT      NOT NULL,
    turn_id         BIGINT      NOT NULL,
    artifact_id     BIGINT      NOT NULL,
    direction       VARCHAR(8)  NOT NULL,
    reference_mode  VARCHAR(16) NOT NULL,
    display_order   INT         NOT NULL DEFAULT 0,
    manifest_json   CLOB        NOT NULL,
    gmt_create      TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_turn_artifact_direction UNIQUE (turn_id, artifact_id, direction)
);

CREATE INDEX IF NOT EXISTS idx_conversation_artifact
    ON conversation_turn_artifact (tenant_id, conversation_id, artifact_id)
