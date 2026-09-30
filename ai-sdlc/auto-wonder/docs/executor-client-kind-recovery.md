# 历史缺失客户端类型执行器的排查与恢复指南

## 背景

0.8 及更早版本创建执行器时后端未强制写入 `executor.client_kind`，数据库列又允许 NULL，因此升级后存在 `client_kind` 为 NULL、空字符串或纯空白的存量记录。这类记录打开「启动命令」弹窗时不会展示 Qoder 模型配置，复制启动命令报 17011「执行器缺少客户端类型」。

本修复（fix/executor-client-kind-recovery-20260920）不做任何无依据的全量回填：类型只能由管理员按执行器实际归属明确补选。本文说明如何排查受影响记录，以及如何通过页面完成恢复。

影响边界：仅 `client_kind` 为 NULL/空串/空白的执行器；已有类型或新建的执行器不受影响，也不会被恢复写入触碰（SQL 守卫 `client_kind IS NULL OR TRIM(client_kind) = ''` 保证只改「类型仍为空」的行）。

## 一、受影响记录排查 SQL

> 注意：排查条件必须用 `TRIM(client_kind) = ''` 而不是 `client_kind = ''`。executor 表为 utf8mb4 无显式 COLLATE，MySQL 8 默认解析为 `utf8mb4_0900_ai_ci`（NO PAD，尾部空格参与比较），`'  ' = ''` 为假——等值写法会漏掉纯空白记录，这正是修复前的缺陷语义。

按租户统计受影响数量：

```sql
SELECT tenant_id, COUNT(*) AS affected
FROM executor
WHERE is_deleted = 0
  AND (client_kind IS NULL OR TRIM(client_kind) = '')
GROUP BY tenant_id;
```

列出受影响明细：

```sql
SELECT id, tenant_id, agent_id, name, status,
       client_kind IS NULL AS kind_null,
       HEX(client_kind) AS kind_hex,
       launch_config IS NULL AS config_missing,
       config_version, gmt_create
FROM executor
WHERE is_deleted = 0
  AND (client_kind IS NULL OR TRIM(client_kind) = '')
ORDER BY tenant_id, id;
```

`kind_hex` 用于区分 NULL、空串（`''`）与纯空白（`2020` 等尾部空格字节）；`config_missing = 1` 表示 `launch_config` 同样缺失，需在同一恢复流程中一并补齐。

## 二、页面恢复操作步骤

1. 进入「执行器管理」列表，找到受影响执行器（如「标准小队-项目经理执行器」），打开其「启动命令」弹窗。
2. 弹窗会显示醒目警告：该执行器缺少客户端类型，并出现必选的「客户端类型」下拉（Qoder / Qoder CN）。
3. 按执行器实际接入的 CLI 选择类型；选中后模型、推理力度、上下文窗口、记忆模式按所选类型的默认值带出，可按需调整。
4. 点击保存。保存会在同一次写入中把补选的 `client_kind` 与启动参数一并落库（带乐观锁 `config_version` 校验，并发下后写者会收到 17005，刷新重试即可）。
5. 保存成功后弹窗内警告消失、列表刷新；重新打开弹窗可见模型配置，普通/Debug 启动命令均可预览与复制（provider 分别为 `qoder` / `qodercn`）。

`launch_config` 同时缺失的记录走同一流程：先补选类型，保存时把必要参数一并填上即可；两类缺失错误可区分（见下表）。

## 三、错误码对照

| 错误码 | 含义 | 处理 |
|---|---|---|
| 17011 | 执行器缺少客户端类型 | 在启动命令弹窗补选类型后保存 |
| 17009 | 客户端类型不合法（仅支持 QODER_CLI/QODER_CN_CLI） | 从下拉中选择，勿手输其他值 |
| 17007 | 启动配置不完整 | 在同一弹窗补齐模型等参数后保存 |
| 17005 | 启动配置已被修改（乐观锁冲突） | 刷新后重试保存 |
| 10001 | 执行器已有客户端类型，不允许通过启动配置修改 | 如确需变更，走 DBA 人工变更流程 |

## 四、边界与注意

- 不做自动迁移/回填：没有可靠依据推断历史执行器的真实类型，任何批量 UPDATE 都必须先经人工确认。
- 恢复写入与常规写入一样受租户隔离与 ADMIN 权限约束；软删除记录不会被恢复。
- 补选保存成功后，`client_kind` 不可再通过启动配置接口修改（守卫拒绝），如误选需 DBA 介入更正。
- MCP 工具 `update_executor_launch_config` 与页面同一链路，同样支持带 `clientKind` 的恢复写入。
- 新建执行器入口已强制必填类型，新数据不会再产生缺类型记录。
