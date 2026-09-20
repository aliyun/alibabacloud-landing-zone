# Deployment Operations Runbook

## Purpose

Use this reference to execute or resume the manifest-backed nine-phase workflow.
Scripts are deterministic boundaries; do not replace their safety checks with
ad hoc cloud mutations.

## Common Rules

1. Keep the manifest outside Git and set restrictive permissions.
2. Use only the dedicated Alibaba Cloud CLI profile `auto-wonder`. Preflight
   probes STS through that profile and, when it is missing or expired, runs
   `aliyun configure --profile auto-wonder --mode OAuth`, overwrites the profile
   identity, and repeats STS before continuing. Clear ambient `ALICLOUD_*` and
   `ALIBABA_CLOUD_*` credentials before loading this profile. Never select the CLI current or
   `default` profile. Export the selected region and pass the CLI global region
   when an API has no request-level `RegionId`.
   Start every workflow, including a resume that skips phase 1, with
   `scripts/bootstrap-control-host.sh --manifest <file>` so STS/OAuth validation
   cannot be bypassed.
3. Run host commands through Cloud Assistant. Poll every invocation to terminal
   status and require exit code zero; retain only sanitized output.
4. Never put raw secret values in command content or output. V1 permits one
   recorded exception: a 15-minute private-intranet presigned URL for secret-file
   transport. Never print it; delete the unique OSS object
   immediately after installation and treat the URL as exposed until expiry.
5. For a new deployment, resume after reconciling the manifest, Terraform state,
   artifact hashes, and live state. Upgrade mode additionally reconciles Git.

## Phase 1: Preflight

**Inputs:** completed questionnaire, current workspace contents, two candidate
zones, and local credential chain. New deployments use fixed
environment `auto-wonder-prod` and mandatory remote state.

Run `scripts/resolve-zones.sh --manifest <file>` to discover the image,
ECS/RDS/Redis specifications, zones and prices as a complete combination before
preflight. On `needs-agent`, use the adaptive candidate protocol below; submit
`resolve --candidate FILE` rather than manually marking a manifest verified.
Never ask the user to look up zones in the console.
Run `scripts/preflight.sh --manifest <file>
--source-dir <repo>`. It validates tools, account identity, region, resolved
x86_64 inputs, two distinct zones, CIDRs, tags, and topology. Do not inspect or
validate Git information for a new deployment.

For each selected zone, query ECS availability by instance type only using
`DescribeAvailableResource` with `InstanceType`. Do not pass CPU or memory
parameters in that request. Check CPU count, memory size, and x86_64 architecture
separately from the `DescribeInstanceTypes` response before accepting the type.
Stop on identity mismatch, unsupported HA inventory, public egress/SSH
requirement, invalid CIDR, or a secret-bearing manifest.

**Output:** reconciled preflight evidence and resolved non-secret inputs.

After preflight checks pass, automatically run `scripts/terraform-backend.sh prepare --manifest
<file>`. It computes the fixed bucket, key, and absolute backend path, creates or
exactly reconciles the private bucket, and records a ready checkpoint. Never ask
the user for backend coordinates or adopt a mismatched bucket.

## Phase 2: Terraform Plan

Terraform init acceleration runs automatically as a background default; do not
ask the user to enable or configure it. `terraform-stage.sh` generates an
isolated `*.tfrc` and exports `TF_CLI_CONFIG_FILE` before Terraform runs. On
Windows, run `scripts/windows/configure-terraform-acceleration.ps1` in the same
PowerShell process as Terraform; on macOS and Linux the stage script calls
`scripts/configure-terraform-acceleration.sh`. Both routes use the Alibaba Cloud
mirror only for `aliyun/alicloud` and `hashicorp/alicloud`, leave other providers
on the direct route, and preserve any existing global CLI configuration.

Run `scripts/terraform-stage.sh plan --manifest <file> --work-dir <dir>`. The
stage formats, initializes, validates, and saves an immutable plan plus its hash.
Reject wildcard application permissions, public SSH, absent tags, same-zone HA,
secret defaults, or unexpected destroy/replace actions. Complete the machine
review, including cost drivers and the live pricing check. If no safety stop is
triggered, automatically approve the exact saved-plan fingerprint and continue
directly to apply. Do not ask the user to confirm the Terraform plan. Do not emit
an intermediate message claiming that a final mandatory review is coming.

**Output:** plan file and `terraform.planFingerprint` in the manifest.

Terraform accepts only the helper-recorded
`<deployment-root>/terraform/backend.hcl`; an arbitrary path or local-state
fallback is a safety failure.

If provider distribution is unreachable, use a deployment-local filesystem
mirror only after verifying the official archive checksum. Point Terraform at
that mirror through a deployment-local CLI config; do not modify system DNS or
commit the provider binary. Reuse the verified mirror on retries.

## Phase 3: Terraform Apply

Run `scripts/terraform-stage.sh apply --manifest <file> --work-dir <dir>
--approved-plan-sha256 <hash>`. Apply only the reviewed saved plan. Then run the
inventory command, reconcile IDs without publishing them, verify zones, tags,
protection, listener sources, private endpoints, and application RAM scope.
Uncommitted or untracked workspace changes must not block Terraform apply.

If a Terraform operation is pending, infrastructure/destroy status is unknown,
or the working directory contains `errored.tfstate`, stop all Terraform stages.
Preserve the reviewed plans, manifest, emergency state, and a private remote-state
snapshot. Refresh credentials, then independently reconcile state lineage,
serials, resource ownership, and the observed cloud result under explicit review.
Do not force-push state, unlock, replay apply, or delete the emergency state merely
to bypass the guard. Clear unresolved markers only after reconciliation is verified;
then create and review a fresh plan before any further mutation.

**Output:** infrastructure inventory and **Infrastructure ready** candidate.

## Phase 4: Immutable Build And Transfer

Run `scripts/build-release.sh` against the current workspace contents. For a new
deployment it does not inspect or validate Git information and accepts
uncommitted or untracked changes. It runs the complete build verification with
`-DskipFrontend=false` and records JAR/schema/template-seed hashes. It
must find `static/index.html` and compiled assets inside the JAR before sealing;
never deploy a backend-only JAR or reuse stale frontend output. The seed artifact is
`autowonder-community-templates.sql`. Phase 4 ends with the sealed local release;
host transfer starts only after runtime configuration exists in Phase 5.

The template SQL stores JSON inside MySQL string literals. Every JSON backslash
must be doubled in the file so default MySQL parsing leaves one backslash for the
JSON parser. The seed temporarily removes `NO_BACKSLASH_ESCAPES` from its own
session and restores the prior mode at the end; never mutate the global SQL mode.

Do not use Cloud Assistant `SendFile` for a JAR, runtime, or secret file. Do not
build from source on ECS. If Java 21 is absent, transfer a pinned, verified
Temurin Linux amd64 runtime. Delete staging objects after verified delivery.

## Phase 5: Host, Database, And Runtime Initialization

Generate the SecretCrypto master key, JWT secret, database/cache credentials,
and application AK/SK only in a protected session. Encode the env file with
`jq -Rrs @sh`, source a sanitized validation copy, and install it as
`/etc/autowonder/autowonder.env` with restrictive ownership. Preserve the master
key: it is required to read persisted `enc:v1:` values. `runtime-config` must
write `AUTOWONDER_PUBLIC_BASE_URL` into this file. It derives a missing value
from manifest `applicationBaseUrl`, while preserving an explicit domain/TLS URL.
For no-domain deployments, Terraform inventory sets this default to `http://`
plus the numerically first of the two ALB public IPv4 addresses returned by
`GetLoadBalancerAttribute`, and records both in
`resources.alb_public_ipv4_addresses`. It stops if addresses cannot be resolved
and validated; it must not fall back to the ALB DNS name.
It also derives `autowonder.runtime.recommended-version` from the exact pending
source `src/main/resources/application.yml`, records the resolved value as
manifest `recommendedRuntimeVersion`, and replaces any stale
`AUTOWONDER_RUNTIME_RECOMMENDED_VERSION`. The source configuration is the source
of truth; the generated manifest value is an audit and resume checkpoint.
The sealed repository `VERSION` is also recorded in the manifest and written as
`AUTOWONDER_VERSION`, which is displayed on the About page.
Set application `OSS_ENDPOINT` to the regional intranet endpoint, for example
`https://oss-cn-hangzhou-internal.aliyuncs.com`, and set
`OSS_PUBLIC_ENDPOINT` to the matching public HTTPS endpoint, for example
`https://oss-cn-hangzhou.aliyuncs.com`. `runtime-config` rejects missing,
reversed, cross-region, or non-HTTPS public endpoint configuration.

Run `initialize-and-verify.sh runtime-config` first. Then use
`deploy-via-cloud-assistant.sh` to create the non-root `autowonder` user, install
the MySQL/Redis clients, transfer the release/env through private OSS objects,
verify hashes, and install the versioned layout, Java runtime, data/log
directories, and systemd unit. The control host uploads and deletes through the
public OSS endpoint; presigned ECS release downloads use the intranet endpoint.
This deployment transport is separate from the application's two OSS clients.
Finally run
`initialize-and-verify.sh database`: confirm empty state, import schema in its
own invocation, import the idempotent four-template seed, then run the separate
read-only postcondition. `.database.imported` checkpoints schema and
`.database.templatesImported` checkpoints templates. A legacy manifest missing
only the latter runs the seed without repeating schema DDL. Never rerun DDL
because post-validation failed.

The current Alibaba Cloud CLI accepts raw `CommandContent` and performs API
encoding. Persist `InvokeId` (or legacy `InvocationId`) immediately after
submission, before polling with `--InvokeId`. Read `InvokeRecordStatus` with a
legacy `InvocationStatus` fallback, wait for a terminal state, and require the
real `ExitCode` to be zero. On a configuration-only retry, run
`deploy-via-cloud-assistant.sh --config-only` and resume from the affected
postcondition without retransferring immutable artifacts.

Preflight records the installed ossutil version and probes the actual `cp`,
`rm`, and `presign` or legacy `sign` help. Deployment uses the resulting
endpoint, region, expiry, and non-interactive force flags through one wrapper;
do not copy flags from examples for another major version. Use the standalone
`ossutil` binary. A cached STS credential is permitted only when intentional,
and neither its token nor any generated signed URL may be printed.

## Phase 6: Rolling Service Activation

Install `assets/systemd/autowonder.service`, point `/opt/autowonder/current`
atomically at the new release, and start one node. Require systemd active state,
a port 7001 listener, `/checkpreload.htm` body `success`, the public branding
endpoint, non-root process ownership, and capabilities before enabling it in
ALB. Never use authenticated `/api/health` as a startup probe. Repeat for the
second node. Logs live
under `/var/lib/autowonder/logs` and in the systemd journal.

Execute this phase with `initialize-and-verify.sh rolling-start`.

**Output:** both nodes and public ingress healthy; **Application ready** candidate.

## Phase 7: Business Initialization

Run `scripts/initialize-and-verify.sh business-init` to create only `admin`.
Do not ask for an organization name or create a workspace. The user creates one
through the existing workspace screen after login. Database/system templates remain required.

Persist deployment-bound credentials at `/etc/autowonder/admin-bootstrap.json`
(root-owned, mode 0600) on the first ECS before registration, under an exclusive lock.
On conflict, verify the saved credentials only for recovery. If verification fails,
stop and preserve the account; never delete or reset an admin based on missing organization
membership. A completed manifest reuses its protected local handoff file without registering again.
The remote recovery file remains sensitive; deliver credentials through the existing encrypted
handoff and tell the user to change the initial password. Legacy organization fields are ignored,
not used to delete existing organizations or create new ones.

**Output:** **Business initialized** candidate.

## Phase 8: Acceptance

Run `scripts/initialize-and-verify.sh acceptance --manifest FILE`. The current
deployment-scoped entrypoint checks exactly two distinct ALB public IPv4
addresses and requires `/checkpreload.htm` to return `success` on each. It then
records `health=passed`, `albPublicIpv4Health=passed`, and `status=accepted`.
This is script acceptance; it does not establish extended acceptance or TLS.
`--acceptance-evidence` and `AUTOWONDER_RUNTIME_PROBE` are not reached by this
scoped entrypoint and have no effect.

When extended acceptance is requested, perform and retain independent evidence
for all ten checks below; these are not automatically executed by the script:

- application-level RDS and Redis write/read plus persistence after restart;
- AutoWonder requirement-file OSS upload/read/presign/delete;
- stored credential begins with `enc:v1:`, excludes plaintext, and decrypts after restart;
- unique records arrive in system, business, and metrics SLS destinations;
- rolling restart and ECS reboot recovery;
- real packaged runtime/executor connects through ALB port 80;
- tags comply and proxy, application, journal, and Cloud Assistant evidence has
  no query token or credential secret material. A RAM AccessKey ID alone is
  permitted as a non-secret resource identifier.

Security-group listings are control-plane evidence; use real data-plane probes.
If SLS reports `IndexConfigNotExist`, cursor movement is degraded evidence, not
a reason to mutate an existing store. New Terraform stores should have indexes.
Record each deep check as `passed`, `degraded`, `failed`, or `pending` in separate
extended-acceptance evidence. The script preserves existing manifest fields but
does not rerun or import these checks. Extended acceptance remains partial while
any required check, including the real packaged runtime WebSocket probe, is
pending; this does not change the script acceptance status.

## Phase 9: Handoff

Run `scripts/sanitize-evidence.sh` before publishing the report. Separate
Infrastructure ready, Application ready, Business initialized, Release accepted,
and TLS accepted. Pending DNS or TLS does not erase lower-level success, but
plaintext `ws://` on port 80 can never satisfy TLS acceptance. Label the current
script result as ALB public IPv4 acceptance; claim extended acceptance only when
its separate ten-check evidence passes. The scoped script does not verify TLS.

For TLS acceptance, the HTTPS health request must succeed with curl's default
certificate-chain and hostname verification. Merely choosing the certificate
scenario or opening TCP port 80 is not evidence.

Display the `admin` username and generated password once directly to the user,
outside reports and logs, then require password rotation. Include manifest path,
resource summary, URLs, hashes, pending actions, rollback boundary, log paths,
and support commands without secrets.

After all deployment statuses and the administrator handoff are complete, ask
once for the credential export preference:

- no export (default): leave secrets only in their protected runtime/state stores;
- encrypted local bundle: require a user-selected path and encryption recipient;
- external secret manager: require an explicit destination and authenticated tool.

Before exporting values, show a secret-name inventory covering the administrator,
database application account, application OSS/SLS RAM credential, SecretCrypto
master key, and JWT secret. Exclude the operator's pre-existing Alibaba Cloud
credential chain. Never print values to chat or place them in the manifest,
sanitized report, shell history, or logs. Confirm successful import at the chosen
destination before deleting any temporary handoff material.

## Resume And Confirmation

In staged mode ask only whether to start the next recorded phase. In unattended
new-deployment mode, automatically approve a machine-reviewed safe Terraform
plan and continue directly to apply, stopping on any safety condition. A
completed mutation is never repeated until its postcondition proves it did not
finish. Teardown is a separate authorized scope; reuse explicit authorization
already covering this deployment instead of requesting it again.

Before `destroy-plan`, verify ownership and the reviewed impact, then run
`bash scripts/prepare-teardown.sh --manifest FILE --confirmation-file FILE`;
the confirmation file must contain `DESTROY <deploymentId>`. This prepares only
reviewed deletion-protection/retention changes, preserves production defaults,
and does not perform subscription refunds. Complete exact-resource BSS
unsubscription for prepaid resources before the main destroy. Review the saved
destroy plan and run `destroy-apply` with its exact fingerprint. After verified
main destruction, backend cleanup automatically deletes the tfstate bucket and
all its versions plus the dedicated operations bucket. Preserve recovery state
if destruction fails or its outcome is uncertain.

## Adaptive resource selection and model candidates

`assets/deployment-policy.json` is the source for the supported product shapes.
New deployments snapshot it into `resourceSelection.policy`; ordinary OSS
restore and application operations do not run a new selection. SKU names and
zones come from live APIs. ECS stays enterprise-class x86 2 vCPU/4 GiB per node;
RDS stays MySQL 8 HA, 2 vCPU/4 GiB and 100 GiB ESSD; Redis stays
community Redis 7, standard primary/replica, 1 GiB. An out-of-policy capacity requires a separately authorized policy change. Monthly subscriptions and auto-renew are unchanged.
The default image family remains Alibaba Cloud Linux 3; the collector checks
system-image availability and compatibility with each instance type. It does
not automatically upgrade OS, database, CLI, or Terraform provider versions.

Run these using the bootstrap's private Python environment and auto-wonder
profile. Region and account UID must already be recorded in the manifest.

```bash
bash scripts/resolve-zones.sh --manifest "$manifest"
# If selection needs investigation, obtain temporary read-only normalized facts:
bash scripts/resolve-zones.sh discover --manifest "$manifest" --output "$inventory"
# The Agent may submit a JSON candidate; it cannot mark itself verified:
bash scripts/resolve-zones.sh resolve --manifest "$manifest" --candidate "$candidate"
```

A candidate has exactly `availabilityZones` and `resolvedInfrastructure`, with
the latter containing `ecsInstanceType`, `ecsImageId`, `ecsVcpus`, `ecsMemoryGiB`,
`rdsInstanceType`, `rdsCategory`, `rdsStorageType`, `rdsStorageGb`,
`redisInstanceClass`, and `zonePlan`. Copy the structure from a resolver result;
choose only items supported by the freshly collected facts. The `zonePlan`
contains ECS/ALB zones, RDS primary/slave zones, Redis primary/secondary zones,
`downgrades` (empty unless Redis secondary uses a third zone), and
`resolvedBy: "inventory"`. No shell fragments, inferred SKU IDs, or fabricated
evidence are accepted. Resolve queries again and independently validates the
candidate; the Agent must not directly edit `verified`, evidence, or hashes.

Selection prefers a complete cross-product combination, then comparable core
subscription price, shared zones and the ECS soft preference. A named preferred
SKU never overrides HA, capacity, disk, image, billing or existing-resource
constraints. Redis can use an evidenced third standby zone without another
switch; RDS discovery currently proves the two network-bearing zones only. A
third RDS zone whose placement needs additional network evidence is a maintenance
boundary, not permission to invent compatibility or create a third switch.

Exit codes are 0 success, 2 malformed/missing input, 3 needs-agent (including
incomplete facts), and 4 blocked/invalid. Unknown fields required by the supported
API shape, permission failures, failed/repeated pages and missing quotes are
never treated as sold-out stock or successful checks. Read-only transient calls
have at most three attempts. Discovery is bounded; incomplete discovery does
not prove that the whole region lacks resources. At most three resolve attempts
are checkpointed per planning run; do not reset this counter to hide a failure.
After exhaustion diagnose the cause and document a new planning run explicitly.

The Agent may issue additional **read-only** CLI queries and consult current
[Alibaba API documentation](https://api.aliyun.com/), then submit a supported
candidate. A new product/API shape that the adapter cannot validate requires a
maintenance change, not an in-session bypass of the validator. No extra user
confirmation is needed for a compliant candidate within the authorized scope.

Prices are original first-month core subscription quotes; promotional trade
prices are evidence only. They are not renewal guarantees or a full monthly bill:
ALB, OSS and SLS usage charges are excluded. If an existing user-approved budget
is supplied, record `budget: {"scope":"core-subscriptions", "currency":"CNY",
"monthlyLimit":123}` (use the actual approved amount). Other budget scopes cannot
be declared satisfied by a partial quote. Do not invent a budget. Catalogues do
not reserve stock; final ordering still checks quotas, account restrictions and
placement. RDS pairs use both live HA zone offers and the official CreateDBInstance
rule: distinct primary/secondary zones with the two VSwitches in matching order.
The manifest records that rule as placement evidence. This is not an order
precheck: CreateDBInstance DryRun needs actual network IDs which do not yet exist
for a fresh deployment. No inventory check claims to reserve stock.

`terraform-stage.sh plan` refreshes Terraform state and produces a local plan,
then verifies current selection facts, actual plan variables, core resources,
zones, subscription settings, capacity, and absence of deletion/replacement or
updates to existing resources. It binds selection, inputs/configuration, core
quote and the exact binary plan fingerprint. Normal machine review remains
required. `apply` checks that binding, refreshes facts for resources being
created, and checks the binding again before submission. Changed inputs or
quotes require a new plan. Never apply a different plan with an old fingerprint.
For existing products whose refreshed plan proves `no-op`, their stored shape
is retained without requiring them to remain on sale. A historical partial
deployment without selection evidence may need read-only reconciliation before
it can satisfy the new creation gate; daily operations on completed deployments
remain on their existing paths.

A new computer recovers `resourceSelection` with the existing OSS manifest,
secrets, backend coordinates and sealed artifacts through `operations-store.py
resolve`. No old local directory is required. Selection writes use the existing
revision check and checkpoint; upload failure stops progress. Inventory history
is explanatory evidence, not a cache for future purchases. Binary Terraform
plans are deliberately not in OSS: re-plan on the new computer before applying.
Pending/unknown Terraform operations still require reconciliation first; never
clear their marker merely to retry creation.
