# AutoWonder Community 0.3.3

- Release date: 2026-08-07
- Version change: `0.3.2` -> `0.3.3`
- Bump rationale: backward-compatible upgrade safety fix
- Internal master baseline: `3262a46f66ac5087261429250091bfea5a61d12b`
- Release-parent Community commit: `feae125a0093b043e6e8c735eb61fdb1f4715aa6`
- Public repository base: `dd3e6d86dcfe74ddb83f6a8f6aa7259568a7f219`
- Public output branch: `fix/autowonder-upgrade-plan-safety-20260807`

## Fixes

- Makes the approved upgrade plan the only authority for deployment mutations.
- Requires upgrades to stop for human confirmation when execution becomes
  uncertain or differs from the approved plan.
- Removes automatic node rollback after a failed rolling activation and records
  that human resolution is required.

## Upgrade And Data Impact

- No application database, environment variable, infrastructure, or runtime
  contract change.
- Existing deployments are not modified by installing this Skill update.

## Verification

- Deployment Skill: 79 tests passed.
- Shell syntax and Git diff checks passed.

## Handoff

- [Create GitHub upstream PR](https://github.com/aliyun/alibabacloud-landing-zone/compare/master...caihe-ch:fix/autowonder-upgrade-plan-safety-20260807?expand=1)
