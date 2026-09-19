# AutoWonder Community 0.9.0

## Version and upstream baseline

0.8.0 → 0.9.0 (MINOR): additive environment-variable management, worker bindings,
platform conversations, project backups, Feishu integration, capability categories,
executor upgrade/configuration and dispatch recovery capabilities.

- Recorded verified baseline: `25371cb104ac019fb26674f0c495c410c01e5041`.
- Previously merged master ancestor: `b52cdeeea3b82316ee370e56d5533e6a8d9245b3`.
- Target master: `d795eea3a8261a0fe22c2d2c8572d774699f3386`.
- Initial Community before sync: `b05c04c4a4c8eae7d50a7afeeddd1921e21aea17`.
- Resumed Community baseline: `8ecab515c9cfdd4fca7323e5af7aa1ce7db511e1`,
  including the independently reviewed E2E lifecycle prerequisite.
- Subsequent Community fixes integrated from
  `da378e982e548169c0894039086c1540aeae2a8c` (deployment/upgrade tooling).

## Features and fixes

Includes runtime-authoritative dispatch recovery, durable checkpoint/artifact
receipts, worker environment variables, Feishu channels, platform chief
conversations, project configuration backups, executor concurrency and update
controls, repository branch permissions, workitem watchers and view preferences,
capability categories and directory/ZIP uploads. Recommended runtime: 0.2.163.

## Community adaptations

Preserves SecretCrypto, public OSS/S3/SLS configuration, Qoder-only executor
creation and the complete pre-sync Community E2E tree. Internal authentication,
independent new Aone iterations and development history are excluded under the
sync guide. Upstream help screenshots containing internal identities, credentials
or deployment details were excluded with their references; tutorial text remains.
Product Skills and `.agents/` remain distributed. The downloadable tutorial ZIP
is repacked without workstation metadata; its functional Skill bytes are unchanged.

## Upgrade And Data Impact

Upgrade Runtime before Platform for environment-bound workers. Preserve the
existing encryption key and its protected off-node backup. Stop old application
nodes for conversation/schema changes; legacy conversation tokens may require
reissue. Verify an active system administrator before applying the initialization
migration. Existing encrypted values require the same key after restore.
See [environment variable operations](../docs/community/environment-variable-operations.md).

## DDL/DML/Migration Impact

Apply new Community migrations V052–V070 in order using the upgrade Skill after
backup and migration approval. These include Aone cursor columns retained for data
compatibility, workitem watchers, platform agent kind, executor launch/update
configuration, project backup/debug logs, dispatch recovery, Feishu/platform
conversations, last-started time, repository branch permissions, exclusive IM,
user settings, platform-admin initialization, capability categories, environment
variables and the executor-status index. Existing migrations remain unchanged.
The admin initialization migration writes its initialized marker; it does not
select or grant an administrator. Fresh installations import the full schema.

## Configuration and deployment impact

New optional `OSS_BACKUP_BUCKET` and executor auto-update YAML bindings are
included in the environment contract. The runtime recommendation derives from
application.yml. Optional Redis wait, notification retry and background scanner
tuning is also exposed without changing upstream defaults; see
[runtime configuration](../docs/community/runtime-configuration.md).
Feishu uses UI-managed encrypted credentials; Aone stays optional
and disabled. Upgrade tooling must verify protected environment identity at staging
and activation. No production deployment is performed by this source sync.

## Verification

Measured clean iteration `ef84938f2ddb05556fa245448c2e76048d335546` passed Maven
(4,654 tests; zero failures/errors; one manual Aone skip), frontend (2,122 tests),
production builds, deploy/upgrade offline suites and the final image lifecycle.
E2E: 14 task-specific API checks plus 39 authenticated checks passed; zero
unattributed ERROR/WARN. Cleanup passed after an unrelated port owner stopped.
Native Windows checks were skipped; five baseline development-dependency audit
findings remain, while the production audit is clear. Real cloud and populated
production upgrades were not executed.

Independent product/data and release-evidence reviews passed at that iteration;
boundary review identified tutorial ZIP metadata, repaired with a failing-then-
passing archive regression. See the [sync log](../docs/community/upstream-sync-log.md)
for run identity, ports, review disposition and concurrent Community integration.
The complete Community E2E tree remains unchanged. Final-SHA static gates, E2E
and three reviews must pass again after these follow-up changes; their authoritative
manifest and reports accompany the MR, without claiming earlier-SHA results as new.

## Risks and rollback

Migration DDL is not atomic. Take a verified database backup and protect the
matching encryption key. Application rollback after database mutation requires a
reviewed recovery plan. Before downgrading environment-variable-aware code,
finish bound work and verify zero active bindings; retain additive data and keys.

## MR/PR links

Internal MR: not created; quality gate pending.
GitHub PR: not created; requires internal MR approval and merge first.
Tag: skipped, not authorized.
