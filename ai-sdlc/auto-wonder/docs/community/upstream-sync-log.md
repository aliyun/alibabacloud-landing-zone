# Community Upstream Sync Log

## Purpose

This file records the exact verified upstream baseline and sync history for the
long-lived `community` branch. Follow the constraints and procedure in the
[upstream sync guide](upstream-sync-guide.md) for every sync.

## Current Baseline

- Synchronized `origin/master`: `7f30bcf858ae2eebe698dc45b3c4404316e62d2e`
- Community merge commit: `5d4fefeb4e257d9e6f1b462d79df4f179650d056`
- Synchronized at: 2026-08-04 (Asia/Shanghai)

## History

### 2026-08-04: `6f7eecfc` to `7f30bcf8`

| Field | Commit |
| --- | --- |
| Previous synchronized baseline | `6f7eecfc191fb31cdb1e4139e668d488477f7a38` |
| Community before merge | `ac317df391a2af2a526615c520be649c7b8263f6` |
| Merged `origin/master` | `7f30bcf858ae2eebe698dc45b3c4404316e62d2e` |
| Resulting merge commit | `5d4fefeb4e257d9e6f1b462d79df4f179650d056` |

Scope: six master-side commits. The merge brought in ISO-8601 string schemas for
MCP timestamp outputs and requirement-document upload support for clarification
conversations. The insecure-context clipboard fix was already present on the
community branch and therefore produced no final tree change.

Two textual conflicts and the related frontend overlaps were reviewed:

- `ExecutorListPage.tsx` and `ExecutorListPage.test.tsx`: retained the master
  clipboard fallback while preserving the community Qoder CLI-only boundary;
  non-Qoder creation and startup paths remain absent.
- Clipboard sources, MCP token UI, ESLint configuration, and package metadata:
  the upstream clipboard change matched the existing community implementation;
  community public dependency versions remain unchanged.
- Upstream introduced no documentation requiring retention-policy filtering.

No product decision was required. Verification completed after the merge:

- Backend: 1,704 tests passed; production JAR built.
- Frontend: 79 test files and 471 tests passed; lint completed with zero errors
  and two existing hook warnings; production build transformed 4,757 modules.
- Deployment Skill: 37 contract tests passed.
- Maven dependency tree: no KeyCenter, Normandy, Akless, RASS, or legacy Log4j
  dependency was found.
- Active build/runtime inputs: no Alibaba-internal domain reference was found;
  excluded development documents were absent; Qoder CLI remains the only
  executor exposed by the community frontend.
- A first parallel frontend run was discarded after Maven concurrently rebuilt
  the shared `node_modules`; the required serial rerun completed successfully.

### 2026-08-04: `adc29fea` to `6f7eecfc`

| Field | Commit |
| --- | --- |
| Previous common baseline | `adc29fea568b96839e693c1f3009a02ef8cf0b8b` |
| Community before merge | `ff1bab4e8d91c8261a5c47d8bca6c3140bc57c9b` |
| Merged `origin/master` | `6f7eecfc191fb31cdb1e4139e668d488477f7a38` |
| Resulting merge commit | `522bcd473daa16317c655932a9e0f43e7af28a97` |

Scope: 11 master-side commits. The merge brought in the AutoWonder business-log
core-field contract, authenticated user/organization attribution, request
outcome and latency recording, the shorter clarification bootstrap prompt, and
safe omission of deleted Skill capabilities during task-package assembly.

One textual conflict and three overlapping files were reviewed:

- `BizLogProducer.java`: retained master log fields while preserving community
  `SlsProperties` credentials, optional SLS behavior, and local fallback.
- `AuthFilter.java` and `AuthFilterTest.java`: retained the community public
  read-only capabilities route and master user/organization log attribution.
- `BizLogProducerTest.java`: adapted only construction to community
  `SlsProperties`; the master field-contract assertion is unchanged.
- Upstream `docs/superpowers` working notes were excluded by documentation
  policy.

The final two packaging commits had no community overlap or frontend change.
No product decision was required. Verification completed after the merge:

- Backend: 1,704 tests passed; production JAR built.
- Frontend: 77 test files and 469 tests passed; production build transformed
  4,756 modules.
- Deployment Skill: 37 contract tests passed.
- Maven dependency tree: no KeyCenter, Normandy, Akless, RASS, or legacy Log4j
  dependency was found.
- Active build/runtime inputs: no Alibaba-internal domain reference was found;
  excluded development documents were absent.

### 2026-08-04: `da1b8be9` to `adc29fea`

| Field | Commit |
| --- | --- |
| Previous common baseline | `da1b8be9d94138038483517a40311f90a93d1979` |
| Community before merge | `bcfc55a13dc0d0e22013da2ff72c1e3cc9708f99` |
| Merged `origin/master` | `adc29fea568b96839e693c1f3009a02ef8cf0b8b` |
| Resulting merge commit | `ec0438d851915e2418caf5264cf5ef7b16bc9fcc` |

Scope: 39 master-side commits. The merge brought in memory distribution,
Repo Map/MCP context, agent MCP publishing and response fixes, requirement
clarification persistence and UI improvements, squad selection, SDLC status
display, and related tests.

The merge completed without textual conflicts. Four files changed on both
sides and were reviewed semantically:

- `docs/autowonder-schema.sql`: retained master schema additions and community
  SecretCrypto/domain-neutral definitions.
- `RequirementDocumentService.java`: retained master requirement-document flow
  and community OSS bucket resolution.
- `AppLayout.test.tsx`: retained the community async-stability assertion.
- `frontend/src/test/mocks/handlers.ts`: retained community-neutral URLs and
  master review-count handlers.

No product decision was required. Master functionality remained authoritative;
community-only differences are limited to external deployability boundaries.

Verification completed after the merge:

- Backend: 1,697 tests passed.
- Frontend: 77 test files and 469 tests passed; production build completed.
- Deployment Skill: 36 contract tests passed.
- Maven dependency tree: no KeyCenter, Normandy, Akless, RASS, or legacy Log4j
  dependency was found.
- Active build/runtime inputs: no Alibaba-internal domain reference was found.
