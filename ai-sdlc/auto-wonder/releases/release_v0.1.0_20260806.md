# AutoWonder Community 0.1.0

- Release date: 2026-08-06
- Version change: none -> `0.1.0`
- Bump rationale: establish the first versioned Community distribution and its release contract
- Internal master baseline: `58140e68a741d29133d77fc05427c1174b9247a4`
- Baseline merge commit: `1e9222ef91e4a2320d391910032fe72a82c8c85d`
- Release-parent Community commit: `fb0393c651791fd699eedefc95fa20038c4acd10`
- Public output branch: `feat/autowonder-safe-upgrade-sop-20260806`

## Highlights

- Provides the complete Community AutoWonder application, frontend, database initialization, deployment Skill, and operational references.
- Retains the Community boundary: replace internal crypto and platform dependencies, keep OSS and SLS as supported public cloud services, and expose only Qoder CLI executor creation in the frontend.
- Separates OSS service and public endpoints so private ECS services can use an internal endpoint while browsers and external runtimes receive public signed URLs.
- Includes AI clarification HTTP compatibility, streaming Markdown rendering, permission isolation fixes, password management, Agent capability binding, and runtime `0.2.117` defaults synchronized from the internal product baseline.
- Includes the standard squad templates, visual onboarding guides, ALB-oriented deployment guidance, and a safe autonomous application-upgrade SOP.
- Defines a dedicated Community synchronization and release engineer contract with conflict tiers, independent sync review, human merge gates, SemVer decisions, and standardized MR/PR handoff output.

## Community Adaptations

- Alibaba-internal KMS and mandatory internal platform dependencies remain excluded from the Community runtime.
- Community deployment requires configured OSS service/public endpoints and includes frontend assets in the production JAR.
- Internal-only design records and development-process documents remain excluded under the Community documentation policy.
- Public output is maintained in `ai-sdlc/auto-wonder` within the `aliyun/alibabacloud-landing-zone` repository model.

## Upgrade And Data Impact

- This is the initial Community version; there is no prior Community release to upgrade from.
- Initial installation uses `docs/autowonder-schema.sql` and `docs/autowonder-community-templates.sql`.
- Future DDL/DML changes must be delivered as ordered files under `docs/migration/` and reviewed before rolling application restart.
- Deployment secrets and credentials are environment inputs and are not included in this release record.

## Verification

The synchronized baseline recorded these passing gates:

- backend: 1,746 tests;
- frontend: 84 test files and 513 tests;
- deployment Skill: 52 contract tests;
- production JAR: frontend static assets present;
- dependency and configuration scan: no prohibited internal runtime dependency or domain;
- Community executor UI: Qoder CLI-only;
- runtime default: `0.2.117`.

Release-specific version assertions, repository diff checks, deployment Skill tests, and public subtree parity are rerun before the output branch is pushed.

## Risk Summary

| Risk | Level | Control |
| --- | --- | --- |
| First Community version has no prior upgrade baseline | Medium | Treat `0.1.0` as a fresh-install baseline and require migration files for later releases |
| Internal master can continue changing after this release | Medium | Read and validate the exact baseline in `docs/community/upstream-sync-log.md` before every sync |
| Public output is a subtree of a larger repository | Medium | Base output on exact upstream `master`, checksum the copied subtree, and use an upstream compare link |
| Release tag could imply an unmerged release | Low | Do not create `autowonder-community-v0.1.0` until the upstream PR is merged and a human explicitly confirms tagging |

## Handoff

- Internal review: the Community branch is pushed for review; protected-branch merge remains a human action.
- [Create GitHub upstream PR](https://github.com/aliyun/alibabacloud-landing-zone/compare/master...caihe-ch:feat/autowonder-safe-upgrade-sop-20260806?expand=1)
- The link above opens the upstream PR creation page; it is not evidence that a PR already exists.
