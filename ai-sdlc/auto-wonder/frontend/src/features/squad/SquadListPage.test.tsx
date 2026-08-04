import { beforeEach, describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { message } from 'antd';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { useAuthStore } from '@/shared/auth/store';
import { SquadListPage } from './SquadListPage';

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter><SquadListPage /></MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('SquadListPage', () => {
  beforeEach(() => {
    useAuthStore.getState().clear();
    useAuthStore.getState().setCurrentOrg({ id: 1, name: 'O', description: '' }, 'READ_WRITE');
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
    useAuthStore.getState().setCurrentOrg({ id: 1, name: 'O', description: '' }, 'READ_ONLY');
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
});
