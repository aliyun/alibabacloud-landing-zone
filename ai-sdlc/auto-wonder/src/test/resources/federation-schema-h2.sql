CREATE TABLE IF NOT EXISTS federation_idp (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(64) NOT NULL,
    description VARCHAR(255),
    enabled TINYINT NOT NULL DEFAULT 1,
    created_by BIGINT,
    gmt_create TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS federation_connection (
    id BIGINT NOT NULL AUTO_INCREMENT,
    idp_id BIGINT NOT NULL,
    name VARCHAR(64) NOT NULL,
    protocol VARCHAR(16) NOT NULL,
    enabled TINYINT NOT NULL DEFAULT 1,
    issuer VARCHAR(255) NOT NULL,
    saml_sso_url VARCHAR(512),
    saml_cert TEXT,
    oidc_authorize_url VARCHAR(512),
    oidc_token_url VARCHAR(512),
    oidc_jwks_url VARCHAR(512),
    client_id VARCHAR(255),
    client_secret VARCHAR(1024),
    scopes VARCHAR(255),
    subject_claim VARCHAR(128),
    name_claim VARCHAR(128),
    email_claim VARCHAR(128),
    allowed_tenants VARCHAR(1024),
    sort_order INT NOT NULL DEFAULT 0,
    gmt_create TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_federation_connection_name UNIQUE (name)
);

CREATE TABLE IF NOT EXISTS federated_identity (
    id BIGINT NOT NULL AUTO_INCREMENT,
    provider_id BIGINT NOT NULL,
    issuer VARCHAR(255) NOT NULL,
    subject VARCHAR(255) NOT NULL,
    user_id BIGINT NOT NULL,
    display_name VARCHAR(128),
    email VARCHAR(128),
    last_login_at TIMESTAMP,
    gmt_create TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_federated_identity_subject UNIQUE (provider_id, issuer, subject)
);
