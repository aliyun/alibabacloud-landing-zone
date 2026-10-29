# Community Upstream Sync Guide

For an accepted Community → GitHub mirror, the
[mirror verification contract](github-mirror-verification.md) is authoritative:
execute the repository verifier and reuse acceptance for identical trees.
Do not repeat product acceptance merely because the repository or commit ID changes.

## Goal

Keep `community` aligned with `origin/master` while preserving only the changes
required for a community build and runtime.

## Rules

1. Work only on the `community` branch or its dedicated worktree. Never perform
   the sync on `master`.
2. Merge an exact fetched `origin/master` commit. Do not merge a stale local
   `master` branch.
3. Master owns product behavior, APIs, schema evolution, UI behavior, and tests.
4. Community owns only its external-deployability boundary:
   - no Alibaba-internal build or runtime dependency;
   - `SecretCrypto` replaces KeyCenter;
   - OSS remains mandatory;
   - public SLS remains supported;
   - BUC (internal unified authentication) related features are excluded entirely;
     do not sync BUC integration code, configuration, or UI to community;
   - Aone (internal workitem system) related feature iterations are excluded;
     the existing Aone integration remains as-is (optional and disabled by default)
     but new Aone-specific feature development on master need not be synced —
     only sync Aone changes when they are inseparable from a broader product
     feature that community requires;
   - Aone-specific configuration keys beyond `AUTOWONDER_AONE_ENABLED` — for
     example `AUTOWONDER_AONE_WEB_BASE_URL` — must never be listed in
     `docs/community/application.env.example`. External community users have no
     Aone instance, and these keys are optional with empty defaults that do not
     affect startup or runtime while Aone is disabled. Their absence from the
     environment inventory is intentional and permanent; the configuration-key
     audit in step 4 must not report it as a missing key. Do not reintroduce them
     in a later sync;
   - the community frontend supports creating Qoder CLI executors only; preserve
     this restriction when syncing executor UI changes from master;
   - the supported release target remains Linux x86_64 until expanded explicitly.
   - all product Skills live under root `skills/` (not `docs/skills/`); sync
     upstream `docs/skills/` content into `skills/` during merge;
   - the root `.agents/` directory is part of the community distribution; both
     `skills/` and `.agents/` must be synced to the external GitHub repository
     under `ai-sdlc/auto-wonder/`.
5. The root `e2e-tests/` directory is a community-owned verification boundary
   and is an explicit exception to rule 3. During an upstream sync, never copy,
   merge, cherry-pick, restore, or otherwise import `origin/master` E2E files or
   directories into `community`. Preserve the complete `e2e-tests/` tree from
   the pre-sync community commit without modification. If the directory did not
   exist in that commit, the sync must not introduce it from master. Community
   E2E changes require a separate, intentional community-owned change and must
   not be bundled into an upstream sync.

   A separately authorized Community E2E repair or verification-policy change
   may follow the preserved merge as its own reviewed change. Record the exact
   authorization, reason and changed paths; test that delta against the final
   candidate. Keep the original preservation comparison for the merge and
   account for the later authorized delta separately. This never permits
   importing master E2E files, changing historical acceptance evidence, weakening
   assertions, or treating a prior candidate's verdict as current.
6. Do not preserve a community difference when the new master implementation is
   already community-compatible.
7. Do not silently drop a master feature to make a conflict easier to resolve.
8. Preserve unrelated local and untracked files.
9. Apply [docs-policy.md](docs-policy.md) to every upstream documentation change;
   do not restore excluded development history in `community`.
10. Review every upstream configuration or operational change against the
   deployment Skill, environment templates, deployment scripts, and operator
   guidance. Update those assets in the same sync when their contract changes.
   Do not infer configuration completeness from a successfully merged properties
   class: compare every upstream configuration file and key explicitly.
11. Complete an independent post-sync review before pushing. The reviewer must
    look for missed master behavior, incorrect conflict resolution, unintended
    community divergence, and incomplete external-repository output.
12. When upstream changes database DDL, synchronize the corresponding immutable
    incremental SQL into `docs/migration/`. Updating the full schema alone is not
    sufficient. Previously published migrations must not be modified, renamed,
    or deleted.
13. After every sync, verify that `application.yml` →
    `autowonder.runtime.recommended-version` remains the single source of truth
    for the recommended runtime version. The deployment manifest template must
    not hard-code that version. Both deploy and upgrade Skills must derive it
    from the exact source release, validate it, record the resolved value in the
    working manifest for audit and resume, and update
    `AUTOWONDER_RUNTIME_RECOMMENDED_VERSION` before application activation or
    restart. A missing, invalid, or independently maintained value is a blocking
    sync defect.

    The standard `verify.sh --check --with-runtime` must follow this source
    recommendation, not a version pinned inside the E2E helper or instructions.
    An explicit lifecycle deployment override is read only from its protected
    `app.env`; the effective value must agree with public branding API
    `data.recommendedRuntimeVersion`. Invalid values, API failures or mismatches
    block the check. Package metadata and daemon self-check must both match the
    selected version. Use the helper's `--print-recommended-version` to install
    the correct package; see [the Agent guide](../../e2e-tests/AGENT_GUIDE.md).

## Conflict Decisions

Apply this order:

1. Accept master when the change does not cross a community boundary.
2. Keep master behavior and adapt only its internal dependency or configuration
   edge when it crosses a community boundary.
3. Add a compatibility adapter only when a direct replacement is insufficient.
4. Stop for a product decision when both master behavior and the community
   boundary cannot be retained, or when schema/data compatibility is uncertain.

Always review files changed on both branches even when Git reports no textual
conflict. Automatic merge success does not prove semantic compatibility.

## Procedure

```bash
git status --short --branch
git fetch origin master community --prune
community_before_sync=$(git rev-parse HEAD)
git rev-parse origin/master
git log --oneline <recorded-baseline>..origin/master
git diff --name-only <recorded-baseline>..origin/master
git merge --no-ff origin/master
git diff --exit-code "$community_before_sync" -- e2e-tests/
```

The final command is a mandatory community E2E preservation gate. Any output or
non-zero exit status means the upstream sync changed `e2e-tests/` and is a
blocking defect. Restore the entire path to the exact pre-sync community tree
(or remove it when it was absent before the sync), then rerun the gate. Do not
resolve the failure by copying any part of `origin/master:e2e-tests/`.

After the merge:

1. Review master-only commits and every file changed on both sides. Confirm that
   `e2e-tests/` is identical to `$community_before_sync`; do not review or adapt
   master E2E content for inclusion in community.
2. Filter documentation through [docs-policy.md](docs-policy.md).
3. Verify schema, configuration, encryption, OSS/SLS, optional integrations,
   frontend contracts, and tests.
   For every DDL change, compare the previous and target schemas and verify a new
   correctly ordered `docs/migration/V<n>__<description>.sql` exists. Treat a
   missing migration or any changed historical migration as a blocking issue.
4. Review deployment impact whenever upstream changes configuration properties,
   environment variables, startup requirements, external endpoints, ports,
   storage, databases, credentials, or runtime versions. Compare the change with:
   - `skills/deploying-autowonder-on-alibaba-cloud/` inputs, manifest, scripts,
     preflight checks, templates, runbook, troubleshooting, and tests;
   - `docs/community/application.env.example` and other retained deployment docs.

   Update affected assets in the same sync. Confirm that required variables are
   both written by deployment scripts and explained or validated during input
   collection; record an explicit "no deployment update required" conclusion
   when the review finds no impact.
   Build a configuration-key checklist from every changed `application*.yml`,
   `@ConfigurationProperties` class, XML configuration, environment template,
   systemd unit, and infrastructure template. For each added, removed, renamed,
   or default-changed key:
   - verify the Community runtime preserves the upstream capability, including
     optional and disabled-by-default integrations;
   - verify its environment binding, default, validation, and mutual-exclusion
     behavior agree with the implementing properties/configuration class;
   - review deployment input collection, protected environment generation,
     upgrade planning, operator documentation, and tests for impact;
   - record every intentional Community difference. An unexplained missing key
     is a blocking sync defect. "Missing" means a key the runtime reads that
     neither `docs/community/application.env.example` nor `application*.yml`
     provides. The deployment environment contract is the union of both sources —
     `plan-upgrade.sh` collects `${VAR}` placeholders from the yml as well as the
     `KEY=` lines of the example — so a key that appears only in the yml is
     already covered by upgrade planning and is not a defect. Do not bulk-add
     such keys to the example: doing so changes their contract hash and makes the
     upgrade planner report them as changed env for no functional gain.
5. Run the gates in [verification.md](verification.md), including backend tests,
   frontend tests/build, deployment Skill tests, and internal-reference scans.
   Run Maven verification and standalone frontend gates serially because both
   use the same `frontend/node_modules` directory.
6. **Mandatory Agent E2E lifecycle.** After merge/conflict resolution and the
   static gates, but before advancing the synchronized baseline or pushing
   `community`, run the final community image from the exact pending-merge
   worktree:

   ```bash
   ./e2e-tests/verify.sh --start --project-root "$(pwd -P)" --mode image --keep-on-failure
   ```

   `--project-root` is always a filesystem path, never a branch name. The Agent
   must not fetch, checkout, or merge as part of this command. The lifecycle
   owns host prerequisite detection/installation, port-conflict resolution,
   compilation, final-image construction, isolated MySQL/Redis/MinIO startup,
   fresh-schema import and verification, Spring Boot startup, standard probes,
   and cleanup. Follow [the Agent E2E guide](../../e2e-tests/AGENT_GUIDE.md)
   rather than inventing alternate startup commands.

   The Agent must perform this evidence loop:

   1. Record the paths printed before long-running work begins. At minimum retain
      `BOOTSTRAP_LOG`, `BUILD_LOG`, `IMAGE_BUILD_LOG`, `COMPOSE_LOG`,
      `MYSQL_LOG`, `REDIS_LOG`, `MINIO_LOG`, `SPRING_BOOT_CONSOLE_LOG`,
      `SPRING_BOOT_FILE_LOG`, `STARTUP_DIAGNOSTIC_LOG`, `FAILURE_REPORT`, and
      `RESULT_JSON`. A log-path announcement is not a success result; wait for
      the final `LIFECYCLE|END|PASS` or `LIFECYCLE|END|FAIL` record.
   2. After `VERDICT=STARTED`, read `runtime.json` for the tested Git commit and
      dirty state, application image, resolved platform/MySQL/Redis/MinIO ports,
      URLs, Compose project/network, and log paths. Read only the individual
      credential values needed for a check from `runtime.env`, which must have
      mode `0600`. Never source or print that file, and never copy its values to
      ordinary logs, chat, screenshots, reports, or command arguments.
   3. Confirm service and health state through the standard lifecycle command:

      ```bash
      ./e2e-tests/verify.sh --status --project-root "$(pwd -P)"
      ```

      Inspect `compose.log` and the MySQL/Redis/MinIO logs for container state;
      inspect `schema-verification.txt` and `probes.txt` under `RUN_STATE_DIR`
      for database/schema and startup-probe evidence. For sync-specific DDL or
      service changes, use the resolved connection metadata and credentials
      without exposing them, and record the query/assertion result rather than
      the secret-bearing command.
   4. Perform the sync-specific human-style UI journey and/or API requests using
      the platform URL in `runtime.json`. While exercising the platform, follow
      Spring Boot output when useful:

      ```bash
      ./e2e-tests/verify.sh --logs --follow --project-root "$(pwd -P)"
      ```

      Correlate unexpected behavior with `spring-boot-console.log`,
      `auto-wonder.log`, and `startup-diagnostic.log`; do not declare success
      from an HTTP health response alone.
   5. Run the mandatory final gate after all Agent-driven interaction:

      ```bash
      ./e2e-tests/verify.sh --check --project-root "$(pwd -P)"
      ```

      This rechecks health, executes the authenticated functional chain, checks
      database-backed behavior, and scans ERROR/WARN records with request-ID
      attribution. Success requires command exit 0, `VERDICT=PASS`, a passing
      `result.json`, `FAIL_COUNT=0` in `authchain.txt`, and zero unattributed
      ERROR/WARN records in `log-scan-attributed.txt`. `VERDICT=STARTED` alone is
      never a release or synchronization verdict.
   6. Always archive evidence and remove only this run's resources when analysis
      is complete, whether startup/check passed or failed:

      ```bash
      ./e2e-tests/verify.sh --clean-up --project-root "$(pwd -P)"
      ```

      Verify the teardown report has zero owned containers, volumes, network,
      and port listeners. If startup failed before Compose was attempted,
      `STATE_ONLY` cleanup is expected and does not require Docker.

      When investigation must pause before cleanup, stop only this run while
      preserving its state, then resume analysis or clean it later:

      ```bash
      ./e2e-tests/verify.sh --stop --project-root "$(pwd -P)"
      ```

      `--stop` does not replace the final `--clean-up` requirement.

   Add the run ID, tested commit/dirty state, resolved ports, Maven/test result,
   image/startup result, schema result, Agent UI/API scenarios, authenticated
   check counts, attributed and unattributed ERROR/WARN counts, final verdict,
   evidence archive path, and cleanup residue counters to
   [upstream-sync-log.md](upstream-sync-log.md). Any failure or unexplained
   diagnostic is a blocking sync defect.
7. Run an independent sync review after conflict resolution and verification.
   At minimum, the review must:
   - prove the fetched `origin/master` baseline is an ancestor of `community`;
   - compare the upstream changed-file list with the final master/community tree
     differences and account for every overlap;
   - inspect conflict resolutions and automatically merged shared files for lost
     master behavior or unintended community behavior;
   - recheck community boundaries, deployment-asset impact, and documentation
     policy;
   - prove `git diff --exit-code "$community_before_sync" -- e2e-tests/`
     succeeds, so no master E2E file or directory entered the community tree;
   - verify the external-repository copy against `community` when external output
     is part of the sync.

   Fix all critical or important findings before moving the baseline or pushing.
8. Update [upstream-sync-log.md](upstream-sync-log.md) with exact full commit IDs,
   scope, conflict decisions, deployment-impact conclusion, independent-review
   conclusion, and verification results.
9. Commit the log update separately, push `community`, and confirm local HEAD
   equals `origin/community`.

## Required Release File

Every sync that increments `VERSION` must produce a release file at
`releases/release_vX.Y.Z_YYYYMMDD.md`. The file must include at minimum:

- version bump and rationale;
- previous and new master baselines;
- feature/fix summary;
- community adaptations;
- **Upgrade And Data Impact** — describe any breaking runtime, configuration,
  or data-format change that affects an existing deployment upgrading to this
  version; if none, state "None — backward-compatible with previous release";
- **DDL/DML/Migration Impact** — list new migration files, schema changes (DDL),
  and any data manipulation (DML) or manual data action required; if none, state
  "No DDL/DML change";
- configuration and deployment impact;
- verification results;
- risks;
- MR/PR links.

The external GitHub copy must include `ai-sdlc/auto-wonder/VERSION` and
`ai-sdlc/auto-wonder/releases/release_vX.Y.Z_YYYYMMDD.md` matching the
community branch exactly. A missing or outdated `VERSION` in the external
repository is a blocking sync defect.

## Required Log Entry

Record at least:

- previous synchronized master baseline;
- community HEAD before merge;
- merged `origin/master` commit;
- resulting merge commit;
- important feature scope;
- overlapping files and their decisions;
- confirmation that the community `e2e-tests/` tree is byte-for-byte unchanged
  from the pre-sync community commit and contains no imported master E2E assets;
- decisions still requiring confirmation;
- deployment Skill, deployment script, environment-template, and operator-doc
  impact, including an explicit no-change conclusion when applicable;
- configuration-key review results, including every intentional omission or
  Community-specific default;
- independent sync-review findings and disposition;
- test, build, and dependency-boundary results.

Never move the recorded baseline until the merge and required verification have
completed and the independent sync review has passed. Always record the full
`origin/master` commit ID rather than a moving branch name or abbreviated SHA.
The last verified baseline is the exclusive starting point for the next sync.

## Community synchronization object-storage acceptance

For Community synchronization acceptance, the release owner authorizes the
standard lifecycle's MinIO S3-compatible backend. Use the existing `verify.sh`
image lifecycle and its isolated MinIO, rather than provisioning real Alibaba
Cloud OSS. Verify upload, object read, presigned download and real executor
artifact download against the URLs returned by that backend; never rewrite
signed URLs to make a failing download pass. Record `storageBackend=MinIO` in
acceptance evidence. This verifies the S3-compatible application path and does
not certify Alibaba Cloud OSS authentication, regional public endpoints, VPC
routing or outside-VPC access. Those remain optional deployment-specific checks
unless explicitly requested. Production storage requirements are unchanged.

This clarification applies to synchronization gates in `verification.md` and
`README.md`: absence of real OSS credentials does not block a synchronization
whose required MinIO scenarios pass. SLS startup checks still follow the SDLC;
MinIO is not an SLS substitute.
