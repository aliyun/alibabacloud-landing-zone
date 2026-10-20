import { i18n } from '@/shared/i18n';
import { beforeEach, describe, it, expect, vi } from 'vitest';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ConfigProvider, message } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import { createMemoryRouter, RouterProvider, MemoryRouter, useLocation } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { useAuthStore } from '@/shared/auth/store';
import { SquadListPage } from './SquadListPage';

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  // 与生产 App.tsx 一致地套上 zhCN locale，否则 antd 默认 en_US 会把弹窗确认键渲染成 "OK"。
  return render(
    <QueryClientProvider client={queryClient}>
      <ConfigProvider locale={zhCN}>
        <MemoryRouter><SquadListPage /></MemoryRouter>
      </ConfigProvider>
    </QueryClientProvider>,
  );
}

/** Mounts the page at a URL and mirrors the live query string so tests can watch it get consumed. */
function renderPageAt(url: string) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[url]}>
        <SquadListPage />
        <LocationProbe />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

function LocationProbe() {
  return <span data-testid="location-search">{useLocation().search || '(empty)'}</span>;
}

describe('SquadListPage', () => {
  beforeEach(async () => {
    await i18n.changeLanguage('zh-CN');
    useAuthStore.getState().clear();
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_WRITE');
    vi.restoreAllMocks();
  });

  it('renders compact squad cards with summary metrics and CRUD buttons', async () => {
    server.use(
      http.get('/api/squads', () => {
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: {
            list: [{
              id: 1,
              name: 'Team Alpha',
              description: 'Frontend squad',
              memberCount: 3,
              roleCount: 2,
              executorOnlineCount: 1,
              executorTotalCount: 2,
              sdlcCount: 1,
              gmtCreate: '2026-07-01',
            }],
            total: 1,
            pageNum: 1,
            pageSize: 20,
          },
        });
      }),
    );
    renderPage();
    expect(await screen.findByText('Team Alpha')).toBeInTheDocument();
    expect(screen.getByText('Frontend squad')).toBeInTheDocument();
    expect(screen.getByText('3 个数字员工')).toBeInTheDocument();
    expect(screen.getByText('2 类角色')).toBeInTheDocument();
    expect(screen.getByText('1/2 在线')).toBeInTheDocument();
    expect(screen.getByText('1 个 SDLC')).toBeInTheDocument();
    expect(screen.getByText('新建小队')).toBeInTheDocument();
    expect(screen.getByText('成员')).toBeInTheDocument();
    expect(screen.getByText('编辑')).toBeInTheDocument();
    expect(screen.getByText('删除')).toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });

  it('renders squads when backend returns raw list data', async () => {
    server.use(
      http.get('/api/squads', () => {
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: [{ id: 2, name: 'API专家小队', description: '负责API治理', memberCount: 0, gmtCreate: '2026-07-11' }],
        });
      }),
    );

    renderPage();

    expect(await screen.findByText('API专家小队')).toBeInTheDocument();
    expect(screen.getByText('负责API治理')).toBeInTheDocument();
  });

  it('shows zero members when backend omits memberCount and memberAgentIds is null', async () => {
    server.use(
      http.get('/api/squads', () => {
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: [{
            id: 10000,
            name: 'AutoWonder项目开发组',
            description: '用于开发和测试以及前端Web UI验收的小队',
            ownerId: null,
            version: 0,
            gmtCreate: '2026-07-11T08:45:10.909+00:00',
            memberAgentIds: null,
          }],
        });
      }),
    );

    renderPage();

    expect(await screen.findByText('AutoWonder项目开发组')).toBeInTheDocument();
    expect(screen.getByText('0 个数字员工')).toBeInTheDocument();
  });

  it('opens a visual squad detail modal with member role and SDLC details', async () => {
    const user = userEvent.setup();
    server.use(
      http.get('/api/squads', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: { list: [{ id: 1, name: 'Team Alpha', description: 'Frontend squad', memberCount: 1, gmtCreate: '2026-07-01' }], total: 1, pageNum: 1, pageSize: 20 },
      })),
      http.get('/api/squads/1/members', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [{
          agentId: 10,
          agentName: '前端 Alpha',
          roleCode: 'FRONTEND_DEV',
          roleName: '前端开发工程师',
          responsibilities: '负责页面实现、组件拆分、接口联调',
          sdlcId: 20,
          sdlcName: '前端标准流',
          sdlcSteps: [
            { id: 21, stepOrder: 1, name: '需求澄清', handlerType: 'AGENT', handlerRoleRef: 'PM_PRODUCT' },
            { id: 22, stepOrder: 2, name: '开发实现', handlerType: 'AGENT', handlerRoleRef: 'FRONTEND_DEV' },
          ],
        }, {
          agentId: 11,
          agentName: '测试 Beta',
          roleCode: 'QA',
          roleName: '测试工程师',
          responsibilities: '负责测试验证',
          sdlcId: 30,
          sdlcName: '质量保障流',
          sdlcSteps: [
            { id: 31, stepOrder: 1, name: '测试设计', handlerType: 'AGENT', handlerRoleRef: 'QA' },
            { id: 32, stepOrder: 2, name: '回归验证', handlerType: 'AGENT', handlerRoleRef: 'QA' },
          ],
        }],
      })),
    );

    renderPage();

    expect(await screen.findByText('Team Alpha')).toBeInTheDocument();
    await user.click(screen.getByText('详情'));

    expect(await screen.findByText('数字员工阵容')).toBeInTheDocument();
    expect(screen.getByText('前端 Alpha')).toBeInTheDocument();
    expect(screen.getAllByText('前端开发工程师').length).toBeGreaterThan(0);
    expect(screen.getByText('负责页面实现、组件拆分、接口联调')).toBeInTheDocument();
    expect(screen.getByText('前端标准流')).toBeInTheDocument();
    expect(screen.getByText('需求澄清')).toBeInTheDocument();
    expect(screen.getByText('开发实现')).toBeInTheDocument();
    expect(screen.getByText('测试 Beta')).toBeInTheDocument();
    expect(screen.getByText('质量保障流')).toBeInTheDocument();
    expect(screen.getByText('测试设计')).toBeInTheDocument();
    expect(screen.getByText('回归验证')).toBeInTheDocument();
  });

  it('keeps create visible but does not open the form for a read-only member', async () => {
    const error = vi.spyOn(message, 'error').mockImplementation(
      () => undefined as unknown as ReturnType<typeof message.error>,
    );
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_ONLY');
    server.use(
      http.get('/api/squads', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: { list: [], total: 0, pageNum: 1, pageSize: 20 },
      })),
    );
    renderPage();

    await screen.findAllByRole('button', { name: /新建小队/ });
    await userEvent.click(screen.getAllByRole('button', { name: /新建小队/ })[0]);

    expect(error).toHaveBeenCalledWith('当前为只读权限，新建小队需要读写权限');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('edits the squad debug log switch and persists the new value', async () => {
    const user = userEvent.setup();
    let putBody: { name?: string; debugLogEnabled?: boolean } | null = null;
    server.use(
      http.get('/api/squads', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: {
          list: [{
            id: 1, name: 'Team Alpha', description: 'Frontend squad', memberCount: 0,
            ownerId: 44, debugLogEnabled: true, gmtCreate: '2026-07-01',
          }],
          total: 1, pageNum: 1, pageSize: 20,
        },
      })),
      http.put('/api/squads/1', async ({ request }) => {
        putBody = await request.json() as { name?: string; debugLogEnabled?: boolean };
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: { id: 1, name: 'Team Alpha', debugLogEnabled: false },
        });
      }),
    );

    renderSquadRoute();
    await screen.findByText('Team Alpha');
    // 卡片上出现 Debug 标记
    expect(screen.getByText('Debug')).toBeInTheDocument();

    await user.click(screen.getByText('编辑'));
    const debugSwitch = await screen.findByTestId('debug-log-switch');
    expect(debugSwitch).toHaveAttribute('aria-checked', 'true');

    await user.click(debugSwitch);
    await act(async () => { await i18n.changeLanguage('en-US'); });
    expect(screen.getByRole('switch', { name: 'Debug log collection' })).toBe(debugSwitch);
    expect(screen.getByLabelText('Squad name')).toHaveValue('Team Alpha');
    await user.click(screen.getByRole('button', { name: 'OK' }));

    await waitFor(() => expect(putBody).not.toBeNull());
    expect(putBody).toEqual({ name: 'Team Alpha', description: 'Frontend squad', ownerId: 44, debugLogEnabled: false });
    await act(async () => { await i18n.changeLanguage('zh-CN'); });
  });

  it('does not render the debug switch in the create modal', async () => {
    const user = userEvent.setup();
    server.use(
      http.get('/api/squads', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: { list: [], total: 0, pageNum: 1, pageSize: 20 },
      })),
    );

    renderPage();
    await user.click(screen.getByText('新建小队'));
    await screen.findByLabelText('小队名称');

    expect(screen.queryByTestId('debug-log-switch')).not.toBeInTheDocument();
  });

  // 后端 PUT /api/squads/{id} 只对 debug_log_enabled 走 COALESCE，
  // name/description/owner_id 是无条件覆盖，因此切开关必须整对象提交。
  it('submits the whole squad object so owner and description survive a debug switch toggle', async () => {
    const user = userEvent.setup();
    let putBody: Record<string, unknown> | null = null;
    server.use(
      http.get('/api/squads', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: {
          list: [{
            id: 1, name: 'Team Alpha', description: 'Frontend squad', ownerId: 7, memberCount: 0,
            debugLogEnabled: false, gmtCreate: '2026-07-01',
          }],
          total: 1, pageNum: 1, pageSize: 20,
        },
      })),
      http.put('/api/squads/1', async ({ request }) => {
        putBody = await request.json() as Record<string, unknown>;
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: { id: 1, name: 'Team Alpha', description: 'Frontend squad', ownerId: 7, debugLogEnabled: true },
        });
      }),
    );

    renderPage();
    await screen.findByText('Team Alpha');
    expect(screen.queryByText('Debug')).not.toBeInTheDocument();

    await user.click(screen.getByText('编辑'));
    const debugSwitch = await screen.findByTestId('debug-log-switch');
    expect(debugSwitch).toHaveAttribute('aria-checked', 'false');

    await user.click(debugSwitch);
    await user.click(screen.getByRole('button', { name: /确\s?定/ }));

    await waitFor(() => expect(putBody).not.toBeNull());
    expect(putBody).toEqual({
      name: 'Team Alpha',
      description: 'Frontend squad',
      ownerId: 7,
      debugLogEnabled: true,
    });
  });

  it('opens the squad detail from a ?squadId= deep link and consumes the param', async () => {
    server.use(
      http.get('/api/squads', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: {
          list: [{ id: 1, name: 'Team Alpha', description: 'Frontend squad', memberCount: 1, gmtCreate: '2026-07-01' }],
          total: 1, pageNum: 1, pageSize: 20,
        },
      })),
      http.get('/api/squads/1', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: {
          id: 1, name: 'Team Alpha', memberAgentIds: [10], memberCount: 1, roleCount: 1,
          executorOnlineCount: 1, executorTotalCount: 1, sdlcCount: 1, version: 0, gmtCreate: null,
          sdlcs: [{ id: 30, name: '全栈交付', workType: 'REQ', status: 'ENABLED' }],
          executors: [{
            id: 91, agentId: 10, agentName: 'Alpha', name: 'dev-machine-01',
            status: 'ONLINE', clientKind: 'QODER_CLI', lastHeartbeat: null,
          }],
        },
      })),
      http.get('/api/squads/1/members', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: [],
      })),
    );

    renderPageAt('/agents?tab=squads&squadId=1');

    // Squad tags on the agent / SDLC / executor pages link here; no click is involved.
    expect(await screen.findByText('dev-machine-01')).toBeInTheDocument();
    expect(screen.getByText('关联执行器')).toBeInTheDocument();
    // Consumed once matched, otherwise closing the modal would immediately reopen it.
    // Only squadId is consumed; the hub keeps its tab param so the pane stays put.
    await waitFor(() => expect(screen.getByTestId('location-search')).not.toHaveTextContent('squadId'));
    expect(screen.getByTestId('location-search')).toHaveTextContent('tab=squads');
  });

  it('resolves a ?squadId= deep link whose squad sits beyond the first page', async () => {
    const detailCalls: string[] = [];
    const firstPage = Array.from({ length: 20 }, (_, index) => ({
      id: index + 1, name: `小队 ${index + 1}`, description: '', memberCount: 0, gmtCreate: '2026-07-01',
    }));
    const secondPage = Array.from({ length: 5 }, (_, index) => ({
      id: 21 + index, name: `小队 ${21 + index}`, description: '', memberCount: 0, gmtCreate: '2026-07-01',
    }));
    server.use(
      http.get('/api/squads', ({ request }) => {
        const page = Number(new URL(request.url).searchParams.get('page') ?? '1');
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: { list: page === 1 ? firstPage : secondPage, total: 25, pageNum: page, pageSize: 20 },
        });
      }),
      http.get('/api/squads/25', () => {
        detailCalls.push('25');
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: {
            id: 25, name: '小队 25', memberAgentIds: [], memberCount: 0, roleCount: 0,
            executorOnlineCount: 1, executorTotalCount: 1, sdlcCount: 0, version: 0, gmtCreate: null,
            sdlcs: [],
            executors: [{
              id: 95, agentId: 20, agentName: 'Gamma', name: 'paged-executor',
              status: 'ONLINE', clientKind: 'QODER_CLI', lastHeartbeat: null,
            }],
          },
        });
      }),
      http.get('/api/squads/25/members', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: [],
      })),
    );

    renderPageAt('/agents?tab=squads&squadId=25');

    // 第 1 页的 20 行里没有 id=25，只能按 id 直取详情；旧实现在这里静默什么都不做。
    expect(await screen.findByText('paged-executor')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByTestId('location-search')).not.toHaveTextContent('squadId'));
    expect(detailCalls.length).toBeGreaterThan(0);

    // 分页已移除：所有小队一次拉全，第 2 页的行直接可见，参数也不会写回 URL。
    expect(await screen.findByText('小队 21')).toBeInTheDocument();
    expect(screen.getByTestId('location-search')).toHaveTextContent('tab=squads');
    expect(screen.getByTestId('location-search')).not.toHaveTextContent('squadId');
  });

  it('warns instead of failing silently when a deep-linked squad cannot be resolved', async () => {
    const warning = vi.spyOn(message, 'warning').mockImplementation(
      () => undefined as unknown as ReturnType<typeof message.warning>,
    );
    server.use(
      http.get('/api/squads', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: {
          list: [{ id: 1, name: 'Team Alpha', description: '', memberCount: 0, gmtCreate: '2026-07-01' }],
          total: 1, pageNum: 1, pageSize: 20,
        },
      })),
      http.get('/api/squads/999', () => HttpResponse.json({
        success: false, code: '10000', message: '小队不存在', traceId: null, data: null,
      })),
    );

    renderPageAt('/agents?tab=squads&squadId=999');

    // 深链失败不得阻塞列表页，也不得静默：给出可见提示，且参数照样被消费。
    expect(await screen.findByText('Team Alpha')).toBeInTheDocument();
    await waitFor(() => expect(warning).toHaveBeenCalledWith('小队 #999 不存在或当前账号无权访问'));
    await waitFor(() => expect(screen.getByTestId('location-search')).not.toHaveTextContent('squadId'));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('shows a placeholder when the squad derives no executors', async () => {
    const user = userEvent.setup();
    server.use(
      http.get('/api/squads', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: { list: [{ id: 2, name: 'Team Beta', description: '', memberCount: 0, gmtCreate: '2026-07-01' }], total: 1, pageNum: 1, pageSize: 20 },
      })),
      http.get('/api/squads/2', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: { id: 2, name: 'Team Beta', memberAgentIds: [], memberCount: 0, roleCount: 0, executorOnlineCount: 0, executorTotalCount: 0, sdlcCount: 0, version: 0, gmtCreate: null, sdlcs: [], executors: [] },
      })),
      http.get('/api/squads/2/members', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: [],
      })),
    );

    renderPage();
    expect(await screen.findByText('Team Beta')).toBeInTheDocument();
    await user.click(screen.getByText('详情'));

    expect(await screen.findByText('关联执行器')).toBeInTheDocument();
    expect(screen.getByText('暂无执行器')).toBeInTheDocument();
  });

  it('opens the copy modal from the squad card for a write member', async () => {
    const user = userEvent.setup();
    let checkCalls = 0;
    server.use(
      http.get('/api/squads', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: {
          list: [{ id: 1, name: 'Team Alpha', description: '', memberCount: 1, gmtCreate: '2026-07-01' }],
          total: 1, pageNum: 1, pageSize: 20,
        },
      })),
      http.get('/api/squads/1/copy-check', () => {
        checkCalls += 1;
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: { eligible: true, missingOnlineVersions: [] },
        });
      }),
      http.get('/api/squads/1/copy-targets', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: { list: [{ id: 2, name: '目标空间A', description: '' }], total: 1, pageNum: 1, pageSize: 10 },
      })),
    );

    renderPage();
    await screen.findByText('Team Alpha');
    await user.click(screen.getByRole('button', { name: /复制到工作空间/ }));

    expect(await screen.findByText('复制到工作空间', { selector: '.ant-modal-title' })).toBeInTheDocument();
    expect(await screen.findByText('目标空间A')).toBeInTheDocument();
    expect(checkCalls).toBe(1);
  });

  it('keeps copy visible but does not run the pre-check for a read-only member', async () => {
    const error = vi.spyOn(message, 'error').mockImplementation(
      () => undefined as unknown as ReturnType<typeof message.error>,
    );
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_ONLY');
    let checkCalls = 0;
    server.use(
      http.get('/api/squads', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: {
          list: [{ id: 1, name: 'Team Alpha', description: '', memberCount: 1, gmtCreate: '2026-07-01' }],
          total: 1, pageNum: 1, pageSize: 20,
        },
      })),
      http.get('/api/squads/1/copy-check', () => {
        checkCalls += 1;
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: { eligible: true, missingOnlineVersions: [] },
        });
      }),
    );

    renderPage();
    await screen.findByText('Team Alpha');
    await userEvent.click(screen.getByRole('button', { name: /复制到工作空间/ }));

    expect(error).toHaveBeenCalledWith('当前为只读权限，复制到工作空间需要读写权限');
    expect(screen.queryByText('复制到工作空间', { selector: '.ant-modal-title' })).not.toBeInTheDocument();
    expect(checkCalls).toBe(0);
  });
});


describe('routed squad localization', () => {
  beforeEach(async () => {
    message.destroy();
    await i18n.changeLanguage('zh-CN');
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_WRITE');
    vi.restoreAllMocks();
  });
  it('switches existing validation errors while keeping drafts and canonical create payloads', async () => {
    await i18n.changeLanguage('zh-CN');
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_WRITE');
    let payload: unknown;
    let finish!: () => void;
    const responseReady = new Promise<void>(resolve => { finish = resolve; });
    server.use(http.post('/api/squads', async ({ request }) => {
      payload = await request.json();
      await responseReady;
      return HttpResponse.json({ success: true, data: { id: 9, ...payload as object } });
    }));
    const router = createMemoryRouter([{ path: '/agents', element: <SquadListPage /> }], { initialEntries: ['/agents?tab=squads'] });
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>);
    await userEvent.click(await screen.findByRole('button', { name: /新建小队$/ }));
    const pristineName = screen.getByLabelText('小队名称');
    await act(async () => { await i18n.changeLanguage('en-US'); });
    expect(screen.queryByText('Enter a squad name')).not.toBeInTheDocument();
    expect(screen.getByLabelText('Squad name')).toBe(pristineName);
    await act(async () => { await i18n.changeLanguage('zh-CN'); });
    await userEvent.type(screen.getByLabelText('描述'), '<b>保留描述</b>');
    await userEvent.click(screen.getByRole('button', { name: /确\s?定/ }));
    expect(await screen.findByText('请输入小队名称')).toBeInTheDocument();
    const nameInput = screen.getByLabelText('小队名称');
    await act(async () => { await i18n.changeLanguage('en-US'); });
    expect(await screen.findByText('Enter a squad name')).toBeInTheDocument();
    expect(screen.getByLabelText('Squad name')).toBe(nameInput);
    expect(screen.getByLabelText('Description')).toHaveValue('<b>保留描述</b>');
    await userEvent.type(nameInput, '<img>自定义小队');
    await userEvent.click(screen.getByRole('button', { name: 'OK' }));
    await waitFor(() => expect(payload).toEqual({ name: '<img>自定义小队', description: '<b>保留描述</b>' }));
    await act(async () => { await i18n.changeLanguage('zh-CN'); });
    finish();
    expect(await screen.findByText('创建成功')).toBeInTheDocument();
  });
});


function renderSquadRoute() {
  const router = createMemoryRouter([{ path: '/agents', element: <SquadListPage /> }], { initialEntries: ['/agents?tab=squads'] });
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>);
}

describe('routed squad detail and member localization', () => {
  beforeEach(async () => {
    await i18n.changeLanguage('zh-CN');
    message.destroy();
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_WRITE');
    vi.restoreAllMocks();
  });

  it('keeps distinct role and SDLC rows when a custom role matches a translated preset label', async () => {
    server.use(
      http.get('/api/squads', () => HttpResponse.json({ success: true, data: [{ id: 1, name: '<b>自定义小队</b>', memberCount: 3 }] })),
      http.get('/api/squads/1', () => HttpResponse.json({ success: true, data: {
        id: 1, name: '<b>自定义小队</b>', sdlcs: [], executors: [
          { id: 7, name: '原始执行器', agentName: null, status: 'ONLINE' },
          { id: 8, name: '未来执行器', agentName: '原始名字', status: 'FUTURE_STATE' },
        ],
      } })),
      http.get('/api/squads/1/members', () => HttpResponse.json({ success: true, data: [
        { agentId: 10, agentName: '<img>原始员工', roleCode: 'FRONTEND_DEV', roleName: '前端开发工程师', responsibilities: '<b>原始职责</b>', sdlcId: 20, sdlcName: '<b>原始流程</b>', sdlcSteps: [{ id: 30, name: '<i>原始步骤</i>', handlerType: 'AGENT', handlerRoleRef: null }] },
        { agentId: 11, agentName: '自定义员工', roleCode: 'CUSTOM', roleName: 'Frontend developer', sdlcId: 20, sdlcName: '<b>原始流程</b>', sdlcSteps: [] },
        { agentId: 12, agentName: '未配置员工', roleCode: '', roleName: '', sdlcId: null, sdlcSteps: [] },
      ] })),
    );
    renderSquadRoute();
    await userEvent.click(await screen.findByRole('button', { name: /详情$/ }));
    const dialog = await screen.findByRole('dialog');
    const zhRole = within(dialog).getAllByText('前端开发工程师', { selector: 'span' })[0];
    const customRole = within(dialog).getAllByText('Frontend developer', { selector: 'span' })[0];
    const rawFlow = within(dialog).getByText('<b>原始流程</b>');
    await act(async () => { await i18n.changeLanguage('en-US'); });
    expect(within(dialog).getAllByText('Frontend developer', { selector: 'span' })).toEqual([zhRole, customRole]);
    expect(zhRole.parentElement).toHaveTextContent('1');
    expect(customRole.parentElement).toHaveTextContent('1');
    expect(within(dialog).getByText('<b>原始流程</b>')).toBe(rawFlow);
    expect(within(dialog).getByText('Frontend developer / Frontend developer')).toBeInTheDocument();
    expect(within(dialog).getByText('<img>原始员工')).toBeInTheDocument();
    expect(within(dialog).getByText('<b>原始职责</b>')).toBeInTheDocument();
    expect(within(dialog).getByText('<i>原始步骤</i>')).toBeInTheDocument();
    expect(within(dialog).getByText('Agent', { exact: true })).toBeInTheDocument();
    expect(within(dialog).getByText('Unknown Agent')).toBeInTheDocument();
    expect(within(dialog).getByText('Online')).toBeInTheDocument();
    expect(within(dialog).getByText('FUTURE_STATE')).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: 'Close' })).toBeInTheDocument();
    await act(async () => { await i18n.changeLanguage('zh-CN'); });
    expect(within(dialog).getByText('前端开发工程师', { selector: 'span' })).toBe(zhRole);
    await userEvent.click(within(dialog).getByRole('button', { name: '关闭' }));
    await userEvent.click(screen.getByRole('button', { name: /删除$/ }));
    await screen.findByText('确认删除该小队？');
    await act(async () => { await i18n.changeLanguage('en-US'); });
    expect(screen.getByText('Delete this squad?')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    await act(async () => { await i18n.changeLanguage('zh-CN'); });
  });

  it('preserves the selected Agent id and membership request across language switches', async () => {
    let payload: unknown;
    server.use(
      http.get('/api/squads', () => HttpResponse.json({ success: true, data: [{ id: 1, name: '原始小队', memberCount: 0 }] })),
      http.get('/api/squads/1/members', () => HttpResponse.json({ success: true, data: [] })),
      http.get('/api/agents', () => HttpResponse.json({ success: true, data: [{ id: 10, name: '<img>原始员工', roleCode: 'CUSTOM', roleName: '原始角色' }] })),
      http.post('/api/squads/1/members', async ({ request }) => {
        payload = await request.json();
        return HttpResponse.json({ success: true, data: null });
      }),
    );
    renderSquadRoute();
    await userEvent.click(await screen.findByRole('button', { name: /成员$/ }));
    const combo = await screen.findByRole('combobox', { name: '选择数字员工添加到小队' });
    await userEvent.click(combo);
    await userEvent.click(await screen.findByText('<img>原始员工', { selector: '.ant-select-item-option-content' }));
    await act(async () => { await i18n.changeLanguage('en-US'); });
    expect(screen.getByRole('combobox', { name: 'Select an Agent to add to the squad' })).toBe(combo);
    expect(combo.closest('.ant-select')).toHaveTextContent('<img>原始员工');
    await userEvent.click(screen.getByRole('button', { name: 'Add' }));
    await waitFor(() => expect(payload).toEqual({ agentIds: [10] }));
    expect(await screen.findByText('Member added')).toBeInTheDocument();
    await act(async () => { await i18n.changeLanguage('zh-CN'); });
  });
});
