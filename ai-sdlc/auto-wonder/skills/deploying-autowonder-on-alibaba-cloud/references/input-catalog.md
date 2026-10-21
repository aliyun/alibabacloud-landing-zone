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
部署拓扑：（多可用区，推荐 / 单可用区）
阿里云账号 UID：
公网访问来源 CIDR：（填写“自动识别”或具体 CIDR）
接入方式：（无域名 / 有域名无证书 / 有域名和证书）
域名：（无域名时填写“无”）
网络 CIDR：（填写“自动建议”或 VPC CIDR、两个交换机 CIDR；单区业务仍需双区 ALB 入口）
```

Accept Alibaba Cloud public-cloud region IDs, including domestic and international
regions. The template's `regionPreset` is advisory, never a supported-region
allowlist. Map 北京、杭州、上海、张家口、新加坡 to `cn-beijing`, `cn-hangzhou`,
`cn-shanghai`, `cn-zhangjiakou`, `ap-southeast-1`. For other place names, query
`aliyun ecs DescribeRegions --profile auto-wonder` and match the live catalogue's
RegionId/LocalName; ambiguous names require correction, never guessing.
A region ID is eligible only when the shared resolver verifies live ECS, image,
RDS, Redis and ALB inventory, placement and subscription prices there. Do not
reject a public region merely because it is absent from the examples. Do not
assume every product, single-zone ALB or subscription offering exists everywhere.

When 公网访问来源 CIDR is “自动识别”, run the public-IP helper and copy all
returned `publicSourceCidrs` into the manifest without asking for confirmation.
Use every distinct detected address as a `/32`, even if only one service succeeds
or services report different addresses. Retry or request manual input only when
no valid public IPv4 is returned.

## Topology Choice And Fixed Decisions

- `environment` and its required tag are always `auto-wonder-prod`.
- Collect topology explicitly: recommend 多可用区 (`multi-az-ha`), with two ECS
  instances in separate zones, HA RDS and cross-zone Redis. 单可用区 (`single-az`)
  uses one ECS and same-zone RDS primary/standby and Redis primary/replica,
  retaining the same product shapes and small sizing. Explain that single-zone
  business placement has no cross-zone disaster recovery. In both modes the
  public ALB still spans two zones: Alibaba Cloud requires
  at least two ALB zones in these regions. Do not ask for load-balancer type or sizing.
  Do not default an unanswered interactive topology field. For explicitly
  authorized unattended tests, the agent may select the requested topology.
- Each ECS node is exactly 2 vCPU and 4 GiB memory; `ecs.c8a.large` is a soft preference.
  A fallback must be x86_64, provide the same 2-vCPU/4-GiB capacity, and be
  available in every selected business zone. Stop instead of lowering or raising capacity.
- Availability zones are resolved automatically from live subscription stock by
  `scripts/resolve-zones.sh` and are never asked of the user. The primary infrastructure pair anchors business placement.
  ALB zones may differ where live product support requires it; the shared
  Terraform module adds only the required extra non-overlapping subnets. Standby placement must
  follow the evidenced rules in operations-runbook.md.
- Lifecycle is always `persistent` (formal production environment) and execution
  mode is always `unattended`. Never ask the user to choose or confirm either
  value. Once required inputs are complete and validated, show the deployment
  summary and continue automatically until a safety stop or failure. Do not
  require a separate confirmation to start deployment or create resources.
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
mandatory. Without a domain, use `http://<alb-public-ipv4>` for the application
base URL, selecting the numerically first of the two ALB EIPs; never use the ALB
DNS name as this default. No-domain and domain-without-certificate scenarios
use `ws://` on port 80; only a trusted certificate handshake satisfies `wss://` TLS acceptance.

## Manifest And Review

Create a sanitized copy of `assets/templates/deployment-manifest.json`. Record
the generated DeploymentId, resolved region, answers, and automatic backend
metadata. Never store passwords, AK/SK, tokens, signed URLs,
or administrator credentials.

Show one review covering answers, fixed values, risks, cost drivers, and phases
as an informational progress update, then continue unattended without waiting for
a reply. Do not ask “请确认是否按此方案开始部署” or require “确认”. Request only
missing or ambiguous required inputs. Respect an explicit user request for a
review-only plan or approval checkpoint, and retain the Skill's Safety Rules.

Resource selection uses assets/deployment-policy.json and live discovery. Specific SKUs are soft preferences or verified results, never questionnaire defaults. See operations-runbook.md for candidate submission, capacity and budget constraints.

`availabilityZones` records the original infrastructure pair; ECS zones are in
`resolvedInfrastructure.zonePlan.ecsZones`. RDS, Redis and ALB record their own
verified placements alongside. Automatically prefer all products in the same
pair, then one shared zone, then disjoint pairs, before comparing prices. Reuse
switches and allocate additional private subnets from free VPC space where
required. Do not expose fallback choices in the questionnaire or intermediate
notices; show the actual zones/subnets, reasons and priority in the final resource
report. See operations-runbook.md for ranking, evidence and existing-resource rules.
