# Deployment Input Catalog

## Purpose

Collect new-deployment choices once with one copyable template. Environment,
source, tags, and Terraform state OSS coordinates are fixed and must never be
asked of the user.

## One-Time Questionnaire

Send this exact copyable template. Do not replace the first field with region
choices and do not add environment, source, tags, or backend questions.

```text
请复制下面的模板并填写：

请提供部署区域，比如北京、杭州：
阿里云账号 UID：
组织名称：
公网访问来源 CIDR：（填写“自动识别”或具体 CIDR）
接入方式：（无域名 / 有域名无证书 / 有域名和证书）
域名：（无域名时填写“无”）
网络 CIDR：（填写“自动建议”或 VPC CIDR、两个交换机 CIDR）
```

Map 北京、杭州、上海、张家口 to `cn-beijing`, `cn-hangzhou`,
`cn-shanghai`, `cn-zhangjiakou`. An ambiguous or unsupported place name
requires correction; never guess.

## Fixed Decisions

- `environment` and its required tag are always `auto-wonder-prod`.
- Topology is always dual-zone high availability: two ECS instances in separate
  zones, HA RDS, cross-zone Redis, and a dual-zone public Application Load
  Balancer (ALB). Sizing is always the small preset. Never ask the user for
  topology, load-balancer type, or sizing.
- Each ECS node is exactly 2 vCPU and 4 GiB memory. Prefer `ecs.c8a.large`.
  A fallback must be x86_64, provide the same 2-vCPU/4-GiB capacity, and be
  available in both selected zones. Stop instead of lowering or raising capacity.
- Lifecycle is always `persistent` (formal production environment) and execution
  mode is always `unattended`. Never ask the user to choose or confirm either
  value. Obtain one confirmation after the final deployment plan, then continue
  automatically until a safety stop or failure.
- Use the current workspace contents, including uncommitted or untracked
  changes. Do not inspect or validate Git information, and do not fetch, pull,
  merge, checkout, or ask for a repository/ref/commit. Git state must not block
  Terraform apply or the application build.
- Optional tags are not collected. Derive only Project, Environment,
  DeploymentId, ManagedBy, and Topology.
- Remote Terraform state is mandatory. Never ask for a state bucket or backend
  path and never fall back to local state.
- Existing Terraform creates the private application package and artifact OSS
  buckets. `scripts/terraform-backend.sh` separately creates the state bucket.
- SLS is enabled, Aone is disabled, and ECS has no NAT, public EIP, or SSH.
- The initial username is `admin`; generate its password during initialization.

## Automatic State Backend

The bucket is `aw-tfstate-<deployment-id>-<hash12>`, where `hash12` is the first
12 lowercase hex characters of SHA-256 over
`<account-uid>|<region-id>|<deployment-id>`. The state key is always
`states/<deployment-id>/terraform.tfstate`.

The protected deployment root is
`<current-project-root>/deployments/<deployment-id>`. The backend file is always
`<deployment-root>/terraform/backend.hcl`. Resolve it programmatically and write
it to `terraform.stateReference`; the model and user never choose it.

Prepare reconciles account ownership, region, private ACL, required tags, and
name. An exact match is idempotent; a collision or mismatch stops. After verified
main Terraform destruction, delete all object versions and multipart uploads,
delete the bucket, and remove the local backend directory. Retain no state copy.

## Application OSS And Ingress

Application `OSS_ENDPOINT` is the regional intranet HTTPS endpoint and
`OSS_PUBLIC_ENDPOINT` is the matching public HTTPS endpoint. Both remain
mandatory. No-domain and domain-without-certificate scenarios use `ws://` on
port 80; only a trusted certificate handshake satisfies `wss://` TLS acceptance.

## Manifest And Review

Create a sanitized copy of `assets/templates/deployment-manifest.json`. Record
the generated DeploymentId, resolved region, answers, and automatic backend
metadata. Never store passwords, AK/SK, tokens, signed URLs,
or administrator credentials.

Show one review covering answers, fixed values, risks, cost drivers, and phases.
After its single confirmation, unattended execution continues automatically.
