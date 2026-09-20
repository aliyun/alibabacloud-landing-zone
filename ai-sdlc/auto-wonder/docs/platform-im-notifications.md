# 平台协作通知

`/workspaces/branding` 的协作通知全局选择钉钉或飞书（二选一）。保存时原渠道自动停用，旧凭据和个人身份保留。关闭通知开关不会改变选择，项目通知配置、项目消息渠道集成和 `/profile/settings` 的 IM 工号均跟随选择。后台拒绝配置其他渠道。

- 钉钉：配置 AppKey、AppSecret、RobotCode，个人填写钉钉用户工号。
- 飞书：配置自建应用 App ID、AppSecret，开启机器人能力、发送消息权限及所需用户 ID 权限，发布应用并确保接收者在应用可用范围内。个人填写该企业的 `user_id`，不是员工编号、`open_id` 或手机号。保存后可发送测试消息验证。
- 项目通知：复用平台机器人凭据，按当前渠道启用通知并配置事件偏好，无需重复配置 Webhook。旧 Webhook 配置保留在数据库中但不再使用；原先该渠道仅为日志占位实现。
- 平台切换后，队列中的旧渠道通知仍保留原渠道标识，在原渠道不可用时跳过，不会转发给另一个渠道的身份。

部署前执行 `docs/migration/V065__exclusive_im_provider.sql`。迁移默认选择钉钉，保留已有钉钉配置和通知偏好。新增飞书偏好默认关闭。

飞书协议参考：[发送消息](https://open.feishu.cn/document/server-docs/im-v1/message/create)、[获取自建应用 tenant_access_token](https://open.feishu.cn/document/server-docs/authentication-management/access-token/tenant_access_token_internal)。
