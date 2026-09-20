CREATE TABLE IF NOT EXISTS environment_variable (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    name VARCHAR(128) NOT NULL,
    credential_ref VARCHAR(1000) NOT NULL,
    description VARCHAR(512),
    gmt_create TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    creator_id BIGINT,
    modifier_id BIGINT,
    is_deleted BIGINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_environment_variable_name UNIQUE (tenant_id, name, is_deleted)
);

CREATE TABLE IF NOT EXISTS agent (
    id BIGINT NOT NULL,
    tenant_id BIGINT NOT NULL,
    name VARCHAR(128) NOT NULL,
    online_version_id BIGINT,
    editing_version_id BIGINT,
    is_deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS agent_version (
    id BIGINT NOT NULL,
    tenant_id BIGINT NOT NULL,
    agent_id BIGINT NOT NULL,
    version_no INT NOT NULL,
    is_deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS agent_environment_variable_ref (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    agent_version_id BIGINT NOT NULL,
    environment_variable_id BIGINT NOT NULL,
    gmt_create TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_agent_environment_variable
        UNIQUE (tenant_id, agent_version_id, environment_variable_id)
);
