# Agent.md / Soul.md 坏模式检测器(4 特征)

读 system prompt(从 events.jsonl 的首个 turn.started 事件的 systemPrompt 字段),检测:

## 1. 只有禁止式约束,无正向规模界
**检测:** 约束/规则部分的全部条目以"不得/禁止/不可/不要"开头,无一条描述:
- 探索范围(如"只读直接相关文件")
- 输出体量(如"不超过 800 字符")
- 工具使用限制(如"不派子代理")

**影响:** 与 SDLC 的 `budget=None` 叠加,agent 在"做多少"维度完全无约束。

---

## 2. 重复 SDLC 已说的事实
**检测:** system prompt 中的环境描述(端口/技术栈/仓库地址)在 SDLC 步骤指令或平台 contract 中重复出现,且措辞不同。

**影响:** 措辞漂移 → agent 可能在两种表述间困惑。

---

## 3. 职责段复述完整流水线
**检测:** system prompt 的职责/Responsibilities 部分列举了 ≥4 个动作,且这些动作与 SDLC 步骤一一对应(如"分析需求→建分支→编码→测试→push→MR")。

**影响:** 与平台的"Execute only this active step"形成张力。agent 在任一步都看到完整流水线是它的职责。

---

## 4. 不声明角色实际拥有的能力
**检测:** system prompt 中无 MCP/skill/CLI 清单,也无"具体能力由平台 Available Capabilities 清单声明"的指引。

**注:** 如果平台 v0.2.135+ 已注入 `## Available Capabilities`,此项可标为"已由平台覆盖"而非问题。
