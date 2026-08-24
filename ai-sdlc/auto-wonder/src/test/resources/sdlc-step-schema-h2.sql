-- H2 版 sdlc_step 建表语句，镜像 docs/autowonder-schema.sql 的列与唯一键 uk_sdlc_order，
-- 仅用于 DAO 层唯一键回归测试。
CREATE TABLE IF NOT EXISTS sdlc_step (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id            BIGINT       NOT NULL,
    sdlc_id              BIGINT       NOT NULL,
    step_order           INT          NOT NULL,
    name                 VARCHAR(128) NOT NULL,
    kind                 VARCHAR(32)  DEFAULT NULL,
    instruction_md       CLOB         DEFAULT NULL,
    checklist_json       CLOB         DEFAULT NULL,
    gate_policy_json     CLOB         DEFAULT NULL,
    required             TINYINT      NOT NULL DEFAULT 1,
    timeout_seconds      INT          DEFAULT NULL,
    retry_budget         INT          DEFAULT NULL,
    code                 VARCHAR(64)  DEFAULT NULL,
    handler_type         VARCHAR(16)  DEFAULT NULL,
    handler_role_ref     VARCHAR(64)  DEFAULT NULL,
    status_on_enter_code VARCHAR(64)  DEFAULT NULL,
    on_success           CLOB         DEFAULT NULL,
    on_fail              CLOB         DEFAULT NULL,
    gmt_create           TIMESTAMP    DEFAULT NULL,
    gmt_modified         TIMESTAMP    DEFAULT NULL,
    creator_id           BIGINT       DEFAULT NULL,
    modifier_id          BIGINT       DEFAULT NULL,
    is_deleted           TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_sdlc_order UNIQUE (tenant_id, sdlc_id, step_order)
);

-- 与线上一致：自增从 10000 起步，避免 -id 哨兵与重排临时负序号在小 id 下误撞
ALTER TABLE sdlc_step ALTER COLUMN id RESTART WITH 10000;
