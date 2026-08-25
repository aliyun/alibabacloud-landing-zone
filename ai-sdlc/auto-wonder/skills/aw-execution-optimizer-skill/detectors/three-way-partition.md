# 三分法分区器

## 算法

遍历 agent 事件流(从 `events.jsonl` 或 `--debug` 日志),按相邻事件对分类每段时间间隔:

| 前一事件 | 后一事件 | 分类 |
|---|---|---|
| `tool_result` | 模型输出(`thinking`/`text`/`tool_use`) | **WAIT** |
| `tool_use` | `tool_result` | **TOOL** |
| 模型输出 | 模型输出 | **STREAM** |
| `tool_result` | `tool_result` | **TOOL**(并行批次) |
| 其余 | 其余 | **STREAM** |

模型输出事件 = `thinking`, `text`, `tool_use`, `message`

## 输出指标

每步:
- 墙钟(秒)
- WAIT 总量 + 占比 + 段数 + 中位数 + 最大值
- STREAM 总量 + 占比
- TOOL 总量 + 占比
- 往返次数(= WAIT 段数)

全局:
- 十步合计
- 按角色(DEV/CR/QA)合计

## 解读标准

| 模式 | 含义 | 方向 |
|---|---|---|
| WAIT > 60% | 模型推理主导,可能有并发争抢(子代理) | 检查是否有 Agent 工具调用 |
| STREAM > 50% | 模型输出体量大,可能无输出约束 | 检查有无硬模板 |
| TOOL > 40% | 工具执行时间长,可能有不必要的命令 | 检查 Top-5 最耗时命令 |
| WAIT 中位 > 5s | 高于正常(正常 ~2-4s) | 可能有并发争抢或上下文过大 |

## 注意事项

- 子代理运行期间主线的 WAIT 会异常高(同 provider 并发额度争抢)
- 每轮 resume 后的首个往返可能分类有误(前一事件是 `agent status` 而非 `tool_result`)
- `durationMs` 是批次累计,**不可求和**(并行工具的 durationMs 各自包含等待时间)
