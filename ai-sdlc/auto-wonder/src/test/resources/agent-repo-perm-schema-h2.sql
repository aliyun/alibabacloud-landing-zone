-- H2 版 agent / agent_version / agent_repo_perm 建表语句，
-- 按 docs/autowonder-schema.sql 裁剪出仓库有效引用查询所需的列与唯一键，
-- 仅用于 AgentRepoPermDao.listActiveRefsByRepoId 的 DAO 层回归测试。
CREATE TABLE IF NOT EXISTS agent (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id          BIGINT       NOT NULL,
    name               VARCHAR(128) NOT NULL,
    status             VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    online_version_id  BIGINT       DEFAULT NULL,
    editing_version_id BIGINT       DEFAULT NULL,
    latest_version_no  INT          NOT NULL DEFAULT 0,
    gmt_create         TIMESTAMP    DEFAULT NULL,
    gmt_modified       TIMESTAMP    DEFAULT NULL,
    is_deleted         TINYINT      NOT NULL DEFAULT 0,
    version            INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS agent_version (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id  BIGINT      NOT NULL,
    agent_id   BIGINT      NOT NULL,
    version_no INT         NOT NULL,
    status     VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    gmt_create TIMESTAMP   DEFAULT NULL,
    is_deleted TINYINT     NOT NULL DEFAULT 0,
    version    INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_agent_ver UNIQUE (agent_id, version_no)
);

CREATE TABLE IF NOT EXISTS agent_repo_perm (
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id        BIGINT      NOT NULL,
    agent_version_id BIGINT      NOT NULL,
    repo_id          BIGINT      NOT NULL,
    perm_level       VARCHAR(16) NOT NULL DEFAULT 'READ',
    allowed_branch_patterns VARCHAR(10000) DEFAULT NULL,
    gmt_create       TIMESTAMP   DEFAULT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_ver_repo UNIQUE (agent_version_id, repo_id)
);

CREATE INDEX IF NOT EXISTS idx_agent_repo_perm_repo ON agent_repo_perm (tenant_id, repo_id);
