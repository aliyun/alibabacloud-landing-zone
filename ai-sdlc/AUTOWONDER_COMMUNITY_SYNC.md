# AutoWonder Community GitHub Sync Guide

## Ownership and acceptance

Publish an already accepted, merged Community Git tree into
`ai-sdlc/auto-wonder/`. Community owns product changes and quality acceptance;
GitHub publication owns exact mirroring, remote identity and upstream PR CR.

If the accepted Community root, merged Community root and GitHub application
subtree are identical, reuse the accepted build/test/Skill/E2E/review evidence.
Do not build a GitHub baseline, repeat tests, start services, rerun full source
scans or repeat product reviews. Required server-side CI is unaffected; do not
launch duplicate local runs. Preserve all skips and coverage limitations.

## 1. Pin inputs

Use execution-provided paths, never developer-specific paths or credentials.
Fetch `origin/community` internally and `upstream/master` plus `origin/master`
in GitHub. Pin full Community and upstream base commit IDs. Record fork/master
relation only; it is not a base and need not be fast-forwarded.

Read `ACCEPTED_COMMIT` from the accepted quality manifest. Confirm required
checks and reviews passed, findings were resolved, cleanup completed and the
internal MR merged. Do not rewrite historical reports for a new commit ID.
Use a dedicated GitHub worktree from the pinned upstream base and a branch
containing the Community short SHA. Do not mutate the user's checkout.

## 2. Export, mirror and commit

Require Git, Python 3.8+, tar and rsync, with complete local clones (not
partial/promisor clones, which the verifier rejects before object reads). Do not install build tools or Docker
for a mirror operation. Export only the immutable Community commit:

```bash
TARGET="$GITHUB_WORKTREE/ai-sdlc/auto-wonder"
STAGE=$(mktemp -d)

test -n "$GITHUB_WORKTREE"
test "$(git -C "$GITHUB_WORKTREE" rev-parse --show-toplevel)" = "$GITHUB_WORKTREE"
test -d "$TARGET"
set -o pipefail
git -C "$INTERNAL_REPO" archive --format=tar "$COMMUNITY_COMMIT" |
  tar -xf - -C "$STAGE"
rsync -a --delete "$STAGE/" "$TARGET/"
git -C "$GITHUB_WORKTREE" add -A -- ai-sdlc/auto-wonder
git -C "$GITHUB_WORKTREE" diff --check --cached
```

Stop on any nonzero exit. Record source/base in the commit message and commit
before verifying. Never use a working-directory copy or independently patch
mirrored product files. Keep runbook changes in a separate documentation PR.
If there is no mirror diff, reuse the existing output commit and PR state.

## 3. Execute the three-tree gate

`VERIFIER_REPO` must contain the reviewed repository verifier. Record its commit
ID. For older releases predating the tool, use a separately pinned reviewed
tooling checkout; do not change the accepted release just to add the script.
The authoritative script is `scripts/verify-community-mirror.py` in AutoWonder.

```bash
GITHUB_OUTPUT_COMMIT=$(git -C "$GITHUB_WORKTREE" rev-parse HEAD)
python3 "$VERIFIER_REPO/scripts/verify-community-mirror.py" \
  --internal-repo "$INTERNAL_REPO" \
  --github-repo "$GITHUB_WORKTREE" \
  --accepted-commit "$ACCEPTED_COMMIT" \
  --community-commit "$COMMUNITY_COMMIT" \
  --github-commit "$GITHUB_OUTPUT_COMMIT" \
  --github-base "$GITHUB_BASE" \
  --output "$EVIDENCE_DIR/mirror-verification.json"
```

Exit 0 / MATCH is required. Exit 1 means mismatch; exit 2 means an input or
execution error. Never treat missing output or an old JSON file as PASS.
The script recursively compares committed paths, object IDs and modes, plus
root tree IDs, and rejects changes outside the application subtree. Added
nested directories need no configuration. Git-untracked/ignored files and
empty directories are not committed content. Gitlink/LFS references are
compared as committed pointers, not validated external payloads.

One independent reviewer checks the script's inputs, exit code, JSON report
and original acceptance provenance. Do not manually compare files or re-review
unchanged application code. Record `verificationMode=REUSED`, original evidence
references, skips/limits and the mirror report. This is not a new test run.

If the mirror differs, repair the mirror and rerun the script. If the accepted
and merged Community trees differ, return the changed source to Community
quality convergence. If acceptance is missing or failed, recover evidence or
complete the missing Community checks. A concrete changed external build or
deployment input may require a targeted check: record the input, affected
behavior and coverage gap first. Different checkout paths or commit IDs alone
are not a reason to repeat acceptance.

## 4. Push, PR and finish

Push precisely the verified commit to the authorized fork. Use `git ls-remote`
to prove remote branch SHA equals the report's GitHub commit. Do not stage more
changes after verification. Do not push/merge upstream master directly.

Create or reuse one upstream PR. If only a compare creation entry is available,
state that it is not an existing PR. Include accepted/Community/base/output
commits, verifier commit, tree result, changed-file summary, version/release,
reused acceptance evidence, known limitations and rollback.

After human merge, confirm PR state and the resulting application tree equals
the report's Community tree; squash/rebase commit IDs may differ. Do not rerun
acceptance or create another equivalent PR. Clean up only this run's owned
staging/worktree resources when safe; no E2E cleanup when no E2E was started.
Keep non-secret evidence. Never change mirrored release files merely to backfill
PR links. No tag is created without prior explicit concrete-tag authorization.
