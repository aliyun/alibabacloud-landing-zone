-- Space dashboard scope query: only the tables/columns listScopedWorkspaces touches.
CREATE TABLE IF NOT EXISTS `org` (
  id BIGINT PRIMARY KEY,
  name VARCHAR(128) NOT NULL,
  owner_id BIGINT NOT NULL,
  status INT NOT NULL DEFAULT 0,
  is_deleted INT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS `workspace_role` (
  id BIGINT PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  access_level VARCHAR(16) NOT NULL,
  is_deleted INT NOT NULL DEFAULT 0
);

-- access_level here is the migration leftover column on purpose: the scope query
-- must ignore it and read workspace_role.access_level instead.
CREATE TABLE IF NOT EXISTS `org_member` (
  id BIGINT PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  status INT NOT NULL DEFAULT 0,
  access_level VARCHAR(16) NOT NULL DEFAULT 'READ_ONLY',
  role_id BIGINT NOT NULL,
  is_deleted INT NOT NULL DEFAULT 0
);
