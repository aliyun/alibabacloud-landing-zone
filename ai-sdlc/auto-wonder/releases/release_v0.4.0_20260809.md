# AutoWonder Community 0.4.0

- Release date: 2026-08-09
- Version change: `0.3.5` -> `0.4.0`
- Bump rationale: backward-compatible S3 storage and user-facing feature additions
- Internal master baseline: `d5e36283e513c86a92241c50bb84bd129bf02f20`
- Release-parent Community commit: `204ce5382e7c677e464e5bc799c02e31d6f9ac5b`
- Community merge commit: `0c03717b14fb955d67a8a01ea066289d777848d0`
- Public repository base: `811645c96c7af117be5de158ae773b0ada6e6014`
- Public output branch: `fix/autowonder-log-retention-20260809`

## Features

- Adds an optional standard S3-compatible storage backend for self-managed
  deployments while retaining OSS as the Alibaba Cloud deployment default.
- Shows the deployed Community version on the About page.
- Adds Markdown and plain-text copy actions to work-item content and comments.
- Includes the bounded local-log retention fix in the full master baseline.

## Community Adaptations

- Keeps SecretCrypto, external OSS/SLS configuration, optional Aone, Linux
  x86_64 packaging, domain-neutral branding, and Qoder-only executor creation.
- Keeps persistent object storage mandatory and does not restore the in-memory
  production fallback.
- Seals `VERSION` into the deployment manifest and writes
  `AUTOWONDER_VERSION` into the protected runtime environment file.
- Prevents deployment-managed environment values from becoming false blockers
  during pre-generation upgrade planning.

## Upgrade And Data Impact

- No new database migration; immutable V036 and V037 already cover the target
  schema additions and remain unchanged.
- No Terraform or Alibaba Cloud resource change.
- Rebuild the full JAR with frontend assets and perform a rolling restart.
- Existing deployment automation writes the new version variable; users do not
  need to invent or manually maintain its value.

## Verification

- Maven production build and 1,854 backend tests passed.
- 89 frontend test files and 547 tests passed; lint had zero errors.
- 82 deployment Skill tests passed.
- Dependency and active-runtime internal-reference scans passed.

## Handoff

- [Create GitHub upstream PR](https://github.com/aliyun/alibabacloud-landing-zone/compare/master...caihe-ch:fix/autowonder-log-retention-20260809?expand=1)
