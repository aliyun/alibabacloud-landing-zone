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

## Cloud Operations State

For resume, configuration changes, scale-out, and upgrade, authenticate the
`auto-wonder` profile, then run `python3 scripts/operations-store.py resolve
--project-root <workspace>` (`python` on Windows). Supply `--region` and
`--deployment-id` only to select among multiple deployments. A missing local
deployment folder is not a reason to create infrastructure or ask for passwords.
Use the returned manifest and restored Terraform/environment paths.

The authoritative recovery data lives in a **separate**, same-region private
`aw-ops-<deploymentId>-<identityHash>` bucket. Never reuse the tfstate, packages,
or artifacts bucket. `deploy/` and `upgrade/` share one verified `current.json`
commit point. Immutable snapshots retain history; bucket versioning must never
be enabled because OSS ignores conditional no-overwrite in versioned buckets.
Only operator credentials may access this bucket; do not grant the application
RAM user access. Operations storage requires ossutil v2; the helper also detects
the v2 command embedded in `aliyun ossutil`.

Only confirmed cloud absence triggers the existing local discovery/import
rules. Import preserves the latest working upgrade state, original secrets,
actual Terraform configuration, and sealed releases before continuing.
403, timeout, corrupt objects, and missing referenced files are **blocked**, not
permission to fall back to stale local data. See
`references/operations-state.md` for import, recovery, and unknown outcomes.

New deployment backend preparation initializes the operations bucket before
later infrastructure changes. Manifest checkpoints write through to OSS;
failure stops execution. `deployment-resume-required` means the cloud snapshot
is an unfinished installation: resume deployment, not upgrade. Local folders
are caches only after cloud initialization and successful restore validation.

If a manifest exists, ask whether to resume it or create a distinct deployment.
Never apply merely because configuration files exist. QA and diagnosis must not
invoke mutation scripts.

At the start of every workflow, including resume paths that do not repeat
preflight, run `bash scripts/bootstrap-control-host.sh --manifest <file>`. It
must validate the dedicated `auto-wonder` profile through STS and complete the
same-profile OAuth recovery before any later cloud or Terraform operation.
When no manifest is available yet, bootstrap with `--region <region>` (or its
default), then resolve cloud operations state. On Windows use
`scripts/windows/bootstrap-control-host.ps1` and native Python; Git Bash may
launch the forwarding bootstrap, but must not execute POSIX deployment logic.

## Automatic Script And Host Compatibility Repair

For Skill script defects or host compatibility errors, diagnose, apply the
smallest local repair, verify it, and continue without confirmation when the
operation and authorization remain unchanged, including resumed workflows.

- Capture sanitized failure evidence and determine whether the command failed
  locally before submission or may already have changed remote state. For an
  unknown outcome, reconcile invocation IDs, OSS checkpoints and live state
  before replaying anything; never blindly repeat apply, migration or restart.
- Fix the actual cause in the executing Skill copy or task-local tool setup.
  Prefer existing native adapters and shared helpers. On Windows check
  PowerShell edition/version, native process exit codes, argument quoting,
  paths with spaces/non-ASCII characters, separators, UTF-8/BOM/CRLF, file ACLs,
  temporary files and executable discovery. Use native PowerShell/Python for
  local control; Linux shell payloads run on ECS, not through local Git Bash.
- Use the private toolchain and process-scoped configuration for missing tools,
  compatible runtime selection, proxy or mirror issues. Preserve version and
  checksum verification; do not globally change execution policy or weaken
  TLS checks, ACLs, identity checks or deployment safety gates.
- A missing/broken native adapter may be repaired using the existing phase
  contract and shared policy. Verify parsing, command construction, exit/error
  handling and postconditions locally before any cloud mutation. Never replace
  it with an unverified ad hoc cloud command or shell translation.
- Keep the target release, resource set, secrets, database scope and approved
  operation unchanged. Preserve backups, progress and authoritative OSS state.
  Revalidate plan bindings and relevant focused checks, then resume at the
  failed idempotent boundary; do not restart the whole workflow or erase state.
- Each further attempt must follow new evidence or a verified change. Do not
  loop on the same failure. Escalate only when required external login/access,
  missing original data, unresolved remote state, a changed operation scope or
  inability to verify the repair prevents safe progress. Explain the concrete
  blocker and request only the missing input/authorization.

Record the cause, repaired files, verification, retries and remaining limits in
the final report. Keep fixes reviewable; do not silently commit/push Skill
changes or claim mocked Windows checks are native Windows validation.

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
| Source package | JDK 21 and Maven 3.9.9+ in the 3.x series; Maven downloads the pinned Node.js and npm versions automatically |
| Frontend only | Node.js 22.22.2 and npm 10.9.7 |
| Deployment | Shared private Python; Terraform 1.5+ in the 1.x series, Alibaba Cloud CLI 3.x, ossutil 2.x and jq 1.8.2+; platform prerequisites and download coverage in `references/cross-platform-runtime.md`; Linux x86_64 with Java 21 on ECS |

When a new deployment needs the control host's public IPv4, run
`bash scripts/detect-public-ip.sh` (Windows: `scripts/windows/detect-public-ip.ps1`).
The shared Python probe queries IPIP, ipify and MyIP over HTTPS concurrently,
with a bounded overall deadline, while respecting the existing proxy route.
When the user selects automatic discovery, `detected` (exit 0) means at least
one valid public IPv4 was found. Copy the complete `publicSourceCidrs` array to
the manifest without further confirmation: a single-source result is usable,
and different addresses from multiple sources are all included as separate,
deduplicated `/32` CIDRs. Do not select only the majority address. `confidence`
and source errors are diagnostic information, not approval gates.
`unavailable` (5) means no valid address was found; offer one retry or manual
public IPv4/CIDR input. Validate manual input with `--ip` or `--cidr`; never
substitute `0.0.0.0/0`. In unattended mode, no valid address blocks the affected
step.
Reuse an explicitly provided valid access CIDR without probing. An upgrade
must preserve existing ingress rules unless their change is explicitly requested.
Do not promise that any third-party IP service is always reachable in China.

This Skill is self-contained: do not read scripts, templates or references from
an installed upgrade Skill. Only `skills/.autowonder-tools` may be shared;
recovery compatibility is carried by OSS records and sealed artifacts.

Runtime initialization and verification are defined in
`references/cross-platform-runtime.md`. Read it before selecting dependencies.
Reuse the bootstrap's returned `runtimeEnvironment` for all subsequent child
processes; dependencies live in the shared project cache, never in a global
package-manager installation. CMD and Git Bash startup wrappers dispatch to
native Windows PowerShell; WSL uses Linux dependencies.

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
preset. Small means exactly 2 vCPU and 4 GiB memory per ECS node. Use an
enterprise-class x86_64 type available in both zones. `ecs.c8a.large` is a soft
preference after hard constraints and comparable core subscription prices. Never
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
Availability zones are resolved automatically and never asked of the user.
Before preflight, run `bash scripts/resolve-zones.sh --manifest <file>`.
The resolver discovers a complete ECS/image/disk/RDS/Redis/ALB combination using
current CLI facts and `assets/deployment-policy.json`; no SKU is a silent
Terraform default. A preference never overrides a complete compliant combination.
For `needs-agent`, follow the candidate protocol in
`references/operations-runbook.md`: collect read-only facts with `discover`,
reason over them, and submit `resolve --candidate FILE`. Do not directly write
`verified` or bypass unknown checks. Missing evidence is not proof of no stock.
Do not ask the user to find zones in the console or re-confirm a compliant plan.
Plan/apply must use `terraform-stage.sh`, which binds the exact plan to verified
selection and configuration, refreshes purchase facts before apply, and enforces
the update policy in `references/operations-runbook.md`: new installs/retries allow updates;
existing operations/upgrades require detailed changes and user confirmation of the exact plan.
OSS recovery retains selection metadata in the existing manifest checkpoint. Restoring
a completed environment does not trigger reselection. Read the runbook's
adaptive-selection section for supported standby changes and API limits.
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
| `cn-zhangjiakou`, `cn-hangzhou`, `cn-shanghai`, `cn-beijing` | no domain: `ws://<alb-public-ipv4>/ws/executor` |
| preflight must find two distinct zones for HA | domain: `ws://<domain>/ws/executor` |
| stop rather than downgrade unavailable HA | domain plus certificate: later verify `wss://<domain>/ws/executor` |

Create a **sanitized manifest** from
`assets/templates/deployment-manifest.json`, then show one review table with all
answers, defaults, risks, cost drivers, and phases. Never store secrets there.
For a new-deployment request, complete and validate the inputs, show this table
as an informational progress update, and continue directly into unattended
execution. The deployment request authorizes creation within these inputs and
fixed defaults; do not ask for another confirmation before backend preparation,
resource creation, or Terraform apply, and never require a reply such as “确认”.
Ask only for missing or ambiguous required inputs; stop on Safety Rules or when
the user explicitly requests a review-only plan or an approval checkpoint.
Do not re-question resolved configuration. A changed choice invalidates the plan:
regenerate and re-review it against the updated inputs, then continue automatically
within the authorized scope.

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
Git, manifest, Cloud Assistant output, proxy/application logs, and saved reports.
The first-deployment admin handoff in the final chat response is the sole
password-display exception, as specified in Output Contract.
Use only the dedicated Alibaba Cloud CLI profile `auto-wonder`; never use the
CLI current profile or `default`. At the start of every deployment workflow,
probe STS with `auto-wonder`. If the profile is missing or its identity has
expired, run OAuth login for that same profile, overwrite its identity data,
and repeat the STS probe before continuing. Clear ambient `ALICLOUD_*` and
`ALIBABA_CLOUD_*` credentials before loading the refreshed `auto-wonder`
temporary credentials.
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
| 1. Preflight | `bash scripts/preflight.sh` (`--profile`, when supplied, accepts only `auto-wonder`) | validated tools including ossutil v2/legacy contract, dedicated profile identity, inputs/inventory |
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
Create only the initial `admin`; do not request an organization name or create a workspace.
The user creates their first workspace after signing in. Persist deployment-bound credentials
in root-only `/etc/autowonder/admin-bootstrap.json` before registration; on a registration
conflict verify the saved credentials without deleting or resetting the existing account.
This recovery file is sensitive and remains protected on the first ECS; handoff transport stays encrypted and the local credential file remains mode 0600.

Terraform inventory resolves both ALB public IPv4 addresses (the ALB EIPs) from
`GetLoadBalancerAttribute` and records them in the manifest. Without a domain,
set `applicationBaseUrl` to `http://<ip>` using the numerically first address;
`runtime-config` uses it as the default `AUTOWONDER_PUBLIC_BASE_URL`. Do not use
the ALB DNS name or allocate an ECS EIP. Missing or invalid addresses stop
inventory. Acceptance probes both addresses independently of user DNS, resolving
them for older manifests if necessary.
| 6. Rolling activation | `scripts/initialize-and-verify.sh rolling-start` | systemd, port 7001, public preload and branding probes ready |
| 7. Business init | `scripts/initialize-and-verify.sh business-init` | Initial admin created; protected credentials ready for handoff |
| 8. Acceptance | `scripts/initialize-and-verify.sh acceptance` | Script acceptance: both ALB public IPv4 preload probes pass; extended checks and TLS reported separately |
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
The builder also seals `source.baseline`, binding the release identity to the
JAR and migration archive hashes, source environment contract, and published
migration checksums. Preserve this baseline and its sealed artifact directory
in the deployment handoff so a later upgrade can compare a workspace release
without pretending its content hash is a Git commit.
Cloud Assistant invocation IDs are checkpointed immediately. After an env-only
correction, use `deploy-via-cloud-assistant.sh --config-only`; do not upload the
JAR, schema, systemd unit, or Java archive again. Acceptance reruns preserve
already-recorded deep checks instead of resetting them to pending. The current
deployment-scoped acceptance returns `accepted` after both ALB public IPv4
`/checkpreload.htm` responses equal `success`; it does not execute the optional
extended ten-check acceptance or TLS verification. `--acceptance-evidence` and
`AUTOWONDER_RUNTIME_PROBE` are not reached by this scoped entrypoint and have no
effect. Preserved deep-check fields are historical evidence, not rerun results.
Report script acceptance, extended acceptance, and TLS separately; see
`references/acceptance-and-rollback.md`.

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

For script defects and host compatibility failures within the approved operation,
follow Automatic Script And Host Compatibility Repair: use read-only diagnostics, repair, verify
and continue. If the remote outcome is uncertain, pause mutation and reconcile
it first. A changed resource/database boundary, additional cloud operation or
rollback requires a revised plan and explicit confirmation; local compatibility
repairs alone do not require another confirmation.

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

For teardown, read `references/acceptance-and-rollback.md` and verify ownership,
backups, impact, and authorization for the exact deployment. Run
`bash scripts/prepare-teardown.sh --manifest FILE --confirmation-file FILE` with
`DESTROY <deploymentId>` in the confirmation file. It applies only reviewed
protection/retention changes without changing production defaults; it does not
perform subscription refunds. Complete exact-resource BSS unsubscription for
prepaid resources before the main destroy. Then run
`scripts/terraform-stage.sh destroy-plan`, review its hash, and apply that exact
plan with `destroy-apply` under the authorized teardown scope. After verified
main destruction, backend cleanup automatically removes all tfstate versions,
the state bucket, and the dedicated operations bucket. Do not remove recovery
state while destruction is failed or uncertain.

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
and next actions. Saved/sanitized reports must exclude live secret and identity
data; the final chat response has the narrow administrator handoff exception below.

After a successful first deployment, the final user-visible chat report MUST
include an Administrator credentials section containing the username `admin`
and its actual generated initial password together, plus the login URL and a
reminder to change the password after signing in. Read the credentials from
this deployment's protected handoff file/output. Do not substitute masked text,
a file path, or "already displayed above" for the password. Tool/command output
and `.business.handoffDisplayed` do not prove delivery in the final chat response.
If handoff already ran, use its result or read the still-protected handoff file;
do not rerun the one-time command, reset the account, or generate another password.
Only this initial admin password may appear in the final chat report; never copy
it into the manifest, saved deployment-report.json/Markdown, logs, Git, or other
evidence. Keep the handoff file until the user confirms receipt, then use
`handoff --confirm-received` to remove it. Do not redisplay credentials during
later maintenance/upgrades or show an old password after rotation. If the actual
initial credential is unavailable, report incomplete delivery; never invent it.
Only after deployment status and administrator handoff are complete, ask once for
the user's credential export preference: no export (default), encrypted local bundle,
or external secret manager. Never export other credentials before an
explicit destination and method are selected, and never place them in chat,
manifest, logs, or the sanitized report.

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
