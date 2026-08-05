# Acceptance, Rollback, And Teardown

## Purpose

Use this reference to classify completion, capture safe evidence, roll back an
application release, or prepare a separately confirmed environment teardown.

## Independent Completion Statuses

Never collapse these into one optimistic result:

| Status | Required evidence |
| --- | --- |
| **Infrastructure ready** | Terraform resources reconcile; two zones, tags, protection, listener CIDRs, private service paths, and scoped RAM policy pass |
| **Application ready** | both systemd services, per-node health, `/checkpreload.htm`, capabilities, and public NLB ingress pass |
| **Business initialized** | `admin` and the requested organization exist; ownership/admin role is verified |
| **Release accepted** | RDS/Redis persistence, OSS server I/O through the intranet endpoint and externally reachable public signed URLs, `enc:v1:` restart use, three SLS destinations, restart/reboot, packaged executor, tags, and secret scan pass |
| **TLS accepted** | trusted certificate, hostname, handshake, and real `wss://` executor pass |

A temporary plaintext endpoint may be Release accepted with TLS pending. It must
not be described as production-ready TLS.

## Evidence Contract

Record deployment ID, exact commit, artifact/runtime/systemd hashes, phase and
step, check name, timestamp, terminal status, exit code, evidence reference, and
rollback boundary. Publish only sanitized summaries. Exclude account UID, live
IP/address, resource IDs, bucket/project names, passwords, AK/SK, STS token,
private key, presigned URL, query token, full ciphertext, and unredacted Cloud
Assistant output.

After `scripts/sanitize-evidence.sh`, fail closed if a forbidden pattern remains.
Never emit a partially sanitized report.

## Release Rollback

1. Stop traffic to the failed node while preserving the healthy node.
2. Stop `autowonder.service` on the failed node.
3. Verify the prior version directory and recorded hash.
4. Atomically repoint `/opt/autowonder/current` to the previous release.
5. Start the service and require local health before NLB re-enable.
6. Repeat only if the second node also requires rollback.
7. Preserve diagnostics under `/var/lib/autowonder/logs` and sanitized invocation
   evidence; remove only unique temporary OSS objects.

Normal rollback never drops schema, deletes RDS/Redis, removes OSS/SLS data, or
destroys Terraform resources. If a database migration is not backward
compatible, stop and follow its separately approved data-recovery plan.

## Failed Initialization

When schema import may have completed, run the read-only postcondition before
any retry. When administrator creation may have completed, query by the expected
identity and reconcile ownership rather than creating another superuser. Keep
the first administrator password only until the one-time user handoff; rotate it
after any uncertain exposure.

Delete private staging objects and local transient secret files after successful
delivery. Preserve service data and logs needed for diagnosis, but scan them for
credentials and query-token material before sharing.

## Persistent Environment Teardown

Teardown is a separate mode even after unattended deployment. Before asking for
destructive confirmation, produce an impact report containing:

- resources selected by Terraform and their `DeploymentId` tags;
- active users/workloads and current health;
- RDS/Redis backup and restore evidence;
- OSS retention/versioning and non-empty-object summary;
- SLS retention/export requirements;
- DNS/certificate actions owned by the user;
- remote-state retention location and credential-rotation list.

Require an explicit confirmation tied to the deployment ID and reviewed destroy
plan hash. Unexpected resources, replacements, missing backups, tag mismatches,
or ambiguous ownership are stop conditions. Do not use broad manual deletion as
a fallback.

## Temporary Environment Teardown

Temporary lifecycle makes resources destroyable but does not waive the teardown
gate. Clean unique application/acceptance objects, capture final sanitized
evidence, generate a Terraform destroy plan with
`scripts/terraform-stage.sh destroy-plan`, review it, and obtain independent
confirmation. Retain or destroy remote state only according to the initial
choice. Revoke the application AccessKey after dependent services are stopped.

## Post-Rollback Or Teardown Verification

For rollback, confirm both nodes, NLB, data access, executor connectivity, and
secret-log scan again. For teardown, verify the reviewed resources are absent,
no chargeable orphan remains, credentials are revoked, DNS guidance is complete,
and retained backups/state have a named owner and expiration. Report failures
and pending items rather than forcing a green status.
