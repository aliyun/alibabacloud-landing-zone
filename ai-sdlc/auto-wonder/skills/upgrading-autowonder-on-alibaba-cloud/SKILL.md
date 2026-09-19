---
name: upgrading-autowonder-on-alibaba-cloud
description: Use when upgrading the software of an existing AutoWonder community deployment on Alibaba Cloud, especially when the user says “开始升级” or needs upgrade inventory, environment comparison, database migration planning, staged release distribution, rolling activation, acceptance, or rollback analysis. Do not use for creating infrastructure, first-time deployment, scale-out, or teardown.
---

# Upgrading AutoWonder On Alibaba Cloud

Upgrade an existing AutoWonder community deployment.
Consume its sanitized manifest and live resource inventory; never recreate cloud
resources, ask the user to retype ECS IDs, or infer targets from names alone.

## Cloud Operations State

After platform bootstrap, always use `scripts/resolve-deployment.sh --search-root
<workspace>` (native `resolve-deployment.ps1 -SearchRoot <workspace>` on Windows).
It first discovers the dedicated **same-region operations bucket**, restores
the complete deployment/upgrade state, then returns the working manifest.
No local `deployments/` or `upgrade-info/` folder is needed after verified import.
Use `--region`/`--deployment-id` (`-Region`/`-DeploymentId`) to select among
multiple deployments; never select the first bucket arbitrarily.

Only confirmed cloud absence permits existing local discovery and automatic
initialization. Permission/network errors or corrupt/incomplete cloud records
must stop; never overwrite cloud state with a local manifest. Import must
retain the newer working upgrade manifest, original environment/secrets,
actual Terraform configuration, and active/pending sealed release baselines.
`deployment-resume-required` is an unfinished deployment, not an upgrade target.

This Skill includes `scripts/operations-store.py` and
`references/operations-state.md`. State is stored under separate `deploy/` and
`upgrade/` prefixes in `aw-ops-...`, never in the tfstate/packages/artifacts
buckets. It uses private encrypted objects and immutable history; **do not
enable bucket versioning** (it disables OSS conditional no-overwrite semantics).
Operations storage requires ossutil v2, including supported `aliyun ossutil`.
Use checkpointed wrappers; a failed upload, stale revision, or unresolved remote
submission blocks further mutation. Do not recreate missing original secrets,
clear database mutation flags, or re-register a restored working manifest.

`deployment.activeCommit` is the immutable active **release identity** retained
for compatibility; a workspace deployment uses a JAR content hash, not a Git
commit. Never relabel that identity as a guessed commit. Both platform planners
use `scripts/upgrade_plan.py` to compare a real Git baseline or verified sealed
artifacts with the exact target Git commit. Read the runbook's release-baseline
recovery procedure for historical workspace deployments.

## Trigger And Boundary

When the operator explicitly prohibits all Git inspection and requests a
same-version validation redeployment, use `plan-upgrade.sh
--workspace-current-content --force-redeploy`. This mode derives a 40-character
immutable release identity from the sorted current-workspace file set, excludes
generated/build/VCS directories without reading VCS metadata, and retains the
normal verification, approval, backup, staging, rollout, and acceptance gates.
Never invoke a Git command in this mode.
Require the recorded active release version and verified sealed baseline. This
same-version mode rejects any migration-file difference; it must never hide
pending migrations by substituting an empty migration list.
On POSIX invoke every Skill shell entrypoint through `bash`; copied files may
retain macOS quarantine metadata even when executable bits are present.
The upgrade build wrapper must copy the versioned target systemd unit into the
sealed release and record its hash with `source:"target-source"`; staging rejects
an unsealed or deployment-host unit.
Release sealing must be retry-safe: create the migrations archive in a temporary
file and atomically replace any prior read-only sealed archive.

When the user says “开始升级”, enter this workflow immediately. Do not route the
request back through the new-deployment questionnaire. This skill owns software
inventory, exact-version planning, candidate environment validation, release
staging, database migrations, rolling activation, acceptance, and upgrade
rollback planning. Infrastructure creation, Terraform scale-out, initial schema,
business initialization, and teardown remain in the deployment skill.

Before executing `V067__platform_admin_init.sql` (or another numbered migration
with that suffix), the database gate requires an existing user with
`is_deleted = 0 AND is_admin = 1`. Missing administrators or a failed query stop
migration; the upgrade never grants privileges. Resolve the administrator roster
through a separately reviewed recovery procedure before retrying. A prior
successful migration ledger entry retains the normal idempotent skip behavior.

Run unattended by default. Do not ask the user to confirm discovery, prerequisite
summaries, ordinary application changes, same-version redeployment, build,
backup, staging, rolling activation, acceptance, or bounded deterministic
repairs. The only routine discovery question is the top-level deployment folder
when the resolver returns `deployment-folder-required`. Browser OAuth may still
require the user to complete login. Present progress as informational updates
and deliver one consolidated report after acceptance.

An approved upgrade plan remains the mutation authority. Discovery, credential
validation, live target verification, inventory, and change analysis are
read-only with respect to cloud resources; their scripts may atomically update
sanitized local manifest checkpoints. Automatically approve and execute a plan
when `.upgrade.confirmationRequired` is false. Ask for explicit confirmation
only when the plan contains database migrations, destructive or rolling-
incompatible changes, an unexpected target/resource change, rollback, or a
repair outside the current plan. Never roll back automatically.

## Platform Selection

Detect the control host before running scripts. On macOS/Linux use the `.sh`
entrypoints. On Windows use the paired `.ps1` entrypoints plus the deployment
skill's native PowerShell Cloud Assistant adapter. Never run local shell scripts
on Windows. Both routes use the same
manifest fields, phase gates, exact commits, node order, and evidence contract.

The upgrade planner and remote Linux payload follow one platform-neutral policy.
If a native Windows adapter for a mutating phase is absent,
stop before that phase and report the missing adapter; do not translate a shell
command ad hoc or weaken a gate.

This bundle includes native Windows discovery, target verification, planning,
approval, build, backup, runtime-config, staging, RDS-backup verification,
migration gating, rolling activation, acceptance, and confirmed rollback
entrypoints. Use the `.ps1` file with the same basename and phase on Windows.
Do not substitute the Bash route on that host.

Before discovery, run this Skill's platform bootstrap and
read its `references/cross-platform-runtime.md`. Only disposable tools share the project's
`skills/.autowonder-tools` cache. Python is a pinned private runtime; compatible
JDK 21, Maven 3.9.9+, Terraform and cloud tools are reused before private downloads.
Never install packages globally or change the permanent PATH. Apply the
bootstrap's returned `runtimeEnvironment` to every later child process; a
completed bootstrap child cannot modify its parent shell's environment.
Git Bash and CMD can start the native Windows bootstrap; mutating phases still
use the native PowerShell adapters. WSL remains a Linux control-host session.
Do not treat locally mocked Windows tests as native Windows validation.

## Automatic Start Flow

Run these steps in order when upgrade starts:

1. Resolve cloud operations state first. An explicit manifest is an identity hint,
   not permission to override cloud data. Only when no cloud record exists,
   resolve the historical deployment manifest. Prefer an explicit path already present in
   the conversation. Otherwise run `scripts/resolve-deployment.sh --search-root
   <current-workspace>` or `scripts/resolve-deployment.ps1 -SearchRoot
   <current-workspace>`. New deployments are read from
   `<current-workspace>/deployments/<deploymentId>/deployment-manifest.json`.
   The resolver first reuses
   `upgrade-info/index.json`, then may register one current deployment manifest
   that contains the required upgrade files and identity fields. Manifest status
   and acceptance fields are not discovery gates. If neither exists it returns
   `deployment-folder-required`; ask once only for the name of a top-level folder
   under the project root and rerun with
   `--deployment-dir <folder>` (or `-DeploymentDirectory <folder>` on Windows).
   Recursively locate the deployment and required files inside that folder; never
   ask for a nested deployment subfolder. Reject missing or ambiguous required
   files rather than using manifest status as a proxy. Do not ask the user to
   reconstruct IDs, commits, resource lists, or backend fields. Discovery records paths and
   parsing rules in `upgrade-info/<deploymentId>/`, with directories mode `0700`
   and JSON files mode `0600`. The project-level `/upgrade-info/` is Git-ignored:
   persist it locally between upgrades but never commit it.
2. On every upgrade run, execute `scripts/refresh-upgrade-info.sh --manifest
   "$MANIFEST" --project-root <current-workspace>` (or the paired PowerShell
   adapter) before cloud verification or planning. The refresh entrypoint must
   normalize `cloudProfile` to the dedicated `auto-wonder` CLI profile, validate
   STS through the deployment bootstrap, and load that profile's credentials
   before initializing Terraform; never rely on ambient credentials. Reuse the recorded parsing
   rule, but always refresh Terraform outputs so newly scaled ECS nodes cannot be
   missed. Support local state and OSS remote state. Historical backend files may
   contain credentials: use a private temporary copy for Terraform initialization,
   never persist backend credentials, never print them, and persist only the
   explicit non-secret output allowlist. Remove temporary Terraform data and
   backend copies on success or failure.
3. Read `cloudProfile`, `region`, `deploymentId`, exact active commit,
   `.resources.ecs_instance_ids`, VPC/inventory data, and `.localContext` source,
   protected-env and Terraform-directory references from the refreshed working
   manifest. Treat its resource set fingerprint as part of the plan identity.
4. Run this Skill's platform bootstrap with the fixed `auto-wonder`
   profile. Never use the CLI current profile, `default`, or a historical
   manifest profile.
   It probes `sts GetCallerIdentity`. If the CLI profile is missing, logged out,
   or expired, automatically run `aliyun configure --profile auto-wonder --mode
   OAuth` and then repeat STS validation. This also returns the session
   environment file; load it before later local phases.
5. Verify the `auto-wonder` CLI profile with STS and compare the Terraform ECS set
   with the complete tagged cloud ECS set before any Cloud Assistant operation.
   Require exact set equality; do not verify only IDs already present in a cached
   manifest. Verify every ECS against the
   manifest before any Cloud Assistant operation. On macOS/Linux run
   `scripts/verify-deployment-targets.sh`; on Windows run the paired PowerShell
   script. Require the same region, instance IDs, VPC when recorded, and exact
   `Project`, `DeploymentId`, `Environment`, `ManagedBy`, and `Topology` tags.
   A valid STS identity with missing resources is not evidence of an expired
   login. Distinguish account mismatch, permission denial, region/ID/tag mismatch,
   and an incomplete inventory. Stop with sanitized evidence; never create or
   infer another target. Only a missing profile or a recognized credential error
   triggers OAuth. Transient API/network failures and unknown errors must not
   trigger login or replay a mutation. After correcting the cause, repeat STS
   and complete target verification before continuing.
6. If refresh or live verification finds newly scaled ECS, update the working
   inventory and resource set fingerprint, include every new node in verification
   and rollout order, and invalidate any prior plan approval. Never continue with
   an approval bound to an earlier resource set.
7. Record and optionally show the sanitized prerequisite summary required by the runbook. Values
   already recorded in the located manifest and confirmed by live target
   verification are sufficient; do not ask the user to reconfirm them. Never ask
   for individual infrastructure values. If required metadata cannot be recovered
   after the user supplies the top-level deployment folder, stop with one
   sanitized blocked report naming the missing fields and recovery evidence.
8. Run `scripts/upgrade-operations.sh upgrade-inventory` to reconcile every
   node's active release (the paired `.ps1` on Windows). Record each node's
   `jarSha256` and `migrationsSha256` from the active release. Stop when nodes
   disagree in identity or content, an archive is missing, or the manifest
   identity differs. A workspace baseline must match these live artifact hashes.
9. Never ask the user for a target Git ref. Fetch `origin/master`, verify that
   `origin` matches the manifest repository, and use an isolated clean detached
   worktree pinned to that exact remote commit. Resolve the AutoWonder project
   directory with `resolve_upgrade_project_source_dir`; never assume the detached
   worktree root is the project root. If local `master` has divergent
   commits or tracked changes, preserve it unchanged; do not merge, rebase, reset,
   or use it as the release source. Compare the remote commit with the reconciled active commit before
   running `scripts/plan-upgrade.sh`. Commit equality is the only
   version-availability check: if they are identical, report that the deployment
   is already the latest version and skip planning, approval, build, staging,
   migration, and activation unless the user explicitly requests a same-version
   redeployment. For that request, pass `--force-redeploy` to the planner and
   preserve every normal plan, approval, backup, staging, activation, and
   acceptance gate. If they differ, run the planner normally. Do not block
   because of Git ancestry and do not ask for a branch, tag, or commit.
   For a workspace release, retain the active content identity and let the
   planner recover its environment/migration baseline from hash-verified sealed
   artifacts. An empty historical `repositoryUrl` is accepted only for the
   explicit AutoWonder repository allowlist in the shared planner; never trust
   an arbitrary local `origin`. A missing historical artifact is a blocked
   recovery condition, not permission to change the active identity or skip DDL.
10. Generate one consolidated plan covering source commits, changed features,
   environment keys, migrations and DDL risk, backup/compatibility gates, build,
   node order, acceptance, rollback boundary, and the plan fingerprint. When
   `confirmationRequired` is false, immediately record it with
   `scripts/approve-upgrade-plan.sh --automatic`. Otherwise show the impact plan
   and wait for explicit approval. Any plan or target change invalidates it.

Read `references/upgrade-runbook.md` before planning or executing an upgrade.

## Approved Execution Route

For an ordinary non-destructive plan, record automatic approval with:

```bash
scripts/approve-upgrade-plan.sh \
  --manifest "$MANIFEST" --fingerprint "$PLAN_FINGERPRINT" --automatic
```

Omit `--automatic` only after the user explicitly approves an impact plan.

build the exact target in an isolated worktree with
`scripts/build-upgrade-release.sh`; this guarded wrapper uses the bundled
release builder. The systemd unit comes from the exact target source tree's
`skills/upgrading-autowonder-on-alibaba-cloud/assets/systemd/`, never another
Skill or a different executing checkout. Before changing the environment, systemd
unit, database, or active release, create and verify the single backup slot on
every ECS:

```bash
scripts/upgrade-operations.sh upgrade-backup --manifest "$MANIFEST"
```

Keep exactly one backup archive per ECS at
`/opt/autowonder/upgrade-rollback-backup.tar.gz`. A retry of the same plan reuses
the original verified archive; a new plan replaces it atomically only after
the active release and replacement checksums validate. Staging and rollback
require backup coverage of every target, bound to the current plan. Rollback
also checks the recorded archive SHA-256 before restoring any files.
Validate the candidate protected
environment file with:

On every upgrade, derive `autowonder.runtime.recommended-version` from the exact
target source `src/main/resources/application.yml`, update
`AUTOWONDER_RUNTIME_RECOMMENDED_VERSION` in the protected candidate environment,
and bind its version and resulting file hash to the approved plan. The
`runtime-config` checkpoint must match that target-derived version before stage;
stage installs the candidate on every ECS before any rolling restart. A resumed
stage or rolling activation must revalidate this checkpoint and cannot reuse a
historical manifest runtime version.

Before planning, record the key escrow's existing opaque UUIDv4 in the protected
candidate as `AUTOWONDER_SECRET_KEY_GENERATION_ID`. This is operator metadata,
not an application secret or proof of escrow. Do not invent an ID to claim
custody, rotate the master key, or put the key in the manifest. Plan approval
binds this ID, the candidate hash and target runtime; changing any invalidates
approval. `runtime-config` accepts only that candidate or its prepared checkpoint,
then binds the normalized environment hash to the same approved plan and ID.
The checkpoint stays in the protected working manifest (POSIX mode `0600`,
Windows current-user ACL). Stage rechecks each installed environment; rollout
rechecks before and after activation. A mismatch stops the phase.
Use this Skill's `scripts/sanitize-evidence.sh` for shareable reports; it removes
environment hashes and secret fields. Preserve the escrow ID's association with
database backup generations off-node; rollback archives do not provide escrow.
Previously approved plans without this generation binding must be regenerated
and approved through the existing plan policy; an old prepared checkpoint is
not grandfathered in.

```bash
scripts/upgrade-operations.sh runtime-config \
  --manifest "$MANIFEST" --env-file "$CANDIDATE_ENV" \
  --terraform-dir "$TERRAFORM_DIR"
```

Stage without changing `/opt/autowonder/current`:

```bash
scripts/stage-upgrade.sh \
  --manifest "$MANIFEST" --env-file "$CANDIDATE_ENV" \
  --release-dir "$RELEASE_DIR"
```

When no migrations exist, record the no-op with `database-migrate`. When they
exist, first run `scripts/verify-rds-backup.sh --manifest "$MANIFEST"`; it binds
a recent successful backup ID to the manifest RDS instance. Then require explicit migration and rolling-
compatibility confirmation. Destructive or active-version-incompatible DDL is
blocked from rolling upgrade. Then run `rolling-upgrade` sequentially. It performs
ECS-local-only acceptance and records the upgrade as accepted as soon as all ECS-local checks pass;
`acceptance` is only an idempotent local-state confirmation. Never run the deployment skill's initial `database`,
`rolling-start`, or `business-init` operations during an upgrade.

If a phase fails, collect sanitized evidence, diagnose the root cause, and
automatically apply a bounded deterministic repair when the target commit,
resource set, plan fingerprint, database boundary, and planned cloud operations
remain unchanged. Examples include credential refresh, manifest normalization,
monorepo source resolution, using a fresh clean worktree, and retrying an
idempotent transfer or acceptance confirmation. Revalidate the plan binding and
resume at the failed idempotent boundary. If repair changes those boundaries or
requires rollback, show the impact and ask for confirmation. Only after the user
confirms rollback, run:

```bash
scripts/upgrade-operations.sh rollback-upgrade \
  --manifest "$MANIFEST" --confirm-rollback
```

The command restores the backed-up release, protected environment, and systemd
unit sequentially on every ECS and verifies local health. It is blocked after
database mutation starts, including a failed or interrupted migration; those
require a separately reviewed recovery plan and are
never reversed automatically.

## Resource Handoff Contract

The deployment manifest is the source of truth. New deployments and every
Terraform inventory refresh must preserve at least:

- deployment ID, environment, region, `cloudProfile: "auto-wonder"`, repository URL, and
  exact active commit;
- ECS IDs plus VPC, VSwitch, private IP, expected tag, ALB, RDS, Redis, OSS, and
  SLS inventory where available;
- protected environment-file reference or evidence and its hash, without secret
  values;
- Terraform state reference and inventory timestamp;
- release hashes, Cloud Assistant invocation IDs, acceptance, and upgrade state.
- `source.baseline` with its release identity, sealed artifact hashes,
  environment contract and migration checksums; preserve the previous active
  baseline during an unfinished upgrade even if the target build replaces
  `source` and `artifacts`.

Planned Terraform scale-out remains a deployment operation. After its inventory
refresh and node initialization complete, the manifest contains the expanded ECS
set; the next upgrade automatically verifies and targets the complete set.

## Safety Rules

- Use only the dedicated `auto-wonder` CLI profile and the region recorded by
  deployment; normalize historical or missing `cloudProfile` values and refresh
  OAuth/STS before cloud API calls. Never place AK/SK/STS values in the manifest
  or output.
- A manifest is not sufficient by itself: verify live ECS identity and tags
  before planning and automatically refresh that read-only verification before
  every mutation. An unchanged target fingerprint preserves approval; any target
  change invalidates it.
- Every mutating upgrade entrypoint requires an approved plan fingerprint that
  still matches the current plan and target-verification checkpoint.
- Use the protected application environment produced by deployment. Never ask
  the user to paste database or application secrets into chat.
- Build and stage only an immutable release identity: an exact 40-character Git
  commit, or the supported sealed workspace identity bound to artifact hashes. Keep the previous immutable
  release and environment snapshot until acceptance.
- Database migrations run once in numeric order with the migration ledger and
  named lock. They are never reversed automatically.
- A failed migration, destructive database change, ECS target mismatch, or
  resource-set change requires impact review. Other failures are repaired and
  retried automatically only while all approved plan boundaries remain unchanged.
- Upgrade acceptance does not inspect ALB, certificates, or domain names and the
  upgrade workflow must never resolve, probe, request, or validate a domain.
- Upgrade acceptance does not require RDS, Redis, OSS, SLS, restart, executor
  WebSocket, tags, or secret-log acceptance checks. Database migration gates
  remain applicable before activation when the target contains migrations.

## References

- `references/upgrade-runbook.md`: detailed inventory, planning, migration,
  staging, rolling activation, acceptance, and rollback gates.
- This Skill includes its release builder, credential helpers, systemd unit,
  state recovery modules, and acceptance primitives. It runs without any other
  Skill installed; only `skills/.autowonder-tools` may be shared.

## Output Contract

Report only after success unless user action is required. Include deployment ID,
environment, region, source and target release identities with their kinds, verified
node count, plan status, migration status, per-node ECS-local rollout status,
application acceptance, rollback boundary, and sanitized evidence paths.
Never print credentials, protected environment values, presigned URLs, or raw
Cloud Assistant output containing secrets.

## Deployment build and health scope

Release builds run `clean package -Dmaven.test.skip=true` with
`-DskipFrontend=false`: compile the application and frontend, but do not compile
or execute application tests. Keep archive integrity, frontend asset, hash,
source identity, initialization, and per-node activation checks. Build failures
still stop deployment. Do not run frontend lint/unit tests or local release testing as part of a
cloud deployment or upgrade. Application
quality checks belong to the release pipeline; this workflow does not certify
application business behavior. Skill maintenance may run its own offline fixtures.

The deployment health endpoint is a liveness response, not a database, Redis,
executor, or storage business test. Do not create test Agents/executors or run
file upload/download smoke tests unless explicitly requested. Keep the existing
acceptance boundary: new deployment checks both ALB EIPs after node activation;
Upgrade acceptance checks ECS locally only; the validation workflow must not
append public EIP or business checks to an upgrade. Preserve OSS checkpoints
and recovery artifacts.

## Optional operation timing

On POSIX hosts, set `AUTOWONDER_METRICS_FILE` to a JSONL file in an existing
private directory using a resolved absolute path. The file must be owned by the
current user, mode `0600`, with no symlink components. Set
`AUTOWONDER_METRICS_PHASE` before each phase (`bootstrap`, `resolve`, `refresh`,
`verify-targets`, `inventory`, `plan`, `approve`, `build`, `backup`,
`runtime-config`, `stage`, `database-migrate`, `rolling-upgrade`, `acceptance`).
Instrumentation records fixed operation names, elapsed time, outcome and bounded
file/byte/call counts. It does not record credentials, command arguments or raw
cloud output. Omit the file variable to disable recording. Windows currently
skips optional metrics because private-file ACL verification is not implemented.
Metrics failures cannot fail or replay a cloud operation.

Checkpoint, bundle collection and OSS transport timings are nested and inclusive;
do not add them together as wall-clock duration. Report phase wall-clock totals
separately. These measurements support later checkpoint optimization; they do
not change checkpoint ordering, locks, backup or write-ahead invocation records.
CLI failures expose only recognized error categories/codes and a validated UUID
request ID when available. Unknown diagnostics remain unknown and sanitized.
