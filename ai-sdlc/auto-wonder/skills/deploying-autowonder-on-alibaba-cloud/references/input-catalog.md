# Deployment Input Catalog

## Purpose

Use this reference before a new deployment or when creating a separate
environment. Collect every choice once, show one sanitized review, and do not
reopen configuration questions after approval.

## One-Time Questionnaire

Ask for the complete table in one message. Apply the listed default when the
user does not override it.

| Input | Allowed values or validation | Default |
| --- | --- | --- |
| Mode | new, resume, QA, teardown | new |
| Region | `cn-zhangjiakou`, `cn-hangzhou`, `cn-shanghai`, `cn-beijing` | none |
| Environment and suffix | lowercase letters, digits, hyphens; globally unique suffix | none |
| Account UID | identity check and collision-resistant naming only | none |
| Topology | `multi-az-ha` or explicitly selected `experience` | `multi-az-ha` |
| Size | small, medium, or custom SKU overrides | small |
| Networks | non-overlapping VPC CIDR and two VSwitch CIDRs | suggested private ranges |
| Public sources | one or more approved CIDRs for NLB port 80 | none |
| Ingress | no domain/no certificate; domain/no certificate; domain/certificate | no domain/no certificate |
| Domain | DNS name for either domain scenario | empty otherwise |
| State | protected remote state or local state | remote |
| Lifecycle | persistent or temporary test | persistent |
| Organization | required non-empty first organization name | none; user must provide |
| Source | repository URL, ref, and exact commit | community ref |
| Execution | staged or unattended | staged |
| Tags | optional Owner, CostCenter, and custom tags | none |

Also confirm the two availability zones discovered by preflight. They must be
different for `multi-az-ha`. Custom tags cannot replace `Project`, `Environment`,
`DeploymentId`, `ManagedBy`, or `Topology`, and must never contain secrets.

## Fixed Decisions

- OSS is mandatory: create a private package bucket and a private artifact bucket.
- Application `OSS_ENDPOINT` is the regional intranet endpoint for server-side
  object I/O. `OSS_PUBLIC_ENDPOINT` is the matching regional public HTTPS
  endpoint for links consumed by browsers and executor runtimes.
- SLS is enabled and creates system, business, and metrics stores.
- Aone is disabled. Linux x86_64 and Java 21 are the supported runtime.
- Executor commands use manifest `recommendedRuntimeVersion` (`0.2.125` for this release).
- ECS has no NAT, public EIP, or SSH access. Cloud Assistant performs host work.
- DNS is not modified. The user owns DNS and certificate binding.
- The initial username is `admin`; generate its password during initialization.

## Ingress Choice

| Scenario | Initial executor URL | TLS state |
| --- | --- | --- |
| No domain, no certificate | `ws://<nlb-address>/ws/executor` | pending; temporary plaintext |
| Domain, no certificate | `ws://<domain>/ws/executor` | pending; temporary plaintext |
| Domain and certificate | same temporary endpoint until binding, then `wss://<domain>/ws/executor` | accepted only after a trusted handshake |

The NLB TCP listener exposes public port 80 to the approved CIDRs and forwards
to ECS application port 7001. Plaintext on port 80 is never described as TLS.

## Manifest And Terraform Mapping

Write non-secret answers to a copy of
`assets/templates/deployment-manifest.json`. The principal mapping is:

| Answer | Manifest key | Terraform input |
| --- | --- | --- |
| region | `region` | `region` |
| environment | `environment` | `environment` |
| generated ID | `deploymentId`, tag value | `deployment_id` |
| topology | `topology`, tag value | `topology` |
| size | `sizePreset` | resolved ECS/RDS/Redis SKU variables |
| zones | resource planning record | `zone_a_id`, `zone_b_id` |
| public CIDRs | `publicSourceCidrs` | `public_source_cidrs` |
| ingress/domain | `ingressScenario`, `domain` | listener/domain metadata inputs |
| state choice | `stateMode`, `terraform.stateReference` | backend bootstrap choice |
| lifecycle | `lifecycle` | `lifecycle_mode` |
| org | `organizationName` | initialization script only |
| source | `repositoryUrl`, `repositoryRef`, `repositoryCommit` | build script only |
| execution | `executionMode` | orchestrator only |
| tags | `tags` | `common_tags` |

The manifest must remain sanitized. Never add passwords, SecretCrypto master
key, JWT secret, AK/SK, STS token, presigned URL, query token, or administrator
password. Pass required Terraform secrets through protected `TF_VAR_*`
environment variables or mode-restricted transient files.

For remote state, `terraform.stateReference` is the path to a protected OSS
backend configuration file. The backend must already exist; V1 stops instead of
silently writing state locally when that reference is absent.

## Review And Confirmation

Show one sanitized table covering every answer, resolved defaults, topology,
estimated cost drivers, security exceptions, state location, and planned phases.
In staged mode, subsequent prompts ask only whether to start the next already
defined phase. In unattended mode, ask once after final plan review. A later
configuration change invalidates the plan fingerprint and requires a new review.
