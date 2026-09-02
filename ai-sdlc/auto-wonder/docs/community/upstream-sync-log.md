# Community Upstream Sync Log

## Purpose

This file records the exact verified upstream baseline and sync history for the
long-lived `community` branch. Follow the constraints and procedure in the
[upstream sync guide](upstream-sync-guide.md) for every sync.

## Current Baseline

- Synchronized `origin/master`: `25371cb104ac019fb26674f0c495c410c01e5041`
- Community merge commit: `6db0fad6906e6892258f9ebf4ba8cc0760617f10`
- Synchronized at: 2026-09-02 (Asia/Shanghai)

## History

### 2026-09-02: `985998be` to `25371cb1`

| Field | Commit |
| --- | --- |
| Previous synchronized baseline | `985998be3c803be1973d0bebc16d009e7a46b122` |
| Community before merge | `51ff353dee2e5fcfd05e1e186cbeb6e03a5265b0` |
| Merged `origin/master` | `25371cb104ac019fb26674f0c495c410c01e5041` |
| Resulting merge commit | `6db0fad6906e6892258f9ebf4ba8cc0760617f10` |
| Community adaptation commit | `7d97b4007` |
| Scheduler test fix commit | `0e76ae22f` |

Released as v0.7.0; see [releases/release_v0.7.0_20260902.md](../../releases/release_v0.7.0_20260902.md).

Scope: 405 upstream commits (312 non-merge), 477 upstream changed paths, 463
final changed paths against the previous community tip. Major features: 7×24
scheduled digital-worker tasks with source-aware execution, workspace discovery
with access requests, encrypted-at-rest MCP configuration, path-token MCP
endpoints, requirement-clarification rework, per-step token/credit usage, and
work-item scheduled start with tags. Recommended executor runtime advances to
`0.2.150`.

Twenty-nine textual and modify/delete conflicts were resolved across
`pom.xml`, `application.yml`, `application-local.yml`, `log4j2.xml`,
`docs/autowonder-schema.sql`, `docs/openapi-reference.md`,
`frontend/package.json`, `frontend/src/shared/api/client.ts`,
`frontend/src/test/mocks/handlers.ts`, four Java test classes, the eight new
migration files, and eight delete-side conflicts. All automatically merged
shared files were reviewed.

Conflict decisions: `client.ts` accepted master verbatim; `openapi-reference.md`
accepted master because master had caught up on the workspace rename, then the
`{aone-host}` placeholder was restored; `frontend/package.json` kept the
community public toolchain and dropped the `allowScripts` block;
`application-local.yml` kept the community env-driven profile rather than
master's copy, which carries live internal credentials.

Community adaptations:

- BUC excluded entirely — the two pom SDK dependencies, the `spring.buc` block,
  the BUC `AsyncLogger` entries, and the `disable-pandora-buc-sso-client` marker
  are all absent. Internal environment profiles remain excluded.
- Master's new `KeyCenterClient` abstraction, introduced for the encrypted MCP
  configuration feature, is bound to community's existing `SecretCrypto`
  contract in `WsDispatchTransport`, `SkillService`,
  `SkillConnectionTestService` and `ConversationCapabilityService`.
  `PlatformKeyCenterClient` and its test are not published because they bind the
  KeyCenter SDK; the `KeyCenterClient` interface and the `InMemoryKeyCenterClient`
  test double are also absent, because community removed that abstraction in
  favour of `SecretCrypto` before this sync. Two Javadoc comments that still said
  "KeyCenter" were reworded so `CommunityDependencyBoundaryTest` passes.
- `com.alibaba.fastjson2:fastjson2:2.0.58` is declared explicitly. The
  scheduled-task code imports it and master resolves it transitively through
  internal SDKs that community removes; without the explicit declaration the
  community build does not compile.
- `autowonder.community-edition` defaults to `true` here.
- Upstream migrations renumbered V041–V048; test and runbook references
  retargeted to `docs/migration/`.
- `ScheduledTaskWorkspaceVocabularyTest` no longer asserts against the design and
  plan documents under `docs/superpowers/`, which the documentation policy
  excludes. Its production-source assertions are unchanged.
- `ImNotificationMessageContextResolverTest` does not carry master's new
  seeded-`DEFAULT_DOMAIN` fallback case; community removed that internal domain
  constant, so the case does not exist here. The blank-domain fallback remains
  covered.
- `docs/autowonder-community-templates.sql` now points at `docs/migration/`, and
  `docs/scheduled-task-operations.md` records that "V037" is the rollout codename
  while the community migration file is `V041__scheduled_task.sql`.
- The new `src/test/resources/schema/autowonder-pre-v037.sql` fixture seeds a
  `NULL` branding domain to match `docs/autowonder-schema.sql`.

Documentation policy: `docs/runtime-usage.md` and
`docs/scheduled-task-operations.md` were added to the retained list. The six new
`docs/superpowers/` plans and specs remain excluded.

Deployment review: the recommended runtime version is the only deployment
contract change. It was propagated to the deployment manifest template and to
the deployment and upgrade Skill tests per Rule 12, and both Skills' version
assertions were re-run. No topology, credential, port, endpoint, database or
environment-template change is required. Deployment input collection needs no new
prompt: a fresh install is ready for the enabled scheduled-task defaults, and the
upgrade override requirement is documented in the release notes and the
scheduled-task runbook.

Configuration-key review: the upstream `application.yml` delta adds
`autowonder.community-edition` and the six `autowonder.scheduled-task.*` keys,
and drops nothing. `ScheduledTaskProperties` binds all six with matching
defaults. All seven new environment variables were added to
`docs/community/application.env.example`. This sync initially also re-added
`AUTOWONDER_AONE_WEB_BASE_URL` there, which reversed the deliberate removal in
`13bb8a9c1`; it has been removed again, and the sync guide now records that
Aone-specific keys beyond `AUTOWONDER_AONE_ENABLED` must never appear in the
environment inventory. External community users have no Aone instance and the key
is optional with an empty default, so its absence is an intentional permanent
omission, not a missing key. Intentional community differences:
`community-edition` defaults to `true`, and `scheduled-task.enabled`,
`scheduled-task.scanner-enabled` and
`scheduled-task.cluster-ready-attestation` default to `true` where upstream
defaults them to `false`, so a fresh community install has 7×24 tasks working on
first boot. That is safe for a fresh install because it imports the complete
`docs/autowonder-schema.sql`; an upgrade of an existing deployment must set all
three to `false` first, which is now the documented requirement in the release
notes, `application.env.example`, `application.yml` and
`docs/scheduled-task-operations.md`. `spring.buc.enabled` and the Aone
web-base-url key are the intentionally omitted upstream keys.

Distribution defect found and fixed: the 25 Skill shell scripts were tracked as
mode `100644` since the Skills were consolidated under `skills/`, leaving them
unrunnable after a fresh clone. Restoring the executable bit also fixed 21 of the
52 deployment-Skill test failures. Compiled Python bytecode under
`__pycache__` was untracked and is now ignored.

Independent review: ancestry proved; all 477 upstream paths accounted for with
62 community differences, every one mapped to a documented boundary and none
unexplained; conflict resolutions and automatically merged shared files checked
for lost master behavior with none found; migrations byte-identical to their
master counterparts and V036–V040 untouched; configuration keys complete; Rule
12 consistent; documentation policy clean; and zero newly introduced internal
references. No critical or important technical finding. Four minor findings were
addressed in this sync.

Verification:

- Maven `clean verify`: BUILD SUCCESS, 2652 tests, 0 failures, 0 errors, 1
  skipped.
- Frontend: 116 files, 901 tests passed, 1 skipped; lint 0 errors with 3
  pre-existing hook warnings; production build succeeded.
- Deployment Skill: 70 passed, 31 failed. Upgrade Skill: 66 passed, 6 failed, 1
  skipped. Both failure sets were measured on the previous community tip
  `51ff353d` and are byte-for-byte identical there, so this sync changed
  neither. They are stale assertions against scripts whose content moved into
  `scripts/internal/release-transfer.sh`, and they remain open work.
- Dependency tree contains no KeyCenter, Normandy, AkLess, RASS, BUC or
  `log4j:log4j` artifact. The verification.md internal-reference scan over
  `pom.xml`, `frontend/package-lock.json`, `frontend/.npmrc`, `APP-META`,
  `src/main` and `src/main/resources` returns nothing.
- Migration immutability, ancestry and schema parity all pass.
- Two audit nuances. First, Rule 12's named assertion
  `test_script_contracts.py::test_runtime_config_replaces_stale_recommended_runtime_version`
  is itself one of the pre-existing deployment-Skill failures, so the five
  runtime-version sources of truth were confirmed by hand at `0.2.150` but are
  not currently guarded by a passing test; fixing that suite would restore
  enforcement. Second, `docs/community/verification.md` still carried
  2026-08-04 evidence (1672 backend tests) and was refreshed to this release's
  measurements, with the Linux image rows marked as not re-run.

Upstream defects found while running the gates. Five frontend assertions and one
backend suite were already failing on `origin/master` and were reproduced there
on a pristine checkout before being fixed here:

- `Sidebar.test.tsx` still expected `/scheduled-tasks` under `workers-group`
  labelled "7×24 任务" after master's `e0e5c0d77` moved it to the delivery group
  as "定时任务".
- `ScheduledTaskCreatePage.test.tsx` asserted a raw ISO instant after master's
  `f8f7c667a` changed the cron preview to `toLocaleString('zh-CN')`.
- `WorkitemCreatePage.test.tsx` typed the literal `2026-09-01 10:00:00`, which
  has since become the past and is rejected by the picker's `disabledDate`. The
  instant is now derived from the current clock.
- `ScheduledTaskSpringMybatisIntegrationTest` seeded `next_fire_at` at absolute
  August instants while letting `gmt_create` default to `CURRENT_TIMESTAMP(3)`.
  `ScheduledTaskScheduler.dueOccurrences` treats the creation time as the
  earliest valid occurrence, so once the wall clock passed the seeded fire times
  every occurrence was filtered out and `scan()` claimed nothing. Seeding
  `gmt_create` at `2025-12-31` restores all 25 tests. This is why the suite
  passed on master's CI in August and fails on any run after the seeded dates.

Two further frontend failures were community-specific: `McpTokenSettingsPanel`
expected the internal hostname while the community mock serves
`community.example`.

`RepoConnectionTesterTest` failed three assertions during one full-suite run but
passes in isolation on both this tree and `51ff353d`; it is load-sensitive rather
than a regression.

Decisions requiring confirmation: none outstanding. The repository owner decided
to keep all three upstream Aone-touching commits (`c54701ce6`, `4aae1d47f`,
`f3e8a23fa`) rather than apply Rule 4's exclusion of Aone iterations. Two are
defect fixes confined to `AoneInboundSyncService`, which community already ships
as an optional integration disabled by default; the third only adds the generic
status word `FIXED` to shared work-item completion classification.

### 2026-08-24 (incremental): `d77e29bf` to `985998be`

| Field | Commit |
| --- | --- |
| Previous synchronized baseline | `d77e29bfeee53e4176c0a051055280ec83f56743` |
| Community before merge | `762cc25c4` (docs-policy update for docs/skills/) |
| Merged `origin/master` | `985998be3c803be1973d0bebc16d009e7a46b122` |
| Resulting merge commit | `dd8b5c6a5e1991a67a94de58b83d20afcae925e3` |

Scope: 11 upstream commits, 20 changed paths. Adds per-step and workitem-level
duration display in delivery progress UI, and syncs aw-execution-optimizer-skill
Phase 6 quality verification and evidence-standards updates.

Policy change: `docs/skills/` added to retained documents list. All 22 skill
files restored from master. No conflicts beyond the expected modify/delete on
previously excluded skill files — resolved by accepting master's version.

No DDL, no configuration key change, no deployment impact. Backend and frontend
changes are pure product features with no community boundary crossing.


### 2026-08-24: `d47d1721` to `d77e29bf`

| Field | Commit |
| --- | --- |
| Previous synchronized baseline | `d47d172129e4edaef4509148e6d5321200bc5e7d` |
| Community before merge | `5e37e83c78e9221e38500d67b35a9c131dba9fc1` |
| Merged `origin/master` | `d77e29bfeee53e4176c0a051055280ec83f56743` |
| Resulting merge commit | `1cf50e3f8555fabf509dd18a51ef677f21c5b99c` |

Scope: 169 upstream commits and 411 final changed paths. Major changes: org →
workspace API rename (`/api/orgs` → `/api/workspaces`), external workitem
collaboration with principal identity and reconciliation, integration outbox
receipt model, executor debug commands, Qoder CLI CN, workitem visual context
attachments, assign-to-human, timeline operator display, kanban per-column
query, memory scope grouping, Jedis 3.10.0, runtime 0.2.138.

25 textual conflicts resolved across pom.xml, application.yml, frontend
lockfile, 5 integration service/test files, frontend executor page/test,
integration page test, branding service, IM notification resolver, access
schema test, and outbox DAO/XML. All automatically merged shared files reviewed.

Community adaptations: migrations renumbered V038–V040 (V039 branding reset
excluded as no-op); AoneWorkitemMapper web-base-url made configurable;
AoneOperationReadbackHandler converted to SecretCrypto; testing1 env excluded;
design/assets/skills docs excluded per policy; openapi-reference corrected for
workspace rename; deployment Skill runtime bumped to 0.2.138.

Deployment review: runtime version is the only deployment contract change.
Manifest, input catalog, and tests updated. No topology, credential, port,
endpoint, database, or environment-template change. AUTOWONDER_AONE_WEB_BASE_URL
added as optional empty-default key.

Independent review: ancestry verified, all 411 upstream paths accounted for,
community differences limited to documented boundaries (SecretCrypto,
resolveArtifactBucket, configurable Aone web-base-url, public Node/npm
toolchain, optional Aone, Qoder-only executor creation, excluded internal env,
docs-policy, and the openapi-reference doc correction).

Verification:
- Maven clean verify: 2049 tests passed, 0 failures.
- Frontend: 662 tests, 0 errors, 1 skipped (new external collaboration
  rendering test, Antd timing); lint 0 errors.
- Deployment Skill: 85 tests passed.
- Internal reference scan, migration immutability, ancestry, dependency boundary:
  all passed.


### 2026-08-11: `d5e36283` to `d47d1721`

| Field | Commit |
| --- | --- |
| Previous synchronized baseline | `d5e36283e513c86a92241c50bb84bd129bf02f20` |
| Community before merge | `de5913a1944a8d3bb7255f66f6c7cb303ca7f22b` |
| Merged `origin/master` | `d47d172129e4edaef4509148e6d5321200bc5e7d` |
| Resulting merge commit | `6f14874601a30c5c9db12433c4efe852c29912ca` |

Scope: 29 upstream commits and 44 final changed paths. This sync adds visible
human-intervention markers to work-item views, published-workitem workspace
cleanup with three-day retention, Unicode artifact paths, detailed SDLC deletion
reference errors, assignment fallback to the work-item type's default SDLC, and
clearer memory MCP arguments. The recommended executor runtime advances to
`0.2.130`.

Two textual conflicts and seven automatically overlapping files were reviewed.
Executor tests retain Community's Qoder-only creation boundary while accepting
the runtime update. `application.yml` retains external SecretCrypto, OSS/S3/SLS,
optional Aone, SIGAR, and public-base configuration while accepting `0.2.130`.
Branding, artifact, IM, and notification overlaps retain external domain, bucket,
and encryption adapters without dropping upstream behavior. The clarification
detail test remains aligned with the enabled Community production flag. Review
also found and fixed two no-op regex escapes in the new human-intervention badge
that caused the upstream frontend lint gate to fail.

Deployment review found no topology, credential, port, endpoint, database, or
environment-template change. The runtime version is a deployment contract, so
the Skill manifest, input catalog, and tests were updated from `0.2.125` to
`0.2.130`. No DDL changed and no new file is required under `docs/migration/`.
The configuration-key audit found no other added, removed, renamed, or
default-changed application key requiring a Community adaptation.

Independent review proved the exact master baseline is an ancestor of Community
and accounted for every changed path. Of 44 upstream paths, 34 match master
exactly; the other ten are the nine documented Community deployment, Qoder-only,
SecretCrypto, storage, domain, and test-fixture boundaries plus the lint fix.
No lost feature, unexplained configuration difference, internal dependency,
excluded documentation, migration omission, or unintended product divergence
was found.

Verification completed after the merge:

- Maven `clean verify` packaged the production frontend and passed 1,871 backend
  tests.
- Frontend passed 90 test files and 586 tests; lint completed with zero errors
  and two existing hook warnings. The badge regression suite passed all 26 tests
  after the lint fix.
- Deployment Skill passed all 85 tests.
- Dependency-tree, internal-reference, migration, shell-syntax, ancestry, and
  whitespace checks passed.

### 2026-08-09: `3262a46f` to `d5e36283`

| Field | Commit |
| --- | --- |
| Previous synchronized baseline | `3262a46f66ac5087261429250091bfea5a61d12b` |
| Community before merge | `204ce5382e7c677e464e5bc799c02e31d6f9ac5b` |
| Merged `origin/master` | `d5e36283e513c86a92241c50bb84bd129bf02f20` |
| Resulting merge commit | `0c03717b14fb955d67a8a01ea066289d777848d0` |

Scope: 20 upstream commits. The merge adds optional standard S3-compatible
object storage, deployment-version display on the About page, Markdown/plain
text copy menus for work-item content and comments, and records the local-log
retention fix in master ancestry.

Six textual conflicts and all automatically overlapping files were reviewed:

- the fresh schema retains Community SecretCrypto and domain-neutral definitions
  while accepting master's V036/V037 schema alignment;
- branding service, API mocks, and tests retain a deployment-derived domain and
  Community bucket resolution while accepting the new deployment-version field;
- object-storage configuration retains mandatory validated persistence and no
  in-memory fallback, adds mutually exclusive S3 wiring, and keeps OSS enabled by
  default for Community deployments;
- `application.yml` retains external SecretCrypto, OSS/SLS, optional Aone, SIGAR,
  and public-base configuration while adding `autowonder.version`;
- `pom.xml` retains the public Node/SIGAR toolchain and removes all internal
  dependencies while accepting the public AWS SDK v2 S3 dependencies;
- Log4j2 retains Community environment-driven SLS configuration and the master
  local archive retention policy unchanged.

Documentation policy excluded the two upstream `docs/superpowers` working notes.
The S3 operator guide was retained as supported-extension documentation, removed
its internal KeyCenter reference, and clarifies that the Alibaba Cloud deployment
Skill remains OSS-only. No new migration is required: the target schema changes
are already represented by immutable Community V036 and V037, which were not
modified.

Deployment review found one required adaptation. `build-release.sh` now seals the
repository `VERSION` into `releaseVersion`, and `runtime-config` writes it as
`AUTOWONDER_VERSION` before startup so the About page reports the deployed
release. The upgrade planner recognizes this and the other values generated by
`runtime-config` as deployment-managed, so a pre-generation candidate environment
file does not incorrectly block the upgrade; the new keys remain visible in the
plan. The environment example and runbook were updated. S3 settings remain optional
and are not added to the OSS-only Alibaba Cloud deployment contract; Terraform and
resource topology are unchanged.

Independent review proved `d5e36283e513c86a92241c50bb84bd129bf02f20` is an
ancestor of Community, accounted for every upstream changed file and conflict,
and found no lost master feature, unintended internal dependency, or Qoder-only
executor regression. S3 implementation files and all work-item copy production
files match master; documented differences are limited to Community deployment,
storage-safety, SecretCrypto, external-domain, and test-isolation boundaries.

A follow-up configuration-key audit found that the initial conflict resolution
had omitted master's `s3:` block from `application.yml`, despite correctly merging
`S3Properties` and the implementation. Commit
`aa4dec9e2a2870b6ccde420b76814efb92b15fbf` restores every S3 key, keeps S3 disabled
by default, exposes only the enable switch and connection values as environment
bindings, and fixes upgrade planning so disabled S3 does not impose credentials
on OSS deployments while enabled S3 still requires its endpoint and AK/SK. This
finding supersedes the initial no-omission conclusion above.

The corrective re-review covered all 20 commits and 35 changed paths: 19 paths
now match master exactly; the remaining 16 were individually inspected and are
limited to the documented Community boundaries for excluded `docs/superpowers`
records, SecretCrypto/schema text, external branding/domain fixtures, public
build dependencies, mandatory persistent storage, external SLS configuration,
the adapted S3 guide, and their tests. No second unexplained feature or
configuration deviation was found. The sync guide now requires a per-key review
of configuration files, properties classes, environment templates, deployment
scripts, and upgrade planning.

Verification completed after the merge:

- Maven `clean verify` packaged production frontend assets; the corrective
  backend suite passed all 1,855 tests after adding the S3 configuration contract.
- Frontend: 89 test files and 547 tests passed; lint completed with zero errors
  and two existing hook warnings; the production build completed in Maven.
- Deployment Skill: 84 tests passed.
- Dependency tree and active runtime inputs contain no prohibited internal
  dependency or domain; excluded documents are absent.
- The first standalone frontend attempt was discarded because Maven's cached x64
  Node omitted the arm64 Rollup optional binary; a clean locked install with the
  host arm64 Node produced the passing result above.

### 2026-08-09: selective local-log retention fix

| Field | Commit |
| --- | --- |
| Full synchronized master baseline (unchanged) | `3262a46f66ac5087261429250091bfea5a61d12b` |
| Community before selective fix | `a6c94b9674193c65ba09314bebc06cbfb1bcfe6d` |
| Reviewed master head / fix parent | `b1916d4b5732854278b34da14bc03e7379ac8ad6` |
| Internal fix commit | `73dec919b86bbf07a91f8886e6b817d35dafbfae` |
| Community cherry-pick commit | `d0416ecbc041b476faedf6e2d28718cfcddad2b5` |

Scope: a selective operational safety fix adds daily rolling and a Log4j2
`Delete` action to the existing 50 MB local file rollover. Only matching
`auto-wonder-*.log.gz` archives are considered; archives older than 14 days or
outside the newest cumulative 5 GB are deleted on rollover. The active log,
unrelated files, nested paths, and link targets are excluded.

The Community SLS appender and all external configuration remain unchanged. The
deployment QA now documents application-file retention and keeps systemd journal
retention as a separate host responsibility. No environment variable, database,
migration, infrastructure, executor, frontend, or internal-dependency boundary
changed, so no deployment Skill flow or environment template update is needed.

This is not a complete merge of commits after `3262a46f`; the Current Baseline
therefore remains unchanged. Review compared the selected implementation and
test with the internal fix byte-for-byte and confirmed that no master-only
design/plan document entered the Community output.

Verification completed after the selective sync:

- Backend: 1,829 tests passed.
- Deployment Skill: 80 tests passed.
- Log4j2 emitted no plugin-resolution error for the new rollover components.
- Deployment and environment contracts were unchanged; the focused LocalFile
  implementation and regression test match the internal fix.

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
