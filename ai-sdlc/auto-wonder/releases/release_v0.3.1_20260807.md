# AutoWonder Community 0.3.1

- Release date: 2026-08-07
- Version change: `0.3.0` -> `0.3.1`
- Bump rationale: backward-compatible work-item comment UI fix
- Internal master baseline: `3262a46f66ac5087261429250091bfea5a61d12b`
- Baseline merge commit: `802dbe507af1f3729ddd4c6ff6d6cddd15d38ffd`
- Release-parent Community commit: `802dbe507af1f3729ddd4c6ff6d6cddd15d38ffd`
- Public repository base: `7af939921cf9e9ba16c0fa3546f1e9b6ad3ea316`
- Previous public output commit: `c3ed8b188f4db217cc75330c607abf1e2de8616f`
- Public output branch: `feat/autowonder-safe-upgrade-sop-20260806`

## Highlights

- Limits the work-item comment mention menu height and enables vertical scrolling.
- Keeps all mention candidates available while preventing long lists from hiding the first entries.
- Adds focused component coverage for the scrollable mention menu.

## Community Adaptations

- None. Both upstream files are retained byte-for-byte.
- Existing external deployment and Qoder CLI-only boundaries are unchanged.

## Upgrade And Data Impact

- No database migration, environment variable, dependency, or service change.
- No deployment Skill or environment-template update is required.

## Verification

- backend: 1,828 tests passed;
- frontend: 85 test files and 526 tests passed;
- frontend lint: zero errors and two existing hook warnings;
- frontend production build: 4,766 modules transformed;
- deployment Skill: 74 tests passed;
- dependency, internal-domain, documentation-policy, deployment-impact, and feature-parity checks passed.

## Risk Summary

| Risk | Level | Control |
| --- | --- | --- |
| Mention items become inaccessible after height limiting | Low | Component test verifies all candidates remain rendered inside the scrollable menu |
| Community diverges from the upstream UI fix | Low | Both changed files are byte-identical to the synchronized master baseline |
| Release tag could imply an unmerged release | Low | Do not tag until the upstream PR is merged and a human confirms it |

## Handoff

- [Create GitHub upstream PR](https://github.com/aliyun/alibabacloud-landing-zone/compare/master...caihe-ch:feat/autowonder-safe-upgrade-sop-20260806?expand=1)
- The link opens the upstream PR creation page; it does not indicate that a PR already exists.
