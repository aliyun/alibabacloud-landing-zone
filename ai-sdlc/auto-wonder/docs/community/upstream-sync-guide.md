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
   - Aone remains optional and disabled by default;
   - the community frontend supports creating Qoder CLI executors only; preserve
     this restriction when syncing executor UI changes from master;
   - the supported release target remains Linux x86_64 until expanded explicitly.
5. Do not preserve a community difference when the new master implementation is
   already community-compatible.
6. Do not silently drop a master feature to make a conflict easier to resolve.
7. Preserve unrelated local and untracked files.
8. Apply [docs-policy.md](docs-policy.md) to every upstream documentation change;
   do not restore excluded development history in `community`.

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
4. Run the gates in [verification.md](verification.md), including backend tests,
   frontend tests/build, deployment Skill tests, and internal-reference scans.
   Run Maven verification and standalone frontend gates serially because both
   use the same `frontend/node_modules` directory.
5. Update [upstream-sync-log.md](upstream-sync-log.md) with exact full commit IDs,
   scope, conflict decisions, and verification results.
6. Commit the log update separately, push `community`, and confirm local HEAD
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
- test, build, and dependency-boundary results.

Never move the recorded baseline until the merge and required verification have
completed. The last verified baseline is the starting point for the next sync.
