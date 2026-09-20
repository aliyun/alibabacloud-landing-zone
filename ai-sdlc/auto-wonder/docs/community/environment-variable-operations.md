# Workspace environment variables

Workspace administrators can manage encrypted environment variables and bind them
to digital-worker versions. Values are resolved for each task and conversation
turn; changing a value takes effect on the next execution. Removing a binding
requires publishing the updated worker version. Lists expose only `**`; reveal
responses are single-value and `Cache-Control: no-store`.

Community uses `SecretCrypto` with AES-256-GCM and `enc:v1:` references. Preserve
`AUTOWONDER_SECRET_MASTER_KEY` across upgrades: it must be standard Base64 for
exactly 32 bytes. All nodes sharing a database must use the same key. Never put
keys or values in manifests, logs, reports, command arguments, or source control.
Existing ciphertext cannot be decrypted after an unplanned key replacement.
This release does not implement online key rotation.

## Upgrade order

Upgrade executors to the runtime version resolved from the release's
`application.yml` before enabling environment-bound workers on the new Platform.
The recommended runtime is 0.2.163. The runtime must advertise
`AGENT_ENVIRONMENT_VARIABLES_V1`. An incompatible runtime must reject a bound
task or conversation; unbound work remains compatible.

Use the upgrade Skill plan, backup, runtime-config, stage, database-migrate,
rolling-upgrade and acceptance workflow. Import the full schema only for a new
installation. Existing deployments apply the newly numbered immutable migrations
after backup verification and migration approval. Retain the existing master key;
do not generate a replacement simply because a local environment file is missing.

Before the platform-admin initialization migration, verify the existing active
administrator list. An existing installation with no active system administrator
requires explicit administrative recovery before this migration; do not choose
an arbitrary user or automatically grant privileges. Stop old application nodes
before incompatible conversation/schema changes, apply the migration sequence,
then start the new version. DDL statements are not transactionally atomic.
Legacy conversation credentials missing agent/version identity must be reissued.

## Backup and recovery

Keep an off-node protected copy of the encryption key with access controls,
audit, ownership and retention covering every recoverable database backup.
Associate each backup with an opaque key-generation identifier in protected
operations state. Set `AUTOWONDER_SECRET_KEY_GENERATION_ID` in the protected
candidate environment to the existing escrow record's UUIDv4; do not invent a
new identifier to bypass missing key provenance. This operations metadata is not
a Platform encryption setting and contains no key material. Regenerate and approve
an upgrade plan when the generation, candidate environment or target runtime changes.
The identifier selects the escrowed key; it alone does not
prove that a node has installed the approved configuration. Validate the installed
protected environment checkpoint after staging and after activation.

Project configuration backups include encrypted references, never decrypted
values. A database or project backup without its matching encryption key is not
a complete recovery set. Test isolated restore and restart with an existing
encrypted variable and a task/conversation injection before relying on recovery.
Internal encryption references cannot be copied into Community and decrypted:
re-enter values in the destination or use a separately reviewed in-memory
decrypt/re-encrypt migration; never export plaintext files.

## Transport and rollback

Environment snapshots are plaintext within WS frames and cross-node Redis
Pub/Sub messages. Prefer trusted `wss://` and private Redis with authentication
and least privilege. Public `ws://` remains functional but provides no transport
confidentiality; record explicit risk acceptance and available network controls.
Do not enable payload/frame logging or place bearer tokens in access logs.

Before a Platform rollback, stop mutations, finish affected tasks/conversations,
unbind and publish or retire all environment-bound active worker versions, and
verify zero active bindings. Only then roll back Platform, followed by Runtime.
Keep additive tables, encrypted values and the matching key. Old Platform code
cannot enforce the new binding compatibility checks. Do not rely on it as a
rollback safety mechanism.

## Other configuration changes

The exact release's `application.yml` remains the sole source for the recommended
runtime. Deploy/upgrade tooling resolves it rather than maintaining a second pin.
`AUTOWONDER_RUNTIME_EXECUTOR_AUTO_UPDATE_ENABLED` defaults to true.
`OSS_BACKUP_BUCKET` optionally selects a private backup bucket; when empty, the
product falls back to the artifact bucket and then the shared bucket. These keys
are already represented by YAML placeholders in the upgrade environment contract.
Existing Aone remains disabled by default; no new Aone environment keys are added
to the example. Feishu is optional and configured through the platform UI.
