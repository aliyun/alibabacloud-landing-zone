# Cross-platform runtime and verification

Each Skill includes its own required scripts, modules, templates and references.
It can be distributed and run without the other Skill installed. There are no
cross-Skill imports, symlinks or runtime copies. Only the disposable downloaded
tools cache at `skills/.autowonder-tools` is shared. Deployment and upgrade
exchange recovery data through the versioned OSS state protocol, not code paths.
Resolve scripts from their own real source location. Installed discovery
symlinks may point to that self-contained Skill directory.

## Starting a control-host session

- macOS/Linux: `bash scripts/bootstrap-control-host.sh`.
- PowerShell 5.1/7: `scripts/windows/bootstrap-control-host.ps1`.
- CMD: `scripts/bootstrap-control-host.cmd` with PowerShell-style parameters.
- Git Bash: the `.sh` bootstrap detects Windows and forwards to native
  PowerShell before downloading tools or interpreting deployment paths.
- WSL: use the Linux entrypoint and Linux tools throughout that session.

The bootstrap checks the existing cloud identity as before. To initialize only
local dependencies, source `scripts/runtime-env.sh` and call
`autowonder_runtime_environment` in Bash or zsh (the helper resolves its own
path in either shell); on Windows run the Python-only
`scripts/windows/runtime-bootstrap.ps1`, then use its returned interpreter to
run `scripts/tool_runtime.py --emit-env --project-root <checkout>`.

Bootstrap returns a `runtimeEnvironment` object. Apply those exact environment
values to subsequent child processes, including approval, build and recovery
commands. Do not assume environment changes in a completed child shell persist
in the agent's next tool call. Never execute the JSON as shell code. A new
session may resolve cached tools again; do not persist a second environment
file that can outlive its checkout. Historical phase entrypoints remain valid
when invoked with this prepared environment.

## Dependency policy

`skills/.autowonder-tools/<tool>/<version>/<os>-<arch>/` contains disposable
dependencies only. It is ignored by Git and excluded from upgrade content
identity and OSS recovery collection. Never put manifests, credentials,
Terraform state or required recovery files there. Do not package this sibling
cache when distributing Skills. Deleting it requires dependencies to be
downloaded again, but does not delete deployment state.

Python always uses the fixed private runtime. JDK, Maven, Terraform, Alibaba
Cloud CLI, ossutil and jq reuse a compatible private cache or compatible system
installation before downloading. Java must be JDK 21 with its matching javac;
Maven must be 3.9.9 or later in the 3.x series; ossutil must be 2.x. No Homebrew,
winget, administrator install or persistent machine PATH modification is used.
Git and basic OS tools used for cold start (HTTPS download, SHA256 and tar;
PowerShell/.NET on Windows) are prerequisites. Missing OS primitives cause an
explicit preflight failure rather than an unreviewed global installation.

`scripts/runtime-lock.tsv` contains publisher URLs and SHA256 (SHA512 for
Maven) only, never software packages. Downloaded bytes must match the lock
before extraction or execution. Installation uses a temporary directory and
an exclusive lock, then activates the validated runtime. Successful installs
remove their archives. A stale lock requires checking that its installer has
stopped before deleting that exact lock; never remove another live lock.

Cache checks cover the entry executable plus required modules/features and
versions, not an anti-tampering guarantee for every file of a local runtime.
The cache is trusted local software, like a reused system installation.

Current download coverage: macOS and glibc Linux x86_64/arm64; Windows x86_64.
Windows arm64 has Python/JDK/Maven/jq assets, but Terraform/aliyun/ossutil have
no reviewed arm64 asset in this lock and require compatible existing tools.
Do not substitute an unverified architecture. musl Linux is outside the
portable Python lock's supported runtime. Native Windows and Linux execution
must be validated on those systems before claiming that release's coverage.

## Shared behavior and speed

- IP discovery uses `public_ip.py`. When automatic discovery is selected, use
  all returned `publicSourceCidrs` without confirmation, including single-source
  results and multiple different addresses. Only no valid result requires retry
  or manual input. It never updates an existing ingress ACL.
- `release_build.py` performs the same Maven `clean package -Dmaven.test.skip=true`, JAR frontend and
  archive checks on every platform. Both migration archives extract directly
  into the remote migration directory. Build duration is diagnostic output,
  separate from approval fingerprints.
- `cloud_assistant.py` shares response parsing and unresolved-operation gates.
  Existing platform submission/checkpoint loops retain write-ahead intent and
  invocation IDs. Invalid output or unknown completion cannot authorize a
  second submission. Remote Linux payloads still run on ECS, not Windows.
- Reuse verified tools and the existing Maven/provider caches. Existing OSS
  content-addressed storage already avoids re-uploading unchanged objects;
  retain its live revision checks. Never cache account authorization or skip
  approved-plan, backup, migration, or activation checks for speed.

## Local verification

From the project root, run `python scripts/verify-cloud-skills.py`. It runs
fixture suites in separate child processes with cloud CLI deny stubs, reports
test IDs/counts/durations, and does not execute a deployment. On macOS an
additional OS network boundary can wrap it:

```bash
sandbox-exec -p '(version 1)(allow default)(deny network*)' \
  python3 scripts/verify-cloud-skills.py
```

Use `--strict` to make any skipped test an incomplete result. A normal
`passed-with-skips` result is not proof of three-platform coverage. Review the
skipped identifiers and run applicable native fixtures on their real OS.
In particular, Windows needs PowerShell 5.1 and 7, CMD and Git Bash entry
checks, no-system-Python cold start, Chinese/space paths, and read-only artifact
replacement. A Python mock of Windows subprocess arguments is not this proof.

Cloud end-to-end verification remains separate: use the dedicated validation
Skill with its approved target directory and round limit, record resources
created by that run, and clean them after verification. Local unit checks do
not create cloud resources and cannot claim cloud end-to-end acceptance.
