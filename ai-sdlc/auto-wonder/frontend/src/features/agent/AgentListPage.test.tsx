import { beforeEach, describe, it, expect, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { message } from 'antd';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { useAuthStore } from '@/shared/auth/store';
import { AgentListPage } from './AgentListPage';

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/agents']}>
        <Routes>
          <Route path="/agents" element={<AgentListPage />} />
          <Route path="/agents/:id" element={<div>数字员工详情路由</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('AgentListPage', () => {
  beforeEach(() => {
    useAuthStore.getState().clear();
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_WRITE');
    window.localStorage.removeItem('autowonder.agents.view');
    vi.restoreAllMocks();
  });

  it('renders compact agent cards with usage and executor status', async () => {
    server.use(
      http.get('/api/agents', () => {
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: [{
            id: 1,
            name: 'Alpha',
            avatarUrl: null,
            status: 'ONLINE',
            onlineVersionId: null,
            editingVersionId: null,
            latestVersionNo: 2,
            version: 1,
            gmtCreate: '2026-07-01',
            roleName: '前端开发工程师',
            roleCode: 'FRONTEND_DEV',
            executorOnlineCount: 1,
            executorTotalCount: 2,
            skillCount: 3,
            memoryCount: 4,
            repoPermCount: 5,
          }],
        });
      }),
    );
    renderPage();
    expect(await screen.findByText('Alpha')).toBeInTheDocument();
    expect(screen.getAllByText('使用中').length).toBeGreaterThan(0);
    expect(screen.getByText('前端开发工程师')).toBeInTheDocument();
    expect(screen.getByText('1/2 在线')).toBeInTheDocument();
    expect(screen.getByText('3')).toBeInTheDocument();
    expect(screen.getByText('技能')).toBeInTheDocument();
    expect(screen.getByText('4')).toBeInTheDocument();
    expect(screen.getByText('记忆')).toBeInTheDocument();
    expect(screen.getByText('5')).toBeInTheDocument();
    expect(screen.getByText('仓库')).toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });

  it('keeps create visible but does not navigate for a read-only member', async () => {
    const error = vi.spyOn(message, 'error').mockImplementation(
      () => undefined as unknown as ReturnType<typeof message.error>,
    );
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_ONLY');
    server.use(
      http.get('/api/agents', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: [],
      })),
    );
    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: /新建$/ }));

    expect(error).toHaveBeenCalledWith('当前为只读权限，新建数字员工需要读写权限');
    expect(screen.getByRole('heading', { name: '数字员工' })).toBeInTheDocument();
  });

  it('shows configuration guidance and one accessible detail link per card', async () => {
    server.use(
      http.get('/api/agents', () => {
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: [{
            id: 1,
            name: 'Alpha',
            avatarUrl: null,
            status: 'ONLINE',
            onlineVersionId: null,
            editingVersionId: null,
            latestVersionNo: 2,
            version: 1,
            gmtCreate: '2026-07-01',
            roleName: '前端开发工程师',
            roleCode: 'FRONTEND_DEV',
            executorOnlineCount: 1,
            executorTotalCount: 2,
            skillCount: 3,
            memoryCount: 4,
            repoPermCount: 5,
          }],
        });
      }),
    );
    renderPage();
    await screen.findByText('Alpha');

    expect(screen.getByText('点击任一数字人卡片进入详情与配置。')).toBeInTheDocument();
    expect(screen.getByText('可维护 SOUL.md、AGENT.md、记忆、仓库权限、SDLC 模板及技能/能力配置。')).toBeInTheDocument();

    const detailLink = screen.getByRole('link', { name: '查看 Alpha 的详情与配置' });
    expect(detailLink).toHaveAttribute('href', '/agents/1');
    expect(screen.getAllByRole('link')).toHaveLength(1);
  });

  it('navigates to agent detail page when clicking the card', async () => {
    const user = userEvent.setup();
    server.use(
      http.get('/api/agents', () => {
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: [{
            id: 1,
            name: 'Alpha',
            avatarUrl: null,
            status: 'ONLINE',
            onlineVersionId: null,
            editingVersionId: null,
            latestVersionNo: 2,
            version: 1,
            gmtCreate: '2026-07-01',
            roleName: '前端开发工程师',
            roleCode: 'FRONTEND_DEV',
            executorOnlineCount: 1,
            executorTotalCount: 2,
            skillCount: 3,
            memoryCount: 4,
            repoPermCount: 5,
          }],
        });
      }),
    );
    renderPage();

    await user.click(await screen.findByRole('link', { name: '查看 Alpha 的详情与配置' }));

    expect(await screen.findByText('数字员工详情路由')).toBeInTheDocument();
  });

  it('defaults to the standard tab and requests kind=STANDARD', async () => {
    const kinds: (string | null)[] = [];
    server.use(
      http.get('/api/squads', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: { list: [], total: 0, pageNum: 1, pageSize: 100 },
      })),
      http.get('/api/agents', ({ request }) => {
        kinds.push(new URL(request.url).searchParams.get('kind'));
        return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: [] });
      }),
    );
    renderPage();
    expect(await screen.findByRole('tab', { name: '数字员工' })).toBeInTheDocument();
    expect(await screen.findByRole('tab', { name: '系统平台智能体' })).toBeInTheDocument();
    await screen.findByText('暂无数字员工');
    expect(kinds).toContain('STANDARD');
  });

  it('switches to the platform tab, requests kind=PLATFORM and shows the platform badge', async () => {
    const user = userEvent.setup();
    const kinds: (string | null)[] = [];
    server.use(
      http.get('/api/squads', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: { list: [], total: 0, pageNum: 1, pageSize: 100 },
      })),
      http.get('/api/agents', ({ request }) => {
        const kind = new URL(request.url).searchParams.get('kind');
        kinds.push(kind);
        const data = kind === 'PLATFORM'
          ? [{
              id: 9,
              name: 'Chief of Staff',
              avatarUrl: null,
              status: 'ONLINE',
              kind: 'PLATFORM',
              onlineVersionId: 5,
              editingVersionId: null,
              latestVersionNo: 1,
              version: 1,
              gmtCreate: '2026-09-04',
              roleName: '平台管家',
              roleCode: 'chief_of_staff',
              executorOnlineCount: 0,
              executorTotalCount: 0,
              skillCount: 0,
              memoryCount: 0,
              repoPermCount: 0,
            }]
          : [];
        return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data });
      }),
    );
    renderPage();
    await user.click(await screen.findByRole('tab', { name: '系统平台智能体' }));
    expect(await screen.findByText('Chief of Staff')).toBeInTheDocument();
    expect(screen.getByText('平台')).toBeInTheDocument();
    expect(kinds).toContain('PLATFORM');
    expect(screen.queryByRole('button', { name: /新 建|新建/ })).not.toBeInTheDocument();
  });

  function useSquadsAndAgents(agents: unknown[]) {
    server.use(
      http.get('/api/squads', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: { list: [{ id: 7, name: 'Squad A' }, { id: 8, name: 'Squad B' }], total: 2, pageNum: 1, pageSize: 100 },
      })),
      http.get('/api/agents', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: agents,
      })),
    );
  }

  function agentRow(id: number, name: string, squadIds?: number[] | null, squadNames?: string[] | null) {
    return {
      id, name, status: 'ONLINE', roleName: '前端开发工程师', version: 1, latestVersionNo: 1,
      gmtCreate: '2026-07-01', squadIds, squadNames,
    };
  }

  it('tags each agent card with its squads and marks unaffiliated ones', async () => {
    window.localStorage.setItem('autowonder.agents.view', 'list');
    useSquadsAndAgents([
      agentRow(1, 'Alpha', [7, 8], ['Squad A', 'Squad B']),
      agentRow(2, 'Beta', [], []),
    ]);

    renderPage();

    expect(await screen.findByText('Alpha')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Squad A' })).toHaveAttribute('href', '/squads?squadId=7');
    expect(screen.getByRole('link', { name: 'Squad B' })).toHaveAttribute('href', '/squads?squadId=8');
    expect(screen.getByText('未分组')).toBeInTheDocument();
  });

  it('re-requests the list with the selected squad ids comma joined', async () => {
    const user = userEvent.setup();
    const seen: string[] = [];
    server.use(
      http.get('/api/squads', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: { list: [{ id: 7, name: 'Squad A' }, { id: 8, name: 'Squad B' }], total: 2, pageNum: 1, pageSize: 100 },
      })),
      http.get('/api/agents', ({ request }) => {
        seen.push(new URL(request.url).search);
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: [agentRow(1, 'Alpha', [7], ['Squad A'])],
        });
      }),
    );

    renderPage();
    expect(await screen.findByText('Alpha')).toBeInTheDocument();

    await user.click(screen.getByRole('combobox', { name: '按小队筛选' }));
    await user.click(await screen.findByText('Squad A', { selector: '.ant-select-item-option-content' }));

    await waitFor(() => expect(seen.some((query) => query.includes('squadIds=7'))).toBe(true));
    // axios' default array form (`squadIds[]=7`) is not bindable by Spring's @RequestParam List<Long>.
    expect(seen.some((query) => query.includes('squadIds%5B%5D'))).toBe(false);
  });

  it('opens grouped by default, buckets agents by squad and keeps unaffiliated ones visible', async () => {
    useSquadsAndAgents([
      agentRow(1, 'Alpha', [7], ['Squad A']),
      agentRow(2, 'Beta', [8], ['Squad B']),
      agentRow(3, 'Gamma', null, null),
    ]);

    const { container } = renderPage();
    expect(await screen.findByText('Alpha')).toBeInTheDocument();

    await waitFor(() => expect(container.querySelectorAll('.agent-squad-group-header')).toHaveLength(3));
    const headers = [...container.querySelectorAll('.agent-squad-group-header')]
      .map((header) => header.textContent ?? '');
    expect(headers[0]).toContain('Squad A');
    expect(headers[1]).toContain('Squad B');
    expect(headers[2]).toContain('未分组');
    expect(headers[2]).toContain('1 个');
    // An agent in two squads shows up under both, so no group looks empty and none is dropped.
    expect(screen.getByText('Gamma')).toBeInTheDocument();
  });

  it('restores the stored flat list view and persists every toggle', async () => {
    const user = userEvent.setup();
    window.localStorage.setItem('autowonder.agents.view', 'list');
    useSquadsAndAgents([
      agentRow(1, 'Alpha', [7], ['Squad A']),
      agentRow(2, 'Beta', [8], ['Squad B']),
      agentRow(3, 'Gamma', null, null),
    ]);

    const { container } = renderPage();
    expect(await screen.findByText('Alpha')).toBeInTheDocument();
    expect(container.querySelectorAll('.agent-squad-group-header')).toHaveLength(0);

    await user.click(screen.getByText('分组'));

    await waitFor(() => expect(container.querySelectorAll('.agent-squad-group-header')).toHaveLength(3));
    expect(window.localStorage.getItem('autowonder.agents.view')).toBe('grouped');

    await user.click(screen.getByText('列表'));

    await waitFor(() => expect(container.querySelectorAll('.agent-squad-group-header')).toHaveLength(0));
    expect(window.localStorage.getItem('autowonder.agents.view')).toBe('list');
  });

  it('falls back to the grouped view when the stored view value is invalid', async () => {
    window.localStorage.setItem('autowonder.agents.view', 'bogus');
    useSquadsAndAgents([agentRow(1, 'Alpha', [7], ['Squad A'])]);

    const { container } = renderPage();
    expect(await screen.findByText('Alpha')).toBeInTheDocument();

    await waitFor(() => expect(container.querySelectorAll('.agent-squad-group-header')).toHaveLength(1));
    expect(container.querySelector('.agent-squad-group-header')).toHaveTextContent('Squad A');
  });
});
