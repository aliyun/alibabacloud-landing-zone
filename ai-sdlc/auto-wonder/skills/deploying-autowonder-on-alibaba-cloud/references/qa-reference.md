# Operations QA Reference

## Purpose

Use this reference for read-only operational questions. Prefix answers with the
evidence class: **Repository default**, **Manifest value**, **Live observation**,
or **Recommendation**. Never present a default as the state of a live deployment.

## Resource And Network Inventory

- **Repository default:** true multi-zone HA uses two ECS nodes in distinct
  zones, cross-zone NLB, HA MySQL, multi-zone Redis, two OSS buckets, three SLS
  stores, and one scoped application RAM user.
- **Manifest value:** region is one of `cn-zhangjiakou`, `cn-hangzhou`,
  `cn-shanghai`, or `cn-beijing`; topology, sizing, lifecycle, and ingress are
  recorded with `DeploymentId` and tags.
- **Live observation:** obtain resource state from Terraform output/state plus
  read-only Alibaba Cloud APIs. Do not infer health from the manifest alone.
- **Repository default:** ECS has no NAT, public EIP, or SSH. Public ingress is
  NLB port 80 restricted to approved CIDRs and forwarded to ECS port 7001.

## Host Layout And Service

| Item | Repository default |
| --- | --- |
| Releases | `/opt/autowonder/releases/<commit>/auto-wonder.jar` |
| Active release | `/opt/autowonder/current` atomic symlink |
| Java | `/opt/autowonder/runtime/bin/java` |
| Environment | `/etc/autowonder/autowonder.env` |
| Working data/logs | `/var/lib/autowonder`, `/var/lib/autowonder/logs` |
| Service | `autowonder.service`, user/group `autowonder` |

Use Cloud Assistant for `systemctl status autowonder`, `journalctl -u
autowonder`, disk/memory checks, and local health probes. Sanitize output. A
ready node has active systemd state, a port 7001 listener, `success` from
`/checkpreload.htm`, and an HTTP-successful `/api/platform/branding/public`.
The authenticated `/api/health` endpoint is not a startup probe. Capabilities
should report Aone disabled.
`AUTOWONDER_PUBLIC_BASE_URL` is stored in the environment file; when not
explicitly supplied, `runtime-config` derives it from manifest
`applicationBaseUrl` before deployment.

## Configuration And Data Services

- RDS uses MySQL 8 and a dedicated application account. A JDBC compatibility
  query parameter such as `allowPublicKeyRetrieval=true` may be required by the
  selected authentication mode; derive the final URL from live RDS settings.
- Redis 7 uses the private endpoint and configured account/TLS mode.
- The private package bucket stores task/skill packages; the private artifact
  bucket stores artifacts and base objects. OSS is mandatory. Application
  `OSS_ENDPOINT` is the regional public endpoint; the deployment-only intranet
  endpoint is not an application environment value.
- SLS maps system and business to indexed Logstores and metrics to MetricStore.
  Endpoints are bare hostnames: the control host uses `<region>.log.aliyuncs.com`
  and no-NAT ECS uses `<region>-intranet.log.aliyuncs.com`. `IndexConfigNotExist`
  means a raw search has no index; it is not proof that no data arrived.
- SecretCrypto ciphertext must start with `enc:v1:` and never equal plaintext.
  The stable master key must survive restart and upgrade.

## Administrator And Secrets

The first username is `admin`; its generated password is handed to the user once
and immediately rotated. Neither manifest nor report is a recovery store. Follow
the product's authenticated administrator recovery procedure and rotate affected
credentials if access is lost. Never extract passwords, AK/SK, JWT material,
master keys, Terraform state, presigned URLs, or executor tokens into chat/logs.

## Domain And TLS

- No domain: temporary `ws://<nlb-address>/ws/executor`.
- Domain without certificate: temporary `ws://<domain>/ws/executor`.
- Domain with certificate: bind a trusted certificate to the NLB path, then
  verify `wss://<domain>/ws/executor` with the actual packaged executor.

DNS and certificate binding can remain pending while base deployment completes.
Plaintext on port 80 is a documented exception, not TLS acceptance. Proxy logs
must exclude query strings because the protocol can carry a query token.

## Upgrades, Scaling, Backup, And Recovery

For an upgrade, build and hash an exact commit, install a new version directory,
start one node, pass health, enable it in NLB, then repeat. Preserve the previous
release and symlink for rolling rollback. Scaling must preserve zone separation
and database/cache constraints; re-plan through Terraform.

Persistent lifecycle enables supported deletion protection and backups. Confirm
actual RDS/Redis backup policy, OSS versioning/lifecycle, SLS retention, and
remote-state protection from live APIs. A backup is not accepted until a restore
procedure and retention owner are known.

## Logs And Diagnosis

Application files are under `/var/lib/autowonder/logs`; service lifecycle is in
the systemd journal. SLS holds system, business, and metrics data. Terraform and
Cloud Assistant provide control-plane evidence. Search only sanitized fields,
and report invocation terminal state, exit code, stdout/stderr classification,
and timeout without exposing full secret-bearing command output.
The manifest records every current `InvokeId` or legacy `InvocationId` at
submission time, so an interrupted run can query `InvokeRecordStatus` (falling
back to `InvocationStatus`) before deciding whether a command may be retried.
Completion still requires the actual `ExitCode` to be zero.

Preflight reports the detected ossutil version and v2/legacy command contract.
Deployment derives endpoint, region, expiry, and force flags from command help.
Signed URLs and cached STS token values are secret material and are never valid
diagnostic output.

## Fast Resume

- Configuration/key/endpoint correction: validate locally, use
  `deploy-via-cloud-assistant.sh --config-only`, then rerun only affected probes.
- Provider download retry: reuse the checksummed deployment-local mirror.
- Schema uncertainty: query table postconditions; never repeat import blindly.
- Acceptance retry: preserve passed checks and execute only pending/degraded gates.
- Final drift: distinguish refresh-only computed changes from a non-empty normal
  plan; release requires the normal plan to be empty.

## Costs And Limits

**Recommendation:** use current plan and price APIs for estimates. The dominant
drivers are HA compute/database/cache, NLB traffic, OSS storage/requests, SLS
ingestion/index/retention, and remote state. V1 supports only new isolated Linux
x86_64 deployments; it does not import/reuse existing resources, mutate DNS,
automate certificate binding, support ARM, replace mandatory OSS, or silently
downgrade HA.
