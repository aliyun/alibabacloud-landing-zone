# Community Sync Agent Design

## Goal

Standardize the complete AutoWonder community release path:

1. synchronize the internal `origin/master` into `community`;
2. preserve documented community-only boundaries;
3. version and document the resulting community release;
4. copy the verified community tree into the public GitHub fork;
5. provide an upstream Pull Request creation link targeting `aliyun/master`.

The digital employee may analyze, resolve permitted conflicts, test, push
temporary branches, and prepare MR/PR links. A human must merge every MR/PR and
approve any release tag.

## AutoWonder Resources

Create these resources in organization `Autowonder 自迭代`:

| Resource | Value |
| --- | --- |
| Hosted GitHub repository | `git@github.com:caihe-ch/alibabacloud-landing-zone.git` |
| Public community directory | `ai-sdlc/auto-wonder` |
| Digital employee | `社区同步发布工程师` |
| Role code | `COMMUNITY_RELEASE_ENGINEER` |
| Squad | `社区版本同步与发布小队` |
| SDLC | `社区版本同步发布 SDLC` |

The squad has one member. Grant the digital employee only the repository access
needed to create and push temporary synchronization branches. It must not merge
MRs/PRs or change protected branches.

## Digital Employee Contract

The digital employee is a release and synchronization engineer, not a general
feature developer. Its business background is maintaining a reproducible public
community edition from the internal AutoWonder product baseline while preserving
both upstream behavior and the documented external-deployability boundary.

Its SOUL and AGENT instructions must require it to:

- treat exact commits, repository state, test output, and retained policy files
  as facts; distinguish facts, inferences, and decisions requiring confirmation;
- follow `docs/community/upstream-sync-guide.md`,
  `docs/community/upstream-sync-log.md`, and the documentation policy;
- make the smallest community-only adaptation and never use conflict resolution
  as a reason to drop master functionality;
- apply the conflict levels in this design and stop on every high-risk case;
- keep credentials, tokens, internal endpoints, and secret-bearing files out of
  commits, comments, evidence, and command arguments;
- create and push temporary branches but never merge protected branches;
- propose SemVer changes from evidence rather than release cadence or guesswork;
- report actual commands, commits, test results, risks, MR link, and upstream PR
  creation link using the standard final comment.

The agent must not broaden scope into unrelated refactoring, deploy production,
alter repository permissions, rewrite published history, modify a published
migration, or create a release tag without explicit human authorization.

## Repository Model

### Internal AutoWonder repository

- `origin/master` is the authoritative product baseline.
- `community` is the protected community branch.
- Create a temporary branch from the latest `community` for every synchronization.
- Merge an exact fetched `origin/master` commit into that temporary branch.
- Push the branch and provide an MR link targeting `community`.

Read the previous verified master baseline from the newest completed entry in
`docs/community/upstream-sync-log.md`. Verify that commit is an ancestor of the
current `community`; never infer it from a local `master` branch.

### Public GitHub repository

- `origin` is `git@github.com:caihe-ch/alibabacloud-landing-zone.git`.
- `upstream` is `git@github.com:aliyun/alibabacloud-landing-zone.git`.
- `upstream/master` is the authoritative public PR base.
- After the internal Community MR is merged, create a temporary branch from an
  exact fetched `upstream/master` commit.
- Copy the complete verified `community` tree into `ai-sdlc/auto-wonder`.
- Push the branch to the `caihe-ch` fork.
- Do not call `gh pr create`. Provide a compare link targeting `aliyun/master`:

```text
https://github.com/aliyun/alibabacloud-landing-zone/compare/master...caihe-ch:<branch>?expand=1
```

## Conflict Policy

| Level | Examples | Required action |
| --- | --- | --- |
| Low | documentation, tests, formatting, or an established mechanical community difference | Resolve automatically, test, and summarize the decision. |
| Medium | shared product code whose correct result is explicit in the sync guide and preserves both master behavior and the community boundary | Resolve automatically, add focused verification, and run the independent sync review. |
| High | uncertain schema/data compatibility, auth or permission semantics, cryptography, public API behavior, destructive migration, internal dependency replacement without a proven equivalent, or inability to preserve both master behavior and the community boundary | Stop before commit/push, report both sides, affected files, risks, and recommended options, then wait for human direction. |

Automatic conflict resolution must never silently discard master behavior.

## Version Contract

The first community release is `0.1.0`. The source of truth consists of:

- repository-root `VERSION`, containing only `X.Y.Z` and a trailing newline;
- `releases/release_vX.Y.Z_YYYYMMDD.md`;
- optional annotated tag `autowonder-community-vX.Y.Z` after the upstream PR is
  merged and a human explicitly confirms tag creation.

The external copy places the same files at:

- `ai-sdlc/auto-wonder/VERSION`;
- `ai-sdlc/auto-wonder/releases/release_vX.Y.Z_YYYYMMDD.md`.

Before proposing a version, compare `VERSION`, the newest release file, and any
existing `autowonder-community-v*` tag. A mismatch is blocking.

Use Semantic Versioning:

- `MAJOR`: incompatible API, data, deployment, configuration, or runtime change;
- `MINOR`: backward-compatible user-visible feature or substantial platform
  capability;
- `PATCH`: backward-compatible fix, documentation, deployment-process
  improvement, or ordinary upstream synchronization;
- no material release change: do not increment.

The digital employee proposes the version and explains the bump. A human confirms
it before MR/PR merge. One synchronization batch creates exactly one version and
one release file.

Every release file records the version and date, bump rationale, previous and new
master baselines, Community commits, GitHub upstream base and output branch,
feature/fix summary, community adaptations, configuration and deployment impact,
DDL/DML and migration impact, verification results, risks, and MR/PR links.

## SDLC

### 1. Preflight And Context

Verify repository remotes, protected bases, working-tree state, credentials,
previous sync baseline, `VERSION`, newest release file, and existing tags. Reuse
facts already present in context; present them for confirmation rather than asking
again. Request only missing values. Stop on inconsistent version/baseline data.

### 2. Master Change Analysis

Fetch `origin/master` and `community`. Compare the previous verified baseline to
the exact target commit. Classify product, schema, configuration, dependency,
frontend, runtime, deployment, documentation, and community-boundary impact.

### 3. Internal Community Synchronization

Create a temporary branch from the latest `community` and merge the exact target
commit. Apply the conflict policy. High-risk ambiguity stops the SDLC for human
decision.

### 4. Community Adaptation And Versioning

Apply only required external-deployability adaptations. Review deployment Skill,
environment templates, migrations, and retained documentation. Propose the SemVer
bump, update `VERSION`, create the release file, and update
`docs/community/upstream-sync-log.md` without advancing the baseline prematurely.

### 5. Verification And Sync Review

Run the repository's required backend, frontend, build, deployment Skill,
dependency-boundary, and internal-reference gates. Independently compare the
upstream changed-file set with the final tree, review all overlaps, confirm
community boundaries, and account for deployment/documentation impact. Fix low or
medium findings; stop on high-risk findings.

### 6. Internal MR Handoff

Push the temporary internal branch and provide the Markdown-formatted MR link.
Post the standardized summary. Wait for a human to merge the MR; do not continue
from an unmerged branch as if it were the Community baseline.

### 7. Public GitHub Output

After confirming the internal MR is merged, fetch `upstream/master`, create a
temporary output branch from its exact commit, copy `community` into
`ai-sdlc/auto-wonder`, and verify byte-level tree consistency within documented
exclusions. Push to the `caihe-ch` fork.

### 8. Upstream PR Handoff

Provide a Markdown-formatted compare link for creating an upstream PR against
`aliyun/master`. Do not claim that a PR exists when only a creation link is
available. Post the standardized final summary and wait for human merge.

### 9. Release Finalization

After the upstream PR is merged, record the final commit and PR link. Propose the
annotated tag. Create or push a tag only with explicit human confirmation and
sufficient repository permission; lack of tag permission does not invalidate the
`VERSION` and release-file record.

## Standard Final Comment

The digital employee must post this structure with real values and Markdown
links. Omit no section; use `None` only when verified as not applicable.

```markdown
## 社区版本同步总结

### 发布信息
- 版本：`<old>` -> `<new>`
- Master 基线：`<previous>` -> `<target>`
- Community Commit：`<commit>`
- GitHub Upstream Base：`<commit>`
- 发布文件：[release_vX.Y.Z_YYYYMMDD.md](<release-file-url>)

### 功能更新
- <feature or fix>

### 社区适配
- <adaptation or None>

### 验证结果
- 后端：<result>
- 前端：<result>
- 部署 Skill：<result>
- 依赖与内部引用扫描：<result>
- Community/GitHub 目录一致性：<result>

### 风险总结
| 风险 | 等级 | 处理结论 |
| --- | --- | --- |
| <risk or None> | <Low/Medium/High> | <decision> |

### 交付链接
- [内部 Community MR](<mr-url>)
- [创建 GitHub Upstream PR](<compare-url>)

### 待真人操作
1. 审核并合并内部 MR。
2. 创建、审核并合并 GitHub Upstream PR。
3. 确认是否创建 `autowonder-community-vX.Y.Z` Tag。
```

## Acceptance

- The hosted GitHub fork belongs to organization `Autowonder 自迭代`.
- The digital employee, one-member squad, repository permission, and dedicated
  SDLC exist and are active.
- The SDLC encodes exact baseline sources, both temporary-branch flows, conflict
  tiers, human merge gates, version rules, release-file rules, and the final
  Markdown summary.
- The current `feat/autowonder-safe-upgrade-sop-20260806` output is updated to
  include initial version `0.1.0` and its release file.
- No step claims an MR/PR/tag was created when only a link or proposal exists.
