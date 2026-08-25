# Architecture And Resources

## Purpose

Use this reference to explain topology, resource ownership, cost, identity,
state, and lifecycle behavior before planning or when answering architecture QA.

## Default Multi-Zone Topology

The default `multi-zone HA` environment contains:

- one VPC and two VSwitches in different availability zones;
- two Linux x86_64 ECS nodes, one in each zone, each exactly 2 vCPU and 4 GiB
  memory, with no public IP; prefer `ecs.c8a.large`;
- one dual-zone public Application Load Balancer (ALB) with an HTTP listener on port 80;
- MySQL 8 in a region-supported HA or cluster form;
- Redis 7 in a region-supported multi-zone primary/replica form;
- two private OSS buckets and one SLS project;
- one least-privilege application RAM user and AccessKey.

Preflight resolves current regional inventory instead of assuming fixed zone or
SKU names. Stop when two zones or a genuine HA database/cache product cannot be
supplied. An ECS fallback is permitted only when it is x86_64, exactly 2 vCPU
and 4 GiB, and available in both zones. Two nodes in one zone and an RDS Basic
instance are not HA.

The `experience` topology is a separately chosen low-cost environment. Label it
as non-HA and never silently downgrade the default.

## Traffic And Egress

The ALB whitelist accepts only configured source CIDRs on public port 80, then forwards
to ECS application port 7001. RDS and Redis accept only the minimum
private network scope. ECS uses private endpoints for RDS, Redis, OSS, and SLS.
There is no NAT, no public EIP, and no SSH rule. Git checkout and application
build occur locally; Cloud Assistant manages hosts.

No-domain and domain-without-certificate scenarios use temporary plaintext
`ws://`. A trusted certificate-bound domain is required for production `wss://`.
DNS remains outside Terraform in V1.

## OSS And SLS

OSS cannot be disabled or downgraded to local storage:

- package bucket: task and skill packages plus deployment staging objects;
- artifact bucket: execution artifacts and the application's base bucket.

Both buckets are private and have globally unique generated names. Acceptance
uses the application itself to upload, read, presign, and delete an object. The
application uses the regional intranet endpoint for server-side reads and writes,
and a separate regional public HTTPS endpoint to sign URLs returned to browsers
or executor runtimes. The signed URL hostname is never rewritten.

SLS is enabled by default with three independent destinations:

- `system`: Logstore with searchable index;
- `business`: Logstore with searchable index;
- `metrics`: MetricStore.

The application endpoint is a bare regional hostname, without `http://` or
`https://`. Acceptance emits a unique marker to every destination.

## Identity And Permissions

Terraform uses the operator's existing local Alibaba Cloud credential chain and
does not create an operator. It creates one application RAM identity whose
policy is scoped to the exact two buckets, the created SLS project, and its three
stores. It has no ECS, ALB, VPC, RDS, or Redis control-plane permission.

The generated application AK/SK is secret deployment input. It must not appear
in the sanitized manifest, Terraform command line, Cloud Assistant output, logs,
or public evidence. Rotate it during incident response and planned credential
maintenance.

## Tags

Apply supported tags to every taggable resource:

| Key | Source |
| --- | --- |
| `Project=AutoWonder` | fixed |
| `Environment=auto-wonder-prod` | fixed |
| `DeploymentId` | generated |
| `ManagedBy=Terraform` | fixed |
| `Topology` | selected topology |
| `Owner`, `CostCenter` | not collected for new deployments |

Only system tags are generated for new deployments. Report provider resource
types that cannot be tagged, then run a post-apply tag inventory.

## State And Lifecycle

Protected remote Terraform state is mandatory and automatic. The dedicated
private state bucket and fixed absolute `backend.hcl` path are derived by
`scripts/terraform-backend.sh`; they are never user inputs. `sensitive = true`
only hides CLI rendering: state still contains values, so access to state is
access to secrets. The state bucket is deleted only after verified main
Terraform destruction.

Persistent environments enable available deletion protection and backups.
Temporary test environments allow planned teardown. Normal application rollback
does not destroy RDS, Redis, OSS, SLS, or Terraform state. Any teardown receives
its own impact review and destructive confirmation.

## Cost Drivers

Major cost drivers are two ECS nodes, public ALB traffic, HA RDS, multi-zone
Redis, storage/requests in two OSS buckets, SLS ingestion/indexing/retention, and
remote state resources. Presets express an intention; query live inventory and
pricing before plan approval. Recommendations are not a live price quote.
