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
