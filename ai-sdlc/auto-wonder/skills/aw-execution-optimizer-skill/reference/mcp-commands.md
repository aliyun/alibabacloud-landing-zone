# MCP 调用参考

## 更新数字人定义

> **⚠️ `update_agent_config` 是全量替换,不是 partial update。**
> 没传的字段会被置为 null。**必须每次都传全部字段**,即使只想改其中一个。
> 清空 `roleCode` 会导致该 agent 在 roster 中丢失角色标识 → handoff 被 runtime 拒绝。

### 安全的更新流程

```json
// Step 1: 先读当前配置
{
  "method": "tools/call",
  "params": {
    "name": "autowonder.get_agent",
    "arguments": { "workspaceId": <int>, "id": <agentId> }
  }
}
// → 从响应中记录: roleCode, roleName, businessBackground(=soulMd), responsibilities(=agentMd)

// Step 2: 全量更新(合并你要改的字段 + 保留原值的字段)
{
  "method": "tools/call",
  "params": {
    "name": "autowonder.update_agent_config",
    "arguments": {
      "workspaceId": <int>,
      "agentId": <agentId>,
      "roleCode": "<必传,从 get_agent 保留或新值>",
      "roleName": "<必传,从 get_agent 保留或新值>",
      "soulMd": "<必传,Business Background 全文>",
      "agentMd": "<必传,Scope + Boundaries + Scale 全文>"
    }
  }
}

// Step 3: 提交审核 + 发布
autowonder.submit_agent_for_review(workspaceId, id)
autowonder.publish_agent(workspaceId, id)

// Step 4: 验证
autowonder.get_agent_version(workspaceId, agentId, versionNo=<latest>)
// → 确认 roleCode/roleName/businessBackground 非空
```

## 更新 SDLC 步骤
```json
{
  "method": "tools/call",
  "params": {
    "name": "autowonder.update_sdlc_step",
    "arguments": {
      "workspaceId": <int>,
      "sdlcId": <int>,
      "stepId": <int>,
      "instructionMd": "<优化后的指令文本>",
      "checklistJson": "[{\"id\":\"...\",\"text\":\"...\"}]",
      "gatePolicyJson": "{\"evidenceRequired\":true, ...}"
    }
  }
}
```

## 注意事项

### Agent 更新(危险操作)
- **`update_agent_config` 是全量替换** —— 省略任何字段 = 置空。这不是 bug,是 API 语义
- 必传字段:`roleCode`、`roleName`、`soulMd`、`agentMd`。任何一个不传都会导致该字段变 null
- `roleCode` 被清空的后果:该 agent 在后续 dispatch 的 roster 里没有角色标识,runtime handoff 验证会拒绝以该 roleCode 为 target 的交接请求
- 更新后**必须验证**:调 `get_agent_version` 确认四个字段非空
- `update_agent`(不是 `update_agent_config`)行为可能不同,但同样建议全量传入

### SDLC 步骤更新(安全操作)
- `update_sdlc_step` **是 partial update** —— 只传你要改的字段,其余保持不变
- SDLC 处于 ACTIVE/ENABLED 状态时 `checklistJson`/`gatePolicyJson` 可能无法更新(需先 disable)
- `instructionMd` 在部分状态下可直接更新(包括 ENABLED 状态)
- `requiredArtifacts` 使用前缀匹配:尾部带 `/` 表示目录前缀(如 `"evidence/"`)
- `evidenceRequired: true` = agent 必须在 completion_requested 中声明至少一个 evidenceRef
