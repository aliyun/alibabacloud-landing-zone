# AutoWonder Community 1.0.0 — 2026-10-20

Status: CANDIDATE — validation PENDING. Internal MR: NOT_CREATED. GitHub PR: NOT_CREATED.

## Version and source

Version 0.10.1 → 1.0.0 (MAJOR). Upstream replaces member access-level APIs with
role-based APIs and replaces account cooling-off/revocation with immediate logical
deletion. These are incompatible API and operational contracts, so this release
uses a major increment rather than describing the change as backward-compatible.

- Previous verified master baseline: `25371cb104ac019fb26674f0c495c410c01e5041`.
- Previously incorporated master ancestor: `cc41f31632dd7ab5f5a8bd7d7916962445dcd02e`.
- Candidate master baseline: `2b97e41ffda2cfbb64f1fa74963867ebed7fe3ef`.
- Community before sync: `f51d701719b036a22c82336ca8df7ed6fccbd49f`.
- The verified baseline is not advanced by this pending candidate.

## Features and fixes

- Public OIDC/SAML enterprise federation, configuration UI, identity binding and
  read-only overview; assertion hardening and one-time state/ticket handling.
- Workspace roles and executor operation governance, platform user management,
  logical deletion/restoration, and workitem recycle bin.
- Chinese/English frontend and backend messages, locale preferences and formatted
  dates/numbers while preserving user content and diagnostic/protocol identities.
- Durable dispatch results, dispatch interaction requests and guidance, model
  selection, live executor launch configuration/restarts, and runtime 0.3.22.
- Clarification attachments, durable squad copy, insight scope and delivery timing,
  skill configuration refresh, and timeline rendering improvements.

## Community adaptations

Retains SecretCrypto, public dependency repositories, OSS/public SLS and optional
S3 support, default-disabled existing Aone, Qoder-only executor creation, root
`skills/` and `.agents/`, and Linux x86_64 release support. Public federation is
included; BUC and new standalone Aone status reconciliation are excluded. Existing
internal deployment profiles and historical development docs remain excluded.
Community `e2e-tests/` is unchanged from the pre-sync commit; no master E2E assets
are imported. Community Node 22/npm 10 and reviewed frontend dependency versions
are retained while adding public i18n dependencies and the upstream npm cache
configuration. Previously excluded help screenshots remain excluded after locale
renaming. New operational documentation is recorded in the retention policy.

## Upgrade And Data Impact

This is a coordinated application/database upgrade. Stop application writes and
back up the full database and secret master key before applying new SQL. Do not
run old and new servers against the migrated role/user data simultaneously.

`PUT /api/workspaces/current/members/{userId}/access-level` is replaced by
`PUT /api/workspaces/current/members/{userId}/role` with `roleId`; membership
creation and authorization now use roles. Update API consumers and automation.

`POST /api/users/me/deactivation` immediately logically deletes the account;
`GET /api/users/me/deactivation` and `POST /api/users/me/deactivation/revoke` are
removed. Recovery is an administrator action. Review affected integrations and
inform operators before upgrading. V088 converts existing active cooling-off
requests to logical deletions and removes their active member/IM links. Previously
anonymized accounts remain unchanged and cannot be restored by this migration.

Fresh installations import `docs/autowonder-schema.sql` and the Community template
seed; do not replay incremental migrations on a fresh schema. Existing installs
apply only previously unapplied new migrations in numeric order, using the
upgrade Skill's checksum history. Preserve existing encryption keys and storage
configuration. Audit member roles and user access before resuming traffic.

## DDL/DML/Migration Impact

Published V036–V075 files are byte-for-byte unchanged. Newly allocated files:

- `docs/migration/V076__workitem_recycle_bin_deleted_at.sql` (upstream `V040__workitem_recycle_bin_deleted_at.sql`).
- `docs/migration/V077__dispatch_requests.sql` (upstream `V061__dispatch_requests.sql`).
- `docs/migration/V078__durable_dispatch_result.sql` (upstream `V061__durable_dispatch_result.sql`).
- `docs/migration/V079__workitem_comment_participation_index.sql` (upstream `V061__workitem_comment_participation_index.sql`).
- `docs/migration/V080__dispatch_model_selection.sql` (upstream `V062__dispatch_model_selection.sql`).
- `docs/migration/V081__org_member_forbidden_domains.sql` (upstream `V062__org_member_forbidden_domains.sql`).
- `docs/migration/V082__workspace_roles.sql` (upstream `V063__workspace_roles.sql`).
- `docs/migration/V083__executor_last_connect_hostname.sql` (upstream `V064__executor_last_connect_hostname.sql`).
- `docs/migration/V084__dispatch_guidance.sql` (upstream `V065__dispatch_guidance.sql`).
- `docs/migration/V085__enterprise_sso_federation.sql` (upstream `V066__enterprise_sso_federation.sql`).
- `docs/migration/V086__squad_copy_operation.sql` (upstream `V066__squad_copy_operation.sql`).
- `docs/migration/V087__clarification_attachment.sql` (upstream `V067__clarification_attachment.sql`).
- `docs/migration/V088__user_logical_delete.sql` (upstream `V067__user_logical_delete.sql`).

V076 adds workitem deletion timestamps/indexes; V077/V078 add dispatch requests
and durable result/checkpoint storage; V079 adds comment participation indexing;
V080 stores dispatch model selections; V081 adds legacy member restriction data;
V082 creates roles and backfills member role IDs (including owner/admin and
restricted-member preservation); V083 stores executor hostnames; V084 adds durable
guidance; V085 adds federation providers/connections/identities; V086 stores squad
copy operations; V087 stores clarification attachments; V088 adds user deletion
snapshots and performs the cooling-off/member/IM DML described above.

V082 deliberately fails on orphaned or invalid memberships instead of granting
broader privileges. Several ALTER statements are one-shot operations: use schema
history and operator preflight, not blind retries. No Aone status-mapping migration
is introduced.

## Configuration and deployment impact

The recommended runtime is 0.3.22, derived solely from `application.yml` by deploy
and upgrade scripts and written before restart. Six optional federation settings
are explicitly environment-bound in that YAML, with upstream defaults documented
in `docs/community/runtime-configuration.md`. The union-based upgrade planner
discovers them, and the deployment Skill planner now honors their nonempty
defaults without requiring new overrides. No duplicate env-example entries or
mandatory new secret are required. Configure IdP endpoints/certificates and public callback base URL
before enabling SSO. Community multipart limits already cover 100 MB per file /
200 MB per request. Public SLS, encryption, object-storage mutual exclusion and
existing Aone env omissions remain unchanged.

## Rollback and risks

Rollback requires the pre-upgrade database backup, secret key and prior application
release as a matched set. An application-only downgrade after role backfill or
user deletion DML is unsupported. Preserve diagnostic and migration receipts.
Validate federation callback/assertion handling, role permissions, deleted-user
access, attachments, dispatch interaction and locale behavior on the candidate.

## Verification

PENDING: backend tests, frontend checks/tests/build, deployment/upgrade Skill tests,
full dependency-boundary scan, Community image/startup/authenticated check,
sync-specific API/UI journeys, cleanup, and independent reviews. No formal build
or test was run during implementation. Repository identity checks confirm an
unchanged E2E tree and published migration files; they are not product acceptance.
Evidence and eventual MR/PR links are external delivery records; this candidate
file intentionally does not predict their results.
