CREATE TABLE IF NOT EXISTS workitem (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    work_type VARCHAR(16) NOT NULL,
    title VARCHAR(200) NOT NULL,
    content_md TEXT,
    template_id BIGINT,
    status_node_id BIGINT,
    sdlc_id BIGINT,
    current_step_id BIGINT,
    assignee_type VARCHAR(16),
    assignee_ref BIGINT,
    assign_operator_id BIGINT,
    priority INT,
    creator_id BIGINT,
    modifier_id BIGINT,
    origin_type VARCHAR(32),
    origin_id BIGINT,
    is_deleted TINYINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    gmt_create TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS platform_feature_switch (
    id BIGINT NOT NULL AUTO_INCREMENT,
    switch_key VARCHAR(64) NOT NULL,
    enabled TINYINT NOT NULL DEFAULT 0,
    modifier_id BIGINT,
    gmt_create TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_feature_switch_key UNIQUE (switch_key)
);

CREATE TABLE IF NOT EXISTS workitem_duplicate_check (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    operator_id BIGINT NOT NULL,
    workitem_id BIGINT,
    form_request_id VARCHAR(64),
    title VARCHAR(256) NOT NULL,
    title_fingerprint CHAR(64) NOT NULL,
    exclude_workitem_id BIGINT,
    candidate_since TIMESTAMP NOT NULL,
    candidate_total INT NOT NULL DEFAULT 0,
    candidate_snapshot_json TEXT,
    next_batch_index INT NOT NULL DEFAULT 0,
    request_id VARCHAR(64),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    match_json TEXT,
    error VARCHAR(128),
    cancel_pending TINYINT NOT NULL DEFAULT 0,
    deadline_at TIMESTAMP,
    lease_until TIMESTAMP,
    attempt INT NOT NULL DEFAULT 0,
    gmt_create TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS agent_conversation (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    channel VARCHAR(32) NOT NULL,
    channel_conversation_id VARCHAR(128),
    PRIMARY KEY (id)
);
