import { afterEach, beforeEach, describe, it, expect, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { CLIENT_KINDS, ExecutorListPage, isQoderClientKind, readQoderStartupPreference, writeQoderStartupPreference } from './ExecutorListPage';
import { buildStartupCommand, detectStartupOs } from './startupCommand';
import { QODER_MODELS, qoderOptionsForModel } from './qoderOptions';
import { useAuthStore } from '@/shared/auth/store';

const originalClipboard = Object.getOwnPropertyDescriptor(navigator, 'clipboard');
const originalExecCommand = Object.getOwnPropertyDescriptor(document, 'execCommand');

const PREFS_KEY_PREFIX = 'autowonder.executor.qoderStartupOptions';

function clearQoderPrefs() {
  for (let i = 0; i < localStorage.length; i++) {
    const key = localStorage.key(i);
    if (key?.startsWith(PREFS_KEY_PREFIX)) {
      localStorage.removeItem(key);
      i--;
    }
  }
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter><ExecutorListPage /></MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('ExecutorListPage', () => {
  beforeEach(() => {
    useAuthStore.getState().clear();
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'ADMIN');
    clearQoderPrefs();
    vi.restoreAllMocks();
  });

  afterEach(() => {
    if (originalClipboard) {
      Object.defineProperty(navigator, 'clipboard', originalClipboard);
    } else {
      Reflect.deleteProperty(navigator, 'clipboard');
    }
    if (originalExecCommand) {
      Object.defineProperty(document, 'execCommand', originalExecCommand);
    } else {
      Reflect.deleteProperty(document, 'execCommand');
    }
  });

  it('renders agent selector and create button', async () => {
    server.use(
      http.get('/api/agents', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [{ id: 1, name: 'Alpha', avatarUrl: null, status: 'ONLINE', onlineVersionId: null, editingVersionId: null, latestVersionNo: 1, version: 1, gmtCreate: '2026-07-01' }],
      })),
      http.get('/api/executors', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [],
      })),
    );
    renderPage();
    expect(await screen.findByText('执行器管理')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /新建执行器/ })).toBeInTheDocument();
  });

  it('shows all executors by default with their owning agent', async () => {
    server.use(
      http.get('/api/agents', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [{ id: 1, name: 'Alpha', avatarUrl: null, status: 'ONLINE', onlineVersionId: null, editingVersionId: null, latestVersionNo: 1, version: 1, gmtCreate: '2026-07-01' }],
      })),
      http.get('/api/executors', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [{ id: 10, agentId: 1, agentName: 'Alpha', name: 'runner-01', status: 'OFFLINE', clientKind: 'QODER_CLI', lastHeartbeat: null, gmtCreate: '2026-07-01' }],
      })),
    );
    renderPage();

    expect(await screen.findByText('runner-01')).toBeInTheDocument();
    expect(screen.getByText('Alpha')).toBeInTheDocument();
    expect(screen.queryByText(/请在右上角选择一个 Agent/)).not.toBeInTheDocument();
  });

  it('renders lastConnectIp column with IP or dash fallback', async () => {
    server.use(
      http.get('/api/agents', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [{ id: 1, name: 'Alpha', avatarUrl: null, status: 'ONLINE', onlineVersionId: null, editingVersionId: null, latestVersionNo: 1, version: 1, gmtCreate: '2026-07-01' }],
      })),
      http.get('/api/executors', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [
          { id: 10, agentId: 1, agentName: 'Alpha', name: 'runner-ip', status: 'ONLINE', clientKind: 'QODER_CLI', lastConnectIp: '203.0.113.50', lastHeartbeat: null, gmtCreate: '2026-07-01' },
          { id: 11, agentId: 1, agentName: 'Alpha', name: 'runner-noip', status: 'OFFLINE', clientKind: 'QODER_CLI', lastConnectIp: null, lastHeartbeat: null, gmtCreate: '2026-07-01' },
        ],
      })),
    );
    renderPage();

    expect(await screen.findByText('203.0.113.50')).toBeInTheDocument();
    expect(screen.getAllByText('接入 IP').length).toBeGreaterThanOrEqual(1);
    const dashCells = await screen.findAllByText('-');
    expect(dashCells.length).toBeGreaterThanOrEqual(1);
  });

  it('configures Qoder runtime options before copying an existing executor command', async () => {
    const user = userEvent.setup();
    server.use(
      http.get('/api/agents', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [{ id: 1, name: 'Alpha', avatarUrl: null, status: 'ONLINE', onlineVersionId: null, editingVersionId: null, latestVersionNo: 1, version: 1, gmtCreate: '2026-07-01' }],
      })),
      http.get('/api/executors', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [{ id: 10, agentId: 1, agentName: 'Alpha', name: 'qoder-runner', status: 'OFFLINE', clientKind: 'QODER_CLI', lastHeartbeat: null, gmtCreate: '2026-07-01' }],
      })),
      http.get('/api/executors/10/token', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: 'exec_test_token',
      })),
    );
    renderPage();

    await screen.findByText('qoder-runner');
    await user.click(screen.getByRole('button', { name: /启动命令/ }));

    expect(screen.getByRole('dialog', { name: '启动命令 · qoder-runner' })).toBeInTheDocument();
    expect(screen.getByText('Qoder 模型')).toBeInTheDocument();
    expect(screen.getByText('Reasoning Effort')).toBeInTheDocument();
    expect(screen.getByText('Context Window')).toBeInTheDocument();
  });

  it('copies an existing executor command when the Clipboard API is unavailable', async () => {
    const user = userEvent.setup();
    const execCommand = vi.fn(() => true);
    Object.defineProperty(document, 'execCommand', {
      configurable: true,
      value: execCommand,
    });
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: undefined,
    });
    server.use(
      http.get('/api/agents', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [{ id: 1, name: 'Alpha', avatarUrl: null, status: 'ONLINE', onlineVersionId: null, editingVersionId: null, latestVersionNo: 1, version: 1, gmtCreate: '2026-07-01' }],
      })),
      http.get('/api/executors', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [{ id: 10, agentId: 1, agentName: 'Alpha', name: 'qoder-runner', status: 'OFFLINE', clientKind: 'QODER_CLI', lastHeartbeat: null, gmtCreate: '2026-07-01' }],
      })),
      http.get('/api/executors/10/token', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: 'exec_test_token',
      })),
    );
    renderPage();

    await screen.findByText('qoder-runner');
    await user.click(screen.getByRole('button', { name: /启动命令/ }));
    await user.click(screen.getByRole('button', { name: '复制启动命令' }));

    expect(execCommand).toHaveBeenCalledWith('copy');
  });

  it('offers only Qoder CLI in the community create modal', async () => {
    const user = userEvent.setup();
    server.use(
      http.get('/api/agents', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [{ id: 1, name: 'Alpha', avatarUrl: null, status: 'ONLINE', onlineVersionId: null, editingVersionId: null, latestVersionNo: 1, version: 1, gmtCreate: '2026-07-01' }],
      })),
      http.get('/api/executors', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [],
      })),
    );
    renderPage();

    await user.click(await screen.findByRole('button', { name: /新建执行器/ }));

    expect(screen.getAllByText('归属 Agent').length).toBeGreaterThanOrEqual(2);
    expect(screen.getAllByText('选择 Agent').length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText('Qoder CLI')).toBeInTheDocument();
    expect(screen.queryByText('Claude Code')).not.toBeInTheDocument();
    expect(screen.queryByText('Codex CLI')).not.toBeInTheDocument();
    expect(screen.queryByText('Cursor CLI')).not.toBeInTheDocument();
    expect(screen.getByText('记忆模式')).toBeInTheDocument();
    expect(screen.getByText('Qoder 模型')).toBeInTheDocument();
    expect(screen.getByText('Reasoning Effort')).toBeInTheDocument();
    expect(screen.getByText('Context Window')).toBeInTheDocument();
  });

  it('keeps create visible but blocks non-admin users before opening the modal', async () => {
    const user = userEvent.setup();
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_WRITE');
    server.use(
      http.get('/api/agents', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: [],
      })),
      http.get('/api/executors', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: [],
      })),
    );
    renderPage();

    const createButton = await screen.findByRole('button', { name: /新建执行器/ });
    expect(createButton).toBeEnabled();
    await user.click(createButton);

    expect(screen.queryByRole('dialog', { name: '新建执行器' })).not.toBeInTheDocument();
    expect(await screen.findByText('当前为读写权限，新建执行器需要管理员权限')).toBeInTheDocument();
  });

  it.each([
    ['QODER_CN_CLI', 'qodercn'],
    ['QODER_CLI', 'qoder'],
    ['CLAUDE_CODE', 'claude'],
    ['CODEX_CLI', 'codex'],
    ['CURSOR_CLI', 'cursor'],
  ])('builds a complete npm command for %s', (clientKind, provider) => {
    expect(buildStartupCommand(
      'exec_test_token',
      10000,
      clientKind,
      'provider-local',
      'https://daily.auto-wonder.example.com/api/mcp',
      '0.2.130',
    )).toBe(
      `npx -y autowonder@0.2.130 connect --ws-url wss://daily.auto-wonder.example.com/ws/executor --token exec_test_token --executor-id 10000 --provider ${provider} --memory-mode provider-local${provider === 'qoder' || provider === 'qodercn' ? ' --token-aware-enable' : ''}`,
    );
  });

  it('offers only Qoder CLI clients when creating an executor', () => {
    expect(CLIENT_KINDS.map((kind) => kind.value)).toEqual(['QODER_CN_CLI', 'QODER_CLI']);
    expect(CLIENT_KINDS.every((kind) => isQoderClientKind(kind.value))).toBe(true);
  });

  it('uses provider model IDs and fixed Qoder runtime choices', () => {
    expect(QODER_MODELS.map((model) => model.value)).toEqual([
      'auto', 'ultimate', 'performance', 'efficient', 'lite',
      'qmodel_38max', 'qfmodel', 'qmodel_latest', 'qmodel',
      'kmodel_latest', 'kmodel', 'gmodel', 'gfmodel',
      'dmodel', 'dfmodel', 'mmodel',
    ]);
    expect(QODER_MODELS).toContainEqual({ value: 'qmodel_38max', label: 'Qwen3.8-Max' });
    expect(QODER_MODELS).toContainEqual({ value: 'auto', label: 'Auto (default)' });
    expect(QODER_MODELS).toContainEqual({ value: 'qfmodel', label: 'Qwen3.8-Flash' });
    expect(QODER_MODELS).toContainEqual({ value: 'gmodel', label: 'GLM-5.3' });
    expect(QODER_MODELS).toContainEqual({ value: 'gfmodel', label: 'GLM-5.3-Flash' });
    expect(QODER_MODELS.some((model) => model.value === 'cmodel' || model.value === 'gm51model')).toBe(false);
    expect(qoderOptionsForModel('ultimate').contextWindows).toEqual([
      { value: '1000000', label: '1M' },
      { value: '400000', label: '400K' },
      { value: '260000', label: '260K' },
    ]);
    expect(qoderOptionsForModel('ultimate').reasoningEfforts.map((option) => option.value)).toEqual([
      'max', 'xhigh', 'high', 'medium', 'low', 'none',
    ]);
    expect(qoderOptionsForModel('qfmodel').defaultReasoningEffort).toBe('medium');
    expect(qoderOptionsForModel('gmodel').defaultContextWindow).toBe('260000');
  });

  it('builds a Qoder command with the selected fixed runtime options', () => {
    expect(buildStartupCommand(
      'exec_test_token',
      10000,
      'QODER_CLI',
      'platform',
      'http://daily.auto-wonder.example.com/api/mcp',
      '0.2.130',
      {
        model: 'ultimate',
        reasoningEffort: 'high',
        contextWindow: '1000000',
      },
    )).toBe(
      'npx -y autowonder@0.2.130 connect --ws-url ws://daily.auto-wonder.example.com/ws/executor --token exec_test_token --executor-id 10000 --provider qoder --memory-mode platform --model ultimate --reasoning-effort high --context-window 1000000 --token-aware-enable',
    );
  });

  it('rejects malformed MCP endpoint when building startup commands', () => {
    expect(() => buildStartupCommand(
      'exec_test_token',
      10000,
      'QODER_CLI',
      'platform',
      'not a url',
      '0.2.130',
    )).toThrow('MCP 地址格式不合法');
  });

  it('wraps the Windows startup command with session-level UTF-8 console settings', () => {
    const cmd = buildStartupCommand(
      'exec_test_token',
      10000,
      'QODER_CLI',
      'platform',
      'http://daily.auto-wonder.example.com/api/mcp',
      '0.2.130',
      undefined,
      'windows',
    );
    expect(cmd.startsWith('powershell -NoProfile -Command "')).toBe(true);
    expect(cmd).toContain('[Console]::OutputEncoding = [System.Text.Encoding]::UTF8');
    expect(cmd).toContain('$OutputEncoding = [System.Text.Encoding]::UTF8');
    expect(cmd).toContain('npx -y autowonder@0.2.130 connect --ws-url ws://daily.auto-wonder.example.com/ws/executor --token exec_test_token --executor-id 10000 --provider qoder --memory-mode platform');
    expect(cmd.endsWith('"')).toBe(true);
  });

  it('keeps the posix startup command unwrapped when the os is explicit', () => {
    expect(buildStartupCommand(
      'exec_test_token',
      10000,
      'CLAUDE_CODE',
      'platform',
      'http://daily.auto-wonder.example.com/api/mcp',
      '0.2.130',
      undefined,
      'posix',
    )).toBe(
      'npx -y autowonder@0.2.130 connect --ws-url ws://daily.auto-wonder.example.com/ws/executor --token exec_test_token --executor-id 10000 --provider claude --memory-mode platform',
    );
  });

  it('detects Windows from the browser platform and defaults to posix elsewhere', () => {
    const originalPlatform = Object.getOwnPropertyDescriptor(navigator, 'platform');
    try {
      Object.defineProperty(navigator, 'platform', { configurable: true, value: 'Win32' });
      expect(detectStartupOs()).toBe('windows');
      Object.defineProperty(navigator, 'platform', { configurable: true, value: 'MacIntel' });
      expect(detectStartupOs()).toBe('posix');
    } finally {
      if (originalPlatform) {
        Object.defineProperty(navigator, 'platform', originalPlatform);
      } else {
        Reflect.deleteProperty(navigator, 'platform');
      }
    }
  });

  it('builds a Qoder CLI CN command with the selected fixed runtime options', () => {
    expect(buildStartupCommand(
      'exec_test_token',
      10000,
      'QODER_CN_CLI',
      'platform',
      'http://daily.auto-wonder.example.com/api/mcp',
      '0.2.130',
      {
        model: 'ultimate',
        reasoningEffort: 'high',
        contextWindow: '1000000',
      },
    )).toBe(
      'npx -y autowonder@0.2.130 connect --ws-url ws://daily.auto-wonder.example.com/ws/executor --token exec_test_token --executor-id 10000 --provider qodercn --memory-mode platform --model ultimate --reasoning-effort high --context-window 1000000 --token-aware-enable',
    );
  });

  it('treats both Qoder client kinds as Qoder without normalizing them', () => {
    expect(isQoderClientKind('QODER_CLI')).toBe(true);
    expect(isQoderClientKind('QODER_CN_CLI')).toBe(true);
    expect(isQoderClientKind('CLAUDE_CODE')).toBe(false);
    expect(isQoderClientKind('CODEX_CLI')).toBe(false);
    expect(isQoderClientKind('CURSOR_CLI')).toBe(false);
    expect(isQoderClientKind(undefined)).toBe(false);
  });

  it('creates a Qoder CLI CN executor with QODER_CN_CLI and a qodercn startup command', async () => {
    const user = userEvent.setup();
    let createBody: Record<string, unknown> | null = null;
    server.use(
      http.get('/api/agents', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [{ id: 1, name: 'Alpha', avatarUrl: null, status: 'ONLINE', onlineVersionId: null, editingVersionId: null, latestVersionNo: 1, version: 1, gmtCreate: '2026-07-01' }],
      })),
      http.get('/api/executors', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [],
      })),
      http.get('/api/agents/1/executors', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [{ id: 20, agentId: 1, agentName: 'Alpha', name: 'cn-runner', status: 'OFFLINE', clientKind: 'QODER_CN_CLI', lastHeartbeat: null, gmtCreate: '2026-07-01' }],
      })),
      http.post('/api/agents/1/executors', async ({ request }) => {
        createBody = await request.json() as Record<string, unknown>;
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: { id: 20, name: 'cn-runner', token: 'exec_cn_token' },
        });
      }),
    );
    renderPage();

    // Select the owning agent from the toolbar so the create form defaults to it.
    await user.click(await screen.findByRole('combobox'));
    await user.click(await screen.findByText('Alpha'));

    await user.click(screen.getByRole('button', { name: /新建执行器/ }));
    const createDialog = screen.getByRole('dialog', { name: '新建执行器' });
    expect(within(createDialog).getByText('Qoder CLI CN')).toBeInTheDocument();

    // Selecting Qoder CLI CN keeps the shared Qoder option fields visible.
    await user.click(within(createDialog).getByText('Qoder CLI CN'));
    expect(within(createDialog).getByText('Qoder 模型')).toBeInTheDocument();
    expect(within(createDialog).getByText('Reasoning Effort')).toBeInTheDocument();
    expect(within(createDialog).getByText('Context Window')).toBeInTheDocument();

    await user.type(screen.getByPlaceholderText('如: dev-machine-01'), 'cn-runner');
    await user.click(screen.getByRole('dialog', { name: '新建执行器' }).querySelector('.ant-modal-footer button.ant-btn-primary')!);

    await screen.findByText('执行器创建成功');
    expect(createBody).toMatchObject({ name: 'cn-runner', clientKind: 'QODER_CN_CLI' });
    expect(screen.getByText(/--provider qodercn/)).toBeInTheDocument();
  });

  it('returns null from readQoderStartupPreference when no preference is stored', () => {
    expect(readQoderStartupPreference(99)).toBeNull();
  });

  it('returns null from readQoderStartupPreference when stored model is invalid', () => {
    localStorage.setItem(
      `${PREFS_KEY_PREFIX}.99`,
      JSON.stringify({ memoryMode: 'platform', model: 'nonexistent_model', reasoningEffort: 'medium', contextWindow: '260000' }),
    );
    expect(readQoderStartupPreference(99)).toBeNull();
  });

  it('round-trips a valid preference through write and read', () => {
    const pref = { memoryMode: 'platform', model: 'qmodel_38max', reasoningEffort: 'medium', contextWindow: '260000' };
    writeQoderStartupPreference(42, pref);
    expect(readQoderStartupPreference(42)).toEqual(pref);
  });

  it('isolates preferences between different executor IDs', () => {
    writeQoderStartupPreference(1, { memoryMode: 'platform', model: 'qmodel_38max', reasoningEffort: 'medium', contextWindow: '260000' });
    expect(readQoderStartupPreference(2)).toBeNull();
    expect(readQoderStartupPreference(1)?.model).toBe('qmodel_38max');
  });

  it('restores saved model when reopening the Qoder startup dialog', async () => {
    const user = userEvent.setup();
    writeQoderStartupPreference(10, {
      memoryMode: 'platform',
      model: 'qmodel_38max',
      reasoningEffort: 'medium',
      contextWindow: '260000',
    });
    server.use(
      http.get('/api/agents', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [{ id: 1, name: 'Alpha', avatarUrl: null, status: 'ONLINE', onlineVersionId: null, editingVersionId: null, latestVersionNo: 1, version: 1, gmtCreate: '2026-07-01' }],
      })),
      http.get('/api/executors', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [{ id: 10, agentId: 1, agentName: 'Alpha', name: 'qoder-runner', status: 'OFFLINE', clientKind: 'QODER_CLI', lastHeartbeat: null, gmtCreate: '2026-07-01' }],
      })),
      http.get('/api/executors/10/token', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: 'exec_test_token',
      })),
    );
    renderPage();

    await screen.findByText('qoder-runner');
    await user.click(screen.getByRole('button', { name: /启动命令/ }));

    const dialog = screen.getByRole('dialog', { name: '启动命令 · qoder-runner' });
    expect(dialog).toBeInTheDocument();
    const modelItems = await screen.findAllByText('Qwen3.8-Max');
    expect(modelItems.length).toBeGreaterThanOrEqual(1);
  });

  function mockExecutor(overrides: Record<string, unknown> = {}) {
    return {
      id: 10000, agentId: 1, name: 'dev-machine-01', agentName: 'A', clientKind: 'CLAUDE_CODE',
      status: 'OFFLINE', lastConnectIp: null, lastHeartbeat: null,
      gmtCreate: '2026-08-24T10:00:00Z', ...overrides,
    };
  }

  function serveExecutor(overrides: Record<string, unknown> = {}) {
    server.use(
      http.get('/api/agents', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: [],
      })),
      http.get('/api/executors', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: [mockExecutor(overrides)],
      })),
      http.get('/api/executors/10000/token', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: 'exec_test_token',
      })),
    );
  }

  it('opens a startup command modal with a preview for non-Qoder executors', async () => {
    const user = userEvent.setup();
    serveExecutor({ clientKind: 'CLAUDE_CODE' });
    renderPage();

    await user.click(await screen.findByRole('button', { name: /启动命令/ }));

    expect(await screen.findByText(/启动命令 · dev-machine-01/)).toBeInTheDocument();
    expect(await screen.findByText(/--provider claude/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '复制启动命令' })).toBeInTheDocument();
  });

  it('opens the same modal with Qoder fields for Qoder executors', async () => {
    const user = userEvent.setup();
    serveExecutor({ clientKind: 'QODER_CLI' });
    renderPage();

    await user.click(await screen.findByRole('button', { name: /启动命令/ }));

    expect(await screen.findByText(/启动命令 · dev-machine-01/)).toBeInTheDocument();
    expect(screen.getByText('Qoder 模型')).toBeInTheDocument();
    expect(screen.getByText('Context Window')).toBeInTheDocument();
  });

  it('hides Qoder fields in the modal for non-Qoder executors', async () => {
    const user = userEvent.setup();
    serveExecutor({ clientKind: 'CODEX_CLI' });
    renderPage();

    await user.click(await screen.findByRole('button', { name: /启动命令/ }));

    expect(await screen.findByText(/启动命令 · dev-machine-01/)).toBeInTheDocument();
    expect(screen.queryByText('Qoder 模型')).not.toBeInTheDocument();
  });

  function mockClipboardWrite() {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText },
    });
    return writeText;
  }

  it('copies a bash debug command from the startup modal and warns about disk usage', async () => {
    const user = userEvent.setup();
    const writeText = mockClipboardWrite();
    serveExecutor({ clientKind: 'CLAUDE_CODE' });
    renderPage();

    await user.click(await screen.findByRole('button', { name: /启动命令/ }));
    await user.click(await screen.findByRole('button', { name: /复制 debug 模式命令/ }));
    await user.click(await screen.findByText('Mac / Linux (bash)'));

    expect(writeText).toHaveBeenCalledTimes(1);
    const cmd = writeText.mock.calls[0][0];
    expect(cmd).toContain('--debug 2>&1 | tee ~/aw-claude-10000-');
    expect(cmd).toMatch(/\.log$/);
    expect(await screen.findByText(/避免日志写满磁盘/)).toBeInTheDocument();
  });

  it('copies a PowerShell debug command from the startup modal', async () => {
    const user = userEvent.setup();
    const writeText = mockClipboardWrite();
    serveExecutor({ clientKind: 'CLAUDE_CODE' });
    renderPage();

    await user.click(await screen.findByRole('button', { name: /启动命令/ }));
    await user.click(await screen.findByRole('button', { name: /复制 debug 模式命令/ }));
    await user.click(await screen.findByText('Windows (PowerShell 7+)'));

    expect(writeText).toHaveBeenCalledTimes(1);
    const cmd = writeText.mock.calls[0][0];
    expect(cmd).toContain('--debug 2>&1 | Tee-Object -FilePath "$HOME/aw-claude-10000-');
    expect(cmd).toMatch(/\.log"$/);
  });

  it('copies a plain startup command without the debug suffix', async () => {
    const user = userEvent.setup();
    const writeText = mockClipboardWrite();
    serveExecutor({ clientKind: 'CLAUDE_CODE' });
    renderPage();

    await user.click(await screen.findByRole('button', { name: /启动命令/ }));
    await user.click(await screen.findByRole('button', { name: '复制启动命令' }));

    expect(writeText).toHaveBeenCalledTimes(1);
    const cmd = writeText.mock.calls[0][0];
    expect(cmd).not.toContain('--debug');
    expect(cmd).toContain('--provider claude');
    expect(await screen.findByText('启动命令已复制')).toBeInTheDocument();
  });
});
