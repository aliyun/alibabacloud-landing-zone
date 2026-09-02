# 调度器 ↔ 客户端执行器 交互协议全景

> 本文档完整描述 `auto-wonder`（服务端 / 调度器）与 `auto-wonder-client-runtime`（客户端 daemon / 执行器）之间的数据交互链路、数据格式、信号与接口、以及每个动作的含义。含时序图、状态机与核心工作流程图。
>
> 对应实现：当前 `auto-wonder` 服务端与 `auto-wonder-client-runtime` 客户端。

---

## 0. 术语与角色

| 角色 | 所在 | 职责 |
|---|---|---|
| **调度器 (Scheduler)** | 服务端 `auto-wonder` | 打包任务、选执行器、下发、驱动 SDLC 排下一棒 |
| **执行器 (Executor)** | 客户端 daemon | token 身份接入、拉起本地 CLI agent、按 SDLC 执行、回传 |
| **工单 (Workitem)** | 服务端 | 需求/Bug/任务的载体，带 SDLC 与当前步 |
| **定时任务 Run** | 服务端 | 7×24 定时任务的一次实例；使用同一派发协议，但以 `source_type=SCHEDULED_TASK_RUN` 隔离 |
| **派发 (Dispatch)** | 服务端表 `dispatch` | 一次“把某工单某步交给某 agent 的某执行器”的记录 |
| **任务包 (Task Package)** | OSS zip | 身份/技能/记忆/sdlc/队友清单/上一轮产物的快照 |
| **数字员工 (Agent)** | 服务端 | 一个角色化的 AI worker，可有多个在线执行器 |

关键基础设施：
- **WebSocket** `/ws/executor` —— 服务端 ↔ 执行器的**唯一双向信道**（下发 + 回传）。
- **Redis** —— 执行器在线表 / 路由表 / agent→执行器集合 / 派发锁 / 跨节点广播。
- **OSS** —— 任务包与（Phase 2）产物的对象存储；下发的是**预签名下载地址**。

---

## 1. 一张图看全链路（核心工作流程）

```
   真人/系统                服务端 auto-wonder(调度器)                       Redis / OSS            客户端 daemon(执行器)              本地 CLI Agent
      │                              │                                          │                          │                            │
      │ 创建并指派工单给数字员工      │                                          │                          │  (启动即带 executorId+token) │
      │─────────────────────────────▶│                                          │  ws://…/ws/executor?exec │◀──────长连接已建立──────────│
      │                              │  WorkitemAssignedEvent(AFTER_COMMIT)      │  &token  →鉴权→登记在线   │                            │
      │                              │  DispatchAssignmentListener               │  exec:online / agent:execs│                            │
      │                              │  → DispatchService.enqueue → runPending    │                          │                            │
      │                              │    1) Redis 派发锁(dispatch:lock)          │◀────setIfAbsent──────────│                            │
      │                              │    2) agent 在线? version?                 │                          │                            │
      │                              │    3) ExecutorSelector.select(agentId)     │◀────smembers agent:execs─│                            │
      │                              │    4) PACKAGING: 组装上下文+打 zip         │                          │                            │
      │                              │       PackageContextAssembler→TaskPackager │────put zip / presignGet─▶│ OSS                        │
      │                              │    5) DISPATCHED                           │                          │                            │
      │                              │  WsDispatchTransport.dispatch              │                          │                            │
      │                              │   本节点有会话? 直发 : 发 Redis 广播       │──node:dispatch:broadcast─│                            │
      │                              │            ══════ TASK_DISPATCH ═════════════════════════════════════▶│  收到派发                   │
      │                              │            ◀═════ TASK_ACK ═══════════════════════════════════════════│  确认                       │
      │                              │  onAck: DISPATCHED→ACKED                   │                          │  下载 zip(downloadUrl)     │
      │                              │            ◀═════ TASK_PROGRESS ══════════════════════════════════════│◀─解压/挂载 capsule─────────│
      │                              │  onProgress: ACKED→RUNNING                 │                          │  拉起 CLI agent 跑 SDLC ───▶│ scope/implement/test/...   │
      │                              │            ◀═════ TASK_PROGRESS(多次)══════════════════════════════════│◀─runtime events────────────│
      │                              │                                          │  产出:deliverables/patches │◀───────产物写入─────────────│
      │                              │                                          │  handoff-proposal.json     │                            │
      │                              │            ◀═════ TASK_RESULT ════════════════════════════════════════│  执行结束(success/失败)     │
      │                              │  onResult: →SUCCEEDED/FAILED               │                          │                            │
      │                              │  SdlcDriver.onSuccess/onFail → 判定下一节点 │                          │                            │
      │                              │            ◀═════ TASK_HANDOFF ═══════════════════════════════════════│  交接:下一跳是谁            │
      │                              │  HandoffService: 真人→停; 数字人→记录       │                          │                            │
      │                              │                                          │                          │                            │
      │                              │  【下一棒】下一步 handler=数字员工 且在线   │                          │                            │
      │                              │  → enqueue+runPending → 新一轮 TASK_DISPATCH│─────────────────────────▶│  (可能发给另一执行器)       │
      │◀── 全程状态 WS/SSE+pub/sub 推前端 ─┘                                          │                          │                            │
```

> 一句话：**工单指派 → 打 OSS 包 → 经 WS 把下载地址定向推给 token 对应执行器 → 执行器下载/挂载/执行 → 回传状态与产物与 handoff → 调度器按 SDLC 排下一棒**，循环直至 SDLC 结束或交接真人。

7×24 Run 与工单走完全相同的 `TASK_DISPATCH` / `TASK_ACK` / `TASK_RESULT` 帧，运行时不增加字段、也无需升级。服务端保留历史 `workitem_id=runId` 以兼容存储和客户端，同时在数据库记录 `source_type=SCHEDULED_TASK_RUN`；因此相同数字 ID 的工单与 Run 不会互相读取评论、产物或派发。

### 1.1 双 Schema 滚动发布对执行器的影响

- **线协议不变。** V037 的 `source_type` 和 `normalized_idempotency_key` 只存在于服务端存储与查询边界；`TASK_DISPATCH`、`TASK_ACK`、`TASK_PROGRESS`、`TASK_RESULT`、`TASK_HANDOFF`、`ARTIFACT_UPLOADED` 和 `HEARTBEAT` 不增加来源字段，客户端 Runtime 无需配合升级。
- **普通工单连续可用。** 兼容二进制在 pre-V037 使用 Legacy Mapper；DDL 完成并重启后使用 Source-aware Mapper。在集群混合 Legacy/Source-aware 节点的滚动窗口，三个 Scheduled 开关必须保持关闭，但普通 Workitem 的下发和回调继续使用原协议。新增列的默认值会把 Legacy 写入归为 `WORKITEM`。
- **能力与开关在启动时冻结。** 数据库升级不会让运行中节点自动切换 Mapper；Guard 也在 Bean 构造时复制 `ENABLED`、`SCANNER_ENABLED`、`CLUSTER_READY`，不支持热更新。每次 Schema 或开关变化都要滚动重启每个节点并逐节点验证。
- **Scheduled 边界 fail closed。** 能力不可用时，Daemon HTTP callback、WebSocket inbound、MCP 和 Realtime 等请求/回调边界抛出 `30006`，且不访问 Scheduled Mapper；普通 Workitem 的 ACK/Progress/Result/评论/Artifact 不受影响。Scanner 与 Run compensation 是后台任务，不向客户端返回 `30006`，而是在 Redis/DAO 前 no-op。执行器不能把被拒绝的 Scheduled 回调降级为 Workitem 重试。
- **外部集群证明。** 发布平台必须用编排平台的完整 serving UID 清单逐节点保存能力响应及 `V037_READY/SOURCE_AWARE` 日志/指标，数量与期望 ready 副本一致；负载均衡抽样和单节点推断都不构成 attestation。采集至启用期间冻结扩缩容/替换，否则重新验证全量清单。
- **集群原子启用。** 三个环境变量默认均为 `false`。滚动发布 enabled+attestation 前，只冻结可独立路由的 Scheduled UI 和 create/update/enable/run-now HTTP 入口；共享 Daemon、通用 MCP tool、executor WebSocket 不能声称可按来源在网关过滤，它们和 dispatch credential 由服务端按持久化来源解析。写冻结下必须机器验证并归档 Task、Run、Scheduled Dispatch/评论/Artifact/Guidance 等数据全为 0；所有 serving 节点均验证 `available=true` 后，在解除屏障前再次执行零数据门禁。首次 manual Run 前没有 Scheduled dispatch credential/callback；原子解除写入口/UI 屏障后才执行内部 Run。任一节点或零数据门禁失败则保持屏障；若已有数据，禁止混合节点激活和无数据快速回退。Scanner 最后单独发布为 true。
- **安全关闭。** 先阻止新 Task/Run，发布 Scanner false 并重启/验证所有节点，同时保持 enabled 与 attestation true，也保持共享 callback、通用 MCP 访问和已有 Scheduled dispatch credential 可用，让回调和补偿继续收口。关闭门禁必须分别证明：Scheduled Run 无非终态；`SCHEDULED_TASK_RUN` Dispatch 无非终态（终态仅 `SUCCEEDED/FAILED/TIMEOUT/CANCELED`）；Scheduled Guidance/Delivery 无 `QUEUED/DELIVERED`；发布平台执行器 callback、耐久 outbox 和 transport pending 均为 0。所有 SQL、平台原始响应与计数都要机器断言并归档；仅 Run 终态或应用 gauge 为 0 不充分。全部通过后才撤销已排空 Run 的 Scheduled credential/source-bound 访问并发布 enabled false；attestation false 前必须以新 attempt 再跑全套门禁。共享通用 MCP tool 和 MCP/WS/Daemon transport 始终为 Workitem 开放，不发布或撤销虚构的 Scheduled 专用 MCP tool。每次开关变更都重启/验证所有节点。紧急同时关闭会搁置 Run，必须用 source-aware-compatible 二进制和 `scanner=false, enabled=true, cluster-ready=true` 恢复回调/补偿后对账收口。
- **二进制回滚边界。** 未产生任何 Scheduled 数据时，只可回退到经验证兼容加法 DDL 的旧版本；一旦出现任意 Scheduled Task/Run/Dispatch/评论/Artifact/Guidance，pre-source-type 二进制永久禁止，只能使用 source-aware-compatible 二进制。
- **活跃数证据。** 能力关闭后 `scheduled_task_active_runs` 会安全返回 0 且不查 DAO，这不能证明数据库已经排空；必须在 enabled 与 attestation 仍为 true 时完成回调/补偿，并同时以 Run、Dispatch、Guidance 数据库计数及平台 callback/outbox/transport 计数为最终关闭门禁。

---

## 2. 组件地图

### 服务端（调度侧）
```
workitem.WorkitemService ──指派──▶ WorkitemAssignedEvent
                                        │ AFTER_COMMIT
                              dispatch.DispatchAssignmentListener
                                        │
                              dispatch.DispatchService  ── enqueue / runPending / onAck / onProgress / onResult / onTimeout
                                        ├── dispatch.ExecutorSelector      (读 Redis agent:execs 选在线执行器)
                                        ├── dispatch.PackageContextAssembler (冻结上下文: 身份/技能/记忆/sdlc/roster/队友产物)
                                        ├── taskpackage.TaskPackager        (序列化→zip→OSS→presign)
                                        ├── dispatch.DispatchTransport      → websocket.WsDispatchTransport (下发)
                                        ├── dispatch.SdlcDriver             (判定下一节点: NEXT/GOTO/RETRY/HANDOFF_HUMAN/END)
                                        └── dispatch.HandoffService         (真人→停调度; 数字人→记录)
websocket.ExecutorWsEndpoint (/ws/executor)  ── onOpen 鉴权 → SessionRegistry + PresenceManager(Redis)
websocket.InboundFrameRouter                 ── 路由上行帧 → DispatchService / HandoffService / ArtifactService / PresenceManager
```

### 客户端（执行侧）
```
cmd/autowonder-daemon/main.go
   └─ runWsLoop ─ dial(wsclient.Conn) ─ serveWsConn(心跳 + 读循环 + ctx回收)
        └─ handleWsDispatch: TASK_ACK → executeAssignment → TASK_RESULT → sendWsHandoff
daemon/wsclient/  ── Conn(dial/read/send) · 帧编解码 · AssignmentFromDispatch · HandoffFrameFromFile
runtime/  ── 下载/校验/挂载 capsule · engine 执行 SDLC · artifacts/handoff/learning 产物  (本次未改)
providers/ ── claude/codex/... 本地 CLI agent 后端                                        (本次未改)
```

---

## 3. 连接与鉴权（会话建立）

**接口**：`GET ws://<server>/ws/executor?executorId=<id>&token=<plainToken>`（`websocket/ExecutorWsEndpoint.java`）

```
执行器 daemon                         服务端 ExecutorWsEndpoint                Redis
     │  Dial ?executorId&token            │                                     │
     │───────────────────────────────────▶│ onOpen                              │
     │                                     │ ExecutorWsAuthenticator.authenticate│
     │                                     │   校验 token 对 ExecutorDO.tokenRef │
     │                                     │ 成功→绑定 executorId/agentId/tenantId│
     │                                     │ SessionRegistry.register            │
     │                                     │ PresenceManager.register ───────────▶ SET exec:online:{id}=node  TTL90s
     │                                     │                                     │ SET exec:route:{id}=node   TTL90s
     │                                     │                                     │ SADD agent:execs:{agentId} {id}
     │◀──── 连接就绪（无显式握手帧）───────│                                     │
```

要点：
- **执行器身份 = token**。进程启动时带服务端一次性签发的 `executorId + token`（`ExecutorController.create → IssuedExecutorVO`）。服务端据此把派发**定向**推给这条会话。
- 登记写入的 `agent:execs:{agentId}` 正是调度时 `ExecutorSelector` 读取的键——**先有在线执行器，才可能被派发**。
- 保活：客户端周期发 `HEARTBEAT`，服务端 `PresenceManager.heartbeat` 刷新 TTL(90s)；掉线后 TTL 过期，键自动失效，不再派发。
- 客户端配置：`AUTOWONDER_SERVER_WS_URL`、`AUTOWONDER_EXECUTOR_ID`、`AUTOWONDER_EXECUTOR_TOKEN`、`AUTOWONDER_PROVIDER`（`daemon/config`）。

---

## 4. 完整时序图（Mermaid）

```mermaid
sequenceDiagram
    autonumber
    actor U as 真人/系统
    participant WS as 服务端 WS 端点
    participant DS as DispatchService
    participant SEL as ExecutorSelector
    participant PKG as PackageContextAssembler+TaskPackager
    participant OSS as OSS
    participant TR as WsDispatchTransport
    participant EX as 客户端执行器(daemon)
    participant AG as 本地 CLI Agent
    participant SD as SdlcDriver

    Note over EX,WS: 执行器已用 executorId+token 建立 /ws/executor 长连(在线登记入 Redis)

    U->>DS: 指派工单给数字员工 (WorkitemAssignedEvent)
    DS->>DS: enqueue → runPending (Redis 派发锁)
    DS->>SEL: select(agentId)
    SEL-->>DS: executorId (在线)
    DS->>PKG: assemble + build (PACKAGING)
    PKG->>OSS: put(zip)
    OSS-->>PKG: ossRef
    PKG->>OSS: presignGet
    OSS-->>PKG: downloadUrl(600s)
    DS->>DS: 状态置 DISPATCHED
    DS->>TR: dispatch(DispatchDO, pkg)
    TR->>EX: TASK_DISPATCH {dispatchId,tenantId,workitemId,attempt,downloadUrl,md5,size}
    EX->>WS: TASK_ACK {dispatchId}
    WS->>DS: onAck (DISPATCHED→ACKED)
    EX->>OSS: GET downloadUrl (下载 zip)
    EX->>EX: 解压 + 挂载 capsule (identity/sdlc/skills/memory/repos/roster/teammates)
    EX->>WS: TASK_PROGRESS {dispatchId}
    WS->>DS: onProgress (ACKED→RUNNING)
    EX->>AG: 拉起 CLI，按 sdlc.json 逐步执行
    loop 执行中
        AG-->>EX: runtime events (step.*/agent.progress)
        EX->>WS: TASK_PROGRESS {dispatchId, resultSummary}
    end
    AG-->>EX: 产出 deliverables/patches/evidence + handoff-proposal.json + learning_delta
    EX->>WS: TASK_RESULT {dispatchId, success, resultSummary, error}
    WS->>DS: onResult (→SUCCEEDED/FAILED)
    DS->>SD: onSuccess/onFail
    SD-->>DS: DriveResult (ENQUEUE next / RETRY / STOP)
    EX->>WS: TASK_HANDOFF {dispatchId, workitemId, to, toType, nextRole, reason}
    WS->>DS: HandoffService.handle (真人→assignee=HUMAN 停; 数字人→记录)
    alt 下一步 handler=数字员工 且在线
        DS->>DS: enqueue + runPending (下一棒)
        DS->>TR: dispatch(...)
        TR->>EX: 新一轮 TASK_DISPATCH (可能发给另一执行器)
    else 真人 / 无在线 agent
        DS->>DS: 置 assignee=HUMAN, 停止自动调度
    end
```

---

## 5. 信号 / 帧目录（数据格式 + 动作）

所有帧都是 JSON 文本，含 `type` 判别字段。**下行**=服务端→执行器，**上行**=执行器→服务端。

### 5.1 下行：`TASK_DISPATCH`（服务端→执行器）
- **接口/来源**：`websocket/WsDispatchTransport.buildFrame`；本节点有会话直发，否则发 Redis `node:dispatch:broadcast`，由持有会话的节点 `NodeMailboxListener` 定向投递。
- **触发**：一次 dispatch 进入 `DISPATCHED`。
- **格式**：
```json
{
  "type": "TASK_DISPATCH",
  "dispatchId": 300001,
  "executorId": 900001,
  "tenantId": 100001,
  "workitemId": 500001,
  "attempt": 1,
  "downloadUrl": "https://oss.example/presigned/....zip",
  "md5": "3bb338675acb7bd6fb050e6415a3d3a6",
  "size": 20481
}
```
- **执行器动作**：`wsclient.DecodeDispatch` → `AssignmentFromDispatch`（数值 id 转字符串，`provider` 取本地配置）→ 组装 `dispatch.DispatchAssignment` → 回 `TASK_ACK` → 落地 assignment 文件 → `executeAssignment`（据 `downloadUrl` 下载、`tenant/workitem/dispatch/attempt` 建 capsule 目录）。

> 说明：客户端下载前必须知道 `tenantId/workitemId/attempt/dispatchId`（建 capsule）+ `downloadUrl`+`md5`（取包）。身份/技能/记忆/sdlc/roster 全部在**包内**（见 §6）。

### 5.2 上行：`TASK_ACK`（执行器→服务端）
```json
{ "type": "TASK_ACK", "dispatchId": 300001 }
```
- **服务端动作**：`InboundFrameRouter → DispatchService.onAck`：`DISPATCHED → ACKED`（幂等，终态忽略）。

### 5.3 上行：`TASK_PROGRESS`（执行器→服务端）
```json
{ "type": "TASK_PROGRESS", "dispatchId": 300001, "resultSummary": "step.started" }
```
- **触发**：runtime event 命中 `shouldReportRuntimeEvent`（`step.*`、`agent.progress`、包校验、上传、终态等）。
- **服务端动作**：`onProgress`：`ACKED/DISPATCHED → RUNNING`（幂等，RUNNING 后为 no-op），并将结构化 runtime event 写入 `dispatch_runtime_event`，供工单详情页按 Agent 内部 SDLC 步骤展示实时进度。
- **兼容格式**：服务端优先读取帧顶层字段，也兼容 `log` 字段中的 JSON；`stepOrder/stepId/stepName` 用于映射 SDLC 步骤，`message/error/detailJson` 用于展示执行明细。

### 5.4 上行：`TASK_RESULT`（执行器→服务端）
```json
{ "type": "TASK_RESULT", "dispatchId": 300001, "success": true, "resultSummary": "已完成并产出交付物", "error": "" }
```
- **触发**：执行结束。`success = (RunState == completed)`；失败时 `error` 带失败信息。
- **服务端动作**：`onResult`：置 `SUCCEEDED/FAILED` → 调 `SdlcDriver.onSuccess/onFail` 得 `DriveResult` → `act()`：
  - `ENQUEUE` → `enqueue + runPending`（**排下一棒**）
  - `RETRY` → 同步同工单同步 attempt+1（预算内）
  - `STOP` → 结束

### 5.5 上行：`TASK_HANDOFF`（执行器→服务端）—— 交接下一跳
```json
{
  "type": "TASK_HANDOFF",
  "dispatchId": 300001,
  "workitemId": 500001,
  "to": "reviewer",
  "toType": "AGENT",
  "nextRole": "reviewer",
  "reason": "编码与自测完成，交接评审"
}
```
- **来源（客户端）**：执行产物 `handoff-proposal.json`（`runtime/artifacts`，字段 `nextOwner/nextRole/summary`）→ `wsclient.HandoffFrameFromFile`。`to` 是**名字/角色字符串**（不是数字 userId），`toType` 缺省 `AGENT`。
- **服务端动作**：`InboundFrameRouter → HandoffService.handle(tenantId, workitemId, to, toType)`：
  - `toType=HUMAN` → 工单 `assignee=HUMAN`（ref 置 null，镜像 `SdlcDriver.assignHuman`），**停止自动调度**；
  - `toType=AGENT`（或其它）→ **仅记录**；数字人的下一棒仍由 `TASK_RESULT → onResult → SdlcDriver` 驱动（Phase 1 最小侵入决策）。

### 5.6 上行：`HEARTBEAT`（执行器→服务端）
```json
{ "type": "HEARTBEAT" }
```
- **服务端动作**：`PresenceManager.heartbeat` 刷新 `exec:online`/`exec:route` TTL(90s)。

### 5.7 上行：`ARTIFACT_UPLOADED`（执行器→服务端）—— Phase 2 沉淀通道
```json
{
  "type": "ARTIFACT_UPLOADED",
  "dispatchId": 300001, "workitemId": 500001,
  "name": "deliverables/report.md", "artifactType": "DELIVERABLE",
  "ossRef": "oss://autowonder-artifact/....", "size": 1024, "metaJson": "{}"
}
```
- **服务端动作**：`ArtifactService.record`（落 `ArtifactDO`）。
- **状态**：服务端 handler 已就绪；**客户端上传逻辑属 Phase 2**（当前主环不发此帧）。

### 信号总表

| 帧 | 方向 | 触发 | 服务端处理 | 状态副作用 |
|---|---|---|---|---|
| `TASK_DISPATCH` | 下行 | dispatch 进入 DISPATCHED | — | — |
| `TASK_ACK` | 上行 | 收到派发 | `onAck` | DISPATCHED→ACKED |
| `TASK_PROGRESS` | 上行 | runtime event | `onProgress` | ACKED→RUNNING |
| `TASK_RESULT` | 上行 | 执行结束 | `onResult`+`SdlcDriver` | →SUCCEEDED/FAILED + 排下一棒 |
| `TASK_HANDOFF` | 上行 | 结束后有 handoff 产物 | `HandoffService` | 真人→停/数字人→记录 |
| `HEARTBEAT` | 上行 | 周期(≈30s) | `PresenceManager` | 刷新在线 TTL |
| `ARTIFACT_UPLOADED` | 上行 | 产物上传后(Phase 2) | `ArtifactService` | 落 ArtifactDO |

---

## 6. 任务包格式（OSS zip，`autoWonder.taskPackage.v1`）

由 `taskpackage/TaskPackager` 打包，`presignGet` 出下载地址。客户端下载解压后**就地挂载**为执行规范（capsule）。

```
<dispatch>.zip
├── manifest.json            # 见下，schemaVersion=autoWonder.taskPackage.v1 + fileDigests(每文件 sha256)
├── workitem.md              # 工单标题 + 正文
├── clarification.md         # 需求澄清材料(可选)
├── identity.json            # 数字员工身份: name/roleCode/职责/操作规则
├── sdlc.json                # 当前 SDLC 步骤定义 + 产物契约 + 完成门
├── skills.json              # 技能清单（+ skills/<name>/SKILL.md ...）
├── repos.json               # 仓库 + 凭据/检出方式
├── roster.json              # 队友清单：数字人小队 + 相关真人  ← 客户端 handoff 决策依据
├── memory/<type>.md         # 记忆
└── teammates/<role>/…       # 上一轮已完成队友的 conclusion.md + artifacts/*
```

`manifest.json`（关键字段）：
```json
{
  "schemaVersion": "autoWonder.taskPackage.v1",
  "packageId": "pkg_300001",
  "tenantId": "100001", "workitemId": "500001", "workType": "BUGFIX",
  "dispatchId": "300001", "attempt": 1, "idempotencyKey": "500001:700001:1",
  "sdlcId": "600001", "sdlcStepId": "700001",
  "agentId": "200001", "agentVersionId": "200101", "executorId": "900001",
  "roleCode": "backend_fixer", "roleName": "后端修复工程师",
  "createdAt": 1752131275121,
  "fileDigests": { "workitem.md": "sha256:…", "identity.json": "sha256:…", "...": "…" },
  "teammates": [ { "roleName": "Reviewer", "agentId": 200002, "dispatchId": 300000, "dir": "Reviewer" } ]
}
```

`roster.json`（客户端 Agent 决定 handoff 下一跳的依据）：
```json
{
  "digitalTeammates": [ { "agentId": 200002, "roleCode": "reviewer", "roleName": "评审" } ],
  "humanTeammates":   [ { "userId": 42, "relation": "assignee" } ]
}
```

> 完整性（Phase 1）：整包用 `md5`（随 `TASK_DISPATCH` 帧下发）；`fileDigests` 为每文件 sha256（不含 manifest 自身）。整包 sha256 校验为 Phase 2。

---

## 7. 状态机

### 7.1 服务端 dispatch 状态（`DispatchStatus`）
```
        enqueue           runPending          transport            onAck        onProgress       onResult(success)
 ∅ ───────────▶ PENDING ─────────▶ PACKAGING ─────────▶ DISPATCHED ──────▶ ACKED ──────▶ RUNNING ──────────────▶ SUCCEEDED
                   │                   │                    │                                 │  onResult(fail)
                   │                   │                    │                                 └──────────────────▶ FAILED
                   │  agent离线/无执行器/打包失败                                                onTimeout ─────────▶ TIMEOUT
                   └──────────────────────────────────────▶ FAILED                            cancel   ─────────▶ CANCELED
```

### 7.2 客户端 RunState → 服务端状态（`CollapseRunState`）
```
assigned                         → DISPATCHED
preparing/package_ready/workspace_ready → ACKED
running/collecting/uploading/blocked/recovering → RUNNING
completed → SUCCEEDED   failed → FAILED   cancelled → CANCELED   timed_out → TIMEOUT
```
> 客户端内部状态更细；对外经帧“收敛”为服务端粗粒度状态，前端只关心用户可见的变化。

### 7.3 SDLC 下一节点判定（`SdlcDriver`）
```
onSuccess(step):  onSuccess 动作 =
    END        → STOP
    GOTO_STEP  → 前进到目标步
    NEXT_STEP  → 按 stepOrder 前进到下一步
  前进到目标步后:
    handlerType=AGENT 且 角色解析出在线 agent → DriveResult.ENQUEUE(step, agentId)   → 继续调度
    否则(真人/无在线 agent)                    → assignee=HUMAN, STOP                → 停止调度

onFail(step):   onFail 动作 =
    RETRY(max)      → DriveResult.RETRY   (attempt+1, 预算内)
    GOTO_STEP       → 前进到目标步
    END_FAIL        → STOP
    HANDOFF_HUMAN   → assignee=HUMAN, STOP
```

---

## 8. 交接（handoff）与“调度器掌握下一棒”

- **谁决定下一跳**：客户端 Agent 依据包内 `roster.json`（数字人小队 + 相关真人）理解并选出 `nextOwner`，写入 `handoff-proposal.json`，经 `TASK_HANDOFF` 上报。
- **服务端如何流转（Phase 1 最小侵入）**：
  - 数字人下一棒：由 `TASK_RESULT → onResult → SdlcDriver` 依 SDLC 步骤 + 角色解析在线 agent 自动 `enqueue+runPending`（PRD §7“按 SDLC 判定下一节点：数字员工→继续调度”）。`TASK_HANDOFF(AGENT)` 记录客户端的选择。
  - 真人下一棒：`TASK_HANDOFF(HUMAN)` → `assignee=HUMAN`，停止自动调度。
- **循环**：下一棒又是一次 `TASK_DISPATCH`（可能落到该角色的另一执行器），如此闭环推进直至 SDLC END / 交接真人。

> 演进（Phase 2）：让客户端选定的目标**权威**驱动数字人下一棒（`TASK_HANDOFF(AGENT)` 直接 enqueue 指定 agent），并让 `runtime` 产物携带 `toType` 以真正触发真人交接。见 §11。

---

## 9. 接口 / 端点参考

| 用途 | 协议 | 位置 |
|---|---|---|
| 执行器接入（双向信令） | WS | `GET /ws/executor?executorId&token` |
| 下发派发 | WS 帧 | `TASK_DISPATCH` |
| 回传 ack/进度/结果/交接/心跳 | WS 帧 | `TASK_ACK` / `TASK_PROGRESS` / `TASK_RESULT` / `TASK_HANDOFF` / `HEARTBEAT` |
| 产物上报（Phase 2） | WS 帧 | `ARTIFACT_UPLOADED` |
| 任务包下载 | HTTPS | OSS presigned URL（随 `TASK_DISPATCH.downloadUrl`） |
| 执行器签发 token | REST | `POST /api/agents/{agentId}/executors`（`ExecutorController`） |
| 产物查询/下载（前端） | REST | `GET /api/workitems/{id}/artifacts`、`GET /api/artifacts/{id}/download` |

Redis 键：`exec:online:{executorId}`、`exec:route:{executorId}`、`agent:execs:{agentId}`、`dispatch:lock:{dispatchId}`、广播频道 `node:dispatch:broadcast`。

---

## 10. 错误处理与边界

| 场景 | 行为 |
|---|---|
| token 失效/鉴权失败 | 服务端 `VIOLATED_POLICY` 关闭连接；客户端指数退避重连(1s→×2→≤30s) |
| 执行器掉线 | `exec:online` TTL 过期→不再派发；重连后重新登记 |
| 无在线执行器 | `runPending` 走 `NO_EXECUTOR` 失败分支→ `SdlcDriver.onFail` |
| 重复派发 | `dispatch:lock` Redis 锁 + `idempotencyKey` 幂等；客户端对同 `dispatchId` 幂等 |
| 下载/执行失败 | 客户端发 `TASK_RESULT{success:false,error}`→服务端失败分支(retry/human 依 SDLC) |
| 服务端优雅停机/ctx 取消 | 客户端 `serveWsConn` 监听 ctx，取消时关连接解除阻塞读循环，退出重连 |
| WS 与 HTTP 并存 | 客户端 `runWsLoop` 与 `runServerLoop` 互斥（配了 WS 走 WS） |
| 跨节点会话 | 目标会话在别的服务节点→Redis 广播→持有会话节点 `NodeMailboxListener` 投递 |
| Scheduled 请求/回调能力未就绪 | 服务端在识别 Scheduled 来源后抛出 `30006`；不访问 Scheduled Mapper，不把回调改投普通 Workitem |
| Scanner/Run compensation 能力未就绪 | 后台任务在 Redis 锁和 DAO 前 no-op；没有可返回给客户端的 `30006` |

滚动升级期间如果能力端点为 `LEGACY`、`V037_PARTIAL` 或不可访问，执行器仍可处理普通 Workitem。Scheduled UI/API 必须保持关闭；不能仅因为某个节点已看到 V037 就发送 Scheduled 派发。若出现 `INCONSISTENT`，该服务节点不得承载业务流量。

---

## 11. Phase 2 遗留（当前主环不含）

1. **产物/记忆沉淀**：客户端把 artifacts/`learning_delta`/observability 上传 OSS 并发 `ARTIFACT_UPLOADED`；服务端整合记忆/repo-map/worker-profile/skill。
2. **整包完整性**：`TASK_DISPATCH` 帧补整包 `sha256`，客户端设 `ExpectedSHA256` 做锚定校验（当前仅 md5 下发、未强校验）。
3. **真人 handoff 落地**：`runtime` 产物写入 `toType`，`TASK_HANDOFF` 才能触发真人分支（当前恒为 `AGENT`）。
4. **handoff 权威驱动**：`TASK_HANDOFF(AGENT)` 直接按客户端选定目标 enqueue 下一棒（替代纯 SDLC 角色解析）。

---

## 附：与代码的对应

- 下发帧与投递：`websocket/WsDispatchTransport.java`、`websocket/frame/TaskDispatchFrame.java`、`websocket/NodeMailboxListener.java`
- 上行路由：`websocket/InboundFrameRouter.java`、`websocket/frame/Task*Frame.java`
- 调度核心：`dispatch/DispatchService.java`、`dispatch/ExecutorSelector.java`、`dispatch/SdlcDriver.java`、`dispatch/HandoffService.java`
- 打包：`dispatch/PackageContextAssembler.java`、`taskpackage/TaskPackager.java`、`taskpackage/PackageContext.java`
- 接入与在线：`websocket/ExecutorWsEndpoint.java`、`websocket/ExecutorWsAuthenticator.java`、`websocket/PresenceManager.java`、`websocket/SessionRegistry.java`
- 客户端：`daemon/wsclient/*.go`、`cmd/autowonder-daemon/main.go`、`protocol/dispatch/types.go`、`protocol/packagecontract/types.go`
