# Community 1.2.0 — frozen upstream synchronization

Status: CANDIDATE. Final required acceptance and releaseReadiness PENDING at the
2026-11-11 documentation snapshot. Later final-tree acceptance is recorded in the
external quality evidence and MR acceptance record; this snapshot asserts no
future PASS.

## Version and inputs

MINOR release for guided workspace initialization,
registration/SSO controls, workflow restart snapshots and notification/UI capabilities.
Previous verified master: `663ae626269d0ca95b0ff7d1123339422983fc5e`.
Frozen candidate master: `e7b4c01c2cf3e06df28d75bc0a9a0bfc909f71d2`.
Pre-sync Community: `33cf8117324511e7a8f87e53567c64e49d0f8817`.
Later upstream commits are outside this delivery.

## Features and fixes

- PRD design is not offered in this Community release: the workitem detail
  entry is hidden. Upstream implementation and schema remain synchronized.
- Guided Harness initialization with frozen variables, resumable batches,
  repository/capability bindings and evidence-based readiness.
- Agent draft/review diffs and submit notes; richer grouped workflow and
  executor management, scoped runtime package commands and recommendation 0.3.43.
- Registration review, workspace creation authorization, default SSO and optional
  federated provisioning; public SSO remains distinct from excluded internal BUC.
- Realtime notifications and duplicate-workitem warnings, delivery insights,
  durable workflow snapshots, explicit latest-workflow restart and recovery.
- Frontend lazy-route recovery, polling/timeline and artifact interaction fixes.

## Community adaptations

Keep SecretCrypto, public OSS/S3 storage, optional public SLS, existing Aone
disabled by default, Qoder-only executor creation UI and Linux x86_64 release.
Exclude standalone new Aone integrations and internal deployment profiles/history.
Generic reusable Harness/Skills retain their operations and customer placeholders.
Public Node/npm dependency versions and immutable migration history are preserved.
The upstream merge preserves the exact pre-sync Community E2E tree. Any later
separately authorized verification repair is recorded by its own commit and
acceptance evidence; Master E2E assets are not imported.

## Upgrade And Data Impact

Apply pending incremental migrations and take a verified backup before activating
this version. Existing users retain their account state. Registration review is
optional and defaults to disabled; when enabled, newly self-registered users
require administrator review. Workspace creation defaults to allowed; when that
global default is disabled, nonadministrators need explicit individual permission.
Existing integrations should review these switches and the new registration/SSO
controls before rollout. Refresh-token TTL follows the source default of one
year; existing access-token/session validation remains enforced.

New PRD/Harness/workflow data is additive and retained on rollback. Do not drop
tables, remove old fields, reset historical tasks, or rewrite accepted receipts.
The scoped runtime package must match the effective source recommendation.

## DDL/DML/Migration Impact

New migrations, in execution order:

- V101 agent version submit note; V102 legacy frontend probe/profile tables.
- V103 duplicate-intent tables; V104 PRD design/version/acceptance/frozen bindings.
- V105 duplicate-check snapshot/capacity/index additions.
- V106 workspace onboarding, frozen snapshots, resumable execution and readiness.
- V107 PRD manual target; V108 workspace guide progress.
- V109 notification related-link snapshots; V110 delivery insight indexes.
- V111 workflow, restart and external-operation receipt snapshots.
- V112 default SSO/registration settings and optional federated provisioning.
- V113 registration review/workspace permission and platform notification scope.

Run the complete dynamic SQL statements in one database session. V101–V113 are
new Community numbers for upstream migrations with colliding version numbers.
All previously published migrations remain unchanged. DML initializes the
platform authentication defaults equivalently; historical users, notifications,
ciphertext and dispatch data are not erased or mass-rewritten.
Fresh-versus-upgrade and historical-data verification results, including actual
tested commits, input bindings, ciphertext preservation and scope limitations,
are retained in the external migration/crypto evidence. Final release acceptance
remains subject to the current quality manifest and independent review.

## Configuration and deployment

Recommended runtime comes only from application.yml and source-derived deployment
resolution. New bounded queue/thread/HTTP/Redis and PRD/duplicate-check settings
are inventoried in retained YAML/operator guidance. Existing deployment overrides
remain compatible. Production frontend bundles retain cross-release route
continuity; Maven's Node heap is configurable using frontend.node.heap.mb.
Review optional switches and resource limits for the target workload.

## Verification

Snapshot tested candidate: `bf91ffe0452942fea3b3029159a2ac012a28bb74`, tree
`96cba4ee13848f40ace3eec7b34f66e067e4d585`. Attempt3 backend executed
8,875 tests with zero failures/errors and one explicit skip; original native
runtime PASS and the nine-check real-Qoder two-step supplement PASS are recorded.
The accumulated standard final check and standard cleanup returned0. These are
component results at the stated tree, not the later documentation commit.

Historical-Chief attempt3 failed with ENOSPC in the private 256MiB tmpfs;
restoration and cleanup were verified. The Chief-only resource correction and
next lifecycle remain unaccepted at this snapshot. Failed attempts and unresolved
historical causes stay archived; restoration is not scenario success.

Frozen Master is the business authority except documented Community adaptations
and explicit user scope decisions. Stale assertions and framework defects are
classified separately from synchronization code defects; correct business is not
changed to make E2E pass. Source reviews passed for the unchanged business inputs
and separately reviewed verification deltas; releaseReadiness remains PENDING.

The exact clean final candidate/tree, fresh runtime/Chief acceptance, SLS-mode
coverage, JSON fixture boundaries, migration/crypto and frontend/Skill results,
input-reuse proofs, original failures, final accumulated check/cleanup and three
independent review directions must close in external quality evidence and the MR
acceptance record. The final documentation tree must satisfy the runtime gate;
older tested commits are not relabelled as its executions. This dated source
snapshot will not be rewritten solely to embed its own commit, final counts or
later links. No final release PASS is asserted here.

## Risks and rollback

Back up database and object data and preserve the previous deployable image and
protected configuration. Roll back application assets/configuration to the prior
release when needed; preserve new additive data and migration history. Review
new account-review/authorization defaults, optional SSO, runtime compatibility,
schema capacity and resource limits before activation. Unresolved required
acceptance blocks release; a started process or unit fixture does not waive it.

## Delivery links

Internal MR: NOT_CREATED. External PR: NOT_CREATED.
Delivery scope is an exact fork branch and compare entry after approved internal
merge; no external PR or tag is created by this task.
