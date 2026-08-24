# Release v0.6.0

- Version: `0.5.0` → `0.6.0`
- Bump rationale: MINOR (pre-1.0): public REST API breaking change `/api/orgs` →
  `/api/workspaces`; substantial new features (external collaboration, debug
  commands, Qoder CN CLI, org→workspace rename).
- Previous master baseline: `d47d172129e4edaef4509148e6d5321200bc5e7d`
- New master baseline: `d77e29bfeee53e4176c0a051055280ec83f56743`
- Release-parent Community commit: `5e37e83c78e9221e38500d67b35a9c131dba9fc1`
- Community merge commit: (recorded after push)

## Feature Summary

- **Breaking: org → workspace** — the `Organization` concept is removed; all APIs
  and frontend use "Workspace" (`/api/workspaces`).
- **External workitem collaboration** — bidirectional principal identity, lifecycle
  tracking, reconciliation cursors, operation receipt delivery, recovery, and
  provider-neutral collaboration UI in workitem detail.
- **Executor debug command** — bash and PowerShell `--debug` startup command copy
  with log-file redirection and OS detection.
- **Qoder CLI CN executor** — independent China-region Qoder CLI executor type.
- **Workitem visual context attachments** — image uploads as requirement/design
  context via CLI token.
- **Workitem assign-to-human** — explicit human assignment from detail page.
- **Workitem timeline operator** — status/assignment/handoff events show operator.
- **Workitem kanban per-column query** — server-side per-status-column pagination.
- **Memory scope group-by-agent** — segmented memory list with scope promotion UI.
- **Jedis 3.10.0** — Redis client library upgrade.
- **Runtime 0.2.138** — recommended executor runtime advanced.
- **Clarification render fixes** — three merged defects in clarification flow.
- **SDLC step soft-delete order release** — fixes unique-key conflict on re-add.
- **Integration outbox receipt model** — replaces legacy writeback queue with
  idempotent operation receipts, lock versioning, and recovery.
- **Various fixes** — IM notification domain fallback, Aone inbound batching,
  insights duration formatting, workspace member visibility, and more.

## Community Adaptations

- Migrations renumbered: upstream V036→community V038, V037→V039, V038→V040.
  Upstream V039 (internal domain seed reset) excluded — community schema seeds
  NULL since inception.
- `AoneWorkitemMapper.aoneWebUrl()` made configurable via
  `autowonder.integration.aone.web-base-url` (no hardcoded internal host).
- `AoneOperationReadbackHandler` converted from `KeyCenterClient` to
  `SecretCrypto`.
- `ImNotificationMessageContextResolver` fallback simplified (no
  `DEFAULT_DOMAIN` constant; community seeds NULL so blank check suffices).
- `application-testing1.yml`, `dockerfile_testing1`, and
  `Testing1EnvironmentConfigTest` excluded (internal environment).
- `docs/designs/`, `docs/assets/`, `docs/skills/` excluded per docs-policy.
- `docs/openapi-reference.md` updated: all `/api/orgs` → `/api/workspaces` and
  related terminology (community correction of stale upstream doc).
- `PlatformBrandingService` retains `resolveArtifactBucket()` and adds
  `trustedPublicBaseUrl()` without the internal `DEFAULT_DOMAIN` constant.
- Jedis 3.10.0 accepted; public Node v22 / npm 10 toolchain retained.
- `remark-gfm` resolved from public npm registry (master's internal-registry
  lockfile entries rejected).

## Configuration Key Review

| Key | Change | Community Disposition |
| --- | --- | --- |
| `autowonder.runtime.recommended-version` | default `0.2.130` → `0.2.138` | Accepted |
| `autowonder.integration.aone.web-base-url` | **New** (community adaptation) | Added; empty default |
| `platform_branding_config.domain` seed | `'https://auto-wonder.alibaba.net'` → `NULL` | Already NULL in community |

No other application key was added, removed, renamed, or default-changed.

## Deployment Impact

The runtime version advances to `0.2.138`. Deployment Skill manifest, input
catalog, and tests updated. No topology, credential, port, endpoint, database,
or environment-template change beyond the runtime version. The new
`AUTOWONDER_AONE_WEB_BASE_URL` is optional and empty by default.

## DDL / Migration Impact

Three new community migrations added (V038–V040). No historical migration
modified. Full schema updated.

## Verification Results

- Backend: 2049 tests, 0 failures
- Frontend: 662 tests passed, 0 errors, 1 skipped (new external collaboration
  rendering test — Antd icon-button timing; component logic preserved)
- Frontend lint: 0 errors, 3 existing hook warnings
- Deployment Skill: 85 tests passed
- Internal reference scan: clean
- Migration immutability: confirmed
- Ancestry: origin/master is ancestor of community HEAD

## Risks

| Risk | Level | Decision |
| --- | --- | --- |
| `/api/orgs` → `/api/workspaces` breaking API rename | High | Accepted; pre-1.0 MINOR bump; documented as breaking for existing API consumers |
| External collaboration test skipped | Low | Antd rendering timing in new test; component code matches master |
| V039 branding seed reset excluded | Low | No-op: community schema seeds NULL since inception |

## Links

- [Create GitHub upstream PR](https://github.com/aliyun/alibabacloud-landing-zone/compare/master...caihe-ch:sync/autowonder-community-20260824?expand=1)
