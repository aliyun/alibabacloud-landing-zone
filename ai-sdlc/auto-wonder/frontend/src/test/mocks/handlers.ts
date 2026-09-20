import { http, HttpResponse } from 'msw';

type StoredLaunchConfig = {
  model: string | null;
  reasoningEffort: string | null;
  contextWindow: string | null;
  memoryMode: string | null;
  version: number;
};

// 页面「启动命令」弹窗读写的服务端启动配置在测试里用这份内存存储兜底，
// 每个用例开始前必须调用 resetExecutorLaunchConfigStore() 清空，避免用例间串扰。
const executorLaunchConfigStore = new Map<number, StoredLaunchConfig>();

type ExecutorFixture = { clientKind: string | null; token: string };

// 生成命令要用到执行器自身的类型与 token，两者都不属于启动配置，单独用 fixture 提供
const executorFixtures = new Map<number, ExecutorFixture>();

export function resetExecutorLaunchConfigStore(): void {
  executorLaunchConfigStore.clear();
  executorFixtures.clear();
}

export function setExecutorLaunchFixture(id: number, fixture: Partial<ExecutorFixture>): void {
  const current = executorFixtures.get(id);
  // null 是显式传入的「类型缺失」（历史数据），与「未传」不同，不能用 ?? 兜底成默认类型
  executorFixtures.set(id, {
    clientKind: fixture.clientKind !== undefined ? fixture.clientKind : (current?.clientKind ?? 'QODER_CLI'),
    token: fixture.token ?? current?.token ?? `exec_token_${id}`,
  });
}

export type SeededLaunchConfig = {
  model?: string | null;
  reasoningEffort?: string | null;
  contextWindow?: string | null;
  memoryMode?: string | null;
  version?: number;
};

// 创建执行器时后端会把启动配置一并落库，测试里用这个 seeder 复现同一行为
export function setExecutorLaunchConfig(id: number, config: SeededLaunchConfig): void {
  const current = executorLaunchConfigStore.get(id) ?? emptyLaunchConfig();
  executorLaunchConfigStore.set(id, {
    model: config.model === undefined ? current.model : config.model,
    reasoningEffort: config.reasoningEffort === undefined ? current.reasoningEffort : config.reasoningEffort,
    contextWindow: config.contextWindow === undefined ? current.contextWindow : config.contextWindow,
    memoryMode: config.memoryMode === undefined ? current.memoryMode : config.memoryMode,
    version: config.version ?? current.version,
  });
}

function emptyLaunchConfig(): StoredLaunchConfig {
  return { model: null, reasoningEffort: null, contextWindow: null, memoryMode: null, version: 1 };
}

const MOCK_WS_URL = 'wss://community.example/ws/executor';
const MOCK_RUNTIME_VERSION = '0.2.163';
const MOCK_POWERSHELL_UTF8_PREAMBLE = '[Console]::OutputEncoding = [System.Text.Encoding]::UTF8; $OutputEncoding = [System.Text.Encoding]::UTF8; ';
const MOCK_PROVIDERS: Record<string, string> = {
  QODER_CN_CLI: 'qodercn',
  QODER_CLI: 'qoder',
  CLAUDE_CODE: 'claude',
  CODEX_CLI: 'codex',
  CURSOR_CLI: 'cursor',
};

function encodePowerShellCommand(script: string): string {
  const full = MOCK_POWERSHELL_UTF8_PREAMBLE + script;
  let binary = '';
  for (let index = 0; index < full.length; index += 1) {
    const code = full.charCodeAt(index);
    binary += String.fromCharCode(code & 0xff, code >> 8);
  }
  return `powershell -NoProfile -EncodedCommand ${btoa(binary)}`;
}

function mockProviderFor(clientKind: string | null): string {
  return (clientKind ? MOCK_PROVIDERS[clientKind] : undefined) ?? 'claude';
}

function isQoderProvider(provider: string): boolean {
  return provider === 'qoder' || provider === 'qodercn';
}

type LaunchCommandBody = {
  os?: string | null;
  debug?: boolean | null;
  shell?: string | null;
  model?: unknown;
  reasoningEffort?: unknown;
  contextWindow?: unknown;
  memoryMode?: unknown;
};

const LAUNCH_OVERRIDE_KEYS = ['model', 'reasoningEffort', 'contextWindow', 'memoryMode'] as const;

// 返回缺失项描述，配置完整时返回 null；Qoder 系要求四个启动值齐全，其余只要求记忆模式
function incompleteConfigReason(stored: StoredLaunchConfig, provider: string): string | null {
  if (!stored.memoryMode) return '缺少记忆模式';
  if (isQoderProvider(provider) && (!stored.model || !stored.reasoningEffort || !stored.contextWindow)) {
    return '缺少 Qoder 启动参数';
  }
  return null;
}

// 与 ExecutorLaunchCommandService 保持一致：启动值只来自数据库配置，命令由服务端拼装
function buildMockLaunchCommand(id: number, stored: StoredLaunchConfig, fixture: ExecutorFixture,
  os: string, debug: boolean, shell: string | null): Record<string, unknown> {
  const provider = mockProviderFor(fixture.clientKind);
  const isQoder = isQoderProvider(provider);
  const argv = [
    'npx', '-y', `autowonder@${MOCK_RUNTIME_VERSION}`, 'connect',
    '--ws-url', MOCK_WS_URL,
    '--token', fixture.token,
    '--executor-id', String(id),
    '--provider', provider,
    '--memory-mode', stored.memoryMode as string,
  ];
  if (isQoder) {
    argv.push('--model', stored.model as string,
      '--reasoning-effort', stored.reasoningEffort as string,
      '--context-window', stored.contextWindow as string,
      '--token-aware-enable');
  }
  const logFileName = debug ? `aw-${provider}-${id}-260909-101112.log` : null;
  let command: string;
  if (debug) {
    argv.push('--debug');
    command = shell === 'powershell'
      ? encodePowerShellCommand(`${argv.join(' ')} 2>&1 | Tee-Object -FilePath "$HOME/${logFileName}"`)
      : `${argv.join(' ')} 2>&1 | tee ~/${logFileName}`;
  } else {
    command = os === 'windows' ? encodePowerShellCommand(argv.join(' ')) : argv.join(' ');
  }
  return {
    executorId: id,
    clientKind: fixture.clientKind,
    provider,
    memoryMode: stored.memoryMode,
    model: isQoder ? stored.model : null,
    reasoningEffort: isQoder ? stored.reasoningEffort : null,
    contextWindow: isQoder ? stored.contextWindow : null,
    wsUrl: MOCK_WS_URL,
    runtimeVersion: MOCK_RUNTIME_VERSION,
    os,
    debug,
    shell: debug ? shell : null,
    logFileName,
    command,
  };
}

// 用例用字面量路径覆盖该接口时 params 里没有 id，只能回退到解析 URL，
// 否则 Number(undefined) = NaN，会读到一个空配置并错误地回 17007
function launchCommandExecutorId(
  params: Record<string, string | readonly string[]>,
  url: string,
): number {
  const fromParams = Array.isArray(params.id) ? params.id[0] : params.id;
  if (fromParams) return Number(fromParams);
  return Number(/\/api\/executors\/(\d+)\/launch-command/.exec(url)?.[1] ?? NaN);
}

// 导出以便用例在断言请求体的同时复用同一份服务端行为（clone 请求后转交）
export async function resolveLaunchCommand({ params, request }: {
  params: Record<string, string | readonly string[]>;
  request: Request;
}) {
  const id = launchCommandExecutorId(params, request.url);
  const body = await request.json() as LaunchCommandBody;
  const override = LAUNCH_OVERRIDE_KEYS.find((key) => body[key] != null);
  if (override) {
    return HttpResponse.json({
      success: false,
      code: '17008',
      message: `启动参数 ${override} 不支持临时覆盖，请先修改并保存启动配置`,
      data: null,
      traceId: null,
    });
  }
  const stored = executorLaunchConfigStore.get(id) ?? emptyLaunchConfig();
  const fixture = executorFixtures.get(id) ?? { clientKind: 'QODER_CLI', token: `exec_token_${id}` };
  const missing = incompleteConfigReason(stored, mockProviderFor(fixture.clientKind));
  if (missing) {
    return HttpResponse.json({
      success: false,
      code: '17007',
      message: `启动配置不完整（${missing}），请先保存启动配置后再生成命令`,
      data: null,
      traceId: null,
    });
  }
  // 与 ExecutorLaunchCommandService 一致：缺客户端类型的历史执行器不再静默解释成 claude，而是明确拒绝
  if (fixture.clientKind == null || fixture.clientKind.trim() === '') {
    return HttpResponse.json({
      success: false,
      code: '17011',
      message: '该执行器缺少客户端类型，无法生成启动命令',
      data: null,
      traceId: null,
    });
  }
  const os = body.os === 'windows' ? 'windows' : 'posix';
  const debug = body.debug === true;
  const shell = debug
    ? (body.shell === 'bash' || body.shell === 'powershell'
      ? body.shell
      : (os === 'windows' ? 'powershell' : 'bash'))
    : null;
  return HttpResponse.json({
    success: true,
    code: '0',
    message: '',
    data: buildMockLaunchCommand(id, stored, fixture, os, debug, shell),
    traceId: null,
  });
}

// 用户级偏好（后端 `user_setting` 表）在测试里用这份内存存储兜底：需求澄清面板挂载即
// 读取发送方式偏好，没有全局 handler 会让所有渲染面板 / RightPanel / 工单详情页的用例
// 落入 unhandled。每个用例开始前必须调用 resetUserSettingStore() 清空，避免用例间串扰。
const userSettingStore = new Map<string, string | null>();

export function resetUserSettingStore(): void {
  userSettingStore.clear();
}

export function seedUserSetting(key: string, valueJson: string | null): void {
  userSettingStore.set(key, valueJson);
}

function userSettingPayload(key: string) {
  return { key, valueJson: userSettingStore.get(key) ?? null };
}

export const handlers = [
  // 多个页面（技能、执行器等）会无条件拉取执行器列表；全局兜底为空列表，
  // 避免个别用例未 mock 时请求落入 unhandled → 拦截器 401 处理清空 auth store。
  http.get('/api/executors', () => {
    return HttpResponse.json({
      success: true,
      code: '0',
      message: '',
      data: [],
      traceId: null,
    });
  }),
  // 能力库页面挂载即拉取项目分类（打标下拉、管理弹窗与分组视图共用）；
  // 全局兜底为空列表，用例按需 server.use 覆盖。
  http.get('/api/categories', () => {
    return HttpResponse.json({
      success: true,
      code: '0',
      message: '',
      data: [],
      traceId: null,
    });
  }),
  // AppLayout 的平台智能体状态横幅会对管理员发起该请求；全局兜底为一切正常，
  // 避免个别用例未 mock 时请求落入 unhandled → 拦截器 401 处理清空 auth store。
  http.get('/api/platform-agent/status', () => {
    return HttpResponse.json({
      success: true,
      code: '0',
      message: '',
      data: { state: 'OK', agentId: 1, executorCount: 1, onlineExecutorCount: 1 },
      traceId: 'trace-platform-agent-status',
    });
  }),
  // 执行器列表页挂载即读自动升级开关；msw 配的是 onUnhandledRequest:'error'，
  // 没有全局兜底会让所有既有用例直接失败。默认关闭，只读展示，用例按需 server.use 覆盖。
  http.get('/api/platform/runtime-auto-update', () => {
    return HttpResponse.json({
      success: true,
      code: '0',
      message: '',
      data: { executorAutoUpdateEnabled: false, targetVersion: '0.2.160' },
      traceId: null,
    });
  }),
  http.get('/api/executors/:id/launch-config', ({ params }) => {
    const id = Number(params.id);
    const stored = executorLaunchConfigStore.get(id) ?? emptyLaunchConfig();
    return HttpResponse.json({
      success: true,
      code: '0',
      message: '',
      data: { ...stored },
      traceId: null,
    });
  }),
  http.put('/api/executors/:id/launch-config', async ({ params, request }) => {
    const id = Number(params.id);
    const body = await request.json() as Partial<StoredLaunchConfig>;
    const current = executorLaunchConfigStore.get(id) ?? emptyLaunchConfig();
    if (body.version == null || body.version !== current.version) {
      return HttpResponse.json({
        success: false,
        code: '17005',
        message: '启动配置已被修改，请刷新后重试',
        data: null,
        traceId: null,
      });
    }
    const next: StoredLaunchConfig = {
      model: body.model ?? null,
      reasoningEffort: body.reasoningEffort ?? null,
      contextWindow: body.contextWindow ?? null,
      memoryMode: body.memoryMode ?? null,
      version: current.version + 1,
    };
    executorLaunchConfigStore.set(id, next);
    return HttpResponse.json({
      success: true,
      code: '0',
      message: '',
      data: { ...next },
      traceId: null,
    });
  }),
  // 命令由服务端拼装：只接受输出格式选项，任何启动值覆盖都直接报错，不做静默替换
  http.post('/api/executors/:id/launch-command', resolveLaunchCommand),
  http.get('/api/executor-model-catalogs/qoder', () => HttpResponse.json({
    success: true,
    code: '0',
    message: '',
    data: { provider: 'qoder', models: [], lastSuccessfulAt: null },
    traceId: null,
  })),
  http.get('/api/executor-model-catalogs/qodercn', () => HttpResponse.json({
    success: true,
    code: '0',
    message: '',
    data: { provider: 'qodercn', models: [], lastSuccessfulAt: null },
    traceId: null,
  })),
  http.get('/api/capabilities/scheduled-task', () => {
    return HttpResponse.json({
      success: true,
      code: '0',
      message: '',
      data: {
        available: true,
        mode: 'V037_READY',
        clusterReady: true,
        reason: null,
      },
      traceId: 'trace-scheduled-task-capability',
    });
  }),
  http.get('/api/platform/branding/public', () => {
    return HttpResponse.json({
      success: true,
      code: '0',
      message: '',
      data: {
        platformName: 'AutoWonder',
        logoUrl: '/logo.png',
        themeKey: 'aliyun-orange',
        primaryColor: '#f97316',
        domain: 'https://community.example',
        mcpBaseUrl: 'https://community.example/api/mcp',
        recommendedRuntimeVersion: '0.2.125',
        deploymentVersion: 'x.x.x',
        communityEdition: false,
        canManage: false,
      },
      traceId: 'trace-branding',
    });
  }),
  http.get('/api/platform/branding', () => {
    return HttpResponse.json({
      success: true,
      code: '0',
      message: '',
      data: {
        platformName: 'AutoWonder',
        logoUrl: '/logo.png',
        themeKey: 'aliyun-orange',
        primaryColor: '#f97316',
        domain: 'https://community.example',
        mcpBaseUrl: 'https://community.example/api/mcp',
        communityEdition: false,
        canManage: false,
      },
      traceId: 'trace-branding-admin',
    });
  }),
  http.get('/api/platform/branding/capability', () => {
    return HttpResponse.json({
      success: true,
      code: '0',
      message: '',
      data: { canManage: false },
      traceId: 'trace-branding-capability',
    });
  }),
  http.get('/api/agents/reviews/count', () => HttpResponse.json({
    success: true, code: '0', message: '', data: 0, traceId: 'trace-agent-review-count',
  })),
  http.get('/api/memories/reviews/count', () => HttpResponse.json({
    success: true, code: '0', message: '', data: 0, traceId: 'trace-memory-review-count',
  })),
  // 平台配置页的「平台管理员」Tab 会拉取管理员名册与候选人；全局兜底为"无管理员且无权管理"，
  // 避免个别用例未 mock 时请求落入 unhandled → 拦截器 401 处理清空 auth store。
  http.get('/api/platform/admins', () => {
    return HttpResponse.json({
      success: true,
      code: '0',
      message: '',
      data: { admins: [], canManage: false },
      traceId: 'trace-platform-admins',
    });
  }),
  http.get('/api/platform/admins/candidates', () => {
    return HttpResponse.json({
      success: true,
      code: '0',
      message: '',
      data: [],
      traceId: 'trace-platform-admin-candidates',
    });
  }),
  http.post('/api/auth/login', () => {
    return HttpResponse.json({
      success: true,
      code: '0',
      message: '',
      data: {
        userId: 1,
        accessToken: 'test-access',
        refreshToken: 'test-refresh',
        user: {
          id: 1,
          username: 'test-user',
          nickname: '测试用户',
          email: 'test@example.com',
        },
      },
      traceId: 'trace-1',
    });
  }),
  http.get('/api/workspaces/mine', () => {
    return HttpResponse.json({
      success: true,
      code: '0',
      message: '',
      data: [],
      traceId: 'trace-workspaces-mine',
    });
  }),
  http.get('/api/agents', () => {
    return HttpResponse.json({
      success: true,
      code: '0',
      message: '',
      data: [],
      traceId: 'trace-agents-list',
    });
  }),
  http.get('/api/squads', () => {
    return HttpResponse.json({
      success: true,
      code: '0',
      message: '',
      data: [],
      traceId: 'trace-squads-list',
    });
  }),
  // The squad detail modal fetches the derived SDLC flows and executors separately from the list.
  http.get('/api/squads/:id', ({ params }) => {
    return HttpResponse.json({
      success: true,
      code: '0',
      message: '',
      data: {
        id: Number(params.id),
        name: '',
        description: null,
        ownerId: null,
        version: 0,
        memberAgentIds: [],
        memberCount: 0,
        roleCount: 0,
        executorOnlineCount: 0,
        executorTotalCount: 0,
        sdlcCount: 0,
        sdlcs: [],
        executors: [],
        gmtCreate: null,
      },
      traceId: 'trace-squads-detail',
    });
  }),
  http.get('/api/agents/reviews/count', () => {
    return HttpResponse.json({
      success: true,
      code: '0',
      message: '',
      data: 0,
      traceId: 'trace-agents-reviews-count',
    });
  }),
  http.get('/api/memories/reviews/count', () => {
    return HttpResponse.json({
      success: true,
      code: '0',
      message: '',
      data: 0,
      traceId: 'trace-memories-reviews-count',
    });
  }),
  // 分页器总数改由后端 count 接口提供；这里兜底为 0，
  // 需要断言真实总数的用例自行 server.use 覆盖。
  http.get('/api/memories/count', () => {
    return HttpResponse.json({
      success: true,
      code: '0',
      message: '',
      data: 0,
      traceId: 'trace-memories-count',
    });
  }),
  http.get('/api/memories/grouped/count', () => {
    return HttpResponse.json({
      success: true,
      code: '0',
      message: '',
      data: 0,
      traceId: 'trace-memories-grouped-count',
    });
  }),
  // RightPanel progress 分支挂载 DebugLogList 后会无条件拉取调试日志；
  // 全局兜底为空列表，避免渲染 RightPanel/WorkitemDetailPage 的用例落入 unhandled。
  http.get('/api/debug-logs', () => {
    return HttpResponse.json({
      success: true,
      code: '0',
      message: '',
      data: [],
      traceId: 'trace-debug-logs-list',
    });
  }),
  http.get('/api/dispatches/:dispatchId/live-activity', ({ params }) => {
    const dispatchId = Number(params.dispatchId);
    return HttpResponse.json({
      success: true,
      code: '0',
      message: '',
      traceId: null,
      data: {
        schemaVersion: '1',
        dispatchId,
        agentId: null,
        workitemId: null,
        sourceType: 'WORKITEM',
        attempt: 1,
        dispatchStatus: null,
        changed: false,
        lastSeq: 0,
        lastUpdatedAt: null,
        currentAction: null,
        actions: [],
        totalActions: 0,
        truncated: false,
        awaitingRuntime: false,
      },
    });
  }),
  http.get('/api/workitems/:workitemId/watchers', () => {
    return HttpResponse.json({
      success: true,
      code: '0',
      message: '',
      data: [],
      traceId: 'trace-workitem-watchers',
    });
  }),
  http.get('/api/users/me/settings', () => {
    return HttpResponse.json({
      success: true,
      code: '0',
      message: '',
      data: [...userSettingStore].map(([key, valueJson]) => ({ key, valueJson })),
      traceId: 'trace-user-settings-list',
    });
  }),
  http.get('/api/users/me/settings/:key', ({ params }) => {
    return HttpResponse.json({
      success: true,
      code: '0',
      message: '',
      data: userSettingPayload(String(params.key)),
      traceId: 'trace-user-setting-get',
    });
  }),
  http.put('/api/users/me/settings/:key', async ({ params, request }) => {
    const key = String(params.key);
    const body = await request.json() as { valueJson?: string | null } | null;
    userSettingStore.set(key, body?.valueJson ?? null);
    return HttpResponse.json({
      success: true,
      code: '0',
      message: '',
      data: userSettingPayload(key),
      traceId: 'trace-user-setting-put',
    });
  }),
  http.delete('/api/users/me/settings/:key', ({ params }) => {
    userSettingStore.delete(String(params.key));
    return HttpResponse.json({
      success: true,
      code: '0',
      message: '',
      data: null,
      traceId: 'trace-user-setting-delete',
    });
  }),
];
