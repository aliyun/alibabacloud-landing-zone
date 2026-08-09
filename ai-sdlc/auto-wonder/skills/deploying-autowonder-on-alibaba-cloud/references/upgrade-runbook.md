# AutoWonder Upgrade Runbook

## Purpose

Upgrade an existing community deployment to an exact GitHub commit without
mutating the active release until source, configuration, database, build, and
rollback checks have passed. Reuse the deployment Skill's immutable releases,
private OSS transfer, sequential activation, probes, acceptance, and rollback.

Use this only for an existing accepted deployment. New installations follow the
normal phase state machine and import the complete schema exactly once.

## Prerequisite Context Gate

Resolve prerequisites at the start, using the current conversation context,
deployment manifest, inventory evidence, and protected environment file before
asking the user:

- ECS instance IDs are needed for active-release inventory, artifact staging,
  rolling activation, and rollback.
- Database connection information is needed only when the target change set
  contains DDL or DML. Confirm host, port, database, username, connectivity
  scope, and a protected credential source such as the mode-`0600` environment
  file. Never display or copy the database password into chat, commands, or the
  manifest.

If all applicable values are already available, present them for user
confirmation as a sanitized prerequisite summary and do not request them again.
If anything applicable is absent, request only the missing values once, present
the completed summary, and wait for user confirmation. Stop before the first
dependent action when prerequisites remain missing or unconfirmed. In
particular, do not run remote ECS operations without confirmed instance IDs, and
do not build, stage, or execute a release containing DDL or DML until its
database access context is confirmed.

## Upgrade Change Boundary

The approved upgrade plan is the **only mutation authority**. Its confirmation
must cover every planned resource operation, including release activation,
configuration changes, database migrations, restarts, rollback, and cleanup of
temporary upgrade artifacts. Do not clean up, delete, recreate, resize,
reconfigure, replace, restart, or otherwise mutate any user deployment resource
outside that plan. Never substitute teardown, new-deployment, or ad hoc repair
commands for an upgrade step.

If execution differs from the plan, a probe fails, state becomes uncertain, or a
new operation appears necessary, stop at once. Limit investigation to read-only
diagnostics and sanitized evidence collection. Do not retry or roll back
automatically and do not attempt to solve the anomaly. Show the current state,
impact, evidence, and decision options to the user. Resume only after a revised
plan, renewed risk review, and explicit human confirmation.

## Deterministic Command Route

Use protected local paths for the manifest and environment file. Never place
their contents or secret values in command arguments or evidence.

```bash
scripts/initialize-and-verify.sh upgrade-inventory --manifest "$MANIFEST"

scripts/plan-upgrade.sh \
  --manifest "$MANIFEST" \
  --source-dir "$SOURCE" \
  --target-ref "$TARGET_REF" \
  --env-file "$CANDIDATE_ENV"

TARGET_COMMIT=$(jq -r '.upgrade.toCommit' "$MANIFEST")
PROJECT_PREFIX=$(git -C "$SOURCE" rev-parse --show-prefix)
git -C "$SOURCE" worktree add --detach "$TARGET_WORKTREE" "$TARGET_COMMIT"
TARGET_SOURCE="$TARGET_WORKTREE/${PROJECT_PREFIX%/}"

scripts/build-release.sh \
  --manifest "$MANIFEST" \
  --source-dir "$TARGET_SOURCE" \
  --output-dir "$RELEASE_DIR"

scripts/initialize-and-verify.sh runtime-config \
  --manifest "$MANIFEST" \
  --env-file "$CANDIDATE_ENV"

scripts/deploy-via-cloud-assistant.sh \
  --manifest "$MANIFEST" \
  --env-file "$CANDIDATE_ENV" \
  --release-dir "$RELEASE_DIR" \
  --stage-only
```

When no migrations are pending, record the no-op directly:

```bash
scripts/initialize-and-verify.sh database-migrate --manifest "$MANIFEST"
```

When migrations are pending, first verify the named RDS backup and atomically
record its sanitized evidence reference in `.upgrade.databaseBackup` with status
`verified`. After the user confirms the displayed migration and compatibility
risks, run:

```bash
scripts/initialize-and-verify.sh database-migrate \
  --manifest "$MANIFEST" \
  --confirm-migrations \
  --confirm-rolling-compatible
```

Then activate and verify:

```bash
scripts/initialize-and-verify.sh rolling-upgrade --manifest "$MANIFEST"
scripts/initialize-and-verify.sh acceptance --manifest "$MANIFEST"
scripts/sanitize-evidence.sh --input "$MANIFEST" --output "$SANITIZED_REPORT"
```

`SOURCE` is the AutoWonder project directory. It may be a standalone repository
root or a monorepo subdirectory; the project-relative path is preserved in the
detached target worktree. Do not run the new-deployment `database` or
`business-init` subcommands during
an upgrade. Remove the temporary target worktree only after its build and hashes
are recorded; keep the sealed release until acceptance and rollback retention
requirements are satisfied.

## Upgrade Contract

- Treat the active deployed commit and the local source commit as separate
  facts. Record both before fetching the remote repository.
- Fetch the target ref without merging it into a dirty checkout. Resolve an
  exact target commit and build it from an isolated clean worktree.
- Require the old deployed commit to be an ancestor of the target commit unless
  the operator explicitly approves a non-linear release with a separate risk
  review.
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
has tracked changes, the target commit is unavailable, or the target architecture
is not Linux x86_64. Use `git fetch --prune`, not an implicit pull or merge.

## Phase 2: Change And Risk Plan

Compare the active commit with the exact target commit. The generated plan must
include:

1. commit subjects grouped into features, fixes, configuration, database,
   dependencies, and operations;
2. changed files and an ancestry result;
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

Upload the sealed release through private OSS staging and install it under
`/opt/autowonder/releases/<target-commit>/` without changing the active symlink.
Stage the validated environment file and systemd unit on every node. Preserve the
first pre-upgrade environment and unit snapshots across staging retries.

## Phase 6: Database And Rolling Activation

After the database confirmation gate, apply pending migrations and verify the
history records. Then activate one node at a time using the existing atomic
`/opt/autowonder/current` symlink and systemd workflow.

For each node require:

- `systemctl is-active autowonder` succeeds;
- port 7001 is listening;
- `/opt/autowonder/current` and the JAR checksum match the target commit;
- `/checkpreload.htm` succeeds;
- public branding succeeds;
- public ingress remains healthy before advancing.

Run the full RDS, Redis, OSS, enabled SLS, executor, and external signed-URL
checks during acceptance. A failed node or public-ingress check stops the rollout
and records that human resolution is required. Preserve the failed state and its
evidence; do not automatically restore, restart, or otherwise change the node.

## Phase 7: Acceptance And Rollback

Run the normal release acceptance matrix, including executor WebSocket
connectivity and externally downloadable OSS signed URLs. Keep the previous
release, environment snapshot, plan, hashes, migration evidence, and rollback
boundary in the sanitized handoff.

After a separate risk review and explicit human confirmation, an approved
application rollback may repoint the current symlink and restore the previous
environment snapshot one node at a time. Rollback is never an automatic failure
handler, and database migrations are never automatically reversed. If the old
application is incompatible with the migrated schema, stop and follow the
reviewed database restore or forward-fix plan.

## Confirmation Gates

| Gate | Confirmation required |
| --- | --- |
| Linear upgrade, no env or database change | Initial upgrade approval only |
| New or changed required environment values | Confirm candidate values without exposing secrets |
| New database migrations | Confirm risk summary, backup, compatibility, and execution |
| Destructive or active-incompatible DDL | Confirm maintenance window and recovery plan |
| Non-linear Git history | Confirm complete change and rollback review |
| Failed node probe or migration | Confirm rollback or forward-fix; never continue automatically |
