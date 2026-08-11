# AutoWonder Community 0.5.0

- Release date: 2026-08-11
- Version change: `0.4.0` -> `0.5.0`
- Bump rationale: backward-compatible delivery, lifecycle, and operator features
- Internal master baseline: `d47d172129e4edaef4509148e6d5321200bc5e7d`
- Release-parent Community commit: `de5913a1944a8d3bb7255f66f6c7cb303ca7f22b`
- Community merge commit: `6f14874601a30c5c9db12433c4efe852c29912ca`
- Public repository base: `79838ade917d0c67d652eec2a288925679b99513`
- Public output branch: `sync/autowonder-community-20260811`

## Features

- Highlights work items assigned to humans across list, kanban, and detail views.
- Cleans published-workitem workspaces after a three-day retention period.
- Supports Unicode artifact paths.
- Reports SDLC deletion references and assignment guidance more precisely, with
  fallback to the work-item type's default SDLC.
- Clarifies memory MCP arguments and recommends executor runtime `0.2.130`.

## Community Adaptations

- Retains SecretCrypto, external OSS/S3/SLS configuration, optional Aone, Linux
  x86_64 packaging, domain-neutral branding, and Qoder-only executor creation.
- Updates the deployment manifest and input contract to executor runtime
  `0.2.130`.
- Fixes invalid regex escapes detected by the frontend lint gate without changing
  badge matching behavior.

## Upgrade And Data Impact

- No database migration or schema change.
- No Terraform, cloud-resource, environment-variable, or ingress change.
- Rebuild the complete JAR with frontend assets and perform a rolling restart.

## Verification

- Maven production build and 1,871 backend tests passed.
- 90 frontend test files and 586 tests passed; lint had zero errors.
- 85 deployment Skill tests passed.
- Sync-difference, dependency, migration, and internal-reference audits passed.

## Handoff

- [Create GitHub upstream PR](https://github.com/aliyun/alibabacloud-landing-zone/compare/master...caihe-ch:sync/autowonder-community-20260811?expand=1)
