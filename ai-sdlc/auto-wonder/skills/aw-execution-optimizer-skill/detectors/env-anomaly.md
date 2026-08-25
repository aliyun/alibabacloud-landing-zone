# 环境异常检测

## 检测项

### 残留进程
- dispatch.completed 后检查是否有 `node`/`vite`/`java` 进程仍以该 capsule 路径运行
- 方法:从日志中的 `workspace.processes_reaped` 事件获取(v0.2.137+)

### 端口冲突
- agent 连续尝试多个端口(如 5173 → 5179 → 5183)
- 检测:`--port` 参数出现 ≥2 个不同值

### 下载超时
- 单条 bash.call 耗时 > 60s 且命令含 `npm install` / `playwright install` / `pip install`

### npm 双源
- 同一 dispatch 中出现两个不同的 `--registry` 参数(公网 vs 内网)

### 平台 optional 二进制不可靠(跨工单复发模式)
- 同一执行机连续 ≥2 个工单出现 esbuild/swc/sharp 等 optionalDependencies 报错
- 检测:`npm install` 后仍需额外 `npm install --no-save @esbuild/<platform>` 或 `rm -rf node_modules && npm install`;或首次 install 报 exit 0 但后续 vitest/tsc/build 因缺平台二进制失败
- 判据:若出现 ≥2 次 npm install 且中间有 vitest/tsc/build 启动失败 → 环境问题
- 实证:三轮复发(50307 QA 89.4s / 50309 DEV 359.8s / 50309 QA 48.7s),参数逐次升级(`--no-save` → `--no-package-lock` → `--cache /tmp`)是典型的 agent 与环境搏斗模式
- 建议:
  - 根治:在执行机仓库目录预热一次 `npm install --include=optional` 让平台二进制与 npm cache 就位
  - 核查:`package-lock.json` 是否在不同平台(x64 vs arm64)的机器生成
  - SDLC 兜底(减少往返,不根治):"node_modules 缺失时一次装全(含平台 optional 二进制),装完确认测试能启动再跑;不要分多次补装、失败后重跑"
  - **不要**建议改用 `npm ci` —— 实测证明 `npm ci` 同样会装错平台二进制(50307 QA 就是用 `npm ci` 触发的)

## 输出

如果检测到 → 在报告中列为环境问题,附配置建议。
