# 行为信号检测器

## 8 类已验证的浪费模式

### 1. 子代理白派
**检测:** `agent.tool_use` 中 `tool=Agent` 或 `tool=Explore` 出现,且主线在子代理返回前用 Read/Grep/Glob 做了同样主题的搜索。

**判据:** 子代理返回后主线的反应是否引用了报告中的新信息。如果只说"Great report"或"Research complete"然后直接做下一步 → 白派。

**实证案例:** 基线 400164 子代理 6m00.1s,主线早 1m33.8s 完成同样调研,报告零新信息。

---

### 2. 步骤边界重做
**检测:** agent.message 流(reassembled)中匹配以下关键词:
- 被收集清空 / 已被收集清空
- 随上轮产物被清理
- 在步骤间被重置 / 在步骤间被收集
- 重新生成浏览器验收 / 重建 evidence
- 重新写入后再跑 / 重装

**注意:** 这个信号在平台 v0.2.133+ 应该消失(已通过 `## Artifact Lifecycle` 声明)。如果仍出现,说明平台版本过旧或声明未生效。

---

### 3. 浏览器/工具下载
**检测:** `bash.call` 命令中包含:
- `npx playwright install`
- `npm install --no-save playwright`
- `npm install --no-save @vitest/coverage`
- 任何 `npm install` 耗时 > 30s

**判据:** 该工具是否是仓库已有的测试基建(检查 package.json 的 devDependencies 或 scripts)。

---

### 4. CR 重跑测试
**检测:** 在 CR 数字人的 dispatch 中出现:
- `vitest run` / `npm run test`
- `mvn test` / `go test`
- `npm install`(安装依赖以跑测试)

**判据:** CR 的角色是评审代码,不是运行测试。上游(DEV)已提供测试日志在交付物中。

---

### 5. 能力穷尽探测
**检测:** 连续(≥5 次)出现以下模式:
- `which <tool>` / `ls /usr/local/bin | grep <tool>`
- `npm view @<scope>/<tool>`
- `npm search --registry=<url>`
- 读取 provider-home 下的 config/mcp.json

**判据:** 如果平台 `## Available Capabilities` 已声明了可用工具清单,则任何对清单外工具的探测都是浪费。

---

### 6. 广度搜索无贡献
**检测:** 该步 Read 过的仓库内文件数 vs 最终 diff 改动的文件数。

**判据:** `读过且被改的 / 读过的总数 < 30%` → 广度搜索效率低。

**计算:**
```python
read_files = {f for tool_use if tool=="Read" and "repos/" in file_path}
diff_files = {f for f in git_diff_name_only}
ratio = len(read_files & diff_files) / len(read_files)
```

---

### 7. 覆盖率工具自造
**检测:** `agent.tool_use` 中 tool=Write 且文件名含:
- `inc_cov` / `coverage` / `cov.pl` / `cov.py`
- 或 bash.call 中 `npm install` 含 `coverage-v8` / `jacoco` 且仓库 package.json 未声明它

**判据:** 覆盖率工具应由仓库自身配置;SDLC 步骤不应触发 agent 现造。

---

### 8. 后台任务后弃回合
**检测:** 同一步骤内出现下面这组组合:
- `bash.call` 的 command 是测试/构建/安装类,且以 `&` 结尾、或含 `nohup`、或用 provider 的后台执行参数
- 紧随其后的 agent.message 流(reassembled)含:`waiting for` / `in the background` / `completion notifications` / 等待…完成 / 后台运行 / 完成后我将
- 该回合内没有 `type=completion_requested` 的 step-event

**必须排除的假阳性:** 单条命令内的 `&` + `wait`(如 `cmd1 & cmd2 & wait`)**不是**这个信号 —— 它在一条前台命令里并行跑多件事,整条命令阻塞到 `wait` 返回,回合不会被放弃。这是**推荐**写法,不要报成问题。判据不是命令里有没有 `&`,而是**回合有没有在命令出结果前就结束**。同理,一个回合内并发发起多个前台工具调用也是推荐写法。

**判据:** 读 `state/events.jsonl` 看该 dispatch 的终态。若是 `step.failed` reason=`missing completion request` → 确诊:这一整个 attempt 的墙钟(含依赖安装与测试)全部作废,下一个 attempt 在全新 `attempt-N/workspace` 从零重跑,同一份指令下还会再踩。这是单点浪费里放大倍数最高的一类。

**实证案例:** 工单 50245 attempt 1 与 attempt 2 连续两次踩中(步骤墙钟 3m10s + 1m21s),attempt 3 改成前台执行(mvn 实测 119s)才通过。三次派发的被测代码完全相同,前两次的 npm ci + mvn test 开销纯属白付,整条 QA 链路从 13:41 拖到 13:56。

**关联:** 这个信号几乎总伴随 `detectors/sdlc-bad-patterns.md` 特征 8(长命令步骤不给回合约束)。行为信号确认现象,SDLC 特征定位病根 —— 报告时一起给,只报行为信号会让用户以为是 agent 不听话。

---

### 8c. 后台任务模式 + 回合内轮询(不致命但静默浪费)
**检测:** `bash.call` 的 input 中 `"run_in_background":true` 出现 ≥2 次,且同步骤内有 Read/Bash 对 provider `tasks/*.output` 文件的读取,或含 `sleep` + `tail`/`grep` 等待输出就绪。

**排除:** 仅 1 次 bg + 1 次读不算(可能是合理的"快速看一眼")。阈值:bg ≥ 2 或 poll ≥ 3 或含 `sleep` 等待命令。

**判据:** 这些轮询回合有没有产出交付物(Write/evidence/comment)?如果只是 tail/grep/ls/pgrep 输出文件 → 8c 命中。

**实证案例:**
- 工单 50309 QA 400173:4×bg + 8 轮询 + `sleep 45`(在输出已就绪 7s 后发起)= 107.5s(步骤的 19.8%)
- 工单 50309 DEV 400166:9×bg + 6 轮询 + `while pgrep ...; do sleep 5; done` = 44.7s + 弃回合补救重定向 70s
- 工单 50314 同步骤优化后:0×bg、0 轮询(降 100%),质量不变

**与 8a 的关系:** 8c 不致命(不会 step.failed),但经常是 8a 的前兆 —— agent 习惯了"后台+轮询"模式后,某一回合轮询累了就结束回合去等,踩中 8a。工单 50309 DEV 400166 同时命中了 8a 和 8c:前 8 次用后台+轮询(8c),第 9 次直接弃回合(8a),消耗掉 runtime 唯一补救额度。

**修法:** 在【执行方式】模板中点名 `run_in_background` 这个工具参数。更新后的完整模板:

```
【执行方式】测试、构建、依赖安装等长命令要在当前回合内拿到结果。鼓励并行发起:把多条命令合成一条前台命令 `cmd1 & cmd2 & wait`,或在一个回合内并发发起多个前台工具调用。不要用工具的后台任务模式(run_in_background)发起后再反复读输出文件或 sleep 等待,那会把一次阻塞等待换成多个来回;也不要发起后就结束回合去等通知。
```

与原版模板的区别:多了"不要用工具的后台任务模式(run_in_background)发起后再反复读输出文件或 sleep 等待,那会把一次阻塞等待换成多个来回"这一从句,精确打在 provider 的后台任务机制上,而非 `&` 字符上。
