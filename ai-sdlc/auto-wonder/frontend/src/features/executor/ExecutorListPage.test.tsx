import { afterEach, beforeEach, describe, it, expect, vi } from 'vitest';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { message } from 'antd';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import {
  resetExecutorLaunchConfigStore, setExecutorLaunchConfig, setExecutorLaunchFixture, resolveLaunchCommand,
} from '@/test/mocks/handlers';
import type { SeededLaunchConfig } from '@/test/mocks/handlers';
import {
  ExecutorListPage, isQoderClientKind, CREATABLE_CLIENT_KINDS, isLaunchConfigured, launchErrorMessage,
  isUpdateInProgress, updateStatusLabel, updateStatusColor, upgradeBlockReason, describeUpdateAllResult,
} from './ExecutorListPage';
import type { ExecutorUpdateAllResultVO, ExecutorUpdateVO, ExecutorVO } from './api';
import { QODER_MODELS, qoderOptionsForModel } from './qoderOptions';
import { ApiError } from '@/shared/types/common';
import { useAuthStore } from '@/shared/auth/store';

const originalClipboard = Object.getOwnPropertyDescriptor(navigator, 'clipboard');
const originalExecCommand = Object.getOwnPropertyDescriptor(document, 'execCommand');

function decodePowerShellCommand(command: string): string {
  const encoded = command.replace(/^powershell -NoProfile -EncodedCommand /, '');
  const binary = atob(encoded);
  let decoded = '';
  for (let index = 0; index < binary.length; index += 2) {
    decoded += String.fromCharCode(binary.charCodeAt(index) | (binary.charCodeAt(index + 1) << 8));
  }
  return decoded;
}

// 启动配置只存数据库：浏览器仅允许鉴权态、msw cookie 和列表视图偏好。
const AUTH_STORAGE_KEY = 'aw-auth';
const INFRA_STORAGE_KEYS = new Set([AUTH_STORAGE_KEY, 'MSW_COOKIE_STORE', 'autowonder.executors.view']);

function unexpectedLocalStorageKeys(): string[] {
  const keys: string[] = [];
  for (let index = 0; index < localStorage.length; index += 1) {
    const key = localStorage.key(index);
    if (key && !INFRA_STORAGE_KEYS.has(key)) keys.push(key);
  }
  return keys;
}

function renderPage(queryRetry: boolean | number = false) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: queryRetry, retryDelay: 0 } } });
  return {
    queryClient,
    ...render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter><ExecutorListPage /></MemoryRouter>
      </QueryClientProvider>,
    ),
  };
}

describe('ExecutorListPage', () => {
  beforeEach(() => {
    localStorage.setItem('aw-auth', JSON.stringify({
      state: {
        accessToken: null,
        refreshToken: null,
        user: null,
        currentWorkspace: { id: 1, name: 'O', description: '' },
        accessLevel: 'ADMIN',
      },
      version: 2,
    }));
    useAuthStore.getState().clear();
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'ADMIN');
    // 页面默认视图已改为按小队分组；本文件既有分组用例均基于按 Agent 分组视图编写，
    // 这里按用例维度固化旧视图，持久化相关的新用例在各自测试体内覆盖该值。
    localStorage.setItem('autowonder.executors.view', 'agent');
    resetExecutorLaunchConfigStore();
    vi.restoreAllMocks();
  });

  afterEach(() => {
    // antd message 渲染在 document 级 .ant-message 容器，RTL 的自动 cleanup 不会卸载它，
    // 否则「启动配置已保存」toast 会残留到后续用例，导致 findByText 命中多个元素
    message.destroy();
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

  it('shows every executor under its owning agent group once the group is expanded', async () => {
    const user = userEvent.setup();
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

    await expandAgentGroup(user, 'Alpha（未编队）');

    expect(screen.getByText('runner-01')).toBeInTheDocument();
    expect(screen.getByText('Alpha')).toBeInTheDocument();
    expect(screen.queryByText(/请在右上角选择一个 Agent/)).not.toBeInTheDocument();
  });

  it('moves connection metadata into details without widening the list', async () => {
    const user = userEvent.setup();
    server.use(
      http.get('/api/agents', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [{ id: 1, name: 'Alpha', avatarUrl: null, status: 'ONLINE', onlineVersionId: null, editingVersionId: null, latestVersionNo: 1, version: 1, gmtCreate: '2026-07-01' }],
      })),
      http.get('/api/executors', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [
          { id: 10, agentId: 1, agentName: 'Alpha', name: 'runner-ip', status: 'ONLINE', clientKind: 'QODER_CLI', lastConnectIp: '203.0.113.50', lastHeartbeat: null, gmtCreate: '2026-07-01' },
          { id: 11, agentId: 1, agentName: 'Alpha', name: 'runner-noip', status: 'OFFLINE', clientKind: 'CLAUDE_CODE', lastConnectIp: null, lastHeartbeat: null, gmtCreate: '2026-07-01' },
        ],
      })),
    );
    renderPage();

    await expandAgentGroup(user, 'Alpha（未编队）');

    expect(screen.queryByText('203.0.113.50')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'runner-ip' }));
    expect(await screen.findByText('203.0.113.50')).toBeInTheDocument();
    expect(screen.getAllByText('接入 IP').length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText('未上报').length).toBeGreaterThanOrEqual(1);
  });

  it('renders version column with reported version or dash fallback', async () => {
    const user = userEvent.setup();
    server.use(
      http.get('/api/agents', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [{ id: 1, name: 'Alpha', avatarUrl: null, status: 'ONLINE', onlineVersionId: null, editingVersionId: null, latestVersionNo: 1, version: 1, gmtCreate: '2026-07-01' }],
      })),
      http.get('/api/executors', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [
          { id: 10, agentId: 1, agentName: 'Alpha', name: 'runner-ver', status: 'ONLINE', clientKind: 'QODER_CLI', version: '0.2.152', lastConnectIp: null, lastHeartbeat: null, gmtCreate: '2026-07-01' },
          { id: 11, agentId: 1, agentName: 'Alpha', name: 'runner-legacy', status: 'OFFLINE', clientKind: 'CLAUDE_CODE', version: null, lastConnectIp: null, lastHeartbeat: null, gmtCreate: '2026-07-01' },
        ],
      })),
    );
    renderPage();

    await expandAgentGroup(user, 'Alpha（未编队）');

    expect(screen.getAllByText('客户端 / 版本').length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText('0.2.152')).toBeInTheDocument();
    expect(screen.getByText('版本未上报')).toBeInTheDocument();
  });

  it('renders current model with display name, id fallback, or not-reported fallback', async () => {
    const user = userEvent.setup();
    server.use(
      http.get('/api/agents', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [{ id: 1, name: 'Alpha', avatarUrl: null, status: 'ONLINE', onlineVersionId: null, editingVersionId: null, latestVersionNo: 1, version: 1, gmtCreate: '2026-07-01' }],
      })),
      http.get('/api/executors', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [
          { id: 10, agentId: 1, agentName: 'Alpha', name: 'runner-named', status: 'ONLINE', clientKind: 'QODER_CLI', version: '0.2.152', model: 'qoder3-coder-plus', modelName: 'Qoder3 Coder Plus', lastConnectIp: null, lastHeartbeat: null, gmtCreate: '2026-07-01' },
          { id: 11, agentId: 1, agentName: 'Alpha', name: 'runner-id-only', status: 'ONLINE', clientKind: 'QODER_CLI', version: '0.2.152', model: 'qoder3-next', modelName: null, lastConnectIp: null, lastHeartbeat: null, gmtCreate: '2026-07-01' },
          { id: 12, agentId: 1, agentName: 'Alpha', name: 'runner-legacy', status: 'OFFLINE', clientKind: 'CLAUDE_CODE', version: null, model: null, modelName: null, lastConnectIp: null, lastHeartbeat: null, gmtCreate: '2026-07-01' },
        ],
      })),
    );
    renderPage();

    await expandAgentGroup(user, 'Alpha（未编队）');

    expect(screen.getByText('Qoder3 Coder Plus')).toBeInTheDocument();
    expect(screen.getByText('qoder3-next')).toBeInTheDocument();
    expect(screen.getByText('模型未上报')).toBeInTheDocument();
  });

  it('shows the current model in the detail drawer', async () => {
    const user = userEvent.setup();
    server.use(
      http.get('/api/agents', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [{ id: 1, name: 'Alpha', avatarUrl: null, status: 'ONLINE', onlineVersionId: null, editingVersionId: null, latestVersionNo: 1, version: 1, gmtCreate: '2026-07-01' }],
      })),
      http.get('/api/executors', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [
          { id: 10, agentId: 1, agentName: 'Alpha', name: 'runner-named', status: 'ONLINE', clientKind: 'QODER_CLI', version: '0.2.152', model: 'qoder3-coder-plus', modelName: 'Qoder3 Coder Plus', lastConnectIp: null, lastHeartbeat: null, gmtCreate: '2026-07-01' },
          { id: 11, agentId: 1, agentName: 'Alpha', name: 'runner-legacy', status: 'OFFLINE', clientKind: 'CLAUDE_CODE', version: null, model: null, modelName: null, lastConnectIp: null, lastHeartbeat: null, gmtCreate: '2026-07-01' },
        ],
      })),
    );
    renderPage();

    await expandAgentGroup(user, 'Alpha（未编队）');
    await user.click(screen.getByRole('button', { name: 'runner-named' }));

    expect(await screen.findByText('当前模型')).toBeInTheDocument();
    expect(screen.getAllByText('Qoder3 Coder Plus').length).toBeGreaterThanOrEqual(2);
  });

  it('opens the startup dialog with the launch config persisted in the database', async () => {
    const user = userEvent.setup();
    serveExecutor({ clientKind: 'QODER_CLI', name: 'qoder-runner', agentName: 'Alpha' });
    renderPage();

    await expandAgentGroup(user, 'Alpha（未编队）');
    await user.click(await screen.findByRole('button', { name: /启动命令/ }));
    const dialog = await findModalByTitle('启动命令 · qoder-runner');

    expect(within(dialog).getByText('Qoder 模型')).toBeInTheDocument();
    expect(within(dialog).getByText('Reasoning Effort')).toBeInTheDocument();
    expect(within(dialog).getByText('Context Window')).toBeInTheDocument();
    // 表单与预览都来自服务端：表单回填数据库里的值，命令由服务端按同一份配置拼装
    await expectContextWindowSelected(dialog, '260K', '260000');
    expect(unexpectedLocalStorageKeys()).toEqual([]);
  });

  it('copies the server-generated command when the Clipboard API is unavailable', async () => {
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
    serveExecutor({ clientKind: 'QODER_CLI', name: 'qoder-runner', agentName: 'Alpha' });
    renderPage();

    await expandAgentGroup(user, 'Alpha（未编队）');
    await user.click(await screen.findByRole('button', { name: /启动命令/ }));
    const dialog = await findModalByTitle('启动命令 · qoder-runner');
    await user.click(within(dialog).getByRole('button', { name: '复制启动命令' }));

    expect(execCommand).toHaveBeenCalledWith('copy');
  });

  it('selects target agent in create modal', async () => {
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

    expect(within(screen.getByRole('dialog', { name: '新建执行器' })).getByText('归属 Agent')).toBeInTheDocument();
    expect(screen.getAllByText('选择 Agent').length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText('Qoder CLI')).toBeInTheDocument();
    expect(screen.getByText('Qoder CLI CN')).toBeInTheDocument();
    expect(screen.queryByText('Claude Code')).not.toBeInTheDocument();
    expect(screen.queryByText('Codex CLI')).not.toBeInTheDocument();
    expect(screen.queryByText('Cursor CLI')).not.toBeInTheDocument();
    expect(screen.getByText('记忆模式')).toBeInTheDocument();
    expect(screen.getByText('Qoder 模型')).toBeInTheDocument();
    expect(screen.getByText('Reasoning Effort')).toBeInTheDocument();
    expect(screen.getByText('Context Window')).toBeInTheDocument();
  });

  function mockAgent(id: number, name: string) {
    return {
      id, name, avatarUrl: null, status: 'ONLINE', onlineVersionId: null,
      editingVersionId: null, latestVersionNo: 1, version: 1, gmtCreate: '2026-07-01',
    };
  }

  // GET /api/squads returns memberAgentIds as null, so membership comes from the detail endpoint.
  function mockSquadApis() {
    const squad = (id: number, name: string, memberAgentIds: number[] | null) => ({
      id, name, description: '', ownerId: 1, version: 0,
      gmtCreate: '2026-07-11T08:45:10.909+00:00',
      memberAgentIds, memberCount: memberAgentIds?.length ?? 0,
    });
    return [
      http.get('/api/squads', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [
          squad(40131, '独立开发者小队', null),
          squad(40132, '开发+评审双人组', null),
          squad(40133, '质量保障小队', null),
        ],
      })),
      http.get('/api/squads/40131', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: squad(40131, '独立开发者小队', [40169]),
      })),
      http.get('/api/squads/40132', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: squad(40132, '开发+评审双人组', [40170, 40171]),
      })),
      http.get('/api/squads/40133', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: squad(40133, '质量保障小队', [40171]),
      })),
    ];
  }

  function mockSameNameAgents() {
    return http.get('/api/agents', () => HttpResponse.json({
      success: true, code: '0', message: '', traceId: null,
      data: [
        mockAgent(40169, '全栈开发'),
        mockAgent(40170, '全栈开发'),
        mockAgent(40171, '代码评审'),
        mockAgent(40172, '测试工程师'),
      ],
    }));
  }

  function mockEmptyExecutors() {
    return http.get('/api/executors', () => HttpResponse.json({
      success: true, code: '0', message: '', traceId: null, data: [],
    }));
  }

  async function openAgentDropdownInCreateModal(user: ReturnType<typeof userEvent.setup>) {
    await user.click(await screen.findByRole('button', { name: /新建执行器/ }));
    const createDialog = await screen.findByRole('dialog', { name: '新建执行器' });
    const agentSelect = within(createDialog).getAllByRole('combobox')[0];
    await user.click(agentSelect);
    return { createDialog, agentSelect };
  }

  it('distinguishes same-named agents by squad suffix in the create modal dropdown', async () => {
    const user = userEvent.setup();
    server.use(mockSameNameAgents(), ...mockSquadApis(), mockEmptyExecutors());
    renderPage();

    await openAgentDropdownInCreateModal(user);

    expect(await screen.findByText('全栈开发（独立开发者小队）')).toBeInTheDocument();
    expect(screen.getByText('全栈开发（开发+评审双人组）')).toBeInTheDocument();
    expect(screen.getByText('代码评审（开发+评审双人组，质量保障小队）')).toBeInTheDocument();
    expect(screen.getByText('测试工程师（未编队）')).toBeInTheDocument();
  });

  it('filters the agent dropdown by squad name', async () => {
    const user = userEvent.setup();
    server.use(mockSameNameAgents(), ...mockSquadApis(), mockEmptyExecutors());
    renderPage();

    const { agentSelect } = await openAgentDropdownInCreateModal(user);
    expect(await screen.findByText('代码评审（开发+评审双人组，质量保障小队）')).toBeInTheDocument();

    await user.type(agentSelect, '质量保障');

    expect(screen.getByText('代码评审（开发+评审双人组，质量保障小队）')).toBeInTheDocument();
    expect(screen.queryByText('全栈开发（独立开发者小队）')).not.toBeInTheDocument();
    expect(screen.queryByText('全栈开发（开发+评审双人组）')).not.toBeInTheDocument();
    expect(screen.queryByText('测试工程师（未编队）')).not.toBeInTheDocument();
  });

  it('still submits the selected agent id after the label gains a squad suffix', async () => {
    const user = userEvent.setup();
    let createdPathname: string | null = null;
    let createBody: Record<string, unknown> | null = null;
    server.use(
      mockSameNameAgents(),
      ...mockSquadApis(),
      mockEmptyExecutors(),
      http.post('/api/agents/40170/executors', async ({ request }) => {
        createdPathname = new URL(request.url).pathname;
        createBody = await request.json() as Record<string, unknown>;
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: { id: 20, agentId: 40170, name: 'runner-01', token: 'exec_squad_token' },
        });
      }),
    );
    renderPage();

    await openAgentDropdownInCreateModal(user);
    await user.click(await screen.findByText('全栈开发（开发+评审双人组）'));

    await user.type(screen.getByPlaceholderText('如: dev-machine-01'), 'runner-01');
    await user.click(screen.getByRole('dialog', { name: '新建执行器' }).querySelector('.ant-modal-footer button.ant-btn-primary')!);

    await screen.findByText('执行器创建成功');
    expect(createdPathname).toBe('/api/agents/40170/executors');
    expect(createBody).toMatchObject({ name: 'runner-01', clientKind: 'QODER_CLI' });
  });

  it('keeps plain agent names when squad membership cannot be loaded', async () => {
    const user = userEvent.setup();
    server.use(
      mockSameNameAgents(),
      mockEmptyExecutors(),
      http.get('/api/squads', () => new HttpResponse(null, { status: 500 })),
    );
    renderPage();

    await openAgentDropdownInCreateModal(user);

    expect(await screen.findAllByText('全栈开发')).toHaveLength(2);
    expect(screen.getByText('代码评审')).toBeInTheDocument();
    expect(screen.queryByText(/未编队/)).not.toBeInTheDocument();
  });

  it('loads separate dynamic catalogs for Qoder and QoderCN in the create modal', async () => {
    const user = userEvent.setup();
    const catalogRequests: string[] = [];
    server.use(
      http.get('/api/executor-model-catalogs/qoder', () => {
        catalogRequests.push('qoder');
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: {
            provider: 'qoder',
            models: [{ id: 'qoder-model-id', name: 'Qoder dynamic model' }],
            lastSuccessfulAt: '2026-09-01T00:00:00Z',
          },
        });
      }),
      http.get('/api/executor-model-catalogs/qodercn', () => {
        catalogRequests.push('qodercn');
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: {
            provider: 'qodercn',
            models: [{ id: 'qodercn-model-id', name: 'QoderCN dynamic model' }],
            lastSuccessfulAt: '2026-09-01T00:00:00Z',
          },
        });
      }),
    );
    renderPage();

    expect(catalogRequests).toEqual([]);
    await user.click(await screen.findByRole('button', { name: /新建执行器/ }));
    const dialog = screen.getByRole('dialog', { name: '新建执行器' });
    expect(await within(dialog).findByText('Qoder dynamic model')).toBeInTheDocument();
    expect(catalogRequests).toEqual(['qoder']);

    await user.click(within(dialog).getByText('Qoder CLI CN'));
    expect(await within(dialog).findByText('QoderCN dynamic model')).toBeInTheDocument();
    expect(catalogRequests).toEqual(['qoder', 'qodercn']);
    expect(within(dialog).queryByText('Qoder dynamic model')).not.toBeInTheDocument();
  });

  it.each([
    ['empty catalog', () => HttpResponse.json({
      success: true, code: '0', message: '', traceId: null,
      data: { provider: 'qoder', models: [], lastSuccessfulAt: null },
    }), 'success'],
    ['catalog 404', () => HttpResponse.json({ success: false, code: '404', message: 'not found', traceId: null }, { status: 404 }), 'error'],
    ['catalog server error', () => HttpResponse.json({ success: false, code: '500', message: 'unavailable', traceId: null }, { status: 500 }), 'error'],
  ])('falls back to static Qoder models after the %s query settles', async (_scenario, response, queryStatus) => {
    const user = userEvent.setup();
    const catalogRequestStarted = vi.fn();
    let resolveCatalogResponse!: (value: HttpResponse) => void;
    const catalogResponse = new Promise<HttpResponse>((resolve) => {
      resolveCatalogResponse = resolve;
    });
    server.use(http.get('/api/executor-model-catalogs/qoder', async () => {
      catalogRequestStarted();
      return catalogResponse;
    }));
    const { queryClient } = renderPage(3);

    await user.click(await screen.findByRole('button', { name: /新建执行器/ }));
    const dialog = screen.getByRole('dialog', { name: '新建执行器' });
    await waitFor(() => expect(catalogRequestStarted).toHaveBeenCalledTimes(1));
    await act(async () => {
      resolveCatalogResponse(response());
      await catalogResponse;
    });
    await waitFor(() => {
      const query = queryClient.getQueryState(['executor-model-catalog', 'qoder']);
      expect(query?.fetchStatus).toBe('idle');
      expect(query?.status).toBe(queryStatus);
      expect(catalogRequestStarted).toHaveBeenCalledTimes(1);
    });

    await user.click(within(dialog).getByRole('combobox', { name: 'Qoder 模型' }));
    expect(await screen.findByText('Qwen3.7-Plus')).toBeInTheDocument();
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

  it('treats both Qoder client kinds as Qoder without normalizing them', () => {
    expect(isQoderClientKind('QODER_CLI')).toBe(true);
    expect(isQoderClientKind('QODER_CN_CLI')).toBe(true);
    expect(isQoderClientKind('CLAUDE_CODE')).toBe(false);
    expect(isQoderClientKind('CODEX_CLI')).toBe(false);
    expect(isQoderClientKind('CURSOR_CLI')).toBe(false);
    expect(isQoderClientKind(undefined)).toBe(false);
  });

  it('restricts creatable client kinds to the two Qoder CLIs', () => {
    expect(CREATABLE_CLIENT_KINDS.map((kind) => kind.value)).toEqual(['QODER_CN_CLI', 'QODER_CLI']);
  });

  it('offers only Qoder kinds in the create modal while the list keeps rendering legacy kinds', async () => {
    const user = userEvent.setup();
    server.use(
      http.get('/api/agents', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [{ id: 1, name: 'Alpha', avatarUrl: null, status: 'ONLINE', onlineVersionId: null, editingVersionId: null, latestVersionNo: 1, version: 1, gmtCreate: '2026-07-01' }],
      })),
      http.get('/api/executors', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [{ id: 11, agentId: 1, agentName: 'Alpha', name: 'legacy-runner', status: 'OFFLINE', clientKind: 'CLAUDE_CODE', lastConnectIp: null, lastHeartbeat: null, gmtCreate: '2026-07-01' }],
      })),
    );
    renderPage();
    await expandAgentGroup(user, 'Alpha（未编队）');

    expect(screen.getByText('legacy-runner')).toBeInTheDocument();
    expect(screen.getByText('Claude Code')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /新建执行器/ }));
    const createDialog = screen.getByRole('dialog', { name: '新建执行器' });
    expect(within(createDialog).getByText('Qoder CLI')).toBeInTheDocument();
    expect(within(createDialog).getByText('Qoder CLI CN')).toBeInTheDocument();
    expect(within(createDialog).queryByText('Claude Code')).not.toBeInTheDocument();
    expect(within(createDialog).queryByText('Codex CLI')).not.toBeInTheDocument();
    expect(within(createDialog).queryByText('Cursor CLI')).not.toBeInTheDocument();
  });

  // 历史数据的 clientKind 可为 null（创建入口已强制必填）：列表必须明确标记「类型缺失」，不留空白
  it('marks legacy executors whose clientKind is missing instead of rendering a blank', async () => {
    const user = userEvent.setup();
    server.use(
      http.get('/api/agents', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [{ id: 1, name: 'Alpha', avatarUrl: null, status: 'ONLINE', onlineVersionId: null, editingVersionId: null, latestVersionNo: 1, version: 1, gmtCreate: '2026-07-01' }],
      })),
      http.get('/api/executors', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [{ id: 12, agentId: 1, agentName: 'Alpha', name: 'legacy-no-kind', status: 'OFFLINE', clientKind: null, lastConnectIp: null, lastHeartbeat: null, gmtCreate: '2026-07-01' }],
      })),
    );
    renderPage();

    await expandAgentGroup(user, 'Alpha（未编队）');

    expect(await screen.findByText('legacy-no-kind')).toBeInTheDocument();
    expect(screen.getByText('类型缺失')).toBeInTheDocument();
  });

  function mockExecutor(overrides: Record<string, unknown> = {}) {
    return {
      id: 10000, agentId: 1, name: 'dev-machine-01', agentName: 'A', clientKind: 'CLAUDE_CODE',
      status: 'OFFLINE', lastConnectIp: null, lastHeartbeat: null,
      gmtCreate: '2026-08-24T10:00:00Z', ...overrides,
    };
  }

  // 数据库里的默认启动配置：Qoder 系四个值齐全，其余客户端只落记忆模式
  function defaultLaunchConfig(clientKind: string): SeededLaunchConfig {
    return isQoderClientKind(clientKind)
      ? {
        memoryMode: 'platform', model: 'auto', reasoningEffort: 'medium',
        contextWindow: '260000', version: 1,
      }
      : {
        memoryMode: 'platform', model: null, reasoningEffort: null,
        contextWindow: null, version: 1,
      };
  }

  // config 传 null 表示数据库里没有配置，用来覆盖「未配置」分支
  function serveExecutor(overrides: Record<string, unknown> = {}, config: SeededLaunchConfig | null = {}) {
    const executor = mockExecutor(overrides);
    const id = executor.id as number;
    const clientKind = executor.clientKind as string;
    setExecutorLaunchFixture(id, { clientKind, token: 'exec_test_token' });
    if (config) {
      setExecutorLaunchConfig(id, { ...defaultLaunchConfig(clientKind), ...config });
    }
    server.use(
      http.get('/api/agents', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: [],
      })),
      http.get('/api/executors', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: [executor],
      })),
      http.get('/api/executors/10000/token', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: 'exec_test_token',
      })),
    );
  }

  async function selectInForm(
    user: ReturnType<typeof userEvent.setup>,
    dialog: HTMLElement,
    label: string,
    optionText: string,
  ) {
    await user.click(formItemSelect(dialog, label));
    await user.click(await screen.findByText(optionText, { selector: '.ant-select-item-option-content' }));
  }

  it('opens a startup command modal with a server-built preview for non-Qoder executors', async () => {
    const user = userEvent.setup();
    serveExecutor({ clientKind: 'CLAUDE_CODE' });
    renderPage();

    await expandAgentGroup(user, 'A（未编队）');
    await user.click(await screen.findByRole('button', { name: /启动命令/ }));

    expect(await screen.findByText(/启动命令 · dev-machine-01/)).toBeInTheDocument();
    expect(await screen.findByText(/--provider claude/)).toBeInTheDocument();
    expect(screen.queryByText(/--model/)).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: '复制启动命令' })).toBeEnabled();
  });

  it('opens the same modal with Qoder fields for Qoder executors', async () => {
    const user = userEvent.setup();
    serveExecutor({ clientKind: 'QODER_CLI' });
    renderPage();

    await expandAgentGroup(user, 'A（未编队）');
    await user.click(await screen.findByRole('button', { name: /启动命令/ }));

    const dialog = await findModalByTitle('启动命令 · dev-machine-01');
    expect(within(dialog).getByText('Qoder 模型')).toBeInTheDocument();
    expect(within(dialog).getByText('Context Window')).toBeInTheDocument();
    await expectContextWindowSelected(dialog, '260K', '260000');
  });

  it('uses the dynamic model ID from the persisted config in the server-built command', async () => {
    const user = userEvent.setup();
    server.use(
      http.get('/api/executor-model-catalogs/qoder', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: {
          provider: 'qoder',
          models: [{ id: 'qoder-model-id', name: 'Qoder dynamic model' }],
          lastSuccessfulAt: '2026-09-01T00:00:00Z',
        },
      })),
    );
    serveExecutor({ clientKind: 'QODER_CLI' }, { model: 'qoder-model-id' });
    renderPage();

    await expandAgentGroup(user, 'A（未编队）');
    await user.click(await screen.findByRole('button', { name: /启动命令/ }));

    expect(await screen.findByText('Qoder dynamic model')).toBeInTheDocument();
    expect(await screen.findByText(/--model qoder-model-id/)).toBeInTheDocument();
    expect(screen.queryByText(/--model Qoder dynamic model/)).not.toBeInTheDocument();
  });

  it('hides Qoder fields in the modal for non-Qoder executors', async () => {
    const user = userEvent.setup();
    serveExecutor({ clientKind: 'CODEX_CLI' });
    renderPage();

    await expandAgentGroup(user, 'A（未编队）');
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

    await expandAgentGroup(user, 'A（未编队）');
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

    await expandAgentGroup(user, 'A（未编队）');
    await user.click(await screen.findByRole('button', { name: /启动命令/ }));
    await user.click(await screen.findByRole('button', { name: /复制 debug 模式命令/ }));
    await user.click(await screen.findByText('Windows (PowerShell 7+)'));

    expect(writeText).toHaveBeenCalledTimes(1);
    const cmd = writeText.mock.calls[0][0];
    expect(cmd).toMatch(/^powershell -NoProfile -EncodedCommand [A-Za-z0-9+/=]+$/);
    const decoded = decodePowerShellCommand(cmd);
    expect(decoded).toContain('--debug 2>&1 | Tee-Object -FilePath "$HOME/aw-claude-10000-');
    expect(decoded).toMatch(/\.log"$/);
  });

  it('copies a plain startup command without the debug suffix', async () => {
    const user = userEvent.setup();
    const writeText = mockClipboardWrite();
    serveExecutor({ clientKind: 'CLAUDE_CODE' });
    renderPage();

    await expandAgentGroup(user, 'A（未编队）');
    await user.click(await screen.findByRole('button', { name: /启动命令/ }));
    await user.click(await screen.findByRole('button', { name: '复制启动命令' }));

    expect(writeText).toHaveBeenCalledTimes(1);
    const cmd = writeText.mock.calls[0][0];
    expect(cmd).not.toContain('--debug');
    expect(cmd).toContain('--provider claude');
    expect(await screen.findByText('启动命令已复制')).toBeInTheDocument();
  });

  const AGENT_ALPHA = {
    id: 1, name: 'Alpha', avatarUrl: null, status: 'ONLINE', onlineVersionId: null,
    editingVersionId: null, latestVersionNo: 1, version: 1, gmtCreate: '2026-07-01',
  };

  // 列表在创建成功前不包含该执行器，复现“创建后从列表首次打开启动命令弹窗”的真实链路
  // seenCreateBodies 用来断言创建请求带上了完整启动参数（后端据此与执行器同事务落库）
  function serveCreateFlow(
    specs: { name: string; clientKind: string }[],
    seenCreateBodies: Record<string, unknown>[] = [],
  ) {
    const idByName = new Map<string, number>(specs.map((spec, index) => [spec.name, 71 + index] as [string, number]));
    const createdNames = new Set<string>();
    server.use(
      http.get('/api/agents', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: [AGENT_ALPHA],
      })),
      http.get('/api/executors', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: specs.filter((spec) => createdNames.has(spec.name)).map((spec) => ({
          id: idByName.get(spec.name) as number, agentId: 1, agentName: null, name: spec.name,
          status: 'OFFLINE', clientKind: spec.clientKind,
          lastConnectIp: null, lastHeartbeat: null, gmtCreate: '2026-09-03T10:00:00Z',
        })),
      })),
      http.post('/api/agents/1/executors', async ({ request }) => {
        const body = await request.json() as {
          name: string; clientKind: string; memoryMode?: string; model?: string;
          reasoningEffort?: string; contextWindow?: string;
        };
        seenCreateBodies.push(body as unknown as Record<string, unknown>);
        createdNames.add(body.name);
        const id = idByName.get(body.name) as number;
        // 后端在创建的同一事务里落库启动配置，这里同步写入 mock 存储供后续读配置/生成命令使用
        setExecutorLaunchFixture(id, { clientKind: body.clientKind, token: `exec_token_${body.name}` });
        setExecutorLaunchConfig(id, {
          memoryMode: body.memoryMode ?? 'platform',
          model: body.model ?? null,
          reasoningEffort: body.reasoningEffort ?? null,
          contextWindow: body.contextWindow ?? null,
          version: 1,
        });
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: {
            id, agentId: 1, name: body.name, token: `exec_token_${body.name}`,
            clientKind: body.clientKind, memoryMode: body.memoryMode ?? 'platform',
            model: body.model ?? null, reasoningEffort: body.reasoningEffort ?? null,
            contextWindow: body.contextWindow ?? null, configVersion: 1,
          },
        });
      }),
      ...specs.map((spec) => http.get(`/api/executors/${idByName.get(spec.name)}/token`, () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: `exec_token_${spec.name}`,
      }))),
    );
    return idByName;
  }

  // rc-util 在 NODE_ENV=test 下把所有弹层 id 固定成 test-id，多个弹窗同时挂载时 aria-labelledby
  // 全部指向 DOM 中第一个标题，按 accessible name 查弹窗会拿到错误节点，只能按标题文本定位。
  async function findModalByTitle(title: string): Promise<HTMLElement> {
    const titleNode = await screen.findByText(title, { selector: '.ant-modal-title' });
    const modal = titleNode.closest('[role="dialog"]');
    expect(modal).not.toBeNull();
    const wrap = (modal as HTMLElement).closest('.ant-modal-wrap') as HTMLElement;
    // 关闭后的弹窗仍留在 DOM 中，必须等到本次真正打开再返回，否则会读到上一次的内容
    await waitFor(() => {
      expect(wrap.style.display).not.toBe('none');
    });
    return modal as HTMLElement;
  }

  // Windows 下预览区渲染的是 base64 编码的 PowerShell 命令，断言前先解码
  function previewedCommand(container: HTMLElement): string {
    const text = container.textContent ?? '';
    const encoded = text.match(/powershell -NoProfile -EncodedCommand [A-Za-z0-9+/=]+/);
    return encoded ? decodePowerShellCommand(encoded[0]) : text;
  }

  // 第二份表单挂载后字段 id 与第一份重复（同为 test-id_xxx），label.control 只指向前者，
  // 后挂载的控件失去 accessible name，只能顺着 label -> .ant-form-item -> .ant-select-selector 定位。
  function formItemSelect(dialog: HTMLElement, label: string): HTMLElement {
    const labelNode = within(dialog).getByText(label, { selector: 'label' });
    const item = labelNode.closest('.ant-form-item') as HTMLElement | null;
    expect(item).not.toBeNull();
    const selector = (item as HTMLElement).querySelector('.ant-select-selector') as HTMLElement | null;
    expect(selector).not.toBeNull();
    return selector as HTMLElement;
  }

  function selectedValue(selector: HTMLElement): string | null {
    return selector.querySelector('.ant-select-selection-item')?.textContent ?? null;
  }

  // 创建链路要跑完「表单交互 -> 创建请求 -> 两个弹窗断言」，并行跑全量或开启覆盖率插桩时
  // jsdom 明显变慢，vitest 默认的 5s 会误判超时
  const CREATE_FLOW_TIMEOUT = 20000;

  async function submitCreateForm(
    user: ReturnType<typeof userEvent.setup>,
    options: { name: string; contextWindowLabel: string; clientKindLabel?: string },
  ) {
    await user.click(await screen.findByRole('button', { name: /新建执行器/ }));
    const dialog = await findModalByTitle('新建执行器');
    if (options.clientKindLabel) {
      // ClientKindSelect 是自定义的卡片式单选，不是 antd Select
      await user.click(within(dialog).getByText(options.clientKindLabel));
    }
    await user.click(formItemSelect(dialog, '归属 Agent'));
    // 归属 Agent 选项带小队后缀（默认 /api/squads 返回空数组即「未编队」）；创建成功后分组面板头
    // 会出现同样文本，故必须限定到下拉选项节点，否则连续创建第二个执行器时会多元素匹配。
    await user.click(await screen.findByText('Alpha（未编队）', { selector: '.ant-select-item-option-content' }));
    await user.click(formItemSelect(dialog, 'Context Window'));
    await user.click(await screen.findByText(options.contextWindowLabel));
    await user.type(within(dialog).getByRole('textbox', { name: '执行器名称' }), options.name);
    await user.click(within(dialog).getByRole('button', { name: 'OK' }));
    return findModalByTitle('执行器创建成功');
  }

  // 缺陷复现的关键步骤：关闭创建成功弹窗且不点击任何复制按钮
  async function closeIssuedDialogWithoutCopying(
    user: ReturnType<typeof userEvent.setup>,
    issuedDialog: HTMLElement,
  ) {
    await user.click(within(issuedDialog).getByRole('button', { name: 'OK' }));
    const wrap = issuedDialog.closest('.ant-modal-wrap') as HTMLElement;
    await waitFor(() => {
      expect(wrap.style.display).toBe('none');
    });
  }

  async function expectContextWindowSelected(dialog: HTMLElement, label: string, value: string) {
    const selector = formItemSelect(dialog, 'Context Window');
    await waitFor(() => {
      expect(selectedValue(selector)).toBe(label);
    });
    expect(previewedCommand(dialog)).toContain(`--context-window ${value}`);
  }

  it.each([
    ['1M', '1000000'],
    ['400K', '400000'],
  ])('keeps the %s Context Window chosen at creation when the startup dialog is first opened from the list', async (label, value) => {
    const user = userEvent.setup();
    const createBodies: Record<string, unknown>[] = [];
    serveCreateFlow([{ name: 'dev-machine-01', clientKind: 'QODER_CLI' }], createBodies);
    renderPage();

    const issuedDialog = await submitCreateForm(user, { name: 'dev-machine-01', contextWindowLabel: label });
    expect(previewedCommand(issuedDialog)).toContain(`--context-window ${value}`);
    await closeIssuedDialogWithoutCopying(user, issuedDialog);

    // 创建即落库：启动参数随创建请求发给服务端，浏览器存储里不留任何执行器偏好
    expect(createBodies).toHaveLength(1);
    expect(createBodies[0]).toMatchObject({
      name: 'dev-machine-01', clientKind: 'QODER_CLI', memoryMode: 'platform',
      model: 'auto', reasoningEffort: 'medium', contextWindow: value,
    });
    expect(unexpectedLocalStorageKeys()).toEqual([]);

    // 新建的执行器落在默认折叠的 Alpha 分组里，先展开才能点到行内按钮
    await expandAgentGroup(user, 'Alpha（未编队）');
    await user.click(await screen.findByRole('button', { name: /启动命令/ }));
    const startupDialog = await findModalByTitle('启动命令 · dev-machine-01');
    await expectContextWindowSelected(startupDialog, label, value);
  }, CREATE_FLOW_TIMEOUT);

  it('restores the creation-time Context Window after the page reloads', async () => {
    const user = userEvent.setup();
    serveCreateFlow([{ name: 'dev-machine-01', clientKind: 'QODER_CLI' }]);
    const firstRender = renderPage();

    const issuedDialog = await submitCreateForm(user, { name: 'dev-machine-01', contextWindowLabel: '1M' });
    await closeIssuedDialogWithoutCopying(user, issuedDialog);
    firstRender.unmount();

    renderPage();
    // 重新挂载后分组回到默认折叠态，同样要先展开
    await expandAgentGroup(user, 'Alpha（未编队）');
    await user.click(await screen.findByRole('button', { name: /启动命令/ }));
    const startupDialog = await findModalByTitle('启动命令 · dev-machine-01');
    await expectContextWindowSelected(startupDialog, '1M', '1000000');
  }, CREATE_FLOW_TIMEOUT);

  it('does not let a second created executor overwrite the first executor Context Window', async () => {
    const user = userEvent.setup();
    const createBodies: Record<string, unknown>[] = [];
    serveCreateFlow([
      { name: 'runner-a', clientKind: 'QODER_CLI' },
      { name: 'runner-b', clientKind: 'QODER_CLI' },
    ], createBodies);
    renderPage();

    const firstIssued = await submitCreateForm(user, { name: 'runner-a', contextWindowLabel: '1M' });
    await closeIssuedDialogWithoutCopying(user, firstIssued);
    const secondIssued = await submitCreateForm(user, { name: 'runner-b', contextWindowLabel: '400K' });
    expect(within(secondIssued).getByText('runner-b')).toBeInTheDocument();
    expect(previewedCommand(secondIssued)).toContain('--context-window 400000');
    await closeIssuedDialogWithoutCopying(user, secondIssued);

    // 两个执行器各自落库，互不覆盖；配置只存在于服务端
    expect(createBodies.map((body) => body.contextWindow)).toEqual(['1000000', '400000']);
    expect(unexpectedLocalStorageKeys()).toEqual([]);

    // 两个执行器都在默认折叠的 Alpha 分组里，先展开才能按行定位
    await expandAgentGroup(user, 'Alpha（未编队）');
    const rowA = screen.getByText('runner-a').closest('tr') as HTMLElement;
    await user.click(within(rowA).getByRole('button', { name: /启动命令/ }));
    const startupDialog = await findModalByTitle('启动命令 · runner-a');
    await expectContextWindowSelected(startupDialog, '1M', '1000000');
  }, CREATE_FLOW_TIMEOUT);

  it('persists the Context Window chosen at creation for Qoder CLI CN', async () => {
    const user = userEvent.setup();
    const createBodies: Record<string, unknown>[] = [];
    serveCreateFlow([{ name: 'cn-runner', clientKind: 'QODER_CN_CLI' }], createBodies);
    renderPage();

    const issuedDialog = await submitCreateForm(user, {
      name: 'cn-runner', contextWindowLabel: '1M', clientKindLabel: 'Qoder CLI CN',
    });
    expect(previewedCommand(issuedDialog)).toContain('--context-window 1000000');
    expect(previewedCommand(issuedDialog)).toContain('--provider qodercn');
    await closeIssuedDialogWithoutCopying(user, issuedDialog);

    expect(createBodies).toHaveLength(1);
    expect(createBodies[0]).toMatchObject({
      name: 'cn-runner', clientKind: 'QODER_CN_CLI', memoryMode: 'platform',
      model: 'auto', reasoningEffort: 'medium', contextWindow: '1000000',
    });
    expect(unexpectedLocalStorageKeys()).toEqual([]);

    // 新建的执行器落在默认折叠的 Alpha 分组里，先展开才能点到行内按钮
    await expandAgentGroup(user, 'Alpha（未编队）');
    await user.click(await screen.findByRole('button', { name: /启动命令/ }));
    const startupDialog = await findModalByTitle('启动命令 · cn-runner');
    await expectContextWindowSelected(startupDialog, '1M', '1000000');
  }, CREATE_FLOW_TIMEOUT);

  it('never writes an executor startup preference into browser storage', async () => {
    const user = userEvent.setup();
    const writeText = mockClipboardWrite();
    serveExecutor({ clientKind: 'CLAUDE_CODE' });
    renderPage();

    await expandAgentGroup(user, 'A（未编队）');
    await user.click(await screen.findByRole('button', { name: /启动命令/ }));
    const startupDialog = await findModalByTitle('启动命令 · dev-machine-01');
    await user.click(within(startupDialog).getByRole('button', { name: '复制启动命令' }));

    await waitFor(() => expect(writeText).toHaveBeenCalledTimes(1));
    // 除鉴权态外，浏览器存储里不应出现任何执行器启动偏好
    expect(unexpectedLocalStorageKeys()).toEqual([]);
    expect(localStorage.getItem(AUTH_STORAGE_KEY)).not.toBeNull();
    const cmd = writeText.mock.calls[0][0];
    expect(cmd).not.toContain('--context-window');
    expect(cmd).not.toContain('--model');
  });

  it('persists the copied Qoder startup config to the server and reloads it on reopen', async () => {
    const user = userEvent.setup();
    const writeText = mockClipboardWrite();
    serveExecutor({ clientKind: 'QODER_CLI' });
    renderPage();

    await expandAgentGroup(user, 'A（未编队）');
    await user.click(await screen.findByRole('button', { name: /启动命令/ }));
    const dialog = await findModalByTitle('启动命令 · dev-machine-01');
    await user.click(formItemSelect(dialog, 'Context Window'));
    await user.click(await screen.findByText('1M', { selector: '.ant-select-item-option-content' }));
    await user.click(within(dialog).getByRole('button', { name: '复制启动命令' }));

    await waitFor(() => expect(writeText).toHaveBeenCalledTimes(1));
    expect(writeText.mock.calls[0][0]).toContain('--context-window 1000000');
    // 复制只写服务端，浏览器存储里不留任何执行器偏好
    expect(unexpectedLocalStorageKeys()).toEqual([]);

    const wrap = dialog.closest('.ant-modal-wrap') as HTMLElement;
    await waitFor(() => expect(wrap.style.display).toBe('none'));
    // 重新打开：本地从未写入偏好，1M 只能来自服务端持久化的配置
    const row = screen.getByText('dev-machine-01').closest('tr') as HTMLElement;
    await user.click(within(row).getByRole('button', { name: /启动命令/ }));
    const reopened = await findModalByTitle('启动命令 · dev-machine-01');
    await expectContextWindowSelected(reopened, '1M', '1000000');
  });

  // workitem 54723：启动命令弹窗新增「保存配置」按钮，并统一 footer 按钮间距
  it('writes the Qoder startup config to the server when 保存配置 is clicked and keeps the dialog open', async () => {
    const user = userEvent.setup();
    serveExecutor({ clientKind: 'QODER_CLI' });
    let putBody: Record<string, unknown> | null = null;
    server.use(http.put('/api/executors/10000/launch-config', async ({ request }) => {
      putBody = await request.json() as Record<string, unknown>;
      return HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: { model: 'qmodel_latest', reasoningEffort: 'high', contextWindow: '1000000', memoryMode: 'platform', version: 2 },
      });
    }));
    renderPage();

    await expandAgentGroup(user, 'A（未编队）');
    await user.click(await screen.findByRole('button', { name: /启动命令/ }));
    const dialog = await findModalByTitle('启动命令 · dev-machine-01');
    await user.click(formItemSelect(dialog, 'Context Window'));
    await user.click(await screen.findByText('1M', { selector: '.ant-select-item-option-content' }));
    const concurrency = within(dialog).getByRole('spinbutton', { name: '最大并发任务数' });
    expect(concurrency).toHaveValue('5');
    await user.clear(concurrency);
    await user.type(concurrency, '5');
    await user.click(within(dialog).getByRole('button', { name: '保存配置' }));

    expect(await screen.findByText('启动配置已保存，请使用新启动命令重启执行器使配置生效')).toBeInTheDocument();
    // 当前表单值 + 打开弹窗时读到的乐观锁 version 一并写库
    await waitFor(() => expect(putBody).toMatchObject({ contextWindow: '1000000', version: 1, maxConcurrentDispatches: 5 }));
    // 保存不关闭弹窗（与复制不同），可继续调整或复制
    const wrap = dialog.closest('.ant-modal-wrap') as HTMLElement;
    expect(wrap.style.display).not.toBe('none');
  });

  it('persists only memoryMode for a non-Qoder executor when 保存配置 is clicked', async () => {
    const user = userEvent.setup();
    serveExecutor({ clientKind: 'CLAUDE_CODE' });
    let putBody: Record<string, unknown> | null = null;
    server.use(http.put('/api/executors/10000/launch-config', async ({ request }) => {
      putBody = await request.json() as Record<string, unknown>;
      return HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: { model: null, reasoningEffort: null, contextWindow: null, memoryMode: 'none', version: 2 },
      });
    }));
    renderPage();

    await expandAgentGroup(user, 'A（未编队）');
    await user.click(await screen.findByRole('button', { name: /启动命令/ }));
    const dialog = await findModalByTitle('启动命令 · dev-machine-01');
    await user.click(formItemSelect(dialog, '记忆模式'));
    await user.click(await screen.findByText('关闭记忆', { selector: '.ant-select-item-option-content' }));
    await user.click(within(dialog).getByRole('button', { name: '保存配置' }));

    expect(await screen.findByText('启动配置已保存，请使用新启动命令重启执行器使配置生效')).toBeInTheDocument();
    await waitFor(() => expect(putBody).toMatchObject({ memoryMode: 'none' }));
    // 非 Qoder 执行器不携带 Qoder 专有字段
    expect(putBody).not.toHaveProperty('model');
    expect(putBody).not.toHaveProperty('contextWindow');
  });

  it('keeps the dialog open and surfaces the conflict when 保存配置 hits a version conflict', async () => {
    const user = userEvent.setup();
    serveExecutor({ clientKind: 'QODER_CLI' });
    server.use(http.put('/api/executors/10000/launch-config', () => HttpResponse.json({
      success: false, code: '17005', message: '启动配置已被修改，请刷新后重试', data: null, traceId: null,
    })));
    renderPage();

    await expandAgentGroup(user, 'A（未编队）');
    await user.click(await screen.findByRole('button', { name: /启动命令/ }));
    const dialog = await findModalByTitle('启动命令 · dev-machine-01');
    await user.click(within(dialog).getByRole('button', { name: '保存配置' }));

    expect(await screen.findByText('配置已被修改，请刷新后重试')).toBeInTheDocument();
    expect(screen.queryByText('启动配置已保存')).not.toBeInTheDocument();
    const wrap = dialog.closest('.ant-modal-wrap') as HTMLElement;
    expect(wrap.style.display).not.toBe('none');
  });

  it('wraps every startup-modal footer action in one Space so 保存配置 and 复制启动命令 are evenly spaced', async () => {
    const user = userEvent.setup();
    serveExecutor({ clientKind: 'QODER_CLI' });
    renderPage();

    await expandAgentGroup(user, 'A（未编队）');
    await user.click(await screen.findByRole('button', { name: /启动命令/ }));
    const dialog = await findModalByTitle('启动命令 · dev-machine-01');

    const footer = dialog.querySelector('.ant-modal-footer') as HTMLElement;
    expect(footer).not.toBeNull();
    const saveBtn = within(footer).getByRole('button', { name: '保存配置' });
    const copyBtn = within(footer).getByRole('button', { name: '复制启动命令' });
    // 所有 footer 动作共处同一个 Space 容器 → 间距由该 Space 统一控制，不再贴合
    const footerSpace = footer.querySelector('.ant-space') as HTMLElement;
    expect(footerSpace).not.toBeNull();
    expect(footerSpace.contains(saveBtn)).toBe(true);
    expect(footerSpace.contains(copyBtn)).toBe(true);
    // 保存配置作为独立 Space item 渲染，与相邻按钮等宽间隔，而非直接贴合复制按钮
    expect(saveBtn.closest('.ant-space-item')).not.toBeNull();
    expect(saveBtn.parentElement).not.toBe(copyBtn.parentElement);
  });

  it('aborts the copy and keeps the dialog open when the server reports a version conflict', async () => {
    const user = userEvent.setup();
    const writeText = mockClipboardWrite();
    serveExecutor({ clientKind: 'QODER_CLI' });
    server.use(http.put('/api/executors/10000/launch-config', () => HttpResponse.json({
      success: false, code: '17005', message: '启动配置已被修改，请刷新后重试', data: null, traceId: null,
    })));
    renderPage();

    await expandAgentGroup(user, 'A（未编队）');
    await user.click(await screen.findByRole('button', { name: /启动命令/ }));
    const dialog = await findModalByTitle('启动命令 · dev-machine-01');
    await user.click(within(dialog).getByRole('button', { name: '复制启动命令' }));

    expect(await screen.findByText('配置已被修改，请刷新后重试')).toBeInTheDocument();
    expect(writeText).not.toHaveBeenCalled();
    const wrap = dialog.closest('.ant-modal-wrap') as HTMLElement;
    expect(wrap.style.display).not.toBe('none');
  });

  it('aborts the copy, keeps the dialog open and prompts to reselect when the server rejects the model as unavailable', async () => {
    const user = userEvent.setup();
    const writeText = mockClipboardWrite();
    serveExecutor({ clientKind: 'QODER_CLI' });
    // 后端判定所选模型已下线，返回 17006（spec §5 / 验收 §8.4 / plan §2.4）
    server.use(http.put('/api/executors/10000/launch-config', () => HttpResponse.json({
      success: false, code: '17006', message: '模型不可用，请重新选择', data: null, traceId: null,
    })));
    renderPage();

    await expandAgentGroup(user, 'A（未编队）');
    await user.click(await screen.findByRole('button', { name: /启动命令/ }));
    const dialog = await findModalByTitle('启动命令 · dev-machine-01');
    await user.click(within(dialog).getByRole('button', { name: '复制启动命令' }));

    // 出现「重新选择」提示（弹窗内 Alert），复制被中止、未写剪贴板、弹窗保持打开
    expect(await within(dialog).findByText('当前模型已不可用，请重新选择')).toBeInTheDocument();
    expect(writeText).not.toHaveBeenCalled();
    const wrap = dialog.closest('.ant-modal-wrap') as HTMLElement;
    expect(wrap.style.display).not.toBe('none');

    // 模型被置空后再点复制：表单校验失败直接中止，既不重复 PUT 也不生成命令
    let putCalls = 0;
    server.use(http.put('/api/executors/10000/launch-config', () => {
      putCalls += 1;
      return HttpResponse.json({
        success: false, code: '17006', message: '模型不可用，请重新选择', data: null, traceId: null,
      });
    }));
    await user.click(within(dialog).getByRole('button', { name: '复制启动命令' }));
    await act(async () => {
      await Promise.resolve();
    });
    expect(writeText).not.toHaveBeenCalled();
    expect(putCalls).toBe(0);
    expect(wrap.style.display).not.toBe('none');
  });

  it('prompts to reselect when the persisted server launch config has no usable model', async () => {
    const user = userEvent.setup();
    serveExecutor({ clientKind: 'QODER_CLI' });
    server.use(http.get('/api/executors/10000/launch-config', () => HttpResponse.json({
      success: true, code: '0', message: '', traceId: null,
      data: { model: null, reasoningEffort: 'medium', contextWindow: '260000', memoryMode: 'platform', version: 4 },
    })));
    renderPage();

    await expandAgentGroup(user, 'A（未编队）');
    await user.click(await screen.findByRole('button', { name: /启动命令/ }));
    const dialog = await findModalByTitle('启动命令 · dev-machine-01');

    expect(await within(dialog).findByText('当前模型已不可用，请重新选择')).toBeInTheDocument();
  });

  // 数据库里从未配置过的执行器：不得用默认值或浏览器偏好补齐，必须提示未配置并禁用复制
  it('blocks command generation until an unconfigured executor saves its launch config', async () => {
    const user = userEvent.setup();
    const writeText = mockClipboardWrite();
    let commandCalls = 0;
    let putBody: Record<string, unknown> | null = null;
    serveExecutor({ clientKind: 'QODER_CLI' }, null);
    server.use(
      http.post('/api/executors/10000/launch-command', async (input) => {
        commandCalls += 1;
        return resolveLaunchCommand(input);
      }),
      http.put('/api/executors/10000/launch-config', async ({ request }) => {
        putBody = await request.json() as Record<string, unknown>;
        const saved = putBody as {
          model?: string | null; reasoningEffort?: string | null;
          contextWindow?: string | null; memoryMode?: string | null; version?: number;
        };
        // 后端保存即落库：随后重新生成的命令必须读到刚保存的值
        setExecutorLaunchConfig(10000, {
          model: saved.model ?? null, reasoningEffort: saved.reasoningEffort ?? null,
          contextWindow: saved.contextWindow ?? null, memoryMode: saved.memoryMode ?? null,
          version: Number(saved.version ?? 1) + 1,
        });
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: {
            model: saved.model ?? null, reasoningEffort: saved.reasoningEffort ?? null,
            contextWindow: saved.contextWindow ?? null, memoryMode: saved.memoryMode ?? null,
            version: Number(saved.version ?? 1) + 1,
          },
        });
      }),
    );
    renderPage();

    await expandAgentGroup(user, 'A（未编队）');
    await user.click(await screen.findByRole('button', { name: /启动命令/ }));
    const dialog = await findModalByTitle('启动命令 · dev-machine-01');

    expect(await within(dialog).findByText('该执行器尚未配置启动参数，请先选择并保存')).toBeInTheDocument();
    expect(within(dialog).getByText('未配置：请先选择启动参数并保存后再生成命令')).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: '复制启动命令' })).toBeDisabled();
    expect(within(dialog).getByRole('button', { name: /复制 debug 模式命令/ })).toBeDisabled();
    // 未配置时不向服务端索取命令，避免生成一条误导性的启动命令
    expect(commandCalls).toBe(0);

    await selectInForm(user, dialog, '记忆模式', '平台记忆（推荐）');
    await selectInForm(user, dialog, 'Qoder 模型', 'Auto (default)');
    await selectInForm(user, dialog, 'Context Window', '1M');
    await user.click(within(dialog).getByRole('button', { name: '保存配置' }));

    expect(await screen.findByText('启动配置已保存，请使用新启动命令重启执行器使配置生效')).toBeInTheDocument();
    expect(putBody).toMatchObject({
      memoryMode: 'platform', model: 'auto', reasoningEffort: 'medium',
      contextWindow: '1000000', version: 1,
    });
    await waitFor(() => expect(within(dialog).getByRole('button', { name: '复制启动命令' })).toBeEnabled());
    await waitFor(() => expect(previewedCommand(dialog)).toContain('--context-window 1000000'));
    expect(commandCalls).toBeGreaterThan(0);
    expect(writeText).not.toHaveBeenCalled();
  });

  // 输出格式（os）只影响命令拼装方式，不是启动配置的一部分，切换时不得写库
  it('regenerates the command server-side when the output OS switches to Windows', async () => {
    const user = userEvent.setup();
    const commandBodies: Record<string, unknown>[] = [];
    let putCalls = 0;
    serveExecutor({ clientKind: 'QODER_CLI' });
    server.use(
      http.post('/api/executors/10000/launch-command', async (input) => {
        commandBodies.push(await input.request.clone().json() as Record<string, unknown>);
        return resolveLaunchCommand(input);
      }),
      http.put('/api/executors/10000/launch-config', () => {
        putCalls += 1;
        return HttpResponse.json({
          success: false, code: '17099', message: '切换输出格式不应写库', data: null, traceId: null,
        });
      }),
    );
    renderPage();

    await expandAgentGroup(user, 'A（未编队）');
    await user.click(await screen.findByRole('button', { name: /启动命令/ }));
    const dialog = await findModalByTitle('启动命令 · dev-machine-01');
    await waitFor(() => expect(previewedCommand(dialog)).toContain('--context-window 260000'));

    await user.click(within(dialog).getByText('Windows'));

    await waitFor(() => expect(commandBodies.some((body) => body.os === 'windows')).toBe(true));
    // 请求体里只有输出格式，没有任何启动值，页面与 MCP 的启动参数因此始终一致
    for (const body of commandBodies) {
      expect(Object.keys(body)).toEqual(['os']);
    }
    expect(putCalls).toBe(0);
    await waitFor(() => {
      expect(previewedCommand(dialog)).toContain('[Console]::OutputEncoding = [System.Text.Encoding]::UTF8');
    });
  });

  // 配置不完整时服务端拒绝生成命令，页面必须如实展示原因，不能退化成一条看似可用的命令
  it('reports the incomplete-config reason instead of generating a misleading command', async () => {
    const user = userEvent.setup();
    const writeText = mockClipboardWrite();
    serveExecutor({ clientKind: 'QODER_CLI' });
    server.use(http.post('/api/executors/10000/launch-command', () => HttpResponse.json({
      success: false, code: '17007',
      message: '启动配置不完整（缺少 Qoder 启动参数），请先保存启动配置后再生成命令',
      data: null, traceId: null,
    })));
    renderPage();

    await expandAgentGroup(user, 'A（未编队）');
    await user.click(await screen.findByRole('button', { name: /启动命令/ }));
    const dialog = await findModalByTitle('启动命令 · dev-machine-01');

    expect(await within(dialog).findByText('启动配置不完整，请先保存启动配置后再生成命令')).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: '复制启动命令' }));

    // 预览与复制失败提示都给出同一个原因，剪贴板不被写入、弹窗保持打开
    await waitFor(() => {
      expect(screen.getAllByText('启动配置不完整，请先保存启动配置后再生成命令').length).toBeGreaterThanOrEqual(2);
    });
    expect(writeText).not.toHaveBeenCalled();
    const wrap = dialog.closest('.ant-modal-wrap') as HTMLElement;
    expect(wrap.style.display).not.toBe('none');
  });

  // 仍有老调用方尝试临时覆盖启动值时，明确指向修改接口而不是静默替换
  it('maps the override rejection to the modify-config guidance', async () => {
    const user = userEvent.setup();
    serveExecutor({ clientKind: 'QODER_CLI' });
    server.use(http.post('/api/executors/10000/launch-command', () => HttpResponse.json({
      success: false, code: '17008',
      message: '启动参数 model 不支持临时覆盖，请先修改并保存启动配置',
      data: null, traceId: null,
    })));
    renderPage();

    await expandAgentGroup(user, 'A（未编队）');
    await user.click(await screen.findByRole('button', { name: /启动命令/ }));
    const dialog = await findModalByTitle('启动命令 · dev-machine-01');

    expect(await within(dialog).findByText('启动参数不支持临时覆盖，请先保存启动配置')).toBeInTheDocument();
  });

  // 缺客户端类型的历史执行器：服务端拒绝生成命令而不是静默解释成 claude，页面如实展示原因
  it('refuses to build a startup command for an executor without a client kind', async () => {
    const user = userEvent.setup();
    serveExecutor({ clientKind: null });
    renderPage();

    await expandAgentGroup(user, 'A（未编队）');
    await user.click(await screen.findByRole('button', { name: /启动命令/ }));
    const dialog = await findModalByTitle('启动命令 · dev-machine-01');

    expect(await within(dialog).findByText('该执行器缺少客户端类型，无法生成启动命令')).toBeInTheDocument();
    expect(previewedCommand(dialog)).not.toContain('--provider');
  });

  it('saves the launch config without copying a command', async () => {
    const user = userEvent.setup();
    const writeText = mockClipboardWrite();
    let putBody: Record<string, unknown> | null = null;
    serveExecutor({ clientKind: 'QODER_CLI' });
    server.use(http.put('/api/executors/10000/launch-config', async ({ request }) => {
      putBody = await request.json() as Record<string, unknown>;
      // 后端保存即落库：保存后重新生成的预览必须读到 ultimate，而不是原来的 auto
      setExecutorLaunchConfig(10000, {
        model: 'ultimate', reasoningEffort: 'high', contextWindow: '260000',
        memoryMode: 'platform', version: 2,
      });
      return HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: {
          model: 'ultimate', reasoningEffort: 'high', contextWindow: '260000',
          memoryMode: 'platform', version: 2,
        },
      });
    }));
    renderPage();

    await expandAgentGroup(user, 'A（未编队）');
    await user.click(await screen.findByRole('button', { name: /启动命令/ }));
    const dialog = await findModalByTitle('启动命令 · dev-machine-01');
    await selectInForm(user, dialog, 'Qoder 模型', 'Ultimate');
    await user.click(within(dialog).getByRole('button', { name: '保存配置' }));

    expect(await screen.findByText('启动配置已保存，请使用新启动命令重启执行器使配置生效')).toBeInTheDocument();
    expect(putBody).toMatchObject({
      model: 'ultimate', reasoningEffort: 'high', contextWindow: '260000',
      memoryMode: 'platform', version: 1,
    });
    expect(writeText).not.toHaveBeenCalled();
    // 保存后预览重新向服务端索取，展示的就是数据库里刚落下的值
    await waitFor(() => {
      expect(previewedCommand(dialog)).toContain('--model ultimate');
      expect(previewedCommand(dialog)).toContain('--reasoning-effort high');
    });
  });

  it('treats a config as unconfigured only when every launch value is null', () => {
    expect(isLaunchConfigured(null)).toBe(false);
    expect(isLaunchConfigured({
      model: null, reasoningEffort: null, contextWindow: null, memoryMode: null, version: 1,
    })).toBe(false);
    // 非 Qoder 执行器只落记忆模式，同样算已配置
    expect(isLaunchConfigured({
      model: null, reasoningEffort: null, contextWindow: null, memoryMode: 'platform', version: 1,
    })).toBe(true);
    expect(isLaunchConfigured({
      model: 'auto', reasoningEffort: null, contextWindow: null, memoryMode: null, version: 1,
    })).toBe(true);
  });

  it.each([
    ['17001', '执行器不存在或已删除，无法生成启动命令'],
    ['17005', '配置已被修改，请刷新后重试'],
    ['17006', '当前模型已不可用，请重新选择'],
    ['17007', '启动配置不完整，请先保存启动配置后再生成命令'],
    ['17008', '启动参数不支持临时覆盖，请先保存启动配置'],
    ['17011', '该执行器缺少客户端类型，无法生成启动命令'],
  ])('maps launch error code %s to an operator-readable reason', (code, expected) => {
    expect(launchErrorMessage(new ApiError(code, 'server text', null), '兜底文案')).toBe(expected);
  });

  it('keeps the server message for unmapped business failures and hides technical system errors', () => {
    expect(launchErrorMessage(new ApiError('17099', '系统繁忙', null), '兜底文案')).toBe('系统繁忙');
    expect(launchErrorMessage(new ApiError('17099', '', null), '兜底文案')).toBe('兜底文案');
    // 网络中断被拦截器统一包成 10000，英文技术文案对操作者没有意义，必须换成业务兜底文案
    expect(launchErrorMessage(new ApiError('10000', 'Network Error', null), '兜底文案')).toBe('兜底文案');
    expect(launchErrorMessage(new Error('网络异常'), '兜底文案')).toBe('兜底文案');
  });

  // QA-2（tsx:559-561）：非 Qoder 执行器 + 服务端已有配置时仅回填 memoryMode；复制 PUT 载荷只含 memoryMode+version
  it('backfills only memoryMode for a non-Qoder executor when the server already has a config', async () => {
    const user = userEvent.setup();
    const writeText = mockClipboardWrite();
    let putBody: Record<string, unknown> | null = null;
    // 服务端已有配置：直接落进 mock 存储，读配置与生成命令因此读到同一份数据
    serveExecutor({ clientKind: 'CLAUDE_CODE' }, { memoryMode: 'provider-local', version: 3 });
    server.use(
      http.put('/api/executors/10000/launch-config', async ({ request }) => {
        putBody = await request.json() as Record<string, unknown>;
        const saved = putBody as { memoryMode?: string | null; version?: number };
        const version = Number(saved.version ?? 3) + 1;
        // 后端保存即落库：随后复制到的命令必须带上刚保存的记忆模式
        setExecutorLaunchConfig(10000, { memoryMode: saved.memoryMode ?? null, version });
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: {
            model: null, reasoningEffort: null, contextWindow: null,
            memoryMode: saved.memoryMode ?? null, version,
          },
        });
      }),
    );
    renderPage();

    await expandAgentGroup(user, 'A（未编队）');
    await user.click(await screen.findByRole('button', { name: /启动命令/ }));
    const dialog = await findModalByTitle('启动命令 · dev-machine-01');

    // memoryMode 回填为服务端值；非 Qoder 不渲染 model/reasoningEffort/Context Window 三项
    await waitFor(() => expect(previewedCommand(dialog)).toContain('--memory-mode provider-local'));
    expect(within(dialog).queryByText('Qoder 模型')).not.toBeInTheDocument();
    expect(within(dialog).queryByText('Reasoning Effort')).not.toBeInTheDocument();
    expect(within(dialog).queryByText('Context Window')).not.toBeInTheDocument();

    await user.click(within(dialog).getByRole('button', { name: '复制启动命令' }));
    await waitFor(() => expect(writeText).toHaveBeenCalledTimes(1));
    const cmd = writeText.mock.calls[0][0];
    expect(cmd).toContain('--memory-mode provider-local');
    expect(cmd).not.toContain('--model');
    expect(cmd).not.toContain('--context-window');
    // PUT 载荷只含 memoryMode + version，不带 model/reasoningEffort/contextWindow
    expect(putBody).toMatchObject({ memoryMode: 'provider-local', version: 3 });
    expect(putBody).not.toHaveProperty('model');
    expect(putBody).not.toHaveProperty('reasoningEffort');
    expect(putBody).not.toHaveProperty('contextWindow');
  });

  // 读不到数据库配置（例如执行器已被删除）时不得生成命令，也不能回退浏览器偏好或默认值
  it('blocks copying and explains why when the launch config cannot be read', async () => {
    const user = userEvent.setup();
    const writeText = mockClipboardWrite();
    let commandCalls = 0;
    serveExecutor({ clientKind: 'QODER_CLI' });
    server.use(
      http.get('/api/executors/10000/launch-config', () => HttpResponse.json({
        success: false, code: '17001', message: 'executor not found', data: null, traceId: null,
      })),
      http.post('/api/executors/10000/launch-command', async (input) => {
        commandCalls += 1;
        return resolveLaunchCommand(input);
      }),
    );
    renderPage();

    await expandAgentGroup(user, 'A（未编队）');
    await user.click(await screen.findByRole('button', { name: /启动命令/ }));
    const dialog = await findModalByTitle('启动命令 · dev-machine-01');

    // Alert 与命令预览都给出同一条原因，操作者不会看到一条误导性的命令
    const reason = '执行器不存在或已删除，无法生成启动命令';
    expect((await within(dialog).findAllByText(reason)).length).toBeGreaterThanOrEqual(2);
    expect(within(dialog).getByRole('button', { name: '复制启动命令' })).toBeDisabled();
    expect(within(dialog).getByRole('button', { name: /复制 debug 模式命令/ })).toBeDisabled();
    expect(commandCalls).toBe(0);
    expect(writeText).not.toHaveBeenCalled();
  });

  // 保存前必须补读到配置与版本号；补读同样失败就如实告知并中止，绝不猜测版本写库
  it('aborts the save with a read-failure message when the config version cannot be re-read', async () => {
    const user = userEvent.setup();
    const writeText = mockClipboardWrite();
    let putCalls = 0;
    serveExecutor({ clientKind: 'CLAUDE_CODE' });
    // GET 始终失败：打开弹窗即进入错误态，保存时的补读也失败
    server.use(
      http.get('/api/executors/10000/launch-config', () => HttpResponse.error()),
      http.put('/api/executors/10000/launch-config', () => {
        putCalls += 1;
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: { model: null, reasoningEffort: null, contextWindow: null, memoryMode: 'provider-local', version: 2 },
        });
      }),
    );
    renderPage();

    await expandAgentGroup(user, 'A（未编队）');
    await user.click(await screen.findByRole('button', { name: /启动命令/ }));
    const dialog = await findModalByTitle('启动命令 · dev-machine-01');
    // Alert 与命令预览位都给出同一条原因，和上面 17001 用例一样是两处
    expect((await within(dialog).findAllByText('启动配置读取失败，无法生成命令')).length)
      .toBeGreaterThanOrEqual(2);

    // 非 Qoder 执行器只要求记忆模式，选中后保存才会走到补读分支
    await selectInForm(user, dialog, '记忆模式', '本机 Agent 记忆');
    await user.click(within(dialog).getByRole('button', { name: '保存配置' }));

    expect(await screen.findByText('启动配置读取失败，无法保存')).toBeInTheDocument();
    expect(putCalls).toBe(0);
    expect(writeText).not.toHaveBeenCalled();
    const wrap = dialog.closest('.ant-modal-wrap') as HTMLElement;
    expect(wrap.style.display).not.toBe('none');
  });

  it('re-reads the config version before saving when the initial read failed', async () => {
    const user = userEvent.setup();
    let getCalls = 0;
    let putBody: Record<string, unknown> | null = null;
    serveExecutor({ clientKind: 'CLAUDE_CODE' });
    server.use(
      http.get('/api/executors/10000/launch-config', () => {
        getCalls += 1;
        // 第 1 次（打开弹窗）失败；第 2 次（保存前补读）成功返回 version 9
        if (getCalls === 1) return HttpResponse.error();
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: {
            model: null, reasoningEffort: null, contextWindow: null,
            memoryMode: 'platform', version: 9,
          },
        });
      }),
      http.put('/api/executors/10000/launch-config', async ({ request }) => {
        putBody = await request.json() as Record<string, unknown>;
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: {
            model: null, reasoningEffort: null, contextWindow: null,
            memoryMode: 'provider-local', version: 10,
          },
        });
      }),
    );
    renderPage();

    await expandAgentGroup(user, 'A（未编队）');
    await user.click(await screen.findByRole('button', { name: /启动命令/ }));
    const dialog = await findModalByTitle('启动命令 · dev-machine-01');
    await waitFor(() => expect(getCalls).toBe(1));

    await selectInForm(user, dialog, '记忆模式', '本机 Agent 记忆');
    await user.click(within(dialog).getByRole('button', { name: '保存配置' }));

    expect(await screen.findByText('启动配置已保存，请使用新启动命令重启执行器使配置生效')).toBeInTheDocument();
    // 补读到的 version(9) 被带入 PUT，GET 恰好两次（打开弹窗 + 保存前补读）
    expect(putBody).toMatchObject({ memoryMode: 'provider-local', version: 9 });
    expect(getCalls).toBe(2);
  });

  // 保存失败且不是 17005/17006 时直接透出服务端原因，复制中止、弹窗保持打开
  it('shows the server reason when the save fails with an unexpected code', async () => {
    const user = userEvent.setup();
    const writeText = mockClipboardWrite();
    serveExecutor({ clientKind: 'QODER_CLI' });
    server.use(http.put('/api/executors/10000/launch-config', () => HttpResponse.json({
      success: false, code: '17099', message: '系统繁忙', data: null, traceId: null,
    })));
    renderPage();

    await expandAgentGroup(user, 'A（未编队）');
    await user.click(await screen.findByRole('button', { name: /启动命令/ }));
    const dialog = await findModalByTitle('启动命令 · dev-machine-01');
    await user.click(within(dialog).getByRole('button', { name: '复制启动命令' }));

    expect(await screen.findByText('系统繁忙')).toBeInTheDocument();
    expect(writeText).not.toHaveBeenCalled();
    const wrap = dialog.closest('.ant-modal-wrap') as HTMLElement;
    expect(wrap.style.display).not.toBe('none');
  });

  function mockGroupedExecutor(overrides: Record<string, unknown> = {}) {
    return {
      id: 10, agentId: 1, agentName: 'Alpha', name: 'runner-01', status: 'OFFLINE',
      clientKind: 'QODER_CLI', lastConnectIp: null, lastHeartbeat: null,
      gmtCreate: '2026-07-01T00:00:00Z', ...overrides,
    };
  }

  // 默认 /api/squads 返回空数组（真值），所以分组标签会带「（未编队）」后缀，与新建弹窗下拉一致。
  function mockGroupedExecutorApis(
    executors: Record<string, unknown>[],
    agents: Record<string, unknown>[] = [],
  ) {
    server.use(
      http.get('/api/agents', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: agents,
      })),
      http.get('/api/executors', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: executors,
      })),
    );
  }

  // 顶部有多个 antd Select，按 placeholder 文案定位到目标下拉再取它内部的 combobox。
  function comboboxOfPlaceholder(placeholder: string): HTMLElement {
    const select = screen.getByText(placeholder).closest('.ant-select');
    if (!select) throw new Error(`找不到 placeholder 为 ${placeholder} 的下拉框`);
    return within(select as HTMLElement).getByRole('combobox');
  }

  // listSquadsWithMembers 会为每个小队再拉一次详情，所以列表与详情都要打桩。
  function mockSquadFilterApis(
    executors: Record<string, unknown>[],
    agents: Record<string, unknown>[] = [],
    seenExecutorQueries: string[] = [],
  ) {
    const squad = (id: number, name: string, memberAgentIds: number[]) => ({
      id, name, description: '', ownerId: 1, version: 0, gmtCreate: '2026-07-01T00:00:00Z',
      memberAgentIds, memberCount: memberAgentIds.length,
    });
    server.use(
      http.get('/api/agents', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: agents,
      })),
      http.get('/api/executors', ({ request }) => {
        seenExecutorQueries.push(new URL(request.url).search);
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null, data: executors,
        });
      }),
      http.get('/api/squads', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [squad(7, 'Squad A', [1]), squad(8, 'Squad B', [2])],
      })),
      http.get('/api/squads/7', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: squad(7, 'Squad A', [1]),
      })),
      http.get('/api/squads/8', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: squad(8, 'Squad B', [2]),
      })),
    );
  }

  function collapseItemOf(label: string | RegExp): HTMLElement {
    const item = screen.getByText(label).closest('.ant-collapse-item');
    if (!item) throw new Error(`找不到分组 ${String(label)} 对应的折叠面板`);
    return item as HTMLElement;
  }

  function expectExpanded(item: HTMLElement, expanded: boolean) {
    if (expanded) {
      expect(item).toHaveClass('ant-collapse-item-active');
    } else {
      expect(item).not.toHaveClass('ant-collapse-item-active');
    }
  }

  async function toggleGroup(
    user: ReturnType<typeof userEvent.setup>,
    label: string | RegExp,
  ): Promise<HTMLElement> {
    const item = collapseItemOf(label);
    const header = item.querySelector('.ant-collapse-header');
    if (!header) throw new Error(`找不到分组 ${String(label)} 的折叠头`);
    await user.click(header as HTMLElement);
    return item;
  }

  // 分组默认折叠，需要行内内容的用例先展开对应分组。
  async function expandAgentGroup(
    user: ReturnType<typeof userEvent.setup>,
    label: string,
  ): Promise<HTMLElement> {
    await screen.findByText(label);
    const item = await toggleGroup(user, label);
    expectExpanded(item, true);
    return item;
  }

  it('renders one panel per owning agent collapsed by default with count and status summary', async () => {
    const user = userEvent.setup();
    mockGroupedExecutorApis([
      mockGroupedExecutor({ id: 10, agentId: 1, agentName: '全栈开发数字人', name: 'dev-machine-01', status: 'ONLINE' }),
      mockGroupedExecutor({ id: 11, agentId: 1, agentName: '全栈开发数字人', name: 'dev-machine-02', status: 'OFFLINE' }),
      mockGroupedExecutor({ id: 12, agentId: 2, agentName: 'CR数字人', name: 'cr-machine-01', status: 'BUSY', clientKind: 'QODER_CN_CLI' }),
    ]);
    renderPage();

    await screen.findByText('全栈开发数字人（未编队）');
    const devGroup = collapseItemOf('全栈开发数字人（未编队）');
    const crGroup = collapseItemOf('CR数字人（未编队）');

    expectExpanded(devGroup, false);
    expectExpanded(crGroup, false);

    // 折叠态下组头的数量与状态汇总仍然可见，组内执行器行不渲染
    expect(within(devGroup).getByText('2 个执行器')).toBeInTheDocument();
    expect(within(devGroup).getByText('在线 1')).toBeInTheDocument();
    expect(within(devGroup).getByText('忙碌 0')).toBeInTheDocument();
    expect(within(devGroup).getByText('离线 1')).toBeInTheDocument();
    expect(within(crGroup).getByText('1 个执行器')).toBeInTheDocument();
    expect(within(crGroup).getByText('忙碌 1')).toBeInTheDocument();
    expect(screen.queryByText('dev-machine-01')).not.toBeInTheDocument();
    expect(screen.queryByText('cr-machine-01')).not.toBeInTheDocument();

    await toggleGroup(user, '全栈开发数字人（未编队）');

    const openedDev = collapseItemOf('全栈开发数字人（未编队）');
    expectExpanded(openedDev, true);
    expectExpanded(collapseItemOf('CR数字人（未编队）'), false);
    expect(within(openedDev).getByText('dev-machine-01')).toBeInTheDocument();
    expect(within(openedDev).getByText('dev-machine-02')).toBeInTheDocument();
    expect(within(openedDev).queryByText('cr-machine-01')).not.toBeInTheDocument();
    expect(screen.queryByText('cr-machine-01')).not.toBeInTheDocument();
  });

  it('expands one agent group without touching the others and collapses it again', async () => {
    const user = userEvent.setup();
    mockGroupedExecutorApis([
      mockGroupedExecutor({ id: 10, agentId: 1, agentName: '全栈开发数字人', name: 'dev-machine-01' }),
      mockGroupedExecutor({ id: 12, agentId: 2, agentName: 'CR数字人', name: 'cr-machine-01' }),
    ]);
    renderPage();

    await screen.findByText('全栈开发数字人（未编队）');
    expectExpanded(collapseItemOf('全栈开发数字人（未编队）'), false);
    expectExpanded(collapseItemOf('CR数字人（未编队）'), false);

    await toggleGroup(user, '全栈开发数字人（未编队）');
    const opened = collapseItemOf('全栈开发数字人（未编队）');
    expectExpanded(opened, true);
    expectExpanded(collapseItemOf('CR数字人（未编队）'), false);
    expect(within(opened).getByText('dev-machine-01')).toBeInTheDocument();
    expect(screen.queryByText('cr-machine-01')).not.toBeInTheDocument();

    await toggleGroup(user, '全栈开发数字人（未编队）');
    expectExpanded(collapseItemOf('全栈开发数字人（未编队）'), false);

    await toggleGroup(user, '全栈开发数字人（未编队）');
    const reopened = collapseItemOf('全栈开发数字人（未编队）');
    expectExpanded(reopened, true);
    expect(within(reopened).getByText('dev-machine-01')).toBeInTheDocument();
  });

  it('separates same-named agents into different groups using the squad suffix', async () => {
    const user = userEvent.setup();
    server.use(
      http.get('/api/agents', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [mockAgent(40169, '全栈开发'), mockAgent(40170, '全栈开发')],
      })),
      ...mockSquadApis(),
      http.get('/api/executors', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [
          mockGroupedExecutor({ id: 10, agentId: 40169, agentName: '全栈开发', name: 'solo-runner' }),
          mockGroupedExecutor({ id: 11, agentId: 40170, agentName: '全栈开发', name: 'duo-runner' }),
        ],
      })),
    );
    renderPage();

    await screen.findByText('全栈开发（独立开发者小队）');
    expectExpanded(collapseItemOf('全栈开发（独立开发者小队）'), false);
    expectExpanded(collapseItemOf('全栈开发（开发+评审双人组）'), false);

    const soloGroup = await expandAgentGroup(user, '全栈开发（独立开发者小队）');
    const duoGroup = await expandAgentGroup(user, '全栈开发（开发+评审双人组）');

    expect(soloGroup).not.toBe(duoGroup);
    expect(within(soloGroup).getByText('solo-runner')).toBeInTheDocument();
    expect(within(soloGroup).queryByText('duo-runner')).not.toBeInTheDocument();
    expect(within(duoGroup).getByText('duo-runner')).toBeInTheDocument();
    expect(within(duoGroup).queryByText('solo-runner')).not.toBeInTheDocument();
  });

  it('puts executors without a resolvable agent name into 未知 Agent and sorts it last', async () => {
    const user = userEvent.setup();
    mockGroupedExecutorApis([
      mockGroupedExecutor({ id: 10, agentId: 1, agentName: null, name: 'orphan-runner' }),
      mockGroupedExecutor({ id: 11, agentId: 2, agentName: 'Beta数字人', name: 'beta-runner' }),
    ]);
    renderPage();

    await screen.findByText('未知 Agent');
    const unknownGroup = collapseItemOf('未知 Agent');
    expectExpanded(unknownGroup, false);
    expectExpanded(collapseItemOf('Beta数字人（未编队）'), false);
    const items = Array.from(document.querySelectorAll('.ant-collapse-item'));
    expect(items[items.length - 1]).toBe(unknownGroup);

    await expandAgentGroup(user, '未知 Agent');
    expect(within(collapseItemOf('未知 Agent')).getByText('orphan-runner')).toBeInTheDocument();

    await expandAgentGroup(user, 'Beta数字人（未编队）');
    expect(within(collapseItemOf('Beta数字人（未编队）')).getByText('beta-runner')).toBeInTheDocument();
    expect(within(collapseItemOf('未知 Agent')).queryByText('beta-runner')).not.toBeInTheDocument();
  });

  it('keeps a newly appearing agent group collapsed after another one was expanded', async () => {
    const user = userEvent.setup();
    mockGroupedExecutorApis([
      mockGroupedExecutor({ id: 10, agentId: 1, agentName: '全栈开发数字人', name: 'dev-machine-01' }),
    ]);
    const { queryClient } = renderPage();
    await screen.findByText('全栈开发数字人（未编队）');

    await toggleGroup(user, '全栈开发数字人（未编队）');
    expectExpanded(collapseItemOf('全栈开发数字人（未编队）'), true);

    await act(async () => {
      queryClient.setQueryData(['executors', undefined, []], [
        mockGroupedExecutor({ id: 10, agentId: 1, agentName: '全栈开发数字人', name: 'dev-machine-01' }),
        mockGroupedExecutor({ id: 12, agentId: 2, agentName: 'CR数字人', name: 'cr-machine-01' }),
      ]);
    });

    await screen.findByText('CR数字人（未编队）');
    expectExpanded(collapseItemOf('CR数字人（未编队）'), false);
    expectExpanded(collapseItemOf('全栈开发数字人（未编队）'), true);
    expect(screen.queryByText('cr-machine-01')).not.toBeInTheDocument();
  });

  it('narrows the groups to the selected agent through the top filter', async () => {
    const user = userEvent.setup();
    const alphaRunner = mockGroupedExecutor({ id: 10, agentId: 1, agentName: 'Alpha数字人', name: 'alpha-runner' });
    server.use(
      http.get('/api/agents', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [mockAgent(1, 'Alpha数字人'), mockAgent(2, 'Beta数字人')],
      })),
      http.get('/api/executors', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [alphaRunner, mockGroupedExecutor({ id: 11, agentId: 2, agentName: 'Beta数字人', name: 'beta-runner' })],
      })),
      http.get('/api/agents/1/executors', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: [alphaRunner],
      })),
    );
    renderPage();

    await screen.findByText('Beta数字人（未编队）');
    expect(document.querySelectorAll('.ant-collapse-item')).toHaveLength(2);

    await user.click(comboboxOfPlaceholder('选择 Agent'));
    await user.click(await screen.findByText('Alpha数字人', { selector: '.ant-select-item-option-content' }));

    await waitFor(() => {
      expect(document.querySelectorAll('.ant-collapse-item')).toHaveLength(1);
    });
    expectExpanded(collapseItemOf('Alpha数字人（未编队）'), false);
    expect(screen.queryByText('Beta数字人（未编队）')).not.toBeInTheDocument();

    const alphaGroup = await expandAgentGroup(user, 'Alpha数字人（未编队）');
    expect(within(alphaGroup).getByText('alpha-runner')).toBeInTheDocument();
    expect(screen.queryByText('beta-runner')).not.toBeInTheDocument();
  });

  it('still opens the startup command modal for a row inside its agent group', async () => {
    const user = userEvent.setup();
    server.use(
      http.get('/api/agents', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: [],
      })),
      http.get('/api/executors', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [
          mockGroupedExecutor({ id: 10, agentId: 1, agentName: 'Alpha数字人', name: 'alpha-runner' }),
          mockGroupedExecutor({ id: 11, agentId: 2, agentName: 'Beta数字人', name: 'beta-runner' }),
        ],
      })),
      http.get('/api/executors/11/token', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: 'exec_beta_token',
      })),
    );
    renderPage();

    const betaGroup = await expandAgentGroup(user, 'Beta数字人（未编队）');
    expect(within(betaGroup).getByText('beta-runner')).toBeInTheDocument();
    await user.click(within(betaGroup).getByRole('button', { name: /启动命令/ }));

    expect(await screen.findByRole('dialog', { name: '启动命令 · beta-runner' })).toBeInTheDocument();
  });

  it('keeps the flat empty table when there is no executor at all', async () => {
    mockGroupedExecutorApis([]);
    renderPage();

    expect(await screen.findByText('执行器管理')).toBeInTheDocument();
    expect(document.querySelector('.ant-collapse')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /新建执行器/ })).toBeInTheDocument();
  });

  it('buckets executors by squad in the 按小队分组 view and keeps unaffiliated ones visible', async () => {
    const user = userEvent.setup();
    mockSquadFilterApis([
      mockGroupedExecutor({ id: 10, agentId: 1, agentName: 'Alpha', name: 'a-runner', squadIds: [7], squadNames: ['Squad A'] }),
      mockGroupedExecutor({ id: 11, agentId: 2, agentName: 'Beta', name: 'b-runner', squadIds: [8], squadNames: ['Squad B'] }),
      mockGroupedExecutor({ id: 12, agentId: 3, agentName: 'Gamma', name: 'loose-runner', squadIds: null, squadNames: null }),
    ], [mockAgent(1, 'Alpha'), mockAgent(2, 'Beta'), mockAgent(3, 'Gamma')]);
    renderPage();

    await screen.findByText('Gamma（未编队）');
    expectExpanded(collapseItemOf('Alpha（Squad A）'), false);
    // 默认是 Agent 分组视图，此时还没有未归属分组
    expect(screen.queryByText('未分组')).not.toBeInTheDocument();

    await user.click(screen.getByText('按小队分组'));

    await screen.findByText('未分组');
    expect(document.querySelectorAll('.ant-collapse-item')).toHaveLength(3);
    const items = Array.from(document.querySelectorAll('.ant-collapse-item'));
    const squadAGroup = collapseItemOf('Squad A');
    const looseGroup = collapseItemOf('未分组');
    expectExpanded(squadAGroup, false);
    expectExpanded(collapseItemOf('Squad B'), false);
    expect(within(squadAGroup).getByText('1 个执行器')).toBeInTheDocument();
    expect(within(looseGroup).getByText('1 个执行器')).toBeInTheDocument();
    expect(screen.queryByText('a-runner')).not.toBeInTheDocument();
    // 未归属分组恒排最后，未编队的执行器不会被隐藏
    expect(items[items.length - 1]).toBe(looseGroup);

    expect(within(squadAGroup).getByText('Squad A').closest('a')).toBeNull();
    await user.click(within(squadAGroup).getByText('Squad A'));
    const opened = squadAGroup;
    expectExpanded(opened, true);
    expect(within(opened).getByText('a-runner')).toBeInTheDocument();
    const squadLink = within(opened).getByRole('link', { name: '查看小队：Squad A' });
    expect(squadLink).toHaveAttribute('href', '/squads?squadId=7');
    await user.click(squadLink);
    expectExpanded(opened, true);
  });

  it('re-requests the executors with the selected squad ids comma joined', async () => {
    const user = userEvent.setup();
    const seen: string[] = [];
    mockSquadFilterApis([
      mockGroupedExecutor({ id: 10, agentId: 1, agentName: 'Alpha', name: 'a-runner', squadIds: [7], squadNames: ['Squad A'] }),
    ], [mockAgent(1, 'Alpha')], seen);
    renderPage();

    // antd 把 aria-label 同时挂在外层 div 和内部 combobox input 上；placeholder span 是 pointer-events:none
    await user.click(screen.getByRole('combobox', { name: '按小队筛选' }));
    await user.click(await screen.findByText('Squad A', { selector: '.ant-select-item-option-content' }));

    await waitFor(() => expect(seen.some((query) => query.includes('squadIds=7'))).toBe(true));
    // axios 默认的数组形式（squadIds[]=7）无法被 Spring 的 @RequestParam List<Long> 绑定
    expect(seen.some((query) => query.includes('squadIds%5B%5D'))).toBe(false);
  });

  it('narrows the per-agent executor endpoint client side because it takes no squad parameter', async () => {
    const user = userEvent.setup();
    const seenAgentScope: string[] = [];
    const squad = (id: number, name: string, memberAgentIds: number[]) => ({
      id, name, description: '', ownerId: 1, version: 0, gmtCreate: '2026-07-01T00:00:00Z',
      memberAgentIds, memberCount: memberAgentIds.length,
    });
    const runners = [
      mockGroupedExecutor({ id: 10, agentId: 1, agentName: 'Alpha', name: 'a-runner', squadIds: [7], squadNames: ['Squad A'] }),
      mockGroupedExecutor({ id: 11, agentId: 1, agentName: 'Alpha', name: 'b-runner', squadIds: [8], squadNames: ['Squad B'] }),
      mockGroupedExecutor({ id: 12, agentId: 1, agentName: 'Alpha', name: 'loose-runner', squadIds: null, squadNames: null }),
    ];
    server.use(
      http.get('/api/agents', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: [mockAgent(1, 'Alpha')],
      })),
      http.get('/api/executors', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: runners,
      })),
      http.get('/api/agents/1/executors', ({ request }) => {
        seenAgentScope.push(new URL(request.url).search);
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null, data: runners,
        });
      }),
      http.get('/api/squads', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [squad(7, 'Squad A', [1]), squad(8, 'Squad B', [])],
      })),
      http.get('/api/squads/7', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: squad(7, 'Squad A', [1]),
      })),
      http.get('/api/squads/8', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: squad(8, 'Squad B', []),
      })),
    );
    renderPage();

    await user.click(screen.getByRole('combobox', { name: '按小队筛选' }));
    await user.click(await screen.findByText('Squad A', { selector: '.ant-select-item-option-content' }));
    await user.click(comboboxOfPlaceholder('选择 Agent'));
    await user.click(await screen.findByText('Alpha', { selector: '.ant-select-item-option-content' }));

    await waitFor(() => expect(seenAgentScope.length).toBeGreaterThan(0));
    // 单 Agent 端点不接受 squadIds，筛选结果只能在前端收敛
    expect(seenAgentScope.every((query) => !query.includes('squadIds'))).toBe(true);

    await screen.findByText('Alpha（Squad A）');
    await user.click(screen.getByText('按小队分组'));
    await waitFor(() => expect(screen.queryByText('Alpha（Squad A）')).not.toBeInTheDocument());

    const panels = Array.from(document.querySelectorAll('.ant-collapse-item'));
    expect(panels).toHaveLength(1);
    const panel = panels[0] as HTMLElement;
    await user.click(panel.querySelector('.ant-collapse-header') as HTMLElement);
    expect(within(panel).getByText('a-runner')).toBeInTheDocument();
    expect(within(panel).queryByText('b-runner')).not.toBeInTheDocument();
    expect(within(panel).queryByText('loose-runner')).not.toBeInTheDocument();
    expect(screen.queryByText('未分组')).not.toBeInTheDocument();
  });

  describe('view preference persistence', () => {
    it('opens in the squad-grouped view by default and persists every toggle', async () => {
      const user = userEvent.setup();
      localStorage.removeItem('autowonder.executors.view');
      mockSquadFilterApis([
        mockGroupedExecutor({ id: 10, agentId: 1, agentName: 'Alpha', name: 'a-runner', squadIds: [7], squadNames: ['Squad A'] }),
      ], [mockAgent(1, 'Alpha')]);
      renderPage();

      await screen.findByText('Squad A');
      expect(screen.queryByText('Alpha（Squad A）')).not.toBeInTheDocument();

      await user.click(screen.getByText('按 Agent 分组'));

      await screen.findByText('Alpha（Squad A）');
      expect(localStorage.getItem('autowonder.executors.view')).toBe('agent');

      await user.click(screen.getByText('按小队分组'));

      await screen.findByText('Squad A');
      expect(localStorage.getItem('autowonder.executors.view')).toBe('squad');
    });

    it('restores the stored agent-grouped view', async () => {
      // beforeEach 已写入 'agent'，这里显式重申，模拟刷新后恢复用户上次的视图
      localStorage.setItem('autowonder.executors.view', 'agent');
      mockSquadFilterApis([
        mockGroupedExecutor({ id: 10, agentId: 1, agentName: 'Alpha', name: 'a-runner', squadIds: [7], squadNames: ['Squad A'] }),
      ], [mockAgent(1, 'Alpha')]);
      renderPage();

      await screen.findByText('Alpha（Squad A）');
      expect(screen.queryByText('未分组')).not.toBeInTheDocument();
    });

    it('falls back to the squad-grouped view when the stored view value is invalid', async () => {
      localStorage.setItem('autowonder.executors.view', 'bogus');
      mockSquadFilterApis([
        mockGroupedExecutor({ id: 10, agentId: 1, agentName: 'Alpha', name: 'a-runner', squadIds: [7], squadNames: ['Squad A'] }),
      ], [mockAgent(1, 'Alpha')]);
      renderPage();

      await screen.findByText('Squad A');
      expect(screen.queryByText('Alpha（Squad A）')).not.toBeInTheDocument();
    });

    it('shows the empty table instead of a blank panel in the squad view when there is no executor', async () => {
      localStorage.removeItem('autowonder.executors.view');
      mockGroupedExecutorApis([]);
      renderPage();

      expect(await screen.findByText('执行器管理')).toBeInTheDocument();
      expect(document.querySelector('.ant-collapse')).not.toBeInTheDocument();
      expect(document.querySelector('.ant-table')).toBeInTheDocument();
    });
  });
  it('shows process startup separately and sends an update restart from details', async () => {
    const user = userEvent.setup();
    serveExecutor({ status: 'ONLINE', restartSupported: true, updateRestartSupported: true, lastStartedAt: '2026-09-11T07:00:00Z' });
    let body: unknown;
    server.use(http.post('/api/executors/10000/restart', async ({ request }) => {
      body = await request.json();
      return HttpResponse.json({ success: true, code: '0', data: { requestId: 'r1', status: 'REQUESTED', message: 'waiting' } });
    }));
    renderPage();
    await expandAgentGroup(user, 'A（未编队）');
    expect(screen.getAllByText('最近启动').length).toBeGreaterThan(0);
    expect(screen.queryByText('创建时间')).not.toBeInTheDocument();
    expect(screen.queryByText('Token')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '详情' }));
    const drawer = await screen.findByRole('dialog', { name: 'dev-machine-01 · 执行器详情' });
    expect(within(drawer).getByText('创建时间')).toBeInTheDocument();
    expect(within(drawer).getByText('最近启动时间')).toBeInTheDocument();
    await user.click(within(drawer).getByRole('button', { name: '更新并重启' }));
    await waitFor(() => expect(body).toEqual({ update: true }));
    expect(await screen.findByText('已发送请求，等待客户端重启结果')).toBeInTheDocument();
    expect(screen.queryByText('重启成功')).not.toBeInTheDocument();
  });

  it('does not offer remote restart for a legacy client', async () => {
    const user = userEvent.setup();
    serveExecutor({ status: 'ONLINE', restartSupported: false });
    renderPage();
    await expandAgentGroup(user, 'A（未编队）');
    await user.click(screen.getByRole('button', { name: '详情' }));
    const drawer = await screen.findByRole('dialog', { name: 'dev-machine-01 · 执行器详情' });
    expect(within(drawer).getByRole('button', { name: '重启客户端' })).toBeDisabled();
    expect(within(drawer).getByRole('button', { name: '更新并重启' })).toBeDisabled();
  });

  describe('升级判定', () => {
    function upgradable(overrides: Partial<ExecutorVO> = {}): ExecutorVO {
      return {
        id: 10, agentId: 1, agentName: 'Alpha', name: 'runner-01', status: 'ONLINE',
        clientKind: 'QODER_CLI', lastConnectIp: null, lastHeartbeat: null,
        gmtCreate: '2026-07-01T00:00:00Z', version: '0.2.155', targetVersion: '0.2.160',
        upgradeSupported: true, upgradeAvailable: true, versionComparable: true, ...overrides,
      };
    }

    function updateTask(overrides: Partial<ExecutorUpdateVO> = {}): ExecutorUpdateVO {
      return {
        taskId: 91, requestId: 'req-1', status: 'PENDING', currentVersion: '0.2.155',
        targetVersion: '0.2.160', attemptCount: 1, maxAttempts: 3, lastError: null,
        source: 'MANUAL', requestedAt: '2026-09-11T08:00:00Z', nextAttemptAt: null,
        completedAt: null, ...overrides,
      };
    }

    function batchResult(
      overrides: Partial<ExecutorUpdateAllResultVO> = {},
    ): ExecutorUpdateAllResultVO {
      return { targetVersion: '0.2.160', total: 2, scheduled: 2, alreadyUpToDate: 0, skipped: [], ...overrides };
    }

    it('counts only the running statuses as in progress', () => {
      expect(isUpdateInProgress(null)).toBe(false);
      expect(isUpdateInProgress(undefined)).toBe(false);
      expect(isUpdateInProgress(updateTask({ status: 'PENDING' }))).toBe(true);
      expect(isUpdateInProgress(updateTask({ status: 'DRAINING' }))).toBe(true);
      expect(isUpdateInProgress(updateTask({ status: 'UPDATING' }))).toBe(true);
      // 终态必须放行，否则升级失败的执行器再也点不动「一键更新」
      expect(isUpdateInProgress(updateTask({ status: 'SUCCESS' }))).toBe(false);
      expect(isUpdateInProgress(updateTask({ status: 'FAILED' }))).toBe(false);
    });

    it('maps the task status onto a label and a tag color', () => {
      expect(updateStatusLabel('PENDING')).toBe('待更新');
      expect(updateStatusLabel('DRAINING')).toBe('排空中');
      expect(updateStatusLabel('UPDATING')).toBe('升级中');
      expect(updateStatusLabel('SUCCESS')).toBe('升级成功');
      expect(updateStatusLabel('FAILED')).toBe('升级失败');
      // 服务端将来新增状态时原样透出，不要渲染成空白标签
      expect(updateStatusLabel('ROLLED_BACK')).toBe('ROLLED_BACK');
      expect(updateStatusLabel(null)).toBe('');
      expect(updateStatusColor('FAILED')).toBe('error');
      expect(updateStatusColor('SUCCESS')).toBe('success');
      expect(updateStatusColor('UPDATING')).toBe('processing');
      expect(updateStatusColor('ROLLED_BACK')).toBe('default');
      expect(updateStatusColor(null)).toBe('default');
    });

    describe('upgradeBlockReason', () => {
      it('allows an online executor that reports an older version', () => {
        expect(upgradeBlockReason(upgradable())).toBeNull();
      });

      it('allows an offline executor so the task ships when it reconnects', () => {
        // 服务端从不因为离线拒绝升级；离线时 protocolFeatures 已过期，能力字段只代表「未知」
        expect(upgradeBlockReason(upgradable({ status: 'OFFLINE', upgradeSupported: false }))).toBeNull();
      });

      it('refuses an online client that never advertised the upgrade feature', () => {
        expect(upgradeBlockReason(upgradable({ upgradeSupported: false })))
          .toBe('当前客户端不支持远程升级，请先在本地升级客户端');
      });

      it('refuses an executor that already runs the target version', () => {
        expect(upgradeBlockReason(upgradable({ upgradeAvailable: false })))
          .toBe('已是目标版本 0.2.160，无需升级');
      });

      it('falls back to the reported version when the platform target is missing', () => {
        expect(upgradeBlockReason(upgradable({ upgradeAvailable: false, targetVersion: null })))
          .toBe('已是目标版本 0.2.155，无需升级');
      });

      it('does not claim an executor is current just because it never reported a version', () => {
        // upgradeAvailable 为 false 也可能只是版本未知，这种执行器服务端照样受理升级
        expect(upgradeBlockReason(upgradable({
          version: null, upgradeAvailable: false, versionComparable: false,
        }))).toBeNull();
      });

      it('does not claim an executor is current when its version cannot be parsed', () => {
        // 预发布版和本地构建都读不出稳定版本号，说它「已是目标版本」是假的；服务端 updateOne 也照样受理
        for (const version of ['0.2.155-beta.1', 'dev', 'nightly']) {
          expect(upgradeBlockReason(upgradable({
            version, upgradeAvailable: false, versionComparable: false,
          })), version).toBeNull();
        }
      });

      it('refuses while a task runs but allows a retry once it failed', () => {
        expect(upgradeBlockReason(upgradable({ update: updateTask({ status: 'UPDATING' }) })))
          .toBe('已有升级任务进行中，请等待结果');
        expect(upgradeBlockReason(upgradable({
          update: updateTask({ status: 'FAILED', lastError: 'npm registry returned 502' }),
        }))).toBeNull();
      });

      it('checks the version before the client capability, like the server does', () => {
        expect(upgradeBlockReason(upgradable({ upgradeSupported: false, upgradeAvailable: false })))
          .toBe('已是目标版本 0.2.160，无需升级');
      });
    });

    describe('describeUpdateAllResult', () => {
      it('summarises a batch where every executor was scheduled', () => {
        expect(describeUpdateAllResult(batchResult())).toBe('目标版本 0.2.160，已下发 2 个');
      });

      it('mentions the executors that were already current', () => {
        expect(describeUpdateAllResult(batchResult({ total: 3, scheduled: 2, alreadyUpToDate: 1 })))
          .toBe('目标版本 0.2.160，已下发 2 个，1 个已是最新');
      });

      it('mentions the skipped executors so they cannot be overlooked', () => {
        expect(describeUpdateAllResult(batchResult({
          total: 3, scheduled: 1, alreadyUpToDate: 1,
          skipped: [{ executorId: 12, executorName: 'busy-runner', reason: '已有升级任务进行中' }],
        }))).toBe('目标版本 0.2.160，已下发 1 个，1 个已是最新，1 个被跳过');
      });
    });
  });

  describe('一键更新', () => {
    // 升级任务在 msw 里没有全局兜底，凡是要点按钮的用例都得自己打桩
    function updateTaskPayload(overrides: Record<string, unknown> = {}) {
      return {
        taskId: 91, requestId: 'req-1', status: 'PENDING', currentVersion: '0.2.155',
        targetVersion: '0.2.160', attemptCount: 0, maxAttempts: 3, lastError: null,
        source: 'MANUAL', requestedAt: '2026-09-11T08:00:00Z', nextAttemptAt: null,
        completedAt: null, ...overrides,
      };
    }

    function behindTarget(overrides: Record<string, unknown> = {}) {
      return mockGroupedExecutor({
        status: 'ONLINE', upgradeSupported: true, upgradeAvailable: true, versionComparable: true,
        version: '0.2.155', targetVersion: '0.2.160', ...overrides,
      });
    }

    function serveUpdateOne(capture: (pathname: string) => void, payload = updateTaskPayload()) {
      server.use(http.post('/api/executors/:id/update', ({ request }) => {
        capture(new URL(request.url).pathname);
        return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: payload });
      }));
    }

    // 「一键全量更新」在卡片右上角，/api/executors 返回前就已渲染，此时列表为空、按钮必然禁用。
    // 分组标题只在数据到达后出现，用它确认加载完成，否则断言到的是加载中的中间态而非真实状态。
    async function waitExecutorsLoaded(label = 'Alpha（未编队）') {
      await screen.findByText(label);
    }

    it('sends a per-executor upgrade and reports the target version', async () => {
      const user = userEvent.setup();
      mockGroupedExecutorApis([behindTarget()]);
      let pathname = '';
      serveUpdateOne((value) => { pathname = value; });
      renderPage();
      await expandAgentGroup(user, 'Alpha（未编队）');

      const upgrade = await screen.findByRole('button', { name: /一键更新/ });
      expect(upgrade).toBeEnabled();
      await user.click(upgrade);

      expect(await screen.findByText('已下发升级指令，目标版本 0.2.160')).toBeInTheDocument();
      expect(pathname).toBe('/api/executors/10/update');
    });

    it('explains why a legacy client cannot be upgraded remotely', async () => {
      const user = userEvent.setup();
      mockGroupedExecutorApis([behindTarget({ upgradeSupported: false, version: '0.2.150' })]);
      renderPage();
      await expandAgentGroup(user, 'Alpha（未编队）');

      const upgrade = await screen.findByRole('button', { name: /一键更新/ });
      expect(upgrade).toBeDisabled();
      // 禁用状态的 button 吞掉鼠标事件，只能 hover 外层 span
      await user.hover(upgrade.parentElement!);
      expect(await screen.findByText('当前客户端不支持远程升级，请先在本地升级客户端')).toBeInTheDocument();
    });

    it('keeps the row button usable for an offline executor', async () => {
      const user = userEvent.setup();
      mockGroupedExecutorApis([behindTarget({ status: 'OFFLINE', upgradeSupported: false })]);
      let posted = false;
      serveUpdateOne(() => { posted = true; });
      renderPage();
      await expandAgentGroup(user, 'Alpha（未编队）');

      // 离线不代表不能升级：任务先落库，客户端重连后再下发
      await user.click(await screen.findByRole('button', { name: /一键更新/ }));
      expect(await screen.findByText('已下发升级指令，目标版本 0.2.160')).toBeInTheDocument();
      expect(posted).toBe(true);
    });

    it('keeps the row button usable when the reported version cannot be parsed', async () => {
      const user = userEvent.setup();
      // 0.2.155-beta.1 读不出稳定版本号：upgradeAvailable 为 false，但 versionComparable 也是 false，
      // 所以这不是「已是目标版本」，服务端 updateOne 照样受理。
      mockGroupedExecutorApis([behindTarget({
        version: '0.2.155-beta.1', upgradeAvailable: false, versionComparable: false,
      })]);
      let posted = false;
      serveUpdateOne(() => { posted = true; });
      renderPage();
      await expandAgentGroup(user, 'Alpha（未编队）');

      const upgrade = await screen.findByRole('button', { name: /一键更新/ });
      expect(upgrade).toBeEnabled();
      await user.hover(upgrade.parentElement!);
      expect(screen.queryByText(/无需升级/)).not.toBeInTheDocument();

      await user.click(upgrade);
      expect(await screen.findByText('已下发升级指令，目标版本 0.2.160')).toBeInTheDocument();
      expect(posted).toBe(true);
    });

    it('flags a new version and the running task in the list', async () => {
      const user = userEvent.setup();
      mockGroupedExecutorApis([behindTarget({
        update: updateTaskPayload({ status: 'DRAINING', source: 'BATCH' }),
      })]);
      renderPage();
      await expandAgentGroup(user, 'Alpha（未编队）');

      expect(await screen.findByText('有新版本')).toBeInTheDocument();
      expect(screen.getByText('排空中')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: /一键更新/ })).toBeDisabled();
    });

    it('surfaces the reason of a failed attempt and lets the operator retry', async () => {
      const user = userEvent.setup();
      mockGroupedExecutorApis([behindTarget({
        update: updateTaskPayload({
          status: 'FAILED', attemptCount: 3, lastError: 'npm registry returned 502',
        }),
      })]);
      renderPage();
      await expandAgentGroup(user, 'Alpha（未编队）');

      const tag = await screen.findByText('升级失败');
      await user.hover(tag);
      expect(await screen.findByText('npm registry returned 502')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: /一键更新/ })).toBeEnabled();
    });

    it('shows the backend refusal instead of a generic failure', async () => {
      const user = userEvent.setup();
      mockGroupedExecutorApis([behindTarget()]);
      server.use(http.post('/api/executors/:id/update', () => HttpResponse.json({
        success: false, code: '10001', message: '已有升级任务进行中，请等待结果', data: null, traceId: null,
      })));
      renderPage();
      await expandAgentGroup(user, 'Alpha（未编队）');

      await user.click(await screen.findByRole('button', { name: /一键更新/ }));
      expect(await screen.findByText('已有升级任务进行中，请等待结果')).toBeInTheDocument();
    });

    it('blocks the upgrade for an operator without workspace admin access', async () => {
      const user = userEvent.setup();
      useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_WRITE');
      mockGroupedExecutorApis([behindTarget()]);
      let posted = false;
      serveUpdateOne(() => { posted = true; });
      renderPage();
      await expandAgentGroup(user, 'Alpha（未编队）');

      await user.click(await screen.findByRole('button', { name: /一键更新/ }));
      expect(await screen.findByText('当前为读写权限，升级执行器需要管理员权限')).toBeInTheDocument();
      expect(posted).toBe(false);
    });

    it('disables the batch upgrade when nothing in scope is behind the target', async () => {
      const user = userEvent.setup();
      mockGroupedExecutorApis([behindTarget({ upgradeAvailable: false, version: '0.2.160' })]);
      renderPage();
      // 不等加载完成的话，空列表本身就会让按钮禁用，断言通过也说明不了什么
      await waitExecutorsLoaded();

      const batch = await screen.findByRole('button', { name: /一键全量更新/ });
      expect(batch).toBeDisabled();
      await user.hover(batch.parentElement!);
      expect(await screen.findByText('当前筛选范围内没有可升级的执行器')).toBeInTheDocument();
    });

    it('states the blast radius before the batch upgrade is sent', async () => {
      const user = userEvent.setup();
      mockGroupedExecutorApis([
        behindTarget({ id: 10, name: 'runner-01' }),
        behindTarget({ id: 11, name: 'runner-02', upgradeAvailable: false, version: '0.2.160' }),
      ]);
      server.use(http.get('/api/platform/runtime-auto-update', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: { executorAutoUpdateEnabled: true, targetVersion: '0.2.160' },
      })));
      renderPage();
      await waitExecutorsLoaded();

      const batch = await screen.findByRole('button', { name: /一键全量更新/ });
      expect(batch).toBeEnabled();
      await user.click(batch);

      const dialog = await findModalByTitle('一键全量更新');
      expect(within(dialog).getByText('目标版本 0.2.160，当前筛选范围内有 1 个执行器可升级')).toBeInTheDocument();
      // 自动升级已开启，被跳过的机器会自行重试，不必再提醒
      expect(within(dialog).queryByText(/自动升级当前处于关闭状态/)).not.toBeInTheDocument();
    });

    it('warns that skipped executors will not retry while auto update is off', async () => {
      const user = userEvent.setup();
      mockGroupedExecutorApis([behindTarget()]);
      renderPage();
      await waitExecutorsLoaded();

      await user.click(await screen.findByRole('button', { name: /一键全量更新/ }));
      const dialog = await findModalByTitle('一键全量更新');
      // 全局兜底返回 executorAutoUpdateEnabled:false
      expect(within(dialog).getByText(/自动升级当前处于关闭状态/)).toBeInTheDocument();
    });

    it('lists every skipped executor after the batch upgrade is sent', async () => {
      const user = userEvent.setup();
      mockGroupedExecutorApis([behindTarget({ id: 10 }), behindTarget({ id: 11, name: 'runner-02' })]);
      let search = 'unset';
      server.use(http.post('/api/executors/update-all', ({ request }) => {
        search = new URL(request.url).search;
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: {
            targetVersion: '0.2.160', total: 3, scheduled: 1, alreadyUpToDate: 1,
            skipped: [{ executorId: 12, executorName: 'busy-runner', reason: '已有升级任务进行中' }],
          },
        });
      }));
      renderPage();
      await waitExecutorsLoaded();

      await user.click(await screen.findByRole('button', { name: /一键全量更新/ }));
      const confirm = await findModalByTitle('一键全量更新');
      await user.click(within(confirm).getByRole('button', { name: '下发升级指令' }));

      const result = await findModalByTitle('全量更新已下发');
      expect(within(result).getByText('目标版本 0.2.160，已下发 1 个，1 个已是最新，1 个被跳过')).toBeInTheDocument();
      expect(within(result).getByText('busy-runner')).toBeInTheDocument();
      expect(within(result).getByText('· 已有升级任务进行中')).toBeInTheDocument();
      // 没有小队筛选时不能带上一个空的 squadIds 参数
      expect(search).toBe('');
    });

    it('scopes the batch upgrade to the selected squads', async () => {
      const user = userEvent.setup();
      const seen: string[] = [];
      mockSquadFilterApis([
        behindTarget({ id: 10, name: 'a-runner', squadIds: [7], squadNames: ['Squad A'] }),
        behindTarget({ id: 11, name: 'b-runner', squadIds: [8], squadNames: ['Squad B'] }),
      ], [mockAgent(1, 'Alpha')], seen);
      let search = 'unset';
      server.use(http.post('/api/executors/update-all', ({ request }) => {
        search = new URL(request.url).search;
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: { targetVersion: '0.2.160', total: 1, scheduled: 1, alreadyUpToDate: 0, skipped: [] },
        });
      }));
      renderPage();

      await user.click(screen.getByRole('combobox', { name: '按小队筛选' }));
      await user.click(await screen.findByText('Squad A', { selector: '.ant-select-item-option-content' }));
      await waitFor(() => expect(seen.some((query) => query.includes('squadIds=7'))).toBe(true));

      await user.click(await screen.findByRole('button', { name: /一键全量更新/ }));
      const confirm = await findModalByTitle('一键全量更新');
      // 确认弹窗统计的是筛选后的可见范围，不是整个租户
      expect(within(confirm).getByText('目标版本 0.2.160，当前筛选范围内有 1 个执行器可升级')).toBeInTheDocument();
      await user.click(within(confirm).getByRole('button', { name: '下发升级指令' }));

      // 同样是逗号拼接，否则 @RequestParam List<Long> 收不到值
      await waitFor(() => expect(search).toBe('?squadIds=7'));
      expect(await findModalByTitle('全量更新已下发')).toBeInTheDocument();
    });

    it('reports a batch failure without opening the result modal', async () => {
      const user = userEvent.setup();
      mockGroupedExecutorApis([behindTarget()]);
      server.use(http.post('/api/executors/update-all', () => HttpResponse.json({
        success: false, code: '10001', message: '目标版本未配置', data: null, traceId: null,
      })));
      renderPage();
      await waitExecutorsLoaded();

      await user.click(await screen.findByRole('button', { name: /一键全量更新/ }));
      const confirm = await findModalByTitle('一键全量更新');
      await user.click(within(confirm).getByRole('button', { name: '下发升级指令' }));

      expect(await screen.findByText('目标版本未配置')).toBeInTheDocument();
      expect(screen.queryByText('全量更新已下发', { selector: '.ant-modal-title' })).not.toBeInTheDocument();
    });

    it('shows the platform auto update switch as read-only state', async () => {
      const user = userEvent.setup();
      mockGroupedExecutorApis([]);
      server.use(http.get('/api/platform/runtime-auto-update', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: { executorAutoUpdateEnabled: true, targetVersion: '0.2.160' },
      })));
      renderPage();

      // 开关是服务端 application.yml 的部署配置，面板只展示状态，页面上不该再出现任何开关控件
      expect(await screen.findByText('已开启')).toBeInTheDocument();
      expect(screen.queryByRole('switch')).not.toBeInTheDocument();
      await user.hover(screen.getByText('自动升级'));
      expect(await screen.findByText(/开关由服务端 application.yml 配置/)).toBeInTheDocument();
    });

    it('shows the platform auto update switch as off when the deployment switch is off', async () => {
      mockGroupedExecutorApis([]);
      server.use(http.get('/api/platform/runtime-auto-update', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: { executorAutoUpdateEnabled: false, targetVersion: '0.2.160' },
      })));
      renderPage();

      expect(await screen.findByText('已关闭')).toBeInTheDocument();
    });

    it('falls back to the server default 已开启 when the switch cannot be read', async () => {
      mockGroupedExecutorApis([]);
      server.use(http.get('/api/platform/runtime-auto-update', () => HttpResponse.json({
        success: false, code: '500', message: 'boom', data: null, traceId: null,
      }, { status: 500 })));
      renderPage();

      // 读取失败时显示「关闭」会让操作者以为自动升级被谁关掉了，而服务端此刻仍在自动下发
      expect(await screen.findByText('已开启')).toBeInTheDocument();
      expect(screen.queryByText('已关闭')).not.toBeInTheDocument();
    });
  });

});
