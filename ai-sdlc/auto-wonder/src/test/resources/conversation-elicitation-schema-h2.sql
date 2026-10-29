-- H2 版 agent_conversation_elicitation 建表语句，镜像 docs/migration/V049 的列、
-- 唯一键 uk_conv_request 与过期扫描索引，仅用于 DAO 层回归测试。
CREATE TABLE IF NOT EXISTS agent_conversation_elicitation (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id       BIGINT       NOT NULL,
    conversation_id BIGINT       NOT NULL,
    turn_id         BIGINT       NOT NULL,
    request_id      VARCHAR(64)  NOT NULL,
    mode            VARCHAR(16)  NOT NULL DEFAULT 'form',
    message         VARCHAR(1024) DEFAULT NULL,
    schema_json     CLOB         DEFAULT NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    answer_json     CLOB         DEFAULT NULL,
    answer_protocol_version INT DEFAULT NULL,
    dispatch_attempt INT DEFAULT NULL,
    executor_id BIGINT DEFAULT NULL,
    answer_id VARCHAR(64) DEFAULT NULL,
    delivery_status VARCHAR(16) DEFAULT NULL,
    delivery_deadline DATETIME DEFAULT NULL,
    next_delivery_at DATETIME DEFAULT NULL,
    gmt_create      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_conv_request UNIQUE (tenant_id, conversation_id, request_id)
);

CREATE INDEX IF NOT EXISTS idx_turn
    ON agent_conversation_elicitation (tenant_id, turn_id);

CREATE INDEX IF NOT EXISTS idx_pending_expiry
    ON agent_conversation_elicitation (status, gmt_create);
