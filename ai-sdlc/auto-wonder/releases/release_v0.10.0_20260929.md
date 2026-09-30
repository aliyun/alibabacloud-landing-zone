# AutoWonder Community v0.10.0

Version: 0.9.0 → 0.10.0 (MINOR). This pre-1.0 release adds server-backed memory, delivery restarts, shared artifacts and an updated workspace UI. It requires the data migration sequence below; it is not a binary-only upgrade.

## Master Baselines

- Last recorded independently reviewed baseline: `25371cb104ac019fb26674f0c495c410c01e5041`.
- Master already incorporated by the starting Community tree: `d795eea3a8261a0fe22c2d2c8572d774699f3386`.
- This synchronization source: `cc41f31632dd7ab5f5a8bd7d7916962445dcd02e`.
- Community before synchronization: `64a278433086f8e190f5b61610cab0134d8a8d97`.

## Features And Fixes

- Authoritative memory stores, document/topic management, ACLs, import and legacy-memory migration, maintenance leases and comment-driven learning.
- Explicit delivery restart rounds on reassignment, stopped/superseded execution display and recovery fencing.
- Immutable anonymous artifact sharing, durable share requests and preview pages.
- Unified workitem status categories and transitions; external source status remains a read-only snapshot.
- Updated themes, navigation hubs, Markdown clarification editor, charts, Credits reporting, pagination and profile management.
- Skill package root normalization and executor configuration recovery; recommended executor runtime is 0.3.3.

## Community Adaptations

Public OSS/S3 and SLS, SecretCrypto, optional Aone disabled by default, and Qoder-only executor creation remain the Community boundaries. Standalone Aone rich-text/discovery and internal deployment iterations are excluded. Shared status-category and JSON contracts are retained. Root `skills/` and `.agents/` remain distributable. The complete pre-sync `e2e-tests/` tree is unchanged. Internal development notes and previously removed internal screenshots are excluded.

## Upgrade And Data Impact

Fresh installations use the updated full schema. Existing installations must back up the database and application/environment identity, stop application writers, inspect and apply new migrations in order before activating this release. The memory document store is enabled by default; legacy memory tables remain for migration/rollback, but newly written document-store data must be preserved separately before any rollback. Legacy review/version bindings no longer define the active memory authority.

V075 changes status categories and moves applicable legacy Aone-template workitems to default templates. Follow its A/B/C/D/E sections: backup, category correction, dry-run/report review, actual migration, and observation-period rollback. Do not blindly execute the rollback section during upgrade. Its upstream `bak_v057_*` backup names are intentionally unchanged; the Community file number is V075. The supported upgrade workflow now enforces this sequence: after maintenance-stop, the first `database-migrate --confirm-migrations` produces a protected live C report and leaves the checkpoint `awaiting-review`. Read the report on the recorded ECS, review the exact digest, then continue with `--reviewed-v075-report <sha256>` (PowerShell: `-ReviewedV075Report <sha256>`). D and activation remain blocked until that report and fresh live output match; interruptions require reviewed recovery. See [the V075 checkpoint runbook](../skills/upgrading-autowonder-on-alibaba-cloud/references/upgrade-runbook.md#v075-live-report-checkpoint) for report inspection, continuation and recovery. No production migration is performed by this synchronization.

Rollback requires the pre-upgrade application plus the matching pre-upgrade database snapshot or the migration's documented reversal after reviewing writes made since activation; do not merely downgrade the executable.

## DDL/DML/Migration Impact

| Community migration | Impact |
|---|---|
| V071__external_artifact_share.sql | Immutable artifact-share snapshot schema |
| V072__workitem_delivery_restart.sql | Delivery restart rounds and identity |
| V073__server_backed_memory.sql | Memory stores/documents, ACL/import/change/lease data |
| V074__artifact_share_request.sql | Durable artifact share declarations |
| V075__unify_status_kanban.sql | Status-category normalization, data migration and backup/rollback workflow |

These five Community migrations are included in this release. Previously published V036–V070 are unchanged.

## Configuration And Deployment Impact

`AUTOWONDER_MEMORY_DOCUMENT_STORE_ENABLED` defaults to true and is bound in `application.yml`; existing deploy/upgrade tooling includes YAML placeholders in its environment inventory. The setting can stage activation, but is not a substitute for schema migration. No additional database service, credential or public endpoint is required. The runtime recommended-version source remains `application.yml`; deploy/upgrade must derive 0.3.3 from the exact release rather than a hard-coded manifest value. Aone-specific environment keys remain excluded from the example except `AUTOWONDER_AONE_ENABLED`.

## Verification Results

Preparation: main and test sources compile; immutable migration byte comparisons and Community E2E-tree preservation pass. Full backend/frontend/Skill/boundary/E2E and independent review results are pending the exact-SHA quality loop. This file is not a PASS certificate. The final quality manifest and MR evidence identify the actually verified commit and supersede preparation results.

## Risks

Existing deployments need the staged data upgrade above. Native Windows and real cloud production upgrades are not claimed as tested. No unresolved product failure is eligible for the MR gate; the final quality loop must close all findings.

## MR/PR Links

Internal MR: not created; create only after exact-SHA quality gates pass. GitHub upstream PR: not created; public output is eligible only after human merge of the internal MR. These statements are status, not existing MR/PR links. No release tag is authorized.
