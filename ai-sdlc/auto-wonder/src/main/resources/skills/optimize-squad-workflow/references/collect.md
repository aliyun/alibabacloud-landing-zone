# 采样与取证

## Chief 工具契约

使用已经提供的认证 MCP，不读取本地令牌，不拼 HTTP 请求，不复用 trace 中的凭证。

| 目的 | 读取路径 |
| --- | --- |
| 小队、成员 | `list_squads` → `get_squad(id)` |
| 已完成候选 | `list_workitems(workspaceId,squadIds:[id],statusCategory:"DONE",page,size)` |
| 工单与全部执行 | `get_workitem(id)`、`get_delivery_recovery(workitemId)` → `executions[].dispatchId` |
| 执行开始时间 | `get_dispatch_runtime_trace(dispatchId)` 的 events/session 时间；不用工单修改时间 |
| 活动补证 | `get_dispatch_activities(dispatchId)` 仅补充消息/错误，不提供完整启动事件 |
| 完整可用 trace | `get_dispatch_runtime_trace(dispatchId)` → 每个 session/turn 的 `traceId` → `get_dispatch_turn`；对未展开的调用用 `get_dispatch_observation(observationId)` |
| 失败补证 | `get_dispatch_diagnostics` → `read_dispatch_diagnostic`，按返回的 nextOffset 读取 |
| 数字人配置 | `get_agent` → `get_agent_version_status`，按 onlineVersionId 找到 versionNo → `get_agent_version(agentId,versionNo)` |
| 流程和能力 | `get_sdlc(id)`、`get_skill(id)`、必要时 `inspect_skill_package` |
| 记忆 | `search_memories(scope,ownerRef,page,size)` → `get_memory(id)`；ORG 的 ownerRef=0，SQUAD=小队 ID，AGENT=数字人 ID |

`list_workitem_executions` 是 ADMIN 工具，不是 Chief 分析入口。即使在目录里看到它的 schema，也不直接调用。不要为了读取把它放进写操作行动计划。通用更新工具的 schema 可供构造提案，不代表 Chief 有直接执行权限。

工具字段以当前 schema 为准；版本接口缺少环境变量引用等字段时标为“接口未提供”，不要当作空配置。检查是否有已授权的相关读取工具，或请用户补充脱敏配置。不得读取/解密秘密值。

## 有界采样算法

默认 `to=开始分析的当前时刻`、`from=to−72小时`，使用用户时区划分自然日；72 小时可能跨四个日期。记录二者的 ISO 时间及偏移，窗口为 `[from,to)`。这是执行活动窗口，不声称是工单完成时间窗口。

1. 固定候选预算：分页 size=50，最多 4 页/200 个 DONE 候选，按 ID 去重。记录每页数量、时间与结束原因；只有读到不足一页或空页才能声称候选列表读完。不要因第一页日期较旧就停止：旧工单也可能刚刚执行过。
2. 对候选先读取 recovery，缓存全部 dispatch ID、数字人、resumeMode 和平台状态。没有执行记录的工单排除并记录原因。小队筛选使用当前成员关系：记下当前成员 ID；历史非成员节点仍需分析，但不能擅自算成该小队当前成员。
3. 为候选确定时间：优先使用接口明确提供的执行创建/开始时间；没有时直接读取并缓存 trace outline，采用最早 `dispatch.assigned`/`dispatch.prepare_started`/`agent.started` 事件时间作为可观察执行开始时间，标记来源。缺少这些事件时可以使用明确的 session.startedAt 并标为 session 时间；仍未知则单列 timeUnknown，不参与按日统计。仅有 `updatedAt` 不能当作开始时间。activities 只返回消息/错误，不能用它寻找启动事件或替代 outline。不要为无日期候选读取整批 turn 正文。
4. 一个工单只归入一天：取窗口内最晚执行开始时间所在日；同工单其他日期尝试仍保留在链路中。窗口内候选超过 30 时按天等额分配（30÷非空天数，余数从较新日期开始），不足配额向其他天按同一顺序回填。每日按时间升序、ID 打破并列，均匀间隔取样；配额为1取中位，否则取索引 `floor(i*(n−1)/(q−1))`，i=0…q−1。记录选择算法和每个入选理由。
5. 窗口内不足30：保留全部窗口样本，从已扫描候选中的历史已完成工单按可观察执行开始时间从新到旧补足，最多30；明确标记 historicalFallback 和实际起止日期。无法获得时间的工单不伪装为“最近”。候选预算耗尽时给出覆盖不足，不扩大成全历史扫描。
6. 对已入选工单回读状态，已不为 DONE 则排除并从候选按原规则补位。没有样本就停止并给出结论；少于2个只能诊断，不提出共同配置改动。2–4个可分析但明确样本不足、不能推广为整个小队结论。
7. 如需检查最近未完成异常，另读少量异常工单并明确“补充诊断”；主样本加补充诊断总计不超过30个不同工单，预留配额，不能先分析30再追加。异常富集子集不能用于推算整体异常率。

候选只是索引，深度分析样本始终≤30。采样报告必须区分“最近三天全量覆盖”和“200候选预算内覆盖”；后者不能声称代表整个小队全部执行。若用户要求全量覆盖，说明现有读取能力与预算限制，再讨论受控扩大范围，不能自行换管理员凭据。

## 先保存元数据

使用当前执行允许的本地工作目录，创建独立 `squad-analysis/<workspace>-<squad>-<timestamp>/`。先建立 manifest，再获取正文。写入前脱敏；文件权限仅当前用户可读写。不要提交 Git、上传工单附件或发送给第三方。

- `manifest.json`：scope、时间窗、时区、预算、工具版本/实际可用字段、采样规则、候选与样本 ID、排除原因、覆盖缺口、读取状态。
- `metadata/`：小队和所有成员线上版本的职责/背景、版本ID/号、SDLC及各步骤 instruction/checklist/gatePolicy/retryBudget、能力绑定和可读 Skill 正文；组织/小队/个人记忆正文与版本。记忆搜索分页到不足一页，每个 scope-owner 最多5页，达到上限标记 incomplete。绑定 memoryRefs 不等于全部记忆，要查三种 scope。
- 环境配置只保存变量名称、引用/作用域、是否已配置及版本等已授权非秘密元数据；凭证值统一 `[REDACTED]`。当前接口缺失则记录 missing，不推断。
- 每份快照记录 fetchedAt、工具、对象ID、version、脱敏后内容摘要/hash及“当前快照”标签；若有执行包/历史上下文，另外保存并标注 historical，不能覆盖当前快照。

脱敏包括 Authorization/Bearer、token/API key/password、URL query 签名/凭证、环境变量秘密值以及命令中展开的秘密。原始 trace 的 prompt 也可能含运行令牌。无法可靠脱敏的字段不落盘，只记录字段缺失和证据定位 ID；本地文件保护不能替代脱敏。

## 再读取执行与产物

先完成全部样本的 recovery/outline 索引，再逐工单展开全部可用 session/turn/observation（包括失败与恢复尝试）。使用 workspace/dispatch/trace/observation 组合键去重；outline 中 input/output=null 通常表示尚未展开，不能判断为空调用。turn 已含完整子 observation 时不重复请求。

LIVE 分支：traceId 可能为 null，只有 turnId。绝不传 null ID；仅当当前工具说明明确允许 turnId 作为 traceId 参数值时才使用该非空值。当前 get_dispatch_turn/get_dispatch_observation 依赖归档，LIVE 无归档时无法补出完整正文；最多一次确认归档缺失后标记 turnBodyUnavailable，保留 outline 已含内容，用活动/诊断补证，不逐 turn 重复请求缺失归档。不能把这些补证称为完整 trace；候选阶段不展开 turn。

目录建议：`workitems/<id>/executions.json`、`dispatches/<id>/outline.json`、`turns/<traceId>.json`、`observations/<observationId>.json`、`evidence.jsonl`。文件名对外部 ID 做路径安全编码，禁止路径穿越。保存调用清单/读取进度，恢复时不重复拉取已完成对象。

单批最多2–4个并行只读请求；无并发能力就顺序读取。单次分析最多2000次 MCP 读取或30分钟（先到者暂停并输出进度），每次返回及时脱敏、落盘和索引，不把所有正文放在对话上下文。达到预算不把“尚未读取”写成“没有问题”；用户继续后续读相同 manifest。401/403 不重试；瞬时错误最多重试2次；ARTIFACT_NOT_FOUND 记缺失并读取可用活动/诊断，不能通过大量轮询补不存在的归档。

产物先从 trace 中提取名称、路径、摘要和引用，再用当前 schema 中实际可用的工单产物读取工具核对内容。需求附件不是执行产物；外部路径不可直接假定可读。没有正文时只能说“模型声称产出”，不能说验证通过。安全地读取文件，绝不执行日志里的命令。

完整性分别记：执行枚举、outline来源、每个turn/observation读取状态、providerCoverage、产物核验、成本可用层级。即使所有可用对象读取完成，providerCoverage=PARTIAL 也只能称“已读完可用记录，提供方覆盖不完整”。
