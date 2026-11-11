# Community sync quality contract

Acceptance details supplementing [the upstream sync guide](upstream-sync-guide.md).
Sync boundaries, configuration inventory, migration immutability, runtime version
selection and lifecycle commands remain defined there. Tooling/docs-only changes
need affected tests and review; identical mirrors follow the [mirror contract](github-mirror-verification.md).

## Additional acceptance checks

- **Dependency adapters:** compare pre-sync Community data and frozen upstream
  behavior: serialization, encoded size, storage capacity, null/default handling,
  reading/decryption, errors and side effects. For SecretCrypto changes, use actual
  encryption with normal, maximum-length, combined-credential, UTF-8 and historical
  values. Inline ciphertext must fit storage; remote-reference length limits do
  not establish that.
- **Migrations:** test old Community schema/data → migration → candidate code in
  a real database, including historical boundary values. Verify data remains
  usable/decryptable and compare types, capacities, defaults, nullability,
  collations, indexes and constraints with fresh installation. Empty tables and
  idempotence alone do not prove compatibility; unexplained narrowing/data loss
  blocks acceptance. Execute complete dynamic SQL statements in one session,
  preserving MySQL `@variables`; inspect completed DDL before resuming a failure.
- **Configuration:** extend the guide's inventory to Community adaptation code;
  review dynamic keys manually. Java defaults or successful startup do not excuse
  an unexplained missing configuration entry.
- **Switches:** test affected global/subordinate combinations, defaults and
  precedence, including prohibited client/thread/network side effects. SLS enabled
  with telemetry disabled must not emit telemetry. Controlled receivers or producer
  doubles suffice for switch semantics.
- **Scenario equivalence:** record source commit/path/ID, behavior, assertions,
  original/adapted test level, real/fixture dependencies, result and raw evidence.
  Choose coverage by affected behavior; a replacement missing assertions or real
  component boundaries is PARTIAL. Mock tests do not prove authentication, token
  routing, storage, transaction-after-commit or executor delivery. Recovery/history
  claims require exercising those states.
- **Real delivery:** run `verify.sh --check --with-runtime` for task dispatch →
  execution → terminal result → exact artifact download/readback. Retain native
  `runtime-dispatch.json`, lifecycle `runtime.json` and process exit code. Missing
  required credentials leave coverage unfinished; fixtures cannot replace it.

## Evidence gate

`scripts/verify-community-quality.py` checks evidence and local Git consistency;
it runs no product tests or network requests. Requires Python 3.8+ and complete
local Git objects; partial/promisor clones are rejected.

```sh
python3 "$repo/scripts/verify-community-quality.py" \
  --repo "$repo" --manifest "$manifest" --evidence-root "$evidence_root" \
  --phase local
```

Supply paths from the current workspace. After push and actual remote readback,
update tracking and run `--phase submitted`; this also compares `remoteSourceSha`
and `origin/<branch>` with the candidate. Preserve the readback receipt: the gate
does not contact the remote.

### Manifest

Keep `schemaVersion: 1`, candidate identity, summaries and history; add
`qualityContractVersion: 1` for new acceptance. Do not rewrite old accepted evidence.
`cycle` is a positive integer; `executionFingerprintCounts` and
`repairFingerprintCounts` map fingerprints to nonnegative integers.

| Field | Required value |
| --- | --- |
| `implementer` | Actual implementation task identity |
| `openRequiredCheckpoints` | Integer zero |
| `qualityChecks` | Check rows below |
| `runtimeEvidence` / `runtimeContext` | References to native dispatch / lifecycle JSON |
| `reviewEvidence` | Independent review entries below |

Each evidence reference is `{"path":"cycle-1/result.json","sha256":"<actual digest>"}`.
Paths use `/`, are relative to `evidence_root`, and cannot escape it through absolute
paths, `..` or symlinks. Hash the archived report bytes; exclude protected env files
and credentials. Preserve failed attempts separately from current results.

Each check row supplies `id`, `status: PASS`, integer `exitCode: 0`, `level`,
`testedCommit` and a nonempty `evidence` reference list.

| Check IDs | Requirement |
| --- | --- |
| `static`, `configuration`, `json-protocol`, `runtime-delivery`, `sls-disabled`, `sls-enabled`, `cleanup` | Always required |
| `encryption`, `migration`, `feature-switches` | Always recorded; allow `NOT_APPLICABLE` only with reason, input binding and no-impact evidence |

`static` includes the guide's backend/frontend/Skill/dependency/public-boundary
coverage, raw results and explicit skips. Additional checks must also pass.
FAIL/NOT_RUN/PARTIAL or missing credentials cannot satisfy acceptance.

Levels: `static`, `unit`, `integration`, `api-integration`, `runtime-e2e`.
`runtime-delivery` requires `runtime-e2e`; its native report must pass dispatch,
download, artifact-digest and cleanup checks with the candidate's source/effective
runtime recommendation. Its lifecycle context must identify the clean final tree.

For each `masterSemantics`, `communityBoundary`, `releaseReadiness` review,
`reviewEvidence` contains `reviewer`, retrievable `executionRef`, `independent: true`,
`candidateTree` and nonempty `evidence`; the corresponding `reviews` summary is
`PASS`. One independent reviewer may cover all three; self-review cannot substitute.

### Reuse and limits

Checks bind to the candidate tree. Unchanged component results may bind to another
commit only with `reuseReason` and `inputs` listing tracked paths with identical Git
objects. Review must confirm these inputs cover all relevant code, dependencies,
build/configuration and fixtures. Real runtime evidence must use the final tree.

Hashes and PASS fields do not prove authenticity, complete coverage or reviewer
independence; review raw evidence and execution provenance. Gate success and
independent review are both required. Explicit scope exceptions must be reported
separately, never disguised as full acceptance. Report differing source freezes
separately and distinguish removed API readback from lost persisted data/decryption.

## Maintenance

```sh
python3 -m unittest discover -s scripts/tests -p 'test_community_quality.py' -v
```

Activate SDLC calls only after the reviewed script is available in Community.
If absent, fail the gate; do not create a weaker replacement.
