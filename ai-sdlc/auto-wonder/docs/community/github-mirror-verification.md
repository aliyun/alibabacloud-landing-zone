# Community → GitHub verification contract

Product acceptance belongs to Community (SDLC 400360). Publishing an identical
Git tree does not require a second acceptance cycle. This contract supersedes
older mirror runbook requirements to rebuild a GitHub baseline or repeat
Maven, frontend, Skill, integration or E2E tests.

## Inputs and command

Pin the accepted commit from the accepted quality manifest, merged Community
commit, GitHub base and committed GitHub output. Use full immutable commit IDs.
Before reuse, confirm required checks/reviews passed, findings were resolved,
cleanup completed and the internal MR merged. Preserve original reports,
skips, limitations and environment scope; never promote untested capabilities.

```bash
python3 "$VERIFIER_REPO/scripts/verify-community-mirror.py" \
  --internal-repo "$INTERNAL_REPO" \
  --github-repo "$GITHUB_WORKTREE" \
  --accepted-commit "$ACCEPTED_COMMIT" \
  --community-commit "$COMMUNITY_COMMIT" \
  --github-commit "$GITHUB_OUTPUT_COMMIT" \
  --github-base "$GITHUB_BASE" \
  --output "$EVIDENCE_DIR/mirror-verification.json"
```

Paths are execution inputs. VERIFIER_REPO is a checkout of the reviewed verifier
version recorded in evidence; it may differ from the source being mirrored for
older releases predating this script. Do not add tooling to an already accepted
release merely to verify it. Requires Python 3.8+ and Git; no dependencies. Partial/promisor clones are rejected
before object reads to prevent implicit network downloads; use complete local clones.

The script recursively compares Git objects, not filesystem copies or a fixed
list of directories. Paths, object IDs, Git modes and root tree IDs must match,
including executable bits and symlink targets. New/nested directories are
included automatically. NUL-delimited Git output handles spaces, tabs, newlines
and non-ASCII filenames. Git does not track empty directories or untracked/
ignored checkout files. Gitlink/LFS entries identify committed references or
pointers; this is not validation of their external payloads.

Changes outside ai-sdlc/auto-wonder/ relative to the ancestor base are rejected.
Publish GitHub runbook changes separately, without adding mirror exceptions.
Commit before verification; push precisely that commit, with no later staging.

- Exit 0 / MATCH: accepted root = merged Community root = GitHub subtree,
  and no outside-subtree changes.
- Exit 1 / MISMATCH: stop; inspect structured differences. Reports contain
  exact counts and up to 50 differing entries per comparison.
- Exit 2 / ERROR: invalid/missing objects or inputs, non-ancestor base or
  report I/O failure. Never infer PASS or consume an older report.

## Fixed publication sequence

1. Consume accepted Community evidence and merged internal MR state.
2. Pin source/base; git archive the Community commit, mirror only the application
   subtree with deletion semantics and commit.
3. Execute the script once. One independent reviewer checks input identities,
   exit code, JSON and acceptance provenance. No manual per-file comparison or
   repeated product review.
4. Record verificationMode=REUSED, original evidence references and the new
   mirror report. This is inherited acceptance, not tests executed on GitHub.
5. Push and verify remote SHA. Create/reuse the upstream PR; await human CR.
   Never automatically merge protected branches.
6. After merge, verify PR state and resulting application tree equals the
   recorded Community tree. No repeated tests. Clean up only this run's owned
   resources that have not already been cleaned.

Do not build/test the old GitHub baseline. For identical accepted trees, do not
repeat Maven/frontend builds, unit/integration tests, deployment/upgrade Skill
suites, E2E, startup, full dependency/reference scans or the three Community
product reviews. Required repository CI still runs; do not launch local copies.

## Explicit exceptions

- Mirror mismatch: repair export/copy scope and rerun this script. Tests cannot
  substitute for identity; never repair product code only in GitHub.
- Accepted/merged Community mismatch: return changed source to Community
  quality convergence. Different commit IDs alone do not invalidate evidence.
- Missing/failed acceptance: recover evidence or complete missing Community
  checks; never label them reused PASS.
- Concrete external build/deployment input changes: record input, affected
  behavior and original coverage gap; verify only that difference. A different
  checkout path/branch/commit ID does not qualify.

This tooling/documentation change requires real-Git verifier regression and
review, not another product release or full application acceptance. Keep it in
a separate reviewed Community MR; do not rewrite accepted release evidence.

```bash
python3 -m unittest discover -s scripts/tests -p test_community_mirror.py -v
```
