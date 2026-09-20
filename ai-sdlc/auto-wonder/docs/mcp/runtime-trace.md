# MCP 执行日志读取

以下工具均要求 `workspaceId`，使用空间 `READ_ONLY` 权限。`dispatchId` 是一次执行的 ID，不是工单 ID，可读取历史执行。

| 工具 | 参数（不含 workspaceId） | 返回 |
|---|---|---|
| `autowonder.get_dispatch_runtime_trace` | `dispatchId`，可选 `afterSeq` | 轨迹大纲、会话、轮次、动作标识；优先 OSS 归档，无归档时返回 LIVE 运行事件 |
| `autowonder.get_dispatch_activities` | `dispatchId` | 按持久化到达顺序排列的进度和生命周期活动 |
| `autowonder.get_dispatch_turn` | `dispatchId`、`traceId` | 归档轮次的 prompt、systemPrompt、output、observations；traceId 也接受 turnId |
| `autowonder.get_dispatch_observation` | `dispatchId`、`observationId` | 归档工具或模型调用的 input、output、error 及子调用 |

例如，先调用：

```json
{"name":"autowonder.get_dispatch_runtime_trace","arguments":{"workspaceId":10001,"dispatchId":10490}}
```

从 `sessions[].turns[]` 中取得 `traceId`，再调用 `get_dispatch_turn`；从 `observations` 中取得 `observationId`，再调用 `get_dispatch_observation`。这些 ID 应直接传递原值，不需要 URL 编码。

`afterSeq` 必须非负，仅用于 LIVE 查询：没有新事件时 `changed=false`；OSS 归档不受游标过滤。归档大纲省略完整输入输出，详情工具读取归档原始内容。详情尚未上传或指定的轮次/动作不存在时返回 `ARTIFACT_NOT_FOUND`，不以空结果表示成功。LIVE 数据和不同执行器的记录覆盖范围可能不完整，执行成功不代表所有动作均已留存。

个人凭证每次调用检查当前空间成员权限。执行凭证仅能读取同一空间、同一执行来源类型、同一工单或定时任务运行的历史执行；不能通过数字相同的工单 ID 与定时任务运行 ID 跨来源访问。定时任务运行遵循现有能力开关。

这些工具复用网页轨迹服务，无需数据库迁移。部署服务端后，MCP 客户端需刷新工具列表；本地测试不能证明线上历史执行已保存完整归档。
