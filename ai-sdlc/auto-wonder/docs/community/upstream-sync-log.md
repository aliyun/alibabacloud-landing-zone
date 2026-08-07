# Community Upstream Sync Log

## Purpose

This file records the exact verified upstream baseline and sync history for the
long-lived `community` branch. Follow the constraints and procedure in the
[upstream sync guide](upstream-sync-guide.md) for every sync.

## Current Baseline

- Synchronized `origin/master`: `3262a46f66ac5087261429250091bfea5a61d12b`
- Community merge commit: `802dbe507af1f3729ddd4c6ff6d6cddd15d38ffd`
- Synchronized at: 2026-08-07 (Asia/Shanghai)

## History

### 2026-08-07: `484a30c1` to `3262a46f`

| Field | Commit |
| --- | --- |
| Previous synchronized baseline | `484a30c19f99dd401b1f1b1cf66b11154d0484ca` |
| Community before merge | `73b973dab484736790ce3975ff152f36147a15d5` |
| Merged `origin/master` | `3262a46f66ac5087261429250091bfea5a61d12b` |
| Resulting merge commit | `802dbe507af1f3729ddd4c6ff6d6cddd15d38ffd` |

Scope: three upstream commits and two frontend files. The work-item comment
mention menu now has a bounded height and vertical scrolling, with two focused
component tests proving that long candidate lists remain fully available.

The merge had no textual conflict or shared-file overlap after the previous
baseline. Both upstream files were accepted byte-for-byte, no Community
adaptation or product decision was required, and no master behavior was lost.

Deployment review found no configuration, environment variable, DDL,
dependency, runtime, service, documentation, or operational change. The
deployment Skill, environment templates, scripts, and operator guidance
therefore require no update.

Independent review proved `3262a46f66ac5087261429250091bfea5a61d12b` is an
ancestor of Community, accounted for all two changed files, and found them
identical to master. Documentation policy, external deployment boundaries,
Qoder-only executor creation, and public-output requirements were rechecked.

Verification completed after the merge:

- Backend: 1,828 tests passed.
- Frontend: 85 test files and 526 tests passed; lint completed with zero errors
  and two existing hook warnings; production build transformed 4,766 modules.
- Deployment Skill: 74 tests passed.
- Maven dependency tree and active runtime inputs contained no prohibited
  internal dependency or domain; excluded documents remained absent.

### 2026-08-07: `f858771a` to `484a30c1`

| Field | Commit |
| --- | --- |
| Previous synchronized baseline | `f858771adf3503ce947cb072ba4c7ddc20a609ef` |
| Community before merge | `cc8e52f87bdd4dec6bc6c999724a15d679c5a4b2` |
| Merged `origin/master` | `484a30c19f99dd401b1f1b1cf66b11154d0484ca` |
| Resulting merge commit | `e9184184d79ac565f40e3795d960c06c8d716b6b` |

Scope: 16 upstream commits and 36 files. The merge adds T-1 human-agent
participation insights, lifecycle fact reconstruction, assignment ownership
persistence and historical fallback, nightly snapshots, cache-only APIs, force
refresh, distributed single-flight coordination, paginated event loading, and
the corresponding trend, breakdown, and slow-tail UI.

Two conflicts and one follow-up test adaptation were reviewed:

- `application.yml` retains Community external OSS/SLS, SecretCrypto, optional
  Aone, and SIGAR configuration while accepting every new participation
  scheduling, cache, lock, and worker default;
- upstream introduced a participation migration as V036, but Community V036 is
  already an immutable account-deactivation migration, so the new migration is
  published as `docs/migration/V037__human_agent_participation_indexes.sql`;
- `InsightsDaoMappingTest` follows the Community migration path and number. The
  relocated SQL body is byte-identical to master, historical V036 is unchanged,
  and both indexes are present in the fresh-install schema.

Deployment review found no new required environment variable or service. The
new insight settings have application defaults, so the Skill and environment
templates require no change. Existing upgrade guidance discovers and applies
the ordered V037 migration from `docs/migration/` before restart.

Independent review proved `484a30c19f99dd401b1f1b1cf66b11154d0484ca` is an
ancestor of Community, accounted for all 36 changed files, and found no missing
master product source or behavior. The only feature-range differences are the
documented Community configuration and migration-test path adaptations.
Documentation policy, migration immutability, external deployment boundaries,
Qoder-only executor creation, and public-output requirements were rechecked.

Verification completed after the merge:

- Backend: 1,828 tests passed.
- Frontend: 84 test files and 524 tests passed; lint completed with zero errors
  and two existing hook warnings; production build transformed 4,766 modules.
- Deployment Skill: 74 tests passed.
- Maven dependency tree and active runtime inputs contained no prohibited
  internal dependency or domain; excluded documents remained absent and the
  Community executor UI remained Qoder CLI-only.

### 2026-08-07: `58140e68` to `f858771a`

| Field | Commit |
| --- | --- |
| Previous synchronized baseline | `58140e68a741d29133d77fc05427c1174b9247a4` |
| Community before merge | `c3316737d8bf58b26f55d3cdcf1581179923b1c0` |
| Merged `origin/master` | `f858771adf3503ce947cb072ba4c7ddc20a609ef` |
| Resulting merge commit | `8642ed54359eaee602a8fb2057e1ba5f32607f56` |

Scope: 54 upstream commits. The merge adds account deactivation with a cooling-off
period, repository deletion UI, Agent-card navigation, persistent Qoder startup
preferences, MCP squad/default-SDLC/pause-dispatch tools, improved work-item MCP
guidance, DingTalk sender context, isolated IM scheduling, stable conversation
capability fingerprints, more resilient OSS logo delivery, and runtime `0.2.125`.

Six textual conflicts and all 17 automatically overlapping files were reviewed:

- executor UI and tests retain the Community Qoder CLI-only boundary while
  accepting master Qoder preference persistence and runtime `0.2.125`;
- profile settings accept the deactivation tab and replace master's unsafe tab
  cast with equivalent type-safe selection;
- `application.yml` and `application-local.yml` accept product/runtime changes
  while retaining external OSS/SLS, SecretCrypto, optional Aone, and SIGAR
  configuration; internal `application-daily.yml` remains excluded;
- auth, branding, repository, work-item, IM, and organization-access overlaps
  retain all master behavior plus existing public metadata and external-storage
  adaptations;
- the one community-only `AuthFilterTest` constructor missed by the automatic
  merge was aligned with master's new `UserDao` dependency in `8221b267`.

Database review found upstream V036. The fresh schema now contains the three
account-deactivation columns, and the immutable migration is published at
`docs/migration/V036__user_account_deactivation.sql`; upstream's alternate
`docs/migrations/` location is excluded. Deployment review found no new required
environment variable or service. The Skill manifest, input catalog, and tests
were updated from runtime `0.2.117` to `0.2.125`; no other deployment change is
required.

Independent review proved `f858771adf3503ce947cb072ba4c7ddc20a609ef` is an
ancestor of Community, accounted for every shared file and conflict, and found
no lost master feature, unintended internal configuration, or unresolved
product decision. Documentation policy, migration immutability, external
deployment boundaries, Qoder-only executor creation, and the public output base
were rechecked. A follow-up byte-level audit aligned the relocated V036 content
exactly with master and confirmed that no upstream product source was deleted.

Verification completed after the merge:

- Backend: 1,788 tests passed.
- Frontend: 84 test files and 524 tests passed; lint completed with zero errors
  and two existing hook warnings; production build transformed 4,761 modules.
- Deployment Skill: 74 tests passed.
- Maven dependency tree and active runtime inputs contained no prohibited
  internal dependency or domain; excluded documents remained absent and the
  Community executor UI remained Qoder CLI-only.

### 2026-08-06: `41aedc7f` to `58140e68`

| Field | Commit |
| --- | --- |
| Previous synchronized baseline | `41aedc7f9ba2d4a7e8fd50e504fb091ea94e2f1a` |
| Community before merge | `ddba1e2596941319f1b9b900858b2168620c9cdf` |
| Merged `origin/master` | `58140e68a741d29133d77fc05427c1174b9247a4` |
| Resulting merge commit | `1e9222ef91e4a2320d391910032fe72a82c8c85d` |

Scope: twelve upstream commits. The merge adds OSS service/public endpoint
separation, bulk Agent capability bindings, the current user's password-change
API and UI, and advances the recommended runtime to `0.2.117`.

Four textual conflicts were resolved:

- `ExecutorListPage.test.tsx`: retained the community Qoder CLI-only contract,
  updated commands to `0.2.117`, and kept the provider literal aligned with the
  single supported executor;
- `OssProperties.java` and `ObjectStorageConfigTest.java`: retained mandatory
  OSS configuration, bucket fallback, and the no-in-memory-storage boundary
  while accepting the upstream dual-endpoint behavior;
- `application.yml`: retained community SecretCrypto, public SLS, optional
  Aone, and SIGAR settings while accepting the dual OSS endpoint and runtime
  version changes;
- the deployment Skill manifest, input catalog, and contract tests were updated
  to the same `0.2.117` runtime default.

The upstream bulk-binding design record under `docs/superpowers` was excluded by
the community documentation policy. A new unsafe tab cast in the password UI
was corrected in follow-up commit `c9979967`; no product decision was required.

Verification completed after the merge:

- Backend: 1,746 tests passed; the production JAR included the frontend static
  assets.
- Frontend: 84 test files and 513 tests passed; lint completed with zero errors
  and two existing hook warnings; production build transformed 4,760 modules.
- Deployment Skill: 52 contract tests passed.
- Maven dependency tree and active runtime inputs contained no prohibited
  internal dependency or domain; excluded documents remained absent, the
  executor UI remained Qoder CLI-only, and all active runtime defaults were
  `0.2.117`.

### 2026-08-05: `ce1764e9` to `41aedc7f`

| Field | Commit |
| --- | --- |
| Previous synchronized baseline | `ce1764e957b287fb64dba867c1e9703d6c914c8e` |
| Community before merge | `86bd90d01a94469c03a9df717b26921836f70984` |
| Merged `origin/master` | `41aedc7f9ba2d4a7e8fd50e504fb091ea94e2f1a` |
| Resulting merge commit | `44544f27d8a05706fbd19631790f97a82e44c0f5` |

Scope: five upstream commits. Personal DingTalk identity management no longer
requires an organization context, and MCP repository management now includes
create, update, and delete tools with coverage.

The merge had no textual conflicts. Automatic overlaps in organization-access
annotation coverage and auth-filter tests were reviewed; master behavior and
the existing community exemptions were both retained. A pre-existing unsafe
test spy in `AgentReviewPage.test.tsx` was corrected in `20d5c220` so the full
lint gate remains clean. No product decision was required.

Verification completed after the merge:

- Backend: 1,732 tests passed; production JAR built and the frontend production
  build transformed 4,759 modules.
- Frontend: 83 test files and 508 tests passed; lint completed with zero errors
  and two existing hook warnings.
- Deployment Skill: 48 contract tests passed.
- Maven dependency tree and active runtime inputs contained no prohibited
  internal dependency or domain; excluded documents remained absent and the
  community executor UI remained Qoder CLI-only.
- The first standalone frontend invocation inherited an arm64 child `node`
  against Maven's x64 Rollup package and was discarded; rerunning with Maven's
  Node directory first in `PATH` passed.

### 2026-08-05: `75bd9303` to `ce1764e9`

| Field | Commit |
| --- | --- |
| Previous synchronized baseline | `75bd9303030df7bbf876cdb96513dcfa936868b1` |
| Community before merge | `4cfcdc854dcaed3bf604b05b0533f3a709263d58` |
| Merged `origin/master` | `ce1764e957b287fb64dba867c1e9703d6c914c8e` |
| Resulting merge commit | `3af87a3ab4f4039bb4f646b49848c624d75bfcee` |

Scope: four upstream commits. Agent approve/reject failures now expose backend
errors, agent listing enforces tenant isolation, and dispatch MCP tokens inherit
the user's actual organization access level instead of hard-coded `READ_WRITE`.

The merge had no textual conflict, community-boundary overlap, configuration or
documentation-policy input, or product decision. Backend verification passed
1,721 tests. The changed Agent review frontend suite passed all four tests; the
immediately preceding full frontend, build, lint, Skill, and dependency-boundary
gates remained applicable because this batch did not change those inputs.

### 2026-08-05: `a4e9ec9e` to `75bd9303`

| Field | Commit |
| --- | --- |
| Previous synchronized baseline | `a4e9ec9e2e8a1adebf1154ea89ef6af88b84278f` |
| Community before merge | `c154b3fc8e35ad7038b92bee407ac6a4402dc348` |
| Merged `origin/master` | `75bd9303030df7bbf876cdb96513dcfa936868b1` |
| Resulting merge commit | `9bc094f0af60d9477ca70d823a6cc3e7ad59f37a` |

Scope: ten upstream commits, including five feature/fix commits. The merge
removes the repository scan-status column, repairs SDLC step ordering after
delete/add operations, advances the recommended runtime to `0.2.115`, adopts
the official HTTP clarification compatibility history, and renders streamed
and persisted clarification replies as Markdown.

Three textual conflicts were resolved:

- `ExecutorListPage.test.tsx`: retained the community Qoder CLI-only contract
  while updating all runtime command expectations to `0.2.115`;
- `WorkitemClarificationPanel.test.tsx`: retained the community HTTP/error and
  `IN`/`INBOUND` compatibility coverage and added the master Markdown test;
- `application.yml`: retained community SecretCrypto and external runtime
  configuration while accepting the `0.2.115` default.

Automatic overlaps in branding, IM tests, and clarification components were
reviewed. Master product behavior was retained, public OSS/SLS and SecretCrypto
boundaries were unchanged, excluded development documents were not restored,
and no product decision was required. The HTTP compatibility patch had already
been cherry-picked to community; the merge now records its official master
ancestry without changing that behavior.

Verification completed after the merge:

- Backend: 1,717 tests passed; production JAR built.
- Frontend: 83 test files and 507 tests passed; lint completed with zero errors
  and two existing hook warnings; production build transformed 4,759 modules.
- Deployment Skill: 45 contract tests passed.
- Maven dependency tree and active runtime inputs contained no prohibited
  internal dependency or domain; community executor creation remained Qoder
  CLI-only and the runtime default was verified as `0.2.115`.
- The first standalone frontend invocation used Maven's x64 optional Rollup
  package on an arm64 shell and was discarded; the required host-npm reinstall
  and serial rerun passed.

### 2026-08-05: `454017b4` to `a4e9ec9e`

| Field | Commit |
| --- | --- |
| Previous synchronized baseline | `454017b42b961b730fb65964ebb24ff88f36543e` |
| Community before merge | `0a79deb016c458561d5ee55f56eed904293d823d` |
| Merged `origin/master` | `a4e9ec9e2e8a1adebf1154ea89ef6af88b84278f` |
| Resulting merge commit | `fa2dbe23c69d4649c9d6345afd5325ec4b9cd190` |

Scope: five master-side commits. The merge stops the clarification spinner as
soon as an agent reply is persisted and adds multiline input behavior to both AI
conversation panels: Enter sends, Shift+Enter inserts a newline, and IME
composition does not submit prematurely.

The four upstream frontend files had no community-side change after the previous
baseline, so master was accepted unchanged. There was no textual conflict,
documentation-policy input, internal dependency change, executor UI change, or
product decision.

Verification completed after the merge:

- Backend: 1,713 tests passed; production JAR built.
- Frontend: 82 test files and 502 tests passed; lint completed with zero errors
  and two existing hook warnings; production build transformed 4,759 modules.
- Deployment Skill: 44 contract tests passed.
- Maven dependency tree and active runtime inputs contained no prohibited
  internal dependency or domain; excluded documents remained absent and the
  community executor UI remained Qoder CLI-only.

### 2026-08-04: `9f984a89` to `454017b4`

| Field | Commit |
| --- | --- |
| Previous synchronized baseline | `9f984a8971bdbe73b25e56922d3f716758b5dca3` |
| Community before merge | `d20d8d91c03742cc4f222d91ff8ea4cf0b6a9755` |
| Merged `origin/master` | `454017b42b961b730fb65964ebb24ff88f36543e` |
| Resulting merge commit | `fed2ea650c8d27dc938042a6928fcb4306d90222` |

Scope: one feature commit plus its master merge commit. The clarification event
view now stops its loading indicator as soon as reply text starts streaming, with
two focused frontend tests.

The merge had no textual conflict, community-only file overlap, configuration
change, internal dependency, or documentation-policy input. Master behavior was
accepted unchanged and no product decision was required.

Verification completed after the merge:

- Backend: 1,713 tests passed; production JAR built.
- Frontend: 82 test files and 496 tests passed; lint completed with zero errors
  and two hook warnings; production build transformed 4,759 modules.
- Deployment Skill: 38 contract tests passed.
- Maven dependency tree and active runtime inputs contained no prohibited
  internal dependency or domain; excluded documents remained absent and the
  community executor UI remained Qoder CLI-only.

### 2026-08-04: `7f30bcf8` to `9f984a89`

| Field | Commit |
| --- | --- |
| Previous synchronized baseline | `7f30bcf858ae2eebe698dc45b3c4404316e62d2e` |
| Community before merge | `de8accc7f45d25c24190addc3867be6128e03342` |
| Merged `origin/master` | `9f984a8971bdbe73b25e56922d3f716758b5dca3` |
| Resulting merge commit | `acb5f6a12b18c8efd4e5100471f295997f246a2c` |

Scope: 34 master-side commits. The merge brought in clarification streaming,
reply-direction and loading fixes; inline memory-card review and shared review
actions; tenant-switch query-cache clearing; administrator approval permissions;
runtime command version pinning; and agent identity/draft-guidance updates.

Four textual conflicts and the related automatic overlaps were reviewed:

- `AppLayout.test.tsx`: retained the community asynchronous route-query removal
  assertion and the master exact work-item, detail, and timeline cache assertions.
- `ExecutorListPage.tsx` and its test: retained the community Qoder CLI-only UI
  while accepting master runtime-version pinning (`0.2.114`).
- `application.yml`: retained community external configuration for OSS, public
  base URL, SecretCrypto, SLS, optional Aone, and SIGAR; added the master
  recommended-runtime-version setting.
- Branding service and tests: retained community OSS bucket resolution and no
  default internal domain while exposing and validating master runtime version.
- Two upstream `docs/superpowers` design records were excluded by documentation
  policy.

No product decision was required. Verification completed after the merge:

- Backend: 1,713 tests passed; production JAR built.
- Frontend: 81 test files and 494 tests passed; lint completed with zero errors
  and two hook warnings; production build transformed 4,759 modules.
- Deployment Skill: 38 contract tests passed.
- Maven dependency tree: no KeyCenter, Normandy, Akless, RASS, or legacy Log4j
  dependency was found.
- Active build/runtime inputs: no Alibaba-internal domain reference was found;
  excluded development documents were absent; Qoder CLI remains the only
  executor exposed by the community frontend.

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
