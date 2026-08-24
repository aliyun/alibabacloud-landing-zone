# Phase 2: 深度分析

## 核心方法:SDLC 指令 × 执行行为 × 日志 × agent 行为 交叉研判

对每个数字人的每个 SDLC 步骤,执行以下分析。这不是浅层的 grep 扫描,而是像一个有经验的工程师审查执行记录一样,综合多维数据做判断。

---

## 2.1 三分法分区(全局 + 逐步骤)

用严格三分法把每步的墙钟拆成 WAIT / STREAM / TOOL:

- **WAIT** = tool_result 之后、模型首 token 之前的沉默(纯模型推理延迟)
- **STREAM** = 模型输出行之间(token 生成中)
- **TOOL** = tool_use 到 tool_result 之间(工具真实执行)

详细算法见 `detectors/three-way-partition.md`。

**输出:** 每步的耗时 + WAIT/STREAM/TOOL 占比 + WAIT 中位数 + 往返次数。

---

## 2.2 行为信号检测

逐步骤检测 8 类已被实证验证的浪费模式:

| 信号 | 检测方法 | 详见 |
|---|---|---|
| 子代理白派 | tool_use 中 tool=Agent + 同步主线做相同 Read/Grep | `detectors/behavior-signals.md` |
| 步骤边界重做 | agent.message 流含特定关键词 | 同上 |
| 浏览器/工具下载 | bash.call 含 playwright install / 大体量 npm install | 同上 |
| CR 重跑测试 | CR dispatch 中出现 vitest/mvn test/go test | 同上 |
| 能力穷尽探测 | 连续多次 which/npm view/ls 查找不存在工具 | 同上 |
| 广度搜索无贡献 | Read 文件数 vs diff 改动文件数 | 同上 |
| 覆盖率工具自造 | Write 自定义脚本算覆盖率 | 同上 |
| 后台任务后弃回合 | 长命令放后台 + 回合内无 completion_requested | 同上 |

---

## 2.3 语义分析(核心差异化能力)

对每步做以下交叉研判:

### 输入
1. **SDLC 步骤指令**(instructionMd):agent 被要求做什么
2. **Agent.md + Soul.md**(system prompt):agent 的角色边界
3. **agent 事件序列**(tool_use / tool_result / thinking / message):agent 实际做了什么
4. **三分法数据**:时间花在哪里
5. **evidenceRefs + artifact.sealed**:产出了什么

### 分析维度

**A. 指令 vs 行为对齐度**
- 指令说做什么 → agent 做了吗?
- agent 做了指令没说的事 → 是合理推理(如读上游交付物)还是无约束膨胀(如从零搭框架)?
- 判据:如果删掉那个动作,步骤的交付物是否仍然完整?

**B. 工具使用效率**
- Read/Grep/Bash/Agent/Write 比例
- 最耗时的 Top-5 命令,每条判断:必要 / 重复 / 环境导致 / 可避免
- 同一文件被读多少次(>2 次可能是 agent 在循环猜)

**C. 输出体量与结构**
- agent.message 流的总字符数(STREAM 时间的直接度量)
- 是否遵循了固定模板(有模板 → 输出被压住;无模板 → 可能膨胀)
- 评论内容是否结构化(有小节标题 vs 散文一大段)

**D. 与前后步骤的衔接**
- 当前步是否重做了上一步已 accepted 的工作?
- evidenceRefs 里的文件,是新产出的还是引用前步的?
- 工单状态是否在正确的步骤被改变?

**E. 环境与能力对齐度**
- 指令要求用某工具(如 code MCP)→ 平台 Available Capabilities 里有没有?
- agent 花了多少时间探测一个不存在的能力?
- 如果能力不可用,agent 的降级行为是否合理(直接记录 vs 穷尽探测)?

---

## 2.4 SDLC 坏模式扫描

直接读 sdlc.json,对每步的 instructionMd 检测八个特征。详见 `detectors/sdlc-bad-patterns.md`。

---

## 2.5 Agent.md/Soul.md 坏模式扫描

读 system prompt,检测四个特征。详见 `detectors/agent-bad-patterns.md`。

---

## 2.6 提示词注入核查

验证平台侧的事实注入是否到位。详见 `detectors/prompt-injection-check.md`。

---

## 2.7 跨步骤复用分析

详见 `detectors/cross-step-reuse.md`。

---

## 2.8 环境异常检测

详见 `detectors/env-anomaly.md`。

---

## 输出

创建 `~/aw-diagnosis-{date}/observation.md`,结构:

```markdown
# 执行观测记录

## 全局概览
- 总耗时: Xm Ys
- 三分法: WAIT X% / STREAM Y% / TOOL Z%
- 数字人: DEV Xm / CR Ym / QA Zm

## 逐步骤分区表
(表格)

## DEV · 步骤 400164「需求分析」
### 三分法
### 行为信号
### 语义分析
### 问题发现

## DEV · 步骤 400165「编码实现」
...

(每个步骤一个 section)
```

## 分析原则

在做上述分析时,遵循 `principles/` 目录下的原则:
- `analysis-principles.md` — 实证优先,不凭推断
- `good-sdlc-criteria.md` — 好 SDLC 的三条正面标准
- `platform-vs-business.md` — 平台事实 vs 业务指令的边界
- `evidence-standards.md` — 证据等级(实证/推断/零影响)

---

## 2.9 Gate / Checklist / Budget 配置推导(贯穿分析过程)

**这不是 Phase 4 才做的事 —— 在分析每步行为时就要同步思考。**

### 方法:从行为问题反推配置

对每步发现的问题,立即评估能否通过三层机制解决:

| 发现的问题 | 能用 budget notes 解决? | 能用 checklist 解决? | 能用 gatePolicy 硬卡? |
|---|---|---|---|
| agent 派了子代理 | ✓ "不派子代理" | — | — |
| agent 下载了新工具 | ✓ "不下载新工具" | — | — |
| agent 没写证据就完成了 | — | ✓ "证据已保存到 evidence/" | ✓ `evidenceRequired:true` |
| agent 用散文写汇报 | ✓ "严格按模板" | ✓ "字段完整" | — |
| agent 重跑了上游测试 | ✓ "不运行测试" | — | — |
| agent 忘了改工单状态 | — | ✓ "工单状态已改" | — |
| agent 没 push 就完成了 | — | ✓ "分支已 push" | ✓ 可用 gitDelivery |

### 三层机制的使用原则

```
budget notes  → 行为边界(不准做什么)→ 软约束,只靠提示词引导
checklist     → 交付检查项(做完了什么)→ 中约束,提醒 + 可选硬卡
gatePolicy    → 质量底线(必须有什么)→ 硬约束,不过不让完成
```

### budget notes 的写法原则(从实战沉淀)

1. **只写行为边界,不写数字** — "不派子代理"比"最多 25 次调用"更有效且不会逼 agent 敷衍
2. **每条都是"不准做 X"** — 正向约束不如负向约束精确(agent 对禁令的遵守率远高于建议)
3. **与该步的实测问题一一对应** — 不凭空想,每条 notes 都源于实际观测到的浪费行为

### checklist 的写法原则

1. **是交付检查项,不是执行步骤** — "代码已提交"而不是"运行 git add && git commit"
2. **每项可独立验证** — agent 能自己判断"是/否",不需要外部信息
3. **与 gatePolicy 对齐** — 如果某项失败会导致 gate 拒绝,把它写进 checklist 让 agent 提前注意
4. **不超过 6 项** — 太多会被 agent 忽略

### gatePolicy 的配置原则

1. **evidenceRequired: true** — 只要该步有实质产出(代码/报告/日志),就开启
2. **requiredArtifacts** — 只用目录前缀(如 `"evidence/"`),不要精确文件名(agent 命名可能不同)
3. **checklistRequired** — 通常不开(checklist 是引导,不是硬卡;硬卡容易因小项遗漏而反复重试)
4. **verificationCommands** — 如果有明确的验证命令(如 `npm test`),由 runtime gate 自己跑,比让 agent 跑更可靠

### 在 Phase 2 的每步分析中如何体现

在每步的语义分析结论里,附一个"配置推导"小节:

```markdown
### 配置推导(400165 编码实现)
- budget notes: "本步只做编码和编写测试用例,不运行测试"(源自:观测到 agent 在编码步跑了测试)
- checklist: ["工单状态已改", "代码已提交", "测试用例已编写"](源自:三项交付物)
- gatePolicy: evidenceRequired=false(编码步不产证据文件,产出是代码本身)
```

这样到 Phase 4 生成方案时,每步的 gate/checklist/budget 都是有实证基础的,不是凭空设计。

---

## 2.10 结论自审(防止自己犯错)

**我们自己三轮实战中多次推翻了自己的结论。把这些教训内化为自审规则:**

### 常见的错误归因

| 我们犯过的错 | 正确做法 |
|---|---|
| "P5 缓存已预热是跨轮污染" → 实际是 HOME 不可覆盖,npm cache 本来就共享 | 查 `isBlockedEnvKey` 等实际代码再下结论 |
| "esbuild 19s 是平台步骤边界导致的" → 实际 repos 目录步骤间根本不碰 | 用 `sealStepAttempt` 的实际逻辑验证,不信 agent 的自我归因 |
| "400165 慢是模型波动" → 实际是 TOOL 暴增(下载 chromium 5m29.6s) | 只看调用次数不够,必须看分区后的 TOOL 绝对时长 |
| "P-C3 证据归属 100% 失效" → 实际没有任何消费方依赖这个字段 | 六项检查全为 0,是潜在缺陷不是当前故障 |
| "STREAM 不可压缩" → STREAM 降了 12m39s(48.5%→36.8%) | STREAM 与"要叙述多少动作"耦合,减少重复工作间接压缩 |

### 自审清单(每个结论都过)

- [ ] 这个结论有没有直接的执行数据支撑(时间戳+命令)?
- [ ] agent 的自述("output 被清空")是事实还是它自己的错误归因?
- [ ] 我是不是只看了调用次数没看时长(或反过来)?
- [ ] 这个问题在本轮是真的造成了后果,还是只是理论上有问题?
- [ ] 我有没有把两个不同原因导致的现象归到了同一个?
- [ ] 这个因果归因有对照组吗?(同条件下的另一个 dispatch/步骤是否也出现同样现象;没有对照的"相关"只是相关,不是因果)
- [ ] 结论写进报告前,我是否重新打开日志逐条核对过证据,而不是凭分析过程中的印象?

---

## 2.11 STREAM 间接压缩的洞察

**全局 STREAM 从 31m54.7s(48.5%)降到 19m15.6s(36.8%),降 12m39.1s —— 是全局最大的单项变化。**

机制:STREAM = 模型的输出体量,而输出体量与"它要叙述多少动作"强耦合。当 agent 不再重做证据、不再枚举版本、不再从零搭 harness 时,它需要输出的推理与叙述同步减少。

**⇒ 平台/SDLC 不能直接命令模型少说话,但可以通过消除重复工作间接压缩 STREAM。**

在分析中:如果某步 STREAM 占比 >60% 且不是硬模板约束的步骤,检查是否有"做了不必要的事然后花大量 STREAM 在叙述它"的模式。

---

## 2.12 提示词三层送达强度(影响建议放哪里)

| 层 | 体量实测 | 送达强度 | 应放什么 |
|---|---|---|---|
| system prompt(Agent.md + Soul.md) | ~500-700 字符 | **每 turn 强制** | 角色边界、正向规模界(Scale) |
| 平台 contract(runtime-contract.md) | ~20KB | **只在第一步被读 1 次,后续依赖 session history** | 完整契约(但关键事实必须同时在 step prompt) |
| 每 turn step prompt | ~2-3KB | **每步强制** | SDLC 指令 + budget notes + checklist + 动态清单 |

**实战教训:** 400166 之所以正确复用产出,不是靠 contract 里的 Artifact Lifecycle(那步 agent 没重读 contract),而是靠每步强制注入的 `## Already Accepted Output` 清单。

**⇒ 要保证一条规则/事实生效,必须放进每 turn 的 step prompt(instructionMd / checklist / gate),不能只放 Agent.md(虽然每 turn 有,但体量有限) 或 contract(送达不稳定)。**

这直接决定优化建议的"放哪里":
- 行为边界(不准做什么)→ instructionMd 的【预算约束】段(每步强制送达)
- 交付检查项 → checklist(每步渲染)
- 角色级通用约束 → Agent.md 的 Scale 段(每 turn 强制)

---

## 2.13 并发争抢的同步骤内对比法(更强的证据方法)

基线只能跨步骤对比(窗口② 10.86s vs 其他步骤 2.1-3.0s),存在"步骤间本就不同"的混淆。

**更强的方法:同一步骤内部做前/中/后三段对比,排除步骤间差异:**

```
派出前 → WAIT 中位 3.43s(无子代理)
运行中 → WAIT 中位 9.72s(子代理在跑)    ← 2.8 倍抬升
返回后 → WAIT 中位 3.67s(无子代理)
```

同步骤、同 model、同 context,唯一变量是"有没有并发子代理" ⇒ 因果关系确立。

如果在分析中发现某步 WAIT 异常高(中位 >5s),先检查是否有子代理在同时跑。如果有,用这个三段对比法量化争抢成本。
