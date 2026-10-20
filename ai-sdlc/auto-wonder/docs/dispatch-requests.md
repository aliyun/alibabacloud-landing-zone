# 执行中的人工输入

先应用 `V061__dispatch_requests.sql`，部署平台，再升级 Runtime。启用 `gatePolicy.humanApprovalRequired` 或 `gatePolicy.askWhenUnclear` 的流程只调度到声明 `DISPATCH_REQUESTS_V1` 的 Runtime；未启用两者的流程仍可使用旧 Runtime。V061 只新增表，仍支持未应用可选 V037 的旧库。

## 确定的人工门禁

在 SDLC 步骤编辑页打开“完成本步骤必须人工确认”，等价于 `gatePolicyJson` 中设置 `humanApprovalRequired: true`。审批人默认工单创建人，也可多选具体成员、身份标签；请求产生时解析为有写权限的工作空间成员并保存名单。任意一位指定审批人批准即可，其他成员能查看但不能操作，管理员也不能绕过名单。每次答复仍校验当前写权限。自定义配置示例：

```json
{"humanApprovalRequired":true,"humanApprovers":{"includeCreator":true,"userIds":[123],"identityTags":["验收"]}}
```

未配置 `humanApprovers` 时使用工单创建人；显式配置时取创建人、成员和标签匹配成员的并集。无人符合时拒绝创建审批，不能跳过门禁。Runtime 校验和上传本步骤产物后，在工单页显示步骤全名、“批准继续 / 要求修改”，同时向审批人发送站内通知，并通过平台 IM 通道通知已绑定账号的人。批准意见可空，要求修改必须填写非空意见。审批没有时间上限；执行取消或关闭工单仍会结束等待。批准绑定 dispatch、step attempt 和产物指纹；产物改变、拒绝、取消都不能通过。拒绝按原步骤修复预算处理。普通问答回答和数字人凭据不能批准。

有效的人工审批或问答尚未回答时，工单的看板分类、列表筛选和详情统一为“待决策”，回答后按现有执行状态重新分类。工单仍由原数字人负责，等待期间保持 dispatch 和执行槽位；不会通过改派负责人或流转业务节点实现待决策。

门禁约束步骤推进；需要控制发布等外部操作时，把该操作放到门禁后的独立步骤。运行期间修改流程配置不会改变已下发的任务包。

## 同一次执行内的问答

“该步骤事实不清时询问用户”默认关闭；只有显式设置 `gatePolicyJson.askWhenUnclear: true` 的当前步骤可以提问。Runtime 和服务端均拦截关闭时的请求，即使关键事实不清也不能发起临时问答；数字人应按已有事实继续或报告阻塞。开启后，在步骤执行说明或 Skill 中描述哪些事实需要追问，问题和选项由数字人生成。原 `askWhenUnclearGuidance` 字段不再使用，编辑保存时移除。未获回答后数字人按已有事实与授权继续，不把沉默当成同意；此配置不增加完成门禁，与人工批准独立。

`POST /dispatches/{id}/request-input` 示例：

```json
{"key":"environment-1","message":"请选择环境","schema":{"type":"object","properties":{"environment":{"type":"string","enum":["开发","测试"]}},"required":["environment"]}}
```

接口等待真人，返回 `{"action":"accept","content":{"environment":"用户输入"}}` 或 `{"action":"decline"}`。单选使用 `string + enum` 或 `oneOf`，多选使用 `array + items.enum` 或 `items.anyOf`；选择题同时允许“其他想法”自定义回答。不要将拒绝或错误当成默认同意。每次问题使用新的 key，网络重试使用原 key。支持字符串、布尔、数值和字符串数组字段，问题和答案最大 32 KiB。

任何有工作空间写权限的真人都可以回答。工单创建人、当前真人负责人、真人评论/提及者、问答和审批回答人均计入真人参与者；新问题通知这些参与者，复用站内通知中心和平台 IM 队列。页面与通知展示步骤全名。请求重试不会重复发通知。

问答保持原 dispatch、工具调用与执行槽位，继续心跳；等待单独限时 30 分钟，不消耗模型工作超时。工具自身的超时也应覆盖这段等待。Codex 原生 `requestUserInput` / form elicitation、Qoder form elicitation 已桥接；其他 provider 可调用相同本地 API。这里没有 ACCTest、Terraform 或 Jarvis 专属逻辑。

## 多语言与原始内容

系统界面、审批/问答通知和请求校验错误支持中文、英文。通知按每位接收者的 `ui.locale` 生成后入库；同一语言快照随 IM 任务入队，后续偏好变化不改写这次投递或历史通知。错误响应按请求语言渲染，保留原错误码、HTTP 状态和诊断日志。

Codex 原生问答生成的通用标题在 schema 中显式标记 `"x-autowonder-message-kind":"CONFIRMATION_REQUIRED"`，前端及通知按该意图翻译标题；问题、选项和答案原样保留。该可选扩展随现有 schema 保存，恢复问答时仍然有效，旧平台忽略扩展并显示原有标题。没有标记或标记未知的旧记录、用户及 AI 文本不根据原文推断翻译意图。平台生成的审批人名称使用 `nameKind: "USER_ID"` 标记，真实名称即使与缺省文案相同也保持原样；旧记录中的缺省步骤名标记保持原样渲染。

验证：`mvn -DskipGitCommitId=true -DskipFrontend=true -Dtest=McpToolServiceTest,DispatchRequestServiceTest,DispatchServiceRecoveryTest,DispatchRequestNotificationListenerTest test`。隔离服务器按 `e2e-tests/AGENT_GUIDE.md` 启动后，可运行 `python3 e2e-tests/dispatch-requests-e2e.py --runtime-json <runtime.json> --daemon <新 Runtime 二进制>`。
