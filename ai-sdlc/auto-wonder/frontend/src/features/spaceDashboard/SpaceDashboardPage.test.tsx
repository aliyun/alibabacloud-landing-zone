import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import dayjs from 'dayjs';
import { server } from '@/test/mocks/server';
import { useAuthStore } from '@/shared/auth/store';
import { SpaceDashboardPage } from './SpaceDashboardPage';
import type { SpaceDashboardOverview } from './types';

const envelope = (data: unknown) => ({ success: true, code: '0', message: '', traceId: null, data });

function payload(overrides: Partial<SpaceDashboardOverview> = {}): SpaceDashboardOverview {
  return {
    platformAdmin: false,
    workspaceCount: 5,
    workspaces: [
      { id: 1, name: '交付一队' },
      { id: 2, name: '自动化平台' },
      { id: 3, name: '运维小组' },
    ],
    agents: { workspaceCount: 5, agentCount: 37, humanCount: 14, squadCount: 9 },
    delivery: {
      window: { start: '2026-09-27', end: '2026-10-26', workTypes: ['REQ', 'BUG', 'TASK'] },
      workitemCount: 320,
      completedCount: 180,
      inProgressCount: 95,
      topCompleted: [{ workspaceId: 2, workspaceName: '自动化平台', value: 62 }],
      topCompletedTotal: 1,
      bottomCompleted: [{ workspaceId: 3, workspaceName: '运维小组', value: 0 }],
      bottomCompletedTotal: 1,
    },
    cost: {
      window: { start: '2026-09-27', end: '2026-10-26' },
      totalCredits: 48213.55,
      dailyAverage: 1607.12,
      completedWorkitemCount: 180,
      perCompletedWorkitem: 267.85,
      topCredits: [{ workspaceId: 2, workspaceName: '自动化平台', value: 18234.11 }],
      topCreditsTotal: 1,
      bottomCredits: [{ workspaceId: 3, workspaceName: '运维小组', value: 0 }],
      bottomCreditsTotal: 1,
    },
    ...overrides,
  };
}

function LocationProbe() {
  const location = useLocation();
  return <span data-testid="location">{location.pathname}{location.search}</span>;
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/workspaces/dashboard']}>
        <Routes>
          <Route path="/workspaces/dashboard" element={<SpaceDashboardPage />} />
          <Route path="/workspaces" element={<div data-testid="workspace-list" />} />
          <Route path="/insights" element={<div data-testid="insights-page" />} />
        </Routes>
        <LocationProbe />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('SpaceDashboardPage', () => {
  const requests: string[] = [];

  function mockOverview(data: SpaceDashboardOverview) {
    server.use(http.get('/api/space-dashboard/overview', ({ request }) => {
      requests.push(new URL(request.url).search);
      return HttpResponse.json(envelope(data));
    }));
  }

  function lastParams(): URLSearchParams {
    return new URLSearchParams(requests.at(-1));
  }

  beforeEach(() => {
    requests.length = 0;
    localStorage.clear();
    useAuthStore.getState().clear();
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it('renders the three sections with the default 30-day windows ending yesterday', async () => {
    mockOverview(payload());

    renderPage();

    expect(await screen.findByTestId('space-dashboard-page')).toBeInTheDocument();
    expect(screen.getByTestId('space-dashboard-scope')).toHaveTextContent('统计范围：你管理的 5 个空间');
    expect(screen.getByTestId('stat-agent-count')).toHaveTextContent('37');
    expect(screen.getByTestId('stat-human-count')).toHaveTextContent('14');
    expect(screen.getByTestId('stat-squad-count')).toHaveTextContent('9');
    expect(screen.getByTestId('stat-workitem-count')).toHaveTextContent('320');
    expect(screen.getByTestId('stat-completed-count')).toHaveTextContent('180');
    expect(screen.getByTestId('stat-in-progress-count')).toHaveTextContent('95');
    expect(screen.getByTestId('stat-total-credits')).toHaveTextContent('48,213.55');
    expect(screen.getByTestId('stat-daily-average')).toHaveTextContent('1,607.12');
    expect(screen.getByTestId('stat-per-workitem')).toHaveTextContent('267.85');
    expect(screen.getByTestId('rank-top-completed-item-2')).toHaveTextContent('自动化平台');
    expect(screen.getByTestId('rank-bottom-completed-item-3')).toHaveTextContent('运维小组');

    const params = lastParams();
    expect(params.get('deliveryStart')).toBe(dayjs().subtract(30, 'day').format('YYYY-MM-DD'));
    expect(params.get('deliveryEnd')).toBe(dayjs().subtract(1, 'day').format('YYYY-MM-DD'));
    expect(params.get('costStart')).toBe(dayjs().subtract(30, 'day').format('YYYY-MM-DD'));
    expect(params.get('costEnd')).toBe(dayjs().subtract(1, 'day').format('YYYY-MM-DD'));
    expect(params.get('workTypes')).toBe('REQ,BUG,TASK');
  });

  it('renders the platform-wide scope copy for a platform admin', async () => {
    mockOverview(payload({ platformAdmin: true, workspaceCount: 32 }));

    renderPage();

    const scope = await screen.findByTestId('space-dashboard-scope');
    expect(scope).toHaveTextContent('统计范围：全平台 32 个空间');
    expect(scope).toHaveTextContent('已排除停用空间与回收站空间');
    expect(scope).not.toHaveTextContent('你管理的');
  });

  it('narrows the delivery section by work type while the cost section stays unchanged', async () => {
    mockOverview(payload());
    renderPage();
    await screen.findByTestId('space-dashboard-page');

    // 取消 BUG 后再勾回：覆盖 checked=true 的累加分支（QA-F2）。
    await userEvent.click(screen.getByTestId('space-dashboard-work-type-BUG'));
    await waitFor(() => expect(lastParams().get('workTypes')).toBe('REQ,TASK'));
    await userEvent.click(screen.getByTestId('space-dashboard-work-type-BUG'));
    await waitFor(() => expect(lastParams().get('workTypes')).toBe('REQ,TASK,BUG'));
    expect(screen.getByTestId('space-dashboard-work-type-BUG')).toHaveClass('ant-tag-checkable-checked');

    await userEvent.click(screen.getByTestId('space-dashboard-work-type-REQ'));
    await waitFor(() => expect(lastParams().get('workTypes')).toBe('TASK,BUG'));

    await userEvent.click(screen.getByTestId('space-dashboard-work-type-TASK'));
    await waitFor(() => expect(lastParams().get('workTypes')).toBe('BUG'));
    expect(screen.getByTestId('stat-total-credits')).toHaveTextContent('48,213.55');
    // 成本窗口参数不随工单类型筛选变化。
    expect(lastParams().get('costStart')).toBe(dayjs().subtract(30, 'day').format('YYYY-MM-DD'));

    const settled = requests.length;
    await userEvent.click(screen.getByTestId('space-dashboard-work-type-BUG'));
    expect(requests).toHaveLength(settled);
    expect(screen.getByTestId('space-dashboard-work-type-BUG')).toHaveClass('ant-tag-checkable-checked');
  });

  it('shows a placeholder when no workitem completed in the delivery window', async () => {
    mockOverview(payload({
      cost: {
        ...payload().cost,
        completedWorkitemCount: 0,
        perCompletedWorkitem: null,
      },
    }));

    renderPage();

    expect(await screen.findByTestId('stat-per-workitem')).toHaveTextContent('--');
    expect(screen.getByTestId('stat-per-workitem')).toHaveTextContent('分母：交付窗口内无已完成工单');
  });

  it('renders the forbidden state when the caller has no manageable workspace', async () => {
    server.use(http.get('/api/space-dashboard/overview', () => HttpResponse.json(
      { success: false, code: '10403', message: '无权限', traceId: null, data: null },
      { status: 403 },
    )));

    renderPage();

    expect(await screen.findByTestId('space-dashboard-forbidden')).toBeInTheDocument();
    expect(screen.getByText('无权限访问空间级看板')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: '返回工作空间列表' }));
    expect(screen.getByTestId('location')).toHaveTextContent('/workspaces');
  });

  it('shows an error state and recovers after retry', async () => {
    let calls = 0;
    server.use(http.get('/api/space-dashboard/overview', () => {
      calls += 1;
      if (calls === 1) {
        return HttpResponse.json(
          { success: false, code: '10000', message: '服务器异常', traceId: null, data: null },
          { status: 500 },
        );
      }
      return HttpResponse.json(envelope(payload()));
    }));

    renderPage();

    expect(await screen.findByTestId('space-dashboard-error')).toBeInTheDocument();
    await userEvent.click(screen.getByTestId('space-dashboard-retry'));
    expect(await screen.findByTestId('space-dashboard-page')).toBeInTheDocument();
  });

  it('switches to the clicked workspace and opens its insights page', async () => {
    mockOverview(payload());
    server.use(http.post('/api/workspaces/2/switch', () => HttpResponse.json(
      envelope({ accessToken: 'switched-token', accessLevel: 'ADMIN' }),
    )));

    renderPage();

    await userEvent.click(await screen.findByTestId('rank-top-completed-item-2'));

    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('/insights'));
    expect(useAuthStore.getState().currentWorkspace?.id).toBe(2);
    expect(useAuthStore.getState().accessToken).toBe('switched-token');
  });

  it('keeps the dashboard open when the workspace switch fails', async () => {
    mockOverview(payload());
    server.use(http.post('/api/workspaces/2/switch', () => HttpResponse.json(
      { success: false, code: '10000', message: '切换失败，请稍后重试', traceId: null, data: null },
      { status: 400 },
    )));

    renderPage();

    await userEvent.click(await screen.findByTestId('rank-top-completed-item-2'));

    expect(await screen.findByText('切换失败，请稍后重试')).toBeInTheDocument();
    expect(screen.getByTestId('location')).toHaveTextContent('/workspaces/dashboard');
  });

  it('ignores ranks whose workspace left the scope before the click', async () => {
    const base = payload();
    mockOverview({
      ...base,
      delivery: {
        ...base.delivery,
        topCompleted: [{ workspaceId: 99, workspaceName: '已移除空间', value: 5 }],
      },
    });
    const switchCalls = vi.fn();
    server.use(http.post('/api/workspaces/:id/switch', ({ params }) => {
      switchCalls(params.id);
      return HttpResponse.json(envelope({ accessToken: 't', accessLevel: 'ADMIN' }));
    }));

    renderPage();

    await userEvent.click(await screen.findByTestId('rank-top-completed-item-99'));

    expect(switchCalls).not.toHaveBeenCalled();
    expect(screen.getByTestId('location')).toHaveTextContent('/workspaces/dashboard');
  });
});
