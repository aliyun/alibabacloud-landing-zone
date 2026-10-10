# Community end-to-end startup harness

Proves that a **brand-new** community install actually works: the schema imports
into an empty volume, the service starts from the build artefact, the
community-specific defaults are live in real responses, and a real user can walk
register → login → workspace → this release's new endpoints.

Unit tests being green does not mean the application starts. This directory
exists because that gap can only be closed by running the thing.

Agents and release verification use `verify.sh` as the stable public interface.
The lower-level scripts remain implementation details and focused developer
diagnostics. See [AGENT_GUIDE.md](AGENT_GUIDE.md) for the complete Agent contract.

## Requirements

The supported verification hosts are macOS and CentOS. `--start` probes every
capability and, by default, installs or starts what is missing:

- macOS: Homebrew packages for Git, curl, Python 3, OpenSSL, JDK 21 and Maven;
  Docker Desktop is installed as a cask and opened when its daemon is stopped;
- CentOS: `dnf` is preferred and `yum` is the fallback; ordinary build tools and
  JDK 21 are installed from RPM packages. If the distribution does not provide
  OpenJDK 21, the script uses the official Eclipse Adoptium RPM repository and
  Temurin 21. Docker CE, Compose v2 and Buildx use Docker's official CentOS
  repository and `systemctl enable --now docker`;
- CentOS installation requires root or already-authorized non-interactive sudo.

The script itself must initially be runnable by Bash. A supported package manager
and network access to its repositories and container registries are also
required. Use `--no-install` for a read-only prerequisite check: missing or
stopped dependencies then fail immediately with the recovery command.

The harness never touches a host MySQL, Redis or object store. Every dependency
runs in a container on its own network with its own volumes, so it can run beside
anything else on the machine and can be run repeatedly.

MySQL, Redis, MinIO and the MinIO client are image references declared by the
repository's Compose/E2E configuration; Docker pulls them when they are absent.
They are not embedded binary archives. The community application image is always
built from the exact `--project-root` being tested.

## Quick start

```bash
./e2e-tests/verify.sh --start --project-root "$(pwd -P)" --mode image --keep-on-failure
# Perform task-specific browser/API exploration.
./e2e-tests/verify.sh --check --project-root "$(pwd -P)"
./e2e-tests/verify.sh --clean-up --project-root "$(pwd -P)"
```

The project root identifies the current run. It may be any pending-merge
worktree; no branch switch or merge is required. The command prints every log
path before starting long work, selects free host ports for all five services,
and returns with the verified stack running for Agent exploration.

Long work is framed by stable records such as `LIFECYCLE|START`,
`STEP|BUILD|START`, `STEP|DOCKER_DAEMON|WAITING`, and exactly one final
`LIFECYCLE|END|PASS` or `LIFECYCLE|END|FAIL`. `BOOTSTRAP_LOG` is printed and the
file is created before host probing starts.

## Knobs

Every value below is an environment variable with a working default. Override by
exporting it before running a script.

| Variable | Default | Meaning |
| --- | --- | --- |
| `AW_E2E_PROJECT` | `aw-e2e` | compose project; also names volumes, network and state dir |
| `AW_E2E_NETWORK` | `<project>-net` | compose network name |
| `AW_E2E_STATE_DIR` | `$TMPDIR/aw-e2e-<project>` | run state, secrets, reports |
| `AW_E2E_MYSQL_PORT` | `33060` | host port, moved automatically if busy |
| `AW_E2E_REDIS_PORT` | `63790` | host port, moved automatically if busy |
| `AW_E2E_MINIO_PORT` | `9000` | S3 API port |
| `AW_E2E_MINIO_CONSOLE_PORT` | `9001` | MinIO console |
| `AW_E2E_APP_PORT` | `7001` | the service's own port |
| `AW_E2E_MYSQL_IMAGE` | `mysql:8.0` | |
| `AW_E2E_REDIS_IMAGE` | `redis:7-alpine` | |
| `AW_E2E_MINIO_IMAGE` | `quay.io/minio/minio:latest` | |
| `AW_E2E_OFFICIAL_IMAGE_MIRROR` | `public.ecr.aws/docker/library` | fallback for Docker Official Images |
| `AW_E2E_BUCKET` | `aw-e2e-autowonder` | object storage bucket |
| `AW_E2E_JAR` | `target/auto-wonder.jar` | artefact under test |
| `AW_E2E_SMOKE_STOP` | unset | set to `1` to stop the service when `smoke.sh` ends |
| `AW_BOOTSTRAP_DOCKER_TIMEOUT` | `180` | seconds to wait for Docker Desktop |

## What is deliberately not modified

`docs/community/docker-compose.dependencies.yml` is the artefact under test and
is used **byte-identically**. Port changes, the MinIO service and the per-run
network name all come from an additional compose layer, `compose.e2e.yml`, passed
as a second `-f`. `up.sh` prints `compose base mode: verbatim` when it managed to
use the community file unmodified, which is the normal case.

## Machine-independence notes

These are the things that break when the harness is moved to another machine, and
how each is handled.

**Registry egress.** `registry-1.docker.io` is unreachable on some networks. Each
Docker Official Image is therefore pulled from `public.ecr.aws/docker/library/...`
as a fallback and retagged locally. That is a public AWS endpoint, not an internal
one. MinIO comes from `quay.io`, which is reachable where Docker Hub is not.

**Architecture pins.** The community compose file pins `platform: linux/amd64` for
MySQL and Redis. On arm64 hosts compose honours that pin strictly and
re-consults the registry for a locally present image whose tag reports the wrong
architecture — which then fails when Docker Hub is unreachable. So `up.sh`
materialises each pinned platform and *proves* it runs (`docker run --platform …
uname -m`) before telling compose `pull_policy: never` for the rest of the run.
Under the containerd image store `docker image inspect` reports the manifest
index, so its `Architecture` field is not decisive; only actually running the
image is.

**Credential helper.** Docker Desktop sets `credsStore: desktop` in
`~/.docker/config.json`, and `docker-credential-desktop` is often absent from a
non-interactive `PATH`. Every pull then fails with "error getting credentials".
`aw_e2e_fix_credential_helper` reads that key and re-adds the helper directory.

**Port collisions.** If MySQL `33060`, Redis `63790`, MinIO `9000`/`9001`, or
Spring Boot `7001` is already taken, the harness picks a free host port and
records the complete set in `runtime.json` and `resolved.env`. Later lifecycle
commands replay that resolution rather than probing again.

**Host bootstrap.** Reused and installed capability versions are recorded in
`bootstrap-state.tsv`; installation and daemon diagnostics go to
`logs/bootstrap.log`. Neither file contains application credentials. A failure
reports a stable kind such as `INSTALL_DISABLED`, `INSTALL_FAILED`,
`SUDO_UNAVAILABLE`, or `DOCKER_START_TIMEOUT` and preserves the log path.

**Exported variables.** Compose reads the process *environment*, not the shell's
variable table. Every knob `compose.e2e.yml` interpolates is therefore exported in
`lib/common.sh`; an unexported one makes compose fall back to its own default
while the scripts keep using the real value, and the two then disagree about the
network name.

## Object storage

The service cannot start without a bucket: `taskPackager(...)` is wired
unconditionally. There is no OSS locally, so MinIO stands in for it through the
`S3ObjectStorage` backend, which the product supports first-class (`s3.endpoint`,
`s3.force-path-style: true`, and the configuration comments name MinIO
explicitly). `oss.enabled` and `s3.enabled` are mutually exclusive and the service
refuses to start if both are true.

`S3ObjectStorage` never creates buckets, so `up.sh` pre-creates one with `mc mb
-p`. `docs/community/application.env.example` documents only the OSS keys; the S3
keys `smoke.sh` sets are listed in that script.

## Environment variable quoting

`docs/community/application.env.example` contains a JDBC URL with four unquoted
`&` characters:

```
SPRING_DATASOURCE_URL=jdbc:mysql://mysql:3306/autowonder?useUnicode=true&characterEncoding=utf-8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai
```

`smoke.sh` demonstrates what actually happens, because it is worse than
truncation and is easily misdiagnosed as a product defect: **sourcing** that line
does not set a truncated variable, it leaves `SPRING_DATASOURCE_URL` *entirely
unset*. Each `&` turns the assignment before it into a background subshell, and
only the trailing `serverTimezone=Asia/Shanghai` survives — as its own unrelated
variable. The harness therefore loads the run environment line by line with
`export`, and asserts the resulting value matches the intended one exactly.

When the service fails to start, suspect the launch script before the product.

## Credentials

Nothing secret is ever placed in a command argument, because argv is readable
through `ps`. Concretely:

- Agent connection credentials live in `runtime.env`, mode 0600; application
  secrets remain in separate mode-0600 state files, all deleted by cleanup;
- bearer tokens go into mode-600 curl config files used with `curl -K`, never
  into `-H` on the command line;
- request bodies go to a mode-600 temp file used with `--data @file`;
- SQL reaches MySQL as an environment variable, not interpolated into a
  double-quoted `sh -c` string — interpolating would let bash command-substitute
  any backtick in the query;
- response bodies are only ever printed after redaction, with credential-shaped
  keys replaced by `<REDACTED len=N>`;
- `redacted-*.json` copies are the only bodies that may be archived as evidence.

## Reading the results

`authchain.sh` writes `CHECK|<PASS|FAIL>|<name>|http=…|success=…|<note>` lines and
ends with `PASS_COUNT`, `FAIL_COUNT` and `VERDICT`. Three of its checks are
**expected failures** of the product's own guards, asserted as such:

- `ws_dup_restore_a` expects `11007` — restoring a soft-deleted workspace is
  refused once an active row holds that name again;
- `ws_dup3` expects `11003` — an active row blocks creating another with the same
  name;
- `conv_turn` expects refusal when no runtime is connected, which on a fresh
  install is always the case.

Those two workspace checks are the decisive test of migration `V051`, which swaps
the unique key `uk_name` for `uk_active_name`, a `STORED` generated column that is
`NULL` once a row is soft-deleted. Both directions are asserted against the live
database, not against SQL text.

`logscan.sh` joins the `request_id` in every diagnostic log line to the
`request_id` in every response body the harness saved, so each `ERROR` or `WARN`
is answered with "which call of mine caused this". `ERROR_UNATTRIBUTED` and
`WARN_UNATTRIBUTED` must be `0`: an attributed diagnostic is explained by
construction, an unattributed one means the application complained about something
nobody asked it to do.

`down.sh` ends with a teardown report whose residue counters must all be `0` /
`no`, including `PORT_<n>_LISTENERS` for all five ports and
`CREDENTIAL_FILES_DELETED`.

## Optional SLS protocol regression

Start with `./e2e-tests/verify.sh --start --with-sls --project-root "$(pwd -P)" --keep-on-failure`.
The saved mode is reused by `--check`, `--status`, `--stop` and `--clean-up`;
`--with-sls` is accepted only with `--start`. The default remains SLS disabled.

This uses the real application image and SLS SDK with dummy credentials and an
isolated Python 3.12 HTTP receiver on the Compose network (no extra host port).
It validates the local SLS request protocol, not cloud authentication or cloud
log ingestion. Three distinct stores must receive nonempty compressed protocol
requests: system, business and metrics. `--check` waits up to 75 seconds for the
30-second metrics cycle and asynchronous sends, and fails for missing receivers,
missing streams or rejected requests before scanning application logs.

Only request counts and byte counts are retained in `sls-receipts.json`; request
bodies and authentication headers are never recorded. Cleanup archives this
sanitized evidence under `checks/` and removes the receiver with the other owned
Compose resources. The receiver requires the `python:3.12-alpine` image; startup
uses the existing image acquisition and public mirror fallback helpers.

## JSON wire regression

Every `--check` also runs `json_regression.mjs` using the Maven-installed Node 22.
It creates an isolated user/workspace and a scripted executor protocol fixture,
then checks four real conversation WebSocket frames (empty/populated environment
variables, initial/resumed turns), no generated `$ref`, strict JSON parsing,
and exact Unicode/newline/quote/JSON-shaped string roundtrips through the API.
The fixture acknowledges turns but does not invoke a model. `--with-runtime`
separately exercises the real Qoder executor and artifact download. Only check
names, IDs and a synthetic value digest are archived in `json-regression.json`;
raw frames and tokens are never logged.

The log gate attributes only two narrowly defined diagnostics from a successful
real runtime scenario: optional usage artifacts with `reason=no_entries` and the
owned executor's teardown EOF. Both require PASS evidence, completed cleanup,
all dispatch/artifact checks and exact object IDs. The EOF must additionally fall
between the per-log checkpoints recorded after artifact verification and after
cleanup. Other reasons, IDs, exception types, ERROR records, earlier disconnects
and incomplete/failed runtime evidence still fail the gate.
