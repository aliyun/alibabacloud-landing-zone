# 三分法精确算法

## 事件行正则
```
^\[(\d\d:\d\d:\d\d\.\d{3}) \+[\d.]+s\] d=(\S+) agent (\S+) (.*)$
```

或从 events.jsonl:
```json
{"type": "agent.tool_use|agent.tool_result|agent.message|llm.thinking_started|llm.thinking_completed", ...}
```

## 分类规则(伪代码)

```python
MODEL_EVENTS = {"thinking", "text", "tool_use", "message"}

for i in range(1, len(events)):
    prev_kind, curr_kind = events[i-1].kind, events[i].kind
    interval = events[i].time - events[i-1].time
    
    if prev_kind == "tool_result" and curr_kind in MODEL_EVENTS:
        bucket = "WAIT"      # 模型收到工具结果后的思考延迟
    elif prev_kind == "tool_use" and curr_kind == "tool_result":
        bucket = "TOOL"      # 工具真实执行时间
    elif prev_kind in MODEL_EVENTS and curr_kind in MODEL_EVENTS:
        bucket = "STREAM"    # token 生成中
    elif prev_kind == "tool_result" and curr_kind == "tool_result":
        bucket = "TOOL"      # 并行批次排空
    else:
        bucket = "STREAM"    # 兜底
```

## 已知边界

1. **子代理运行中** — 主线 WAIT 会被人为抬高(并发额度争抢),不代表模型真的在思考
2. **resume 首轮** — 前一事件是 `agent status` 而非 `tool_result`,朴素分类器会误归 STREAM
3. **`durationMs` 字段** — 是"每批累计"不是"每次调用",绝不可求和
