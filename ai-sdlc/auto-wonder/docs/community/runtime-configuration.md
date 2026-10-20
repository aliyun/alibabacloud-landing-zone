# Community runtime configuration

The runtime configuration contract is the union of `application*.yml` and
`application.env.example`. The optional settings below already have working
defaults in YAML and Java; deployment does not require new input. Set overrides
in the protected deployment environment and restart the application. Do not
duplicate these entries in the environment example merely to enumerate them:
the upgrade planner discovers YAML environment placeholders automatically.

| Property | Environment variable | Default | Effect |
| --- | --- | --- | --- |
| `spring.redis-meta.poolMaxWaitMs` | `REDIS_POOL_MAX_WAIT_MS` | `2000` | Maximum wait for a Redis pool connection, milliseconds; nonpositive values fall back to 2000 in Java. Previously fixed at 10000. |
| `autowonder.im.notification.dlq-stream-key` | `AUTOWONDER_IM_NOTIFICATION_DLQ_STREAM_KEY` | `autowonder:im-notification:dlq` | Redis stream for notifications that exhaust retries. Keep identical across cluster nodes. |
| `autowonder.im.notification.dlq-max-length` | `AUTOWONDER_IM_NOTIFICATION_DLQ_MAX_LENGTH` | `10000` | Dead-letter stream retention target; nonpositive values fall back to 10000. |
| `autowonder.im.notification.max-backoff-ms` | `AUTOWONDER_IM_NOTIFICATION_MAX_BACKOFF_MS` | `30000` | Notification retry backoff cap; nonpositive values fall back to 30000. |
| `autowonder.debug-log.reconciliation.fixed-delay-ms` | `AUTOWONDER_DEBUG_LOG_RECONCILIATION_FIXED_DELAY_MS` | `3600000` | Interval between debug-log reconciliation runs. |
| `autowonder.executor-update.scan-fixed-delay-ms` | `AUTOWONDER_EXECUTOR_UPDATE_SCAN_FIXED_DELAY_MS` | `60000` | Executor upgrade scan interval. |
| `autowonder.dispatch.recovery.package-retries` | `AUTOWONDER_DISPATCH_RECOVERY_PACKAGE_RETRIES` | `3` | Recovery package retry limit. |
| `autowonder.dispatch.recovery.retry-delay-ms` | `AUTOWONDER_DISPATCH_RECOVERY_RETRY_DELAY_MS` | `30000` | Delay before retrying recovery packaging. |
| `feishu.inbox.poll-ms` | `FEISHU_INBOX_POLL_MS` | `3000` | Durable Feishu callback inbox polling interval. |
| `feishu.inbox.initial-delay-ms` | `FEISHU_INBOX_INITIAL_DELAY_MS` | `15000` | Delay before the first Feishu inbox poll after startup. |

Use positive numbers for scheduling intervals and retry limits; the Java
fallbacks listed above apply only to the indicated Redis/notification settings.
Feishu inbox timing does not provision a robot or enable an IM provider: configure
the selected provider and binding through the platform as described in
[`feishu-channel.md`](../feishu-channel.md). The inbox table must be migrated
before starting the new server, even when no robot is configured.

`autowonder.runtime.executor-auto-update-enabled` is bound to
`AUTOWONDER_RUNTIME_EXECUTOR_AUTO_UPDATE_ENABLED`, default `true`. Set it to
`false` before restarting if operators require manual executor upgrades; manual
and batch upgrades remain available. The recommended runtime version comes from
`autowonder.runtime.recommended-version` in the exact source release. Deploy and
upgrade tooling validates that value, records it in the working manifest and
writes `AUTOWONDER_RUNTIME_RECOMMENDED_VERSION` before activation. Do not keep a
separate hard-coded runtime version in deployment templates.

`oss.backup-bucket` / `OSS_BACKUP_BUCKET` is optional and defaults to empty.
Backups use this bucket when configured, then `oss.artifact-bucket`, then
`oss.bucket`. Ensure an explicit backup bucket is reachable through the configured
storage endpoint and writable with its credentials. An additional bucket or new
credentials are not required for the default path. OSS and S3 remain mutually
exclusive durable backends; the S3 backend also uses these `oss.*` bucket names.

The existing Aone integration remains optional and disabled by default. New
Aone deployment and comment-poller configuration is excluded from Community.

## v0.10.0 memory document store

`memory.document-store.enabled` binds `AUTOWONDER_MEMORY_DOCUMENT_STORE_ENABLED`
and defaults to `true`. Existing installations must apply V071–V075 as described
in the v0.10.0 release before application activation; V075 requires a staged
dry-run and operator review. Fresh installations use the full schema. Legacy
memory tables remain intact, but active memory writes use document stores and ACLs.
The upgrade planner already inventories this YAML placeholder; it does not need
a redundant entry in the example environment file. No new service or credential
is required. Recommended runtime remains derived from application.yml (0.3.3).

## Public enterprise federation (1.0.0)

OIDC and SAML2 identity providers are configured by platform administrators in
Platform Configuration → Enterprise SSO. Connections and identity providers must
both be enabled before a login option is visible. A configured public HTTPS base
URL supplies callback URLs. Identity-provider endpoints and certificates are
operator inputs; there is no built-in internal identity provider or BUC dependency.
Client secrets use the same Community SecretCrypto master key as other protected
configuration. Keep that key stable and backed up during upgrades.

The following optional settings are bound in `application.yml`, so the deploy and
upgrade environment inventory already includes them without duplicating entries
in `application.env.example`:

| Environment key | Default | Meaning |
| --- | --- | --- |
| AUTOWONDER_FEDERATION_SP_ENTITY_ID | autowonder | SAML issuer/audience |
| AUTOWONDER_FEDERATION_LOGIN_STATE_TTL_SECONDS | 600 | One-time login state lifetime |
| AUTOWONDER_FEDERATION_TICKET_TTL_SECONDS | 300 | Callback ticket lifetime |
| AUTOWONDER_FEDERATION_CLOCK_SKEW_SECONDS | 120 | Assertion clock tolerance |
| AUTOWONDER_FEDERATION_JWKS_CACHE_TTL_SECONDS | 3600 | OIDC signing-key cache lifetime |
| AUTOWONDER_FEDERATION_HTTP_TIMEOUT_SECONDS | 15 | Identity-provider HTTP timeout |

The properties preserve upstream defaults and validation behavior. Do not reduce
clock or expiry protections to work around an incorrect IdP configuration. No
new mandatory deployment secret is required when federation is unused.

Runtime recommendation now defaults to 0.3.22 in `application.yml`. Deployment
and upgrade scripts continue deriving it from that exact release and writing the
resolved override before application activation; do not pin a second value in a
manifest template. Community `application-local.yml` already provides multipart
limits of 100 MB per file and 200 MB per request, including clarification uploads.
