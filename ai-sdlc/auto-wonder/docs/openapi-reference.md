# AutoWonder OpenAPI Reference

> 完整的系统管控 API 清单，供外部系统集成使用。

## 通用说明

| 项目 | 说明 |
|------|------|
| Base URL | `https://{host}:7001` |
| 认证方式 | Bearer JWT Token (通过 `/api/auth/login` 获取) |
| 请求头 | `Authorization: Bearer <accessToken>`, `Content-Type: application/json` |
| 组织上下文 | JWT 中携带当前 org，切换组织需调用 `/api/orgs/{id}/switch` 获取新 token |
| 统一响应格式 | `{ "success": true, "code": "OK", "data": <T>, "message": "" }` |
| 分页约定 | `page` (从 1 开始), `size` (默认 20) |
| 访问控制 | 组织成员使用 `READ_ONLY < READ_WRITE < ADMIN` 三档访问等级；身份标签不参与鉴权 |

---

## 1. 认证 (Auth)

| 方法 | 路径 | 访问要求 | 说明 |
|------|------|------|------|
| POST | `/api/auth/register` | 公开 | 注册 |
| POST | `/api/auth/login` | 公开 | 登录 |
| POST | `/api/auth/logout` | 登录即可 | 登出 |

### POST /api/auth/register
```json
// Request
{ "username": "string", "password": "string", "email": "string", "nickname": "string" }
// Response: UserVO
{ "id": 1, "username": "...", "email": "...", "nickname": "..." }
```

### POST /api/auth/login
```json
// Request
{ "username": "string", "password": "string" }
// Response: LoginResponse
{ "accessToken": "jwt...", "refreshToken": "...", "expiresIn": 7200, "user": { UserVO } }
```

### POST /api/auth/logout
```json
// Request
{ "refreshToken": "string" }
```

---

## 2. 组织管理 (Organization)

| 方法 | 路径 | 访问要求 | 说明 |
|------|------|------|------|
| POST | `/api/orgs` | 登录即可 | 创建组织 |
| GET | `/api/orgs/mine` | 登录即可 | 我加入的组织列表 |
| POST | `/api/orgs/{id}/switch` | 登录即可 | 切换当前组织 (返回新 token) |
| GET | `/api/orgs/current` | READ_ONLY | 获取当前组织信息 |
| GET | `/api/orgs/current/membership` | READ_ONLY | 获取当前成员访问等级和身份标签 |
| GET | `/api/orgs/current/members` | ADMIN | 列出组织成员 |
| GET | `/api/orgs/current/member-candidates` | ADMIN | 搜索可添加的全局人员 |
| POST | `/api/orgs/current/members` | ADMIN | 添加成员，默认 `READ_ONLY` |
| DELETE | `/api/orgs/current/members/{userId}` | ADMIN | 移除成员 |
| PUT | `/api/orgs/current/members/{userId}/access-level` | ADMIN | 修改成员访问等级 |
| PUT | `/api/orgs/current/members/{userId}/identity-tags` | ADMIN | 修改成员身份标签 |
| POST | `/api/orgs/current/owner/transfer` | ADMIN | 移交组织所有者 |

### POST /api/orgs
```json
// Request
{ "name": "string", "description": "string", "background": "string" }
// Response: OrgVO
```

### POST /api/orgs/{id}/switch
```json
// Response: SwitchOrgResponse
{ "accessToken": "new-jwt...", "accessLevel": "READ_ONLY|READ_WRITE|ADMIN" }
```

### POST /api/orgs/current/members
```json
// Request
{ "userId": 10001 }
```

### PUT /api/orgs/current/members/{userId}/access-level
```json
{ "accessLevel": "READ_ONLY|READ_WRITE|ADMIN" }
```

### PUT /api/orgs/current/members/{userId}/identity-tags
```json
{ "identityTags": ["需求管理员", "澄清员"] }
```

---

## 3. 组织访问等级

| 等级 | 能力 |
|------|------|
| READ_ONLY | 查看组织业务数据、日志、洞察、评论和产物 |
| READ_WRITE | 包含只读能力，并可执行组织业务的创建、修改、删除、审核和运行 |
| ADMIN | 包含读写能力，并可管理成员、owner、系统设置、外部集成和执行器 |

身份标签只用于任务协作和人员识别，不授予任何访问能力。长效 MCP Token 是**用户个人资产**，不绑定组织、也不保存 per-token 权限上限；调用组织域工具时按 `(orgId, userId)` 实时解析成员等级作为有效等级。工单 dispatch Token 固定为 `READ_WRITE`，并继续受工单、dispatch 和有效期约束。

### 长效 MCP Token（个人资产）

| 方法 | 路径 | 访问要求 | 说明 |
|------|------|----------|------|
| POST | `/api/mcp/tokens` | 本人资源（登录即可，不要求当前组织） | 创建自己的长效个人 Token |
| GET | `/api/mcp/tokens` | 本人资源（登录即可，不要求当前组织） | 列出自己的全部个人 Token |
| DELETE | `/api/mcp/tokens/{id}` | 本人资源（登录即可，不要求当前组织） | 撤销自己的 Token |
| GET | `/api/mcp/tokens/tools` | 本人资源（登录即可，不要求当前组织） | 获取 MCP 工具目录 |
| GET | `/api/mcp/tokens/platform-skills` | 本人资源（登录即可，不要求当前组织） | 获取平台技能目录 |

```json
// POST /api/mcp/tokens
{ "name": "Codex" }
// Response: IssuedMcpTokenVO；token 明文仅返回一次
{ "id": 10000, "name": "Codex", "tokenPrefix": "awmcp_abcd", "token": "awmcp_..." }
```

请求体不再接受 `accessLevel`，响应也不再返回 `accessLevel` / `effectiveAccessLevel`。

#### 接入流程

1. 在「个人设置 -> MCP 令牌」创建个人 Token（不需要先选择组织）。
2. 调用 `autowonder.list_projects` 发现自己可访问的组织及权限等级。
3. 调用组织域工具时传入 `orgId`（必填、正整数）。
4. 权限跟随你在该 `orgId` 内的实时成员等级：离开组织或被降权后立即生效。

`autowonder.list_projects`、`autowonder.inspect_skill_package`、`autowonder.list_platform_skills`
不访问组织数据，不需要 `orgId`；其余工具全部必填 `orgId`。

缺少 `orgId` 或 `orgId` 非正整数返回 `PARAM_INVALID`；不是目标组织有效成员返回 `ORG_NOT_MEMBER`；
成员等级不足返回 `NO_PERMISSION`。

dispatch / conversation Token 仍锁定在自身组织：省略 `orgId` 时沿用该组织，传入其他组织的
`orgId` 返回 `NO_PERMISSION`。

客户端 MCP endpoint 来自部署属性 `autowonder.public-base-url`，组织管理员不能修改该地址；执行器启动命令的 WebSocket 地址也从同一部署地址派生。

### 平台品牌配置

| 方法 | 路径 | 访问要求 | 说明 |
|------|------|----------|------|
| GET | `/api/platform/branding/public` | 公开 | 获取公开品牌和 canonical MCP endpoint |
| GET | `/api/platform/branding/logo` | 公开 | 获取 Logo |
| GET | `/api/platform/branding` | ADMIN | 获取品牌管理配置 |
| PUT | `/api/platform/branding` | ADMIN | 更新平台名称、主题色和展示域名 |
| POST | `/api/platform/branding/logo` | ADMIN | 上传 Logo |

```json
// PUT /api/platform/branding
{ "platformName": "AutoWonder", "themeKey": "aliyun-orange",
  "primaryColor": "#f97316", "domain": "https://auto-wonder.example.com" }
```

`mcpBaseUrl` 是只读部署信息，不属于品牌更新请求，不能通过组织 API 修改。

---

## 4. 智能体管理 (Agent)

| 方法 | 路径 | 访问要求 | 说明 |
|------|------|------|------|
| POST | `/api/agents` | READ_WRITE | 创建智能体 |
| GET | `/api/agents/{id}` | READ_ONLY | 获取智能体详情 |
| GET | `/api/agents` | READ_ONLY | 列出智能体 |
| PUT | `/api/agents/{id}/config` | READ_WRITE | 编辑配置 (产生新草稿版本) |
| POST | `/api/agents/{id}/submit` | READ_WRITE | 提交审批 |
| POST | `/api/agents/{id}/approve` | READ_WRITE | 审批通过 |
| POST | `/api/agents/{id}/reject` | READ_WRITE | 审批拒绝 |
| POST | `/api/agents/{id}/rollback` | READ_WRITE | 回滚到指定版本 |
| POST | `/api/agents/{id}/offline` | READ_WRITE | 下线 |
| GET | `/api/agents/{id}/versions` | READ_ONLY | 版本列表 |
| GET | `/api/agents/{id}/versions/{versionNo}` | READ_ONLY | 获取指定版本 |
| POST | `/api/agents/{id}/repos` | READ_WRITE | 添加仓库权限 |
| DELETE | `/api/agents/{id}/repos/{repoId}` | READ_WRITE | 移除仓库权限 |
| POST | `/api/agents/{id}/skills` | READ_WRITE | 绑定技能 |
| DELETE | `/api/agents/{id}/skills/{skillId}` | READ_WRITE | 解绑技能 |
| POST | `/api/agents/{id}/memories` | READ_WRITE | 绑定记忆 |
| DELETE | `/api/agents/{id}/memories/{memoryId}` | READ_WRITE | 解绑记忆 |
| GET | `/api/agents/{id}/memories` | READ_ONLY | 获取已绑定记忆列表 |
| GET | `/api/agents/{id}/squads` | READ_ONLY | 获取所属小队 |

### POST /api/agents
```json
// Request
{ "name": "string", "avatarUrl": "string", "roleName": "string", "roleCode": "string",
  "businessBackground": "string", "responsibilities": "string" }
// Response: AgentVO
```

### PUT /api/agents/{id}/config
```json
// Request
{ "roleName": "string", "roleCode": "string", "businessBackground": "string",
  "responsibilities": "string", "sdlcId": 1,
  "evolutionMode": "MANUAL|ASSISTED|AUTO_PROPOSAL" }
// Response: AgentVersionVO
```

`evolutionMode` 控制该数字员工上传 `learning_delta` 后的服务端自进化入口：

| 模式 | 效果 |
|---|---|
| MANUAL | 只保存 artifact，不自动沉淀 Memory，也不自动生成 Evolution Proposal |
| ASSISTED | 默认模式。自动生成待审核 Memory / Proposal；Proposal 进入 Bayesian Trial，但不自动写 active 资产 |
| AUTO_PROPOSAL | 自动生成候选并进入 Bayesian Trial；Trial 达到 ADOPT 后可显式 release，仍不静默写 active 资产 |

### POST /api/agents/{id}/approve | /reject
```json
{ "comment": "string" }
```

### POST /api/agents/{id}/rollback
```json
{ "versionNo": 2 }
```

### POST /api/agents/{id}/repos
```json
{ "repoId": 1, "permLevel": "READ|WRITE" }
```

### POST /api/agents/{id}/skills
```json
{ "skillId": 1 }
```

### POST /api/agents/{id}/memories
```json
{ "memoryId": 1, "source": "MANUAL|AUTO" }
```

### GET /api/agents?status=&page=1&size=20
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| status | string | 否 | 过滤状态 |
| page | int | 否 | 页码 (默认 1) |
| size | int | 否 | 每页条数 (默认 20) |

---

## 5. 小队管理 (Squad)

| 方法 | 路径 | 访问要求 | 说明 |
|------|------|------|------|
| POST | `/api/squads` | READ_WRITE | 创建小队 |
| GET | `/api/squads/{id}` | READ_ONLY | 获取小队 |
| GET | `/api/squads` | READ_ONLY | 列出小队 |
| PUT | `/api/squads/{id}` | READ_WRITE | 更新小队 |
| DELETE | `/api/squads/{id}` | READ_WRITE | 删除小队 |
| GET | `/api/squads/{id}/members` | READ_ONLY | 列出小队成员 (Agent) |
| POST | `/api/squads/{id}/members` | READ_WRITE | 添加成员 |
| DELETE | `/api/squads/{id}/members/{agentId}` | READ_WRITE | 移除成员 |

### POST /api/squads
```json
{ "name": "string", "description": "string", "ownerId": 1 }
```

### PUT /api/squads/{id}
```json
{ "name": "string", "description": "string", "ownerId": 1 }
```

### POST /api/squads/{id}/members
```json
{ "agentIds": [1, 2, 3] }
```

---

## 6. 工单管理 (Workitem)

| 方法 | 路径 | 访问要求 | 说明 |
|------|------|------|------|
| POST | `/api/workitems` | READ_WRITE | 创建工单 |
| GET | `/api/workitems/{id}` | READ_ONLY | 获取工单详情 |
| GET | `/api/workitems` | READ_ONLY | 列出工单 |
| POST | `/api/workitems/{id}/transition` | READ_WRITE | 状态流转 |
| PUT | `/api/workitems/{id}/assignee` | READ_WRITE | 分配/派发 |
| PUT | `/api/workitems/{id}/content` | READ_WRITE | 更新标题/内容 |
| POST | `/api/workitems/{id}/comments` | READ_WRITE | 添加评论 |
| GET | `/api/workitems/{id}/comments` | READ_ONLY | 获取评论列表 |
| GET | `/api/workitems/{id}/timeline` | READ_ONLY | 事件时间线 |
| GET | `/api/workitems/{id}/unified-timeline` | READ_ONLY | 统一时间线 (评论+事件) |
| GET | `/api/workitems/{id}/delivery-progress` | READ_ONLY | 交付进度 |
| GET | `/api/workitems/{id}/participants` | READ_ONLY | 参与者列表 |
| POST | `/api/workitems/{id}/external-sync` | READ_WRITE | 同步到外部系统 |

### POST /api/workitems
```json
{ "workType": "TASK|BUG|STORY", "title": "string", "contentMd": "markdown...", "priority": 1 }
// Response: WorkitemVO
```

### GET /api/workitems
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| workType | string | 否 | TASK / BUG / STORY |
| statusNodeId | long | 否 | 按状态节点过滤 |
| assigneeType | string | 否 | AGENT / SQUAD |
| assigneeRef | long | 否 | 分配对象 ID |
| page | int | 否 | 默认 1 |
| size | int | 否 | 默认 20 |

### POST /api/workitems/{id}/transition
```json
{ "toNodeId": 3 }
```

### PUT /api/workitems/{id}/assignee
```json
{ "assigneeType": "AGENT|SQUAD", "assigneeRef": 1, "sdlcId": 1, "squadId": 2 }
```

### PUT /api/workitems/{id}/content
```json
{ "title": "string", "contentMd": "string" }
```

### POST /api/workitems/{id}/comments
```json
{ "contentMd": "评论内容 markdown" }
```

---

## 7. 工单澄清 (Clarification)

| 方法 | 路径 | 访问要求 | 说明 |
|------|------|------|------|
| GET | `/api/workitems/{workitemId}/clarification` | READ_ONLY | 获取澄清文档 |
| PUT | `/api/workitems/{workitemId}/clarification` | READ_WRITE | 更新澄清文档 |

### PUT /api/workitems/{workitemId}/clarification
```json
{ "contentMd": "string" }
```

---

## 8. 派发记录 (Dispatch)

| 方法 | 路径 | 访问要求 | 说明 |
|------|------|------|------|
| GET | `/api/dispatches` | READ_ONLY | 列出派发记录 |
| GET | `/api/dispatches/{id}` | READ_ONLY | 获取单条派发详情 |

### GET /api/dispatches
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| page | int | 否 | 默认 1 |
| page_size | int | 否 | 默认 50 |
| status | string | 否 | 状态过滤 |
| agent_id | long | 否 | 按智能体过滤 |
| workitem_id | long | 否 | 按工单过滤 |
| time_range | string | 否 | 时间范围 (默认 30d) |

---

## 9. 执行器管理 (Executor)

| 方法 | 路径 | 访问要求 | 说明 |
|------|------|------|------|
| POST | `/api/agents/{agentId}/executors` | ADMIN | 为智能体创建执行器 |
| GET | `/api/agents/{agentId}/executors` | READ_ONLY | 列出某智能体的执行器 |
| GET | `/api/executors` | READ_ONLY | 列出所有执行器 |
| GET | `/api/executors/{id}/token` | ADMIN | 获取执行器 Token |
| DELETE | `/api/executors/{id}` | ADMIN | 删除执行器 |

### POST /api/agents/{agentId}/executors
```json
{ "name": "string", "clientKind": "CLAUDE_CODE|CUSTOM" }
// Response: IssuedExecutorVO (含 token，仅创建时可见)
```

---

## 10. 仓库管理 (Repository)

| 方法 | 路径 | 访问要求 | 说明 |
|------|------|------|------|
| POST | `/api/repos` | READ_WRITE | 创建仓库 |
| POST | `/api/repos/test-connection` | READ_WRITE | 测试仓库连接 |
| GET | `/api/repos` | READ_ONLY | 列出仓库 |
| GET | `/api/repos/{id}` | READ_ONLY | 获取仓库详情 |
| PUT | `/api/repos/{id}` | READ_WRITE | 更新仓库 |
| DELETE | `/api/repos/{id}` | READ_WRITE | 删除仓库 |
| POST | `/api/repos/{id}/scan` | READ_WRITE | 触发仓库扫描 |
| GET | `/api/repos/{id}/conclusion` | READ_ONLY | 获取扫描结论 |
| PUT | `/api/repos/{id}/conclusion` | READ_WRITE | 更新扫描结论 |
| GET | `/api/repos/relations` | READ_ONLY | 列出仓库关系 |
| POST | `/api/repos/relations` | READ_WRITE | 创建仓库关系 |
| DELETE | `/api/repos/relations/{id}` | READ_WRITE | 删除仓库关系 |

### POST /api/repos
```json
{ "name": "string", "url": "git@...", "defaultBranch": "master", "description": "string" }
```

### POST /api/repos/test-connection
```json
{ "name": "string", "url": "git@...", "defaultBranch": "master" }
// 复用本机 git 权限，不接受任何凭据
// Response: RepoConnectionTestResult
```

### PUT /api/repos/{id}/conclusion
```json
{ "purpose": "string", "keyBusiness": "string", "upstreams": "string",
  "downstreams": "string", "summaryMd": "string" }
```

### POST /api/repos/relations
```json
{ "fromRepoId": 1, "toRepoId": 2, "relationType": "DEPENDS_ON|UPSTREAM|DOWNSTREAM",
  "description": "string", "aiSessionId": 1 }
```

### GET /api/repos/relations?repoId=1
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| repoId | long | 否 | 按仓库过滤，不传则返回组织全部 |

---

## 11. 技能管理 (Skill)

| 方法 | 路径 | 访问要求 | 说明 |
|------|------|------|------|
| POST | `/api/skills` | READ_WRITE | 创建技能 |
| GET | `/api/skills/{id}` | READ_ONLY | 获取技能 |
| GET | `/api/skills` | READ_ONLY | 列出技能 |
| PUT | `/api/skills/{id}` | READ_WRITE | 更新技能 |
| DELETE | `/api/skills/{id}` | READ_WRITE | 删除技能 |

### POST /api/skills
```json
{ "type": "MCP_SERVER|TOOL|PROMPT", "name": "string",
  "installSpec": "npm:@org/pkg | url:https://...", "description": "string" }
```

### GET /api/skills?type=&page=1&size=20

---

## 12. 记忆管理 (Memory)

| 方法 | 路径 | 访问要求 | 说明 |
|------|------|------|------|
| POST | `/api/memories` | READ_WRITE | 创建记忆 |
| GET | `/api/memories` | READ_ONLY | 列出记忆 |
| GET | `/api/memories/{id}` | READ_ONLY | 获取记忆详情 |
| PUT | `/api/memories/{id}` | READ_WRITE | 更新记忆 |
| DELETE | `/api/memories/{id}` | READ_WRITE | 删除记忆 |
| POST | `/api/memories/{id}/review` | READ_WRITE | 审核记忆 |
| GET | `/api/memories/reviews` | READ_ONLY | 待审核记忆列表 |
| POST | `/api/memories/from-artifact` | READ_WRITE | 从制品导入记忆 |

### POST /api/memories
```json
{ "scope": "ORG|SQUAD|AGENT", "ownerRef": 1, "type": "FACT|RULE|PROCESS",
  "title": "string", "contentMd": "string" }
```

### GET /api/memories
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| scope | string | 否 | ORG / SQUAD / AGENT |
| ownerRef | long | 否 | 归属对象 ID |
| type | string | 否 | FACT / RULE / PROCESS |
| status | string | 否 | PENDING / APPROVED / REJECTED |
| page | int | 否 | 默认 1 |
| size | int | 否 | 默认 20 |

### POST /api/memories/{id}/review
```json
{ "decision": "ADOPT|REJECT", "editedContentMd": "string (可选修订)",
  "scope": "ORG|SQUAD|AGENT", "ownerRef": 1, "comment": "string" }
```

`memory_delta` 产生的待审核记忆默认是 `scope=AGENT, ownerRef=<reportingAgentId>`。审核时可通过 `scope/ownerRef` 保持员工记忆，或提升为小队/组织全局记忆。若该数字员工的 `evolutionMode=MANUAL`，`memory_delta` 只作为 artifact 留存，不进入待审核记忆池。

### POST /api/memories/from-artifact
```json
{ "artifactId": 1, "scope": "ORG", "ownerRef": null,
  "title": "string", "contentMd": "string", "type": "FACT" }
```

### MCP 记忆工具

数字员工在执行 SDLC 步骤时通过内置 `autowonder` MCP server 直接读写同一套记忆库，无需经由 learning delta 文件：`autowonder.create_memory` / `search_memories` / `get_memory` / `update_memory` / `deprecate_memory` / `delete_memory`。MCP 写入的记忆 `source=MCP`、`status=PENDING`，溯源信息（`dispatchId` / `workitemId` / `agentId`）由服务端从 dispatch 令牌推导后写入 `sourceRef`，与 `LEARNING_DELTA` 来源并行共存、共用同一审核队列。工具入参出参与调用示例见 [MCP Memory Management](mcp-memory-management.md)。

---

## 13. SDLC 流程管理 (Software Delivery Lifecycle)

| 方法 | 路径 | 访问要求 | 说明 |
|------|------|------|------|
| POST | `/api/sdlcs` | READ_WRITE | 创建 SDLC 流程 |
| GET | `/api/sdlcs/{id}` | READ_ONLY | 获取流程详情 |
| GET | `/api/sdlcs` | READ_ONLY | 列出流程 |
| PUT | `/api/sdlcs/{id}` | READ_WRITE | 更新流程 |
| DELETE | `/api/sdlcs/{id}` | READ_WRITE | 删除流程 |
| POST | `/api/sdlcs/{id}/steps` | READ_WRITE | 添加步骤 |
| PUT | `/api/sdlcs/{id}/steps/{stepId}` | READ_WRITE | 更新步骤 |
| DELETE | `/api/sdlcs/{id}/steps/{stepId}` | READ_WRITE | 删除步骤 |
| PUT | `/api/sdlcs/{id}/steps/reorder` | READ_WRITE | 步骤重排序 |
| POST | `/api/sdlcs/{id}/enable` | READ_WRITE | 启用流程 |
| POST | `/api/sdlcs/{id}/disable` | READ_WRITE | 禁用流程 |

### POST /api/sdlcs
```json
{ "name": "string", "description": "string", "workType": "TASK|BUG|STORY" }
```

### GET /api/sdlcs?workType=&status=&page=1&size=20

### POST /api/sdlcs/{id}/steps
```json
{ "stepOrder": 1, "name": "string", "kind": "AI|MANUAL|GATE",
  "instructionMd": "string", "checklistJson": "string", "gatePolicyJson": "string",
  "required": true, "timeoutSeconds": 3600, "retryBudget": 3,
  "code": "unique-step-code", "handlerType": "AGENT|SQUAD",
  "handlerRoleRef": "string", "statusOnEnterCode": "IN_PROGRESS",
  "onSuccess": "NEXT|COMPLETE", "onFail": "RETRY|BLOCK" }
```

### PUT /api/sdlcs/{id}/steps/reorder
```json
{ "stepIds": [3, 1, 2] }
```

### POST /api/sdlcs/{id}/enable?statusTemplateId=1

---

## 14. 状态模板管理 (Status Template)

| 方法 | 路径 | 访问要求 | 说明 |
|------|------|------|------|
| GET | `/api/status-templates?workType=TASK` | READ_ONLY | 列出模板 |
| GET | `/api/status-templates/{id}` | READ_ONLY | 获取模板详情 |
| POST | `/api/status-templates` | READ_WRITE | 创建模板 |
| PUT | `/api/status-templates/{id}` | READ_WRITE | 更新模板 |
| DELETE | `/api/status-templates/{id}` | READ_WRITE | 删除模板 |
| GET | `/api/status-templates/{id}/nodes` | READ_ONLY | 列出状态节点 |
| POST | `/api/status-templates/{id}/nodes` | READ_WRITE | 创建节点 |
| PUT | `/api/status-templates/{id}/nodes/{nodeId}` | READ_WRITE | 更新节点 |
| DELETE | `/api/status-templates/{id}/nodes/{nodeId}` | READ_WRITE | 删除节点 |
| GET | `/api/status-templates/{id}/transitions` | READ_ONLY | 列出流转规则 |
| POST | `/api/status-templates/{id}/transitions` | READ_WRITE | 创建流转 |
| PUT | `/api/status-templates/{id}/transitions/{tid}` | READ_WRITE | 更新流转 |
| DELETE | `/api/status-templates/{id}/transitions/{tid}` | READ_WRITE | 删除流转 |

### POST /api/status-templates
```json
{ "workType": "TASK", "name": "默认任务流" }
```

### POST /api/status-templates/{id}/nodes
```json
{ "code": "IN_PROGRESS", "name": "进行中", "category": "TODO|IN_PROGRESS|DONE", "sort": 1 }
```

### POST /api/status-templates/{id}/transitions
```json
{ "fromNodeId": 1, "toNodeId": 2, "name": "开始处理" }
```

---

## 15. AI 会话 (AI Session)

| 方法 | 路径 | 访问要求 | 说明 |
|------|------|------|------|
| POST | `/api/ai/sessions` | READ_WRITE | 创建 AI 会话 |
| GET | `/api/ai/sessions/{id}` | READ_ONLY | 获取会话详情 |
| POST | `/api/ai/sessions/{id}/messages` | READ_WRITE | 追加消息 |
| POST | `/api/ai/sessions/{id}/confirm` | READ_WRITE | 确认结果 |
| POST | `/api/ai/sessions/{id}/cancel` | READ_WRITE | 取消会话 |

### POST /api/ai/sessions
```json
{ "scene": "REPO_SCAN|WORKITEM_ASSIST", "bizRefType": "REPO|WORKITEM",
  "bizRefId": 1, "input": "用户输入内容" }
// Response: Long (sessionId)
```

### POST /api/ai/sessions/{id}/messages
```json
{ "content": "追加的消息内容" }
```

### POST /api/ai/sessions/{id}/confirm
```json
{ "resultJson": "{\"accepted\": true, ...}" }
```

---

## 16. 制品管理 (Artifact)

| 方法 | 路径 | 访问要求 | 说明 |
|------|------|------|------|
| GET | `/api/workitems/{id}/artifacts` | READ_ONLY | 列出工单制品 |
| GET | `/api/artifacts/{id}/download` | READ_ONLY | 获取制品下载 URL |

### Daemon 上传接口 (内部)

| 方法 | 路径 | 认证方式 | 说明 |
|------|------|----------|------|
| POST | `/api/daemon/dispatches/{dispatchId}/artifacts` | Daemon/dispatch 凭据 | 执行器上传制品 |

参数: `token` (query), `idempotencyKey` (query, 可选), `filesMetadata` (query, JSON array), `files` (multipart)

---

## 17. 通知管理 (Notification)

| 方法 | 路径 | 访问要求 | 说明 |
|------|------|------|------|
| GET | `/api/notifications` | 本人资源 | 列出通知 |
| GET | `/api/notifications/unread-count` | 本人资源 | 未读数 |
| POST | `/api/notifications/{id}/read` | 本人资源 | 标记已读 |
| POST | `/api/notifications/read-all` | 本人资源 | 全部已读 |
| GET | `/api/notifications/prefs` | 本人资源 | 获取通知偏好 |
| PUT | `/api/notifications/prefs` | 本人资源 | 更新通知偏好 |

### GET /api/notifications?status=UNREAD&page=1&size=20

### PUT /api/notifications/prefs
```json
{ "items": [
    { "type": "DISPATCH_COMPLETE", "inApp": true, "dingtalk": false },
    { "type": "WORKITEM_ASSIGNED", "inApp": true, "dingtalk": true }
] }
```

---

## 18. AI 用量管理 (AI Usage)

| 方法 | 路径 | 访问要求 | 说明 |
|------|------|------|------|
| GET | `/api/ai-usage` | READ_ONLY | 用量列表 |
| GET | `/api/ai-usage/quota` | ADMIN | 获取配额 |
| PUT | `/api/ai-usage/quota` | ADMIN | 更新配额 |

### GET /api/ai-usage?period=2024-07

### PUT /api/ai-usage/quota
```json
{ "maxCalls": 10000, "maxTokens": 5000000, "concurrencyLimit": 5 }
```

---

## 19. 洞察面板 (Insights)

| 方法 | 路径 | 访问要求 | 说明 |
|------|------|------|------|
| GET | `/api/insights/metrics` | READ_ONLY | 聚合指标 |
| GET | `/api/insights/audit` | READ_ONLY | 风险审计 |
| GET | `/api/insights/workers` | READ_ONLY | 工作者列表 |

### GET /api/insights/metrics?worker_id=1&time_range=30d
### GET /api/insights/audit?page=1&page_size=50&risk_level=HIGH&worker_id=1&time_range=30d

---

## 20. 审计日志 (Audit Log)

| 方法 | 路径 | 访问要求 | 说明 |
|------|------|------|------|
| GET | `/api/audit-logs` | READ_ONLY | 搜索审计日志 |
| GET | `/api/audit-logs/count` | READ_ONLY | 日志计数 |

### GET /api/audit-logs
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| module | string | 否 | 模块 |
| action | string | 否 | 动作 |
| actorId | long | 否 | 操作者 |
| targetType | string | 否 | 目标类型 |
| targetId | long | 否 | 目标 ID |
| startTime | string | 否 | 开始时间 |
| endTime | string | 否 | 结束时间 |
| keyword | string | 否 | 关键词 |
| page | int | 否 | 默认 1 |
| size | int | 否 | 默认 20 |

---

## 21. 系统设置 (System Settings)

| 方法 | 路径 | 访问要求 | 说明 |
|------|------|------|------|
| GET | `/api/settings/{group}` | ADMIN | 获取某组设置 |
| PUT | `/api/settings/{group}` | ADMIN | 更新某组设置 |

### PUT /api/settings/{group}
```json
{ "items": [
    { "key": "concurrent_dispatches", "valueJson": "5", "secret": false },
    { "key": "api_key", "valueJson": "\"sk-xxx\"", "secret": true }
] }
```

---

## 22. 外部集成 — Aone (Integration)

Aone 是可选集成，社区版默认关闭。只有部署环境显式启用并提供可访问的
Aone 服务地址后，以下接口才可用。

| 方法 | 路径 | 访问要求 | 说明 |
|------|------|------|------|
| POST | `/api/integrations/aone/bindings/test` | ADMIN | 测试 Aone 连接 |
| POST | `/api/integrations/aone/bindings` | ADMIN | 创建绑定 |
| GET | `/api/integrations/aone/bindings` | ADMIN | 列出绑定 |
| POST | `/api/integrations/aone/projects/search` | ADMIN | 搜索 Aone 项目 |
| POST | `/api/integrations/aone/projects/{projectId}/members` | ADMIN | 列出项目成员 |
| POST | `/api/integrations/aone/bindings/{id}/sync-now` | ADMIN | 立即同步 |
| POST | `/api/integrations/aone/outbox/dispatch-now` | ADMIN | 手动触发发件箱 |

### POST /api/integrations/aone/bindings
```json
{ "baseUrl": "https://{aone-host}", "clientKey": "string",
  "accessSecret": "string", "regionId": "string",
  "externalProjectId": "string", "externalProjectName": "string",
  "writebackStaffId": "string", "pollIntervalSeconds": 300, "enabled": true }
```

### POST /api/integrations/aone/bindings/{id}/sync-now
```json
{ "issueIds": ["ISSUE-123", "ISSUE-456"] }
```

---

## 23. 自进化 Trial (Evolution Trial)

自进化主链路是 Bayesian Trial：`/api/evolution/orchestrate` 创建候选后进入 `TRIAL`。后续真实任务把候选结果作为 evidence 写回，服务端比较 candidate posterior 与创建 Trial 时冻结的 baseline posterior。新版推荐用 `taskType + primaryRepoGroup + operation` 生成稳定 `taskPatternKey`，并可通过 `assetUsage` 对同一任务涉及的多个资产做 Bayesian credit assignment。

| 方法 | 路径 | 访问要求 | 说明 |
|------|------|------|------|
| POST | `/api/evolution/orchestrate` | READ_WRITE | 记录 root evidence；Bayesian policy 触发后创建 proposal 并进入 Trial |
| POST | `/api/evolution/proposals/{id}/trial/start` | READ_WRITE | 手动把已有 proposal 放入 Trial |
| POST | `/api/evolution/proposals/{id}/trial/evidence` | READ_WRITE | 记录候选在真实任务中的 PASS/FAIL |
| POST | `/api/evolution/proposals/{id}/trial/decide` | READ_WRITE | 根据 Bayesian posterior 判定 CONTINUE_TRIAL / ADOPT / REJECT |
| POST | `/api/evolution/proposals/{id}/release` | READ_WRITE | 只在 replay PASS 或 Trial ADOPT 后写 active 资产 |

### POST /api/evolution/proposals/{id}/trial/evidence
```json
{ "rawOutcome": "PASS|FAIL", "sourceType": "AUTHORITATIVE_SYSTEM|HUMAN_REVIEW",
  "sourceRef": "dispatch:123", "idempotencyKey": "dispatch:123:trial",
  "evidenceJson": "{\"summary\":\"real task outcome\"}" }
```

Trial decision 返回中包含：

```json
{
  "decision": "CONTINUE_TRIAL|ADOPT|REJECT",
  "posteriorWinProbability": 0.92,
  "posteriorLoseProbability": 0.03,
  "expectedLift": 0.18
}
```

## 24. 外部工单通用上报 API v1

| 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|
| POST | `/api/v1/external/workitems/import` | workitem:write | 外部系统主动上报标准化工单 |
| GET | `/api/v1/external/workitems/import-records` | workitem:read | 查询导入记录 |

### POST /api/v1/external/workitems/import

`sourceSystem + externalWorkitemId` 是幂等标识。重复上报时 `updateExisting` 缺省视为 `true`，仅更新本地标题/正文等内容，不重置本地流程状态；传 `false` 时不更新且返回 `duplicate=true`。

`fieldMappings` 的 key 是 `extensions` 中的外部字段名，value 是标准字段名。当标准字段缺失时，导入服务会按映射从 `extensions` 补齐 `sourceSystem`、`externalWorkitemId`、`externalProjectId`、`title`、`description`、`type`、`priority`、`assignee`、`creator`、`status`、`sourceUrl` 等字段；映射本身也会随导入记录持久化，便于追踪。

```json
{
  "sourceSystem": "JIRA",
  "externalWorkitemId": "PROJ-123",
  "externalProjectId": "PROJ",
  "title": "支付失败时提示不明确",
  "description": "用户在支付超时后无法判断是否重试。",
  "type": "BUG",
  "priority": 1,
  "assignee": "zhangsan",
  "creator": "lisi",
  "status": "Open",
  "sourceUrl": "https://jira.example.com/browse/PROJ-123",
  "attachments": [
    { "name": "screenshot.png", "url": "https://files.example.com/screenshot.png", "contentType": "image/png", "size": 1024 }
  ],
  "fieldMappings": { "severity": "priority" },
  "extensions": { "severity": "S1", "component": "payment" },
  "updateExisting": true,
  "requestId": "req-20260719-001"
}
```

成功返回：

```json
{
  "workitemId": 10001,
  "importRecordId": 10000,
  "sourceSystem": "JIRA",
  "externalWorkitemId": "PROJ-123",
  "created": true,
  "updated": false,
  "duplicate": false
}
```

明确错误返回沿用统一 `Result`：未登录 `10401`，无权限 `10403`，缺少 `sourceSystem/externalWorkitemId/title/type` 返回 `10001`，不支持的类型返回 `13001`，并带 `traceId`。

### GET /api/v1/external/workitems/import-records

查询参数：`sourceSystem`、`externalWorkitemId`、`status`、`page`、`size`。`status` 可为 `CREATED`、`UPDATED`、`DUPLICATE`、`FAILED`。

返回记录包含来源系统、外部工单 ID、本地工单 ID、请求 ID、导入状态、失败原因、原始链接和导入时间。状态回写、处理结果回传和 webhook 可在现有 integration outbox/provider 机制上继续扩展。

---

## 访问等级速查表

| 访问要求 | 说明 |
|----------|------|
| 公开 | 不要求登录 |
| 登录即可 | 要求有效用户会话，不依赖当前组织访问等级 |
| READ_ONLY | 要求当前用户是组织有效成员 |
| READ_WRITE | 要求当前成员至少为读写 |
| ADMIN | 要求当前成员为管理员 |
| 本人资源 | 通知已读、创建/列出/撤销自己的 MCP Token，不受组织等级阶梯限制 |
