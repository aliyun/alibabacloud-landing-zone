# Phase 1: 采集与解析

## 目标

从 `--debug` 日志和 capsule 目录中提取全部分析所需的原始数据。

## 步骤

### 1.1 从日志中定位 capsule 目录

日志中的 `dispatch.prepare_started` 事件包含 `packageDir`:
```
[HH:MM:SS.xxx] d=<dispatchId> event dispatch.prepare_started ... packageDir=<path>/package
```

capsule 根 = `packageDir` 的上两级目录。

### 1.2 提取 events.jsonl

事件流在运行期位于 `<capsule>/state/events.jsonl`,完成后移到 `<capsule>/observability/events.jsonl`。两个位置都检查。

### 1.3 提取关键配置文件

从每个 dispatch 的 capsule 中读取:

| 文件 | 位置 | 内容 |
|---|---|---|
| SDLC 定义 | `<capsule>/package/sdlc.json` | 步骤指令、checklist、gatePolicy |
| 契约文档 | `<capsule>/bootstrap/runtime-contract.md` | 平台注入的事实 |
| 工单内容 | `<capsule>/package/workitem.md` | 任务描述 |
| 仓库配置 | `<capsule>/package/repos.json` | 仓库清单 |
| MCP 配置 | `<capsule>/package/skills.json` | 声明的能力 |

### 1.4 提取 system prompt(Agent.md + Soul.md)

从 events.jsonl 中第一个 `turn.started` 事件的 `payload.systemPrompt` 字段。

### 1.5 通过 MCP 获取补充数据(可选)

如果 MCP 可用:
- `autowonder.get_agent` → 获取最新的 Agent 定义(可能比 capsule 里的新)
- `autowonder.get_sdlc` → 获取最新的 SDLC 定义
- `autowonder.list_workitem_comments` → 获取工单评论(用于评估评论质量)

### 1.6 输出

创建 `~/aw-diagnosis-{date}/data-index.md`:

```markdown
# 数据索引

## Dispatches
- DEV: dispatchId=XXXX, capsule=<path>, steps=[400164, 400165, ...]
- CR:  dispatchId=XXXX, capsule=<path>, steps=[400169, 400170, ...]
- QA:  dispatchId=XXXX, capsule=<path>, steps=[400173, 400175, ...]

## 配置
- SDLC: sdlcId=XXXXX, steps=N
- Agent: id=XXXXX, name=<name>
- 工单: <title>

## 可用数据
- events.jsonl: ✓/✗ (每个 dispatch)
- sdlc.json: ✓/✗
- system prompt: ✓/✗
- MCP 补充: ✓/✗
```
