# AutoWonder Alibaba Cloud Upgrade Runbook

## Purpose

Upgrade an existing community deployment to an exact GitHub commit without
mutating the active release until source, configuration, database, build, and
rollback checks have passed. Use this Skill's immutable releases,
private OSS transfer, sequential activation, probes, acceptance, and rollback.

Use this only for an existing deployment with the files and identity fields
required by the upgrade workflow. Discovery does not gate on manifest status or
acceptance fields. New installations follow the normal phase state machine and
import the complete schema exactly once.

## Prerequisite Context Gate

Resolve the dedicated operations bucket before relying on local deployment
files. See `references/operations-state.md` in this Skill.
The returned manifest and recovered paths are authoritative; a cloud permission
error is not a reason to use an older local manifest. Preserve all migration and
invocation checkpoints when restoring, then rerun live prerequisite checks.

Detect the control host first and run this Skill's matching bootstrap
adapter. The adapter checks all supported third-party dependencies and installs
missing supported third-party dependencies without conversational confirmation,
including Alibaba Cloud CLI. It must use only the dedicated `auto-wonder`
profile and validate it with `sts GetCallerIdentity`; a missing or expired
identity triggers `aliyun configure --profile auto-wonder --mode OAuth`
automatically, followed by another STS probe. Historical or missing manifest
profiles are normalized to `auto-wonder`; never fall back to the CLI current or
`default` profile.

Target verification compares the complete live ECS inventory with the recovered
manifest. A successful STS probe with missing targets does not establish an
expired login. Distinguish account mismatch, permission denial, region/ID/tag
mismatch and incomplete inventory; report sanitized evidence and stop. Only a
confirmed missing profile/session or recognized credential failure triggers
OAuth. Do not trigger login for transient API/network failures or unknown errors,
and do not replay mutations. Repeat STS and complete target verification after
the underlying cause is corrected.

Discovery and target verification are read-only with respect to cloud
resources, but may atomically write sanitized evidence checkpoints to the local
manifest. On a Windows control host use the same-basename `.ps1` entrypoints for
discovery, verification, planning, approval, build, backup, configuration,
staging, migration gating, rollout, and acceptance. Never substitute the Bash
mutation route.

Resolve the working manifest automatically first. Reuse
`upgrade-info/index.json` when present; otherwise register one current deployment
manifest containing the required upgrade files and identity fields. Only when
neither exists may discovery ask once for the name of a top-level folder under
the project root and pass it as `--deployment-dir`. Search that folder
recursively for the unique deployment, Terraform root, inventory, protected
environment, state/backend reference, and deployment metadata. Never ask for a
nested deployment subfolder, and never use manifest status or acceptance fields
as a discovery gate. Never ask the user to retype ECS IDs or reconstruct fields
already produced by deployment:

- ECS instance IDs are needed for active-release inventory, artifact staging,
  rolling activation, and rollback.
- Database connection information is needed only when the target change set
  contains DDL or DML. Confirm host, port, database, username, connectivity
  scope, and a protected credential source such as the mode-`0600` environment
  file. Never display or copy the database password into chat, commands, or the
  manifest.

If all applicable values are already available, record or show a sanitized
prerequisite summary and continue without confirmation. Recover missing values
from registered deployment artifacts. Ask the user only when the resolver needs
one top-level deployment folder; never ask for individual infrastructure values.
If required metadata remains unavailable after that folder is registered, stop
with one sanitized blocked report naming the missing fields and recovery evidence.
In particular, do not run remote ECS operations without verified instance IDs, and
do not build, stage, or execute a release containing DDL or DML until its
database access context is verified.

## Persistent Upgrade Information Gate

`upgrade-info/index.json` selects the active deployment. Each
`upgrade-info/<deploymentId>/` contains `discovery.json`, `inventory.json`,
`manifest.json`, `upgrade-state.json`, and sanitized `runs/<runId>/summary.json`.
These files persist locally across upgrades, use protected permissions, and are
excluded by `/upgrade-info/` in `.gitignore`; never commit or publish them.

All records are JSON objects with `schemaVersion`, are written atomically, and
contain only the fields below. Unknown source fields are ignored rather than
copied. Directories use mode `0700` and files mode `0600` on POSIX; Windows uses
the current-user-only protected-file contract.

| File | Required content | Forbidden content |
| --- | --- | --- |
| `index.json` | active deployment ID; per-deployment project-relative source folder, info folder, and last-used time | credentials, resources, absolute paths |
| `discovery.json` | deployment ID; source/Terraform folders; backend mode; local-state or backend-config reference; workspace; output bindings; rule/resolver revision; validation hashes/times | backend file contents, environment values, access keys, tokens |
| `inventory.json` | region; allowlisted VPC/VSwitch/ECS/ALB/RDS/Redis/OSS/SLS identifiers/endpoints; topology; current/previous resource set fingerprint; added/removed/unchanged ECS IDs; node counts; cloud verification status | Terraform raw output, passwords, connection secrets, signed URLs |
| `manifest.json` | sanitized working identity, local references, current resources, full active commit, upgrade plan/checkpoints, and resource set fingerprint | protected environment contents and raw command output |
| `upgrade-state.json` | source/target commit; plan/resource fingerprints; migration status; rollback boundary; acceptance time; latest run ID | backup payloads, database credentials, invocation output |
| `runs/<runId>/summary.json` | operation, commits, resource fingerprint, node count/status, migration status, acceptance result, rollback boundary, completion time | raw Terraform/Cloud Assistant output, command arguments containing secrets |

The first folder registration discovers exactly one Terraform root and records
only project-relative paths and deterministic parsing bindings. Reject ambiguous
roots, conflicting identity, symbolic links, and references outside the project.
For a nested deployment layout, bind `backend.hcl` from the Terraform directory
or its ancestor chain up to the supplied top-level folder; reject multiple
matching backend files. After a successful registration, resolve later runs from
`upgrade-info/index.json` without asking for the folder or reparsing its layout.
The deployment may use local state or OSS remote state. An OSS backend file may
contain historical secrets that cannot be changed: Terraform may read a mode
`0600` temporary copy, but upgrade discovery must never persist backend
credentials, log them, or copy them into the manifest, inventory, state, or run
summary. Persist only the allowlisted deployment identity and resource outputs.

On every upgrade run, `refresh-upgrade-info` reuses `discovery.json` but executes
Terraform output again. Before Terraform initialization, the wrapper validates
STS through the deployment bootstrap and loads temporary credentials from the
`auto-wonder` CLI profile; it
does not rely on ambient Alibaba Cloud credentials. It compares the prior set, current Terraform set, and
the complete tagged cloud ECS set. Exact Terraform/cloud equality is required.
A changed resource set fingerprint records added and removed nodes, makes newly
scaled ECS part of all later phases, and invalidates any prior plan approval,
backup binding, or target-verification checkpoint. A missing node, extra tagged
cloud node, identity conflict, or unfinished scale-out stops before mutation.

Current deployment and scale-out acceptance call `upgrade_info.py
register-manifest`; this refreshes the persistent inventory after new nodes pass
deployment acceptance. Historical folders need the operator only on their first
registration. Later upgrades reuse the stored folder and parsing rules while
still refreshing state and cloud membership. Discovery remains local/read-only
with respect to Alibaba Cloud resources and authorizes no upgrade mutation.

## Upgrade Change Boundary

The approved upgrade plan is the **only mutation authority**. Non-destructive
plans with `.upgrade.confirmationRequired=false` are approved automatically by
the guarded approval entrypoint. Impact plans require human confirmation and
must cover every planned database or recovery operation. Do not clean up,
delete, recreate, resize,
reconfigure, replace, restart, or otherwise mutate any user deployment resource
outside that plan. Never substitute teardown, new-deployment, or ad hoc repair
commands for an upgrade step.

If execution differs from the plan, collect sanitized diagnostics and compare
the proposed repair with the approved commit, resource set, fingerprint,
database boundary, and cloud-operation set. If all remain unchanged, apply a
bounded deterministic repair and resume from the failed idempotent boundary
without confirmation. Use a fresh detached worktree instead of resetting a
dirty build worktree. Ask the user only when a repair changes a boundary,
applies database impact, changes targets, or requires rollback. Never roll back
automatically.

## Deterministic Command Route

### Release Baseline Recovery

An active release ID is not necessarily a Git commit. New workspace deployments
use a JAR content identity and seal `source.baseline`; historical deployments
may have only the JAR and migration-archive hashes. Keep the real active identity
in `deployment.activeCommit` and `upgrade.fromCommit` throughout the upgrade.

1. Refresh targets and run active inventory. Every node must report the same
   release directory, `jarSha256`, and `migrationsSha256`; missing artifacts or
   content differences stop planning.
2. When the active ID is a real Git object, compare that commit with the exact
   target. Otherwise the planner reads the original sealed `auto-wonder.jar`
   and `autowonder-migrations.tar.gz` from the recorded artifact directory.
   It verifies their full SHA-256 values against the manifest and every live
   node. For a historical release without a baseline, the JAR hash prefix must
   also establish the recorded active release ID.
3. Recover environment placeholders from the sealed JAR and published migration
   checksums from the sealed archive. New deployments additionally retain the
   complete source environment contract in `source.baseline`. Never use the
   target source as the old database baseline.
4. If the original release directory has moved, pass `--baseline-dir <folder>`
   or `-BaselineDirectory <folder>` to the planner. This changes only where
   evidence is read; it cannot override its checksums or release identity.
   Missing evidence requires recovery of the original sealed artifacts before
   continuing. Do not edit `activeCommit` to a guessed Git SHA.

The planner preserves `deployment.activeReleaseBaseline` before a target build
can replace `source` or `artifacts`. A failed build or transfer must not cause a
subsequent plan to compare the target release against itself. Once database
mutation has started, an unfinished upgrade must resume its existing reviewed
plan; replanning must not erase the migration checkpoint or permit rollback.

If a historical manifest has an empty `repositoryUrl`, the default allowlist
remains conservative. A verified internal repository or repository migration
can be selected explicitly with --repository-url and --allow-repository-change
after authorization; the exact source is then bound to the upgrade plan. Never
change the deployment target to find a matching local repository. SSH
and HTTPS spellings normalize to the same identity. All Git command failures
stop planning; an unsuccessful diff is never an empty migration result.

### Platform Commands

For an explicitly requested same-version Skill validation where the operator
forbids all Git inspection, replace the Git fetch/worktree planning route with:

```bash
scripts/plan-upgrade.sh --manifest "$MANIFEST" --source-dir "$SOURCE" \
  --env-file "$CANDIDATE_ENV" --workspace-current-content --force-redeploy
```

The planner hashes the sorted current-workspace file set into a distinct release
identity and the build wrapper verifies the same identity before building. It
must not inspect `.git` or invoke Git, and it still uses all normal cloud target,
approval, backup, staging, migration, rolling, and acceptance gates.
The recorded active release version and verified sealed artifacts are required;
any migration-file difference is rejected in this validation-only mode.

Use protected local paths for the manifest and environment file. Never place
their contents or secret values in command arguments or evidence.

On Windows invoke the paired PowerShell scripts with native parameter names,
including `-Manifest`, `-SourceDirectory`, `-EnvFile`, `-ReleaseDirectory`,
`-Fingerprint`, `-Automatic`, and `-ForceRedeploy`. Use
`upgrade-operations.ps1 <operation>` for phase operations. The phase order and
confirmation gates below are identical on both platforms.

Windows staging uploads the sealed JAR, migration archive, target systemd unit,
and candidate environment through private OSS transfer. It validates downloaded
hashes before installing files and records evidence for every node. The remote
Python payload runs only on Linux ECS; Windows does not execute a local `.sh`.
It accepts both existing migration archive layouts (`./V*.sql` from the POSIX
builder and `migration/V*.sql` from the Windows builder), retaining the same
archive hash and rejecting unsafe paths or links.
When resuming a POSIX-staged release that lacks an in-directory systemd unit,
Windows staging may add the verified target unit only after all existing release
objects match their sealed hashes; a different existing file still blocks it.
Backup includes the resolved release contents, environment and systemd unit,
with a verified archive and mode `0600`. A retry for the same plan reuses the
original verified backup instead of replacing it with a partly upgraded state.

Validate repository changes locally with:

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -B -m unittest discover \
  -s skills/upgrading-autowonder-on-alibaba-cloud/tests -v
```

The Linux payload tests exercise real file transfer, archive verification,
backup/restore and migration control flow with service/database boundaries
stubbed. They do not contact Alibaba Cloud. On a Windows host, also run the
native PowerShell/ACL tests explicitly:

```powershell
python -B -m unittest discover -s skills/upgrading-autowonder-on-alibaba-cloud/tests -p test_windows_upgrade_gates.py -v
```

Missing PowerShell or Windows ACL support is reported as skipped coverage;
passing static checks must not be reported as a Windows end-to-end upgrade.

```bash
scripts/resolve-deployment.sh --search-root "$PROJECT_ROOT"
scripts/refresh-upgrade-info.sh --project-root "$PROJECT_ROOT" --manifest "$MANIFEST"
scripts/verify-deployment-targets.sh --manifest "$MANIFEST"
scripts/upgrade-operations.sh upgrade-inventory --manifest "$MANIFEST"

scripts/plan-upgrade.sh \
  --manifest "$MANIFEST" \
  --source-dir "$SOURCE" \
  --env-file "$CANDIDATE_ENV"

scripts/approve-upgrade-plan.sh \
  --manifest "$MANIFEST" \
  --fingerprint "$PLAN_FINGERPRINT" \
  --automatic

TARGET_COMMIT=$(jq -r '.upgrade.toCommit' "$MANIFEST")
PROJECT_PREFIX=$(git -C "$SOURCE" rev-parse --show-prefix)
git -C "$SOURCE" worktree add --detach "$TARGET_WORKTREE" "$TARGET_COMMIT"
TARGET_SOURCE="$TARGET_WORKTREE/${PROJECT_PREFIX%/}"

scripts/build-upgrade-release.sh \
  --manifest "$MANIFEST" \
  --source-dir "$TARGET_SOURCE" \
  --output-dir "$RELEASE_DIR"

scripts/upgrade-operations.sh upgrade-backup --manifest "$MANIFEST"

scripts/upgrade-operations.sh runtime-config \
  --manifest "$MANIFEST" \
  --env-file "$CANDIDATE_ENV" \
  --terraform-dir "$TERRAFORM_DIR"

scripts/stage-upgrade.sh \
  --manifest "$MANIFEST" \
  --env-file "$CANDIDATE_ENV" \
  --release-dir "$RELEASE_DIR"
```

When no migrations are pending, record the no-op directly:

```bash
scripts/upgrade-operations.sh database-migrate --manifest "$MANIFEST"
```

When migrations are pending, first verify the named RDS backup and atomically
record its sanitized evidence reference in `.upgrade.databaseBackup` with status
`verified`. After the user confirms the displayed migration and compatibility
risks, run:

```bash
scripts/upgrade-operations.sh database-migrate \
  --manifest "$MANIFEST" \
  --confirm-migrations \
  --confirm-rolling-compatible
```

Then activate and verify:

```bash
scripts/upgrade-operations.sh rolling-upgrade --manifest "$MANIFEST"
# Optional idempotent confirmation; rolling-upgrade already records acceptance.
scripts/upgrade-operations.sh acceptance --manifest "$MANIFEST"
scripts/sanitize-evidence.sh \
  --input "$MANIFEST" --output "$SANITIZED_REPORT"
```

`SOURCE` may be a standalone repository root, a monorepo root, or the AutoWonder
project subdirectory. The planner and build wrapper resolve the unique project
directory from `src/main/resources/application.yml`, `VERSION`, and `pom.xml`.
The target must include this upgrade Skill's versioned systemd asset for sealing. Preserve
the project-relative path in the detached target worktree. Do not run the new-deployment `database` or
`business-init` subcommands during
an upgrade. Remove the temporary target worktree only after its build and hashes
are recorded; keep the sealed release until acceptance and rollback retention
requirements are satisfied.

## Upgrade Contract

- Treat the active deployed commit and the local source commit as separate
  facts. Record both before synchronizing the repository.
- Discover the recorded repository default branch (or use an explicitly selected
  ref), verify its identity, and create an isolated clean detached worktree at
  the exact fetched commit. An explicitly authorized source repository change
  remains bound to the same deployment and is recorded in the plan. Preserve a dirty, ahead, or divergent
  local branch unchanged; never merge, rebase, reset, or build it. The planner
  accepts that detached target worktree and still rejects tracked changes.
- Compare the reconciled active commit with the fetched target commit
  before producing an upgrade plan. Commit equality is the only
  version-availability check. If they match, report that the deployment is
  already the latest version and skip planning, approval, build, staging,
  migration, and activation. Only when the operator explicitly requests a
  same-version redeployment, run the planner with `--force-redeploy`; the plan
  records that intent and retains all normal mutation gates. If they differ,
  continue planning. Do not block
  because of Git ancestry between the active deployment and the target commit.
- Build with the frontend enabled and reject a JAR without `static/index.html`
  and compiled static assets.
- Write and validate environment changes before starting the target release.
- Never apply `docs/autowonder-schema.sql` to an existing database.
- Stop before any database migration until the operator confirms the migration
  plan and backup evidence.
- Activate one ECS at a time. Do not continue after a failed node probe.
- Keep the previous immutable release and environment snapshot until the target
  release is accepted.

## Phase 1: Inventory And Fetch

Record a sanitized upgrade evidence directory containing:

- deployment ID and region;
- local source repository URL, branch, clean/dirty state, and commit;
- active release commit from `/opt/autowonder/current` on every ECS;
- current JAR, systemd unit, and environment-file SHA-256 values;
- current database backup policy and latest successful backup time;
- target remote URL, ref, and resolved commit.

Abort when active nodes report different release commits, repository identity
validation fails, the isolated target worktree has tracked changes or is not
pinned to the exact fetched target commit, the target commit is unavailable,
or the target architecture is not Linux x86_64. Preserve the operator's local
branch and working tree; their divergence is not an upgrade blocker. Explicit
same-version workspace validation follows its separate no-Git route above.

## Phase 2: Change And Risk Plan

Compare the active commit with the exact target commit. The generated plan must
include:

1. commit subjects grouped into features, fixes, configuration, database,
   dependencies, and operations;
2. changed files and the exact active and target commit identities;
3. environment variables referenced by `application*.yml`, the community env
   template, deployment manifest, systemd unit, and Skill scripts at both commits;
   parse `KEY=...` declarations only from the env template and `${KEY...}`
   references from other sources; never treat Shell assignments as application
   environment variables. Control-host script references are informational;
   application configuration, environment templates and systemd units define
   required candidate values;
4. added, removed, or default-changed variables and the source file for each;
5. added, modified, or deleted `docs/migration/*.sql` files;
6. changes to ports, health probes, OSS/SLS endpoints, credentials, Java/Node/
   Maven requirements, runtime versions, and external service contracts;
7. application rollback compatibility with the target database schema.

Do not print secret values. Mark the plan blocked when a required environment
value is missing, an existing migration was modified or deleted, migration
versions are duplicated or out of order, the build requirements are unavailable,
or application rollback compatibility cannot be established.

## Phase 3: Environment Preparation

Create a root-readable snapshot of `/etc/autowonder/autowonder.env` before any
change. Merge only reviewed keys into a candidate file, preserve unchanged
secrets, reject placeholder values, and run the same preflight validation used
for a new deployment. Validate all target-required variables, including an
existing key whose default disappeared; evaluate nested fallbacks against the
candidate values. Compare `AUTOWONDER_SECRET_MASTER_KEY` to the preserved active
environment and reject a missing or changed key without exposing its value.
No key-generation UUID is required.
Record its SHA-256 after final validation and require the staged file to match it.

For every upgrade, read `autowonder.runtime.recommended-version` from the exact
target commit's `src/main/resources/application.yml` (including the default in
the `AUTOWONDER_RUNTIME_RECOMMENDED_VERSION` placeholder). Atomically upsert that
value into the protected candidate as
`AUTOWONDER_RUNTIME_RECOMMENDED_VERSION`, then bind the target-derived version
and updated candidate hash to the plan. `runtime-config` must record a matching
checkpoint. Stage must install that candidate on every ECS before rolling
activation; both stage and a resumed rolling activation reject a missing,
mismatched, or stale checkpoint.

Distribute and atomically install the candidate on all nodes before activating
the target application. Do not remove an old variable merely because the target
no longer reads it; removal is a separate confirmed cleanup after acceptance.

## Phase 4: Database Migration Gate

Incremental migrations live in `docs/migration/` and use this immutable naming
contract:

```text
V1__description.sql
V2__description.sql
V3__description.sql
```

`V` is uppercase, the positive numeric version is unique and strictly
increasing, and optional zero padding such as `V036` is accepted. A merged
migration is never modified, renamed, or deleted. Only migrations added between
the active and target commits are eligible for the upgrade.

Before confirmation, report for every new migration:

- version, filename, SHA-256, and source commit;
- DDL operations and affected tables/indexes;
- lock duration, data rewrite, capacity, and downtime risks when inferable;
- compatibility with both the active and target application versions;
- backup identifier, completion time, and restore instructions;
- whether application-only rollback remains safe after the migration.

Require explicit operator confirmation when migrations exist. Destructive or
active-version-incompatible DDL requires a maintenance window and must not use
normal rolling activation.

Execute confirmed migrations once, in numeric order, from one controlled node.
For plans whose fingerprinted `upgrade.executionMode` is `maintenance`, finish
release staging and verified backups, then run `upgrade-operations.sh
maintenance-stop --manifest "$MANIFEST"` (PowerShell: `upgrade-operations.ps1
maintenance-stop -Manifest $Manifest`). This stops every verified ECS application
service and records the approved plan and complete target set. It does not drain
running agent work: agree the interruption window and let active work settle
before entering maintenance. `database-migrate --confirm-migrations` then
rechecks every service is inactive, has no main process, and has no port 7001
listener before applying SQL. The rolling-compatibility flag is not used in this
mode. `rolling-upgrade` subsequently starts target nodes sequentially with the
existing per-node acceptance checks; the database remains marked non-rolling.
An interrupted migration requires reviewed recovery. Activation may resume only
when previously passed nodes still match the approved target, artifacts and live
health checks, while all remaining nodes remain stopped for the same plan. Neither
the old application nor failed SQL is automatically retried. Do not edit
risk classifications, stop evidence, or `rollingAllowed` to bypass these gates.

`DROP INDEX` requires maintenance review but does not delete application rows.
For the V052–V070 batch, also review V059's expanded retry uniqueness, V062's
invalidation of old conversation tokens, and the existing administrator required
before V067 records initialization. A successful health endpoint cannot establish
that a usable platform administrator exists.
Record version, filename, SHA-256, target commit, start/end time, and result in a
database table named `autowonder_schema_history`. The migration runner creates
this ledger when absent and serializes execution with the MySQL named lock
`autowonder-community-migration`. A successful recorded version with the same
checksum is skipped; a different checksum or a previous failed record is fatal
and requires reviewed repair rather than automatic retry. Stop on the first
failure and do not activate the target release.

## Phase 5: Build And Stage

Build the exact target worktree with `-DskipFrontend=false` and
`-Dmaven.test.skip=true`; frontend assets remain part of the release build.
By default do not run backend tests, frontend tests/lint, Docker checks, or
business workflows during a customer upgrade. Those are separate source-release
verification tasks only when explicitly requested. Retain artifact integrity and
release sealing checks, recording the exact commit and SHA-256 values in the
manifest. When repairing this Skill, run the relevant Skill regression tests
locally; they are not application acceptance gates.

Before any ECS environment, systemd unit, database, or active-release mutation,
run `upgrade-backup`. It creates exactly one backup archive per ECS at
`/opt/autowonder/upgrade-rollback-backup.tar.gz`, containing the complete active
release, protected environment, systemd unit, release identity, and checksums.
The archive is built and validated in a temporary path. Retries of the same plan
reuse the original verified archive, including when candidate configuration has
already been installed. A new plan may atomically replace the slot only while
the active release still matches its planned source. A failed replacement
leaves the old archive intact. Staging and rollback require all target nodes and
the current plan to match the backup evidence; rollback checks the recorded
archive SHA-256 before extraction. Do not retain per-target upgrade snapshots
elsewhere.

Upload the sealed release through private OSS staging and install it under
`/opt/autowonder/releases/<target-commit>/` without changing the active symlink.
Stage the validated environment file and systemd unit on every node only after
the per-ECS backup gate passes.

## Phase 6: Database And Rolling Activation

After the database confirmation gate, apply pending migrations and verify the
history records. Then activate one node at a time using the existing atomic
`/opt/autowonder/current` symlink and systemd workflow.

For each node require:

- `systemctl is-active autowonder` succeeds;
- port 7001 is listening;
- `/opt/autowonder/current` and the JAR checksum match the target commit;
- `/checkpreload.htm` succeeds;
- the node-local `/api/platform/branding/public` probe succeeds.

This is **ECS-local-only acceptance**. It does not inspect ALB, certificates, or domain names,
and it never performs DNS resolution or a public-ingress probe. When all ECS-local checks pass,
`rolling-upgrade` immediately records `rollingUpgrade.status=passed`,
`acceptance.ecsLocalHealth=passed`, and the overall upgrade status as `accepted`.
A failed node-local check stops the rollout and records that human resolution is required. Preserve the failed state and its
evidence; do not automatically restore, restart, or otherwise change the node.

## Phase 7: Acceptance And Rollback

Upgrade acceptance does not require RDS, Redis, OSS, SLS, restart, executor WebSocket, tags, or secret-log acceptance checks.
Those checks belong to new-deployment acceptance or separate diagnostics and
must not hold an ECS software upgrade in `partial`. The `acceptance` command is
an idempotent confirmation of complete ECS inventory coverage, target commit,
and recorded node-local success; it performs no additional network or service
probes. Keep the previous release, environment snapshot, plan, hashes, migration
evidence, and rollback boundary in the sanitized handoff.

After a separate risk review and explicit human confirmation, an approved
application rollback uses `upgrade-operations.sh rollback-upgrade --manifest
"$MANIFEST" --confirm-rollback` to restore the single verified backup one node
at a time. This is the explicit rollback confirmation gate; the flag may be
passed only after the user confirms the displayed impact. Never roll back
automatically. If database migrations were applied, one-click application
rollback is blocked; follow the reviewed database restore or forward-fix plan.

## Confirmation Gates

| Gate | Confirmation required |
| --- | --- |
| Ordinary application upgrade or explicit same-version redeployment | No; approve automatically |
| OAuth refresh, build, backup, staging, rollout, acceptance, bounded deterministic repair | No |
| Deployment directory cannot be resolved uniquely | Ask once for the top-level folder |
| New database migrations | Confirm risk summary, backup, compatibility, and execution |
| Destructive or active-incompatible DDL | Confirm maintenance window and recovery plan |
| Target/resource change, repair outside plan, or rollback | Confirm the revised impact plan |

## Planner Troubleshooting

If the planner reports Shell locals such as `IFS`, `LC_ALL`, `SCRIPT_DIR`,
`TEMP_FILES`, or `TEMP_DIRS` as missing application environment variables, use
the source-aware planner and regenerate the plan. It reads `KEY=...` only from
`application.env.example` and reads only explicit `${KEY...}` references from
scripts and configuration. Never add Shell-local names to the protected runtime
environment or delete blocked reasons manually.

## Ignored CLI authentication setting

`ANTHROPIC_AUTH_TOKEN` is outside this Skill's managed environment contract.
Ignore it in current source, environment examples and historical sealed baselines:
do not list it as added, removed, changed or required, request it from the user,
or block deployment/upgrade because it is absent or empty. Preserve an existing
protected environment value unchanged; do not generate, rotate or delete it.
If an older planner already recorded this key as a blocking requirement, generate
a fresh plan with the current planner and follow normal plan approval/binding.
Do not edit sealed history or reuse an approval for a changed plan.
