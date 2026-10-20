# 个人 MCP 记忆管理

个人令牌 `awmcp_…` 的记忆工具与平台记忆页面共用权限和读写服务。每次调用根据令牌所属用户的当前工作空间成员身份鉴权，必须传入 `workspaceId`。

- 工作空间成员可读取页面可见的记忆。
- 写入需要工作空间写权限，且用户为管理员或具有目标记忆库的 `WRITE` 权限。普通成员默认只能读取记忆；已有的单库 WRITE 授权继续生效。
- 创建尚不存在的记忆库仍需要管理员权限。
- 写入记录为 `USER` 操作，不携带 Agent dispatch/session 身份，不进入旧版记忆审核队列。

此说明仅适用于个人 MCP。Agent 自主管理记忆、任务凭证及 Runtime 协议没有调整。调用时以个人令牌的 `tools/list` 返回结构为准；个人设置页的工具目录使用相同定义。

| 工具 | 页面对应行为 |
|---|---|
| `autowonder.search_memories` | 在页面目录中搜索可读主题和 `MEMORY.md` 索引，支持 keyword/scope/ownerRef/type/page/size |
| `autowonder.get_memory` | 按文档 id 读取，返回版本、原文及解析后的主题正文 |
| `autowonder.create_memory` | 创建主题并更新索引；传 scope、ownerRef、type、title、description、contentMd、idempotencyKey |
| `autowonder.update_memory` | 编辑主题或索引；传 id、contentMd、expectedVersion、idempotencyKey，主题还需要 type/title/description |
| `autowonder.delete_memory` | 删除主题并更新索引；传 id、expectedVersion、idempotencyKey；不能删除索引 |
| `autowonder.deprecate_memory` | 为兼容现有工具名保留，行为等同 delete_memory，没有额外的“弃用”状态 |

`scope` 为 `ORG`、`SQUAD` 或 `AGENT`；组织 ownerRef 为 0，其他为对应所有者 id。主题类型为 `user`、`feedback`、`project`、`reference`。主题路径由页面服务生成，编辑时不能移动所属库或改路径。

读取结果中 `contentMd` 是含 frontmatter 的完整文档，`body` 是主题正文。创建或编辑主题时，将正文放到入参 `contentMd`，不要把 frontmatter 再作为正文提交。编辑 `MEMORY.md` 时，contentMd 则是完整索引内容。

编辑/删除必须使用读取结果中的 `version` 作为 `expectedVersion`。冲突后刷新文档、确认修改并使用新请求键提交；不要自动覆盖。每个写请求提供独立 `idempotencyKey`，仅对同一个请求重试时复用。重试行为完全复用页面服务，包括返回该文档的当前状态。

## 隔离端到端验证

按 `e2e-tests/AGENT_GUIDE.md` 启动隔离环境后执行：

```bash
python3 e2e-tests/personal-mcp-memory.py --manifest /绝对路径/runtime.json
```

脚本仅接受当前 worktree 的标准生命周期 loopback 环境，对比个人 MCP 与页面 API 的管理员 CRUD、普通成员只读、单库 WRITE 授权、索引操作、版本冲突、重试、授权撤销、跨工作空间隔离与 USER 审计。完成后仍须执行标准 `--check` 和 `--clean-up`。
