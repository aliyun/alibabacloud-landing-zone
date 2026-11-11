# 工单流程快照与显式换轮

部署前对已有数据库执行 `docs/migration/V111__delivery_workflow_snapshot.sql`。新建数据库使用同步更新的 `docs/autowonder-schema.sql`。迁移只增加三张表，不修改历史执行、checkpoint、任务包、产物或外部回执。

每次正式交付或评论交互在入队时保存完整 SDLC 文档、当前步骤、员工版本、摘要和轮次来源。普通重试、暂停恢复复制原快照，沿用原会话及 checkpoint；员工默认配置变化不会改变原轮次。原流程被禁用时，普通恢复拒绝且保留暂停点。历史执行优先从原任务包导入并校验快照；无法取得原快照时拒绝静默使用当前配置。

暂停执行可通过页面“更多执行操作 → 按最新流程重启”，或 `POST /api/workitems/{workitemId}/dispatches/{dispatchId}/restart-latest` 显式换轮。个人 MCP 的 `autowonder.control_delivery` 也支持 `action=restartLatest`。只有当前已暂停的正式执行可使用此操作，交付必须保持开启。

新入口、员工及流程先校验，再持久化新轮快照并隔离旧轮。旧执行停止确认后，新轮从入口启动，不使用旧会话或 checkpoint；旧产物和业务回执继续保留。相同源 dispatch 的重复请求只对应一轮，即使随后默认配置再次变化。普通重新指派属于新交付：显式指定的有效流程优先；未指定时使用目标员工当前默认流程。正式 COMMENT_REWORK 在实际生效时重新确认目标当前流程及步骤，并原子换绑。

任务 MCP `autowonder.external_operation` 提供跨轮次的业务回执协议，限当前工单的有效执行凭据：

1. 对 PR、外部评论、云写操作，按仓库/分支、评论意图、云资源/请求确定稳定 `operationKey`，同时提供 `kind` 和请求 SHA-256。键不能包含轮次、dispatch 或重试编号。
2. `action=claim` 仅在 `mayExecute=true` 时允许执行。已成功的回执返回原外部引用；同键不同请求摘要拒绝。重复领取或领取结果不明不能获得第二次执行许可。
3. 执行后用领取时的 `claimToken` 调用 `action=complete`，记录 `SUCCEEDED` 与外部资源/请求引用；超时或结果不明记录 `UNKNOWN`。回执确认本身可安全重试。
4. `IN_FLIGHT` / `UNKNOWN` 必须先查询外部权威状态。确定结果后，使用 `action=reconcile`、当前 `expectedVersion` 和 `evidenceRef` 确认 `SUCCEEDED` 或已最终确定的 `NOT_FOUND`。未确认停止的旧执行不能被另一轮核对重放；只有确认不存在的操作可再次领取。

回执和必须的审计同事务提交。任务包提供当前轮次来源、快照摘要、已有回执和执行规则；重启不清空回执。已有集成同步评论继续使用原 outbox/readback 机制。通用回执不代理任意外部服务，也不能约束绕过协议直接执行的外部 CLI；外部查询超时、最终一致性未收敛或没有权威证据时，必须保持未知，不能凭空确认不存在。
