# 提示词注入核查

验证平台侧的事实注入是否到位(从 turn.started 的 prompt 字段或 runtime-contract.md 检查):

| 检查项 | 标记 | 说明 |
|---|---|---|
| `## Artifact Lifecycle` | P1 | 步骤边界产出去向 |
| `## Available Capabilities` | P6 | 实际可用能力清单 |
| `## Already Accepted Output` | P7 | 前步已接受产出清单(非首步才有) |
| `## Upstream Delivery` | P7 | 上游交付物清单(非首 dispatch 才有) |
| `## Step Budget` 或 `【预算约束】` | budget | 规模引导 |
| `## Checklist` | checklist | 交付检查项 |
| `is a symlink to` | P3 | 仓库路径是 symlink |
| `Copy this for the active step` | N4 | step-event 范例 |
| `evidenceRefs, spelled exactly` | P2 | 字段名警告 |

**如果缺失 → 报告为平台侧问题(用户应升级执行器版本)。**
