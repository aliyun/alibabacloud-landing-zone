# Acceptance, Rollback, And Teardown

## Purpose

Use this reference to classify completion, capture safe evidence, roll back an
application release, or prepare a separately confirmed environment teardown.

Teardown first applies the exact reviewed main destroy plan and verifies an
empty managed state. It then deletes every state object version and multipart
upload, deletes the dedicated OSS state bucket, and removes the local backend
directory and the dedicated operations bucket. Never retain these recovery
buckets after successful teardown or delete them while main destruction is
failed or uncertain.

## Independent Completion Statuses

Never collapse these into one optimistic result:

| Status | Required evidence |
| --- | --- |
| **Infrastructure ready** | Terraform resources reconcile; two zones, tags, protection, listener CIDRs, private service paths, and scoped RAM policy pass |
| **Application ready** | both systemd services, per-node health, `/checkpreload.htm`, capabilities, and public ALB ingress pass |
| **Business initialized** | initial `admin` exists and protected credentials are ready for handoff; no organization is created |
| **Release accepted — script scope** | Both distinct ALB public IPv4 addresses return `success` from `/checkpreload.htm`; the deployment script records `status=accepted` |
| **Extended acceptance — optional separate scope** | RDS/Redis persistence, OSS server I/O through the intranet endpoint and externally reachable public signed URLs, `enc:v1:` restart use, three SLS destinations, restart/reboot, packaged executor, tags, and secret scan pass |
| **TLS accepted** | trusted certificate, hostname, handshake, and real `wss://` executor pass |

A temporary plaintext endpoint may pass script acceptance with TLS pending. It
must not be described as production-ready TLS or as passing extended acceptance.
The current deployment-scoped entrypoint exits after its ALB probes;
`--acceptance-evidence` and `AUTOWONDER_RUNTIME_PROBE` are not reached and have no
effect. It preserves earlier deep-check fields without revalidating them. When
extended acceptance is requested, retain independent evidence for all ten checks
above and report pending/failed items separately. TLS also requires independent
verification; neither result is implied by the script status.

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

For an upgrade, first check `.upgrade.databaseMigration` and the recorded
application rollback compatibility. Restore the previous
`autowonder.env.previous` only with the matching previous release. A completed
database migration is never automatically reversed; when the previous
application cannot run against the new schema, use the approved database restore
or forward-fix plan instead of repointing the symlink.

1. Stop traffic to the failed node while preserving the healthy node.
2. Stop `autowonder.service` on the failed node.
3. Verify the prior version directory and recorded hash.
4. Atomically repoint `/opt/autowonder/current` to the previous release.
5. Start the service and require local health before ALB re-enable.
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

Require explicit teardown authorization for the deployment ID and apply only the
reviewed destroy plan hash. Reuse authorization already covering the exact
deployment and cleanup; do not request the same permission again. Unexpected
resources, replacements, missing backups, tag mismatches, or ambiguous ownership
are stop conditions. Do not use broad manual deletion as a fallback.

Before `destroy-plan`, run
`bash scripts/prepare-teardown.sh --manifest FILE --confirmation-file FILE`,
with `DESTROY <deploymentId>` in the confirmation file. The script reviews and
applies only the required deletion-protection/retention changes and checks their
postconditions; production defaults remain unchanged. It does not perform
subscription refunds. Complete exact-resource BSS unsubscription for prepaid
ECS/RDS/Redis before the main destroy, verifying each outcome instead of assuming
that preparation releases a subscription. Then generate and review the main
`destroy-plan` and apply its exact fingerprint with `destroy-apply`. Only after
main destruction is verified does backend cleanup automatically remove all
tfstate versions, the state bucket, and the dedicated operations bucket.

For `bssopenapi RefundInstance`, pass the deployment region explicitly through
`--region`, the exact instance/product identity returned by
`QueryAvailableInstances`, and the product type (including an explicit empty
string for ECS). Persist a unique `--ClientToken` before each request and reuse
it for an uncertain retry. An ECS `ResourceNotExists` response from a different
region does not prove release: query the exact ECS IDs in the deployment region.
After successful refund responses, verify that all selected subscription instances
are absent before planning the remaining Terraform destroy.

Before subscription refunds or the main destroy plan, run the explicitly confirmed
protection preparation:

```bash
bash scripts/prepare-teardown.sh --manifest FILE --confirmation-file FILE
```

The confirmation file must contain `DESTROY <deploymentId>`. The helper applies
only the reviewed protection/retention plan and verifies actual refreshed values.
An unchanged original RDS `backup_retention_period` above the requested seven days
is reported as a retention exception only when it is the sole remaining difference;
RDS/Redis release protections, OSS cleanup settings, and `released_keep_policy=None`
must already satisfy their postconditions.

If preparation remains `pending`, retain its saved plan and reconcile instead of
reapplying or manually clearing the checkpoint:

```bash
bash scripts/prepare-teardown.sh --manifest FILE --confirmation-file FILE --reconcile
```

Reconciliation checks the recorded plan fingerprint and original mutation scope,
then creates a fresh targeted read-only plan. It never runs apply. Only a verified
postcondition records completion, both plan fingerprints, and any retained backup
period. Any other mismatch leaves the operation pending.

### Released RDS database state reconciliation

After protection preparation, refund the exact subscription instances and verify
that the RDS parent is absent. Before generating the normal destroy plan, run:

```bash
bash scripts/reconcile-released-rds-database.sh --manifest FILE --confirmation-file FILE
```

This narrowly handles the provider's `alicloud_db_database.app` refresh failure
when `DescribeDatabases` cannot find a released RDS parent. It independently
requires the exact account, region, backend and RDS identity, an unambiguous
`DescribeDBInstanceAttribute` instance-not-found response, and the database's
exact state identity. It does not require first provoking the known refresh
failure. If the state entry is already absent without a repair checkpoint, it
reports `not-required` without changing state.

The helper backs up the state privately, checkpoints `pending`, and removes only
that verified database address with normal backend locking. All other state must
remain unchanged. A repeated invocation with `pending` never repeats removal:
it completes only if the database entry is absent and the protected backup proves
that the remaining state is unchanged. An existing instance, uncertain API result,
identity mismatch, changed state, or missing/corrupt backup stops reconciliation.
Keep the backup and pending record until the outcome is resolved. Then generate
and review a fresh normal destroy plan; never disable refresh or generalize this
exception to other resources.

## Temporary Environment Teardown

Temporary lifecycle makes resources destroyable but does not waive the teardown
gate. Clean unique application/acceptance objects, capture final sanitized
evidence, generate a Terraform destroy plan with
`scripts/terraform-stage.sh destroy-plan`, review it, and obtain independent
authorization for this deployment. Follow the same preparation and prepaid
unsubscription sequence above where applicable. After verified main destruction,
automatically remove both dedicated recovery buckets. Revoke the application
AccessKey after dependent services are stopped.

## Post-Rollback Or Teardown Verification

For rollback, confirm both nodes, ALB, data access, executor connectivity, and
secret-log scan again. For teardown, verify the reviewed resources are absent,
no chargeable orphan remains, credentials are revoked, DNS guidance is complete,
and retained backups/state have a named owner and expiration. Report failures
and pending items rather than forcing a green status.
