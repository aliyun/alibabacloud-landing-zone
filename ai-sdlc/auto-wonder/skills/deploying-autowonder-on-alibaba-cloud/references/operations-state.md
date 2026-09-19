# Cloud operations state

## Ownership and storage

Use a dedicated private `aw-ops-<deploymentId>-<identityHash>` bucket in the
deployment region. Its identity binds account, region, and deployment. Do not
reuse business buckets or the existing Terraform state bucket, and do not grant
the application RAM user access. `identity.json` makes it discoverable without
local metadata. `current.json` is the single complete-state commit point.

`deploy/` stores deployment records and content-addressed recovery files;
`upgrade/` stores upgrade records and sealed release files. Identical content
within each scope is reused. Snapshots retain history without OSS bucket
versioning: OSS ignores `x-oss-forbid-overwrite` on Enabled **and Suspended**
versioning buckets. The helper rejects either setting rather than weakening its
write lock. It explicitly uploads private AES256-encrypted objects.

Checkpoint writes reuse objects referenced by the previous verified record,
checking their existence and length without downloading unchanged release bodies.
New objects and every restore receive full SHA-256 verification. Same-size
external corruption is detected on restore, not by the lightweight checkpoint.

Only required recovery files are collected: actual Terraform configuration,
local module closure, inputs, dependency lock, original infrastructure secrets,
application and candidate environments, and declared sealed release artifacts.
No provider cache, build cache, ordinary logs, reviewed binary Terraform plan,
CLI profile, STS token, or signed URL is archived. Terraform/application runtime
credentials are necessary recovery data and remain protected object contents,
never command-line arguments or manifest values. Existing remote state stays at
its original backend; its coordinates are recovered without transient credentials.

## Resolve on any control host

Authenticate the `auto-wonder` profile using the platform bootstrap first. Then
invoke the deployment Skill's shared Python command (`python` on Windows):

```text
python3 scripts/operations-store.py resolve --project-root <workspace>
```

The upgrade Skill's paired resolve entrypoints call the same command. Optional
`--manifest`, `--region`, and `--deployment-id` narrow identity, never override
cloud content. Without a region it checks the four supported deployment regions.
Multiple cloud deployments require an explicit selection. Operations storage
uses ossutil v2, standalone or embedded in `aliyun ossutil`; legacy OSS transfers
can still be used by existing release scripts, but are not sufficient here.

Only a confirmed missing cloud record allows the historical resolver: existing
upgrade index, current deployment manifest, or an explicitly named top-level
deployment directory. Preserve the newer upgrade working state and merge only
missing original deployment configuration; do not reset `upgrade={}`. Before
initial publication, verify live ownership and active release hashes. Missing
original files or conflicting identities block import. A 403, timeout, invalid
JSON, or missing referenced object never triggers local fallback.

After uploading and verifying all referenced objects, publish `current.json`
last and restore into a fresh private `.operations-cache/` directory. Generate
discovery/index files from the restored state without replacing its execution
checkpoints. The returned working manifest points only at recovered files.
Original directories are not deleted. They are no longer required after a
complete import and successful restore. Treat both the cache and local working
manifests as private data outside Git.

## New deployments and local Terraform state

Backend preparation initializes an incomplete operations snapshot before the
first infrastructure apply. An incomplete snapshot returns
`deployment-resume-required`; resume its installation instead of registering an
upgrade. Checkpoints promote the snapshot only after required recovery inputs
and a sealed release exist. Missing already-recorded passwords/keys must never
be regenerated.

For historical default-workspace local state, first preserve its original bytes
and migration intent in OSS, then migrate the Terraform backend to
`deploy/terraform-state/<deploymentId>/terraform.tfstate` in the new operations
bucket. Compare lineage, serial and resource identities before marking it ready.
Existing remote state is not migrated. Non-default workspaces or conflicting
backend definitions require a separately reviewed migration; never silently
omit their state. An uncertain migration is not automatically retried.

## Checkpoints and failures

Shared Bash, PowerShell and Python manifest writers check the current revision
and write through to OSS. Database mutation flags and remote submission intent
must commit before submission. Persist the returned invocation ID and each
node result. A stale cache or failed checkpoint stops further changes; reload
cloud state and reconcile actual execution before resuming.

`write-lock.json` is created atomically and never automatically stolen. Normal
completion/failure removes only the caller's own lock. After a hard process
termination, verify the original writer has stopped and reconcile its cloud
invocations before explicitly removing the abandoned lock. Do not delete a lock
merely because it is old. Known invocation IDs permit read-only status checks;
unknown submissions require explicit reconciliation before another RunCommand.

Terraform plans are rebuilt and reviewed after recovery. Existing upgrade
approval is reusable only while its plan, resource identity, and content hashes
still match; live target/backup validation must still run. Database migration
running/failed flags survive recovery and continue blocking unsafe rollback.

No automatic operations-bucket deletion is provided. Existing deployment teardown
does not authorize deleting its recovery history. A separate explicit teardown
decision is required, especially if historical local Terraform state now lives
in the operations bucket.

The manifest also carries resourceSelection (version 1): effective policy, selected coordinates, normalized evidence, checks and hashes. It uses the same revision-checked checkpoint and current.json commit. No new bucket or storage protocol is introduced. Restore requires no copy of an old deployments directory; historical stock is not current purchase evidence. A missing resourceSelection on a completed historical deployment does not invalidate its daily operations. Binary plans still require regeneration after restore.
