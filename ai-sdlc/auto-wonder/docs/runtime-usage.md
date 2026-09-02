# Runtime Usage — AutoWonder 客户端运行时使用指南

AutoWonder 客户端运行时(Runtime)是部署在执行机上的本地执行端,通过 npm 包 `autowonder` 分发,负责连接 AutoWonder 服务端、接收 dispatch 任务并在本机驱动 AI Agent(claude / codex / qoder / qodercn)执行。本文覆盖命令清单、常见用法、以及典型场景问题与现象(QA)。

## 1. 环境要求

| 项 | 要求 |
|---|---|
| Node.js | `>=16`;使用 qoder / qodercn 时需要 `>=20` |
| Agent CLI | 至少安装 claude、codex、qodercli 之一;qodercn 需显式指定 |
| 网络 | 可访问服务端 WebSocket 端点(`--ws-url`) |

## 2. 命令清单

| 命令 | 说明 |
|---|---|
| `autowonder connect --ws-url <url> --token <token> --executor-id <id>` | 连接服务端并前台运行(推荐的生产用法) |
| `autowonder start [--provider claude]` | 后台启动 daemon(本地模式) |
| `autowonder stop` | 停止后台 daemon |
| `autowonder status` | 查看 daemon 状态与健康信息 |
| `autowonder dispatch <assignment.json>` | 本地模式:手工提交一个 dispatch |
| `autowonder install [--force]` | 安装/更新 daemon 二进制并启动 |
| `autowonder workitem upload --server-url <url> --workitem-id <id> --file <path>...` | 上传本地需求/设计文档到工单 |

统一通过 npx 调用,例如:

```bash
npx -y autowonder connect --ws-url wss://autowonder.example.com/ws/executor \
  --token xxx --executor-id 10000 --provider qoder
```

## 3. 常用参数

| 参数 | 说明 |
|---|---|
| `--provider <name>` | Agent 提供方:claude、codex、qoder、qodercn;默认自动探测,qodercn 必须显式指定 |
| `--max-tasks <n>` | 最大并发 dispatch 数,默认 10 |
| `--workspace-root <path>` | workspace 目录(dispatch 仓库工作区);默认 `~/autowonder_workspaces_<provider>_<executor-id>`;也可用环境变量 `AUTOWONDER_WORKSPACE_ROOT` |
| `--model` / `--reasoning-effort` | 仅 codex、qoder、qodercn 支持 |
| `--context-window <n>` | 仅 qoder/qodercn;可选 1000000、400000、260000 |
| `--settings <file>` | 仅 claude:settings JSON 文件 |
| `--memory-mode <mode>` | platform(默认)、provider-local、none |
| `--name <name>` | Runtime 名称,默认 provider + executor ID |
| `--debug` | 将 daemon 与 agent 活动输出到控制台和日志,用于排障 |

## 4. 典型用法

### 4.1 连接服务端(执行机常驻)

```bash
npx -y autowonder connect --ws-url wss://autowonder.example.com/ws/executor \
  --token <executor-token> --executor-id 10000 --provider qoder
```

### 4.2 本地模式(手工提交任务)

```bash
npx -y autowonder start
npx -y autowonder dispatch ./assignment.json
npx -y autowonder status
```

### 4.3 指定 workspace 目录(如 Windows 非 C 盘)

```bash
npx -y autowonder connect --ws-url wss://autowonder.example.com/ws/executor \
  --token xxx --executor-id 10000 --provider qoder --workspace-root D:\autowonder
```

### 4.4 上传需求/设计文档到工单

```bash
npx -y autowonder workitem upload --server-url https://autowonder.example.com \
  --workitem-id 50063 --file ./req.md --file ./design.png --json
```

## 5. 场景问题与现象(QA)

### Q1:Windows 执行机 dispatch 失败,报 `symlink ... A required privilege is not held by the client`

**现象**:dispatch 启动阶段投影仓库到 workspace 时报错:

```
execute dispatch: project repo XXX into workspace: symlink
C:\...\repos\XXX → ...\workspace\repos\XXX:
A required privilege is not held by the client.
```

**原因**:Windows 创建目录 symlink 需要 `SeCreateSymbolicLinkPrivilege`,普通用户默认没有该权限(需启用开发者模式或管理员运行)。

**应对**:
1. 客户端运行时已将 repo 投影 fallback 为 NTFS junction(`mklink /J`),junction 不需要特权,普通用户即可运行(见 auto-wonder-client-runtime MR !218);升级到包含该修复的版本后无需任何额外配置。
2. 如需进一步规避 C 盘权限/空间问题,用 `--workspace-root D:\autowonder` 将 workspace 指到非 C 盘。
3. 兜底手段:启用 Windows 开发者模式或以管理员身份运行(不推荐作为常态方案)。

### Q2:想把 workspace 放到自定义目录/非 C 盘,怎么配?

**方式一(推荐)**:connect 时加 `--workspace-root <path>`。
**方式二**:设置环境变量 `AUTOWONDER_WORKSPACE_ROOT`。
未指定时默认落在用户主目录:`~/autowonder_workspaces_<provider>_<executor-id>`。

### Q3:启动时报 `No agent CLI detected`

**原因**:本机未检测到任何受支持的 Agent CLI。
**应对**:安装 claude、codex、qodercli 之一后重试;使用 qodercn 时必须显式 `--provider qodercn`(自动探测不会选择 qodercn)。

### Q4:使用 qoder/qodercn 报 Node.js 版本相关错误

**原因**:qoder/qodercn 依赖 Node.js 20+。
**应对**:升级 Node.js 到 20 或更高版本后重新 connect。

### Q5:参数被拒绝,如 `--context-window is supported only for qoder and qodercn`

**原因**:部分参数仅在特定 provider 下有效:`--model`/`--reasoning-effort` 仅 codex/qoder/qodercn;`--context-window` 仅 qoder/qodercn 且取值必须为 1000000/400000/260000;`--settings` 仅 claude。
**应对**:按第 3 节参数表核对 provider 兼容性后再传参。

### Q6:任务执行异常,需要看详细日志

**应对**:connect 时加 `--debug`,daemon 与 agent 的完整活动会流式输出到控制台并写入日志文件;再结合 `autowonder status` 确认 daemon 健康状态。

## 6. 参考

- 客户端运行时仓库:`auto-wonder-client-runtime`(npm 包 `autowonder`)
- Windows symlink 修复与 `--workspace-root` 能力:auto-wonder-client-runtime 分支 `fix/win-symlink-workspace-root-20260826`,MR !218
