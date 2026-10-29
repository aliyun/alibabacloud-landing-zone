# 飞书消息渠道接入

当前版本新建绑定使用平台共享群聊应用：平台管理员配置 GROUP_CHAT 用途的飞书应用后，项目 ADMIN 将外部群 ID 绑定到数字人。同一平台应用可服务多个群和项目；群内 @机器人触发对话。已有 LEGACY 独立应用绑定仍可维护，保留原有单聊和群聊行为。

本版使用 HTTP 事件订阅，不包含长连接、商店应用、文件/图片输入、卡片交互，也不替换平台级钉钉通知和用户钉钉身份。数字人的执行器和在线版本使用现有会话引擎的配置。

## 部署

1. 已有数据库先执行 [V060__feishu_agent_conversation.sql](migration/V060__feishu_agent_conversation.sql)。全新数据库的 `autowonder-schema.sql` 已包含新表。不要在应用已启动后再等待自动建表。
2. 设置 `autowonder.public-base-url` 为外部可访问的 HTTPS 地址。网关需允许 POST `/api/integrations/feishu/callback`，并原样透传请求体以及 `X-Lark-Request-Timestamp`、`X-Lark-Request-Nonce`、`X-Lark-Signature` 请求头。
3. 服务需要能访问 `https://open.feishu.cn`。该域名固定在后端，配置页面不接受自定义出站地址。
4. 飞书应用启用机器人能力，开通消息权限并发布版本。为所绑定的数字人配置在线执行器。

## 新建共享群聊绑定

1. 平台管理员在「平台配置 → 协作通知」配置飞书 GROUP_CHAT 应用及回调验证凭据，并选择飞书为当前协作渠道。
2. 飞书开放平台启用机器人、消息权限和事件订阅，发布应用；回调使用平台展示的 `/api/integrations/feishu/group-callback?configId=<平台配置ID>`。
3. 将机器人加入目标群，项目 ADMIN 在飞书绑定表单选择群 ID 和数字人；数字人须有在线版本和执行器。
4. 保存后在群内 @机器人发送消息验证往返；不使用确认码，也不提交独立应用凭据字段。

## 既有 LEGACY 独立应用维护

以下旧回调和凭据说明仅用于已有独立绑定的维护；不得据此新建绑定。
新建接口不再接受 App ID / App Secret / Verification Token 等独立应用载荷。


1. 在飞书开放平台创建企业自建应用，记录 App ID / App Secret。
2. 在「事件与回调 → 加密策略」获取 Verification Token；若启用 Encrypt Key，也需在本系统配置相同值。
3. 编辑已有 LEGACY 飞书绑定，核对数字人及凭据后保存；新建请使用上面的共享群聊流程。
4. 复制保存后展示的回调地址，例如 `https://wonder.example/api/integrations/feishu/callback?bindingId=10000`。
5. 飞书事件订阅选择「将事件发送至开发者服务器」，填入该地址。系统支持明文及加密的 URL verification challenge。
6. 订阅 `im.message.receive_v1`。按使用场景开通 `im:message.p2p_msg:readonly`（单聊）、`im:message.group_at_msg:readonly`（群 @机器人），以及 `im:message:send_as_bot`（以应用身份发送消息），按飞书后台要求发布应用并配置可用范围。
7. 将机器人加入群聊后 @机器人，或与机器人单聊，发送一条文本验证收发。

参考官方文档：[接收消息事件](https://open.feishu.cn/document/server-docs/im-v1/message/events/receive)、[回复消息](https://open.feishu.cn/document/server-docs/im-v1/message/reply)、[获取 tenant_access_token](https://open.feishu.cn/document/server-docs/authentication-management/access-token/tenant_access_token_internal)、[事件订阅方式](https://open.feishu.cn/document/server-docs/event-subscription-guide/event-subscription-configure-/request-url-configuration-case)。签名及加密兼容性参考飞书官方 Java SDK 的 [EventDispatcher](https://github.com/larksuite/oapi-sdk-java/blob/v2_main/larksuite-oapi/src/main/java/com/lark/oapi/event/EventDispatcher.java) 和 [Decryptor](https://github.com/larksuite/oapi-sdk-java/blob/v2_main/larksuite-oapi/src/main/java/com/lark/oapi/core/utils/Decryptor.java)。

## 行为和排查

- App Secret、Verification Token、Encrypt Key 作为一个凭据包通过 SecretCrypto 加密保存；接口不返回明文。编辑时密钥留空保留原值；清除 Encrypt Key 需要显式勾选，并同步修改飞书配置。App ID 不允许原地修改。
- 普通事件校验 Verification Token 和 App ID；启用加密时还校验原始请求体签名和五分钟时间窗口。URL verification 按飞书协议使用 Token 校验，不能要求该请求携带普通事件签名。
- 回调只在事件落库后返回成功；后台默认每三秒轮询收件箱，避免回调等待数字人执行。飞书重复投递由 `(binding_id, message_id)` 唯一键去重，进入会话后再用带渠道/绑定前缀的消息 ID 去重。
- 群消息会通过机器人信息接口确认被 @的是当前机器人；不响应其他成员之间的消息、不响应其他机器人的消息。非文本输入被忽略。回复采用文本，Markdown 标记原样呈现；长文本按 Unicode 字符分段，每段带稳定 UUID，降低重试重复发送的风险。
- 收件箱处理失败最多尝试五次，间隔依次为 30/60/90/120 秒；进程退出后，处理中的记录在两分钟租约到期后可重新领取。绑定列表显示最近成功处理时间和最近错误。停用、删除或更换数字人后，不会将已排队的旧消息路由到新数字人。
- `last_success_at` 表示最近成功提交会话或发送回复，不代表长连接状态。持续失败时先检查飞书应用发布和权限、SecretCrypto、服务出站网络、数字人在线版本和执行器状态。修复后可发送新消息验证；已达到重试上限的消息保留为 `FAILED`，不会无限重试。
- 收件箱保存已验证事件的消息内容，不保存回调 Token；属于运行数据，不包含在项目配置备份中。运维可按保留策略清理旧 `DONE` / `FAILED` 记录，保留处理中和待重试记录。会话中已接收的消息仍由会话唯一键去重。
- 项目备份包含飞书应用、数字人映射和状态，**不导出飞书凭据包**（其中含回调验证凭据）。迁移环境后需重新填写三项密钥并更新回调地址。

真实飞书端验收：URL 校验通过；单聊往返成功；群 @当前机器人往返成功；@其他用户不触发；重复消息不产生第二轮；停用后不再调用数字人；修改密钥后旧 Token/签名失效。

## 平台共享群聊模式

平台管理员在「平台配置 → 协作通知」保存一组平台级飞书应用凭据（`GROUP_CHAT` 用途）后，各空间可以复用同一个飞书机器人，按群绑定到不同数字人，无需再为每个空间单独创建飞书应用。

- 应用凭据只存在于平台配置中，群绑定行不保存 App Secret / Verification Token / Encrypt Key；绑定行的 `credential_ref` 必须为空，绑定、备份和接口都不会返回明文密钥。
- 平台共享群聊回调地址为 `https://<域名>/api/integrations/feishu/group-callback?configId=<平台配置ID>`。`configId` 只用于定位平台应用凭据，普通事件仍按飞书协议校验 Verification Token、App ID、签名和五分钟时间窗口，URL verification 也绑定该 `configId`。
- 回调通过 `feishu_message_inbox` 持久化，`binding_id` 记录接收时命中的群绑定，`receipt_context` 保存接收时的路由快照；升级前的历史记录没有快照，按旧路径处理。收件箱轮询、去重和失败重试规则与独立绑定一致。未选择为当前协作渠道时，群回调仍会校验签名，但不再路由新消息。
- 新建共享群绑定只需选择外部群和数字人；当前版本已移除群确认流程，不生成确认码，不需要群内确认。只有当前选中的协作渠道接收新消息。
- 群内消息必须 @机器人 才会处理。平台共享应用回复文本；已有独立绑定保持原有模式，新绑定使用平台共享应用。
- 平台管理员切换协作渠道后，只有当前选中的渠道处理新的群消息；被切换走的渠道进入排空状态，已接收的消息继续处理。群运行时状态（ACTIVE / DRAINING / PAUSED / DISABLED / ERROR）和积压数量可在平台配置页查看，用于判断群消息是否仍在投递。
- 项目备份导出共享绑定的模式、群 ID、数字人、状态和路由版本，不导出平台应用凭据。跨环境恢复后需重新选择平台配置；旧版独立绑定恢复时仍需重新填写密钥。
- 旧客户端只提交独立应用字段时，创建共享频道后的接口会拒绝这类请求并提示升级；请通过新界面的群绑定表单操作。

升级共享群聊先按本版发布说明执行 V094，再执行 V100 移除本轮过渡确认列；备份并停止旧实例后操作，不使用旧二进制回滚到新结构。全新安装使用完整 schema。
