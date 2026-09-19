# Community Upstream Sync Log

## Purpose

This file records the exact verified upstream baseline and sync history for the
long-lived `community` branch. Follow the constraints and procedure in the
[upstream sync guide](upstream-sync-guide.md) for every sync.

## Current Baseline

- Synchronized `origin/master`: `25371cb104ac019fb26674f0c495c410c01e5041`
- Community merge commit: `6db0fad6906e6892258f9ebf4ba8cc0760617f10`
- Synchronized at: 2026-09-02 (Asia/Shanghai)

**Historical checkpoint (2026-09-07).** A newer sync — merged `origin/master`
`b52cdeeea3b82316ee370e56d5533e6a8d9245b3`, merge commit
`616b84687e15702cbc42ac677021499352037e8f`, released as v0.8.0 — is recorded in
History below, but it is **not** the baseline. This guide requires "Never move
the recorded baseline until the merge and required verification have completed
and the independent sync review has passed" and "The last verified baseline is
the exclusive starting point for the next sync" — **two** conditions, and both
must hold. Their status as of SDLC step `400360`: the independent sync review
**has passed** (findings and disposition are recorded in the 2026-09-07 entry
below), but the merge **has not landed** —
`refs/remotes/origin/community` is still
`c3a75ae60462e3bdc93d1e9fb82041dc9d442f51`, because `616b84687` sits on the sync
branch `aw/community-sync-b52cdeeea-20260906` awaiting the human MR gate
(step `400361`) and `community` is protected. The three bullets above therefore
were kept authoritative pending that human merge; moving them then would advertise
a synchronized baseline that `community` does not contain. Ancestry of the
candidate was proved, not assumed: `25371cb1` is an ancestor of the community tip
`c3a75ae6`, and both `c3a75ae6` and `b52cdeeea` are ancestors of `616b84687`.

## History

### 2026-09-19: candidate sync to `d795eea3a` — verified iteration and review follow-up

- Fixed upstream source: `d795eea3a8261a0fe22c2d2c8572d774699f3386`.
- Community before sync: `b05c04c4a4c8eae7d50a7afeeddd1921e21aea17`.
- Resumed Community baseline: `8ecab515c9cfdd4fca7323e5af7aa1ce7db511e1`.
  The E2E submit/approve prerequisite was independently reviewed, verified and
  merged into Community before this sync resumed. This sync preserves its E2E
  tree exactly; it does not import master E2E files.
- Subsequent Community target: `da378e982e548169c0894039086c1540aeae2a8c`.
  Seven concurrent Community commits (51 paths) improve deployment inventory,
  sanitized diagnostics, public-IP detection, administrator initialization and
  cloud packaging/health scope. They were integrated without textual conflicts
  in `ac723b9b836560e2e505284e62df3465b5ade9ff`; the Community E2E tree is unchanged.
- Original master merge: `ef85bff30f2d4aa4e51ea4f91c254a8c23f347c6`;
  prerequisite Community merge: `9bc3e212ebdd2b7e06de6bf46943324ebc38cc73`.
- Recorded verified baseline remains `25371cb104ac019fb26674f0c495c410c01e5041`;
  the already incorporated master ancestor is `b52cdeeea3b82316ee370e56d5533e6a8d9245b3`.
- Sync branch: `aw/community-sync-d795eea3a-20260919`.
- Scope from the recorded baseline: 869 commits / 1,144 changed paths.
- Candidate version: 0.8.0 → 0.9.0 (MINOR), documented in
  [release_v0.9.0_20260919.md](../../releases/release_v0.9.0_20260919.md).
- Includes workspace environment variables, Feishu, platform conversations,
  project backups, executor upgrades, capability categories and dispatch recovery.
- Community adaptations preserve SecretCrypto and public storage, exclude BUC
  and standalone new Aone functionality, and retain the complete pre-sync E2E
  tree unchanged. Existing optional Aone remains disabled by default.
- New migrations are V052–V070; published V036–V051 remain byte-identical.
  Admin initialization requires an existing administrator and never grants one.
- Measured iteration: clean `ef84938f2ddb05556fa245448c2e76048d335546`.
  Maven 4,654 tests, zero failures/errors, one manual Aone skip; frontend
  196 files/2,122 tests pass; lint zero errors/three warnings; production build
  passes. Deploy 366 tests + 245 subtests pass (10 native Windows skips);
  upgrade 159 tests + 88 subtests pass (12 native Windows skips). Production
  npm audit is clear; five unchanged development-dependency findings remain.
  Dependency/internal endpoint and strict YAML/runtime configuration checks pass.
- E2E run `aw-e2e-ef84938f2d-e9e54d32`, image mode, dirty=false: image/startup
  and fresh schema (84 tables) pass; 14 environment-variable/binding/backup API
  checks pass; authenticated chain 39 PASS/0 FAIL. Ports: 7001, 39060, 63790,
  39260, 39360. Two ERROR and six WARN records are attributed negative probes;
  unattributed ERROR/WARN are zero. Standard final check exits zero.
  Cleanup initially encountered a foreign task reusing 7001; after its owner
  stopped it, standard cleanup exited zero with no containers, volumes, network
  or listeners. Archive: `target/e2e-results/aw-e2e-ef84938f2d-e9e54d32/`;
  external cycle-4 cleanup-resume evidence records the later successful teardown
  separately from the raw archived earlier failure snapshot.
- Independent iteration reviews: master product/data semantics PASS; release
  evidence PASS; Community boundary found one Important issue in the public
  tutorial ZIP's AppleDouble metadata. Repacked the archive with only the
  original Skill payload (unchanged SHA-256), and a real archive regression
  failed before the repair and passed after it. No product semantics changed.
- Configuration review covers the union of YAML placeholders and the env example;
  optional Java-only controls gained bindings without default changes. Intentional
  Aone omissions follow rule 4. Deployment/upgrade retain source-derived runtime
  0.2.163, escrow identity/environment hashes and existing-admin migration checks;
  concurrent cloud packaging changes do not weaken local release quality gates.
- The target integration, metadata repair and this post-review log commit require
  a new exact-SHA quality cycle. The measured results above are explicitly scoped
  to ef849 and are not substituted for that cycle. Its final manifest, independent
  reports and remote identity belong to the MR evidence, outside the source tree.
  Real cloud services, native Windows and populated production upgrades are not
  claimed as executed. No unresolved product/data decision is deferred to CR.
- No internal MR or GitHub PR has been created. Public output requires the
  internal human merge gate. No tag is authorized. The verified baseline above
  is deliberately unchanged until the required verification and merge complete.

### 2026-09-07: `25371cb1` to `b52cdeeea` — **baseline not moved yet**

| Field | Commit |
| --- | --- |
| Previous synchronized baseline | `25371cb104ac019fb26674f0c495c410c01e5041` |
| Community before merge | `c3a75ae60462e3bdc93d1e9fb82041dc9d442f51` |
| Merged `origin/master` | `b52cdeeea3b82316ee370e56d5533e6a8d9245b3` |
| Resulting merge commit | `616b84687e15702cbc42ac677021499352037e8f` |
| Community adaptation commit | `2813b524f7d8aa8c8919ef7ab72b03c7769aa032` |
| Sync-log commit | this commit |
| Sync branch | `aw/community-sync-b52cdeeea-20260906` (pushed; remote ref == `616b84687` before the two commits above, re-pushed after) |

Released as v0.8.0; see [releases/release_v0.8.0_20260907.md](../../releases/release_v0.8.0_20260907.md).
`VERSION` moved `0.7.0` → `0.8.0` (**MINOR**: 56 `feat:` commits, three additive
migrations, zero `BREAKING CHANGE` markers in any subject or body, zero `!:`
breaking suffixes, no public REST path removed or renamed). `VERSION` was checked
for the historical gap v0.7.0 had to repair and **none was found** — before this
commit `VERSION` read `0.7.0` and `releases/release_v0.7.0_20260902.md:3`
declares `0.6.0` → `0.7.0`, so the two already agreed.

Scope: 276 upstream commits (225 non-merge, 51 merge), 335 upstream changed
paths, 309 final changed paths against the previous community tip
(+34,004 / −1,872). Major features: platform administrator role, workspace edit
/ soft delete / recycle bin / restore, ACP conversation elicitation, server-side
executor launch-command and launch-options generation with matching MCP tools, a
Qoder provider model catalog refreshed from idle runtimes, work-item CLI download
tokens, work-item credits aggregation, skill-package file browsing, scheduled-task
logical delete, the clarification conversation rework, and executor lists grouped
by digital-worker Agent. Recommended executor runtime advances to `0.2.152`.

Twenty-five conflicts were resolved: 6 took master verbatim, 2 took community,
8 were excluded, 9 were blended. Overlapping files and their decisions:

- **Took master** (Rule 3 — master owns product behavior, UI and tests):
  `frontend/src/app/Sidebar.test.tsx`,
  `frontend/src/features/executor/ExecutorListPage.tsx`,
  `frontend/src/features/scheduledTask/ScheduledTaskCreatePage.test.tsx`,
  `frontend/src/features/skill/SkillListPage.test.tsx`,
  `frontend/src/features/workitem/WorkitemCreatePage.test.tsx`,
  `frontend/src/features/workitem/WorkitemDetailPage.test.tsx`. The last also
  un-skips `renders provider-neutral external collaboration relations and hides
  empty groups`, which now runs and passes.
- **Took community** (2), each verified not to drop master behavior: `Bootstrap.java`
  and `application-local.yml`. Master's window change to both consisted *only* of
  deleting Alibaba-internal code and config (`initAkLess`, the `akless:` block),
  and community had already deleted a superset, so there was no master behavior to
  lose. Carrying master's `application-local.yml` would have committed live-looking
  credentials, which AGENT.md Rule 8 forbids.
- **Excluded** (8): `docs/config-replacement-checklist.md` and the
  `docs/superpowers/` spec (docs-policy, removed by `09e689903`),
  `application-daily.yml` (removed by `65371c7b5`), `application-testing1.yml`
  and `Testing1EnvironmentConfigTest.java` (removed by `e957d1453` — a direct
  precedent from the previous sync), and the three upstream migrations, which were
  **renumbered, not dropped**.
- **Blended** (9): `pom.xml` (master's dependency changes including the explicit
  `fastjson2` declaration, minus KeyCenter / Akless / Normandy / SecuritySDK / BUC
  and the internal Maven repository); `application.yml` (master's new keys
  rewritten to community's environment-variable-driven form, `community-edition`
  stays `true`); `AoneInboundSyncService.java` and its test (master's new
  `NotifyService` plumbing and all four constructor overloads carried over in
  full, 27 `@Test` on master and 27 in the merge, only the `KeyCenterClient`
  parameter type becoming community's `SecretCrypto`); `frontend/package.json`
  (community's newer toolchain pins from `86e3614ff` retained, master's
  `allowScripts` gate adopted for the first time, pinned to `esbuild@0.25.12`);
  `frontend/package-lock.json` (regenerated from the public registry: 35 keys
  added, 36 removed, **0 version changes**, all 36 removals proven to be npm-11
  optional-peerDependency pruning); `ExecutorListPage.test.tsx`,
  `frontend/src/test/mocks/handlers.ts` and `frontend/vite.config.ts`.

**Automatically overlapping files — standing community divergence Git merged
without reporting a conflict (15).** The 25 decisions above are the *textual*
conflicts. A further 15 paths were changed by master inside the window *and*
already diverged on community, so Git auto-merged them and produced no conflict to
decide. The guide still requires each to be recorded, because an auto-merge is a
decision too: it keeps community's adaptation **and** takes master's change.

The overlap set was derived from the merge base rather than from a HEAD-versus-master
diff, which would have mixed in community-only files. `git merge-base b52cdeeea
c3a75ae6` = `25371cb10`; master changed **335** paths and community changed **545**
paths since it; their intersection is **38** files Git had to merge across a standing
divergence. At HEAD those 38 split into **6** whose blob is byte-identical to master
(so the divergence was resolved back to master's own content and needs no decision),
**5** absent at HEAD (`docs/config-replacement-checklist.md` and the
`docs/superpowers/` spec under the docs policy, plus internal-only
`application-daily.yml`, `application-testing1.yml` and
`Testing1EnvironmentConfigTest.java`), and **27** genuinely divergent. The remaining
**32** non-identical files are the ones needing a recorded decision. Matching each
by basename *or* stem against this entry's text, measured both with and without the
classification below: **19** of the 32 were already named by the 25 textual-conflict
decisions and the surrounding prose, and **13** were strictly unnamed. The **15**
classified below cover those 13 plus 2 that were named only indirectly, through the
test file that exercises them rather than in their own right. All 15 were re-derived
by measurement at step `400360`'s final tip (`git diff --numstat b52cdeeea HEAD` per
path, plus a full `-U0` reading of each), and every one is a pre-existing Rule 4
boundary adaptation, not a conflict-resolution artefact. After this entry gained the
classification below, the same match over all 32 gives **`NAMED_IN_ENTRY=32`,
`UNNAMED_IN_ENTRY=0`**. None drops a master behavior:

- **Internal host or bucket literal removed (5).**
  `frontend/src/features/platform/brandingApi.ts` (+1/−1, internal default domain
  → `null`); `src/main/java/.../branding/PlatformBrandingService.java` (internal
  `DEFAULT_DOMAIN` deleted, hardcoded `autowonder-artifact-daily` bucket fallback →
  `ossProperties.resolveArtifactBucket()`, `fallback.setDomain(null)`);
  `src/main/java/.../artifact/RequirementDocumentService.java` (+1/−2, same bucket
  resolver); `src/main/java/.../skill/SkillPackageService.java` (+1/−8, internal
  `autowonder-artifacts-daily` default plus its 8-line fallback chain →
  `props.resolveSkillBucket()`);
  `frontend/src/features/platform/BrandingConfigPage.test.tsx` (+3/−2, internal
  host in two fixtures → `https://community.example`).
- **`KeyCenterClient` → `SecretCrypto` (2).**
  `src/main/java/.../skill/SkillService.java` (+7/−7: import, field, constructor
  parameter, assignment, two exception messages reworded from "KeyCenter
  未配置/未返回" to "密文存储未配置/未返回", and the `encrypt` call site);
  `src/test/java/.../skill/SkillServiceTest.java` (+5/−5, the matching mock).
  Master's assertions and the `kc:v1:` reference literals are unchanged.
- **Internal test-fixture endpoint redacted (1).**
  `frontend/src/shared/ui/MarkdownView.test.tsx` (+4/−4, four `api.aliyun-inc.com`
  fixture URLs → `api.example.com`). This is a GATE-B pattern match, so keeping
  master's literal would have put an internal host into the community tree.
- **Community-only endpoint wired into an existing guard (2).**
  `src/main/java/.../auth/filter/AuthFilter.java` (+4/−1, adds
  `INTEGRATION_CAPABILITIES_PATH = "/api/integrations/capabilities"` to the
  unauthenticated-path allowlist);
  `src/test/java/.../access/WorkspaceAccessAnnotationCoverageTest.java` (+1/−0,
  adds `IntegrationCapabilityController#capabilities()` to the covered set).
  `IntegrationCapabilityController` does not exist on master
  (`git cat-file -e b52cdeeea:…` fails), so this is community-only surface kept
  consistent with community's own coverage test.
- **Community frontend toolchain adaptation (1).**
  `frontend/src/features/skill/skillPackage.ts` (+3/−3, JSZip input changed from
  the raw `File` object to `new Uint8Array(await file.arrayBuffer())` and the loop
  to `await Promise.all(…)`), required by community's newer vitest/JSZip pins and
  the `Blob`/`File` globals in `frontend/src/test/setup.ts`.
- **Migration renumbering referenced from a test (1).**
  `src/test/java/.../scheduledtask/V037CompatibilityMatrixTest.java` (+3/−3,
  `docs/migrations/V037__scheduled_task.sql` → `docs/migration/V041__scheduled_task.sql`
  at three call sites). Direct consequence of Rule 11; the target exists at HEAD
  and the plural `docs/migrations/` directory holds 0 files there.
- **Fixture `gmt_create` pin — label corrected 2026-09-07, not an upstream defect (1).**
  `src/test/java/.../scheduledtask/ScheduledTaskSpringMybatisIntegrationTest.java`
  (+3/−3, two fixture `INSERT`s add an explicit `gmt_create` of `'2025-12-31 00:00:00.000'`).
  The mechanism below is real and was re-verified in product code, but this bullet's
  earlier "Upstream time-bomb fixture fix" heading is stale: master fixed the same
  mechanism independently at **scanner** level in `21e28e0f4` (2026-09-05), so pristine
  `b52cdeeea` passes outright — measured below and in `verification.md:174`.
- **Master's new tests preserved 1:1 (1).**
  `src/test/java/.../integration/AoneInboundSyncServiceTest.java` (+113/−109).
  Measured, not assumed: `@Test` count **27 on master and 27 at HEAD**, the
  extracted test-method name set is **identical** in both directions, `KeyCenter`
  occurrences go **28 → 0** while `SecretCrypto` occurrences go **0 → 28**, i.e. a
  strict 1:1 type substitution with zero test loss.
- **Docs-policy exclusion (1).**
  `docs/superpowers/specs/2026-08-10-always-on-digital-worker-design.md` (−644,
  absent at HEAD). The `docs/superpowers/` category is excluded wholesale by the
  docs policy — HEAD holds 0 such files against master's 170 — and this specific
  spec is the one already named in the *Excluded* decision above.

**Rule 7 measured, not assumed.** Unrelated community-only files were preserved:
`git diff --name-status c3a75ae6 HEAD` reports **0 deletions** of any kind, and of
the **161** paths present at the pre-sync community tip but absent from master,
**0** are missing at HEAD. Community-only surface entered this sync intact.

Three post-merge breakages Git did **not** report as conflicts were found by
compiling and testing rather than by a conflict-marker sweep, and all three were
fixed in test code with master's assertions preserved: `AuthFilterTest.java`
(master added a 5th `WorkspaceDao` constructor argument inside the window while
community's own test still called the 4-arg form), `ExecutorListPage.test.tsx`
(master's new tests hardcoded an internal MCP host at two call sites, redacted to
`https://community.example/api/mcp`, which also removes a fresh
`CommunityDependencyBoundaryTest` violation), and `PlatformBrandingServiceTest.java`
(master's new Rule-12 drift guard loads `application.yml` from the classpath,
where community's deliberate `src/test/resources/application.yml` stub shadows it
under surefire ordering; the helper now reads `src/main/resources/application.yml`
by filesystem path, the pattern community's own boundary test already uses, with
**every assertion, message and return value unchanged**). Deleting the stub or
adding `runtime.recommended-version` to it were both rejected: the second would
have made the guard assert against the stub instead of the shipped config,
silently defeating master's Rule-12 protection.

Community adaptations:

- **Rule 12 runtime-version alignment — the one blocking sync defect this cycle.**
  Master advanced `autowonder.runtime.recommended-version` `0.2.150` → `0.2.152`
  in `8dc6afe2a` and community's `application.yml` took it unchanged, but
  community's own deploy/upgrade Skills — which master does not have at all —
  still pinned `0.2.150` in seven literals. All seven were moved to `0.2.152`:
  `deployment-manifest.json:57`, `test_manifest.py:110`,
  `test_script_contracts.py:561,1190`, `test_upgrade_info.py:41,183,332`.
  `test_script_contracts.py:547` deliberately stays at `0.2.110` because it is
  the stale *input* the replacement logic must overwrite. This is not a new
  divergence: `8db1bd6fe` established the manifest-as-source-of-truth mechanism
  pinned to the then-current release, and `f9ba26785` later bumped the manifest
  alone and left the tests behind — exactly the drift Rule 12 forbids.
  `260b30363`, which removed the manifest key, is **not** an ancestor.
- `SecretCrypto` in place of KeyCenter, unchanged from v0.7.0; zero `KeyCenter`
  occurrences remain.
- Migrations renumbered from upstream `docs/migrations/V045,V046,V047` to
  community `docs/migration/V049,V050,V051`, each byte-identical to its upstream
  counterpart modulo the filename. Community numbering continues from its own tip
  `V048`: contiguous, no gap, no collision.
- Internal build/runtime dependencies excluded per Rule 4: the internal `akless:`
  block, BUC integration, internal SLS `roag.log4j2` and hardcoded internal
  credentials.
- Documentation filtered through `docs-policy.md`: 24 upstream paths not carried,
  each with cited docs-policy authority and a cited deliberate community removal
  commit. The 19 new `docs/superpowers/` documents are excluded development
  history. The retained list is unchanged this cycle.
- **Executor create flow needed no adaptation.** Rule 4 requires it to stay
  Qoder-CLI-only, but master converged to that on its own:
  `ExecutorLaunchOptionsService.CREATABLE_CLIENT_KINDS` is byte-identical between
  master and community, and community's frontend derives the same list via
  `isQoderClientKind`. Under Rule 5 no community difference is preserved where
  master's new implementation is already community-compatible. Legacy
  `CLAUDE_CODE` executors still render and still resolve a provider; they are
  simply not creatable.

Decisions carried to the human gate. Items 1–3 were **ruled by the operator on
2026-09-08** (workitem comment `126197`) and those rulings are executed in this
tree; the remaining items are **still open**:

1. **R5 `V050` DML privilege grant — ACCEPTED BY THE OPERATOR, closed.**
   `UPDATE user SET is_admin = 1 ... LIMIT 1` promotes the system's *first active*
   user to platform administrator on an existing deployment, with no prompt, and
   that role can view and restore every workspace's recycle-bin records. The
   migration is byte-identical to upstream and is not modified by community
   (Rule 3 — master owns schema evolution), so the remedy is an operator data
   action, not a patch. Ruling, verbatim: 「接受 将系统第一个活跃用户升级为平台管理员」.
   This is now a **recorded operator acceptance**, not an open risk. Upgrading
   deployments still need the operator to know the grant happens; it is restated
   in the release file's upgrade section.
2. **R6 `V051` destructive index swap — RULED, keep upstream, closed.**
   `DROP INDEX uk_name` plus `ADD UNIQUE KEY uk_active_name` on a live table;
   rollback is **not** symmetric because re-adding `uk_name` fails if duplicate
   names exist among soft-deleted rows. Ruling, verbatim: 「org 表 没事，保持以
   origin/master 为主。org表不大。」 — `origin/master` stays authoritative and the
   migration ships unmodified. The asymmetric-rollback fact is retained below as a
   measured property of the migration, not as a blocker; a shadow-database dry run
   remains advisable for any deployment whose `org` table is not small.
3. **R7 Aone @-mention wake (`a1b6228bc`) — RULED "strip", EXECUTED 2026-09-08.**
   Ruling, verbatim: 「如果是aone的新功能的话，就剥离，如果是存量功能的话就保留。」
   The condition was resolved by measurement, not by opinion, and it splits:
   - **New Aone functionality → stripped.** The capability "an Aone-imported
     comment wakes an AutoWonder agent" does not exist in pre-sync community
     `c3a75ae6`; `a1b6228bc` is not an ancestor of it. It lives only in the Aone
     inbound path. `AoneInboundSyncService.java` is therefore restored
     **byte-identical to its pre-sync community blob**, removing the
     `GuidanceService` import, field, constructor parameter, the extra
     null-delegating constructor and the `createForComment` call after
     `commentLinkDao.insert(cl)`. Constructor count returns to 3.
   - **Pre-existing functionality → kept.** `GuidanceService.createForComment`
     mention resolution already existed in community as `resolveLeadingMention`
     and has **three non-Aone production callers** —
     `WorkitemController.java:134`, `DaemonCommentController.java:88` and
     `McpToolService.java:1450`. The window's improvements to it
     (`2e2556b8a`, `25bbbb85e`) serve that shared mechanism, so `GuidanceService.java`
     and `GuidanceServiceTest.java` are **untouched**; stripping them would have
     degraded master's product behaviour for the UI, daemon and MCP paths and so
     would have violated Rule 3.
   - The alias-resolution piece originally added by `a1b6228bc`
     (`resolveMentionAlias`, `SystemSettingService`, setting key
     `agent.mention.aliases`) needed no action: master **already reverted it** in
     `1e84fedc0`, so it is absent at this tip.
   - `AoneInboundSyncServiceTest.java` is likewise restored byte-identical to
     pre-sync community, which deletes `refreshIssueIdsCreatesGuidanceForNewInboundMention`.
     That deletion is **removing the test of a removed capability**, not lowering
     an assertion to go green: the test's only substantive assertion is
     `verify(guidanceService).createForComment(...)`, which cannot hold once the
     call site is gone. Total backend test count moves 3171 → 3170 by exactly this
     one method.
   - **Observable effect on a compliant community deployment: none.** Aone is
     gated by `@ConditionalOnProperty(prefix = "autowonder.integration.aone",
     name = "enabled", havingValue = "true", matchIfMissing = false)` on both
     `AoneInboundPoller` and `AoneIntegrationController`, defaults to
     `AUTOWONDER_AONE_ENABLED=false`, and both deployment Skills hard-fail
     otherwise (`operations.sh`: `grep -q '^AUTOWONDER_AONE_ENABLED=false$' … ||
     die "Aone must be disabled"`). The strip removes unreachable code, so it is a
     boundary-hygiene change rather than a behaviour change.
4. **R11 Docker — RESOLVED by the operator, suites now run.** The original
   pre-check found no Docker daemon, so 7 Testcontainers/MySQL-gated suites plus
   `AoneUserApiManualTest` skipped and four options were put to the human. The
   operator answered by installing Docker Desktop (workitem comment `126149`,
   2026-09-08), which also carried the standing instruction that both backend and
   frontend unit tests must run and reach 100%. The container-gated suites were
   therefore executed in this round, with this recipe:

   - `DOCKER_HOST=unix:///Users/honeyfamily/.docker/run/docker.sock` — the
     daemon socket is user-scoped, not the default `/var/run/docker.sock`.
   - `TESTCONTAINERS_RYUK_DISABLED=true` — Docker Hub egress is filtered here, so
     `testcontainers/ryuk:0.11.0` cannot be pulled (`docker pull` fails with
     `docker-credential-desktop: executable file not found in $PATH`, and
     `RegistryConfig.Mirrors` is empty). Two images were pre-pulled from an
     ECR mirror instead: `mysql:8.4.4` and `redis:7-alpine`. Disabling Ryuk
     means **no reaper**, so containers must be cleaned up by hand.
   - `-DargLine="-Dapi.version=1.44"` — the daemon is Server 29.7.2 with
     `MinAPIVersion 1.40`, but the shaded docker-java inside Testcontainers
     1.20.6 negotiates `1.32` and is rejected with
     `client version 1.32 is too old`. Testcontainers' env-var whitelist carries
     13 `DOCKER_*` names and **not** `DOCKER_API_VERSION`, so the only channel
     into the shaded client is a JVM system property.

   Measured result on the post-strip tree: `MVN_CONTAINERS_EXIT=0`,
   `Tests run: 3211, Failures: 0, Errors: 0, Skipped: 1`, `BUILD SUCCESS`,
   `0` `^[ERROR]` lines, and `CONTAINERS_CREATED=8` (7 × `mysql:8.4.4`,
   1 × `redis:7-alpine`) — the same 8 container classes as the prior accepted
   baseline. The single skip is `AoneUserApiManualTest`, a manual test, also
   unchanged. The total is exactly the prior accepted `3212` minus the one Aone
   test method removed by the R7 strip.

   **Caveat that must not be papered over:** `-DargLine="-Dapi.version=1.44"` and
   `TESTCONTAINERS_RYUK_DISABLED=true` are *verifier-supplied* environment
   overrides for this machine, **not** project settings and **not** committed
   anywhere. A community user whose Docker daemon negotiates the API version
   normally, and who can reach Docker Hub, needs neither. This green reading is
   therefore real but environment-specific, and `docs/community/verification.md`
   states it as such.
5. **`fastjson2` version decrease** `2.0.58` → `2.0.31`, resolved per Rule 5.
   Community declares `fastjson2` directly, so it must be reviewed for advisories
   independently of the internal SDKs that used to supply it.
6. **Pre-existing Skill-test debt** (~20 stale assertions) means Rule-12 source #4
   has no passing guard in this environment — the same failure mode as
   `f9ba26785`. Repairing it would multiply the diff without serving external
   deployability, which the minimal-adaptation requirement and AGENT.md's ban on
   扩大无关重构 both forbid, so it is carried forward as open work.
7. **Internal code-review link exposure (H-1) — HIGH, still awaiting a ruling.**
   The guide's Required Release File mandates MR/PR links, so
   `releases/release_v0.8.0_20260907.md` names the internal review host,
   following published precedent (`release_v0.3.5_20260809.md:34`,
   `release_v0.7.0_20260902.md:214`). That file is also copied verbatim into the
   public repository. Three things were measured rather than assumed, and they
   change the *character* of the finding without closing it:

   - **It is genuinely pre-existing and genuinely already public.** An anonymous
     `curl` of the upstream public repository returns HTTP `200`; the
     landing-zone clone has `origin/master` == `origin/HEAD` ==
     `35dd11c296066bfcb9cfccca6db057605b76db2b`, and its
     `ai-sdlc/auto-wonder/releases/` directory already holds **12** release
     files that are blob-identical to community's. Counting the internal-host
     occurrences across the public tree: **28 of 30 are already published**, this
     sync adds **+2**, and **0** are removed. Step `400785` had independently
     reached the same conclusion, calling them 「既有的三条手册要求的 code-review URL」
     with 「零个新增内部 endpoint」. What this round adds is the public-readability
     proof, not the finding itself.
   - **The authoritative gate is blind to it by construction.** Gate B's scope is
     `pom.xml frontend/package-lock.json frontend/.npmrc APP-META src/main
     src/main/resources` — it deliberately excludes `docs/` and `releases/`. It
     passes (`GATE_B_RG_EXIT=1`, 0 hits, 0 stderr bytes), and an out-of-scope
     positive control with the same pattern finds exactly one matching line in
     each of `releases/release_v0.3.5_20260809.md`,
     `releases/release_v0.7.0_20260902.md`,
     `releases/release_v0.8.0_20260907.md` and
     `docs/community/upstream-sync-log.md`. So a gate can report PASS while the
     shipped public surface carries the string it exists to catch. **A gate's
     scope exclusion can blind it to the very artifact a governing rule mandates
     publishing** — the check has to be run against the shipped surface, not only
     the gated one.
   - **Why it is not decided here.** Redacting the links would make the release
     file violate the guide's Required Release File section and would diverge
     from two published precedents; keeping them publishes an internal hostname.
     Both options break a rule, so this is a genuine high-risk boundary decision
     under AGENT.md rule 4 and is handed to the operator rather than resolved
     unilaterally. It is **not** silently changed. Because the R7 strip already
     forces a second internal MR, a redaction ruling can ride in that same MR at
     no extra process cost.
8. **A stale prior-release note.** `releases/release_v0.7.0_20260902.md:217` still
   says the upstream pull request was "not opened yet", although `upstream/master`
   already contains the merged sync `#115`. That file is a published historical
   record and was not edited.

Deployment review: the recommended runtime version is again the only deployment
contract change, and it is the **only** deployment-asset change this sync
requires. It was propagated to the deployment manifest template and to the
deployment and upgrade Skill tests per Rule 12. The operator-documentation place
needed **no numeric edit** because it is already correct by reference —
`references/operations-runbook.md:121-122` names the manifest as the source of
truth instead of hardcoding a version, and `references/input-catalog.md` no longer
carries a version literal, so bumping the manifest keeps the guidance correct
automatically. **No topology, credential, port, endpoint, database or
environment-template change is required, and deployment input collection needs no
new prompt.** The merge itself changed **zero** files under
`skills/deploying-autowonder-on-alibaba-cloud` or
`skills/upgrading-autowonder-on-alibaba-cloud`, proved two ways: the
`--name-only` diff between the community tip and the merge commit reports 0
paths, and the `skills/` subtree hash is identical on both sides
(`729d40ec323ffe29056d580a701b6ebcdf692073`).

Configuration-key review: master's window changed `src/main/resources/application*.yml`
in exactly three ways — it removed the internal `akless:` block from four files
(community already excludes it), advanced `recommended-version` to `0.2.152`, and
added the new `autowonder.provider-model-catalog` group of 7 keys
(`refresh-fixed-delay-ms` 86400000, `refresh-lock-ttl-ms` 120000,
`request-timeout-seconds` 20, `ticket-ttl-seconds` 30, `max-candidates` 3,
`failure-cooldown-seconds` 300, `worker-queue-capacity` 8). **Explicit Rule-9
conclusion for that group: no deployment update required.** All seven keys are
hardcoded literals with no `${VAR}` placeholder, so none is a member of the
environment contract; community's `application.yml` already contains the block
identically; and its readers use Redis, which community already mandates. No new
infrastructure, port, credential or environment variable, and therefore no change
to `application.env.example`, the deployment scripts, preflight checks, input
collection or the runbook. Nothing was dropped.

Environment contract measured this sync: 37 keys in
`docs/community/application.env.example`, 54 `${VAR}` placeholders in
`application*.yml`, union 54, and the example is a strict subset of the yml. The
**17 yml-only keys are intentional omissions, not missing keys**, per this guide's
rule that `plan-upgrade.sh` collects the yml placeholders as well as the example's
`KEY=` lines; bulk-adding them would change their contract hash and make the
upgrade planner report changed environment for no functional gain.

All 17 are named here with the measured reason each is safe to leave out, because
the guide's rule is that an *unexplained* missing key is a blocking defect:

* **4 Redis tuning knobs** — `REDIS_DATABASE` (`application-local.yml:38`, default
  `0`), `REDIS_CONNECT_TIMEOUT_MS` (`:39`, `1000`), `REDIS_SOCKET_TIMEOUT_MS`
  (`:40`, `1000`), `REDIS_POOL_MAX_TOTAL` (`:41`, `100`). The three
  connection-identity keys an operator must actually set *are* documented:
  `REDIS_HOST` (`application.env.example:4`), `REDIS_PORT` (`:5`),
  `REDIS_PASSWORD` (`:6`).
* **1 OSS master switch** — `OSS_ENABLED` (`application.yml:29`, default `true`).
  Community mandates OSS, so the default already is the required value and there
  is nothing for an operator to choose.
* **3 OSS bucket overrides** — `OSS_TASK_PKG_BUCKET` (`application.yml:35`),
  `OSS_ARTIFACT_BUCKET` (`:36`), `OSS_SKILL_BUCKET` (`:37`). All three default to
  `${OSS_BUCKET:}`, and `OSS_BUCKET` is documented
  (`application.env.example:13`), so the one documented key already drives all
  four; the overrides exist only to split a single bucket into three.
* **6 S3 keys** — `S3_ENABLED` (`application.yml:42`, default `false`),
  `S3_ENDPOINT` (`:43`), `S3_PUBLIC_ENDPOINT` (`:44`), `S3_REGION` (`:45`),
  `S3_ACCESS_KEY_ID` (`:46`), `S3_ACCESS_KEY_SECRET` (`:47`). This is the
  disabled-by-default alternative storage path the guide treats as optional, and
  `plan-upgrade.sh` encodes exactly that: `S3_ENABLED`, `S3_PUBLIC_ENDPOINT` and
  `S3_REGION` are never required from the env file (`:117-118`), while
  `S3_ENDPOINT`, `S3_ACCESS_KEY_ID` and `S3_ACCESS_KEY_SECRET` become required
  only once the operator has set `S3_ENABLED=true` (`:108-109`, `:115-116`).
  Documenting them unconditionally would invite an operator to fill in credentials
  for a path they are not using.
* **1 launcher-owned profile key** — `SPRING_PROFILES_ACTIVE`
  (`application.yml:3`, default `local`). The deployment scripts choose the
  profile themselves, so exposing it in the example would create a second,
  conflicting way to pick one.
* **1 Aone key under the guide's permanent-omission clause** —
  `AUTOWONDER_AONE_WEB_BASE_URL` (`application.yml:99`, default empty). Aone
  feature iterations are excluded from Community, so the only Aone key the example
  carries is the one that switches them off: `AUTOWONDER_AONE_ENABLED=false`
  (`application.env.example:26`).
* **1 runtime-version key** — `AUTOWONDER_RUNTIME_RECOMMENDED_VERSION`, explained
  individually in the next paragraph.

The guide's coverage claim was verified against the script rather than assumed:
`collect_env_contract`
(`skills/deploying-autowonder-on-alibaba-cloud/scripts/plan-upgrade.sh:65-87`)
dispatches on path at `:69-75` and scans `src/main/resources/application*.yml` for
`${VAR}` at `:72-73` alongside the example's `KEY=` lines at `:70-71`, so all 17
sit inside the planner's contract set and inside its added/removed/changed diff
(`:91-95`).

`AUTOWONDER_RUNTIME_RECOMMENDED_VERSION` is one of the 17: it stays out of the
example, is still collected from the yml placeholder, is required non-empty by
`scripts/internal/operations.sh:392`, and is written from the manifest at `:372`.
`AUTOWONDER_VERSION` stays the `x.x.x` placeholder in both `application.yml:52`
and `application.env.example:10` because `build-release.sh:42-45` injects the real
value from `VERSION` at deploy time, so bumping `VERSION` needs no yml or example
edit. `AUTOWONDER_AONE_ENABLED=false` remains the only Aone key in the example.
Community-specific defaults were re-verified in all three places and are
consistent, and **no community-versus-upstream default changed in this window**:
`autowonder.community-edition` (`application.yml:56`, `application.env.example:28`,
`docs/community/README.md:111-116`), and the three `autowonder.scheduled-task`
switches `enabled`, `scanner-enabled` and `cluster-ready-attestation`, which
default to `true` here where upstream defaults them to `false`
(`application.yml:72-74`, `application.env.example:33-35`,
`docs/scheduled-task-operations.md:9-11`, whose `:22` also forbids a single node
inferring `CLUSTER_READY`, and `docs/community/README.md:118-123`). The upgrade
requirement to set all three to `false` until every node runs the new schema is
documented in the release notes, the example, the yml and the runbook.
`spring.buc.enabled` and the Aone web-base-url key remain the intentionally
omitted upstream keys.

**Third-place gap found and closed in this step.** An earlier draft of the
paragraph above cited *this log* as the third place for
`autowonder.community-edition`. That was wrong: a sync log is an audit record,
not operator-facing documentation, so the most identity-defining community
default had **no** operator documentation at all — an operator reading
`docs/community/README.md` could not learn that the flag exists, that it differs
from upstream, or what it controls. `docs/community/README.md` now carries both
community-default paragraphs in a new final section, §Community-Specific Defaults
(`:109-123`). It is appended rather than inserted inside §Configuration on
purpose: inserting 15 lines at `:44` shifted every README line below `:43` and
stale-d roughly 15 pre-existing numeric `README.md:NN` citations in this log,
`docs/community/verification.md` and `releases/release_v0.8.0_20260907.md`.
Appending keeps the README's first 107 lines identical to the whole file at
pre-edit commit `ce8721243` (both `2a247dbcc0c8618ff5ce0bdca099084e089a46f9`),
so the added documentation does not create the defect class this step corrects.

The differing-default set was enumerated mechanically rather than from memory:
every `${VAR:default}` in `src/main/resources/application.yml` was
compared against the same set at `b52cdeeea`, giving exactly **4** differing
defaults (the four named above), 26 community-only placeholders and 2
master-only ones (`autowonder.jwt.secret`, a hardcoded development secret, and
`profile_env: daily`; both are deliberate community removals, not defaults to
synchronize).

One further literal is recorded because the guide requires every
community-specific default to be named: `PlatformBrandingService.java:54`
declares `@Value("${autowonder.community-edition:false}")`, i.e. the constructor
fallback is upstream's `false`, not community's `true`. This is **not** a
contradiction — `application.yml:56` always supplies the property, so the
fallback never fires — but if that yml key were ever removed the deployment would
silently stop being a community edition. It is the same class of second literal
as Rule-12 source #9 and is disclosed rather than changed, because aligning it
would modify master-owned product code for no behavioural gain.

Independent review: **performed and passed, with findings — SDLC step `400360`,
2026-09-07, measured at community sync-branch tip `579225caf`.** The review was
executed against the six checks the guide mandates in Procedure step 6 and was
organized in three stages. No finding was classified critical, and no finding
blocked the push; two are carried to the human gate.

**Stage 1 — completeness and deviation (three-way set comparison).**
SET_A = upstream `25371cb1..b52cdeeea` = **335** paths. SET_B = community
`c3a75ae6..579225caf` = **316** paths. SET_C = residual `b52cdeeea..579225caf` =
**564** paths.

- `A − B` = **29 paths, all attributed to a recorded community boundary**:
  3 migrations renamed `V045`/`V046`/`V047` → `V049`/`V050`/`V051` (`cmp -s`
  **identical**, Rule 11); 20 × `docs/superpowers/{plans,specs}/*` (HEAD 0 vs
  master 170, Rule 8 + docs-policy); `docs/config-replacement-checklist.md`
  (master=1, HEAD=0, deleted — master's own change removed the whole
  `## 6. AkLess` section, which community had already deleted);
  `Bootstrap.java` (master removed AkLess initialization; community had already
  removed all internal credential machinery, leaving
  `public static void main(String[] args) { SpringApplication.run(Bootstrap.class, args); }`
  — a strict subset of master's result); `application-daily.yml`,
  `application-testing1.yml`, `Testing1EnvironmentConfigTest.java` (master=1,
  HEAD=0, internal profiles excluded); `application-local.yml` (master=1, HEAD=1
  — master only deleted its `akless:` block, community had already replaced the
  entire internal section with `${ENV:default}` form).
  All 6 conflicting files appear in both `merge-conflicts-raw.txt` and the
  conflict-disposition ledger.
- `B − A` = **10 paths, all community-owned release artifacts**: `VERSION`,
  `releases/release_v0.8.0_20260907.md`, this log, `docs/migration/V049`,
  `V050`, `V051`, `deployment-manifest.json`, `test_manifest.py`,
  `test_script_contracts.py`, `test_upgrade_info.py`.
- **Mapper/DAO statement loss: none.** Master changed 26 mapper/DAO files; all 26
  are `IDENTICAL_TO_MASTER` at HEAD.
- **DDL loss: none.** `docs/autowonder-schema.sql` differs from master by only
  6 added / 6 removed lines, all `SecretCrypto` column widenings
  (`credential_ref VARCHAR(…)` → `TEXT`, the `token_ref` comment, one header
  line). `src/test/resources/conversation-elicitation-schema-h2.sql` differs by
  exactly 1 comment line (`镜像 docs/migrations/V045 的列` →
  `镜像 docs/migration/V049 的列`).
- **Deliberate boundary deletion:** `src/main/resources/rass-policy.xml` —
  master=1 (blob `450f4d3e255f0ebc71c9ac71f5f2d6c23533b320`, 23 lines),
  community HEAD=0, pre-merge=0, `--name-status` = `D`.
- **Environment variables: zero loss.** Placeholders — master `application.yml`
  16 + `application-local.yml` 2; community HEAD 41 + 13 → union **54**; the
  pre-sync community tip is also **54** (`IDENTICAL_PLACEHOLDER_SET=YES`).
  Master placeholders absent at HEAD: **0**. Community-only placeholders: **36**,
  all Rule 4 adaptation. Master added **0** and removed **0** `${VAR}`
  placeholders across `25371cb1..b52cdeeea` (measured by `comm` in both
  directions), so no new key entered the contract this cycle. The union rule was
  honored: the 37 `KEY=` lines of `application.env.example` are a subset of the
  54, and the 17 yml-only keys were deliberately **not** bulk-added.
- **Blended file spot-check:** `AoneInboundSyncService.java` took master's new
  `GuidanceService` wiring while preserving its community
  `KeyCenterClient` → `SecretCrypto` and `AoneIntegrationProperties` difference
  (31+/20− vs master; pre-sync delta 27+/17−). No master behavior was dropped.

**Stage 2 — manual compliance.** The full guide (209 lines) and docs-policy
(59 lines) were read and each numbered rule checked by measurement, not recall:

- Rule 1 — work was performed on the sync branch
  `aw/community-sync-b52cdeeea-20260906`, never on `master`.
- Rule 2 — `refs/remotes/origin/master` = `b52cdeeea`; merge `616b84687e`
  parents = `c3a75ae6` + `b52cdeeea`; merge-base = `b52cdeeea`;
  `ORIGIN_MASTER_IS_ANCESTOR_OF_HEAD=YES`. An exact fetched commit was merged,
  not a stale local branch.
- Rules 3, 5, 6 — see the Stage 1 blended-file and set-comparison evidence.
  Notably, master itself upstreamed the Qoder-CLI-only executor restriction in
  `b0a010db0`, so `ExecutorListPage.tsx:25,26,28,33,36,40,132` and
  `qoderOptions.ts` (blob `3f5b0dc8df9adb589f2e96a364f5a1fc5adda6b8`) are
  **line-identical on master and community**; Rule 5 makes that boundary a no-op
  this cycle.
- Rule 4, all nine sub-bullets — BUC: the only `grep 'buc'` hits are `bucket`
  (`application.yml:32,35,36,37`); `spring.buc` = 0, `BUC_[A-Z_]+` = 0,
  `find -iname '*buc*'` = 1 → `AoneRateBucketDao.java`. Aone:
  `application.env.example` carries exactly `26:AUTOWONDER_AONE_ENABLED=false`
  and no `AUTOWONDER_AONE_WEB_BASE_URL`. SecretCrypto: 3 files present
  (`SecretCryptoAutoConfiguration.java`, `security/crypto/SecretCrypto.java`,
  `SecretCryptoProperties.java`); KeyCenter in `src/main` + `pom.xml` = **0**.
  OSS mandatory / SLS supported / SIGAR / scheduled-task defaults verified at
  `application.yml:29,42,72,73,86,98,102`. Linux x86_64 target intact at
  `SKILL.md:41,59,73,83` and `docs/community/README.md:5`. Skills relocation:
  root `skills/` holds 3 directories, `docs/skills` HEAD=**0** vs master=**22**,
  `.agents` HEAD=2 vs master=**0**; **all 22 master `docs/skills` files are
  byte-identical under root `skills/` (IDENTICAL=22, DIFFERS=0, MISSING=0)**, and
  `docs/skills` was unchanged in the window — the relocation is proven by
  `8591746bd refactor(community): consolidate skills under root skills/ directory`.
- Rule 8 / docs-policy — all **18** retained documents present; `docs/migration`
  17 files; every excluded category **0** (`superpowers` 0 vs master 170, plans 0,
  specs 0, plural `docs/migrations` 0). `releases/` 13 files. HEAD `docs/` = 40
  paths vs master 284. The internal-reference rescan this entry told step `400360`
  to re-measure at the final tip was re-measured: the authoritative narrow gate
  returns **0 hits** (`GATE_B_RG_EXIT=1`, re-run both at `579225caf` and at the
  step-`400360` commit), `KeyCenterClient` in `src` + `pom.xml` = **0**, and the
  broad `cl_2` rescan's **code-scope** count is **39** at `c3a75ae6`, at
  `579225caf` and at the step-`400360` commit — unchanged. Only its tree-wide
  deduped count moves, 62 → 80 → **86**, entirely from documentation prose that
  names the excluded dependencies in order to explain their exclusion. Every
  anchored figure recorded earlier in this entry reproduced exactly on re-run.
- Rule 9 — recorded earlier in this entry as "no deployment update required",
  corroborated by the environment-variable measurement above.
- Rule 11 — see Stage 1 and the verification table: 3 additions, 0 modifications,
  0 deletions of published migrations, byte-identical content, no duplicate
  numbering.
- Rule 12 — all five sources read `0.2.152`; residual `0.2.150` under `skills/`
  and `src/main` = **0**; targeted `test_manifest.py` (4 passed, exit 0) and
  `test_upgrade_info.py` (11 passed, exit 0) both green.
- Release-file and log-entry mandates — all **9** required release-file fields
  and all **12** required log fields present.

**Stage 3 — consistency, byte-for-byte including file modes.**
`git ls-tree -r HEAD` → 1871 × `100644` + 28 × `100755` = **1899** paths;
symlinks (`120000`) = **0**; exotic modes = **none**. Executable set: HEAD 28,
merge commit 28 → `EXEC_MODE_DRIFT=0`. The exact export mechanism step `400362`
is mandated to use was then proved end to end:
`git archive --format=tar HEAD | tar -x -C <tmp>` → `GIT_ARCHIVE_TAR_EXIT=0`,
1899 files extracted, 0 symlinks; a per-file Python recheck recomputed each git
blob SHA-1 from the extracted bytes and each filesystem executable bit against
the `ls-tree` manifest → MISSING=0, CONTENT_MISMATCH=0, MODE_MISMATCH=0, EXTRA=0,
i.e. `ARCHIVE_ROUNDTRIP_BYTE_AND_MODE_IDENTICAL: YES`. The external-repository
copy has not yet been produced (step `400362`), so the external tree comparison
is deferred to that step and must be re-checked there.

**Findings and disposition.**

| # | Finding | Risk | Disposition |
| --- | --- | --- | --- |
| F1 | `docs/community/verification.md` carried measurements from the v0.7.0 cycle (backend 2652 tests, frontend 116 files / 901 tests, Skill counts) and a now-false npm-audit paragraph describing two high React Router RSC-mode advisories that npm no longer reports | Important | **Fixed in this step.** The file was rewritten with this cycle's measured values; the audit paragraph now reports the single high **nanoid** `GHSA-2v37-7h3g-55p8`; every un-rerun row is marked `NOT RE-RUN` with its last measurement date |
| F2 | The same file attributed all 29 deployment-Skill failures to "stale assertions against script content that moved into `scripts/internal/release-transfer.sh`" | Important | **Fixed in this step.** The measured split is 18 stale moved-content + 2 stale manifest fixtures + 8 `terraform` absent + 1 `~/.aliyun/config.json` absent. The upgrade Skill's 6 are 5 environment + 1 stale exit-code contract whose guard was manually reproduced and found to fire correctly |
| F3 | The release file's MR/PR paragraph says "the follow-up commit" (singular) although five correction commits exist on the branch | Low | **Fixed in this step** and rephrased durably so it does not re-stale on the next commit |
| F4 | Five files sit in the published `docs/` tree that docs-policy's Retained Documents list does not name: `docs/community/community-sync-agent-design.md` and 4 × `docs/images/*.png` | Low | **Reported, not unilaterally fixed.** The 4 images are legitimately required by root `README.md:15,22,35,43`. The design doc has **0** references anywhere and came from `fb0393c65 docs(community): define sync release agent`. All five are pre-existing (`master=0, presync=1, head=1`) and were untouched by this sync, so this is a policy-inventory gap, not a sync defect. Amending the governing policy document is a human decision and is carried to the `400361` gate |
| F5 | Rule 12 source #4 (`test_script_contracts.py`) is not guarded by a passing test in this environment | Low | **Disclosed, not fixed here.** Its three literals were confirmed by direct inspection. Fixing the pre-existing Skill-test debt is out of scope for a sync and is tracked as disclosed debt |
| F6 | Toolchain drift: the environment ran Node `v26.4.0` / npm `11.17.0` while the project documents Node v22 / npm 10 | Low | **Disclosed.** All gates were still captured by exit code and summary line. Re-measure on the documented toolchain before publishing a release image |

**Baseline disposition.** The recorded baseline in *Current Baseline* above
**stays at `25371cb104ac019fb26674f0c495c410c01e5041`** and is **not** moved by
this step. The guide conditions the move on both "the merge and required
verification have completed" **and** "the independent sync review has passed".
The review has now passed, but the merge has **not** landed:
`refs/remotes/origin/community`
is still `c3a75ae60462e3bdc93d1e9fb82041dc9d442f51` because merge `616b84687`
sits on the sync branch awaiting the human MR gate (step `400361`). Moving the
baseline now would advertise a synchronized baseline that `community` does not
contain. `b52cdeeea3b82316ee370e56d5533e6a8d9245b3` therefore remains the
**candidate** baseline and becomes the recorded one only after a human merges the
MR into `community`. Ancestry was proved rather than assumed: `25371cb1` is an
ancestor of `c3a75ae6`, and both `c3a75ae6` and `b52cdeeea` are ancestors of
`616b84687`.

Verification. Exit codes were captured from the build tools themselves, never from
a command downstream of a pipe.

- Backend `mvn clean verify` on the merged tree: BUILD SUCCESS, `MVN_EXIT=0`,
  3171 tests, 0 failures, 0 errors, 8 skipped. Pristine `origin/master`
  `b52cdeeea` baseline: 3151 tests, 0 failures, 0 errors, the **same** 8 skips,
  so the merge adds 20 tests and no regression. `mvn testCompile` is clean after
  the three post-merge fixes.
- Frontend: lint `NPM_LINT_EXIT=0` with 0 errors and 2 pre-existing
  `react-hooks/exhaustive-deps` warnings; production build `NPM_BUILD_EXIT=0`
  (`tsc -b && vite build`, built in 5.97s); unit tests 1460 passed / **9 failed**
  of 1469, `NPM_TEST_EXIT=1`. All 9 are attributed and none comes from conflict
  resolution: 6 in `ExecutorListPage.test.tsx` are master's own pre-existing
  failures from the new Agent-grouping UI, reproduced on pristine master; 3 in
  `WorkitemDetailPage.test.tsx` are caused by community's earlier toolchain bump
  `86e3614ff`, isolated by a reproduction worktree carrying pristine master plus
  only that toolchain change. The suite is **not** green and must not be reported
  as green.
- Deployment Skill: 72 passed, 29 failed. Upgrade Skill: 66 passed, 6 failed, 1
  skipped. Both failure-name sets are **byte-for-byte identical before and after**
  this cycle's edits, measured on a pristine detached worktree at the merge commit
  with the adaptation reverted, so the Rule-12 alignment introduced zero
  regressions. The two Rule-12 suites that guard the aligned literals are green:
  `test_manifest.py` 4 passed and `test_upgrade_info.py` 11 passed, both
  `PYTEST_EXIT=0`. Causes of the remaining failures are missing `terraform`,
  `ossutil` and `~/.aliyun/config.json`; stale text-grep assertions that still
  expect inline implementation which a prior community refactor moved into
  `scripts/internal/`; and one host-gated PowerShell skip. **The credential file
  was deliberately not created** to make tests pass — AGENT.md Rule 8 forbids
  credentials entering this work.
- Migration immutability (Rule 11): 3 additions `V049`/`V050`/`V051`, each
  byte-identical to its upstream counterpart modulo the filename, and **zero**
  previously published migrations modified, renamed or deleted
  (`git diff --name-status c3a75ae6..616b84687 -- docs/migration` reports three
  `A` entries and no `M` or `D`). `docs/autowonder-schema.sql` was updated in the
  same merge and carries all of it, so the full schema and the incremental
  migrations agree. Published `V048__workspace_access_request.sql` still contains
  a stale `-- V044__` self-header comment; Rule 11 forbids modifying a published
  migration, so it was left untouched and is disclosed instead.
- Dependency boundary: `CommunityDependencyBoundaryTest` green, `KeyCenterClient`
  0 occurrences, no internal SDK required, and `package-lock.json` regenerated
  from the public registry. Two different scans must not be confused here. **The
  authoritative gate** is the internal-host scan defined in
  `docs/community/verification.md` — the `! rg -i 'alibaba-inc\.com|…'` command
  in its **Automated Gates** block, tabulated there as the *Internal-reference
  scan* row. It is cited by name rather than by line number because this sync
  cycle rewrote that file, so any line citation is already stale —
  internal-host pattern over `pom.xml`, `frontend/package-lock.json`,
  `frontend/.npmrc`, `APP-META`, `src/main` and `src/main/resources` — and it
  returns **0 hits**, re-measured at this sync's tip. Its scope deliberately
  excludes `releases/` and `docs/`, so documentation prose cannot move it. **The
  broader `cl_2` rescan** additionally matches the *names* of the excluded
  internal dependencies, so its count grows whenever a boundary document explains
  an exclusion; it is a review aid, not a fixed threshold. Normalized to
  `file:text` and deduped it gives 62 at the community tip `c3a75ae6`, **62 at the
  merge `616b84687`, so the merge added zero**, 70 once the v0.8.0 release file
  landed, and 78 once this entry landed. Every one of those +16 sits in the two
  documentation files this sync authored — prose naming what was excluded, plus
  the single internal code-review URL that guide line 181 mandates under "MR/PR
  links". **This sync introduced zero code-scope hits and removed zero.** Measured
  over `src`, `pom.xml`, `frontend`, `skills`, `APP-META`, `docs/migration` and
  `*.sh`, the broad rescan returns **39** at the community tip `c3a75ae6` and the
  **same 39** at `616b84687`, `2813b524f`, `fe22d55dc` and this sync's tip — an
  earlier draft of this entry claimed "no hit is in any source, build, config,
  dependency, manifest, script or schema file", which is false as an absolute and
  is corrected here. All 39 are pre-existing and classified: 14 are the negative
  assertions inside `CommunityDependencyBoundaryTest` and `CommunityBuildInputTest`
  (the forbidden strings *are* the guard), 2 the same in
  `test_squad_template_seed.py`, 2 a .NET BCL false positive
  (`[Security.Cryptography.SHA256]` matching the `Cryptograph` alternative) in two
  PowerShell scripts, 3 SQL `COMMENT` text in the test-only legacy fixture
  `src/test/resources/schema/autowonder-pre-v037.sql`, 16 Aone integration test
  fixture URLs (11 Java, 5 frontend `*.test.tsx`) for a module that is
  master-owned and disabled by default in community, and 2 an internal git URL used
  as a unit-test fixture in `RepoWorkspacePreparerTest`. None is a live endpoint,
  a build input, a dependency coordinate or a shipped configuration value, and the
  authoritative gate stays at **0** precisely because its scope is `src/main` and
  not `src/test`. Zero hits were removed, so no pre-existing community redaction
  point was restored or weakened. Step 400358's `cl_2` reported "77 versus 76"
  measured at the merge commit under a slightly different normalization; for any
  later tip those counts are superseded by the above, and step `400360` must
  re-measure at whatever the final tip is rather than quoting a stale number.
- Environment-contract union, executable file modes (28 before and after, 0
  drift) and the no-lost-master-feature proof over the 53 paths where merged
  differs from master all pass; all 24 deletions carry docs-policy authority plus
  a cited deliberate removal commit.
- **Reported as SKIPPED, never as PASS:** every Docker/Testcontainers-gated suite,
  the `terraform`/`ossutil`/credential-gated Skill tests, and the PowerShell-gated
  Windows adapter test. `docs/community/verification.md` was **not** rewritten by
  this cycle: it still carries v0.7.0-era measurements, including a startup smoke
  that reported runtime `0.2.150`. Rewriting it to claim `0.2.152` without
  re-measuring would be fabricated evidence, so the review and smoke steps own it
  and must mark every un-rerun row `NOT RE-RUN` with its last measurement date.

**Added by SDLC step `400785` (local startup smoke verification), 2026-09-07.** The
baseline statement above is unchanged; these are two defects that step found in the
already-merged tree, plus what it could and could not prove at runtime.

- **Release-blocking regression found and fixed: `frontend/package-lock.json` could
  not be installed by the toolchain the build actually uses.** `@vitest/mocker@3.2.6`
  declares an optional peer `msw: ^2.4.9` that the root pin `msw@2.3.0` cannot
  satisfy, so npm must install a second copy at `vitest/node_modules/msw@2.15.0` with
  its own transitive closure — 23 keys. With those entries absent, `npm ci
  --include=dev --include=optional` — the exact arguments `frontend-maven-plugin`
  runs — fails with `EUSAGE`, "`npm ci` can only install packages when your
  package.json and package-lock.json are in sync", and 23 `Missing: …` lines. **A
  fresh clone of the community branch could not build at all**, which for a fork whose
  purpose is external installability is the worst possible failure. Repaired with
  `npm install --package-lock-only` so npm resolved the entries itself: 23 keys added,
  0 removed. The versions are npm's current resolutions for those ranges, not a
  byte-identical revert of any earlier state.
- **Where the 23 keys actually went missing — the merge did not drop them.** This is
  a correction, recorded because getting it wrong would point future cycles at the
  wrong gate. Per Rule 4 and risk **R4** of step 400357's analysis, master's lockfile
  was **deliberately not merged**: it resolves the new `@vitest/coverage-v8@3.2.6`
  subtree from an internal npm registry, whose hostname is not written here because
  Rule 8 forbids internal endpoints in published community output. Merging it would
  have put an internal registry coordinate into a community artefact. Step 400359
  instead kept the community side (`git checkout --ours`) and regenerated with
  `npm install --package-lock-only`, which added 35 keys and removed 36 with
  **0 version changes**.
  All 36 removals were proven at the time to be npm-11 optional-peerDependency
  pruning — and these 23 are among them, reachable only through `@vitest/mocker`'s
  optional peer `msw ^2.4.9`. So the correct boundary adaptation was executed on a
  **drifted toolchain** (node `v26.4.0` / npm `11.17.0` instead of the documented
  Node v22 / npm 10), and npm 11's pruning is what removed them. Step 400360's
  independent review recorded exactly that drift as finding **F6** and prescribed
  "re-measure on the documented toolchain before publishing a release image", but
  graded it **Low** because every gate it ran was still captured by exit code and
  summary line — true, and the reason it was low is that no gate exercised a clean
  install. **Commit `6fa57221a`'s message ("restore the lock subtrees the master
  merge dropped") therefore states the mechanism imprecisely.** It is corrected here
  rather than amended: the discipline is to correct forward with new commits and never
  to rewrite pushed history, and the *content* of that commit is right regardless of
  how its message describes the cause.
- **Why every earlier gate missed it, stated so it does not recur.** Two
  independent reasons. First, the documented verification command carries
  `-DskipFrontend=true`, which suppresses all three `frontend-maven-plugin`
  executions, and the standalone frontend gates ran against an already populated
  `node_modules`, so a clean dependency install was never exercised anywhere in the
  cycle. Second, and less obvious: the standalone `npm ci` that *did* pass used the
  machine's **npm 11.17.0**, which tolerates the out-of-sync lock and installs 716
  packages with exit 0, while the **npm 10.9.7** the plugin installs rejects it with
  exit 1. Both measurements are reproducible and both were true; the counts
  corroborate the diagnosis exactly, since 716 + 23 = 739 and the repaired lock
  installs `added 739 packages`. `verification.md`'s Automated Gates section now
  requires one build **without** `-DskipFrontend` and requires the frontend gates to
  run under `target/node`, the plugin's own toolchain.
- **Rule 11 gap found: `docs/autowonder-schema.sql` is not covered by any rule in
  `upstream-sync-guide.md`.** Rule 11 renumbers `docs/migrations/` → `docs/migration/`
  (V045→V049, V046→V050, V047→V051) and the renumbering *was* applied everywhere it
  was written down — `ConversationElicitationSchemaContractTest` cites
  `docs/migration/V049__conversation_elicitation.sql` where master's copy cites
  `docs/migrations/V045__…`. But three comments inside the schema file still cited
  upstream numbers, and two of them pointed at *different, unrelated* community
  migrations, which is worse than dangling. One, `V045__conversation_elicitation`,
  was introduced by this cycle's merge and is a straight miss of step 400358's
  instruction to move migration paths to community numbering; the other two predate
  this sync. Corrected: now `V049` and `V048`, both real files, and the third
  (community `V018` is unrelated) now says plainly that the dingtalk tables predate
  the community migration baseline. **Comment text only — 4 insertions, 3 deletions,
  0 changed lines that are not SQL comments**, so no `CREATE TABLE`, column, key or
  engine clause moved. `ConversationElicitationSchemaContractTest` asserts the
  `agent_conversation_elicitation` definition is byte-identical between migration and
  schema file, and it passes in the full build, as do `ExecutorSchemaContractTest`
  and the 14 `SystemSettingServiceTest` cases that also read this file.
  **Recommendation:** the sync guide should gain a rule naming
  `docs/autowonder-schema.sql` explicitly in the renumbering step, and the sync
  checklist should grep that file for `V0\d\d__` after every merge. Nothing in the
  current guide would have caught this.
- **Local startup smoke: PASSED — and the FAIL verdict recorded here earlier the
  same day is withdrawn.** The prior bullet said the smoke was "attempted,
  failed, attributed to the environment", that the dependency compose "cannot be
  started, because this host has no container runtime of any kind", and that the
  health check, capability endpoint and auth chain "were **not observed and are
  not claimed**". All three statements are now false. That verdict was correct
  when it was measured, and why it changed is worth keeping: Docker Desktop was
  present all along, but `DOCKER_HOST` had to be pointed at
  `unix://$HOME/.docker/run/docker.sock` (`/var/run/docker.sock` does not exist
  on this host) and Docker Hub egress is filtered, so the images had to be pulled
  from public mirrors (`public.ecr.aws/docker/library/...`, `quay.io/minio/...`)
  and `docker tag`-ed back to the names the community files expect. That kept
  `docs/community/docker-compose.dependencies.yml` and `docs/community/README.md`
  **byte-for-byte unmodified** — adapting a community artefact to fit a host
  would have been an environment-driven product change. The earlier failure was
  real, and its proximate cause was
  `RedisManager.testEnterprise(RedisManager.java:249)`, which probes Redis
  eagerly at bean construction and rethrows `redis enterprise test fail.`; with
  Redis actually listening, that call logs `Redis Enterprise version check
  success!` and startup proceeds.
  - **Run A (host JVM).** `java -jar target/auto-wonder.jar` →
    `Started Bootstrap in 3.666 seconds`.
  - **Run B (the documented container path, `README:73` verbatim).**
    `DOCKER_RUN_EXIT=0`; healthy after 13 × 2 s polls; `CONTAINER_STARTUP_OK=1`;
    `container_state=running running=true exitcode=0`; `Started Bootstrap in
    20.704 seconds (JVM running for 24.114)`; the driving script's own
    `SCRIPT_EXIT=0`.
  - **Landmarks common to both runs:** profile `local`, `Tomcat initialized with
    port(s): 7001 (http)`, `HikariPool-1 - Start completed.`, `V037 schema
    capability: mode=V037_READY, mapper_mode=SOURCE_AWARE,
    scheduled_available=true, missing_count=0`, `AiWorkerPool started with 3
    workers`.
  - **The schema really was imported into a brand-new volume.** `down -v`
    destroyed the volume and `up -d --wait` recreated it, the entrypoint logged
    `running /docker-entrypoint-initdb.d/001-autowonder-schema.sql`, and the live
    database holds **65 tables against 65 `CREATE TABLE` statements** in
    `docs/autowonder-schema.sql`. This cycle's new tables
    (`agent_conversation_elicitation`, `workspace_access_request`) are present,
    all **4** guarding unique keys report `unique=YES`, and the previously
    published "114 generated columns" is corrected to exactly **2** true computed
    columns — 112 `DEFAULT_GENERATED` timestamps had been conflated with them.
  - **The application serves requests.** `/checkpreload.htm` → HTTP 200
    `success`; `/api/integrations/capabilities` → HTTP 200
    `{"aoneEnabled": false}`; `/api/platform/branding/public` → HTTP 200 with the
    community defaults live — `communityEdition: true`,
    `recommendedRuntimeVersion: "0.2.152"`, `deploymentVersion: "0.8.0"`,
    `platformName: "AutoWonder"`, `themeKey: "aliyun-orange"`. An unauthenticated
    `/api/workspaces/mine` returns `{"code":"10401","message":"未登录或登录已失效"}`,
    so the guard is real and not merely absent.
  - **The full auth chain was walked, not just probed:** register → login →
    create workspace → switch → then this cycle's new endpoints (V048 access
    request create/read/approve, V051 soft-delete → recycle-bin → restore, V049
    clarification-conversation list, and the two new elicitation routes). **26
    numbered steps, 27 HTTP calls, every one HTTP 200.** The two brand-new
    elicitation routes return `{"success":false,"code":"10001","message":"conversation not found"}`
    for a non-existent conversation id — reachable and validating, which is the
    proof that the routes exist rather than 404.
  - **Log scan:** 323 lines, 309 INFO, **5 WARN, 0 ERROR**, 0 stack frames,
    0 `Caused by:`. All five WARN are business-expected and were triggered by my
    own requests (codes `12012`/`12013`). Container log: 47 lines, **0 ERROR,
    0 WARN**. No token or password appears in either (`eyJ`=0, `password`=0).
  - **Still not proven, and deliberately not claimed:** the two Docker-backed
    Testcontainers suites (`V037LegacyArtifactServiceFlowMySqlTest`,
    `ScheduledTaskSpringMybatisIntegrationTest`) **skipped** rather than passed,
    and a skip proves nothing about the container contracts. Relatedly, the
    compose file ships `mysql:8.0` (measured live as 8.0.46) while those two
    suites declare `mysql:8.4.4`, so **no test in the tree exercises the MySQL
    version the community deployment actually ships**. What closed that gap for
    this cycle is the live smoke above, which ran the real schema against a real
    MySQL and a real Redis.
- **One community-tree code change came out of this step, and it is not the
  smoke.** `docs/community/README.md:70`'s documented `docker build` could not
  succeed: `APP-META/docker-config/Dockerfile`'s build stage copied `pom.xml`,
  `frontend`, `src`, `docs` and `APP-META` but never `scripts`, while
  `V037DockerReleaseGateScriptContractTest` resolves
  `scripts/verify-v037-docker-gates.sh` relative to the process working
  directory, which inside that stage is `/workspace`. Fixed with one line,
  `COPY scripts ./scripts` (commit `624bde76e`), after which the documented build
  gives `DOCKER_BUILD_EXIT=0`, in-container `BUILD SUCCESS`, `Tests run: 3171,
  Failures: 0, Errors: 0, Skipped: 8`. The defect is **pre-existing, not a merge
  regression**: the script and its contract test were both added on 2026-08-18
  and the file is community-owned (`git diff c3a75ae6 HEAD` on it was empty
  before this edit), so the documented build has been broken since the test
  landed. Graded **low risk** under AGENT.md rule 4 — deployment process, no
  shared-code behaviour change. Verified safe as well as effective: `scripts/`
  reaches only the discarded build stage (`scripts_dir_in_runtime=ABSENT`,
  `workspace_in_runtime=ABSENT`), the runtime image still contains just
  `/app/auto-wonder.jar` and `/app/logs`, and `scripts/` passes the
  internal-reference gate (`rg` exit 1 = no match).
- **Env-var quoting proven at three levels, as the step requires.** The two
  prescribed sources do **not** carry the same number of `&`, which an earlier
  draft of this bullet conflated: `application.env.example:1` carries **4** (line
  length 167, of which `SPRING_DATASOURCE_URL=` is 22, so the value is **145**
  chars), while `application-local.yml:27`'s *default* URL carries **3** because
  it omits the fifth param. Quoted, a JVM sees all of them intact — **145**
  chars / 4 `&` for the shipped value, **150** / 4 for the host-retargeted
  variant (`mysql:3306` → the compose-mapped host port, +5 chars), and Run B
  measured **145** / 4 *inside the container* from the unmodified shipped
  template, which is the reading that matters for the documented deployment.
  Unquoted in an **assignment**, bash yields an *empty* variable **and exit 0**
  while spawning four background jobs — the silent form that masquerades as a
  product defect, because Spring then falls back to the `application-local.yml`
  default `127.0.0.1:3306`, which in a compose deployment is the host, not the
  `mysql` container. Unquoted in **zsh** it is a loud parse error, and unquoted
  in **argument** position it is a loud exit 127; the silent danger is specific
  to assignment position. A first startup attempt returned exit 127 purely
  because this host has no `timeout(1)` or `gtimeout(1)`; that was my harness,
  java never ran, and it is recorded as void rather than as a result.
- **Handed over rather than fixed unilaterally** (three items, none blocking):
  (1) the schema footer still reads `-- 表清单（64 张）` while the file defines 65
  tables — equally wrong on master, no effect on executed DDL, and correcting it
  would create standing divergence in a file upstream edits every cycle; this stale
  footer is also where the previous "64 tables" figure in `verification.md` came
  from. (2) `docs/community/application.env.example` ships bare `KEY=value` with
  unquoted `&`. **The `docker run --env-file` half of this concern is now closed
  by measurement, not argument**: Run B executed `docs/community/README.md:73`
  verbatim against the **unmodified** template (`git diff --numstat` on it = 0
  lines) and the container read back `container_env_url_len=145`,
  `container_env_url_ampersands=4`, `container_env_url_host=mysql:3306`, so
  Docker's literal env-file parsing really does preserve all four `&`. The
  earlier claim that validating this was "impossible on a host with no container
  runtime" is withdrawn with the rest of that verdict. **What remains open is
  narrower and is still handed over:** the file silently yields an *empty* JDBC
  URL if a user does the natural host-install thing and `source`s it (measured:
  bash assignment position → `TRUNCATED_LEN=0` *with exit 0*), and
  `docs/community/README.md` does not warn against that. Single-quoting the
  values would remove the trap but must be validated against Docker's parser
  before shipping, and editing a shipped community template is a boundary change
  that a verification step should not make unilaterally. Recommended: either
  single-quote and re-run `README:73`, or add one README line saying the file is
  for `--env-file` only and must not be `source`d. (3) `frontend-maven-plugin` 1.11.3
  installs an x86_64 node under an arm64 JVM, so an Apple Silicon contributor who
  builds with Maven and then runs `npx vitest` with their own node hits
  `Cannot find module @rollup/rollup-darwin-arm64` and may well misreport it as a
  product defect; `docs/community/README.md`'s prerequisites should say so.

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
  passed on master's CI in August. **The companion clause recorded here — that it
  "fails on any run after the seeded dates" — is stale and is withdrawn on
  2026-09-07.** It described community's tree as it stood when written. Master
  fixed the same mechanism independently, at **scanner** level, in `21e28e0f4`
  (2026-09-05), while community had fixed it at **seed-INSERT** level in
  `0e76ae22f` (2026-09-02); the merge `616b84687` kept both. Pristine
  `origin/master` `b52cdeeea3b82316ee370e56d5533e6a8d9245b3` was therefore run on
  the targeted command `mvn -B -DskipGitCommitId=true -DskipFrontend=true
  -Dtest=ScheduledTaskSpringMybatisIntegrationTest,V037LegacyArtifactServiceFlowMySqlTest
  -DfailIfNoTests=false test` → `MVN_PRISTINE_EXIT=0`, `Tests run: 27, Failures: 0,
  Errors: 0, Skipped: 0`, `BUILD SUCCESS`, 3 containers created. Master passes, so
  there is no upstream bomb to inherit. The mechanism is nevertheless genuine and
  was verified in product code rather than assumed: `ScheduledTaskScheduler.java:78`
  (`Instant earliest = task.getGmtCreate() != null ? task.getGmtCreate().toInstant()
  : Instant.EPOCH;`), `:80` and `:87`, which drop occurrences earlier than that,
  with the no-op at `:44` (`if (occurrences.isEmpty()) return;`) making
  `claimAndFire` do nothing. `gmt_create` occurrences in the test file decompose
  exactly — merge base `25371cb1` = 1, community `0e76ae22f` added 2, master
  `21e28e0f4` added 2 plus its comment, so HEAD carries 5 at blob
  `720ccbb2cacb94d516145ecc685da62d98ab5c36`, identical to the merge commit's own
  blob, i.e. nothing touched the file afterwards. Community's two INSERT-level pins
  are not load-bearing at HEAD, so the strict union needs no repair: line 241's rows
  feed only `scheduledTaskListShapesUseTheTenantScopedDaoAndHaveAnExplainPlan`,
  whose four EXPLAIN queries contain 0 `gmt_create` references, and line 1349's row
  is overwritten on every scanner path by `resetActiveDueTask()`'s explicit
  `UPDATE … gmt_create='2026-08-01 00:00:00.000'`, while the paused-path tests
  never read the column.

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
