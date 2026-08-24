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
   - the community frontend supports creating Qoder CLI executors only; preserve
     this restriction when syncing executor UI changes from master;
   - the supported release target remains Linux x86_64 until expanded explicitly.
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
     is a blocking sync defect.
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
