---
name: deploying-autowonder-on-alibaba-cloud
description: Use when deploying, resuming, operating, troubleshooting, or removing an AutoWonder community environment on Alibaba Cloud, including Terraform, ECS, NLB, RDS, Redis, OSS, SLS, domains, TLS, and executor connectivity.
---

# Deploying AutoWonder On Alibaba Cloud

## Modes

Select the mode before any read/write workflow:

| Mode | Action |
| --- | --- |
| New deployment | collect inputs, plan, create, initialize, and verify |
| Resume deployment | reconcile manifest, Git, Terraform, and live state; continue at an idempotent boundary |
| QA and diagnosis | answer from references and perform only authorized read-only inspection |
| Teardown | review impact and destroy plan, then require separate destructive confirmation |

If a manifest exists, ask whether to resume it or create a distinct deployment.
Never apply merely because configuration files exist. QA and diagnosis must not
invoke mutation scripts.

## Build And Runtime Environment

| Purpose | Supported environment |
| --- | --- |
| Source package | JDK 21 and Maven 3.9.9; Maven downloads the pinned Node.js and npm versions automatically |
| Frontend only | Node.js 22.22.2 and npm 10.9.7 |
| Deployment | Bash, Git, jq, Terraform, Alibaba Cloud CLI, ossutil, OpenSSL, and curl on the control host; Linux x86_64 with Java 21 on ECS |

## Initial Questionnaire

For new deployment, read `references/input-catalog.md` and ask **one consolidated questionnaire**
covering region, environment/suffix, account identity, topology,
sizing, CIDRs, ingress/domain/certificate scenario, state, lifecycle,
organization, exact source commit, execution mode, and tags. Use these defaults:

- `multi-zone HA`, small sizing, protected remote state, persistent lifecycle;
- staged execution; `SLS is enabled`; Aone is disabled;
- `OSS is mandatory`; Linux x86_64; no NAT, no public EIP, and no SSH.

| Region presets | Ingress result before trusted TLS |
| --- | --- |
| `cn-zhangjiakou`, `cn-hangzhou`, `cn-shanghai`, `cn-beijing` | no domain: `ws://<nlb-address>:443/ws/executor` |
| preflight must find two distinct zones for HA | domain: `ws://<domain>:443/ws/executor` |
| stop rather than downgrade unavailable HA | domain plus certificate: later verify `wss://<domain>/ws/executor` |

Create a **sanitized manifest** from
`assets/templates/deployment-manifest.json`, then show one review table with all
answers, defaults, risks, cost drivers, and phases. Never store secrets there.
After confirmation, do not re-question configuration. A changed choice invalidates
the plan and requires one new consolidated review.

Default `staged` mode asks only whether to start the next already-defined phase.
Optional `unattended` mode asks once after the final plan, then continues until a
safety stop or failure. Teardown always requires a new explicit confirmation.

## Safety Rules

Stop and report sanitized evidence when:

- account, region, deployment ID, ownership, or expected tags differ;
- two-zone HA inventory is unavailable, or a plan silently downgrades topology;
- the database is non-empty or a non-idempotent mutation has uncertain status;
- plan/apply contains unexpected deletion/replacement or wildcard app permissions;
- a network change affects another workload or requires public SSH/ECS egress;
- checksum, schema, health, tag, encryption, or application-level storage fails;
- a credential or query token appears in logs/evidence;
- safe recovery requires guessing, broadening access, or printing Terraform state.

Use the local Alibaba Cloud credential chain. Keep secrets out of command lines,
Git, manifest, Cloud Assistant output, proxy/application logs, and reports.

## Phase State Machine

Run phases in order and record terminal results in the manifest. Read
`references/operations-runbook.md` for preconditions, evidence, and resume rules.

| Phase | Deterministic route | Completion candidate |
| --- | --- | --- |
| 1. Preflight | `scripts/preflight.sh` (`--profile` locks a verified CLI profile) | validated tools, identity, inputs/inventory |
| 2. Plan | `scripts/terraform-stage.sh plan` | reviewed plan fingerprint |
| 3. Apply | `scripts/terraform-stage.sh apply`, then `inventory` | Infrastructure ready |
| 4. Build | `scripts/build-release.sh` | sealed local JAR, schema, and template seed |
| 5. Host/DB/runtime | `scripts/initialize-and-verify.sh runtime-config`; `scripts/deploy-via-cloud-assistant.sh`; then `scripts/initialize-and-verify.sh database` | Java 21, clients, release, env (including public base URL), systemd, schema and four system templates installed |
| 6. Rolling activation | `scripts/initialize-and-verify.sh rolling-start` | Application ready |
| 7. Business init | `scripts/initialize-and-verify.sh business-init` | Business initialized |
| 8. Acceptance | `scripts/initialize-and-verify.sh acceptance` | Release accepted; TLS independently checked |
| 9. Handoff | `scripts/initialize-and-verify.sh handoff`; `scripts/sanitize-evidence.sh` | sanitized report and one-time credentials |

Shared guards live in `scripts/lib.sh`. Apply only a reviewed saved plan whose
hash matches the manifest. On resume, reconcile real postconditions before
retrying; never blindly repeat apply, schema import, or administrator creation.
Cloud Assistant invocation IDs are checkpointed immediately. After an env-only
correction, use `deploy-via-cloud-assistant.sh --config-only`; do not upload the
JAR, schema, systemd unit, or Java archive again. Acceptance reruns preserve
already-passed deep checks instead of resetting them to pending.

The immutable release includes `autowonder-community-templates.sql`. Database
initialization imports it after the schema and records
`.database.templatesImported`. On an older manifest without this checkpoint,
resume the database phase to run only the idempotent template seed and postcheck.

For teardown, read `references/acceptance-and-rollback.md`, run
`scripts/terraform-stage.sh destroy-plan`, review backups/impact/hash, and obtain
separate confirmation before applying destruction.

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

## Output Contract

Report these statuses separately: **Infrastructure ready**, **Application ready**,
**Business initialized**, **Release accepted**, and **TLS accepted**. Never count
plaintext port 443 as TLS. Mark checks completed, pending, degraded, or failed;
include exact source/hash, topology, URLs, evidence references, rollback boundary,
and next actions without live secret or identity data.

At final handoff display username `admin` and its generated password exactly once
to the user, outside the manifest/report/logs, and require immediate rotation.
