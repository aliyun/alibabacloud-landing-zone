# AutoWonder Alibaba Cloud Upgrade Runbook

## Purpose

Upgrade an existing community deployment to an exact GitHub commit without
mutating the active release until source, configuration, database, build, and
rollback checks have passed. Reuse the deployment Skill's immutable releases,
private OSS transfer, sequential activation, probes, acceptance, and rollback.

Use this only for an existing deployment with the files and identity fields
required by the upgrade workflow. Discovery does not gate on manifest status or
acceptance fields. New installations follow the normal phase state machine and
import the complete schema exactly once.

## Prerequisite Context Gate

Detect the control host first and run the deployment Skill's matching bootstrap
adapter. The adapter checks all supported third-party dependencies and installs
missing supported third-party dependencies without conversational confirmation,
including Alibaba Cloud CLI. It must validate the recorded profile with `sts
GetCallerIdentity`; a missing or expired identity triggers `aliyun configure
--profile <PROFILE> --mode OAuth` automatically, followed by another STS probe.

After OAuth, target verification is also the account deployment-presence probe.
If STS succeeds but there is no manifest-owned AutoWonder deployment visible in
the recorded region, report an account authorization/login failure and ask the
user to log into the Alibaba Cloud account that deployed AutoWonder in the
browser. Stop until the user gives an equivalent natural-language confirmation
that the browser login is complete, such as “已登录”, “登录好了”, or “已重新登录”.
Do not require an exact confirmation phrase. That confirmation authorizes an
automatic rerun of OAuth on the recorded profile to overwrite the previous CLI
login; repeat STS and target verification before continuing.

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
STS and loads temporary credentials from the manifest-recorded CLI profile; it
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

Use protected local paths for the manifest and environment file. Never place
their contents or secret values in command arguments or evidence.

On Windows invoke the paired PowerShell scripts with native parameter names,
including `-Manifest`, `-SourceDirectory`, `-EnvFile`, `-ReleaseDirectory`,
`-Fingerprint`, `-Automatic`, and `-ForceRedeploy`. Use
`upgrade-operations.ps1 <operation>` for phase operations. The phase order and
confirmation gates below are identical on both platforms.

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
../deploying-autowonder-on-alibaba-cloud/scripts/sanitize-evidence.sh \
  --input "$MANIFEST" --output "$SANITIZED_REPORT"
```

`SOURCE` may be a standalone repository root, a monorepo root, or the AutoWonder
project subdirectory. The planner and build wrapper resolve the unique project
directory from its versioned systemd marker, `VERSION`, and `pom.xml`. Preserve
the project-relative path in the detached target worktree. Do not run the new-deployment `database` or
`business-init` subcommands during
an upgrade. Remove the temporary target worktree only after its build and hashes
are recorded; keep the sealed release until acceptance and rollback retention
requirements are satisfied.

## Upgrade Contract

- Treat the active deployed commit and the local source commit as separate
  facts. Record both before synchronizing the repository.
- Never ask the user for a target Git ref. Fetch `origin/master`, verify that
  `origin` matches the manifest repository, and create an isolated clean detached
  worktree at the exact fetched commit. Preserve a dirty, ahead, or divergent
  local branch unchanged; never merge, rebase, reset, or build it. The planner
  accepts that detached remote-master worktree and still rejects tracked changes.
- Compare the reconciled active commit with the pulled `origin/master` commit
  before producing an upgrade plan. Commit equality is the only
  version-availability check. If they match, report that the deployment is
  already the latest version and skip planning, approval, build, staging,
  migration, and activation. Only when the operator explicitly requests a
  same-version redeployment, run the planner with `--force-redeploy`; the plan
  records that intent and retains all normal mutation gates. If they differ,
  continue planning. Do not block
  because of Git ancestry between the active deployment and `origin/master`.
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

Abort when active nodes report different release commits, the local repository
has tracked changes, the checked-out branch is not `master`, `git pull --ff-only
origin master` cannot fast-forward, the target commit is unavailable, or the
target architecture is not Linux x86_64.

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
   environment variables;
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
for a new deployment. The candidate must contain every newly required variable.
Record its SHA-256 after final validation and require the staged file to match it.

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
Record version, filename, SHA-256, target commit, start/end time, and result in a
database table named `autowonder_schema_history`. The migration runner creates
this ledger when absent and serializes execution with the MySQL named lock
`autowonder-community-migration`. A successful recorded version with the same
checksum is skipped; a different checksum or a previous failed record is fatal
and requires reviewed repair rather than automatic retry. Stop on the first
failure and do not activate the target release.

## Phase 5: Build And Stage

Build the exact target worktree with `-DskipFrontend=false`. Run backend tests,
frontend tests/lint/build, deployment Skill tests, internal-reference scans, and
the existing release sealing checks. Record the target commit and artifact
SHA-256 values in the deployment manifest.

Before any ECS environment, systemd unit, database, or active-release mutation,
run `upgrade-backup`. It creates exactly one backup archive per ECS at
`/opt/autowonder/upgrade-rollback-backup.tar.gz`, containing the complete active
release, protected environment, systemd unit, release identity, and checksums.
The archive is built and validated in a temporary path; a successful rename
atomically overwrites the previous backup. A failed replacement leaves the old
archive intact. Do not retain per-target upgrade snapshots elsewhere.

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
