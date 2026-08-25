---
name: upgrading-autowonder-on-alibaba-cloud
description: Use when upgrading the software of an existing AutoWonder community deployment on Alibaba Cloud, especially when the user says “开始升级” or needs upgrade inventory, environment comparison, database migration planning, staged release distribution, rolling activation, acceptance, or rollback analysis. Do not use for creating infrastructure, first-time deployment, scale-out, or teardown.
---

# Upgrading AutoWonder On Alibaba Cloud

Upgrade an existing deployment created by `$deploying-autowonder-on-alibaba-cloud`.
Consume its sanitized manifest and live resource inventory; never recreate cloud
resources, ask the user to retype ECS IDs, or infer targets from names alone.

## Trigger And Boundary

When the operator explicitly prohibits all Git inspection and requests a
same-version validation redeployment, use `plan-upgrade.sh
--workspace-current-content --force-redeploy`. This mode derives a 40-character
immutable release identity from the sorted current-workspace file set, excludes
generated/build/VCS directories without reading VCS metadata, and retains the
normal verification, approval, backup, staging, rollout, and acceptance gates.
Never invoke a Git command in this mode.
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

Before discovery, run the sibling deployment skill's platform bootstrap. It
detects the control host rather than inferring it from path spelling and checks
the complete supported third-party dependency set. On macOS this is Bash, Git,
jq, Terraform, Alibaba Cloud CLI, ossutil, OpenSSL, curl, Python 3, JDK 21, and
Maven 3.9.9+; on Windows this is native PowerShell 5.1+, Git, jq, Terraform,
Alibaba Cloud CLI, ossutil, `curl.exe`, Python, tar, JDK 21, and Maven 3.9.9+.
Install missing supported third-party dependencies without conversational
confirmation through the platform package adapter, including Alibaba Cloud CLI.
An operating-system elevation or package-manager dialog may still require the
user's direct operating-system consent; do not add a separate chat confirmation.

## Automatic Start Flow

Run these steps in order when upgrade starts:

1. Resolve the deployment manifest. Prefer an explicit path already present in
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
   validate STS and load credentials from the manifest-recorded CLI profile
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
4. Run the deployment skill's platform bootstrap with the recorded profile.
   It probes `sts GetCallerIdentity`. If the CLI profile is missing, logged out,
   or expired, automatically run `aliyun configure --profile <PROFILE> --mode
   OAuth` and then repeat STS validation. This also returns the session
   environment file; load it before later local phases.
5. Verify the selected CLI profile with STS and compare the Terraform ECS set
   with the complete tagged cloud ECS set before any Cloud Assistant operation.
   Require exact set equality; do not verify only IDs already present in a cached
   manifest. Verify every ECS against the
   manifest before any Cloud Assistant operation. On macOS/Linux run
   `scripts/verify-deployment-targets.sh`; on Windows run the paired PowerShell
   script. Require the same region, instance IDs, VPC when recorded, and exact
   `Project`, `DeploymentId`, `Environment`, `ManagedBy`, and `Topology` tags.
   This is the deployment-presence probe: a valid STS identity with no
   manifest-owned AutoWonder deployment visible in the recorded account and
   region is an account authorization/login failure, not permission to create a
   deployment or infer another target. Tell the user: “账号授权登录失败：当前账号未检测到该
   AutoWonder 部署。请先在浏览器登录部署 AutoWonder 的阿里云账号，完成后告诉我
   已登录。” Stop and wait. Accept “已登录”, “登录好了”, “已重新登录”, or an
   equivalent natural-language confirmation that the browser login is complete;
   do not require an exact confirmation phrase. Then automatically rerun OAuth
   for the same recorded profile so it will overwrite the previous CLI login,
   rerun `sts GetCallerIdentity`, and repeat target verification. Never continue
   with the previously verified wrong account.
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
   node's active release. Stop when nodes disagree or the manifest commit differs.
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
`scripts/build-upgrade-release.sh`; this guarded wrapper reuses the sibling
deployment skill's release builder. Before changing the environment, systemd
unit, database, or active release, create and verify the single backup slot on
every ECS:

```bash
scripts/upgrade-operations.sh upgrade-backup --manifest "$MANIFEST"
```

Each run keeps exactly one backup archive per ECS at
`/opt/autowonder/upgrade-rollback-backup.tar.gz` and atomically overwrites the
previous backup only after the replacement archive and checksums validate.
Upgrade staging is blocked until this backup matches the approved target plan.
Validate the candidate protected
environment file with:

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
database migrations; those require a separately reviewed recovery plan and are
never reversed automatically.

## Resource Handoff Contract

The deployment manifest is the source of truth. New deployments and every
Terraform inventory refresh must preserve at least:

- deployment ID, environment, region, selected CLI profile, repository URL, and
  exact active commit;
- ECS IDs plus VPC, VSwitch, private IP, expected tag, ALB, RDS, Redis, OSS, and
  SLS inventory where available;
- protected environment-file reference or evidence and its hash, without secret
  values;
- Terraform state reference and inventory timestamp;
- release hashes, Cloud Assistant invocation IDs, acceptance, and upgrade state.

Planned Terraform scale-out remains a deployment operation. After its inventory
refresh and node initialization complete, the manifest contains the expanded ECS
set; the next upgrade automatically verifies and targets the complete set.

## Safety Rules

- Use only the CLI profile and region recorded by deployment; refresh OAuth/STS
  before cloud API calls. Never place AK/SK/STS values in the manifest or output.
- A manifest is not sufficient by itself: verify live ECS identity and tags
  before planning and automatically refresh that read-only verification before
  every mutation. An unchanged target fingerprint preserves approval; any target
  change invalidates it.
- Every mutating upgrade entrypoint requires an approved plan fingerprint that
  still matches the current plan and target-verification checkpoint.
- Use the protected application environment produced by deployment. Never ask
  the user to paste database or application secrets into chat.
- Build and stage only an exact 40-character commit. Keep the previous immutable
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
- The sibling deployment skill owns the release builder, credential
  helpers, systemd unit, acceptance primitives, and deployment manifest producer.
  Reuse them through the bounded entrypoints; do not duplicate their policies.

## Output Contract

Report only after success unless user action is required. Include deployment ID,
environment, region, source and target commits, verified
node count, plan status, migration status, per-node ECS-local rollout status,
application acceptance, rollback boundary, and sanitized evidence paths.
Never print credentials, protected environment values, presigned URLs, or raw
Cloud Assistant output containing secrets.
