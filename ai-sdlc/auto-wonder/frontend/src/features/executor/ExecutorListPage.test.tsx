import { afterEach, beforeEach, describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { buildStartupCommand, ExecutorListPage } from './ExecutorListPage';
import { QODER_MODELS, qoderOptionsForModel } from './qoderOptions';
import { useAuthStore } from '@/shared/auth/store';

const originalClipboard = Object.getOwnPropertyDescriptor(navigator, 'clipboard');
const originalExecCommand = Object.getOwnPropertyDescriptor(document, 'execCommand');

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
    useAuthStore.getState().setCurrentOrg({ id: 1, name: 'O', description: '' }, 'ADMIN');
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
    );
    renderPage();

    await screen.findByText('qoder-runner');
    await user.click(screen.getByRole('button', { name: /启动命令/ }));

    expect(screen.getByRole('dialog', { name: 'Qoder 启动配置' })).toBeInTheDocument();
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
    await user.click(screen.getByRole('button', { name: /OK|确/ }));

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
    useAuthStore.getState().setCurrentOrg({ id: 1, name: 'O', description: '' }, 'READ_WRITE');
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

  it('builds a complete npm command for Qoder CLI', () => {
    expect(buildStartupCommand(
      'exec_test_token',
      10000,
      'QODER_CLI',
      'provider-local',
      'https://daily.auto-wonder.example.com/api/mcp',
      '0.2.114',
    )).toBe(
      'npx -y autowonder@0.2.114 connect --ws-url wss://daily.auto-wonder.example.com/ws/executor --token exec_test_token --executor-id 10000 --provider qoder --memory-mode provider-local',
    );
  });

  it('rejects unsupported executor clients in the community frontend', () => {
    expect(() => buildStartupCommand(
      'exec_test_token',
      10000,
      'CLAUDE_CODE',
      'provider-local',
      'https://daily.auto-wonder.example.com/api/mcp',
      '0.2.114',
    )).toThrow('社区版仅支持 Qoder CLI');
  });

  it('uses provider model IDs and fixed Qoder runtime choices', () => {
    expect(QODER_MODELS.map((model) => model.value)).toEqual([
      'auto', 'ultimate', 'performance', 'efficient', 'lite', 'cmodel',
      'qmodel_38max', 'qmodel_latest', 'qmodel', 'kmodel_latest', 'kmodel',
      'gm51model', 'dmodel', 'dfmodel', 'mmodel',
    ]);
    expect(QODER_MODELS).toContainEqual({ value: 'qmodel_38max', label: 'Qwen3.8-Max' });
    expect(qoderOptionsForModel('ultimate').contextWindows).toEqual([
      { value: '1000000', label: '1M' },
      { value: '400000', label: '400K' },
      { value: '260000', label: '260K' },
    ]);
    expect(qoderOptionsForModel('ultimate').reasoningEfforts.map((option) => option.value)).toEqual([
      'max', 'xhigh', 'high', 'medium', 'low', 'none',
    ]);
  });

  it('builds a Qoder command with the selected fixed runtime options', () => {
    expect(buildStartupCommand(
      'exec_test_token',
      10000,
      'QODER_CLI',
      'platform',
      'http://daily.auto-wonder.example.com/api/mcp',
      '0.2.114',
      {
        model: 'ultimate',
        reasoningEffort: 'high',
        contextWindow: '1000000',
      },
    )).toBe(
      'npx -y autowonder@0.2.114 connect --ws-url ws://daily.auto-wonder.example.com/ws/executor --token exec_test_token --executor-id 10000 --provider qoder --memory-mode platform --model ultimate --reasoning-effort high --context-window 1000000',
    );
  });

  it('rejects malformed MCP endpoint when building startup commands', () => {
    expect(() => buildStartupCommand(
      'exec_test_token',
      10000,
      'QODER_CLI',
      'platform',
      'not a url',
      '0.2.114',
    )).toThrow('MCP 地址格式不合法');
  });
});
