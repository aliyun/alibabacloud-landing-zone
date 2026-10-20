-- 企业 SSO 联合登录（工单 #55938）：IdP 实例注册、协议连接与外部身份绑定。
-- 存量数据库执行一次本迁移；新建数据库使用 docs/autowonder-schema.sql。
-- 不会自动执行迁移：请在发布代码前手动执行。
--
-- 模型：
--   federation_idp        一个实际 AD FS 部署或 Entra 租户（可多个同类实例）；
--   federation_connection 该实例的一条具体协议连接（providerId），协议字段共存在一行里按 protocol 取用；
--   federated_identity    外部身份到 AW 账号的绑定，唯一键 (provider_id, issuer, subject)。
-- 绑定键刻意不含 email/UPN：IdP 侧改邮箱或改 UPN 不得造成误绑定，展示信息只做同步刷新。
--
-- 密码字段约束调整：纯 SSO 账号没有本地密码，user.password_hash 由 NOT NULL 放宽为可空。
--   MODIFY COLUMN 本身幂等，重复执行不报错；应用侧登录与过滤器均已对空哈希做判空（纯 SSO 账号
--   无法通过密码登录，停用状态检查不再依赖哈希里的哨兵值）。
--
-- 重复执行：三条 CREATE TABLE 带 IF NOT EXISTS，可安全重跑。

CREATE TABLE IF NOT EXISTS `federation_idp` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `name` VARCHAR(64) NOT NULL COMMENT 'IdP 实例名（如「AD FS 生产」「Entra 租户 A」）',
  `description` VARCHAR(255) DEFAULT NULL,
  `enabled` TINYINT NOT NULL DEFAULT 1 COMMENT '0 停用 / 1 启用；停用后其连接不进入登录页',
  `created_by` BIGINT DEFAULT NULL COMMENT '创建者 user.id',
  `gmt_create` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `gmt_modified` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='企业 SSO 身份提供方实例';

CREATE TABLE IF NOT EXISTS `federation_connection` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `idp_id` BIGINT NOT NULL COMMENT '所属 IdP 实例（federation_idp.id）',
  `name` VARCHAR(64) NOT NULL COMMENT '登录方式名（登录页入口文案）',
  `protocol` VARCHAR(16) NOT NULL COMMENT 'SAML2 / OIDC',
  `enabled` TINYINT NOT NULL DEFAULT 1 COMMENT '0 停用 / 1 启用',
  `issuer` VARCHAR(255) NOT NULL COMMENT 'idpId 的 Issuer（OIDC 支持 {tid} 模板）',
  `saml_sso_url` VARCHAR(512) DEFAULT NULL COMMENT 'SAML SSO 地址（HTTP-Redirect 发起）',
  `saml_cert` TEXT DEFAULT NULL COMMENT 'SAML 签名证书（PEM/Base64 DER）',
  `oidc_authorize_url` VARCHAR(512) DEFAULT NULL,
  `oidc_token_url` VARCHAR(512) DEFAULT NULL,
  `oidc_jwks_url` VARCHAR(512) DEFAULT NULL,
  `client_id` VARCHAR(255) DEFAULT NULL,
  `client_secret` VARCHAR(1024) DEFAULT NULL COMMENT '客户端密钥密文（SecretCrypto 加密后落库）',
  `scopes` VARCHAR(255) DEFAULT NULL COMMENT 'OIDC scope，空则默认 openid profile email',
  `subject_claim` VARCHAR(128) DEFAULT NULL COMMENT '稳定主体声明映射（如 Entra 的 tid:oid；SAML 为属性名，nameid 表示 NameID）',
  `name_claim` VARCHAR(128) DEFAULT NULL,
  `email_claim` VARCHAR(128) DEFAULT NULL,
  `allowed_tenants` VARCHAR(1024) DEFAULT NULL COMMENT '逗号分隔的允许租户（Entra tid）清单，空为不限制',
  `sort_order` INT NOT NULL DEFAULT 0,
  `gmt_create` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `gmt_modified` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_federation_connection_name` (`name`),
  KEY `idx_federation_connection_idp` (`idp_id`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='企业 SSO 协议连接（providerId）';

CREATE TABLE IF NOT EXISTS `federated_identity` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `provider_id` BIGINT NOT NULL COMMENT '建立该绑定的连接（federation_connection.id）',
  `issuer` VARCHAR(255) NOT NULL COMMENT '校验通过的 Issuer',
  `subject` VARCHAR(255) NOT NULL COMMENT '稳定主体标识（如 Entra tid:oid、SAML NameID/属性）',
  `user_id` BIGINT NOT NULL COMMENT '绑定的 AW 账号（user.id）',
  `display_name` VARCHAR(128) DEFAULT NULL COMMENT '最近一次登录同步的展示名',
  `email` VARCHAR(128) DEFAULT NULL COMMENT '最近一次登录同步的邮箱（仅展示，不参与匹配）',
  `last_login_at` DATETIME(3) DEFAULT NULL,
  `gmt_create` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `gmt_modified` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_federated_identity_subject` (`provider_id`, `issuer`, `subject`),
  KEY `idx_federated_identity_user` (`user_id`),
  KEY `idx_federated_identity_provider` (`provider_id`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COMMENT='企业 SSO 外部身份绑定';

ALTER TABLE `user`
  MODIFY COLUMN `password_hash` VARCHAR(100) DEFAULT NULL COMMENT 'BCrypt 哈希（含盐）；纯 SSO 账号为空';
