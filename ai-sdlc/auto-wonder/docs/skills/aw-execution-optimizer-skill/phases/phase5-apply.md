# Phase 5: 应用

## 询问用户

> 优化方案已生成,你可以在 `~/aw-diagnosis-{date}/` 中查看。
>
> 应用方式:
> 1. **通过 MCP 自动应用** — 我来调用 `update_agent` 和 `update_sdlc_step`,你确认即可
> 2. **手动复制** — 打开 rewrite 目录里的文件,复制到管理界面
>
> 选哪种?

## MCP 自动应用流程

### ⚠️ 关键安全规则:`update_agent_config` 是全量替换

**`autowonder.update_agent_config` 不是 partial update —— 没传的字段会被服务端置为 null。**

这意味着:如果只传 `agentMd` 而省略 `roleCode`/`roleName`/`soulMd`,这些字段会被清空。
清空 `roleCode` 的后果:该 agent 在下游 dispatch 的 roster 中丢失角色标识,导致 handoff 被 runtime 拒绝
("unknown handoff target"),整条链路断裂。

**实测教训(2026-08-24):** 更新 agent 40013/40015 时只传了 `agentMd`,导致 `roleCode=null`。
CR 尝试交接 QA 时 runtime 返回 `"unknown handoff target \"QA\""`,因为 roster 中 40015 的条目
变成了 `{"agentId":40015}`(无 roleCode)。agent 正确降级到交接真人,但 QA 没被自动派发。

### 正确的 Agent 更新步骤

```
1. 先读取当前配置(获取要保留的字段):
   autowonder.get_agent(workspaceId, id)
   → 记录: roleCode, roleName, businessBackground(soulMd), responsibilities(agentMd)

2. 合并你要改的字段到完整 payload:
   autowonder.update_agent_config(
     workspaceId,
     agentId,
     roleCode = <从 get_agent 读到的,或你要改的新值>,
     roleName = <从 get_agent 读到的,或你要改的新值>,
     soulMd   = <从 get_agent 读到的,或你要改的新值>,
     agentMd  = <你优化后的全文>
   )

3. 提交审核 + 发布:
   autowonder.submit_agent_for_review(workspaceId, id)
   autowonder.publish_agent(workspaceId, id)

4. 验证(发布后):
   autowonder.get_agent_version(workspaceId, agentId, versionNo=<latest>)
   → 确认 roleCode/roleName/soulMd 非空
```

**必须传的字段清单(每次都传,无例外):**

| 字段 | 对应 API 参数 | 清空后果 |
|---|---|---|
| 角色代码 | `roleCode` | roster 丢失角色标识 → handoff 失败 |
| 角色名称 | `roleName` | 管理界面显示异常 |
| Soul.md | `soulMd` | agent 丢失业务背景(system prompt 中 Business Background 段消失) |
| Agent.md | `agentMd` | agent 丢失 Scope/Boundaries/Scale |

### SDLC 步骤更新(无此风险)

`autowonder.update_sdlc_step` 是 **partial update** —— 只传你要改的字段,未传的保持不变。
所以只改 `instructionMd` 不会影响 `checklistJson`/`gatePolicyJson`。

```
autowonder.update_sdlc_step(workspaceId, sdlcId, stepId, instructionMd=<新内容>)
→ checklistJson / gatePolicyJson 保持原值 ✓
```

### 完整应用流程

1. **Agent 更新**(每个数字人):
   - `get_agent` → 记录 roleCode/roleName/soulMd
   - `update_agent_config` → **全量传入**所有字段(含未改的)
   - `submit_agent_for_review` → `publish_agent`
   - `get_agent_version` → 验证非空

2. **SDLC 步骤更新**(每步):
   - `update_sdlc_step` → 只传要改的字段即可(partial update)

3. 如果 SDLC 是 disabled/草稿态,更新后提示用户重新 enable

4. 输出应用结果:
   ```
   ✓ Agent {name} 已更新 (roleCode={code}, roleName={name}, soulMd={len}字符, agentMd={len}字符)
   ✓ SDLC step {id}「{name}」instructionMd 已更新
   ...
   ```

## 手动复制指引

> 请按以下顺序操作:
> 1. 打开 AutoWonder 管理界面 → 数字人管理
> 2. 找到 {name} → 编辑 → 将 `agent-profile.md` 中的内容粘贴到对应字段
> 3. 打开 SDLC 管理 → 找到对应的 SDLC → 编辑步骤
> 4. 将 `sdlc-steps.json` 中每步的 instructionMd/checklistJson/gatePolicyJson 粘贴到对应字段

## 验证提示

应用完成后:
> 建议重跑同一个工单(或同类型的新工单)来验证效果。
> 重跑后把新的日志路径给我,我可以做前后对比分析。
