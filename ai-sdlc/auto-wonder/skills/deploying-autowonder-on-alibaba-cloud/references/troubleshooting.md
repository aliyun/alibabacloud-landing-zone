# Troubleshooting

## Purpose

Use this reference after a failed check. For each case, collect sanitized
evidence, apply the narrow fix, and resume at the stated idempotent boundary.
Never broaden permissions or expose credentials to make progress.

## Automatic Backend Mismatch

If the derived bucket, account, region, ACL, tags, state key, or fixed
`backend.hcl` path differs from the manifest, stop. Do not ask for a replacement
path, add a guessed suffix, adopt the bucket, or fall back to local state.

## Known Failure Patterns

### Active Alibaba Cloud Profile Cannot Refresh

**Symptom:** identity preflight fails with a credential refresh HTTP error.
**Cause:** the active local profile is stale while another existing profile is
valid. **Safe fix:** verify account identity read-only and rerun preflight with
`--profile <verified-name>`. **Unsafe:** rewriting global credentials or selecting
a profile without matching account identity. **Resume:** preflight.

### Terraform Provider Distribution Is Unreachable

**Symptom:** `terraform init` resolves the version but cannot download the release
asset. **Safe fix:** download the official provider archive, verify it against the
official checksum list, and use a deployment-local filesystem mirror. **Unsafe:**
disabling checksums or changing system DNS. **Resume:** Terraform init and plan.

### Cloud Assistant Invocation Contract Differs

**Symptom:** submission succeeds but no invocation ID is recorded, or the remote
shell exits `126` with Base64 text. **Cause:** old scripts expected
`InvocationId`, pre-encoded `CommandContent`, and polled `InvocationStatus`.
**Safe fix:** pass raw command content; checkpoint current `InvokeId` or legacy
`InvocationId` immediately; poll with `--InvokeId`; inspect current
`InvokeRecordStatus` or legacy `InvocationStatus`; and require the actual
`ExitCode` to be zero. Reconcile the stored invocation before retry. **Resume:**
the first unproven host postcondition.

### Authenticated Endpoint Produces A False Startup Failure

**Symptom:** deployment reports unhealthy while the service and public UI are
available; `/api/health` returns 401. **Cause:** an authenticated business API
was used as a startup probe. **Safe fix:** require active systemd state, a port
7001 listener, `success` from `/checkpreload.htm`, and HTTP success from
`/api/platform/branding/public`. **Unsafe:** adding credentials to a load
balancer health check. **Resume:** rolling start or acceptance.

### Control Host Cannot Reach OSS Intranet Endpoint

**Symptom:** staging upload fails before any ECS mutation. **Safe fix:** upload
and delete with the regional public OSS endpoint, but presign downloads against
the VPC intranet endpoint. Detect whether installed ossutil provides `presign`
or legacy `sign`. **Unsafe:** giving ECS public egress. **Resume:** staging.

### Application OSS Endpoints Are Not Split

**Symptom:** server-side object I/O fails on a no-egress ECS, or task packages,
work-item artifacts, and requirement downloads contain an unreachable
`-internal` hostname. **Cause:** one endpoint was used for both server data-plane
traffic and externally consumed signed links. **Safe fix:** set `OSS_ENDPOINT`
to `https://oss-<region>-internal.aliyuncs.com` and `OSS_PUBLIC_ENDPOINT` to
`https://oss-<region>.aliyuncs.com`, run `runtime-config`, deploy with
`--config-only`, and rolling restart. Both clients must use the same region,
bucket, and credential scope. Never replace the hostname after signing because
the endpoint participates in signature construction. **Resume:** OSS application
acceptance.

### Required Environment Key Is Empty

**Symptom:** the env file contains a required key but runtime authentication later
fails. **Safe fix:** regenerate from the exact Terraform variable name and run
`runtime-config`; it rejects empty values before upload. Use `--config-only`
after correction. **Resume:** environment synchronization.

### Upgrade Plan Reports Shell Locals As Missing Environment

**Symptom:** `plan-upgrade.sh` blocks on names such as `IFS`, `LC_ALL`,
`SCRIPT_DIR`, `TEMP_FILES`, or `TEMP_DIRS`. **Cause:** an old planner treated
uppercase Shell assignments as application environment declarations. **Safe
fix:** use the source-aware planner that reads `KEY=...` only from
`application.env.example` and reads only explicit `${KEY...}` references from
scripts and other configuration sources, then regenerate the plan. **Unsafe:**
adding these Shell-local names to `/etc/autowonder/autowonder.env` or manually
deleting the blocked reasons. **Resume:** upgrade planning and risk review.

### SecretCrypto Key Contains A Line Break

**Symptom:** Java rejects the master key although a permissive local decoder
accepts it. **Safe fix:** remove CR/LF before shell quoting and require exactly
44 Base64 characters decoding to 32 bytes. **Resume:** configuration-only sync
and rolling start; replace a key only before encrypted data exists.

### Template Seed Reports Invalid Encoding In String

**Symptom:** importing `autowonder-community-templates.sql` fails in a JSON
function with `Invalid encoding in string`. **Cause:** a JSON escape such as
`\n` or `\"` was stored as a single backslash in a MySQL string literal, so the
SQL parser consumed it before JSON parsing. **Safe fix:** use the corrected seed
whose JSON backslashes are doubled and let it scope SQL mode changes to its own
session. Resume the idempotent template import and postcheck. **Unsafe:** changing
global `sql_mode`, editing JSON content on the server, or marking the template
checkpoint complete without four valid templates.

### Private ECS Cannot Send SLS Data

**Symptom:** health is green but SLS producer requests time out. **Cause:** a
no-NAT ECS was given the public endpoint. **Safe fix:** runtime uses
`<region>-intranet.log.aliyuncs.com`; control-host queries use the public bare
hostname. **Resume:** configuration-only sync, rolling restart, SLS acceptance.

### Manifest Rejects Its Own Status Metadata

**Symptom:** a later phase reports a forbidden secret key after runtime or
acceptance metadata was written. **Safe fix:** reject exact value-bearing secret
fields while allowing file-mode and secret-log-scan statuses. **Resume:** the
blocked phase.

### Terraform Refresh Shows Only Computed Drift

**Symptom:** refresh-only reports provider-computed ECS fields or normalized
backup ordering, while a following normal plan is empty. **Safe fix:** retain the
refresh evidence and require the normal plan to report no changes. **Unsafe:**
treating refresh-only noise as a required mutation. **Resume:** plan review.

### Region-Implicit API Uses The Wrong Endpoint

**Symptom:** an existing RDS or Redis instance appears missing. **Cause:** the API
selects its endpoint from CLI global configuration. **Evidence:** sanitized CLI
region and request ID. **Safe fix:** export the region and pass the global region
flag. **Unsafe:** retry across regions. **Resume:** failed read-only inventory.

### SLS Endpoint Contains A Scheme

**Symptom:** DNS contains a malformed project hostname. **Cause:** the plugin
expects a bare hostname. **Evidence:** redacted configured endpoint. **Safe fix:**
remove the URL scheme. **Unsafe:** disabling TLS validation. **Resume:** SLS probe.

### Java 21 Is Missing

**Symptom:** the OS repository has no Java 21 package. **Cause:** image repository
inventory. **Evidence:** architecture and package query. **Safe fix:** transfer a
pinned, checksummed Temurin Linux amd64 runtime via private OSS. **Unsafe:** an
unverified download or replacing system Java. **Resume:** runtime installation.

### MySQL Or Redis Client Is Missing

**Symptom:** database or persistence acceptance cannot start on the base image.
**Safe fix:** let the idempotent host bootstrap install the distribution client
packages before database initialization. **Unsafe:** running an unverified
binary from a public URL. **Resume:** host bootstrap.

### OSS Wrapper Injects Cached STS

**Symptom:** a signed URL unexpectedly contains a security-token parameter.
**Cause:** the `aliyun ossutil` wrapper injected cached credentials. **Evidence:**
query parameter names only. **Safe fix:** invoke the standalone binary, run the
version/subcommand preflight, and confirm the STS source is intentional without
printing its value. **Unsafe:** printing or reusing the URL. **Resume:** presign
and transfer.

### Ossutil Flags Differ

**Symptom:** `presign` rejects method or expiry flags. **Cause:** installed CLI
syntax differs from old examples. **Evidence:** version and sanitized help.
**Safe fix:** probe version plus `cp`, `rm`, `presign`, and legacy `sign` help,
then use the detected endpoint, region, duration/timeout, and force flags through
the compatibility wrapper. **Unsafe:** assuming parameters from another major
version or installing an unknown binary during deployment. Validate only URL
shape and success; never print the signed query. **Resume:** presign only.

### Environment Values Retain JSON Quotes

**Symptom:** correct credentials fail authentication. **Cause:** JSON-rendered
shell quoting became literal data. **Evidence:** key presence and value lengths,
never values. **Safe fix:** regenerate with `jq -Rrs @sh`, source a protected test
copy, and compare hashes/lengths. **Unsafe:** echoing secrets. **Resume:** env-file
installation and affected connection probe.

### Schema Import Reports A Tail Failure

**Symptom:** DDL may have succeeded but the invocation failed in validation.
**Cause:** import and postcondition were coupled. **Evidence:** read-only table
inventory. **Safe fix:** run the independent idempotent postcondition; import only
if the database is still empty. **Unsafe:** rerunning DDL blindly. **Resume:**
schema postcondition.

### SLS Query Has No Index

**Symptom:** raw query returns `IndexConfigNotExist`. **Cause:** the Logstore has
no index. **Evidence:** index metadata and shard cursors. **Safe fix:** for newly
created stores repair Terraform; for externally supplied stores record cursor
advance as degraded evidence. **Unsafe:** modifying customer logging merely to
pass a check. **Resume:** SLS acceptance.

### Rule Listing And Reachability Disagree

**Symptom:** security-group inspection and real traffic disagree. **Cause:** rule
composition or another network layer. **Evidence:** control-plane inventory plus
exact data-plane HTTP/TCP probes. **Safe fix:** trace the narrow path. **Unsafe:**
open `0.0.0.0/0` or SSH. **Resume:** network acceptance.

### Generated Shell Shadows A Reserved Name

**Symptom:** commands previously available become not found. **Cause:** a loop
variable replaced `PATH`. **Evidence:** sanitized generated script. **Safe fix:**
rename it, for example `JSON_PATH`, run `bash -n`, and rerun the complete check.
**Unsafe:** hard-code a permissive search path. **Resume:** failed read-only scan.

### Real Executor Fails While Simple Probes Pass

**Symptom:** browser/curl connects but the packaged daemon cannot reach the ALB.
**Cause:** endpoint policy may classify the daemon or destination port. **Evidence:**
actual daemon result on both direct ports without its token. **Safe fix:** use the
ALB endpoint on port 80 and require trusted `wss://` for production. **Unsafe:**
claim success from a substitute client. **Resume:** executor acceptance.

### Copy Buttons Fail On A Plaintext Endpoint

**Symptom:** executor token or startup-command copy throws because
`navigator.clipboard` is undefined. **Cause:** browsers restrict the Clipboard
API outside trusted HTTPS or localhost, while the temporary IP/domain scenarios
use plaintext HTTP. **Safe fix:** deploy a release with the legacy/manual copy
fallback and complete trusted TLS for production. **Unsafe:** require users to
disable browser security controls. **Resume:** application rollout and UI check.

### Proxy Logs Capture A Query Token

**Symptom:** executor authentication appears in access logs. **Cause:** the
request URI includes a query token. **Evidence:** boolean secret scan only.
**Safe fix:** use a log format excluding query strings and authorization, clear
temporary exposed logs under retention policy, and rotate the token. **Unsafe:**
publishing the matching line. **Resume:** secret-log acceptance.

### OSS Bucket Name Collides

**Symptom:** create reports an existing globally owned name. **Cause:** OSS names
are global. **Evidence:** attempted sanitized prefix and ownership result.
**Safe fix:** regenerate the suffix from deployment identity. **Unsafe:** reuse an
unowned bucket. **Resume:** Terraform plan.

### Regional Inventory Or Provider Schema Mismatch

**Symptom:** a SKU or field validates locally but fails in the chosen region or
provider. **Cause:** inventory drift or guessed schema. **Evidence:** provider
schema, zone inventory, quota, and price queries. **Safe fix:** resolve a supported
equivalent that preserves topology and re-plan. **Unsafe:** downgrade HA silently
or bypass validation globally. **Resume:** preflight and plan review.

### Sensitive Terraform Output Still Exists In State

**Symptom:** a value hidden in CLI output is visible to state readers. **Cause:**
`sensitive = true` is display control, not encryption. **Evidence:** state access
policy, never state contents in logs. **Safe fix:** protect/rotate credentials and
minimize stored outputs. **Unsafe:** printing state for diagnosis. **Resume:**
credential rotation and plan.
