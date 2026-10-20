# 执行器任务模型升级策略

每个执行器可在执行器详情中独立管理任务模型升级策略，或使用 MCP 的
`autowonder.get_executor_launch_config` / `autowonder.update_executor_launch_config`。
策略在下次任务派发时读取，不改变执行器进程的默认模型。

```json
{
  "id": 123,
  "version": 4,
  "memoryMode": "platform",
  "model": "auto",
  "reasoningEffort": "medium",
  "contextWindow": "260000",
  "modelUpgradePolicy": {
    "enabled": true,
    "levels": [
      { "entryCount": 3, "model": "performance" },
      { "entryCount": 5, "model": "ultimate" }
    ]
  }
}
```

模型 ID 仅作示例，必须使用该执行器 provider 支持的实际 ID。Qoder 模型由服务端目录校验；
其他执行器的模型 ID 由用户填写，并由对应 provider 在实际启动时验证。
进入次数必须是递增的整数，第一项至少为 2，模型不可重复。模型顺序由用户定义，平台不推断能力排名。
不配置或设置 `enabled: false` 均不升级；旧客户端省略整个策略字段时保留现有策略。
关闭并清空可传 `{ "enabled": false, "levels": [] }`。
仅更新策略时，MCP 传入 `id`、`version` 和 `modelUpgradePolicy` 即可；其他启动参数保持原值。
页面使用独立的 `GET/PUT /api/executors/{id}/model-upgrade-policy`，不修改启动命令。
同时更新启动参数仍沿用既有全量保存规则，应先读取配置并回传需要保留的其他参数与版本号。

同一工作项或同一定时任务运行分别计数，按 Agent ID 区分：首次进入 QA 不升级，QA 返回 RD 时
RD 的进入次数增加。同一 Agent 内步骤推进、重试、恢复和评论交互不增加次数。
到达最后一级后保持最后一级；新任务重新计数并使用默认模型。
既有自动交接次数上限及转人工逻辑保持有效，不因升级策略增加交接预算。

每个 dispatch 的选择结果写入 `dispatch_model_selection`，后续发送复用冻结结果，避免重复升级。
启动日志包含实际模型、进入次数、命中阈值、策略版本和原因码：
`POLICY_DISABLED`、`THRESHOLD_NOT_REACHED`、`ENTRY_THRESHOLD_REACHED`、`INTERACTION`、`MODEL_UNAVAILABLE`。
目录中已不可用的 Qoder 升级模型不会下发，改用执行器默认配置并记录 `MODEL_UNAVAILABLE`。

## 上线顺序

1. 执行 `docs/migration/V080__dispatch_model_selection.sql`，创建决策快照表。
2. 发布并启动支持 `TASK_MODEL_SELECTION_V1` 的 runtime。
3. 发布服务端和前端，再按需启用执行器策略。

未达到升级阈值时仍兼容旧 runtime；实际升级要求 runtime 声明上述协议能力，否则显式失败，
防止服务端记录已升级但执行器仍用旧模型。runtime 日志和新任务默认模型行为的验证不等同于
已完成真实 provider 的端到端启动验收。
