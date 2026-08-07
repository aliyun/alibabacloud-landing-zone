# AutoWonder Community 0.3.2

- Release date: 2026-08-07
- Version change: `0.3.1` -> `0.3.2`
- Bump rationale: backward-compatible deployment Skill fix
- Internal master baseline: `3262a46f66ac5087261429250091bfea5a61d12b`
- Release-parent Community commit: `ad0723fce920e46bf129956e60f65aceae9ca970`
- Public repository base: `7af939921cf9e9ba16c0fa3546f1e9b6ad3ea316`
- Previous public output commit: `303f2bb2a93b1243c93014f3301f95631869b85d`
- Public output branch: `feat/autowonder-safe-upgrade-sop-20260806`

## Fixes

- Allows upgrade planning and release builds when AutoWonder is located in a monorepo subdirectory.
- Normalizes Git tree and diff paths to the AutoWonder project root.
- Accepts existing zero-padded migration versions such as V036 and V037.
- Preserves the AutoWonder project prefix when creating a detached target worktree.

## Upgrade And Data Impact

- No application database, environment variable, infrastructure, or runtime change.
- Existing V036 and V037 files are unchanged; only Skill discovery and validation are corrected.

## Verification

- deployment Skill: 77 tests passed;
- monorepo planner regression detected V036 and V037 from the real landing-zone history;
- monorepo release-build regression and shell syntax checks passed.

## Handoff

- [Create GitHub upstream PR](https://github.com/aliyun/alibabacloud-landing-zone/compare/master...caihe-ch:feat/autowonder-safe-upgrade-sop-20260806?expand=1)
