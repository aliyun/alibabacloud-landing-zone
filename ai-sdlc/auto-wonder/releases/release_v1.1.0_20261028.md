# AutoWonder Community 1.1.0 — 2026-10-28

Status: CANDIDATE. Required acceptance and independent reviews PENDING.

## Scope and version

Version 1.1.0 is explicitly selected by the release owner. Public API changes
below remain migration requirements; the version choice does not imply full API
backward compatibility. Fixed master input:
`663ae626269d0ca95b0ff7d1123339422983fc5e`, frozen 2026-10-28T09:58:55Z.
Previous verified master: `2b97e41ffda2cfbb64f1fa74963867ebed7fe3ef`.
Community starting tree: `d81418a4526cbdc104236979ecc3eb51f89fa89e`.
612 upstream commits / 1,141 changed paths include preserved Community exceptions.

## Features and fixes

- Shared DingTalk/Feishu group channels, routing ownership and delivery fencing.
- Squad workflow graphs and workspace-scoped task data records.
- Conversation token usage, delivery insights, stage duration and cross-workspace dashboards.
- Durable question/answer delivery, explicit expired-history continuation, pause/checkpoint retention and recovery.
- Workitem tags, execution controls, conversation/UI reliability and Markdown diagrams.
- Recommended executor runtime 0.3.30, derived from this source release.

## Community adaptations

Retain SecretCrypto and public storage/SLS, optional disabled existing Aone,
Qoder-only executor creation, public dependency registry and existing Community
package pins. New Mermaid dependency supports the upstream diagram feature.
Standalone Aone iterations and development notes are excluded; shared external
workitem identity behavior is retained because the generic importer depends on it.
Preserve root `skills/` and `.agents/`. The upstream merge preserves the pre-sync
`e2e-tests/` tree. A separately authorized Community repair updates only the JSON
regression management-API check from removed plaintext retrieval to create/list
mask assertions; executor original-value and resumed-session assertions remain.
New migration names are unique; all published migration contents remain unchanged.

## Upgrade And Data Impact

This is not a backward-compatible API upgrade. DingTalk/Feishu POST binding
creation now requires groupId plus platform GROUP_CHAT configuration and rejects
legacy independent-app credential payloads. Existing LEGACY rows can still be
updated. Update automation and provision platform group configuration before
creating new bindings. Environment variable `GET /{id}/value` plaintext retrieval
has been removed; UI/API lists remain masked, while task/conversation injection
continues through existing encrypted bindings. Clients must stop calling reveal.
The generic external-workitem identity key now forbids cross-binding duplicates;
V089 blocks unresolved existing conflicts rather than deleting history.


Back up the database and SecretCrypto master key before upgrading. Stop old server
instances during the migration window; apply only the new scripts below in order,
then activate this exact release. Keep existing credentials and binding data.
Fresh installs use the complete schema and Community template seed.

Shared bindings store credentials only in platform configuration; binding-level
credentials become nullable while legacy values retain TEXT capacity. Current
shared-group mode no longer requires confirmation codes. V094 introduces the
upstream transitional columns; V100 removes them before activation. Never run
V100 while an intermediate binary still uses those columns. Existing released
1.0.0 does not have these transitional columns, but mixed intermediate deployments
must follow this maintenance sequence.

V089 explicitly stops on duplicate/orphan external links and preserves a backup.
Resolve conflicts with operator review, never use mysql --force or delete history
to obtain a successful migration. Consult SQL preflight and backup-table rules
before retrying. New web sender/answer metadata leaves historical rows nullable.

## DDL/DML/Migration Impact

- `V089__external_workitem_identity.sql` (upstream `V067__external_workitem_identity.sql`).
- `V090__agent_conversation_usage.sql` (upstream `V068__agent_conversation_usage.sql`).
- `V091__dispatch_control_event.sql` (upstream `V068__dispatch_control_event.sql`).
- `V092__dashboard_pipeline_health_index.sql` (upstream `V069__dashboard_pipeline_health_index.sql`).
- `V093__dispatch_stage_event_index.sql` (upstream `V069__dispatch_stage_event_index.sql`).
- `V094__shared_group_channels.sql` (upstream `V070__shared_group_channels.sql`).
- `V095__squad_workflow_snapshot.sql` (upstream `V070__squad_workflow_snapshot.sql`).
- `V096__task_data_record.sql` (upstream `V070__task_data_record.sql`).
- `V097__conversation_reliability_scan_indexes.sql` (upstream `V071__conversation_reliability_scan_indexes.sql`).
- `V098__conversation_answer_delivery.sql` (upstream `V072__conversation_answer_delivery.sql`).
- `V099__conversation_turn_submitted_user.sql` (upstream `V073__conversation_turn_submitted_user.sql`).
- `V100__remove_group_confirmation.sql` (upstream `V074__remove_group_confirmation.sql`).

V089 snapshots and diagnoses external identity conflicts before uniqueness changes.
V090, V095 and V096 add usage/workflow/task-data tables. V091 adds control-event
metadata. V092–V093 and V097 add query indexes. V094 extends shared-channel tables
and routing constraints; V098–V099 add durable answer/sender metadata. V100 removes
only retired confirmation columns. DDL is not transactionally rolled back; inspect
information_schema and SQL guards before any retry. No destructive down script.

## Configuration and deployment

See `docs/community/runtime-configuration.md` for optional conversation/workflow,
insights and telemetry settings. All preserve upstream defaults and are discovered
by deployment/upgrade YAML inventory. No new mandatory credentials are introduced.
Runtime version remains source-derived; do not pin a second version in manifests.
Existing optional Aone environment omissions are intentional.

## Acceptance

PENDING: backend/full image, frontend tests/lint/i18n, cloud Skill fixture tests,
configuration/dependency/boundary gates, upgrade schema and data checks, standard
lifecycle check, real executor artifact delivery, selected master scenario mapping,
SLS startup and resource cleanup, and three independent reviews.
Final evidence is stored outside the immutable accepted tree and bound to exact
commit/tree. Existing debt and any NOT_RUN/SKIP must be reported without promotion.

## Risk and rollback

Back up before migration; V089 conflicts need operator resolution. Shared routing,
workflow and conversation recovery require multi-node deployment coordination.
If activation fails, keep traffic stopped, preserve diagnostics and restore a
verified pre-upgrade database backup before rolling binaries back when schemas
are incompatible. Do not run an older binary against removed-column intermediate
schemas. Object-storage acceptance uses the authorized MinIO S3-compatible backend, not
real Alibaba Cloud OSS. Real cloud/IdP behavior is not claimed.

## Delivery

Internal MR: NOT_CREATED. Target: community.
External output: NOT_CREATED. Exact accepted/merged tree will be mirrored to
`ai-sdlc/auto-wonder/` on a fork branch; delivery is a compare link, not a created PR.
No tag or protected-branch merge is authorized by this release record.
