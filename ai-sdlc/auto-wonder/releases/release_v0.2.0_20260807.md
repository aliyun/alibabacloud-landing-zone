# AutoWonder Community 0.2.0

- Release date: 2026-08-07
- Version change: `0.1.0` -> `0.2.0`
- Bump rationale: add backward-compatible product capabilities and a new database migration
- Internal master baseline: `f858771adf3503ce947cb072ba4c7ddc20a609ef`
- Baseline merge commit: `8642ed54359eaee602a8fb2057e1ba5f32607f56`
- Release-parent Community commit: `8221b267cdecd5c63ec57f80f44780192bbdd913`
- Public repository base: `7af939921cf9e9ba16c0fa3546f1e9b6ad3ea316`
- Public output branch: `feat/autowonder-safe-upgrade-sop-20260806`

## Highlights

- Adds account deactivation with a seven-day cooling-off period and the matching UI.
- Adds repository deletion UI, clickable Agent cards, and persistent Qoder startup preferences.
- Adds MCP squad creation, default Agent SDLC configuration, dispatch pausing, and improved work-item guidance.
- Improves DingTalk sender context, IM notification scheduling, conversation capability stability, OSS logo handling, runtime traces, and work-item layout.
- Advances the recommended executor runtime to `0.2.125`.

## Community Adaptations

- Preserves the Qoder CLI-only executor creation boundary in the Community frontend.
- Keeps external OSS/SLS, SecretCrypto, optional Aone, and Linux x86_64 deployment configuration.
- Excludes internal daily/local credentials and development-only documents.
- Adds the account-deactivation DDL to both the fresh-install schema and `docs/migration/V036__user_account_deactivation.sql`.
- Updates deployment Skill defaults and contract tests to runtime `0.2.125`.

## Upgrade And Data Impact

- Existing installations must execute `docs/migration/V036__user_account_deactivation.sql` before starting this version.
- The migration adds nullable account-deactivation timestamps and does not rewrite existing user data.
- Fresh installations use the updated `docs/autowonder-schema.sql` and do not separately execute V036.
- No new required environment variable or external service is introduced.

## Verification

- backend: 1,788 tests passed;
- frontend: 84 test files and 524 tests passed;
- frontend lint: zero errors and two existing hook warnings;
- frontend production build: 4,761 modules transformed;
- deployment Skill: 74 tests passed;
- dependency, internal-domain, documentation-policy, migration, and Qoder-only boundary checks passed.

## Risk Summary

| Risk | Level | Control |
| --- | --- | --- |
| Existing databases lack the new user columns | Medium | Apply V036 before rolling application instances |
| Account deactivation changes authentication behavior | Medium | Covered by service, filter, controller, and frontend tests |
| Upstream supports additional executor clients | Low | Community tests retain the explicit Qoder CLI-only boundary |
| Release tag could imply an unmerged release | Low | Do not tag until the upstream PR is merged and a human confirms it |

## Handoff

- Internal Community branch is pushed directly for review under the requested sync workflow.
- [Create GitHub upstream PR](https://github.com/aliyun/alibabacloud-landing-zone/compare/master...caihe-ch:feat/autowonder-safe-upgrade-sop-20260806?expand=1)
- The link opens the upstream PR creation page; it does not indicate that a PR already exists.
