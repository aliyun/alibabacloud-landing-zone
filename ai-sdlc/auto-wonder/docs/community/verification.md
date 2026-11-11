# Community Verification

For current sync acceptance, use [the sync quality contract](sync-quality-contract.md).
The measurement history below is not evidence for a new candidate.

Verification date: 2026-09-07, with a **2026-09-08 re-measurement generation**
(release v0.8.0, candidate master baseline `b52cdeeea`)

**This table mixes three measurement generations, and every row names its own.**
The v0.8.0 release evidence was first measured at community sync-branch commit
`579225caf`. Step `400360` then re-measured most rows in a later attempt, at
whatever the delivery tip was at that moment; those rows say "re-measured" or
"re-run in this attempt" and carry their own exit codes. A **third generation was
measured on 2026-09-08** after the operator ruled on R7 and the Aone strip was
executed; it is collected in one place, *2026-09-08 re-measurement generation*,
and it supersedes the backend, frontend, Skill and Automated-Gates numbers wherever
the two disagree. Rows that were **not** re-run say `NOT RE-RUN` with the date they
were last measured, and are never reported as `PASS`. Do not read a `NOT RE-RUN`
row's numbers as current.

Three commits after `579225caf` touched non-Markdown files, so
**"documentation-only" is not true here and must not be assumed**:

* `APP-META/docker-config/Dockerfile` — one added line, `COPY scripts ./scripts`,
  so the documented `docker build` can see the release-gate script; attribution and
  the safety argument are in the *Linux image build* row.
* `frontend/package-lock.json` — 23 dependency entries restored, 0 removed, so a
  clean `npm ci` works again. No frontend source moved: the `frontend/src` tree
  hash and the `frontend/package.json` blob are both identical to `579225caf`. The
  frontend rows below were re-measured against the repaired lock with a freshly
  installed `node_modules`, and the full-build row records the result on the
  documented command with `-DskipFrontend` **not** set.
* `docs/autowonder-schema.sql` — comments re-pointed at community migration numbers.
  Over `579225caf..HEAD` zero changed lines are not SQL comments.

Re-check every sentence above instead of trusting it:

```bash
for P in src frontend skills pom.xml APP-META docs/migration; do
  git ls-tree 579225caf -- "$P"; git ls-tree HEAD -- "$P"
done
git ls-tree 579225caf -- frontend/src frontend/package.json
git ls-tree HEAD      -- frontend/src frontend/package.json
git diff --name-only 579225caf..HEAD
git diff -U0 579225caf..HEAD -- docs/autowonder-schema.sql \
  | grep -E '^[+-]' | grep -vE '^(\+\+\+|---)' | grep -vcE '^[+-][[:space:]]*--'
```

Three paths differ, not two: `frontend`, `APP-META` and `docs/autowonder-schema.sql`.
`src`, `skills`, `pom.xml` and `docs/migration` are still identical. That last
command must print `0` over `579225caf..HEAD` but `10` over `b52cdeeea..HEAD`; see
*Database schema*. `git diff --name-only` lists seven files: those three plus four
Markdown files under `docs/community/` and `releases/`.

This header names no branch tip: a commit moves it, so any tip here is already stale.

Every `PASS`/`FAIL` row below was measured in this cycle and is supported by the
build tool's own exit code and its own summary line, captured into a named
variable on the line immediately after the tool ran. No result in this file was
carried forward from an earlier cycle unless the row explicitly says
`NOT RE-RUN` and states when it was last measured. A `NOT RE-RUN` row is never
reported as `PASS`.

Toolchains actually used — there are **two**, and the difference is material:

* Machine toolchain: Node `v26.4.0` (arm64), npm `11.17.0`, OpenJDK `21.0.11`,
  Maven `3.9.16`, Python `3.10.20`, pytest `9.1.1`, ripgrep `15.0.0`.
* Build toolchain, installed by `frontend-maven-plugin` into `target/node`: Node
  `v22.22.2` (**x86_64** — the plugin 1.11.3 predates Apple Silicon), npm
  `10.9.7`. This is what `docs/community/README.md:69` actually builds with, and
  what the frontend gates below must use. npm `11.17.0` accepted a lock file that
  npm `10.9.7` rejected with 23 missing entries, so naming only one toolchain here
  would hide a real failure mode.

**The container-runtime premise changed during this cycle, and the earlier
sentence in this file was corrected rather than left standing.** When the v0.8.0
evidence was first measured this host had no container runtime of any kind — no
Docker, Podman, Colima, nerdctl, containerd or lima, no container socket, and
`DOCKER_HOST` unset. That is no longer true. Docker Desktop is now running:
`/Users/honeyfamily/.docker/run/docker.sock` exists, a raw `GET /info` over that
socket returns a full 59-key object with `ServerVersion 29.7.2`, and Testcontainers
logs `Found Docker environment with Docker accessed via Unix socket
(/Users/honeyfamily/.docker/run/docker.sock)` followed by `Connected to docker`.
`/var/run/docker.sock` is still absent and `DOCKER_HOST` is still unset by default,
so the discovery path is the Docker Desktop socket. The `docker` CLI **does** exist,
at `$HOME/.docker/bin/docker` (client and server `29.7.2`, `ApiVersion 1.55`,
`MinAPIVersion 1.40`, `linux/arm64`, containerd `v2.3.3`, runc `1.4.3`, 8 CPUs,
7.748 GiB, overlayfs; `docker compose` v5.5.0) — it is simply not on the default
`PATH`. Earlier text in this file said there was no CLI; that was wrong and is
corrected here. Any script that drives the daemon must export both
`PATH="$HOME/.docker/bin:$PATH"` and
`DOCKER_HOST=unix://$HOME/.docker/run/docker.sock` itself.

**Registry egress is selectively filtered, not absent.** `docker.io` is
unreachable — its name resolution is sinkholed and `curl` against it exits 28 —
so no Docker Hub image can be pulled on this host, including
`testcontainers/ryuk:0.11.0` and the `docker/dockerfile:1` BuildKit frontend.
These registries answer normally: `public.ecr.aws`, `quay.io`, `ghcr.io`,
`registry.cn-hangzhou.aliyuncs.com`, `repo.maven.apache.org` and
`registry.npmjs.org`. Pulling from `public.ecr.aws/docker/library/...` and
`quay.io/minio/...` and then `docker tag`-ing the result back to the Docker Hub
name lets `docs/community/docker-compose.dependencies.yml` run **completely
unmodified** — the compose file was never edited to work around this host, because
editing it would have been an environment-driven product change. The AWS mirror
also carries the Dockerfile's digest-pinned JRE at the identical digest
`sha256:468586c92d39f8cbad76574623db3fe001625ed4d895431c3ff7bd2ec9ce7ae3`.

**Testcontainers discovery on this host looked bimodal; it is deterministic, and the
root cause is now measured rather than described.** Testcontainers `1.20.6` *shades*
`docker-java`, and shaded `docker-java`'s environment-variable whitelist — thirteen
`DOCKER_*` names, enumerated in `step400360-attempt1-affected-checks.txt` — does
**not** contain `DOCKER_API_VERSION`. Exporting that variable is therefore a no-op
(proved: the env-var attempt failed), and `docker-java` negotiates its default
`RemoteApiVersion` `1.32` against a daemon advertising `MinAPIVersion 1.40`, which
answers `/info` with `Status 400` and an all-empty `ServerVersion` body. The only
channel that reaches shaded `docker-java` is a JVM system property, delivered to the
surefire fork by `-DargLine="-Dapi.version=1.44"`. With it, discovery succeeds
deterministically and **this cycle obtained the pass that the earlier "neither
outcome is a pass" sentence said was unavailable: `MVN_CONTAINERS_EXIT=0`,
`BUILD SUCCESS`, `Tests run: 3212, Failures: 0, Errors: 0, Skipped: 1`,
`^[ERROR]` count 0, `CONTAINERS_CREATED=8`, jar 73045077 bytes.** That sentence is
withdrawn. The count reconciles exactly: 3171 − 7 + 48 = 3212. Two other outcomes
stay recorded because a reader repeating the plain documented command will hit one
of them: `MVN_FULL_EXIT=1` with `Errors: 7`, all seven being `ContainerFetch` on
Docker Hub pulls, and `MVN_MIRROR_EXIT=0` with `Skipped: 8` after pre-pulling the
images — the latter is **refused as a pass**, because its 8 skips are exactly the 8
Docker-backed classes. `-DargLine=…` is a *verifier* override, not a project
setting: the documented command still fails on any host whose daemon minimum
exceeds `1.32`. Testcontainers is pinned `1.20.6` identically at HEAD, pre-sync
community `c3a75ae6` and pristine `origin/master` `b52cdeeea`, so upgrading it
would change a master-owned dependency version and is handed over as a finding.
Ryuk's pull failure is separately caused by Docker Hub egress being blocked and
disappears with `TESTCONTAINERS_RYUK_DISABLED=true`; `DOCKER_HOST=tcp://127.0.0.1:1`
does not disable discovery — it logs `not listening` and **falls through** anyway.

There is still no `mysql` or `redis-server` **binary** on this host, but that no
longer blocks anything: both were run as containers this cycle, so the schema
import and the local startup smoke **were** performed and their rows below are now
live measurements rather than `NOT RE-RUN`. Object storage has no runtime fallback
— `ObjectStorageConfig` declares two `@ConditionalOnProperty` beans plus an
*unconditional* `taskPackager(ObjectStorage, ...)` bean, so the context cannot
start without a backend — and the repo-sanctioned local path from
`docs/autowonder-s3-storage.md` §7 (`oss.enabled=false` + `s3.enabled=true` against
MinIO) was used, since `terraform`, `ossutil` and `aliyun` remain absent and
`~/.aliyun/config.json` still does not exist and was deliberately not created.

**Beyond the recorded community adaptation, this cycle produced three unplanned
changes to the tree, not one — an earlier version of this paragraph said one and
is corrected from `git show --stat` on each commit.** The adaptation itself is
`2813b524f` (`VERSION` 0.7.0→0.8.0, this release's notes, and the four deploy/upgrade
Skill version files). The three additions on top of it are:

1. `6fa57221a` — `frontend/package-lock.json`, **361 insertions, 0 deletions**: the
   23 lock subtrees npm 11's optional-peer pruning had dropped, without which the
   plugin's npm 10.9.7 refuses `npm ci`. See the *Frontend install* row.
2. `fcaec187d` — `docs/autowonder-schema.sql`, **4+/3−**, all of it SQL comments:
   three migration references still cited upstream numbering. See the Rule-11 row
   in the release notes; no rule in the sync guide names that file, which is a
   coverage gap handed over.
3. `624bde76e` — `APP-META/docker-config/Dockerfile`, **1 insertion**:
   `COPY scripts ./scripts`, because the documented `docker build` at
   `docs/community/README.md:70` could not otherwise succeed — the build stage never
   copied `scripts/`, while `V037DockerReleaseGateScriptContractTest` resolves
   `scripts/verify-v037-docker-gates.sh` relative to the process working directory.
   Full attribution, risk grading and the safety argument — the added directory
   reaches only the discarded build stage, never the shipped runtime image — are in
   the *Linux image build* row below.

All three are repairs to defects that predate this sync and were found by running
the documented commands rather than by reading the diff. A reviewer diffing this
branch against pre-sync community should expect the merge, the adaptation commit,
those three repairs, the **operator-ruled R7 Aone strip** of 2026-09-08 (two Java
paths restored byte-identical to pre-sync community; see the section below), and the
documentation set (`docs/community/upstream-sync-log.md`, this file and
`releases/release_v0.8.0_20260907.md`) — and nothing else.

## 2026-09-08 re-measurement generation

**These are the current numbers.** They were measured on 2026-09-08 in the delivery
worktree, *after* the operator ruled on R7 (comment `126197`:
「如果是aone的新功能的话，就剥离，如果是存量功能的话就保留」) and *after* the
resulting strip of the new Aone mention-wake feature. Where a row in *Completed
Evidence* below disagrees with a row here, **this section wins**, and the older row
is a prior generation kept for continuity rather than deleted.

Every exit code in this section was captured into a named variable on the line
immediately after the tool ran, from the tool itself, and each result quotes the
tool's own summary line. No verdict here rests on a pipeline's exit status.

Two facts make these numbers comparable to the accepted 2026-09-07 generation
rather than merely adjacent to it:

* **The tree measured is the tree the second internal MR will carry.** The worktree
  differs from HEAD in exactly four paths — `docs/community/upstream-sync-log.md`,
  `docs/community/verification.md`, `releases/release_v0.8.0_20260907.md` and the
  two stripped Java files — and **zero of them are frontend paths**.
* **`frontend/` and `skills/` could not have moved.** The `frontend/` tree hash is
  `6235c7e5ad11b18b7ba0bed1875a38227ea4c541` at HEAD *and* at `origin/community`;
  the `skills/` subtree hash is `91ad71bc7f2c4c226fadf2b20b8dd86511c0c409` at HEAD,
  `origin/community` and the worktree, which is the same hash the accepted Skill
  failure classification was measured at. So the frontend and Skill rows below
  re-measure an unchanged subtree; only the backend rows measure genuinely new bytes.

**Verifier-supplied environment overrides — stated first because every backend
number below depends on them:** `DOCKER_HOST` pointed at the Docker Desktop socket,
`TESTCONTAINERS_RYUK_DISABLED=true`, `-DargLine="-Dapi.version=1.44"`, and
`mysql:8.4.4` / `redis:7-alpine` pre-pulled from a reachable mirror and retagged to
their Docker Hub names. These are **not project settings, are not committed
anywhere, and are not what a contributor runs.** They exist only because this host
filters Docker Hub egress and because shaded docker-java inside the pinned
Testcontainers `1.20.6` negotiates `RemoteApiVersion 1.32` against a daemon whose
`MinAPIVersion` is `1.40`. The documented plain command still fails on such a host,
and that is a pre-existing property of the pinned Testcontainers version, not
something this sync introduced. Disabling Ryuk also means **no reaper**, so
containers were removed by hand. A contributor with Docker Hub access runs
`docs/community/README.md:69` verbatim.

| Check | Result | Measured on 2026-09-08 |
| --- | --- | --- |
| Full documented build, containers executed | **PASS** | `mvn -B -DskipGitCommitId=true clean verify -DargLine=-Dapi.version=1.44` → **`MVN_CONTAINERS_EXIT=0`**, `[INFO] BUILD SUCCESS`, `Total time: 02:54 min`, `Finished at: 2026-09-08T12:26:32+08:00`. Aggregate at log line 8488: **`[WARNING] Tests run: 3211, Failures: 0, Errors: 0, Skipped: 1`**. `grep -c '^\[ERROR\]'` over the whole 8510-line log = **0**. `CONTAINERS_CREATED=8` (7 × `mysql:8.4.4`, 1 × `redis:7-alpine`). `target/auto-wonder.jar` produced at **73044853** bytes. The frontend half ran inside it, not skipped: `Installing node version v22.22.2`, `npm ci --include=dev --include=optional --cache ../target/npm-cache` → `added 739 packages, and audited 740 packages in 30s`, `npm run build` → `vite v6.4.3 building for production...` → `✓ 4923 modules transformed.` → `✓ built in 9.39s`, and `antrun … verify-frontend-static-assets` executed. **371** suites reported. 63 application-level `\|ERROR\|` lines are present and are *not* build errors — the same count as the accepted decisive run; Maven's own `^[ERROR]` count is 0 |
| Backend test-count reconciliation | **PASS — closed against a prediction written before Maven ran** | The log header carries `PRIOR_ACCEPTED_BASELINE=Tests run: 3212, Failures: 0, Errors: 0, Skipped: 1, CONTAINERS_CREATED=8, exit 0` and `PREDICTION=Tests run: 3211 (3212 minus the 1 deleted Aone feature test), Failures: 0, Errors: 0, Skipped: 1`. Both lines were written **before** the build started; the observed aggregate is exactly 3211/0/0/1. The single skip is line 1995, `Tests run: 1, … Skipped: 1 … com.aliyun.autowonder.integration.aone.AoneUserApiManualTest` — the documented manual test, and the only skip in the run. No test count moved for any other reason |
| cl_3 protection: no assertion lowered, no test skipped to go green | **PASS** | The 3212 → 3211 delta is **exactly one `@Test` method**, and it is the test *of the removed capability*. Master's `AoneInboundSyncServiceTest` has 27 `@Test` methods, the final file has 26, and the method-name comm difference is empty in one direction only: `ONLY_IN_MASTER = refreshIssueIdsCreatesGuidanceForNewInboundMention`, **`ONLY_IN_FINAL = 0`**. Against **pre-sync community** `c3a75ae6` the method set is identical in *both* directions (`PRESYNC_ONLY=0`, `FINAL_ONLY=0`) and the file is byte-identical. Frontend total is unchanged at **1469** — no frontend test was added, removed, skipped or excluded |
| Frontend install | **PASS** | `npm ci --include=dev --include=optional` under the plugin toolchain (`NODE_VER=v22.22.2`, `NPM_VER=10.9.7`, resolved via `export PATH="../target/node:$PATH"`) → **`NPM_CI_EXIT=0`**, `added 739 packages, and audited 740 packages in 10s`, `1 high severity vulnerability` — the same pre-existing `nanoid <3.3.18` finding recorded in *npm audit posture*, unchanged |
| Frontend tests | **FAIL (attributed; zero sync-introduced regressions)** | `npm run test:run` → **`NPM_TEST_EXIT=1`**, `Test Files 3 failed \| 144 passed (147)`, **`Tests 10 failed \| 1459 passed (1469)`**, `Start at 12:31:27`, `Duration 223.28s`. That exit code is vitest's own. The 10 are: **6** in `src/features/executor/ExecutorListPage.test.tsx` (Context-Window persistence and Qoder startup-preference cases; 5 × `Unable to find role="button" and name /启动命令/`, 1 × `Unable to find an element with the text: runner-a`), **3** in `src/features/workitem/WorkitemDetailPage.test.tsx` (clarify panel width, and two clarify-URL-persistence cases), and **1** in `src/features/statemachine/StatusTemplatePage.test.tsx`. Attribution is in the prose below this table. **This suite is not green and is not reported as green** |
| Frontend tenth failure — **the slot rotates** | **NOTED — corrects the prior attribution, which named one fixed test** | This run's tenth failure was `StatusTemplatePage.test.tsx > keeps node creation visible but does not open its modal for a read-only member`, *not* the `AllWorkspacesTab` case the accepted generation named — and `AllWorkspacesTab` **passed** in this run. Both files pass in isolation on the same machine in the same hour: `npx vitest run src/features/statemachine/StatusTemplatePage.test.tsx` → **`ISO_STP_EXIT=0`**, `Test Files 1 passed (1)`, `Tests 3 passed (3)`, 6.25s; `npx vitest run src/features/auth/AllWorkspacesTab.test.tsx` → **`ISO_AWT_EXIT=0`**, `Test Files 1 passed (1)`, `Tests 25 passed (25)`, 9.39s. The failing DOM dump (610 lines) ends in `ant-spin-dot-item`, i.e. the page was still loading when `findByRole` timed out at test line 97, and the string `添加节点` occurs exactly **once** in that dump — inside the expected error message, never in the rendered tree. That is the same timeout mechanism as the accepted generation's `Unable to find an element with the text: 别的空间`. **Conclusion: the tenth failure is a rotating load-sensitive slot, not a fixed defective test.** The classification is unchanged (concurrency/load sensitive, not a functional defect, not a sync regression) but the earlier attribution to one named file was over-specific and is corrected here. This is *stronger* evidence for load-sensitivity than a single repeated observation was |
| Frontend deterministic nine, re-measured in isolation | **FAIL (pre-existing, both groups reproduced on the current tree)** | `npx vitest run src/features/executor/ExecutorListPage.test.tsx` → **`HEAD_EXECUTOR_EXIT=1`**, `Tests 6 failed \| 56 passed (62)`, 37.34s, same six names. `npx vitest run src/features/workitem/WorkitemDetailPage.test.tsx` → **`HEAD_WDP_EXIT=1`**, `Tests 3 failed \| 63 passed (66)`, 33.39s, same three names. Both reproduce the accepted figures exactly and prove the groups are deterministic rather than load dependent. The 6-name group's identity with pristine `origin/master`, and the 3-name group's identity with the `master-comtoolchain-wt` reproduction (community's own toolchain bump `86e3614ff`), were measured in the accepted generation and carry forward **on bit-identity, not on trust**: the `frontend/` tree hash is unchanged, so those reproductions still describe this tree |
| Frontend lint | **PASS** | `npm run lint` → **`NPM_LINT_EXIT=0`**, `✖ 2 problems (0 errors, 2 warnings)`, at `MemoryListPage.tsx:78:9` and `RightPanel.tsx:114:6` — the same two pre-existing `react-hooks/exhaustive-deps` warnings, same files, same lines |
| Frontend production build | **PASS** | `npm run build` (`tsc -b && vite build`) → **`NPM_BUILD_EXIT=0`**, `✓ 4923 modules transformed.`, `✓ built in 10.53s`, and the artefact inventory is **identical including content hashes**: `dist/index.html 0.60 kB │ gzip: 0.32 kB`, `dist/assets/index-Bn8zSGCu.css 20.14 kB │ gzip: 4.18 kB`, `dist/assets/index-sMm4vivb.js 4,147.58 kB │ gzip: 1,251.01 kB`. Same module count, same three asset filenames — so no module entered or left the bundle graph and only wall-clock moved |
| Community boundary tests | **PASS** | Inside the build above: `CommunityBuildInputTest` **4/0/0** (log line 147), `CommunityDependencyBoundaryTest` **2/0/0** (line 149), `PlatformBrandingServiceTest` **20/0/0**, `V037DockerReleaseGateScriptContractTest` **2/0/0**, `ExecutorSchemaContractTest` **1/0/0**, `SystemSettingServiceTest` **14/0/0** (line 7932), `ConversationElicitationSchemaContractTest` **2/0/0**, `ScheduledTaskSchemaContractTest` **2/0/0**, `S3ObjectStorageTest` **13/0/0**, `InMemoryObjectStorageTest` **3/0/0** — every one `Failures: 0, Errors: 0`. The two Docker-backed MySQL/Redis suites **executed rather than skipped** this generation (`ScheduledTaskSpringMybatisIntegrationTest` 26/0/0, `V037SchemaCapabilityDetectorMySqlTest` 7/0/0) |
| Dependency tree scan and Gate A | **PASS** | `mvn -B -DskipFrontend=true -DskipGitCommitId=true dependency:tree` → **`DEPTREE_MVN_EXIT=0`**, `BUILD SUCCESS`, `Total time: 0.925 s`, `^[ERROR]` = 0, `^[WARNING]` = 0, **185** tree-glyph lines — the same figure as the accepted generation, so no artifact entered or left the resolved graph. Gate A's own pattern over that output → **`GATE_A_RG_EXIT=1`**, 0 matches, which is the pass condition for a negative gate and was captured from `rg` itself. **Conclusion unchanged: the community tree needs no internal SDK, no internal Maven repository and no internal npm registry to compile, install or bundle** — proved again by `npm ci` resolving 739 packages from `registry.npmjs.org` alone |
| Gate B, internal references in shipped code | **PASS** | `rg -i` over `pom.xml frontend/package-lock.json frontend/.npmrc APP-META src/main src/main/resources` → **`GATE_B_RG_EXIT=1`**, 0 hits, 0 stderr lines. A positive control run in the same invocation over the out-of-scope paths gave **`CONTROL_RG_EXIT=0`** with 4 hits, which proves the pattern works and that the zero is the scope exclusion, not a broken regex |
| Gate B surface — **corrected twice, and this row was itself an instance of the defect it describes** | **NOTED (feeds H-1)** | Two patterns were run and both are reported, because they answer different questions: the **plain** host pattern, which finds *live* literals, and an **optional-backslash** variant, which additionally finds the same hosts written in *escaped-regex form* — the form a gate's own pattern text necessarily takes inside the document that states it. That second form is what the accepted generation's control of **4** missed, so its 4 was correct for the plain pattern and an undercount only for the quotations. **Reading 1, before this row was fixed:** plain → `PLAIN_EXIT=0`, **5 files / 5 lines**; variant → `ESC_EXIT=0`, **5 files / 9 lines**. The extra fifth live literal was **introduced by this very row's first draft**, which spelled out an internal *test-fixture* host in order to describe someone else's use of it, and which was the first live host literal ever to appear in this file — `git show HEAD:docs/community/verification.md` returns **0** plain-pattern hits, so it entered only in this step's uncommitted edit. **Reading 2, after removing it and re-measuring rather than subtracting:** plain → **4 files / 4 lines**; variant → **5 files / 8 lines**; `verification.md` plain hits → **0**. **That sequence is the finding, not a footnote: a document written to report a scope blind spot fell into it on the first attempt, which is direct evidence the blind spot is easy to enter and hard to see.** The 4 surviving live literals classify **3 + 1**: three internal code-review URLs — `releases/release_v0.3.5_20260809.md` and `releases/release_v0.7.0_20260902.md` (both published precedent, already in the community tree) and this release's own MR/PR link in `releases/release_v0.8.0_20260907.md` (**new**, and mandated by the sync guide); plus one pre-existing internal test-fixture host in `docs/community/upstream-sync-log.md`, named there while recording that the same host had been redacted out of a frontend test file — i.e. the redaction was documented by spelling the redacted string. The other **4** lines the variant finds are the gate's own pattern text quoted in the *Internal-reference scan* row and the *Automated Gates* block of this file, the cl_2 row of `releases/release_v0.8.0_20260907.md`, and a quotation of the same command in `docs/community/upstream-sync-log.md`; a gate pattern must name what it forbids, so redacting those would break the gate. No ruling was needed to remove the fifth literal, because Rule 8 forbids *introducing* an internal endpoint regardless of how H-1 is decided; **the other four are left untouched pending the operator's H-1 ruling rather than redacted unilaterally.** **The exposure H-1 actually turns on is therefore 3 code-review URLs (2 already public, 1 new) plus 1 pre-existing fixture-host mention — not 9, not 8, and not 4.** Locations are cited by file and row/section name rather than line number, per this file's own convention that a line citation is stale the moment the file is rewritten. Gate B itself re-confirmed after the fix: `GATE_B_RG_EXIT=1`, 0 hits |
| Credential-shape residual | **PASS** | **`RESIDUAL_SENSITIVE_EXIT=1`**, 0 hits, run **case-sensitively** per the rule recorded in the *Internal-reference scan* row (`-i` turns `ltaIngesti` from `EvolutionDeltaIngestionLiteService` into a false `LTAI[0-9A-Za-z]{12,}` match) |
| Rule 12 runtime version | **PASS** | Residual scan for the superseded literal → **`RESIDUAL_RG_EXIT=1`**, `HITS=0` for `0.2.150`; **9** occurrences of `0.2.152` across the five enumerated sources plus `PlatformBrandingService.java`. Not re-listed file-by-file here; the *Rule 12 runtime version* row below carries the per-line inventory and is unchanged because none of those files moved |
| cl_2 broad internal-reference rescan | **PASS — the strip introduced and removed zero code-level references** | Run with the **exact** pattern and normalization recorded in the step-`400359` evidence (`git grep -IE`, ref prefix stripped with `sed`, `sort -u`), with the one internal-host branch replaced by a blob-identity proof so no such literal entered a command argument. **The figure that matters is code-scope, and it does not move: `CODE = 39` at pre-sync community `c3a75ae6`, at the merge, at HEAD and in this worktree, with every bidirectional `comm` set delta = `0`** — so the strip neither introduced nor removed a single code-level reference. Code scope excludes `docs/` and `releases/`, which is why it is stable while the tree-wide count is not. **Tree-wide, per ref, measured rather than extrapolated:** `c3a75ae6` RAW 65 / DEDUP 62 · `616b84687` 65 / 62 (the merge added **zero**) · `2813b524f` 73 / 70 · `fe22d55dc` 81 / 78 · `17fc0bdaf` 83 / 80 · `579225caf` 83 / 80 · **HEAD `6c297bf6e` 95 / 92** · `origin/community` 95 / 92, with both `comm` deltas against HEAD = `0` and `SET_IDENTICAL=yes` · **this worktree 96 / 93**. These supersede the accepted generation's anchored `RAW=89 / DEDUP=86`, which was measured at an earlier tip. **The worktree figure is stated last and with its cause named, because this row cannot quote an absolute that its own text perturbs:** the +1 over HEAD is this section's own new rows, which *name* the excluded internal dependencies precisely in order to record their exclusion — the same mechanism the release file's cl_2 row describes. Rewriting a line that already matches cannot raise the count, and this row was re-measured after being corrected rather than adjusted by the expected delta; that correction is why the reading is 96 / 93 and not the 97 / 94 taken before the *Gate B surface* row above was fixed. `KeyCenterClient` occurrences in `src` + `pom.xml` = **0**. Controls prove the scan is live: `CTRL_KeyCenter=40`, `CTRL_Cryptograph=5`, `CTRL_sigma_app_name=1` |
| Deployment Skill tests | **FAIL (pre-existing, carried forward on bit-identity and re-run anyway)** | `cd skills/deploying-autowonder-on-alibaba-cloud && python3 -m pytest tests -q` → **`DEPLOY_PYTEST_EXIT=1`**, `29 failed, 72 passed in 28.54s`. Counts identical to the accepted generation and to the pre-sync baseline, whose bidirectional name-set comparison gave `DEPLOY_FAILSET_EQ_PRESYNC=YES`. The `skills/` subtree hash is unchanged (`91ad71bc…`) and no worktree modification touches `skills/`, so the accepted four-class root-cause split still describes these 29 exactly |
| Upgrade Skill tests | **FAIL (pre-existing, carried forward on bit-identity and re-run anyway)** | `cd skills/upgrading-autowonder-on-alibaba-cloud && python3 -m pytest tests -q` → **`UPGRADE_PYTEST_EXIT=1`**, `6 failed, 66 passed, 1 skipped in 26.00s`. All 6 in `tests/test_split_contract.py`; the 1 skip is the PowerShell-gated Windows adapter test on this macOS host. Same counts as the accepted generation, which recorded `UPGRADE_FAILSET_EQ_PRESYNC=YES` |

**Net attribution for this generation: 6 upstream defects + 3 community-toolchain
defects + 1 rotating load-sensitive flake = zero sync-introduced regressions.** The
operator's demand for a 100% frontend pass rate **cannot be met inside this sync's
mandate**: the 6 belong to `origin/master` and fixing them in community would
violate Rule 3 (master owns product behaviour) — they must be fixed on master; the
3 are community's own pre-existing debt from the toolchain bump in `86e3614ff` and
repairing them here would be 扩大无关重构; and the tenth is not a defect at all.
That is a decision for the operator, not a number to be adjusted.

**NOT RE-RUN in this generation**, each last measured 2026-09-07 and none reported
as `PASS` here: the standalone `-DskipFrontend` backend package run; the
pristine-`origin/master` side of the backend regression comparison; Rule 11
migration byte-identity over all five renumbered files; the environment-variable
contract counts; the live database-schema import; the local startup smoke (both
runs); the Linux image build; runtime image identity; runtime base image digest;
directory-consistency and file-mode counts; the `git archive` export round-trip;
and the `npm audit` detail beyond the single high finding `npm ci` itself printed.
The last two of those **must** be re-measured after the strip is committed, because
both are whole-tree counts taken at the pre-strip HEAD, and the file inventory and
path count can move. They are not re-quoted as current until they are.

## Completed Evidence

| Check | Result | Evidence |
| --- | --- | --- |
| Backend package | Superseded by the full-build row below, which is **PASS** and produced the JAR | The standalone `mvn -B -DskipGitCommitId=true -DskipFrontend=true clean verify` was **NOT RE-RUN** in this attempt; last measured 2026-09-07 earlier in this cycle at `MVN_EXIT=0`, `Tests run: 3171, Failures: 0, Errors: 0, Skipped: 8`, `BUILD SUCCESS`, `Total time: 35.483 s`. **The explanation recorded here earlier — that this `Skipped: 8` / exit-0 outcome and the `Errors: 7` outcome were two branches of a "bimodal" host — is withdrawn**, along with the claim that `Skipped: 8` was "no longer reproducible". All three backend runs of this attempt are explained by one deterministic cause, shaded docker-java negotiating `RemoteApiVersion 1.32` against a daemon whose `MinAPIVersion` is `1.40`, described in the container-runtime paragraph above. What this attempt actually measured, each exit code captured from Maven itself: plain `mvn verify` → `MVN_FULL_EXIT=1`, `Tests run: 3171, Failures: 0, Errors: 7, Skipped: 8`, `BUILD FAILURE`, all 7 errors `ContainerFetch` on Docker Hub pulls; the same with the images pre-pulled → `MVN_MIRROR_EXIT=0`, `3171/0/0/8`, `BUILD SUCCESS`, **refused as a pass** because those 8 skips are exactly the 8 Docker-backed classes; and with `-DargLine="-Dapi.version=1.44"` → `MVN_CONTAINERS_EXIT=0`, `[WARNING] Tests run: 3212, Failures: 0, Errors: 0, Skipped: 1`, `BUILD SUCCESS`, `CONTAINERS_CREATED=8`, jar 73045077 B. This command still **cannot detect a broken frontend lock**, because `-DskipFrontend=true` suppresses all three `frontend-maven-plugin` executions, so the full-build row is the one that matters |
| Full documented build | **PASS** | `mvn -B -DskipGitCommitId=true clean verify`, i.e. `docs/community/README.md:69` with `-DskipFrontend` **not** set. **This attempt's decisive run** was made at HEAD `a144a5da0e5d8e38128fc891fb4acaefb68b70c2` on `aw/community-sync-b52cdeeea-20260906` with `DOCKER_HOST` exported to the Docker Desktop socket, `TESTCONTAINERS_RYUK_DISABLED=true` and `-DargLine="-Dapi.version=1.44"`: **`MVN_CONTAINERS_EXIT=0`**, `BUILD SUCCESS`, `[WARNING] Tests run: 3212, Failures: 0, Errors: 0, Skipped: 1`, `grep -c '^\[ERROR\]'` over the whole log = **0**, `CONTAINERS_CREATED=8`, and **`target/auto-wonder.jar` produced at 73045077 bytes** — the same artefact size the earlier reading produced, and the artifact the smoke test below then started. That count reconciles exactly against the two rejected readings of this same command: **3171 − 7 + 48 = 3212**, because the first run executed 3171 tests with 7 erroring inside container setup, the decisive run executed those classes fully, and the container-backed classes contribute 48 more individual test methods than the errored stubs did — no test appears or disappears unexplained. **Two readings of this command were refused as passes rather than published as green:** plain → `MVN_FULL_EXIT=1`, `Tests run: 3171, Failures: 0, Errors: 7, Skipped: 8`, `BUILD FAILURE`, all 7 errors `ContainerFetch` on Docker Hub pulls; and the same with the images pre-pulled → `MVN_MIRROR_EXIT=0`, `3171/0/0/8`, `BUILD SUCCESS`, whose 8 skips are exactly the 8 Docker-backed classes, so it proves compilation and every non-container test and proves nothing about the container paths. Reporting that second run as green would have been the single easiest way to publish a false pass this cycle. Frontend half green: `Installing node version v22.22.2`, `npm ci --include=dev --include=optional --cache ../target/npm-cache` → `added 739 packages, and audited 740 packages`, `npm run build` → `vite v6.4.3 building for production...` → `✓ 4923 modules transformed.`. This attempt re-measured those two commands standalone under the plugin toolchain at `NPM_CI_EXIT=0` / `added 739 packages, and audited 740 packages in 13s` and `BUILD_EXIT=0` / `✓ built in 9.85s`; the in-build readings from the prior full build were `1m` and `✓ built in 9.94s`. 372 suites ran in that prior reading, a count **NOT RE-RUN** here. Every community gate is green in both: `CommunityBuildInputTest` 4/0/0, `CommunityDependencyBoundaryTest` 2/0/0, `PlatformBrandingServiceTest` 20/0/0, `V037DockerReleaseGateScriptContractTest` 2/0/0, `ExecutorSchemaContractTest` 1/0/0, `SystemSettingServiceTest` 14/0/0, `ConversationElicitationSchemaContractTest` 2/0/0, `ScheduledTaskSchemaContractTest` 2/0/0, `S3ObjectStorageTest` 13/0/0, `InMemoryObjectStorageTest` 3/0/0. **Two things in this log must not be misread.** First, it contains **63 application-level `|ERROR|` lines and they are not build errors** — Maven's own `^[ERROR]` count is 0, and the 63 are unit tests deliberately driving failure paths, emitted by `DispatchService`, `ImNotificationWorker`, `UserImIdentityService`, `PlatformImChannelConfigService`, the workspace and workitem notification listeners, `S3ObjectStorage`, `AliyunOssObjectStorage` and others. That is exactly one fewer than the 64 in the rejected mirror run, and the single differing message is `DockerClientProviderStrategy|main|Could not find a valid Docker environment. Please check configuration.` — which only the mirror run could emit, because only its discovery failed; 54 of the 55 distinct normalized messages are identical between the two logs, and the one other apparent difference is a UUID-shape artifact of my own `s/[0-9]+/N/g` normalization applied to a random testId. **The delta is therefore corroborating evidence that the decisive run really had a live daemon, not a regression.** `|ERROR|` also occurs 0 times in `src/main` at HEAD and 0 times at sealed tip `7d4ae7bcd`, so no product source changed shape here. Second, **`-DargLine="-Dapi.version=1.44"` is a diagnostic override supplied by the verifier, not a project setting**: the documented plain command still fails on any host whose daemon `MinAPIVersion` exceeds shaded docker-java's `1.32` default, which is a pre-existing property of the pinned Testcontainers `1.20.6` and not something this sync introduced. Unlike every earlier reading, this run's MySQL 8.4.4 / Redis 7 suites **executed rather than skipped** (`Skipped: 1`, `CONTAINERS_CREATED=8`), so the live smoke below is now corroboration of a proven suite result rather than the only proof of the DDL. **Prior readings, kept for continuity:** at `9856387065acdbdd43cf1873eaeb9b1d824f0191`, started `2026-09-07T05:23:29Z`, `MVN_VERIFY_EXIT=0`, `Tests run: 3171, Failures: 0, Errors: 0, Skipped: 8`, `Total time: 02:32 min`, `Finished at: 2026-09-07T13:26:03+08:00`, `ContainerFetch` = 0, 64 `|ERROR|` lines (`DispatchService` 14, `ImNotificationWorker` 8, `UserImIdentityService` 6, `PlatformImChannelConfigService` 4, `S3ObjectStorage` 2, `AliyunOssObjectStorage` 2 and others), jar 73045077 B — and in that run the two Docker-backed suites **skipped rather than passed**, Testcontainers logging `Could not find a valid Docker environment` at `13:25:48` so their `assumeTrue` guard was false and their contracts were left unproven *as suites*. Two runs at 10:58:55 and 11:30:05 gave `MVN_VERIFY_EXIT=1` with `Tests run: 3171, Failures: 0, Errors: 2, Skipped: 6` and `ContainerFetchException: Can't get Docker image: RemoteDockerImage(imageName=testcontainers/ryuk:0.11.0)`, which is removed by `TESTCONTAINERS_RYUK_DISABLED=true`. Earlier still, the same command exited 1 for a *different* reason, the frontend lock, before that lock was repaired. No assertion was lowered and no test was edited, excluded or skipped by hand to obtain any of these numbers |
| Backend regression comparison | PASS — both sides green this attempt, so there is nothing to attribute; and a stale "time bomb" claim in the sync log is corrected here | **This attempt's measurement, at HEAD `a144a5da0e5d8e38128fc891fb4acaefb68b70c2`:** community full suite `Tests run: 3212, Failures: 0, Errors: 0, Skipped: 1`, `MVN_CONTAINERS_EXIT=0`, `BUILD SUCCESS`; pristine `origin/master` `b52cdeeea3b82316ee370e56d5533e6a8d9245b3` on the targeted command `mvn -B -DskipGitCommitId=true -DskipFrontend=true -Dtest=ScheduledTaskSpringMybatisIntegrationTest,V037LegacyArtifactServiceFlowMySqlTest -DfailIfNoTests=false test` → `Tests run: 27, Failures: 0, Errors: 0, Skipped: 0`, `MVN_PRISTINE_EXIT=0`, `BUILD SUCCESS`, 3 containers created. **Zero failures are attributable to this sync.** Both comparison trees were isolated and detached, the delivery branch was never checked out for them, and `git status --porcelain` stayed at 0 lines throughout; the pristine worktree was verified clean (0 tracked modifications, no untracked source) and then removed, `REMOVE_EXIT=0`. That pristine run was made specifically to test the sync log's "upstream time-bomb fixture" claim, and master **passed**, so the claim is stale rather than wrong about the mechanism. `ScheduledTaskSpringMybatisIntegrationTest.java`'s `gmt_create` occurrences decompose exactly: merge base `25371cb1` = 1; community `0e76ae22f` (2026-09-02) added 2 at **seed-INSERT** level; master `21e28e0f4` (2026-09-05) added 2 at **scanner** level plus its comment; merge `616b8468` kept **both**, so HEAD has 5 at blob `720ccbb2cacb94d516145ecc685da62d98ab5c36` — identical to the merge commit's own blob, so nothing touched the file afterwards. Pristine master contains `21e28e0f4` and is therefore not exposed to the bomb, which is exactly why it passes. The mechanism itself is genuine and was verified in product code at `ScheduledTaskScheduler.java:78` (`Instant earliest = task.getGmtCreate() != null ? task.getGmtCreate().toInstant() : Instant.EPOCH;`), `:80` and `:87`, which drop occurrences earlier than that, with the no-op at `:44` (`if (occurrences.isEmpty()) return;`) making `claimAndFire` do nothing. Community's two INSERT-level pins were then shown **not** to be load-bearing at HEAD: line 241's rows feed only `scheduledTaskListShapesUseTheTenantScopedDaoAndHaveAnExplainPlan`, whose four EXPLAIN queries contain 0 `gmt_create` references, and line 1349's row is overwritten on every scanner path by `resetActiveDueTask()`'s explicit `UPDATE … gmt_create='2026-08-01 00:00:00.000'`, while the paused-path tests never read the column. So the merge outcome is a correct strict union needing no repair, and `upstream-sync-log.md:174` (heading) plus `:1059` (the withdrawn "fails on any run after the seeded dates" clause, inside the bullet starting at `:1052`) have been **corrected in this attempt rather than left asserting a bomb that does not fire** — the log's own cited lines `:428`, `:914` and `:1034` were re-checked afterwards and did not move, because the heading edit was made length-neutral for exactly that reason. **Prior attribution, kept for continuity — four runs in two isolated detached worktrees, same targeted command, exit code captured from Maven itself. First pair, 11:15, daemon not answering:** pre-sync community `c3a75ae60462e3bdc93d1e9fb82041dc9d442f51` → `MVN_EXIT_presync=0`, `Tests run: 2, Failures: 0, Errors: 0, Skipped: 2`, `BUILD SUCCESS`; pristine `origin/master` `b52cdeeea3b82316ee370e56d5533e6a8d9245b3` → `MVN_EXIT_master=0`, same `Skipped: 2`, `BUILD SUCCESS`. Both logged `Could not find a valid Docker environment`, `UnixSocketClientProviderStrategy: … NoSuchFileException (/var/run/docker.sock)` and `DockerDesktopClientProviderStrategy: … BadRequestException (Status 400)`. **Second pair, 12:02, daemon answering — this is the decisive one:** pristine `origin/master` `b52cdeeea` → `MVN_EXIT_pristine=1`, `Tests run: 2, Failures: 0, Errors: 2, Skipped: 0`, `BUILD FAILURE`, with `Found Docker environment with Docker accessed via Unix socket`, `ContainerFetchException: Can't get Docker image: RemoteDockerImage(imageName=testcontainers/ryuk:0.11.0)` and `ScheduledTaskSpringMybatisIntegrationTest.setup:130` — **the same exception, the same image and the same line number as HEAD**; a raw `GET /info` immediately before it returned `ServerVersion= 29.7.2` with 59 keys. Four seconds later pre-sync community `c3a75ae6` → `MVN_EXIT_pre=0`, `BUILD SUCCESS`, `Could not find a valid Docker environment`, because the daemon had stopped answering again. **Those four runs were read at the time as showing that "the skip-versus-error outcome tracks host daemon responsiveness at the instant of the run and is not a property of any tree"; the deterministic root cause now recorded in the container-runtime paragraph supersedes that reading**, and every one of those four numbers is reproduced by it — the `Status 400` was shaded docker-java's `RemoteApiVersion 1.32` against the daemon's `MinAPIVersion 1.40`, and the `ContainerFetchException` was Docker Hub egress being blocked. Three source-level facts still make the conclusion independent of timing: Testcontainers is pinned `1.20.6` identically at HEAD, `c3a75ae6` and `b52cdeeea`; `V037LegacyArtifactServiceFlowMySqlTest.java` is blob-identical (`9ae45f40a7b501c1d2be5d214dc6ae59d6be5c97`) at all three, so its behaviour cannot differ; and `ScheduledTaskSpringMybatisIntegrationTest.java`'s `@BeforeAll` guard and both container declarations (`mysql:8.4.4`, `redis:7-alpine`) are structurally unchanged at all three, with `diff(b52cdeeea, HEAD)` limited to 3+/3− — the two fixture `INSERT` statements adding an explicit `gmt_create` of `2025-12-31 00:00:00.000`, decomposed commit-by-commit earlier in this row rather than attributed to a bomb. **Prior reading, kept for continuity:** earlier in this cycle pristine master's full suite gave 3151 tests, 0 failures, 0 errors and the same 8 skips, so the merge added 20 tests and no regression on the 3171 basis; that basis is itself superseded by this attempt's 3212, against which the comparison still shows no regression because both sides are green. **Epilogue from this attempt's decisive run:** with `TESTCONTAINERS_RYUK_DISABLED=true` and `-DargLine="-Dapi.version=1.44"`, both Docker-backed suites executed rather than skipped, the aggregate returned `Tests run: 3212` / `Errors: 0` / `Skipped: 1` / exit 0, and no reading in this row any longer depends on host timing |
| Frontend install | PASS (after lock repair) | The original measurement — `npm ci --ignore-scripts` → `NPM_CI_EXIT=0`, `node_modules` 370M / 547 entries — was taken with the **machine's npm 11.17.0** and is superseded. npm 11 tolerates an out-of-sync lock: on the pre-repair lock `800777e2c` it exits 0 and installs `added 716 packages`. The **npm 10.9.7 that `frontend-maven-plugin` installs** refuses that same lock: `npm ci --include=dev --include=optional` → `EXIT=1`, `npm error code EUSAGE`, "`npm ci` can only install packages when your package.json and package-lock.json are in sync", with **23** `Missing: … from lock file` entries headed by `msw@2.15.0`. Both measurements are reproducible and both were true; the difference is the npm major version, which is why a green standalone gate coexisted with a broken documented build. The counts corroborate: 716 + 23 = 739, and the repaired lock installs `added 739 packages`. Repaired with `npm install --package-lock-only` (23 keys added, 0 removed); post-repair `npm ci` under the plugin's npm → `EXIT=0`. Lockfile remains public-registry-only (`registry.npmjs.org` per `frontend/.npmrc`, 0 internal endpoints); `npm audit` still reports the single pre-existing `nanoid <3.3.18` high finding. **Frontend gates must be run under the plugin's npm, not a contributor's machine npm** |
| Frontend tests | FAIL (attributed, not a regression) | Re-run in this attempt under the **plugin** toolchain (`export PATH="../target/node:$PATH"`, node v22.22.2, npm 10.9.7, vitest v3.2.6), serially with the other three gates: **`TEST_RUN_EXIT=1`**, `Test Files 3 failed \| 144 passed (147)`, `Tests 10 failed \| 1459 passed (1469)`, `Duration 415.34s`. That exit code is vitest's own, captured on the line immediately after it, not from anything downstream of a pipe. **Total test count is unchanged at 1469** — no test was added, removed, skipped or excluded. The 10 names are the documented 9 plus the load-sensitive `AllWorkspacesTab` case, so this run reproduces sealed Run B exactly in both counts and name set and is the fourth data point on that flake. The run's log is 108,464,270 B, and that volume was checked for a runaway loop before being accepted as legitimate rather than assumed away: 21,945 React `act(...)` warnings each carrying a full component stack, 154,499 `at div` lines, vitest processes alive and progressing throughout, and 19 GB disk free. Prior readings earlier in this cycle: two runs at 227.54s and 222.30s both gave `3 failed \| 144 passed (147)` / `10 failed \| 1459 passed (1469)` with the same 10-name set; a stable run gave `Test Files 2 failed \| 145 passed (147)`, `Tests 9 failed \| 1460 passed (1469)`, 125.30s. Full attribution follows the table. **This suite is not green and must not be reported as green** |
| Frontend flake | NOTED — failed again in this attempt's run, so **4 of the 5 full-suite runs** on community HEAD now carry it | `src/features/auth/AllWorkspacesTab.test.tsx > AllWorkspacesTab > keeps the modal open when the target merely leaves the current result page`. This attempt's single full-suite run failed it, giving 10 rather than 9. **The tally is stated against a named inventory so it can be audited rather than taken on trust — five full-suite runs on community HEAD exist, and exactly one lacked the flake:** sealed **Run B** (125.99s) → 10, sealed **Run C** (125.30s) → **9, the only run without it**, then two prior-attempt runs (227.54s and 222.30s) → 10 and 10, and this attempt's run (415.34s) → 10. So **4 with the flake, 1 without**. Its isolation run, sealed **Run D**, was **NOT RE-RUN** in this attempt; last measured 2026-09-07 earlier in this cycle as `npx vitest run src/features/auth/AllWorkspacesTab.test.tsx` → `SINGLE_EXIT=0`, `Test Files 1 passed (1)`, `Tests 25 passed (25)`, 4.44s — the only observation of it passing anywhere in this cycle's record. Classification is unchanged — fails only under full-suite load, so concurrency/load sensitive, not a functional defect and not a sync regression — but the honest statement is that its pass-under-load rate is closer to 1-in-5 than to "occasional", and a contributor machine should expect `10 failed | 1459 passed (1469)` as the normal full-suite result. It was **not** made to disappear by loosening the assertion and **not** skipped or excluded |
| Frontend lint | PASS | Re-measured in this attempt under the plugin toolchain: `LINT_EXIT=0`, `✖ 2 problems (0 errors, 2 warnings)`. Prior reading: `npm run lint` → `NPM_LINT_EXIT=0`, same `✖ 2 problems (0 errors, 2 warnings)`; both are pre-existing `react-hooks/exhaustive-deps` warnings at `MemoryListPage.tsx:78:9` and `RightPanel.tsx:114:6`. Unchanged, and the two warnings are not errors |
| Frontend production build | PASS | Re-measured in this attempt under the plugin toolchain: **`BUILD_EXIT=0`**, `✓ 4923 modules transformed.`, `✓ built in 9.85s`, and the artefact inventory **was** captured this time — `dist/index.html 0.60 kB │ gzip: 0.32 kB`, `dist/assets/index-Bn8zSGCu.css 20.14 kB │ gzip: 4.18 kB`, `dist/assets/index-sMm4vivb.js 4,147.58 kB │ gzip: 1,251.01 kB`. Module count 4923 and all three asset names and byte sizes are identical to the earlier readings (`✓ built in 9.29s` in the previous attempt; `NPM_BUILD_EXIT=0`, `✓ built in 6.09s`, `dist` 4.1M before that), so no module entered or left the bundle graph and the only movement is wall-clock. The `dist` byte-total (4.1M) was **NOT RE-RUN** in this attempt |
| Community boundary tests | PASS | Run inside the backend build: `Tests run: 4 … CommunityBuildInputTest`, `Tests run: 2 … CommunityDependencyBoundaryTest`, both 0 failures. **Re-confirmed inside this attempt's single `BUILD FAILURE` run** — the plain `mvn verify` at `MVN_FULL_EXIT=1`, in which both suites are green; that is the point of quoting it, because the build there failed on the seven container `ContainerFetch` errors and not on the boundary guards. It was **not** two failing runs as recorded earlier in this row: the other two runs of this attempt both ended `BUILD SUCCESS`, so there was no second failure to inspect |
| Dependency tree scan | PASS | Re-run in this attempt with the standalone invocation, `mvn -B -DskipFrontend=true -DskipGitCommitId=true dependency:tree` → **`MVN_DEPTREE_EXIT=0`**, `BUILD SUCCESS`, `Total time: 0.931 s`, `^[ERROR]` = 0, `^[WARNING]` = 0, **185 tree-glyph lines / 186 artifact lines including the root / 1 module** — the 186 matching the figure recorded earlier in this cycle, so no artifact entered or left the resolved graph. Gate A's pattern, `rg -i 'keycenter\|normandy\|akless\|rass\|(^|[[:space:]])log4j:log4j:'` run over that output → **`GATE_A_RG_EXIT=1`**, i.e. 0 matches, which is the pass condition for a negative gate and was captured from `rg` itself on the line immediately after it rather than from anything downstream of a pipe. **Byte reproduction was checked rather than assumed:** the raw log is 13023 B against the 13004 B sealed earlier, and the 19-byte excess was investigated instead of glossed — it is exactly the trailing `MVN_DEPTREE_EXIT=0` line I appended (18 chars + newline), so pure Maven output is 13004 B and **DELTA = 0**. Corroborated by three items re-measured in the same attempt: `CommunityDependencyBoundaryTest` 2/0/0 inside the full build, `KeyCenterClient` occurrences in `src` + `pom.xml` = 0, and the code-scope internal-reference rescan at 39 (see the *Internal-reference scan* row). **Explicit dependency-boundary conclusion for this attempt: the community tree requires no internal SDK, no internal Maven repository and no internal npm registry to compile, install or bundle** — proved by `npm ci` resolving 739 packages from `registry.npmjs.org` alone and by the production build succeeding inside `mvn clean verify` |
| Internal-reference scan | PASS | Re-measured in this attempt: `rg -i 'alibaba-inc\.com\|aliyun-inc\.com\|daily-keycenter\.alibaba\.net'` over `pom.xml frontend/package-lock.json frontend/.npmrc APP-META src/main src/main/resources` → `GATE_B_RG_EXIT=1`, 0 hits (exit 1 is the pass condition for a negative gate, captured from `rg` itself and not from anything downstream of a pipe). Gate A was re-run alongside it and is also clean: `GATE_A_RG_EXIT=1` (see the *Dependency tree scan* row). The credential-shape residual scan was re-run too → `RESIDUAL_SENSITIVE_EXIT=1`, 0 hits. **A method defect was found there and corrected rather than published:** a first case-*insensitive* run reported 18 apparent leaked credentials, and every one was a false positive — `-i 'LTAI[0-9A-Za-z]{12,}'` matches `ltaIngesti` 31 times (from the class name `EvolutionDeltaIngestionLiteService`) and `LtAIBwFUK8` once inside a base64 `sha512-…` integrity hash. Re-run case-sensitively it is 0. **Rule recorded: this gate must be case-SENSITIVE, because Aliyun AccessKey IDs are uppercase `LTAI…` and `-i` turns ordinary identifiers into false findings.** That defective number was not written into any verdict. **Scope limitation, stated rather than hidden:** this gate covers `src/main`, not `src/test`; `releases/` and `docs/` are out of scope too. `src/test` is therefore a known blind spot of the published gate — whether to widen it changes what the community gate asserts and is a human decision, so this step measured the blind spot (see the Gate-B block-D evidence) and did not silently alter the gate's scope. The broader cl_2 rescan was also re-run with the **exact** pattern and normalization recorded in the step-`400359` evidence (`git grep -IE`, ref prefix stripped, `sort -u`), after an initial narrower variant produced a misleadingly low figure that was discarded rather than published: **code-scope = 39, identical to pre-sync community `c3a75ae6`**, so this sync introduced zero new code-level internal references and removed zero redaction points. The tree-wide RAW count is higher than pre-sync by 24, and every one of those lines is documentation that *names* an excluded internal dependency in order to record its exclusion. A residual-literal check for the superseded runtime version was also captured directly from `rg`: `RESIDUAL_RG_EXIT=1`, `HITS=0` for `0.2.150` under `skills/` + `src/main` |
| Rule 12 runtime version | PASS | Re-measured in this attempt. All five sources of truth read `0.2.152`: `application.yml:58` (`recommended-version: ${AUTOWONDER_RUNTIME_RECOMMENDED_VERSION:0.2.152}`), `deployment-manifest.json:57`, `test_manifest.py:110`, `test_script_contracts.py:561` and `:1190`, `test_upgrade_info.py:41/:183/:332`. A **ninth** literal outside the guide's enumerated five was also found and agrees: `PlatformBrandingService.java:52` `@Value("${autowonder.runtime.recommended-version:0.2.152}")`. Residual `0.2.150` under `skills/` + `src/main`: `RESIDUAL_RG_EXIT=1`, `HITS=0`. Targeted re-runs were **NOT RE-RUN** in this attempt; last measured earlier in this cycle as `pytest tests/test_manifest.py -q` → `MANIFEST_EXIT=0`, `4 passed` and `pytest tests/test_upgrade_info.py -q` → `UPGRADE_INFO_EXIT=0`, `11 passed`. Both files are inside the full-suite runs recorded in the Skill rows below, whose pass counts are unchanged |
| Rule 12 source #4 | NOTED | `test_script_contracts.py` does not currently pass in this environment (see the Skill rows), so source #4 is **not guarded by a passing test here**. Its three literals were confirmed by direct inspection instead of by a green run, and re-confirmed by inspection in this attempt. `test_script_contracts.py:547` deliberately stays at `0.2.110` because it is the *stale input* the upgrade planner must detect, not a version source |
| Rule 11 migrations | PASS | Re-measured in this attempt over **all five** upstream-renumbered migrations, not only the three inside this sync's window. `cmp -s` plus SHA-256 against the upstream originals, all **identical**: `docs/migrations/V044__skill_soft_delete_release_unique_name.sql` → `docs/migration/V047__…` (508 B, `3f3effe204d2ace4`), `V044__workspace_access_request.sql` → `V048__…` (1249 B, `3bc98254b0d5ede1`), `V045__conversation_elicitation.sql` → `V049__…` (1334 B, `d4bd379a7851e3f2`), `V046__user_is_admin.sql` → `V050__…` (634 B, `de109c6aaf48dc9e`), `V047__org_recycle_bin.sql` → `V051__…` (1522 B, `96818d11f492f3c4`). Only the first three are inside the `25371cb1..b52cdeeea` window — `git diff --name-only 25371cb1 b52cdeeea` returns 0 paths for both `V044` files, which were introduced on 2026-08-28 and belong to the previous sync — so checking all five is a superset that also re-proves the earlier renumbering is still byte-intact. Upstream's **duplicate `V044`** (two different files sharing one number) is correctly resolved to the distinct community numbers `V047` and `V048`. `docs/migration/` holds 17 files, contiguous `V036`…`V051` with `DUPLICATE_NUMBERS=0` and no gap in that range. `presync..HEAD` name-status for that directory: 3 `A`, 0 `M`, 0 `D` — no published migration was modified, renamed or deleted |
| Directory consistency / file modes | PASS | Re-measured in this attempt. `git ls-tree -r HEAD` → 1871 × `100644` + 28 × `100755` = 1899 paths; symlinks (`120000`): 0; exotic modes: none. **New check added by this attempt:** joining the `ls-tree` mode manifests of `origin/master` `b52cdeeea` and HEAD on path gives `PATHS_IN_BOTH=1734` and `MODE_CHANGE_COUNT=0`, so the merge changed no file's mode. All 28 `.sh` files in HEAD are `100755` (`NONEXEC_SH_COUNT=0`), i.e. no executable bit was lost. Master carries only 3 executables; the extra 25 are community-only deployment and upgrade Skill scripts, `skills/` being absent from master entirely (`git cat-file -e b52cdeeea:skills` fails). Executable set at HEAD = 28, at the merge commit = 28 → `EXEC_MODE_DRIFT=0` |
| Export mechanism for the external repo | PASS | `git archive --format=tar HEAD \| tar -x -C <tmp>` → `GIT_ARCHIVE_TAR_EXIT=0`, 1899 files extracted, 0 symlinks. A per-file Python recheck recomputed each git blob SHA-1 from the extracted bytes and each filesystem executable bit against the `ls-tree` manifest: MISSING=0, CONTENT_MISMATCH=0, MODE_MISMATCH=0, EXTRA=0 → byte- and mode-identical round-trip |
| Environment-variable contract | PASS | Re-measured independently in this attempt by extracting every `${VAR}` / `${VAR:default}` placeholder from `src/main/resources/application*.yml` (2 files: `application.yml`, `application-local.yml`) and every `KEY=` line from `docs/community/application.env.example`: `YML_PLACEHOLDER_COUNT=54`, `TEMPLATE_KEY_COUNT=37`, template keys with no yml placeholder `EXTRA_COUNT=0` (so the example is a strict subset of the yml), yml placeholders absent from the example `UNDOCUMENTED_COUNT=17`. These reproduce the prior reading exactly: master `application.yml` 16 + `application-local.yml` 2; community HEAD 41 + 13 → union **54**; pre-sync community tip also **54** (`IDENTICAL_PLACEHOLDER_SET=YES`). Master placeholders absent at HEAD **depends on which `${…}` kinds the filter admits, and a correction made earlier in this attempt quoted `1` without naming a filter — that is fixed here rather than left standing, because the release file quotes `2` for the same metric and two unnamed numbers for one name is exactly how a reconciliation goes wrong.** Measured directly this attempt over `application.yml` + `application-local.yml` at both refs. Restricting to environment-variable placeholders (`${UPPER_SNAKE}`): master **18**, HEAD **54**, union **54**, **master-only 0** — master's env-var set is a strict subset of HEAD's, which is what the union figure has always meant and why `0` was recorded here. Admitting every `${…}` target including Spring property references: master **20**, HEAD **54**, **master-only 2**, namely `profile_env` and `autowonder.jwt.secret`. **Neither is a loss.** `profile_env` (master `application.yml:3`, `active: ${profile_env:daily}`) is a deliberate Rule 8 exclusion of the internal `daily` profile, introduced by commit `65371c7b5` and already recorded at `upstream-sync-log.md:428` and `release_v0.8.0_20260907.md:462`. `autowonder.jwt.secret` (master `application.yml:92`) was **re-expressed, not removed**: HEAD `application.yml:82` reads `secret: ${AUTOWONDER_JWT_SECRET:}`, so the same setting became a genuine environment variable and master's hardcoded base64 development default was dropped — a Rule 8 hardening, and the reason the key is invisible to the env-var filter. The honest reading is therefore "0 under the env-var filter, 2 under the all-placeholders filter, both deliberate, Rule 8, see those two locations". One measurement defect is recorded because it produced a wrong number before it was caught: a first pass used `grep -o '\${[A-Za-z0-9_]\+'`, whose character class omits `.`, so it truncated `${autowonder.jwt.secret:…}` to `autowonder` and reported a phantom master-only key; the class now includes `.`, `-` and `:` and the phantom is gone. Community-only placeholders: 36, all Rule 4 adaptation. Master added **0** and removed **0** `${VAR}` placeholders in the `25371cb1..b52cdeeea` window. **The 17 yml-only keys are intentional omissions, not missing keys**, and are named here in full because the guide requires every intentional omission to be recorded rather than merely counted: `AUTOWONDER_AONE_WEB_BASE_URL`, `AUTOWONDER_RUNTIME_RECOMMENDED_VERSION`, `OSS_ARTIFACT_BUCKET`, `OSS_ENABLED`, `OSS_SKILL_BUCKET`, `OSS_TASK_PKG_BUCKET`, `REDIS_CONNECT_TIMEOUT_MS`, `REDIS_DATABASE`, `REDIS_POOL_MAX_TOTAL`, `REDIS_SOCKET_TIMEOUT_MS`, `S3_ACCESS_KEY_ID`, `S3_ACCESS_KEY_SECRET`, `S3_ENABLED`, `S3_ENDPOINT`, `S3_PUBLIC_ENDPOINT`, `S3_REGION`, `SPRING_PROFILES_ACTIVE`. The mechanism that makes omitting them safe is that `plan-upgrade.sh` collects the yml placeholders as well as the example's `KEY=` lines, so all 17 are still in the upgrade contract; bulk-adding them to the example would change their contract hash and make the upgrade planner report changed environment for no functional gain |
| Database schema | **PASS (live import into a brand-new volume)** | **The import was performed this cycle and the row is no longer `NOT RE-RUN`.** `docker compose -p aw400785smoke -f docs/community/docker-compose.dependencies.yml down -v` destroyed the previous volume, then `up -d --wait` (`docs/community/README.md:59`) recreated `aw400785smoke_mysql-data` from nothing under an independent project name, so no table could have been inherited. The MySQL entrypoint really ran the community schema file: `[Entrypoint]: /usr/local/bin/docker-entrypoint.sh: running /docker-entrypoint-initdb.d/001-autowonder-schema.sql` at `13:39:38` and `MySQL init process done. Ready for start up.` at `13:39:41`; server version **8.0.46** from image `mysql:8.0` (`7dcddc01f13b`). Credentials were passed via `MYSQL_PWD` and never as `-p` on argv, so no secret appears in a process listing. **`live_tables=65`, `file_create_table=65`, `match=YES`** — the import is complete. Both tables this sync adds are present and empty: `agent_conversation_elicitation` (`V049`) `rows=0` and `workspace_access_request` (`V048`) `rows=0`. The `V050`/`V051` columns are present: `user.is_admin`, `org.deleted_at`, `org.active_name_key`, `org.deleted_by`. All **4** guarding unique keys report `unique=YES` when read back from the live database — `uk_active_name` on `org(active_name_key)`, `uk_conv_request` on `agent_conversation_elicitation(tenant_id,conversation_id,request_id)`, `uk_dispatch_normalized_idempotency` on `dispatch(tenant_id,normalized_idempotency_key)`, `uk_workspace_access_request_pending` on `workspace_access_request(tenant_id,requester_id,pending_marker)` — and the superseded `uk_name` has `uk_name_occurrences=0`. **A previously published figure in this row was wrong and is corrected here.** There are exactly **2** true computed columns, not 114: `information_schema.columns` sets `EXTRA=DEFAULT_GENERATED` on 112 `DATETIME(3) … DEFAULT CURRENT_TIMESTAMP(3)` columns as well, and conflating that with `STORED GENERATED` produced the 114. Filtering on `EXTRA LIKE '%STORED GENERATED%'` gives 2, and their expressions were read back verbatim — `dispatch.normalized_idempotency_key` = `((case when ((\`source_type\` = _utf8mb4'WORKITEM') and regexp_like(\`idempotency_key\`,_utf8mb4'^[0-9]+:[0-9]+:[0-9]+$')) then concat(_utf8mb4'WORKITEM:',\`idempotency_key\`) else \`idempotency_key\` end))` and `workspace_access_request.pending_marker` = `((case when (\`status\` = _utf8mb4'PENDING') then 1 else NULL end))`. The index totals are likewise disambiguated rather than left as one number: **452** `information_schema.statistics` rows (one per index *column*) = **208** DISTINCT `(table,index)` pairs, of which **51** are unique non-PRIMARY indexes. Every business table is empty (`rows(user)=0`, `org`, `dispatch`, `agent_conversation`, and both new tables), which is what proves the volume was schema-only. **The "64 tables" figure recorded here previously was read from the file's own footer comment `-- 表清单（64 张）`, not counted; `docs/autowonder-schema.sql` defines 65 `CREATE TABLE` statements and the live import agrees with 65.** That footer is equally wrong on `origin/master`, so it is an upstream defect, not community divergence; it is deliberately left uncorrected because fixing it would create standing divergence in a file upstream edits every cycle, and it affects no executed DDL. **Community divergence from master on this file is stated for two ranges, because one number was being published without saying which question it answered.** Over `b52cdeeea..HEAD` (master → community, total divergence): `git diff --numstat` gives **10 added / 9 removed**, of which `ADDED_COMMENT=5`, `ADDED_NONCOMMENT=5`, `REMOVED_COMMENT=4`, `REMOVED_NONCOMMENT=5` — reconciling exactly with numstat (5+5=10, 4+5=9) — so **`TOTAL_NONCOMMENT_CHANGED=10`**. Over `579225caf..HEAD` (this cycle's incremental edits only): **4 added / 3 removed**, `ADDED_COMMENT=4`, `ADDED_NONCOMMENT=0`, `REMOVED_COMMENT=3`, `REMOVED_NONCOMMENT=0`, **`TOTAL_NONCOMMENT_CHANGED=0`**. So the "**6 added / 6 removed**" and the unqualified "**0 changed lines that are not SQL comments**" recorded here earlier are both corrected: the first was simply wrong (truth 10/9), and the second is true only for the incremental range and false for the master range. The 10 non-comment lines are **5 replacement pairs**, and characterising them as "10 structural changes" would overstate it: **4 are genuine column-type widenings `credential_ref VARCHAR(n)` → `TEXT`** (n = 1024, 256, 512, 512), each carrying an inline `COMMENT` rewording KeyCenter → SecretCrypto, and **1 — `token_ref` — keeps its `VARCHAR(256)` type and changes only its inline `COMMENT`** (`KeyCenter 引用（WS 鉴权 token）` → `可解析的 WS 鉴权 token 引用`). All five are the recorded Rule 8 / Rule 3 consequence of replacing the internal KeyCenter credential reference with the community SecretCrypto ciphertext, i.e. an intentional, already-documented community difference, not drift. **A measurement trap is recorded here because it silently produced a wrong number twice:** a *removed* SQL comment line `-- foo` renders in unified-diff form as `--- foo`, indistinguishable by prefix from the diff's own `--- a/path` header, so both `grep -c '^-[^-]'` and `grep -vE '^(\+\+\+|---)'` discard real content — the first returned `REMOVED=5` against numstat's 9. The only safe header guards are anchored on the path prefix, `^--- a/` and `^\+\+\+ b/`, and a classifier using those guards is what produced the reconciling figures above. Any future recount of a SQL-comment-bearing diff must use them. `ConversationElicitationSchemaContractTest` (2), `ExecutorSchemaContractTest` (1) and `SystemSettingServiceTest` (14) all read this file and all pass in this attempt's full build; the live import above is the runtime proof that the DDL they assert is the DDL MySQL actually accepts |
| Local startup smoke | **PASS — run twice this cycle; the prior `FAIL (environment, attributed)` verdict is withdrawn** | **That verdict was correct when measured and is falsified now**, so it is superseded rather than left standing. Its cause is named and removed: `RedisManager.testEnterprise(RedisManager.java:249)` probes Redis eagerly during bean construction and rethrows `redis enterprise test fail.` when nothing answers, which is what killed the earlier run after ~3 s. With the community compose file's `redis:7-alpine` actually running, the same code path logs `Redis Enterprise version check start!` → `Redis Enterprise version check success!` and startup proceeds. **Run A — host JVM from the Maven build artefact** (`java -jar target/auto-wonder.jar`, PID 97671, launched `13:41:12`): `Started Bootstrap in 3.666 seconds`. **Run B — the documented container path `docs/community/README.md:73`**, using the image built by the *Linux image build* row below: container `13b4185a9283`, `DOCKER_RUN_EXIT=0`, healthy after 13 × 2 s polls on `GET /checkpreload.htm`, `CONTAINER_STARTUP_OK=1`, `container_state=running running=true exitcode=0`, `Started Bootstrap in 20.704 seconds (JVM running for 24.114)` — slow because it is `linux/amd64` under emulation on an `arm64` host — and `SCRIPT_EXIT=0` captured from the script itself, not from a wrapper. Both runs reached the same landmarks: profile `local` active, `Tomcat initialized with port(s): 7001 (http)`, `HikariPool-1 - Start completed.`, `V037 schema capability: mode=V037_READY, mapper_mode=SOURCE_AWARE, scheduled_available=true, missing_count=0`, `AiWorkerPool started with 3 workers`, `Tomcat started on port(s): 7001`. Object storage has no runtime fallback, so Run A and Run B both used the repo-sanctioned local backend from `docs/autowonder-s3-storage.md` §7 (`oss.enabled=false` + `s3.enabled=true` against MinIO). **Env-var quoting was proven with a negative control before the positive run**, because an unquoted `&` produces a downstream-initialisation failure that looks exactly like a product defect: the shipped `docs/community/application.env.example` line 1 is **167** chars total containing 4 `&` and all 5 JDBC params — `SPRING_DATASOURCE_URL=` accounts for 22 of them, so the **value** is **145** chars — and `eval`'d naively by a shell it backgrounds the assignment and leaves the variable **empty** (`TRUNCATED_LEN=0`) *while still exiting 0*. An earlier draft of this row said 172 chars and attributed `QUOTED_LEN=150` to "the same line read as an env file"; both are wrong and are corrected from direct measurement at HEAD rather than left standing. The `150` readings are the **host-retargeted** value, i.e. the shipped 145 chars with `mysql:3306` replaced by the compose-mapped host port so Run A could connect from outside the network: a compiled JVM probe read back `JVM_PROP_LEN=150`, `JVM_PROP_AMPERSANDS=4`, `JVM_ENV_LEN=150`, `JVM_ENV_AMPERSANDS=4`. Run B proves the unretargeted form for `docker --env-file` against the **unmodified** shipped template (`git diff --numstat` on it = 0 lines): inside the container the URL is 145 chars with `container_env_url_ampersands=4` and `container_env_url_host=mysql:3306` (150 − 5 = 145, exactly the `localhost:33060` → `mysql:3306` host:port difference), so `docker --env-file` literal parsing is safe here and a shell `source` of the same file is not. `src/main/resources/application-local.yml:27`'s *default* URL carries **3** `&`, one fewer than the example, because it omits the fifth param — so the two prescribed sources are not interchangeable when counting ampersands. **Capability endpoints (cl_2), community defaults not overridden:** `GET /checkpreload.htm` → 200 `success`; `GET /api/integrations/capabilities` → 200 `{"aoneEnabled":false}`, matching what `docs/community/README.md` promises; `GET /api/platform/branding/public` → 200 with `recommendedRuntimeVersion:"0.2.152"` (Rule 12 source #9, live), `deploymentVersion:"0.8.0"` (this release), `communityEdition:true`, `platformName:"AutoWonder"`, `themeKey:"aliyun-orange"`, `primaryColor:"#f97316"`, `mcpBaseUrl:"http://localhost:7001/api/mcp"`, `canManage:false`. Negative controls: unauthenticated **and** bogus-token `GET /api/workspaces/mine` both → 401 `{"code":"10401","message":"未登录或登录已失效"}`. **Real auth chain (cl_3), 26 numbered steps / 27 calls, every one HTTP 200:** register → login → create workspace → `POST /api/workspaces/10000/switch` → current/membership/members/member-candidates; a second registered user; `V048` discovery list, write access-request, owner read (`pending_request_count=1`, `first_request_id=10000`), approve, list-after-approve → `data: []`, members now 2 with the guest at `accessLevel:"READ_ONLY"`; `V051` recycle-bin empty → soft delete → listed with `deletedAt`/`deletedBy`/`deletedByName`/`restorable:true` → restore with `version` advancing `0`→`2`; `V049` `clarification-conversations` list → `data: []` for both an existing and a nonexistent workitem id, with the mandatory `?agentId=1`; and **this cycle's two genuinely new routes**, `POST …/elicitations/{requestId}/reply` and `GET …/turns/{turnId}/events`, both HTTP 200 returning the correct business error `{"success":false,"code":"10001","message":"conversation not found"}` — which proves the routes are registered, `@RequireWorkspaceAccess` admits the caller and the ownership lookup runs, with only a live agent conversation missing. **Not claimed:** an end-to-end elicitation round trip, which needs a running digital worker and was not available. **Log scan (cl_4), re-measured after all traffic and before teardown:** `app-run.log` 61622 B / 323 lines → 309 `INFO`, 5 `WARN`, **0 `ERROR`**, 0 `FATAL`/`SEVERE`/`DEBUG`/`TRACE`, 0 stack frames, 0 `Caused by:`, 38 × `status=200`, 115 `BizLoggerFilter` lines, 5 non-level banner lines. All 5 WARN are `GlobalExceptionHandler|biz exception` on `POST /api/workspaces/10000/access-requests` — 4 × `code=12013 已是该工作空间成员` (request ids `1f349c9d`, `723139d7`, `2510b191` at `13:42:02`, `0097dab3` at `13:47:28`) and 1 × `code=12012 已有待审批的申请` (`00b5253f` at `13:47:27`) — i.e. the idempotency guards firing on repeated probes issued by this verification itself, correlated line-for-line with the probe log; they are expected business behaviour, not defects. `eyJ`=0 and `password=`=0, so no credential reached the log. Run B's container log is cleaner still: 47 lines, **0 ERROR, 0 WARN**, no Sigar or native-library failure despite `AUTOWONDER_SIGAR_ENABLED=true` under emulation. **Two things this row must not be read as claiming.** First, the two Docker-backed Testcontainers *suites* still did not execute (see the full-build row), so `mysql:8.4.4` and `redis:7-alpine` *as those suites declare them* remain unproven; what is proven is the pair the community compose file actually ships — MySQL **8.0.46** and `redis:7-alpine` — against which the whole application started and served real traffic. Second, that compose file pins `mysql:8.0` while the suites pin `mysql:8.4.4`; the two are not the same server, and no test covers the shipped one. Last successful full startup before this cycle: 2026-09-02 |
| Linux image build | **PASS, after a one-line fix to a community-owned defect this step found** | `docker build --platform linux/amd64 -f APP-META/docker-config/Dockerfile -t autowonder-community:local .` (`docs/community/README.md:70`) → **`DOCKER_BUILD_EXIT=0`**, captured from `docker build` itself immediately after the command, and image `sha256:53d7388ccc0870c27b76278621020a67f7b68b7a7bd3edb9e0ca354641242059`, created `2026-09-07T06:09:32Z`. The Maven phase inside the build stage is green on its own terms: `BUILD SUCCESS`, `Tests run: 3171, Failures: 0, Errors: 0, Skipped: 8`, `Total time: 03:37 min`, `Finished at: 2026-09-07T06:09:31Z`. **The first attempt at this exact documented command failed, and the failure is a real pre-existing community defect, not an environment limitation.** `docker build`'s own exit was **1** with `failed to solve: process "/bin/sh -c mvn -DskipGitCommitId=true package" did not complete successfully: exit code: 1`; inside the container, `V037DockerReleaseGateScriptContractTest` gave 1 failure (`release gate script must exist ==> expected: <true> but was: <false>`) and 1 error (`» IO`). Root cause: that test resolves `Path.of("scripts/verify-v037-docker-gates.sh")` **relative to the process CWD**, which in the build stage is `/workspace`, but the build stage's `COPY` set was `pom.xml`, `frontend`, `src`, `docs`, `APP-META` and never `scripts/`. `.dockerignore` is not implicated — it excludes only `.git`, `.worktrees`, `.idea`, `.vscode`, `target`, `logs`, `frontend/node_modules`, `frontend/dist`, `frontend/tsconfig.tsbuildinfo`. Attribution was established **before** changing anything: `APP-META/docker-config/Dockerfile` is community-owned (`git diff c3a75ae6 HEAD` on it was empty, and master's version of that path is an entirely different internally-based Dockerfile that the community tree replaces wholesale), while both the test and `scripts/verify-v037-docker-gates.sh` were added on 2026-08-18 by commits `12c7c1d82` and `18efee248`, which are ancestors of pre-sync community, of the merge base and of pristine master alike. So **`README:70`'s documented build has been broken and unverified since 2026-08-18** — consistent with this row's last green reading being 2026-08-04 — and it is a pre-existing community defect rather than a regression from this sync. Graded **low risk** under AGENT.md rule 4 (community-owned deployment config, one unambiguous mechanical answer, zero product-behaviour change) and fixed with a single line, `COPY scripts ./scripts`; `git diff --stat` on the file is `1 file changed, 1 insertion(+)`, blob `b4a5d4636..2c473744d`. The fix was verified to be *safe*, not merely effective: `scripts_dir_in_runtime=ABSENT` and `workspace_in_runtime=ABSENT`, so `scripts/` feeds only the discarded build stage and adds nothing to the shipped runtime image, and `scripts/` passes the internal-reference gate (`RG_EXIT=1`, 0 hits) — its only tracked file is the 2705-byte mode-0755 release-gate script. **Two honest caveats about how the build was run on this host.** (a) Docker Hub is unreachable here, so BuildKit cannot fetch the external frontend named by the Dockerfile's own line 1, `# syntax=docker/dockerfile:1`, and `docker/dockerfile:1` is absent from `public.ecr.aws`, `ghcr.io` and `registry.cn-hangzhou.aliyuncs.com`; the build therefore used a scratch copy of the fixed Dockerfile with **only line 1 removed** (`diff` = `1d0`). That substitution is **equivalent on behaviour, not on byte count**: the *builtin* frontend already supports `RUN --mount=type=cache` and the first, failing build reached the Maven phase through it, so the external frontend is not load-bearing for this Dockerfile. The scratch file's identity rests **solely on that `diff` record** — the scratch directory was deleted during teardown and cannot be re-derived. `step400785-image-build.txt` §3 further discloses an **unreconciled** gap between BuildKit's reported `transferring dockerfile` sizes and the repo file's byte counts (+39, +39 and +145 across the three attempts, the last carrying +106 that nothing now explains); **no conclusion in this row depends on those three numbers.** What is *not* proven is that the file built was byte-identical to the repo Dockerfile minus line 1 — only that a `diff` taken at the time said so, and that the frontend that parsed and executed it behaved identically. A contributor with Docker Hub access runs `README:70` verbatim. (b) Base images were obtained by pulling from `public.ecr.aws/docker/library/…` and `quay.io/minio/…` and retagging to the Docker Hub names, so the community compose file and Dockerfile resolve **unmodified**; no community file was edited to work around egress. Prior reading, kept for continuity: 2026-08-04, image `sha256:3f44d9514a0f4e7a7b8add0f373d16c2c04d6bd8a4ffb5a03bd07ef5f3a1b055`, 1671 container tests. That test count is not comparable to this cycle's 3171 — the suite has grown by roughly 1500 tests since, so the two numbers describe different trees |
| Runtime image identity | **PASS** | Measured from the image built above, not from a stored note. `docker image inspect autowonder-community:local` → `Arch=amd64`, `Os=linux`, **8** layers (base 5 + 3), `.Size=164941578` bytes as reported by `inspect` while `docker images` reports `543MB` for the same image — two different tool readings of the same artefact, recorded as-is rather than reconciled. Security-relevant config is intact: `User=[autowonder]` (non-root), `WorkDir=/app`, `ExposedPorts={'7001/tcp': {}}`, `Entrypoint=['sh','-c','exec java $JAVA_OPTS -jar /app/auto-wonder.jar']`. Inside the image: `/app/auto-wonder.jar` present at **73045077** bytes — byte-for-byte the same size the host build produced — `/app` contains only `auto-wonder.jar` and the `logs` directory owned by `autowonder`, `id` returns `uid=999(autowonder) gid=999(autowonder)`, `uname -m` returns `x86_64`, and `java -version` returns `openjdk version "21.0.11" 2026-04-21 LTS`. The packaged manifest was read on the host after `docker create` + `docker cp` (the JRE image has no `unzip`): `Start-Class: com.aliyun.autowonder.Bootstrap`, `Main-Class: org.springframework.boot.loader.JarLauncher`, `Spring-Boot-Version: 2.7.18`. **One difference is stated rather than glossed:** the in-image JAR hashes `8134ace7f3c3e98bcb7224666e1de1becd844a773ea839ae8012ef28a992b2f3` while the host-built `target/auto-wonder.jar` hashes `7e98f84afdaf9cdbbc7617174e29ad50a4f036d6f0a556078e8a46eaab6fb7b4`, at identical size. They are two independent builds of the same source — the container build ran its own `mvn package` in a fresh cache mount — and a Spring Boot fat JAR embeds entry timestamps, so identical bytes were never expected. Identical size plus identical `Start-Class`, identical layer config and a successful startup from the image (Run B above) are the meaningful equivalences; a byte-identical JAR is not claimed. Prior reading: 2026-08-04, Linux amd64, user `autowonder`, JAR present |
| Runtime base image | **PASS — digest unchanged since 2026-08-04** | The Dockerfile pins `eclipse-temurin:21-jre-jammy@sha256:468586c92d39f8cbad76574623db3fe001625ed4d895431c3ff7bd2ec9ce7ae3`, and the tag present on this host reports `RepoDigests=[eclipse-temurin@sha256:468586c92d39f8cbad76574623db3fe001625ed4d895431c3ff7bd2ec9ce7ae3 public.ecr.aws/docker/library/eclipse-temurin@sha256:468586c92d39f8cbad76574623db3fe001625ed4d895431c3ff7bd2ec9ce7ae3]` — the pinned digest **exactly**, with `Os=linux Arch=amd64`, and the same digest again under the mirror it was pulled from. So the runtime base is bit-identical to the one measured on 2026-08-04 even though it arrived via a mirror, which is precisely what digest pinning is for. The build stage's base, `maven:3.9.9-eclipse-temurin-21`, is **not** digest-pinned in the Dockerfile; it is a floating tag and its contents are therefore not reproducible across time. That is a pre-existing property of the file, unchanged by this sync, and is recorded here as a hardening opportunity rather than fixed unilaterally |
| Deployment Skill tests | FAIL (pre-existing, re-proved on the pre-sync baseline **first-hand** this attempt) | Re-run in this attempt from the real directory `skills/deploying-autowonder-on-alibaba-cloud`: `python3 -m pytest tests -q` → **`PYTEST_EXIT=1`**, `29 failed, 72 passed in 229.39s (0:03:49)`, and **every one of the 29 is in `tests/test_script_contracts.py::ScriptContracts`**. **Why a baseline run was mandatory here and could not be discharged by reasoning:** `git diff --numstat c3a75ae6 HEAD -- skills` is 4 files / **7 lines**, every one a Rule-12 runtime-version literal `0.2.150` → `0.2.152`, and **2 of those 7 changed lines are inside `tests/test_script_contracts.py` — the very file that holds all 29 failures.** Changed file and failing file being the same file means an argument from disjointness was unavailable, so the baseline was actually executed: `git worktree add /tmp/aw360-presync-skills c3a75ae60` → `WORKTREE_ADD_EXIT=0`, `skills` subtree there `729d40ec323ffe29056d580a701b6ebcdf692073` with 7 occurrences of the pre-Rule-12 value `0.2.150` confirmed present, and the full deploy suite in that worktree gave **`29 failed, 72 passed in 421.39s (0:07:01)`**, `PRESYNC_DEPLOY_PYTEST_EXIT=1`. The failure-name sets were extracted from the `FAILED` lines, normalized, sorted and diffed **in both directions**: `PRESYNC_NAMES=29`, `HEAD_NAMES=29`, `comm -23` (only in HEAD, i.e. new regressions) → **0 lines**, `comm -13` (only in presync, i.e. fixed by the sync) → **0 lines**, **`DEPLOY_FAILSET_EQ_PRESYNC=YES`**. The same bidirectional comparison against the accepted evidence `skill-deploy-FULL-pytest.txt` also gives `NAMES_ACCEPTED=29` with both difference sets empty, `FAILSET_EQ_ACCEPTED=YES`. **Verdict: all 29 are pre-existing on the pre-sync community tip, are not this cycle's regression, and are not counted as one; the 7 Rule-12 literals changed nothing about which tests fail.** One representative root cause, quoted verbatim from the baseline log to show the class is contract-shape drift and not version-string drift: `AssertionError: 'configure_cloud_profile "$manifest"' not found in '#!/usr/bin/env bash\nset -euo pipefail\n… exec bash "$SCRIPT_DIR/internal/release-transfer.sh" "$@"\n' : deploy-via-cloud-assistant.sh` — the script was refactored into a thin dispatcher over a shared `internal/release-transfer.sh` while the contract test still asserts the old inline body, identically before and after the sync. Prior readings earlier in this cycle: `PYTEST_EXIT_deploy=1` `29 failed, 72 passed in 38.86s` (Python 3.10.20) and `39.14s`, and at the pre-sync tip `PRESYNC_DEPLOY_EXIT=1` `29 failed, 72 passed in 34.84s`; the pass/fail counts are identical across all of them and only wall-clock moved. Environment causes were **NOT RE-RUN** in this attempt; last measured earlier in this cycle as `TERRAFORM=ABSENT`, `ALIYUN=ABSENT`, `ALIYUN_CONFIG_PRESENT=NO`, accounting for 9 of the 29 (8 missing-`terraform` + 1 missing-`~/.aliyun/config.json`), and the credential file was deliberately **not** created because AGENT.md Rule 8 forbids credentials entering this work. The four-class split of all 29 is in `step400360-skill-failure-classification.txt` |
| Upgrade Skill tests | FAIL (pre-existing, not a regression) | Re-run in this attempt from `skills/upgrading-autowonder-on-alibaba-cloud`: `python3 -m pytest tests -q` → **`PYTEST_EXIT=1`**, `6 failed, 66 passed, 1 skipped in 221.55s (0:03:41)`. All 6 are in `tests/test_split_contract.py::UpgradeSkillSplitContractTests`; the 1 skip is `tests/test_windows_native_adapters.py:96`, PowerShell being unavailable on this macOS control host. Same bidirectional name-set comparison against `skill-upgrade-FULL-pytest.txt`: `NAMES_NOW=6`, `NAMES_ACCEPTED=6`, both difference sets empty, `FAILSET_EQ_ACCEPTED=YES`. **No pre-sync baseline run was required for this suite, and the argument is structural rather than an experiment:** the upgrade suite's only changed file is `tests/test_upgrade_info.py`, which is **not** among the failing files — all 6 failures live in `tests/test_split_contract.py` — and a change confined to a non-failing file cannot alter the failing set. That contrasts with the deploy suite above, where the changed file *was* the failing file and a real baseline run was therefore mandatory. Two independent grounds agree here: the sealed evidence already records `UPGRADE_FAILSET_EQ_PRESYNC=YES`, and this attempt's re-run reproduces the same 6 names. Prior readings earlier in this cycle: `UPGRADE_PYTEST_EXIT=1` `6 failed, 66 passed, 1 skipped in 38.57s` and `39.07s`; pre-sync tip `PRESYNC_UPGRADE_EXIT=1` with the same counts. Counts identical throughout, only wall-clock moved |
| Skill subtree stability across both Skill rows | PASS (stability) / **differs from the pre-sync tip, for a recorded reason** | `git rev-parse HEAD:skills` = `91ad71bc7f2c4c226fadf2b20b8dd86511c0c409`, **identical** to the same expression at `579225caf` and at `e61d08ffd`, so nothing under `skills/` moved between the accepted measurement and this attempt — the re-run compares like with like. Per-skill hashes: `deploying…` = `1d9a97f4d8fd05dc8e1a53bde6b40a383a72778f`, `upgrading…` = `f83b75649a57716f030e3fb9a32b3a39a77c59e1`. **No upstream baseline is possible for these two suites**: `git cat-file -e b52cdeeea:skills` fails, i.e. `skills/` does not exist in `origin/master` at all, so the pre-sync community tip is the only valid comparison. **A clause recorded here earlier said that comparison "matches"; it does not, and it is corrected rather than left standing.** Pre-sync `c3a75ae6` gives `skills` = `729d40ec323ffe29056d580a701b6ebcdf692073` against HEAD's `91ad71bc7f2c4c226fadf2b20b8dd86511c0c409` — **two different trees**. The difference is exactly 4 files / 7 lines and every one of the 7 is a Rule-12 runtime-version literal `0.2.150` → `0.2.152`, so the subtree differs, differs for a rule the sync guide mandates, and that difference is what the deploy Skill row's baseline run exists to prove harmless (`DEPLOY_FAILSET_EQ_PRESYNC=YES`) |

### The 9 stable frontend failures, attributed

The 9 stable failures all reproduce in isolation, and none is caused by conflict
resolution. A tenth, load-sensitive case joins them under full-suite load, so a
contributor should expect `10 failed | 1459 passed (1469)` rather than 9; see the
*Frontend flake* row.

That tenth case is reported against a **named inventory** rather than a vague
"occasional", because an earlier sentence here claimed it failed in "**both**" of
this cycle's runs — the inventory is five runs, not two, and one of the five did
not carry it: sealed Run B (125.99s) → 10, sealed Run C (125.30s) → **9, the only
run without it**, two prior-attempt runs (227.54s, 222.30s) → 10 and 10, and this
attempt's run (415.34s) → 10. So **4 with the flake, 1 without**, and Run C is the
source of the "9 stable" name.

The two isolation reproductions quoted below (the 6-name executor run and the
3-name toolchain worktree run) were measured earlier in this cycle and were
**NOT RE-RUN** in this attempt. What this attempt did re-measure is the full suite
itself, **once** (`Duration 415.34s`), with an unchanged total of 1469 tests — an
earlier sentence here said "twice", which described the sealed Run B / Run C pair
rather than this attempt and is corrected.

**6 in `src/features/executor/ExecutorListPage.test.tsx` — upstream defects.**
These are master's own failures from its new Agent-grouping UI. Reproduced on
pristine `origin/master` `b52cdeeea` with the identical 6 names, and reproduced
again at the community tip: `npx vitest run src/features/executor/ExecutorListPage.test.tsx`
→ `HEAD_EXECUTOR_EXIT=1`, `Tests 6 failed | 56 passed (62)`, same 6 names.
Deterministic, not load sensitive.

**3 in `src/features/workitem/WorkitemDetailPage.test.tsx` — community toolchain
debt.** The test file *and* its subject component are byte-identical to master
(blob `cc96d9e3…`, empty numstats), yet they fail on community and pass on
master. The only differences are 5 community-owned harness files introduced by
community-only commit `86e3614ff` (`frontend/package.json` bumps
`react-router-dom` 6.24 → `7.18.2`, `vite` 4.5.14 → 6.4.3, `vitest` 0.34.6 →
3.2.6, `undici` 5.28.4 → 8.10.0, the `@typescript-eslint/*` pair 6.21.0 →
^8.66.0, `@vitejs/plugin-react` 4.2.1 → 4.7.0, `@vitest/coverage-v8` 0.34.6 →
3.2.6, and the `esbuild` allowScripts key; `frontend/src/test/setup.ts` adds
`Blob`/`File` globals; `frontend/vite.config.ts` adds `testTimeout: 10000`).
Decisive experiment, run in a dedicated worktree carrying pristine master plus
only those harness files and **no** merge: `npx vitest run src/features/workitem/WorkitemDetailPage.test.tsx`
→ `COMTOOLCHAIN_WDP_EXIT=1`, `Test Files 1 failed (1)`, `Tests 3 failed | 63 passed (66)`,
same 3 names. Toolchain causation, not merge causation. The three assertion
messages are:

```
× applies clarify width on the right panel container and bottom-anchors the clarify box
  → expect(element).toHaveStyle()
  - Expected  + Received
  - display: flex;
  - flexDirection: column;
  + display: block;
× clarify URL persistence (工单 53035) > records fullscreen in the URL and drops it again on exit
  → expected '?panel=clarify&fullscreen=1' not to contain 'fullscreen=1'
× clarify URL persistence (工单 53035) > records the picked agent and its conversation in the URL
  → expected '?panel=clarify&agent=1&conversation=5' to contain 'fullscreen=1'
```

Two of the three are URL/search-parameter synchronization failures consistent
with the `react-router-dom` v6 → v7 major bump; one is a style-application
failure.

**Conclusion: 6 upstream defects + 3 community-toolchain defects + 1 load-sensitive
flake = zero sync-introduced regressions.** No assertion was weakened and no test
was skipped to obtain a greener number.

### Deployment and Upgrade Skill failures, attributed

The previous revision of this file claimed the deployment failures were all
"stale assertions against script content that moved into
`scripts/internal/release-transfer.sh`". That was **partly false** — only about
18 of 29 are. The class split below came from a per-block classifier over the
pytest output plus manual script reproduction, run **earlier in this cycle and
NOT RE-RUN in this attempt**. What this attempt did re-measure is the failure
*sets* — at HEAD and, for the deployment suite, first-hand on the pre-sync tip:

- **Deployment at HEAD:** `PYTEST_EXIT=1`, `29 failed, 72 passed in 229.39s (0:03:49)`,
  all 29 inside `tests/test_script_contracts.py::ScriptContracts`.
- **Deployment on a fresh `git worktree` at the pre-sync community tip**
  `c3a75ae60462e3bdc93d1e9fb82041dc9d442f51` (`WORKTREE_ADD_EXIT=0`, that tree's
  `skills` = `729d40ec323ffe29056d580a701b6ebcdf692073`, carrying the 7 pre-sync
  `0.2.150` literals): `PRESYNC_DEPLOY_PYTEST_EXIT=1`,
  `29 failed, 72 passed in 421.39s (0:07:01)`.
- **Bidirectional name diff of the two sets:** `comm -23` → 0 lines, `comm -13` →
  0 lines, `DEPLOY_FAILSET_EQ_PRESYNC=YES`. A baseline run was **mandatory** here
  rather than inferable: 2 of the 7 lines this sync changed under `skills/` are
  inside the failing test file, so disjointness could not be assumed.
- **Upgrade at HEAD:** `PYTEST_EXIT=1`, `6 failed, 66 passed, 1 skipped in 221.55s (0:03:41)`,
  all 6 inside `tests/test_split_contract.py::UpgradeSkillSplitContractTests`. No
  baseline run was required: the only file this sync changed in that suite,
  `tests/test_upgrade_info.py`, is **not** among the failing files, and the sealed
  `UPGRADE_FAILSET_EQ_PRESYNC=YES` plus this attempt's recurrence of the same 6
  names are two independent grounds that agree.

The four deployment classes and two upgrade classes that follow are the earlier
classifier's, quoted so the counts are auditable:

**Deployment Skill — 29 failures in 4 classes:**

- **18 stale moved-content assertions.** 16 assert strings that now live in
  `scripts/internal/operations.sh` / `scripts/internal/release-transfer.sh`
  behind an intentionally thin entrypoint; 2 are
  `IndexError: list index out of range` from
  `initialize.split("  database-migrate)", 1)[1]` and
  `initialize.split("  rolling-upgrade)", 1)[1]`.
- **2 stale manifest fixtures.**
  `test_acceptance_rerun_preserves_completed_deep_checks` →
  `AssertionError: 1 != 0 : ERROR: ALB ID missing for public acceptance`
  (`tests/test_script_contracts.py:1146`);
  `test_preflight_dry_run_accepts_valid_manifest_and_rejects_secret` →
  `AssertionError: 1 != 0 : ERROR: new deployment fixed environment, topology,
  sizing, account UID, lifecycle, execution mode, or remote state is invalid`
  (`:255`).
- **8 environment failures — `terraform` absent from PATH.**
- **1 environment failure — `~/.aliyun/config.json` absent.**

**Upgrade Skill — 6 failures in 2 classes:**

- **5 environment failures** — `ERROR: required file missing: /Users/honeyfamily/.aliyun/config.json`.
- **1 stale exit-code contract** —
  `test_deployment_entrypoints_reject_upgrade_operations` asserts
  `self.assertEqual(2, operation.returncode)` and gets
  `AssertionError: 2 != 1`. Manually reproduced:
  `scripts/initialize-and-verify.sh rolling-upgrade` → `EXIT=1`,
  `ERROR: operation is outside the selected skill boundary`;
  `scripts/deploy-via-cloud-assistant.sh --stage-only` → `EXIT=1`,
  `ERROR: stage-only is outside the deployment skill boundary`. **The guard fires
  correctly; only the expected status code drifted.**

The three environment facts behind those 9 failures — `terraform` absent from
PATH, `aliyun` CLI absent from PATH, and `~/.aliyun/config.json` absent — were
measured **earlier in this cycle** and were **NOT RE-RUN in this attempt**;
`TERRAFORM=ABSENT`, `ALIYUN=ABSENT` and `ALIYUN_CONFIG_PRESENT=NO` are carried
forward from that measurement, not re-observed here. (An earlier sentence said
they were "confirmed on PATH", which contradicts its own values.) So the 9
environment failures are properties of this machine rather than of the tree, and
what this attempt proves independently is narrower and stronger: the failure
*names* at HEAD are identical, in both directions, to those on the pre-sync tip
on this same machine.

### npm audit posture

**`npm audit` itself was NOT RE-RUN in this attempt.** Last measured 2026-09-07,
earlier in this cycle, on the repaired lock: `npm audit --json` → `AUDIT_EXIT=1`,
`{"info":0,"low":0,"moderate":0,"high":1,"critical":0,"total":1}`. The single
high finding is **nanoid**, advisory `GHSA-2v37-7h3g-55p8`.

This attempt does supply one independent, weaker corroboration: `npm ci` ran
(`NPM_CI_EXIT=0`, `added 739 packages, and audited 740 packages in 13s`) and npm
printed its own summary line `1 high severity vulnerability`. That confirms the
**count** is still 1 without naming the package, so it is not a substitute for
the audit run and is not recorded as one.

The finding is otherwise safe to carry forward without a fresh run because the
input did not change: `frontend/package-lock.json`'s blob is the one the repair
produced, and `frontend/package.json` is identical to `579225caf`. An advisory
database can still have moved since, so re-run `npm audit` before publishing.

It stays **tracked debt rather than a fix in this sync**: Community Boundary Rule
5 forbids moving dependency versions as part of a sync, so bumping past the
advisory is a separate, deliberate change and not something a release engineer may
fold into a merge.

The previous revision of this file described two high entries for a React Router
RSC-mode CSRF advisory. **That is no longer true and has been removed.** With
`react-router-dom` pinned at `7.18.2`, npm no longer flags React Router at all.
The nanoid finding is carried forward for the next dependency refresh.

## Automated Gates

Run the full Maven build and the standalone frontend gates **serially** — both use
the same `frontend/node_modules` directory. The full build runs first because it
creates `target/node`, which the frontend gates below then reuse.

Two rules here are load-bearing, and both were learned the hard way:

* **A `-DskipFrontend=true` build is not a frontend gate.** It suppresses all
  three `frontend-maven-plugin` executions, so a lock file that cannot be
  installed from clean passes the whole matrix. At least one build per release
  must run without it.
* **Run the frontend gates under the plugin's own node and npm**, not the
  machine's. npm 11 tolerates an out-of-sync lock that the npm 10 bundled by
  `frontend-maven-plugin` rejects outright, so a contributor's newer npm can
  report green on a lock the documented build cannot install. Exporting `PATH`
  also matters for a second reason: `frontend-maven-plugin` 1.11.3 predates Apple
  Silicon and installs an **x86_64** node even under an arm64 JVM, so `npm ci`
  materialises x64 optional native binaries. Vitest's bin shim is
  `#!/usr/bin/env node`; without `PATH` exported it resolves the machine's arm64
  node and dies with `Cannot find module @rollup/rollup-darwin-arm64`. Installer
  arch and runner arch must match.

```bash
mvn -B -DskipGitCommitId=true -DskipFrontend=true clean verify
MVN_EXIT=$?

mvn -B -DskipGitCommitId=true clean verify
MVN_FULL_EXIT=$?

cd frontend
export PATH="../target/node:$PATH"
node -v && npm -v          # must report the plugin's versions, e.g. v22.22.2 / 10.9.7
npm ci --include=dev --include=optional
NPM_CI_EXIT=$?
npm run test:run
NPM_TEST_EXIT=$?
npm run lint
NPM_LINT_EXIT=$?
npm run build
NPM_BUILD_EXIT=$?

cd ..
mvn -B -DskipFrontend=true -DskipGitCommitId=true dependency:tree \
  > target/community-dependency-tree.txt
DEPTREE_MVN_EXIT=$?
! rg -i 'keycenter|normandy|akless|rass|(^|[[:space:]])log4j:log4j:' \
  target/community-dependency-tree.txt
! rg -i 'alibaba-inc\.com|aliyun-inc\.com|daily-keycenter\.alibaba\.net' \
  pom.xml frontend/package-lock.json frontend/.npmrc APP-META src/main src/main/resources
```

The order above is not arbitrary. Every `clean verify` deletes `target/`, and only
the build **without** `-DskipFrontend` recreates `target/node`, so the full build
must be the last Maven invocation before the frontend gates or `export PATH` will
point at a directory that no longer exists. `MVN_FULL_EXIT` is the gate that
actually proves a fresh clone can build; `MVN_EXIT` alone does not.

A `!`-prefixed `rg` gate **passes when it finds nothing**: `rg` exit `1` means no
match, `0` means match found, `2` means error. Capture the build tool's own `$?`
on the line immediately after it runs. Never judge success from the exit code of
a command downstream of a pipe, and remember that `grep -c` exits `1` when the
count is `0`.

**A negative gate must also name its paths, because a bare `rg` may search stdin
instead of the tree.** Found while measuring this round: when `rg` is given no path
argument and stdin is not a tty, it searches stdin. In a non-interactive shell stdin
is an empty pipe, so `rg 'PATTERN'` reports "no matches" and exits `1` — the pass
condition for a negative gate — **without ever reading a file**. Probed directly:
`printf 'hello 0.2.150 world\n' | rg '0\.2\.150'` echoed the piped line and exited `0`,
while `printf 'nothing here\n' | rg '0\.2\.150'` exited `1` with 1892 files sitting in
the working tree. It is not an ignore-file effect: `rg --files` and `rg --files .` both
enumerate `1892`, and `git check-ignore -v docs/community/verification.md` reports not
ignored. It is also **unstable**, which is what makes it dangerous rather than merely
wrong — 20 runs of `rg -l '0\.2\.150'` with no path returned the wrong answer 20/20 in
one shell, while `rg -l '0\.2\.150' .` was correct 20/20, and the same bare invocation
had returned exit `0` with 4 files minutes earlier under different plumbing. A gate
whose verdict flips on invisible plumbing is not a gate. Both gates published above are
safe because both name their paths — Gate A passes `target/community-dependency-tree.txt`,
Gate B passes six — and Gate A's file argument is deliberate, since it scans build
output. For any **new** negative gate: name the paths explicitly, and print a positive
control proving the tool can find something in that scope, so an empty result is
distinguishable from a search that never happened.

**Do not gate on an error count here — gate on the invariant.** An earlier
revision of this instruction told the reader to expect
`Tests run: 3171, Failures: 0, Errors: 2, Skipped: 6` and to check that "the only
two error names" were the two container suites. That count is host- and
image-dependent: this attempt's plain run produced `Errors: 7, Skipped: 8`
instead, so the literal would have failed a perfectly healthy tree. Assert these
four things:

1. `Failures:` is `0` — in every branch. A nonzero `Failures` is never
   attributable to Docker and must be fixed before proceeding.
2. Every entry in the surefire error list is a `ContainerFetchException` /
   container-launch error naming a **Docker Hub image** (`testcontainers/ryuk:0.11.0`,
   `mysql:8.4.4`, `redis:7-alpine`, …).
3. `grep -c '^\[ERROR\]'` over the whole Maven log is `0`. Count Maven's own
   errors separately from the application-level `|ERROR|` lines the tests emit on
   purpose while driving failure paths.
4. The report states **how many of the 8 Docker-backed classes actually
   executed**, because that number — not the exit code — is what a branch means.

Six legitimate outcomes, distinguished by that executed-class count:

| Observed | Meaning | Verdict |
| --- | --- | --- |
| `Tests run: 3171, Failures: 0, Errors: 0, Skipped: 8`, exit `0` | No answering daemon; all 8 container classes hit their `assumeTrue` guard and skipped | **Not a pass.** Proves compilation and every non-container test, and nothing about the container paths |
| `Tests run: 3171, Failures: 0, Errors: N, Skipped: M`, exit `1`, every error a Docker Hub `ContainerFetch` — `N=2, M=6` and `N=7, M=8` have both been observed | Daemon reachable, but image pull or API-version negotiation failed | **Not a pass.** Pre-existing Testcontainers/`docker-java` behaviour, reproducible on pristine `origin/master` |
| As above but with the images pre-pulled, so `Errors: 0, Skipped: 8`, exit `0` | Pull succeeded, API negotiation still failed | **Not a pass, and the most dangerous shape — it looks green.** Refuse it explicitly and say so |
| `Tests run: 3212, Failures: 0, Errors: 0, Skipped: 1`, exit `0`, `CONTAINERS_CREATED=8` | All 8 container classes ran; the 48-test gap against 3171 is those classes' methods executing instead of erroring during setup | **The only pass branch.** Reached with `TESTCONTAINERS_RYUK_DISABLED=true` plus `-DargLine="-Dapi.version=1.44"` |
| exit `1` with the failure in `frontend-maven-plugin` or the frontend lock rather than in a test | Real build failure, unrelated to Docker | **Real failure.** Attribute before proceeding |
| `Failures:` greater than `0`, or any error name that is not a container-fetch class | Real test failure | **Real failure.** Attribute before proceeding |

Only the fourth row may be reported as green, and only with its own exit code and
its own summary line quoted. `-DargLine="-Dapi.version=1.44"` is a
**verifier-supplied diagnostic override, not a project setting**: the documented
plain command still fails on any host whose daemon `MinAPIVersion` exceeds shaded
docker-java's `1.32` default, which is a property of the pinned Testcontainers
`1.20.6` and not something a sync introduces.

Python Skill gates:

```bash
cd skills/deploying-autowonder-on-alibaba-cloud && python3 -m pytest tests -q
cd skills/upgrading-autowonder-on-alibaba-cloud && python3 -m pytest tests -q
```

## External Service Acceptance

Use non-production test credentials and sanitize all captured output.

1. Start the image with disposable MySQL/Redis plus real OSS credentials.
2. Verify `/checkpreload.htm` and `/api/integrations/capabilities` return HTTP 200.
3. Exercise OSS upload/read through `OSS_ENDPOINT`, then verify a presigned URL
   uses the `OSS_PUBLIC_ENDPOINT` HTTPS host and is downloadable outside the VPC.
4. Create and read one secret system setting or IM credential. Query its `credential_ref`;
   assert it starts with `enc:v1:` and does not contain the submitted plaintext.
5. With SLS enabled, emit a system log, business log, and metric; confirm each
   arrives in its configured logstore.
6. Start a second instance with `AUTOWONDER_SIGAR_ENABLED=false`; health must
   remain successful and SIGAR gauges must be absent.

**Status this cycle: NOT RUN as a whole, and one of the two blockers previously
recorded here is withdrawn.**

* Real OSS/SLS test credentials were not available, and AGENT.md Rule 8 forbids
  putting credentials into this work, so steps 3 and 5 could not be attempted
  regardless of infrastructure. This blocker stands.
* **Withdrawn:** this paragraph previously said steps 1, 2, 4 and 6 need a running
  image and that "image pulls fail with `client version 1.32 is too old. Minimum
  supported API version is 1.40` … So the container could not be started here
  either." **The container was built and started by step `400785`, twice, and
  served requests.** That error message is real but was misattributed: it comes
  from the `docker-java` transport bundled with Testcontainers `1.20.6`, which
  negotiates client API `1.32` against a daemon whose `MinAPIVersion` is `1.40`,
  and it blocks *Testcontainers discovery* only. The `docker` CLI itself pulls and
  builds fine. Docker Hub egress is separately policy-filtered, which is why the
  images came from `public.ecr.aws/docker/library/…` and `quay.io/minio/…` and were
  retagged to the Hub names so this file's commands stay unmodified.

Per-step position, so nothing is quietly counted as done:

* **Step 2 is covered** by the smoke — `/checkpreload.htm` → 200 `success` and
  `/api/integrations/capabilities` → 200 `{"aoneEnabled":false}` — but against
  MinIO, not real OSS, so it satisfies the assertion and not the credential
  precondition of step 1.
* **Steps 1, 3 and 5 remain blocked** on real OSS/SLS credentials, and step 3's
  presigned-URL-over-`OSS_PUBLIC_ENDPOINT`-outside-the-VPC behaviour is
  OSS-specific with no faithful local substitute.
* **Steps 4 and 6 are unexercised gaps that nothing now blocks.** A running image
  and a local object store both existed during the smoke; the `enc:v1:`
  credential-ref assertion and the second-instance `AUTOWONDER_SIGAR_ENABLED=false`
  check were simply outside that step's scope, which targeted this cycle's new
  endpoints. They are named here rather than left implied by a withdrawn blocker,
  and are handed over. Note that step 4 matters more than it looks: this cycle's
  schema delta is six `SecretCrypto` column widenings, so the encryption path is
  touched code with no runtime assertion behind it.

All six steps remain release-environment acceptance items and are reported as
skipped, never as passed.

## Which commit this evidence describes

Rows above that name a measurement tip name `a144a5da0`. That was HEAD when they were
measured and it is still true of them, but HEAD has since advanced by documentation
commits from step `400360`'s own corrections, so the tip named in a row and the tip of
the branch are no longer the same object. This matters only if the difference could
invalidate a measurement, so it was checked rather than assumed:

```bash
git diff --name-only a144a5da0..HEAD          # 3 files, all .md
git diff --name-only a144a5da0..HEAD | grep -vc '\.md$'   # 0
for P in src frontend skills pom.xml APP-META docs/migration VERSION; do
  [ "$(git rev-parse "a144a5da0:$P")" = "$(git rev-parse "HEAD:$P")" ] \
    && echo "$P IDENTICAL" || echo "$P DIFFERS"
done
git diff --raw a144a5da0..HEAD | awk 'substr($1,2) == $2 { next } { print }'   # 0 mode changes
```

All seven product subtrees are byte-identical between the measurement tip and HEAD —
`src`, `frontend`, `skills`, `pom.xml`, `APP-META`, `docs/migration` and `VERSION` —
and zero file modes changed. Every build, test, Skill and dependency measurement above
therefore still describes the current tree, and none of it needed re-running. Do not
generalize this: it holds because the intervening commits touched only Markdown, and
the check above is what establishes that, not the claim.

Note for whoever pushes next: the correction commits are **local only** at the time of
writing, `origin/aw/community-sync-b52cdeeea-20260906` still points at
`ce87212436fcc98355b701134b2f21c56f0cb476`, and HEAD is strictly ahead with nothing
missing remotely, so the push is a fast-forward. Step `400361` owns it. `origin/community`
remains at `c3a75ae60462e3bdc93d1e9fb82041dc9d442f51` and
`git merge-base HEAD origin/community` returns that same commit, i.e. HEAD is a strict
descendant of the protected branch and the review is a clean fast-forward — but it is
still protected and still must not be auto-merged.

Two measurement hazards found while producing this round are recorded here because both
would otherwise silently pass a future gate. The first is the bare-`rg` stdin behaviour
described in **Automated Gates** above. The second is scope confusion between two diff
ranges that report the same metric name: enumerating "non-documentation commits" over
`c3a75ae6..HEAD` returns 200+ entries because that range includes all 276 merged
upstream commits, while the same question over `2813b524f..HEAD` returns 2. A first pass
at checking the release file's code-change claim used the wider range and produced a
wall of output that looked like a finding and was actually the wrong denominator. Always
name the range a count belongs to.
