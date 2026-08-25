# Phase 4: 优化方案生成

## 原则

1. **不改变原有交付语义** — 建分支/编码/测试/push/MR/评论/交接等核心流程不动
2. **只消除已实证的浪费与歧义** — 不做预防性的"也许有用"的改动
3. **每条改动可验证** — 附预期信号(重跑后应该/不应该看到什么)
4. **不假设业务形态** — 不写死任何工具名/版本/路径

## 生成内容

### 对每个数字人的 Agent.md / Soul.md

```markdown
# {角色名}

## Role
...
## Scope
(一句话交付物)
## Boundaries
(保留有效的禁止式)
## Scale
(新增正向规模约束,基于实测发现的问题)
```

### 对每个 SDLC 步骤

```json
{
  "instructionMd": "优化后的指令(含【预算约束】段)",
  "checklistJson": "[{\"id\":\"...\",\"text\":\"...\"}]",
  "gatePolicyJson": "{\"evidenceRequired\":true,...}"
}
```

### 变更说明

每个 rewrite 目录含 `CHANGES.md`:
- 逐字段对照(原 → 新 + 理由)
- 语义保留确认表
- 预期效果信号

## 输出

```
~/aw-diagnosis-{date}/
├── {dev-role-name}-rewrite/
│   ├── agent-profile.md
│   ├── sdlc-steps.json
│   └── CHANGES.md
├── {cr-role-name}-rewrite/
│   ├── ...
├── {qa-role-name}-rewrite/
│   ├── ...
└── verification.md         ← 重跑后的验证方案
```

---

## 交叉 Review(生成方案后必做)

**从我们三轮实战中踩过的坑总结出三条 review 维度:**

### 1. 真实性 Review — 方案是否与 Phase 2/3 的问题呼应对照

| 检查 | 怎么做 |
|---|---|
| 每条 budget notes 是否对应一个实测到的浪费? | 回溯 Phase 2 的观测,找到具体的 seq/时间戳/命令 |
| 每条 checklist 是否对应一个该步的必要产出? | 对照 artifact.sealed 的 fileCount 和 evidenceRefs |
| gatePolicy 的设置是否会误拒? | 检查 `requiredArtifacts` 是否用了目录前缀(尾部 `/`),agent 上报的文件名是否能匹配上 |
| 删掉的指令段是否真的没有交付价值? | 重读原指令,确认被删的是"兜平台缺口的散文"而非"核心交付动作" |

**我们踩过的真实坑:**
- `requiredArtifacts: ["evidence/"]` → controller 层精确匹配 → 永远通不过(直到修了前缀匹配)
- 删了"涉及前端界面时补充真实浏览器验收路径"→ 但没给降级说明 → 实际上需要说"只跑仓库已有的"
- 把 budget notes 里写了 `maxMinutes: 3` → 可能逼 agent 敷衍复杂任务(后来删了数字只留行为边界)

### 2. 有效性 Review — 方案是否真的能解决问题

| 检查 | 怎么做 |
|---|---|
| budget notes 的约束 agent 能遵守吗? | 看 Scale 段的同类约束在实测中是否被遵守(如"不派子代理"在第三轮确实没再派) |
| gate 会不会导致无限重试? | 确认 agent 在指令中被告知 gate 会检查什么(如"gate 会校验该目录非空,缺失会被拒回") |
| 硬模板是否覆盖了全部必要字段? | 对照原来散文式要求里提到的所有信息项 |
| 平台 Available Capabilities 是否已生效? | 检查 runtime-contract.md 中是否有该段(需要执行器 v0.2.135+) |
| 新写的约束会不会把并发一起禁掉? | 全文搜自己产出的 instructionMd:出现"不要放到后台"/"禁止 `&`"/"逐个执行"/"串行"就是踩了特征 8b。长命令步骤只允许用【执行方式】固定模板 |

**这条是我们自己踩过的:** 第一版把约束写成"在当前回合内前台执行,不要放到后台",打在 `&` 字符上而不是回合边界上,顺手禁掉了 `cmd1 & cmd2 & wait` 这种安全的并行写法。实测数据显示同一步骤三个前台调用并发只需 119.6 秒、串行要约 358 秒 —— 写反一句话就是三倍墙钟。

### 3. 全面性 Review — 是否有遗漏或陷入局部视角

| 检查 | 怎么做 |
|---|---|
| 是否只优化了时间最长的步骤而忽略了短步骤的结构性问题? | 回看全部步骤的 SDLC 坏模式扫描结果 |
| 是否修了 SDLC 但漏了 Agent.md? | S1-S4 的四个问题是否都在 Agent.md 重写中体现 |
| 是否漏掉了跨步骤的问题? | 检查"复用上游产出"的声明是否在每个相关步骤都有 |
| 是否改了指令但忘了对齐 checklist/gate? | 指令里说"保存到 evidence/" → checklist 里应有对应项 → gate 应设 evidenceRequired |
| 交付语义是否被意外改变? | 列出所有原有的核心交付动作(建分支/测试/push/MR/交接),逐个确认保留 |

---

## SDLC 配置实际写法案例(供客户参考)

**重要说明:** `budget` 这个字段在当前服务端数据结构中**没有独立的列**。我们在实践中是把 budget notes **直接写进 `instructionMd` 的末尾**,以 `【预算约束】` 段落的形式。平台 runtime 会把整个 instructionMd 渲染进每步的 `## Step Instruction`,因此效果等同于独立的 budget 段落。

### 完整的 SDLC 步骤配置案例(开发数字人 · 自测与交付步骤)

```json
{
  "instructionMd": "运行与本次改动相关的已有测试(仓库中已配置的测试命令),全部通过后将日志保存到 artifacts/output/evidence/;gate 会校验该目录非空。不要搭建仓库中不存在的测试框架,不要下载新的运行时或浏览器。如果某类测试在当前环境无法运行(缺依赖/缺服务),在证据中记录原因并标注为\"不适用\",不要因此判定失败。\n\n自测发现问题时直接修复并重跑,直到全部通过。\n\n本地构建通过后提交所有改动并 push 业务分支。用 code MCP 创建 MR(若该能力可用);不可用时标记 MR_NOT_CREATED 并在证据中给出创建入口链接。\n\n交付信息包含 branch、baseCommit、headCommit、MR 状态。返工逐项更新反馈编号为 FIXED/OPEN/REGRESSED。\n\n【执行方式】测试、构建、依赖安装等长命令要在当前回合内拿到结果。鼓励并行发起(并行工具调用,或 `cmd1 & cmd2 & wait` 合成一条命令),但不要发起后就结束回合去等通知。\n\n【预算约束】只运行仓库已有的测试命令;不搭建新的测试框架或下载新工具;复用前步已 accepted 的产出。",
  
  "checklistJson": "[{\"id\":\"tests-pass\",\"text\":\"运行相关测试全部通过\"},{\"id\":\"evidence-saved\",\"text\":\"测试日志已保存到 artifacts/output/evidence/\"},{\"id\":\"build-pass\",\"text\":\"本地构建通过\"},{\"id\":\"pushed\",\"text\":\"业务分支已 push\"},{\"id\":\"mr-status-reported\",\"text\":\"MR 状态已汇报(真实链接或 MR_NOT_CREATED + 创建入口)\"}]",
  
  "gatePolicyJson": "{\"evidenceRequired\":true,\"requiredArtifacts\":[\"evidence/\"]}"
}
```

### 字段解说

| 字段 | 在哪里消费 | 效果 |
|---|---|---|
| `instructionMd` | 渲染为 `## Step Instruction`,每步强制送达 agent | agent 看到具体要做什么 + 什么不做 |
| `instructionMd` 中的 `【预算约束】` | 同上(与指令一起渲染) | 等效于 budget.notes,告诉 agent 行为边界 |
| `checklistJson` | 渲染为 `## Checklist`,每步强制送达 | agent 看到待完成的检查项列表 |
| `gatePolicyJson.evidenceRequired` | gate 硬校验:agent 的 evidenceRefs 必须非空 | 没声明证据就不让通过 |
| `gatePolicyJson.requiredArtifacts` | gate 硬校验:该路径前缀下必须有文件 | artifacts/output/evidence/ 目录必须非空 |

### 配置写法要点

**instructionMd 的结构:**
```
[核心动作描述]

[降级/异常处理说明]

[交付物说明]

【执行方式】[仅当该步有长命令时;直接抄 detectors/sdlc-bad-patterns.md 特征 8 的模板]

【预算约束】[行为边界,不写数字]
```

**【执行方式】的固定模板**(有长命令的步骤必加,替换开头的动作名即可,其余原样):
```
【执行方式】测试、构建、依赖安装等长命令要在当前回合内拿到结果。鼓励并行发起:把多条命令合成一条前台命令 `cmd1 & cmd2 & wait`,或在一个回合内并发发起多个前台工具调用。不要用工具的后台任务模式(run_in_background)发起后再反复读输出文件或 sleep 等待,那会把一次阻塞等待换成多个来回;也不要发起后就结束回合去等通知。
```
不要简写成"不要放到后台"或"前台执行"——那会把 `& wait` 并行一起禁掉,用失败换降速。理由与实测数据见特征 8 和 8c。

**与旧版模板的区别:** 旧版只禁"结束回合去等通知"(8a),新版额外点名了 `run_in_background` 这个 provider 工具参数(封堵 8c)。实测:旧模板下 agent 字面合规但仍用后台+轮询浪费 19.8%;新模板下同步骤 0 次后台调用。

**checklistJson 的写法:**
```json
[
  {"id": "unique-key", "text": "可独立判断是/否的交付检查项"}
]
```
- id 是唯一标识(英文,无空格)
- text 是 agent 看到的描述
- 不超过 6 项

**gatePolicyJson 的写法:**
```json
{
  "evidenceRequired": true,          // agent 必须声明至少一个证据路径
  "requiredArtifacts": ["evidence/"] // 目录前缀匹配(尾部带 /)
}
```
- `requiredArtifacts` 带尾部 `/` = 前缀匹配(该目录下有任何文件即通过)
- `requiredArtifacts` 不带 `/` = 精确匹配(必须有这个确切文件名)
- 建议只用目录前缀,不用精确文件名(agent 命名可能不同)

### 不同步骤类型的配置模式

| 步骤类型 | instructionMd 特点 | gate | 示例 |
|---|---|---|---|
| **分析/规划步骤** | 轻量;预算约束"不派子代理" | `evidenceRequired: false` | 需求分析 |
| **编码步骤** | 只编辑+commit,不编译不测试;预算约束显式列出被禁命令名(mvn compile/test, npm run build, vitest, go build/test 等);约束 commit 粒度 | `evidenceRequired: false` | 编码实现 |
| **测试/验证步骤** | 跑已有测试;加【执行方式】模板;预算约束"不搭新框架" | `evidenceRequired: true` | 自测与交付 |
| **评审步骤** | 只读 diff 不跑测试;预算约束"不安装依赖" | `evidenceRequired: true` | 代码评审 |
| **部署步骤** | 读能力清单;预算约束"不在清单外探测" | `evidenceRequired: true` | 条件部署 |
| **汇报/交接步骤** | **硬模板**;预算约束"不自由发挥" | `evidenceRequired: false` | 完成汇报 |

### 通过 MCP 应用的完整命令

```bash
# 更新步骤(需要 SDLC 处于可编辑状态)
curl -s "<MCP_URL>" -H "Content-Type: application/json" -d '{
  "jsonrpc": "2.0", "id": 1,
  "method": "tools/call",
  "params": {
    "name": "autowonder.update_sdlc_step",
    "arguments": {
      "workspaceId": <你的 workspace id>,
      "sdlcId": <SDLC id>,
      "stepId": <步骤 id>,
      "instructionMd": "<优化后的指令全文>",
      "checklistJson": "<JSON 数组字符串>",
      "gatePolicyJson": "<JSON 对象字符串>"
    }
  }
}'
```

**注意:** 如果返回"流程非草稿状态,无法编辑结构",需要先在管理界面把 SDLC disable(改为草稿态),改完后重新 enable。`instructionMd` 在部分状态下可直接修改而无需 disable。
