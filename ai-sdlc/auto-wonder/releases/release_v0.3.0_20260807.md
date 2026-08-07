# AutoWonder Community 0.3.0

- Release date: 2026-08-07
- Version change: `0.2.0` -> `0.3.0`
- Bump rationale: add a backward-compatible human-agent participation insights capability
- Internal master baseline: `484a30c19f99dd401b1f1b1cf66b11154d0484ca`
- Baseline merge commit: `e9184184d79ac565f40e3795d960c06c8d716b6b`
- Release-parent Community commit: `b079b2d4a8bdc6bba2e7a10d47e99ce50971d601`
- Public repository base: `7af939921cf9e9ba16c0fa3546f1e9b6ad3ea316`
- Public output branch: `feat/autowonder-safe-upgrade-sop-20260806`

## Highlights

- Adds T-1 human-agent participation insights with ratio and duration trends, a duration breakdown, and slow-tail details.
- Reconstructs participation from work-item lifecycle events and preserves assignment ownership types for future facts.
- Adds nightly snapshots, cache-only APIs, force refresh, distributed single-flight coordination, and paginated event loading.
- Infers historical assignment target types and stops duration accumulation after terminal events.

## Community Adaptations

- Renumbers the new migration from upstream V036 to Community V037 because Community V036 is already immutable.
- Adds both participation indexes to the fresh-install schema and keeps the V037 SQL body identical to master.
- Keeps external OSS/SLS, SecretCrypto, optional Aone, Linux x86_64, and Qoder CLI-only executor boundaries unchanged.
- Retains all upstream product source without behavior changes.

## Upgrade And Data Impact

- Existing installations must execute `docs/migration/V037__human_agent_participation_indexes.sql` before starting this version.
- Fresh installations use the updated `docs/autowonder-schema.sql` and do not separately execute V037.
- The migration adds two indexes and does not rewrite business data.
- No new required environment variable or external service is introduced; participation scheduling and cache settings have defaults.

## Verification

- backend: 1,828 tests passed;
- frontend: 84 test files and 524 tests passed;
- frontend lint: zero errors and two existing hook warnings;
- frontend production build: 4,766 modules transformed;
- deployment Skill: 74 tests passed;
- dependency, internal-domain, documentation-policy, migration, and Qoder-only boundary checks passed.

## Risk Summary

| Risk | Level | Control |
| --- | --- | --- |
| Existing databases lack participation indexes | Medium | Apply V037 before rolling application instances |
| Historical assignment events can omit ownership type | Low | Covered by explicit fallback and lifecycle reconstruction tests |
| Snapshot refresh is expensive on large histories | Low | Pagination, cache-only reads, distributed single-flight, and bounded refresh workers |
| Release tag could imply an unmerged release | Low | Do not tag until the upstream PR is merged and a human confirms it |

## Handoff

- Internal Community branch is pushed directly for review under the requested sync workflow.
- [Create GitHub upstream PR](https://github.com/aliyun/alibabacloud-landing-zone/compare/master...caihe-ch:feat/autowonder-safe-upgrade-sop-20260806?expand=1)
- The link opens the upstream PR creation page; it does not indicate that a PR already exists.
