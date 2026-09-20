import { beforeEach, describe, it, expect, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ConfigProvider, message } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import { MemoryRouter, useLocation } from 'react-router-dom';
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
  beforeEach(() => {
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

    expect(await screen.findByText('数字人阵容')).toBeInTheDocument();
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
            debugLogEnabled: true, gmtCreate: '2026-07-01',
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

    renderPage();
    await screen.findByText('Team Alpha');
    // 卡片上出现 Debug 标记
    expect(screen.getByText('Debug')).toBeInTheDocument();

    await user.click(screen.getByText('编辑'));
    const debugSwitch = await screen.findByTestId('debug-log-switch');
    expect(debugSwitch).toHaveAttribute('aria-checked', 'true');

    await user.click(debugSwitch);
    await user.click(screen.getByRole('button', { name: /确\s?定/ }));

    await waitFor(() => expect(putBody).not.toBeNull());
    expect(putBody).toMatchObject({ name: 'Team Alpha', debugLogEnabled: false });
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

    renderPageAt('/squads?squadId=1');

    // Squad tags on the agent / SDLC / executor pages link here; no click is involved.
    expect(await screen.findByText('dev-machine-01')).toBeInTheDocument();
    expect(screen.getByText('关联执行器')).toBeInTheDocument();
    // Consumed once matched, otherwise closing the modal would immediately reopen it.
    await waitFor(() => expect(screen.getByTestId('location-search')).toHaveTextContent('(empty)'));
  });

  it('resolves a ?squadId= deep link whose squad sits beyond the first page', async () => {
    const user = userEvent.setup();
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

    renderPageAt('/squads?squadId=25');

    // 第 1 页的 20 行里没有 id=25，只能按 id 直取详情；旧实现在这里静默什么都不做。
    expect(await screen.findByText('paged-executor')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByTestId('location-search')).toHaveTextContent('(empty)'));
    const callsAfterResolve = detailCalls.length;
    expect(callsAfterResolve).toBeGreaterThan(0);

    // 参数已消费：翻页不得重新解析深链，也不得把参数写回 URL。
    await user.click(screen.getByTitle('2'));
    expect(await screen.findByText('小队 21')).toBeInTheDocument();
    expect(detailCalls).toHaveLength(callsAfterResolve);
    expect(screen.getByTestId('location-search')).toHaveTextContent('(empty)');
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

    renderPageAt('/squads?squadId=999');

    // 深链失败不得阻塞列表页，也不得静默：给出可见提示，且参数照样被消费。
    expect(await screen.findByText('Team Alpha')).toBeInTheDocument();
    await waitFor(() => expect(warning).toHaveBeenCalledWith('小队 #999 不存在或当前账号无权访问'));
    await waitFor(() => expect(screen.getByTestId('location-search')).toHaveTextContent('(empty)'));
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
});
