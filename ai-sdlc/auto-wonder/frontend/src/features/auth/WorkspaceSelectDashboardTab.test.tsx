import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { useAuthStore } from '@/shared/auth/store';
import { WorkspaceSelectPage } from './WorkspaceSelectPage';

const envelope = (data: unknown) => ({ success: true, code: '0', message: '', traceId: null, data });

const MANAGEABLE_WORKSPACE = { id: 1, name: '星云工坊', description: '可管理', canManage: true };
const MEMBER_WORKSPACE = { id: 1, name: '星云工坊', description: '只读成员', canManage: false };

function mockMine(data: unknown[]) {
  server.use(http.get('/api/workspaces/mine', () => HttpResponse.json(envelope(data))));
}

function mockOverview(overrides: Record<string, unknown> = {}) {
  server.use(http.get('/api/space-dashboard/overview', () => HttpResponse.json(envelope({
    platformAdmin: false,
    workspaceCount: 1,
    workspaces: [{ id: 1, name: '星云工坊' }],
    agents: { workspaceCount: 1, agentCount: 3, humanCount: 2, squadCount: 1 },
    delivery: {
      window: { start: '2026-09-27', end: '2026-10-26', workTypes: ['REQ', 'BUG', 'TASK'] },
      workitemCount: 0,
      completedCount: 0,
      inProgressCount: 0,
      topCompleted: [],
      topCompletedTotal: 0,
      bottomCompleted: [{ workspaceId: 1, workspaceName: '星云工坊', value: 0 }],
      bottomCompletedTotal: 1,
    },
    cost: {
      window: { start: '2026-09-27', end: '2026-10-26' },
      totalCredits: 0,
      dailyAverage: 0,
      completedWorkitemCount: 0,
      perCompletedWorkitem: null,
      topCredits: [],
      topCreditsTotal: 0,
      bottomCredits: [{ workspaceId: 1, workspaceName: '星云工坊', value: 0 }],
      bottomCreditsTotal: 1,
    },
    ...overrides,
  }))));
}

function LocationProbe() {
  const location = useLocation();
  return <span data-testid="location">{location.pathname}{location.search}</span>;
}

function renderAt(path: string) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[path]}>
        <WorkspaceSelectPage />
        <LocationProbe />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('WorkspaceSelectPage dashboard tab', () => {
  beforeEach(() => {
    localStorage.clear();
    useAuthStore.getState().clear();
  });

  it.each(['/workspaces?tab=dashboard', '/workspaces/dashboard'])(
    'shows the forbidden state instead of falling back when a member opens %s',
    async (path) => {
      const overviewCalls = vi.fn();
      server.use(http.get('/api/space-dashboard/overview', () => {
        overviewCalls();
        return HttpResponse.json(envelope({}));
      }));
      useAuthStore.getState().setUser({ id: 5, username: 'member', nickname: '成员', email: 'member@example.com' });
      mockMine([MEMBER_WORKSPACE]);

      renderAt(path);

      expect(await screen.findByTestId('space-dashboard-forbidden')).toBeInTheDocument();
      expect(screen.getByText('无权限访问空间级看板')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: '返回工作空间列表' })).toBeInTheDocument();
      // 无权限时不渲染任何统计内容，也不发起看板请求。
      expect(screen.queryByTestId('workspace-select-grid')).toBeNull();
      expect(screen.queryByTestId('space-dashboard-page')).toBeNull();
      expect(screen.queryByTestId('workspace-dashboard-tab')).toBeNull();
      expect(overviewCalls).not.toHaveBeenCalled();
      expect(screen.getByTestId('location').textContent).toBe(path);
    },
  );

  it('returns to the workspace list from the forbidden state', async () => {
    useAuthStore.getState().setUser({ id: 5, username: 'member', nickname: '成员', email: 'member@example.com' });
    mockMine([MEMBER_WORKSPACE]);

    renderAt('/workspaces/dashboard');

    await userEvent.click(await screen.findByRole('button', { name: '返回工作空间列表' }));

    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('/workspaces'));
  });

  it('opens the dashboard from the tab and syncs both directions with the url', async () => {
    useAuthStore.getState().setUser({ id: 5, username: 'admin', nickname: '管理员', email: 'admin@example.com' });
    mockMine([MANAGEABLE_WORKSPACE]);
    mockOverview();

    renderAt('/workspaces');

    await userEvent.click(await screen.findByTestId('workspace-dashboard-tab'));

    expect(await screen.findByTestId('space-dashboard-page')).toBeInTheDocument();
    expect(screen.getByTestId('location')).toHaveTextContent('/workspaces/dashboard');

    await userEvent.click(screen.getByRole('tab', { name: '我的工作空间' }));
    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('/workspaces?tab=mine'));
  });

  it('opens the dashboard directly through its deep link for a platform admin', async () => {
    useAuthStore.getState().setUser({ id: 5, username: 'platform-admin', nickname: '平台管理员', email: 'pa@example.com', isAdmin: true });
    mockMine([]);
    mockOverview({ platformAdmin: true, workspaceCount: 3 });

    renderAt('/workspaces/dashboard');

    expect(await screen.findByTestId('space-dashboard-page')).toBeInTheDocument();
    expect(screen.getByTestId('workspace-dashboard-tab')).toBeInTheDocument();
    expect(screen.getByTestId('space-dashboard-scope')).toHaveTextContent('统计范围：全平台 3 个空间');
    expect(screen.getByTestId('location')).toHaveTextContent('/workspaces/dashboard');
  });
});
