# Release v0.7.0

- Version: `0.6.0` → `0.7.0`
- Bump rationale: MINOR (pre-1.0). Substantial new features — 7×24 scheduled
  digital-worker tasks with their own tables and staged rollout switches,
  workspace discovery with access requests, encrypted-at-rest MCP configuration,
  path-token MCP endpoints, per-step token/credit usage, and a reworked
  clarification conversation. No public REST path was removed or renamed.
- Previous master baseline: `985998be3c803be1973d0bebc16d009e7a46b122`
- New master baseline: `25371cb104ac019fb26674f0c495c410c01e5041`
- Release-parent Community commit: `51ff353dee2e5fcfd05e1e186cbeb6e03a5265b0`
- Community merge commit: `6db0fad6906e6892258f9ebf4ba8cc0760617f10`
- Scope: 405 upstream commits (312 non-merge), 477 upstream changed paths,
  463 final changed paths against the previous community tip.

Note on the `VERSION` file: release v0.6.0 declared the bump `0.5.0` → `0.6.0`
but the `VERSION` file was never updated and stayed at `0.5.0` in both the
community branch and the external repository. This release writes `0.7.0` and
closes that gap.

## Feature Summary

- **7×24 scheduled digital-worker tasks.** New `scheduled_task` and
  `scheduled_task_run` tables, cron scanning with lease-based claiming, run
  detail and delivery-progress views, derived work items, run artifacts and
  comments. Execution sources become source-aware across `dispatch`,
  `artifact`, `workitem_comment`, `workitem_comment_mention` and
  `workitem_comment_delivery`. The whole module is gated behind three
  start-up switches that default to off.
- **Workspace discovery and access requests.** A new `workspace_access_request`
  table, an all-workspaces discovery tab, request submission, withdrawal,
  approval and rejection, an approval panel in members management, plus in-app
  and DingTalk notifications with per-recipient isolation.
- **Encrypted MCP configuration.** Private MCP header and environment values are
  stored as ciphertext references instead of plaintext, resolved only when a task
  package or conversation capability snapshot is built. MCP connection discovery
  and versioned connection-test results were added.
- **MCP endpoints accept a path token.** `/api/mcp/<token>/` is now supported
  alongside the query-token form, and the auth filter no longer rejects it.
  DISPATCH and conversation credentials can mint CLI upload tokens.
- **Requirement clarification rework.** Full-screen toggle, resizable input,
  response indicators, stop-response with cancel frames and timeouts, polling
  fallback when a terminal event is lost, and fixes for duplicate streamed
  bubbles and scroll following.
- **Per-step token usage.** `dispatch_ai_usage` gains `step_id`, reasoning
  tokens and credits, with per-step aggregation surfaced in delivery progress.
- **Work item scheduling.** Work items accept a scheduled start time and tags,
  show a persistent scheduled-execution badge in list, kanban and detail views,
  and degrade a past scheduled start to immediate delivery.
- **Other.** Requirement document upload accepts `.txt`/`.html`/`.pdf`; repo
  detail supports partial edits with explicit-null clearing; skill soft delete
  releases its unique name; workspace list cache is isolated per account.
- Recommended executor runtime advances from `0.2.138` to `0.2.150`.

## Community Adaptations

- **BUC (internal unified authentication) excluded entirely.** The upstream
  `buc.sso.client.plugin-sdk` and `buc-spring-boot-starter-mini` dependencies,
  the `spring.buc` block in `application.yml`, the BUC `AsyncLogger` entries in
  `log4j2.xml`, and the `src/main/resources/disable-pandora-buc-sso-client`
  marker are all absent. Internal environment profiles that carried BUC
  configuration remain excluded.
- **KeyCenter replaced by `SecretCrypto`.** Master introduced a new
  `KeyCenterClient` abstraction for the encrypted MCP configuration feature.
  Community binds the same encrypt/decrypt/mask contract to its existing
  `SecretCrypto`, so `WsDispatchTransport`, `SkillService`,
  `SkillConnectionTestService` and `ConversationCapabilityService` keep the
  upstream behavior without the KeyCenter SDK. `PlatformKeyCenterClient` and its
  test are not published.
- **`fastjson2` declared explicitly.** The scheduled-task code imports
  `com.alibaba.fastjson2`, which master receives transitively from internal
  SDKs that community removes. Community declares
  `com.alibaba.fastjson2:fastjson2:2.0.58` from Maven Central.
- **`autowonder.community-edition` defaults to `true`.** Upstream added this
  flag defaulting to `false`; this distribution is the community edition, so the
  MCP token panel shows only the Qoder client snippet, consistent with the
  existing Qoder-only executor boundary.
- **Migrations renumbered** into the community sequence as V041–V048.
- **`docs/openapi-reference.md`** keeps the `https://{aone-host}` placeholder
  instead of the internal Aone hostname, and the new
  `src/test/resources/schema/autowonder-pre-v037.sql` fixture seeds a `NULL`
  branding domain to match `docs/autowonder-schema.sql`.
- **Development working notes** under `docs/superpowers/` and the internal
  configuration checklist remain excluded per the documentation policy.

## Upgrade And Data Impact

Backward-compatible with v0.6.0 for existing deployments, provided the staged
rollout in `docs/scheduled-task-operations.md` is followed.

The scheduled-task schema change is the only sensitive part. `V041` adds a
`source_type` column and a stored generated column plus a new unique key to the
`dispatch` table, and adds source-aware columns and indexes to `artifact`,
`workitem_comment`, `workitem_comment_mention` and `workitem_comment_delivery`.
Ordinary work items keep working before, during and after the change because
every added column carries a compatible default.

`AUTOWONDER_SCHEDULED_TASK_ENABLED`, `AUTOWONDER_SCHEDULED_TASK_SCANNER_ENABLED`
and `AUTOWONDER_SCHEDULED_TASK_CLUSTER_READY` all default to `false` and are
frozen at bean construction, so they are not hot-reloadable. Do not set
`AUTOWONDER_SCHEDULED_TASK_CLUSTER_READY=true` until every node serving traffic
reports the new schema mode. An upgrade that applies the migrations and leaves
all three switches at `false` changes no existing behavior.

`V046` drops the `uk_dispatch_provider_model` unique key on
`dispatch_ai_usage` and replaces it with `uk_dispatch_step_provider_model`,
which additionally includes `step_id`. Existing rows receive `step_id = ''`.

No DML or manual data action is required.

## DDL/DML/Migration Impact

New migrations, applied in this order:

| File | Change |
| --- | --- |
| `docs/migration/V041__scheduled_task.sql` | Creates `scheduled_task` and `scheduled_task_run`; adds source-aware columns, indexes and the normalized idempotency generated column to `dispatch`, `artifact`, `workitem_comment`, `workitem_comment_mention`, `workitem_comment_delivery`; adds `origin_type`/`origin_id` to `workitem` |
| `docs/migration/V042__skill_description_2048.sql` | Widens `skill.description` to `VARCHAR(2048)` |
| `docs/migration/V043__ai_usage_credits_reasoning.sql` | Adds `reasoning_tokens` and `credits` to `dispatch_ai_usage` |
| `docs/migration/V044__workitem_scheduled_start_and_tags.sql` | Adds `scheduled_start_at` and `tags` to `workitem` |
| `docs/migration/V045__workitem_scheduled_start_triggered_at.sql` | Adds `scheduled_start_triggered_at` to `workitem` |
| `docs/migration/V046__ai_usage_step_id.sql` | Adds `step_id` to `dispatch_ai_usage`; replaces the provider/model unique key with a step-aware one |
| `docs/migration/V047__skill_soft_delete_release_unique_name.sql` | Releases the skill name placeholder on soft delete |
| `docs/migration/V048__workspace_access_request.sql` | Creates `workspace_access_request` |

`docs/autowonder-schema.sql` mirrors all of the above for fresh installations.
No previously published migration (V036–V040) was modified, renamed or deleted.
No DML is required.

## Configuration And Deployment Impact

New keys, all recorded in `docs/community/application.env.example`:

| Key | Environment variable | Default |
| --- | --- | --- |
| `autowonder.community-edition` | `AUTOWONDER_COMMUNITY_EDITION` | `true` (community) |
| `autowonder.scheduled-task.enabled` | `AUTOWONDER_SCHEDULED_TASK_ENABLED` | `false` |
| `autowonder.scheduled-task.scanner-enabled` | `AUTOWONDER_SCHEDULED_TASK_SCANNER_ENABLED` | `false` |
| `autowonder.scheduled-task.cluster-ready-attestation` | `AUTOWONDER_SCHEDULED_TASK_CLUSTER_READY` | `false` |
| `autowonder.scheduled-task.scan-fixed-delay-ms` | `AUTOWONDER_SCHEDULED_TASK_SCAN_FIXED_DELAY_MS` | `10000` |
| `autowonder.scheduled-task.scan-batch-size` | `AUTOWONDER_SCHEDULED_TASK_SCAN_BATCH_SIZE` | `100` |
| `autowonder.scheduled-task.lock-ttl-seconds` | `AUTOWONDER_SCHEDULED_TASK_LOCK_TTL_SECONDS` | `30` |

`AUTOWONDER_AONE_WEB_BASE_URL` was introduced by the previous sync but had never
been listed in the environment inventory; it is now recorded with an empty
default.

The recommended runtime version is a deployment contract, so `0.2.150` was
propagated to the deployment manifest template and to the deployment and upgrade
Skill tests. No topology, credential, port, endpoint, database or
environment-template change is required. Because every new switch defaults to
off, no deployment input collection change is required.

The executable bit was restored on the 25 Skill shell scripts. They had been
committed as `100644` when the Skills were consolidated under `skills/`, which
made them unrunnable for operators after a fresh clone.

## Verification Results

- Maven `clean verify`: BUILD SUCCESS — 2652 tests, 0 failures, 0 errors,
  1 skipped.
- Frontend: 116 test files, 901 tests passed, 1 skipped; ESLint 0 errors with
  3 pre-existing hook warnings; production build succeeded.
- Deployment Skill: 70 passed, 31 failed. Upgrade Skill: 66 passed, 6 failed,
  1 skipped. Both failure sets are byte-for-byte identical on the previous
  community tip `51ff353d`, so this release changed neither. They are stale
  assertions against scripts whose content moved into
  `scripts/internal/release-transfer.sh` and remain open work.
- Dependency tree contains no KeyCenter, Normandy, AkLess, RASS, BUC or
  `log4j:log4j` artifact; the internal-reference scan from
  `docs/community/verification.md` returns nothing.
- Migration immutability, baseline ancestry and schema parity all pass.

Six test failures inherited from upstream were root-caused and fixed here; the
most consequential was a clock-dependent seed in the scheduler integration suite
that made `scan()` claim nothing once the wall clock passed the seeded fire
times. See `docs/community/upstream-sync-log.md` for the full record.

Real OSS/SLS credentials were not available locally, so the object-storage
upload/presign and SLS delivery checks remain release-environment acceptance
items rather than passed gates.

## Risks

- The scheduled-task rollout is the main risk. Applying `V041` is a schema change
  on hot tables; follow `docs/scheduled-task-operations.md` and keep all three
  switches off until the whole cluster is upgraded.
- `V046` replaces a unique key on `dispatch_ai_usage`. Verify the index swap on a
  shadow database first if that table is large.
- Community now declares `fastjson2` directly, so it must be reviewed for
  advisories independently of the internal SDKs that used to supply it.
