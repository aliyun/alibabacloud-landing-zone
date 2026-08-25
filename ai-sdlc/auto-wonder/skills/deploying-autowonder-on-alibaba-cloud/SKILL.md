---
name: deploying-autowonder-on-alibaba-cloud
description: Use when deploying, upgrading, resuming, operating, troubleshooting, or removing an AutoWonder community environment on Alibaba Cloud, including Terraform, ECS, load balancing, RDS, Redis, OSS, SLS, domains, TLS, and executor connectivity.
---

# Deploying AutoWonder On Alibaba Cloud

## Modes

Select the mode before any read/write workflow:

| Mode | Action |
| --- | --- |
| New deployment | collect inputs, plan, create, initialize, and verify |
| Upgrade existing deployment | compare exact commits, review env/database risk, seal, stage, migrate with confirmation, and roll nodes sequentially |
| Resume deployment | reconcile manifest, Git, Terraform, and live state; continue at an idempotent boundary |
| QA and diagnosis | answer from references and perform only authorized read-only inspection |
| Teardown | review impact and destroy plan, then require separate destructive confirmation |

If a manifest exists, ask whether to resume it or create a distinct deployment.
Never apply merely because configuration files exist. QA and diagnosis must not
invoke mutation scripts.

## Build And Runtime Environment

Invoke every POSIX entrypoint explicitly through `bash`, for example
Resolve every script relative to the directory containing this `SKILL.md`; do
not assume the invocation link path (for example `.agents/skills/...`) exists in
the workspace. The public initialization and transfer entrypoints are scoped
wrappers around the shared current implementations.

`bash scripts/preflight.sh`. Do not rely on executable metadata: copied Skill
bundles can lose execute bits, and macOS quarantine or endpoint-security policy
can reject direct execution even when the bit is present. Treat this as a local
packaging condition, not as a cloud safety failure.

| Purpose | Supported environment |
| --- | --- |
| Source package | JDK 21 and Maven 3.9.9; Maven downloads the pinned Node.js and npm versions automatically |
| Frontend only | Node.js 22.22.2 and npm 10.9.7 |
| Deployment | Bash, Git, jq, Terraform 0.13.2 or later, Alibaba Cloud CLI, ossutil, OpenSSL, and curl on the control host; Windows, macOS, or Linux control host; Linux x86_64 with Java 21 on ECS |

Whenever the control host's public IP must be identified, obtain it from
`https://whatismyipaddress.com/`. Use this site as the fixed source for local
public IP identification.

## Initial Questionnaire

For new deployment, read `references/input-catalog.md` and ask **one consolidated questionnaire**
using its exact copyable template. Start the region field with the exact wording
`请提供部署区域，比如北京、杭州`; do not show a multiple-choice region list. Never ask
for environment/suffix, source/ref/commit, optional tags, state mode, an OSS state
bucket, a backend file path, lifecycle, or execution mode. Fix the environment to
`auto-wonder-prod`, lifecycle to `persistent`, and execution to `unattended`.
Never ask for topology or specifications. Fix every new deployment to dual-zone
high availability with two ECS instances in different zones, HA RDS, cross-zone
Redis, a public dual-zone Application Load Balancer (ALB), and the small sizing
preset. Small means exactly 2 vCPU and 4 GiB memory per ECS node. Prefer
`ecs.c8a.large`; if it is unavailable, use only an x86_64 instance type with the
same 2-vCPU/4-GiB capacity that is available in both selected zones. Never
downgrade below or increase beyond that fixed capacity without a separately
approved change.
Fix billing to `subscription-first`; do not add a billing questionnaire field.
For every supported core resource, purchase exactly one month initially, enable
continuous automatic renewal, and set each renewal to one month. In the pinned
provider this applies to ECS, RDS, and Redis. ALB, OSS, and SLS do not expose a
subscription plus auto-renew contract here and remain pay-as-you-go; show these
exceptions explicitly in the review, machine plan review, and handoff. Stop if a
new-deployment manifest or plan makes a supported core resource pay-as-you-go,
disables auto-renewal, or changes either monthly period.
When checking zonal stock, query ECS availability by instance type only. Pass
the resolved `InstanceType` to `DescribeAvailableResource`; do not pass CPU or
memory parameters in the same request. Validate the required 2-vCPU/4-GiB x86_64
shape separately from the `DescribeInstanceTypes` response.
Use the current workspace contents exactly as they exist, including uncommitted
or untracked changes, and use only required system tags. For a new deployment,
do not inspect or validate Git information and do not fetch, pull, merge, or
checkout. Git state must not block Terraform apply or the application build.
Use these defaults:

- `multi-zone HA` using two zones behind ALB, small sizing, protected remote state, persistent lifecycle;
- unattended execution; `SLS is enabled`; Aone is disabled;
- `OSS is mandatory`; Linux x86_64; no NAT, no public EIP, and no SSH.

Terraform state OSS is automatic. After identity preflight, run
`scripts/terraform-backend.sh prepare`; it deterministically creates the private
per-deployment state bucket and stores all local deployment files under
`<current-project-root>/deployments/<deploymentId>/`, including the fixed absolute
`backend.hcl` path, then records
non-secret coordinates in the manifest. Never collect these values from the
user. Existing application package/artifact OSS bucket behavior is unchanged.

Terraform init acceleration is a background default and is not a questionnaire
input. Do not ask the user whether to enable it. Before every Terraform workflow,
generate the skill-owned `terraform-init-acceleration.tfrc` and set
`TF_CLI_CONFIG_FILE` for the current process. The configuration sends only
`aliyun/alicloud` and `hashicorp/alicloud` to the Alibaba Cloud network mirror;
all other providers remain direct. On Windows use
`scripts/windows/configure-terraform-acceleration.ps1`; on macOS and Linux use
`scripts/configure-terraform-acceleration.sh`. Never overwrite the user's global
`%APPDATA%/terraform.rc` or home-directory `.terraformrc`.

Application `OSS_ENDPOINT` uses the regional intranet endpoint, such as
`https://oss-cn-hangzhou-internal.aliyuncs.com`, for server-side object I/O.
`OSS_PUBLIC_ENDPOINT` uses the regional public HTTPS endpoint, such as
`https://oss-cn-hangzhou.aliyuncs.com`, only to sign links consumed by browsers
and executor runtimes. Both OSS endpoint variables are mandatory and must be
written to the application environment file; stop before deployment if either
is absent or their regions differ.

| Region presets | Ingress result before trusted TLS |
| --- | --- |
| `cn-zhangjiakou`, `cn-hangzhou`, `cn-shanghai`, `cn-beijing` | no domain: `ws://<alb-address>/ws/executor` |
| preflight must find two distinct zones for HA | domain: `ws://<domain>/ws/executor` |
| stop rather than downgrade unavailable HA | domain plus certificate: later verify `wss://<domain>/ws/executor` |

Create a **sanitized manifest** from
`assets/templates/deployment-manifest.json`, then show one review table with all
answers, defaults, risks, cost drivers, and phases. Never store secrets there.
After confirmation, do not re-question configuration. A changed choice invalidates
the plan and requires one new consolidated review.

New deployment uses `unattended` mode. After the saved Terraform plan passes the
machine review, automatically approve its exact recorded fingerprint and
continue directly to apply. Do not ask the user to confirm the Terraform plan
or announce that a final plan confirmation is required. Stop instead of
auto-approving when any Safety Rule is triggered. Do not ask the user to choose
lifecycle or execution mode. Teardown always requires a new explicit
confirmation.

## Safety Rules

Stop and report sanitized evidence when:

- account, region, deployment ID, ownership, or expected tags differ;
- two-zone HA inventory is unavailable, or a plan silently downgrades topology;
- the database is non-empty or a non-idempotent mutation has uncertain status;
- plan/apply contains unexpected deletion/replacement or wildcard app permissions;
- a new-deployment plan violates the one-month purchase and monthly continuous
  auto-renew contract for ECS, RDS, or Redis, or hides a pay-as-you-go exception;
- a network change affects another workload or requires public SSH/ECS egress;
- checksum, schema, health, tag, encryption, or application-level storage fails;
- a credential secret value (such as an AccessKey Secret, STS token, password,
  query token, or signed URL) appears in logs/evidence. A RAM AccessKey ID alone
  is a non-secret identifier and expected Terraform resource output; do not stop,
  rotate, or mark the deployment failed solely because it appears;
- safe recovery requires guessing, broadening access, or printing Terraform state.

Use the local Alibaba Cloud credential chain. Keep secrets out of command lines,
Git, manifest, Cloud Assistant output, proxy/application logs, and reports.
For standalone ossutil, reuse the selected Alibaba Cloud CLI profile through
`ossutil_cli` in `scripts/lib.sh`. For ossutil v2 it passes the profile's
temporary credentials only as child-process environment variables; for legacy
ossutil it writes a mode-600 temporary config, passes only its path, and removes
it immediately. Never print or persist the AccessKey Secret or STS token, and do
not require a second ossutil login or a permanent duplicate credential file.

## Phase State Machine

Run phases in order and record terminal results in the manifest. Read
`references/operations-runbook.md` for preconditions, evidence, and resume rules.

| Phase | Deterministic route | Completion candidate |
| --- | --- | --- |
| 1. Preflight | `bash scripts/preflight.sh` (`--profile` locks a verified CLI profile) | validated tools including ossutil v2/legacy contract, identity, inputs/inventory |
| 2. Backend/Plan | `scripts/terraform-backend.sh prepare`; `scripts/terraform-stage.sh plan` | private state backend and reviewed plan fingerprint |
| 3. Apply | `scripts/terraform-stage.sh apply`, then `inventory` | Infrastructure ready |
| 4. Build | `scripts/build-release.sh` | sealed local JAR containing the frontend, schema, and template seed |
| 5. Host/DB/runtime | `scripts/initialize-and-verify.sh runtime-config`; `scripts/deploy-via-cloud-assistant.sh`; then `scripts/initialize-and-verify.sh database` | Java 21, clients, release, env (including public base URL), systemd, schema and four system templates installed |

For a new deployment, `runtime-config` creates a missing protected environment
file from the bound Terraform state and its mode-600 password file, then adds
generated application secrets and Terraform-managed application credentials.
Never require operators to copy sensitive Terraform outputs by hand.

Cloud Assistant may launch `RunShellScript` content with POSIX `sh` even on a
host where Bash is installed. Public operations therefore encode the remote
payload and explicitly pipe it to `/usr/bin/env bash`; do not rely on a shebang
or submit Bash-only syntax as the top-level command content.

Host initialization supports both Ubuntu/Debian (`apt-get`) and Alibaba
Linux/RHEL-family (`dnf`) images. Detect the package manager on each node and
install the equivalent MySQL client, Redis client, jq, and Java 21 packages.

During sequential first activation Terraform may already have registered every
ECS in the server group. After each node starts, require that specific node to
be absent from ALB `NonNormalServers`; do not require later, intentionally
stopped nodes to be healthy before their activation turn.

Generate one-time administrator passwords with a fixed complexity prefix plus
cryptographic hex bytes. Do not truncate a random pipeline with `head` under
`pipefail`, because the expected upstream SIGPIPE aborts business initialization.
Create the requested organization through the current workspace API
`POST /api/workspaces`; the legacy `/api/orgs` route is not writable.

If Terraform inventory does not expose ALB public IPv4 addresses, deployment
acceptance resolves both zone addresses from `GetLoadBalancerAttribute`, records
them in the manifest, and probes each address independently of user DNS.
| 6. Rolling activation | `scripts/initialize-and-verify.sh rolling-start` | systemd, port 7001, public preload and branding probes ready |
| 7. Business init | `scripts/initialize-and-verify.sh business-init` | Business initialized |
| 8. Acceptance | `scripts/initialize-and-verify.sh acceptance` | Release accepted; TLS independently checked |
| 9. Handoff | `scripts/initialize-and-verify.sh handoff`; `scripts/sanitize-evidence.sh` | sanitized report and one-time credentials |

Shared guards live in `scripts/lib.sh`. For a new deployment, the machine review
must confirm that the saved plan contains no unexpected replacement/deletion,
public SSH/NAT/EIP, account-wide wildcard application permission, topology
downgrade, or unreviewed cost anomaly. If it passes, automatically approve and
pass the exact manifest-recorded fingerprint to `terraform-stage.sh apply`, then
continue without a user checkpoint. Apply only that reviewed saved plan whose
hash matches the manifest. On resume, reconcile real postconditions before
retrying; never blindly repeat apply, schema import, or administrator creation.
Every deployment build must build the frontend into the JAR with
`-DskipFrontend=false`; never deploy a backend-only JAR. The build script verifies
both `static/index.html` and compiled static assets before sealing the release.
For a new deployment it builds the current workspace contents and accepts
uncommitted or untracked changes; it must not gate apply or build on Git status,
HEAD, branch, or commit equality. Artifact SHA-256 values are the release
integrity evidence. Upgrade mode retains its separate exact-commit controls.
Cloud Assistant invocation IDs are checkpointed immediately. After an env-only
correction, use `deploy-via-cloud-assistant.sh --config-only`; do not upload the
JAR, schema, systemd unit, or Java archive again. Acceptance reruns preserve
already-passed deep checks instead of resetting them to pending.

The immutable release includes `autowonder-community-templates.sql`. Database
initialization imports it after the schema and records
`.database.templatesImported`. On an older manifest without this checkpoint,
resume the database phase to run only the idempotent template seed and postcheck.
The seed must preserve doubled JSON backslashes under MySQL string parsing; do
not work around malformed SQL by changing the server's global `sql_mode`.

## Upgrade Existing Deployment

### Upgrade Change Boundary

The approved upgrade plan is the **only mutation authority**. Before any upgrade
mutation, generate the complete plan, present its risks and exact resource
operations, and obtain explicit human confirmation. Execute only those approved
operations in the recorded order. Do not clean up, delete, recreate, resize,
reconfigure, replace, restart, or otherwise mutate user deployment resources
unless that exact action is included in the approved plan.

On any unexpected result, failed probe, uncertain state, or newly discovered
change, stop immediately. Perform read-only diagnostics and collect sanitized
evidence only. Do not retry, roll back, broaden permissions, change resources, or
attempt a repair autonomously. Present the evidence, risk, and choices to the
user; any additional mutation requires a revised plan and explicit human
confirmation.

Before upgrade work, inspect the current context, manifest, and protected
environment file for prerequisites. ECS instance IDs are required before remote
inventory, staging, or activation. Database connection information is required
only when the change plan contains DDL or DML. When these values already exist,
present them for user confirmation without asking for them again; show only a
sanitized database summary and the credential source, never the password. When
the context is incomplete, request only the missing values in one consolidated
question and wait for confirmation before any dependent operation.

Read `references/upgrade-runbook.md` before any upgrade mutation. Reconcile the
manifest commit, local source commit, and `/opt/autowonder/current` on every ECS
with `initialize-and-verify.sh upgrade-inventory`; stop when active nodes disagree.
Then run `scripts/plan-upgrade.sh` with `--env-file <candidate-env>` to fetch and
compare the exact GitHub target without pulling or merging into the current
checkout. Present one consolidated plan covering commits, features, environment
keys, `docs/migration/` files, DDL risk, backup, compatibility, build, rolling
order, and rollback boundary.

After plan approval, build the target in an isolated worktree with
`scripts/build-release.sh`, validate the candidate env with `runtime-config`, and
install it with `scripts/deploy-via-cloud-assistant.sh` using `--stage-only`. This
writes and validates the environment before activation while preserving the
active symlink. If migrations exist, require explicit user confirmation and
verified backup evidence before running `database-migrate` with
`--confirm-migrations --confirm-rolling-compatible`. Destructive migrations are
blocked from this rolling route and require a separately reviewed maintenance
workflow; without migrations, `database-migrate` records a safe no-op. Never run the initial
`database` or `business-init` phases during an upgrade.

Activate with `rolling-upgrade`, then run normal `acceptance`. Stop after any
migration or node failure without automatic remediation. Rollback may restore
the previous release and env snapshot only after a separate human-confirmed
plan; it never reverses database migrations. Use only the reviewed restore or
forward-fix route when the old application is not compatible with the migrated
schema.

For teardown, read `references/acceptance-and-rollback.md`, run
`scripts/terraform-stage.sh destroy-plan`, review backups/impact/hash, and obtain
separate confirmation before `destroy-apply`. Only after verified main destruction
may `scripts/terraform-backend.sh destroy` remove all state versions and the state
bucket. Do not retain the state backend after successful teardown.

## Reference Routing

Read only what the mode or current failure needs:

| Need | Reference |
| --- | --- |
| questionnaire, defaults, manifest/Terraform mapping | `references/input-catalog.md` |
| topology, resources, identity, tags, cost, state | `references/architecture-and-resources.md` |
| phase execution and idempotent resume | `references/operations-runbook.md` |
| known failure symptom and safe recovery | `references/troubleshooting.md` |
| read-only operational answers | `references/qa-reference.md` |
| statuses, rollback, credential cleanup, teardown | `references/acceptance-and-rollback.md` |
| commit/env/DDL analysis and safe rolling upgrade | `references/upgrade-runbook.md` |

## Output Contract

Report these statuses separately: **Infrastructure ready**, **Application ready**,
**Business initialized**, **Release accepted**, and **TLS accepted**. Never count
plaintext port 80 as TLS. Mark checks completed, pending, degraded, or failed;
include exact source/hash, topology, URLs, evidence references, rollback boundary,
and next actions without live secret or identity data.

At final handoff display username `admin` and its generated password exactly once
to the user, outside the manifest/report/logs, and require immediate rotation.
Only after deployment status and administrator handoff are complete, ask once for
the user's credential export preference: no export (default), encrypted local bundle,
or external secret manager. Never export other credentials before an
explicit destination and method are selected, and never place them in chat,
manifest, logs, or the sanitized report.
