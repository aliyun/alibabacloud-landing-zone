# Frontend translations

System UI uses paired semantic-key JSON namespaces in `locales/zh-CN` and `locales/en-US`. Register each pair in `index.ts`; its exported `resources` provides TypeScript's translation-key contract. Current namespaces: `common` (application preferences and account controls), `shell` (navigation and platform notices), `auth` (sign-in, registration and workspace lifecycle), and `shared` (shared components, access labels, uploads and AI result editors). Add a business namespace when migrating its owning feature.

Use `useTranslation(namespace)` in each component that renders translated labels or formatted values, including routed components. React Router caches route elements: an application-level rerender does not update every route. Include `t` or locale in memo dependencies when the memo contains localized output. Build Select options during render so cached options do not retain old labels. For non-React helpers and asynchronous completion messages, call the exported `i18n` singleton at the time the result is needed. Avoid module-load translations; shared label getters may resolve the current locale while consumers subscribe themselves.

For Ant Design forms, call `useLocalizedValidation(form)` with the existing `Form.useForm()` instance. For conditional or lazy forms, pass the actual attachment predicate as `useLocalizedValidation(form, enabled)`; a disabled hook performs no Form API calls. It refreshes only fields already showing errors when the language changes, preserving values and touched state without validating pristine fields.

Use complete sentences with named parameters. English count messages need `_one` and `_other`; Chinese needs `_other`. Parameter names must match across locales and variants. Use `Trans` for styled interpolation and keep React escaping enabled. Add both translations together; Chinese fallback cannot substitute for missing English resources.

User-authored names, prompts, editable templates, logs, AI output and API payloads stay unchanged. Translate fixed enums through explicit finite mappings, keeping submitted values intact (for example, memory category values are Chinese protocol enums). Preserve attachment names, including the established generated pasted-file prefix. Brand and protocol names such as AutoWonder, Aone, MCP, HSF and Redis stay unchanged. Language self-names stay recognizable. Exact scanner exceptions in the temporary baseline include a reason; never add broad exclusions or regenerate the baseline to permit new UI text.

Use `useLocale()` for locale selection. Use the singleton-aware `formatDateTime` and `formatNumber` helpers while subscribing with `useLocale` or `useTranslation` during render. Language changes must not change time zones, remount forms, clear drafts or rewrite user content.

| Chinese | English |
| --- | --- |
| 工作空间 | Workspace |
| 工单 | Work item |
| 数字员工 | Agent |
| 平台智能体 | Platform agent |
| 小队 | Squad |
| 执行器 | Executor |
| 产物 | Artifact |
| 技能 | Skill |
| 澄清 | Clarification |
| 交付 | Delivery |
| 定时任务 | Scheduled task |
| SDLC 流程 | SDLC workflow |

From `frontend/`, run:

```sh
npm run check:i18n
node --test scripts/check-i18n.test.mjs
VITEST_MAX_THREADS=4 VITEST_MIN_THREADS=1 npm run test:run
npm run build
```

The AST gate finds JSX text, known visible properties, messages and Chinese literals. It cannot prove all data flows; review runtime/backend text and uncaught English constants manually. Reduce exact legacy entries as modules migrate. Add a live language-switch test around actual `RouterProvider` consumers, and verify user input and canonical payload values survive switching.
