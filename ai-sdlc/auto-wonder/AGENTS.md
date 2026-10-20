# Repository Agent Instructions

## Exact Community → GitHub mirrors

For publishing an already accepted tree, follow
[the mirror verification contract](docs/community/github-mirror-verification.md).
Run `scripts/verify-community-mirror.py` against immutable commits; do not
manually compare files. Matching trees inherit Community acceptance. Do not
repeat builds, Skill tests, E2E or startup merely for a GitHub mirror.
The lifecycle instructions below apply when actual application verification
is required, not to this identity-only publication step.

## Community release and startup verification

For any request to build, start, inspect, or release-verify the community
edition, follow [e2e-tests/AGENT_GUIDE.md](e2e-tests/AGENT_GUIDE.md). Use
`e2e-tests/verify.sh` as the only orchestration entry point. Target the exact
worktree with `--project-root`; do not fetch, checkout, or merge as part of a
verification run.

Do not print credentials. The lifecycle writes Agent-readable credentials to a
mode-`0600` `runtime.env`; read individual values only when a check requires
them. A release verdict requires a successful `--check`, not merely a running
process or a successful curl.

## 前端多语言规范

- 用户可见的前端文案必须放在 `frontend/src/shared/i18n/locales/{zh-CN,en-US}/` 下成对的语义命名空间中。在 `shared/i18n/index.ts` 中注册每个命名空间；该文件导出的 `resources` 同时定义编译期翻译键约束。React 内使用 `useTranslation(namespace)`，React 外使用导出的 `i18n` 单例。用户自行编写的名称和内容保持原样。
- 使用 `useLocale()` 获取当前语言及切换语言；`LanguageSwitch` 使用各语言的自称，并满足无障碍要求。语言偏好键 `ui.locale` 使用现有的当前用户设置 API。`formatDateTime` 和 `formatNumber` 使用单例的当前语言；React 组件在渲染时调用格式化函数，必须通过 `useLocale` 或 `useTranslation` 订阅语言变化。
- 前端国际化检查、检查器单测、前端单测及构建属于 Community 质量验收阶段。按 `e2e-tests/verify.sh --start` 的覆盖复用构建结果，仅补充未覆盖的检查；冻结候选前不提前运行正式测试。复数键采用 i18next 后缀，各语言覆盖其 Intl.PluralRules 类别，插值参数一致。
- TypeScript AST 检查覆盖 JSX 文本和表达式、已知的可见属性和对象属性、通知和消息调用，以及中文自然语言常量；排除测试、注释、导入、类型字面量和已知技术字段。该检查无法证明运行时生成的内容、后端内容或任意英文常量是否进入界面，必须人工审查这些路径。新增可见的英文字段时，按需补充检测器和测试。
- `frontend/scripts/i18n-legacy-baseline.json` 是临时迁移债务：按文件、类型和文本精确记录出现次数，其中也包含有意保留的中英文语言自称。页面迁移时删除对应条目；禁止通过重新生成基线来放行新增文案，也禁止忽略整个已有页面。在同一旧文件中重复添加基线字面量，仍会导致检查失败。基线不验证译文质量。

## 后端多语言规范

- 系统自身的文案必须在同一次改动中，添加到两个 UTF-8 文件 `src/main/resources/i18n/messages_{zh_CN,en_US}.properties`，使用语义键、完整句子和数字占位参数 `{0}`。`ErrorCode.getMessageKey()` 从枚举名称派生默认键；`ErrorCode.getMessage()` 以及异常的 `getMessage()`/`getLocalizedMessage()` 保留原始诊断信息。禁止根据文本相等、中文原文或错误码匹配来推断翻译意图。
- 默认的 `BizException(ErrorCode)` / `(ErrorCode, Throwable)` 和 `Result.fail(ErrorCode)` 在响应边界渲染。自定义系统详情使用 `BizException.keyed(ErrorCode, key, Object[] args, diagnostic[, cause])`；显式包装原始错误码使用 `BizException.keyed(String code, key, Object[] args, diagnostic, cause)`。包装异常原因时保留元数据，捕获异常时使用 `Result.fail(BizException)`，结构化失败使用 `Result.failKeyed(code, key, args, diagnostic, data)`。普通 String 构造方法和工厂方法保留原文。`KeyedIllegalArgumentException(key, args, diagnostic[, cause])` 必须保留父类捕获逻辑和诊断信息；面向用户的响应转换必须保留原有 HTTP 状态（global200 与 topic400）。数组进行防御性复制，参数值本身必须是稳定的原始数据。
- `SystemMessages.render(key, args, diagnostic)` 使用当前请求上下文；没有请求上下文时默认中文。显式重载 `render(key, args, diagnostic, Locale)` 支持按接收者语言渲染。AuthFilter 与名为 `localeResolver` 的 bean 共用按权重解析 Accept-Language 的逻辑。禁止修改 JVM 默认语言，也禁止让请求语言上下文被子线程继承。必须保留错误码、HTTP 状态、追踪信息、请求 ID、业务载荷、外部/用户/AI 原始内容、诊断日志、持久化历史和协议分类。
- 通知必须解析每位接收者现有的 `ui.locale` 偏好，并在持久化和投递前使用显式 Locale 渲染。新建或入队的 IM 任务必须保存接收者语言快照；已有且受支持的快照保持不变。现有快照不受支持或旧任务没有快照时，使用中文。读取、领取、重试和死信队列（DLQ）处理都必须使用该快照，禁止重新解析当前偏好或改写旧载荷和历史。只有显式声明且属于有限集合的来源意图，才允许翻译兜底标签；自行编写的内容、缺失或未知的来源意图保持原文。请求语言不得覆盖接收者偏好；禁止改写历史通知文案或诊断信息。
- 增加双语边界及原始内容保留测试；由 Community 质量阶段通过唯一 lifecycle 验证入口运行后端测试，核实 SystemMessagesResourceTest 等资源与响应边界测试的真实结果。不得以旧报告代替当前验收。
