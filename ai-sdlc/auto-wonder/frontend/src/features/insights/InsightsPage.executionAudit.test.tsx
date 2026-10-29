import { beforeEach, describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import dayjs from 'dayjs';
import { server } from '@/test/mocks/server';
import { InsightsPage } from './InsightsPage';

const ok = (data: unknown) => HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data });

const metricsPayload = {
  cost: { totalTokens: 100000, avgTokensPerTask: 5000, dailyAvg: 14000, trend: [10, 12, 11, 13, 14, 12, 15] },
  efficiency: { completionRate: 80, totalTasks: 20, completedTasks: 16, avgDurationMinutes: 30, trend: [70, 72, 75, 77, 78, 79, 80] },
  stability: { successRate: 90, retryCount: 2, blockedCount: 1, trend: [88, 89, 90, 91, 90, 89, 90] },
  security: { highRiskOps: 1, complianceRate: 98, auditBlocks: 0, trend: [0, 1, 0, 1, 0, 0, 1] },
};

const overviewPayload = {
  kpi: { todayCreatedWorkitems: 2, runningWorkitems: 2, todayCompletedTasks: 5, weekCompletedTasks: 20 },
  inventory: { byLifecycle: { init: 1, inProgress: 2, done: 3, canceled: 4 }, byType: { req: 1, task: 2, bug: 3 } },
  generatedAt: '2026-09-29T02:00:00Z',
};

const pipelineHealthPayload = {
  range: 'today', successRate: 95, successSub: { succeeded: 19, total: 20 }, failedOrTimeout: 0,
  rework: { rate: 10, reworkedWorkitems: 1, executedWorkitems: 10, retryCount: 1 },
  avgWorkitemDurationMinutes: 30, generatedAt: '2026-09-29T02:00:00Z',
};

const realtimePayload = {
  kpi: {
    runningDispatches: 2, todayCompletedTasks: 5, weekCompletedTasks: 20, avgTaskDurationMinutes: 42,
    inProgressWorkitems: 3, queuedDispatches: 1, activeSquads: 2, onlineAgents: 4, avgLoad: 0.5,
  },
  inventory: { byLifecycle: { init: 1, inProgress: 2, done: 3, canceled: 4 }, byType: { req: 1, task: 2, bug: 3 } },
  squads: [],
  workstations: [],
  health: { successRate: 95, failedOrTimeout: 0, retries: 1, avgDurationMinutes: 30 },
  runningFeed: [],
  recentFeed: [],
  generatedAt: '2026-09-29T02:00:00Z',
};

const requests = { metrics: [] as string[], audit: [] as string[] };

function stubInsights() {
  requests.metrics.length = 0;
  requests.audit.length = 0;
  server.use(
    http.get('/api/dashboard/realtime', () => ok(realtimePayload)),
    http.get('/api/dashboard/overview', () => ok(overviewPayload)),
    http.get('/api/dashboard/pipeline-health', () => ok(pipelineHealthPayload)),
    http.get('/api/insights/metrics', ({ request }) => {
      requests.metrics.push(new URL(request.url).search);
      return ok(metricsPayload);
    }),
    http.get('/api/insights/workers', () => ok([{ id: 12, name: '验收数字员工' }])),
    http.get('/api/insights/audit', ({ request }) => {
      requests.audit.push(new URL(request.url).search);
      return ok({ items: [], total: 0 });
    }),
  );
}

function renderInsights() {
  const router = createMemoryRouter([
    { path: '/insights', element: <InsightsPage /> },
    { path: '/audit-logs', element: <div>审计日志落点</div> },
  ], { initialEntries: ['/insights'] });
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>);
  return router;
}

const pref = (name: string, value: unknown) => window.localStorage.setItem(`aw:insights:v1:anonymous:none:${name}`, JSON.stringify(value));

describe('InsightsPage execution audit handoff', () => {
  beforeEach(() => {
    window.localStorage.clear();
    stubInsights();
  });

  it('redirects the retired audit tab to audit logs with mapped filters', async () => {
    pref('tab', 'audit');
    pref('audit.worker', 12);
    pref('audit.range', '7d');
    pref('audit.risk', 'high');

    const router = renderInsights();

    expect(await screen.findByText('审计日志落点')).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/audit-logs');
    const params = new URLSearchParams(router.state.location.search);
    expect(params.get('actorType')).toBe('AGENT');
    expect(params.get('actorId')).toBe('12');
    expect(params.get('riskLevel')).toBe('high');
    expect(params.get('endTime')).toBe(new Date(`${dayjs().format('YYYY-MM-DD')}T23:59:59.999+08:00`).toISOString());
    expect(params.get('startTime')).toBe(new Date(`${dayjs().subtract(6, 'day').format('YYYY-MM-DD')}T00:00:00+08:00`).toISOString());
    // 重定向优先于默认 tab 渲染，页面不得先渲染洞察内容再跳转。
    expect(screen.queryByText('数据洞察')).toBeNull();
  });

  it('keeps the page-level audit and metrics summaries running once during the redirect', async () => {
    pref('tab', 'audit');

    renderInsights();

    expect(await screen.findByText('审计日志落点')).toBeInTheDocument();
    expect(requests.audit).toHaveLength(1);
    expect(requests.metrics).toHaveLength(1);
    expect(requests.audit[0]).toContain('time_range=30d');
    expect(requests.metrics[0]).toContain('time_range=30d');
  });

  it('omits actor filters and empty risk when only the audit tab preference exists', async () => {
    pref('tab', 'audit');

    const router = renderInsights();

    expect(await screen.findByText('审计日志落点')).toBeInTheDocument();
    const params = new URLSearchParams(router.state.location.search);
    expect(params.get('actorType')).toBeNull();
    expect(params.get('actorId')).toBeNull();
    expect(params.get('riskLevel')).toBeNull();
    expect(params.get('startTime')).not.toBeNull();
    expect(params.get('endTime')).not.toBeNull();
  });

  it('keeps the worker filter without a risk level when the stored risk is empty', async () => {
    pref('tab', 'audit');
    pref('audit.worker', 12);
    pref('audit.risk', '');

    const router = renderInsights();

    expect(await screen.findByText('审计日志落点')).toBeInTheDocument();
    const params = new URLSearchParams(router.state.location.search);
    expect(params.get('actorId')).toBe('12');
    expect(params.get('riskLevel')).toBeNull();
  });

  it('shows the four remaining tabs without an audit entry and stays on insights', async () => {
    pref('tab', 'realtime');

    const router = renderInsights();

    expect(await screen.findByText('数据洞察')).toBeInTheDocument();
    expect(screen.queryByText('执行审计')).toBeNull();
    for (const label of ['实时看板', '人机协作', '工单交付', '阶段耗时']) {
      expect(screen.getByRole('radio', { name: label })).toBeInTheDocument();
    }
    expect(screen.queryByRole('radio', { name: '需求交付' })).toBeNull();
    expect(router.state.location.pathname).toBe('/insights');
  });

  it('keeps retired audit preferences without redirecting while another tab is active', async () => {
    pref('tab', 'metrics');
    pref('audit.worker', 12);

    const router = renderInsights();

    expect(await screen.findByText('数据洞察')).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/insights');
    expect(window.localStorage.getItem('aw:insights:v1:anonymous:none:audit.worker')).toBe('12');
  });
});
