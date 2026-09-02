# Community Upstream Sync Guide

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
5. Do not preserve a community difference when the new master implementation is
   already community-compatible.
6. Do not silently drop a master feature to make a conflict easier to resolve.
7. Preserve unrelated local and untracked files.
8. Apply [docs-policy.md](docs-policy.md) to every upstream documentation change;
   do not restore excluded development history in `community`.
9. Review every upstream configuration or operational change against the
   deployment Skill, environment templates, deployment scripts, and operator
   guidance. Update those assets in the same sync when their contract changes.
   Do not infer configuration completeness from a successfully merged properties
   class: compare every upstream configuration file and key explicitly.
10. Complete an independent post-sync review before pushing. The reviewer must
    look for missed master behavior, incorrect conflict resolution, unintended
    community divergence, and incomplete external-repository output.
11. When upstream changes database DDL, synchronize the corresponding immutable
    incremental SQL into `docs/migration/`. Updating the full schema alone is not
    sufficient. Previously published migrations must not be modified, renamed,
    or deleted.
12. After every sync, verify runtime and deployment version consistency across
    all sources of truth. At minimum, confirm that these values agree:
    - `application.yml` → `autowonder.runtime.recommended-version` default;
    - `skills/deploying-autowonder-on-alibaba-cloud/assets/templates/deployment-manifest.json`
      → `recommendedRuntimeVersion`;
    - `skills/deploying-autowonder-on-alibaba-cloud/tests/test_manifest.py`;
    - `skills/deploying-autowonder-on-alibaba-cloud/tests/test_script_contracts.py`;
    - `skills/upgrading-autowonder-on-alibaba-cloud/tests/test_upgrade_info.py`
      → `recommendedRuntimeVersion` fixtures and assertions.

    Both deploy and upgrade Skills must reference the same runtime version so
    that fresh deployments and upgrades converge to the correct executor release.
    A mismatch means external deployments or upgrades will install an outdated
    runtime version. Treat this as a blocking sync defect.

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
git rev-parse origin/master
git log --oneline <recorded-baseline>..origin/master
git diff --name-only <recorded-baseline>..origin/master
git merge --no-ff origin/master
```

After the merge:

1. Review master-only commits and every file changed on both sides.
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
6. Run an independent sync review after conflict resolution and verification.
   At minimum, the review must:
   - prove the fetched `origin/master` baseline is an ancestor of `community`;
   - compare the upstream changed-file list with the final master/community tree
     differences and account for every overlap;
   - inspect conflict resolutions and automatically merged shared files for lost
     master behavior or unintended community behavior;
   - recheck community boundaries, deployment-asset impact, and documentation
     policy;
   - verify the external-repository copy against `community` when external output
     is part of the sync.

   Fix all critical or important findings before moving the baseline or pushing.
7. Update [upstream-sync-log.md](upstream-sync-log.md) with exact full commit IDs,
   scope, conflict decisions, deployment-impact conclusion, independent-review
   conclusion, and verification results.
8. Commit the log update separately, push `community`, and confirm local HEAD
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
