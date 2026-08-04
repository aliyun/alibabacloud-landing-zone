# MCP Memory Management

AutoWonder MCP exposes the server memory store directly to agents. An agent can write, retrieve, correct, and retire memories through MCP tool calls while it is thinking or executing, instead of writing a `learning_delta/memory_delta.json` file and relying on artifact upload.

The legacy learning-delta path is untouched and keeps running in parallel; the two write paths are distinguished by the memory `source` field (`MCP` vs `LEARNING_DELTA`).

## Where the tools live

The memory tools are part of the existing built-in `autowonder` MCP server, so no extra MCP server registration and no client-side configuration change is needed. During a dispatch the runtime already renders the `autowonder` entry into `mcp.json` and injects the dispatch bearer token, so the tools appear in `tools/list` automatically.

- Endpoint: `POST <publicBaseUrl>/api/mcp` (JSON-RPC, streamable HTTP)
- Auth: `Authorization: Bearer <token>` or `?token=<token>`

## Credential model

| Credential | How memories are attributed | Visibility and mutation limits |
|---|---|---|
| Dispatch token (`awdispatch_…`, issued per dispatch) | Server derives `agentId`, `workitemId`, and `dispatchId` from the credential. Memories are always `AGENT`-scoped and owned by that agent. | Can read `SQUAD` / `ORG` memories plus its own `AGENT` memories. Can only write, update, deprecate, or delete `AGENT` memories it owns; cannot create `SQUAD` / `ORG` memories. |
| Long-lived token (`awmcp_…`, issued by a user) | Behaves like the `/api/memories` web path: `source=MANUAL`, `status=PENDING`, creator is the token owner. | `scope` is required and may be `AGENT`, `SQUAD`, or `ORG`. Not restricted to a single agent. |

Provenance is never taken from tool arguments. A dispatch caller that passes `ownerRef` has it ignored in every scope, so an agent cannot attribute a memory to a different agent, squad, or organization. Widening a memory beyond its author is deliberately a human decision: a reviewer promotes an adopted `AGENT` memory to `SQUAD` or `ORG` through `POST /api/memories/{id}/review`. A dispatch credential that passes `scope=SQUAD` or `scope=ORG` is rejected with `27003` rather than silently downgraded, so the agent gets actionable feedback.

## Lifecycle

A memory written through MCP or the web page starts at `status=PENDING`, exactly like a learning-delta memory. It becomes reusable only after a human adopts it at `/memories/reviews` (`POST /api/memories/{id}/review`). Adoption resolves the reviewed `AGENT`, `SQUAD`, or `ORG` scope, attaches the memory to each affected digital worker's editable version, and submits draft versions for review. The memory enters future dispatch packages only after that worker version is approved. `search_memories` therefore defaults to `status=ADOPTED`.

Statuses: `PENDING` → `ADOPTED` or `REJECTED`. `deprecate_memory` moves a memory to `REJECTED` from any status, including `ADOPTED`.

## Idempotency

`create_memory` builds a dedupe key of `dispatch:<dispatchId>:mcp:<key>`, where `<key>` is the caller's `idempotencyKey` or a SHA-256 of `title` + `contentMd`. Creation then resolves that key before writing, so a retried or resumed step never duplicates a memory and never overwrites a review decision:

| Existing row for the key | Behaviour |
|---|---|
| none | insert a new `PENDING` memory |
| `status=PENDING` | update `title` / `contentMd` / `type` in place, still `PENDING` |
| `status=ADOPTED` or `REJECTED`, identical `title` and `contentMd` | return the stored memory unchanged — a genuine idempotent retry, nothing is written |
| `status=ADOPTED` or `REJECTED`, different content | refused with `MEMORY_ALREADY_REVIEWED` (21006) |

The last row is what makes the guarantee in **Lifecycle** hold. Because the dedupe key is caller-supplied, a blind upsert would let an agent replace the body of an already adopted memory while it kept its `ADOPTED` status — unreviewed content would then be distributed to other digital workers with no audit trail. To change an adopted memory, use `update_memory` (which is explicit and audited) or write a new memory with a fresh `idempotencyKey`.

## MCP tools

### `autowonder.create_memory`

Input:

```json
{
  "title": "MyBatis 动态 keyword 检索",
  "contentMd": "列表查询加关键词过滤时用 `CONCAT('%', #{keyword}, '%')` 参数化 LIKE，不要拼接 `${}`。",
  "type": "BEST_PRACTICE",
  "scope": "AGENT",
  "idempotencyKey": "step-400165-mybatis-like"
}
```

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| title | string | 是 | 记忆标题 |
| contentMd | string | 否 | Markdown 正文，写可复用结论而非本次任务过程 |
| type | string | 否 | 如 `PITFALL` / `BEST_PRACTICE` / `CONSTRAINT` / `TOOL_USAGE` / `DOMAIN` |
| scope | string | 否 | dispatch 令牌只能用 `AGENT`（默认值），传 `SQUAD` / `ORG` 报 27003；长效令牌必填，可用 `AGENT` / `SQUAD` / `ORG` |
| ownerRef | integer | 否 | 归属对象 ID；dispatch 令牌下任何 scope 均被忽略 |
| idempotencyKey | string | 否 | 幂等键，缺省时按 `title` + `contentMd` 求哈希；记忆已被采纳/驳回后复用同键改内容会被拒绝 |

Output is the stored memory row, so `status` / `version` / `gmtCreate` / `gmtModified` always reflect the database rather than pre-insert defaults. A newly created memory has `status=PENDING`, `source=MCP`, and `sourceRef` carrying provenance:

```json
{
  "id": 10231,
  "scope": "AGENT",
  "ownerRef": 40013,
  "type": "BEST_PRACTICE",
  "title": "MyBatis 动态 keyword 检索",
  "contentMd": "…",
  "status": "PENDING",
  "source": "MCP",
  "sourceRef": "{\"dispatchId\":11226,\"workitemId\":28559,\"agentId\":40013}",
  "version": 0,
  "gmtCreate": "2026-08-03 12:00:00",
  "gmtModified": "2026-08-03 12:00:00"
}
```

### `autowonder.search_memories`

Input (all fields optional):

```json
{ "keyword": "MyBatis", "type": "BEST_PRACTICE", "page": 1, "size": 20 }
```

| 参数 | 类型 | 默认 | 说明 |
|---|---|---|---|
| keyword | string | — | 对 `title` 与 `contentMd` 做参数化 LIKE 匹配；`%` / `_` / `\` 会被转义为字面量，不会当作通配符 |
| scope | string | 全部 | `AGENT` / `SQUAD` / `ORG` |
| ownerRef | integer | — | 归属对象 ID，作为普通筛选条件；不影响可见性约束 |
| type | string | 全部 | 记忆类型 |
| status | string | `ADOPTED` | `PENDING` / `ADOPTED` / `REJECTED` |
| page | integer | 1 | 页码 |
| size | integer | 20 | 每页大小，上限 100 |

Output is `{ "items": [ <memory>, … ] }`.

For a dispatch credential the visibility restriction is enforced as a SQL predicate (`scope <> 'AGENT' OR owner_ref = <callerAgentId>`) that is applied **before** `LIMIT`. Pagination therefore operates on the set the caller may actually see: another agent's `AGENT`-scoped memories can never consume a page and leave the caller with a silently empty or short result. The Java-side filter is retained only as defence in depth.

Known limitation: `contentMd` is `MEDIUMTEXT` with no supporting index, so a leading-wildcard `LIKE` over content cannot use an index. This is acceptable at current memory volumes (memories are human-adopted artefacts) but will need a full-text index or a title-only match if the table grows substantially.

### `autowonder.get_memory`

```json
{ "id": 10231 }
```

Returns one memory. Cross-tenant reads report `MEMORY_NOT_FOUND` (21001); reading another agent's `AGENT`-scoped memory reports `NO_PERMISSION` (10403).

### `autowonder.update_memory`

```json
{ "id": 10231, "title": "新标题", "contentMd": "修正后的正文", "type": "BEST_PRACTICE" }
```

Omitted fields keep their current value. Uses the optimistic-lock `version`, so a concurrent edit reports `MEMORY_VERSION_CONFLICT` (21004).

### `autowonder.deprecate_memory`

```json
{ "id": 10231, "comment": "接口已改，结论不再成立" }
```

Marks the memory `REJECTED` so it stops being reused, keeps the row, and appends a `REJECT` record to the memory audit trail. Prefer this over `delete_memory`. Deprecating an already rejected memory reports `MEMORY_ALREADY_REVIEWED` (21006).

### `autowonder.delete_memory`

```json
{ "id": 10231 }
```

Soft deletes the memory and returns `{ "deleted": true }`. Rejected with `MEMORY_DELETE_IN_USE` (21003) when the memory is still bound to a digital worker — use `deprecate_memory` instead.

## Typical agent flow

1. Before deciding, call `search_memories` with a keyword from the task to pull in adopted prior conclusions.
2. While working, call `create_memory` as soon as a reusable conclusion appears; do not batch it into a file at the end of the run.
3. If a retrieved memory turns out to be inaccurate, call `update_memory` to correct it, or `deprecate_memory` when it no longer applies at all.

## Errors

| Code | Meaning |
|---|---|
| 10403 | Credential may not read or mutate this memory |
| 21001 | Memory not found, or it belongs to another tenant |
| 21002 | `title` is missing or blank |
| 21003 | Memory is still bound to a digital worker and cannot be deleted |
| 21004 | Optimistic lock conflict; re-read and retry |
| 21006 | Memory has already been reviewed: it is already rejected, or a reused `idempotencyKey` would rewrite adopted/rejected content |
| 27002 | Unknown tool name |
| 27003 | Invalid argument, e.g. an unsupported `scope` |

## Relation to learning delta

| | Memory MCP | learning delta file |
|---|---|---|
| Transport | MCP tool call during the run | `artifacts/output/learning_delta/memory_delta.json`, uploaded after the run |
| Timing | Immediate, readable in the same run | Only after artifact upload |
| Retrieval | `search_memories` | Not retrievable by the agent |
| `source` | `MCP` | `LEARNING_DELTA` |
| Gating | Always ingested | Skipped when the agent's `evolutionMode=MANUAL` |

Both paths write to the same `memory` table and share the same human review queue, so a team can migrate gradually.
