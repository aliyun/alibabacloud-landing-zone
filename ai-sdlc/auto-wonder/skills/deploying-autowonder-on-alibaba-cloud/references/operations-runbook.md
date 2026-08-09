# Deployment Operations Runbook

## Purpose

Use this reference to execute or resume the manifest-backed nine-phase workflow.
Scripts are deterministic boundaries; do not replace their safety checks with
ad hoc cloud mutations.

## Common Rules

1. Keep the manifest outside Git and set restrictive permissions.
2. Use the local Alibaba Cloud credential chain. Select a known-good profile once
   with `preflight.sh --profile <name>` when necessary; never change global CLI
   configuration as part of a deployment. Export the selected region and pass
   the CLI global region when an API has no request-level `RegionId`.
3. Run host commands through Cloud Assistant. Poll every invocation to terminal
   status and require exit code zero; retain only sanitized output.
4. Never put raw secret values in command content or output. V1 permits one
   recorded exception: a 15-minute private-intranet presigned URL for secret-file
   transport. Never print it; delete the unique OSS object
   immediately after installation and treat the URL as exposed until expiry.
5. Resume only after reconciling Git, manifest, Terraform state, and live state.

## Phase 1: Preflight

**Inputs:** completed questionnaire, exact source commit, two candidate zones,
local credential chain.

Resolve image and ECS/RDS/Redis SKUs with read-only inventory and price APIs,
then record them in the manifest. Run `scripts/preflight.sh --manifest <file>
--source-dir <repo>`. It validates tools, account identity, region, resolved
x86_64 inputs, two distinct zones, CIDRs, tags, topology, and source commit.
Stop on identity mismatch, unsupported HA inventory, public egress/SSH
requirement, invalid CIDR, or a secret-bearing manifest.

**Output:** reconciled preflight evidence and resolved non-secret inputs.

## Phase 2: Terraform Plan

Run `scripts/terraform-stage.sh plan --manifest <file> --work-dir <dir>`. The
stage formats, initializes, validates, and saves an immutable plan plus its hash.
Reject wildcard application permissions, public SSH, absent tags, same-zone HA,
secret defaults, or unexpected destroy/replace actions. Show the sanitized plan,
cost drivers, and security exceptions for final confirmation.

**Output:** plan file and `terraform.planFingerprint` in the manifest.

If provider distribution is unreachable, use a deployment-local filesystem
mirror only after verifying the official archive checksum. Point Terraform at
that mirror through a deployment-local CLI config; do not modify system DNS or
commit the provider binary. Reuse the verified mirror on retries.

## Phase 3: Terraform Apply

Run `scripts/terraform-stage.sh apply --manifest <file> --work-dir <dir>
--approved-plan-sha256 <hash>`. Apply only the reviewed saved plan. Then run the
inventory command, reconcile IDs without publishing them, verify zones, tags,
protection, listener sources, private endpoints, and application RAM scope.

**Output:** infrastructure inventory and **Infrastructure ready** candidate.

## Phase 4: Immutable Build And Transfer

Run `scripts/build-release.sh` against the exact repository commit. It runs the
complete build verification with `-DskipFrontend=false`, records
JAR/schema/template-seed hashes, and refuses a dirty or mismatched source. It
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
It also replaces any stale `AUTOWONDER_RUNTIME_RECOMMENDED_VERSION` with the
manifest `recommendedRuntimeVersion`; the manifest is the deployment source of truth.
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
NLB. Never use authenticated `/api/health` as a startup probe. Repeat for the
second node. Logs live
under `/var/lib/autowonder/logs` and in the systemd journal.

Execute this phase with `initialize-and-verify.sh rolling-start`.

**Output:** both nodes and public ingress healthy; **Application ready** candidate.

## Phase 7: Business Initialization

Run the initialization portion of `scripts/initialize-and-verify.sh`. Generate a
strong random password for `admin`, create the first user and requested
organization, and verify the user owns and administers it. If records already
exist, reconcile them; do not blindly create duplicates.

Keep the password only in the protected process until the final one-time handoff.
**Output:** **Business initialized** candidate.

## Phase 8: Acceptance

Run `scripts/initialize-and-verify.sh` acceptance checks:

- application-level RDS and Redis write/read plus persistence after restart;
- AutoWonder requirement-file OSS upload/read/presign/delete;
- stored credential begins with `enc:v1:`, excludes plaintext, and decrypts after restart;
- unique records arrive in system, business, and metrics SLS destinations;
- rolling restart and ECS reboot recovery;
- real packaged runtime/executor connects through NLB port 80;
- tags comply and proxy, application, journal, and Cloud Assistant evidence has
  no query token or credential material.

Security-group listings are control-plane evidence; use real data-plane probes.
If SLS reports `IndexConfigNotExist`, cursor movement is degraded evidence, not
a reason to mutate an existing store. New Terraform stores should have indexes.
Record each deep check as `passed`, `degraded`, `failed`, or `pending`; a rerun
must merge with completed evidence. Release acceptance remains partial while the
real packaged runtime WebSocket probe is pending.

## Phase 9: Handoff

Run `scripts/sanitize-evidence.sh` before publishing the report. Separate
Infrastructure ready, Application ready, Business initialized, Release accepted,
and TLS accepted. Pending DNS or TLS does not erase lower-level success, but
plaintext `ws://` on port 80 can never satisfy TLS acceptance.

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
mode continue after the single final plan confirmation, stopping on any safety
condition. A completed mutation is never repeated until its postcondition proves
it did not finish. Teardown is always a separate mode and confirmation.
