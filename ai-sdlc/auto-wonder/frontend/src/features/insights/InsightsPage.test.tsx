import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { InsightsPage } from './InsightsPage';

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <InsightsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

// The page defaults to the realtime tab, which mounts RealtimeDashboard and
// fires /api/dashboard/realtime on render. These tests target the audit tab,
// so stub the realtime poll to keep MSW's strict handler check happy.
const realtimeHandler = http.get('/api/dashboard/realtime', () =>
  HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: null }),
);

describe('InsightsPage', () => {
  it('links worker and time filters into audit requests', async () => {
    const metricsRequests: string[] = [];
    const auditRequests: string[] = [];
    server.use(
      realtimeHandler,
      http.get('/api/insights/metrics', ({ request }) => {
        metricsRequests.push(new URL(request.url).search);
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: {
            cost: { totalTokens: 100000, avgTokensPerTask: 5000, dailyAvg: 14000, trend: [10, 12, 11, 13, 14, 12, 15] },
            efficiency: { completionRate: 80, totalTasks: 20, completedTasks: 16, avgDurationMinutes: 30, trend: [70, 72, 75, 77, 78, 79, 80] },
            stability: { successRate: 90, retryCount: 2, blockedCount: 1, trend: [88, 89, 90, 91, 90, 89, 90] },
            security: { highRiskOps: 1, complianceRate: 98, auditBlocks: 0, trend: [0, 1, 0, 1, 0, 0, 1] },
          },
        });
      }),
      http.get('/api/insights/workers', () => {
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: [{ id: 12, name: '验收数字员工' }],
        });
      }),
      http.get('/api/insights/audit', ({ request }) => {
        auditRequests.push(new URL(request.url).search);
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: {
            items: [
              { timestamp: '2026-07-12 12:00:00', worker: '验收数字员工', operatorType: 'AGENT', eventType: 'REJECT', detail: 'aone#1 评审驳回', riskLevel: 'medium' },
              { timestamp: '2026-07-12 12:10:00', worker: '张三', operatorType: 'HUMAN', eventType: 'UPDATE', detail: 'aone#1 更新工单', riskLevel: 'low' },
            ],
            total: 2,
          },
        });
      }),
    );

    const user = userEvent.setup();
    renderPage();
    expect(await screen.findByText('数据洞察')).toBeInTheDocument();

    await user.click(screen.getByText('执行审计'));
    await user.click(screen.getByRole('combobox', { name: '数字员工' }));
    const workerOptions = await screen.findAllByText('验收数字员工');
    await user.click(workerOptions[workerOptions.length - 1]);
    await user.click(await screen.findByText('近7天'));

    expect(await screen.findByText('评审驳回')).toBeInTheDocument();
    expect(screen.getByText('张三')).toBeInTheDocument();
    expect(screen.getAllByText('操作人').length).toBeGreaterThan(0);
    const latestMetricsRequest = metricsRequests[metricsRequests.length - 1];
    const latestAuditRequest = auditRequests[auditRequests.length - 1];
    expect(latestMetricsRequest).toContain('worker_id=12');
    expect(latestMetricsRequest).toContain('time_range=7d');
    expect(latestAuditRequest).toContain('worker_id=12');
    expect(latestAuditRequest).toContain('time_range=7d');
  });

  it('keeps audit pagination when worker and time filters change', async () => {
    const auditRequests: string[] = [];
    server.use(
      realtimeHandler,
      http.get('/api/insights/metrics', () => {
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: {
            cost: { totalTokens: 100000, avgTokensPerTask: 5000, dailyAvg: 14000, trend: [10, 12, 11, 13, 14, 12, 15] },
            efficiency: { completionRate: 80, totalTasks: 20, completedTasks: 16, avgDurationMinutes: 30, trend: [70, 72, 75, 77, 78, 79, 80] },
            stability: { successRate: 90, retryCount: 2, blockedCount: 1, trend: [88, 89, 90, 91, 90, 89, 90] },
            security: { highRiskOps: 1, complianceRate: 98, auditBlocks: 0, trend: [0, 1, 0, 1, 0, 0, 1] },
          },
        });
      }),
      http.get('/api/insights/workers', () => {
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: [{ id: 12, name: '验收数字员工' }],
        });
      }),
      http.get('/api/insights/audit', ({ request }) => {
        const url = new URL(request.url);
        auditRequests.push(url.search);
        const page = Number(url.searchParams.get('page') ?? '1');
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: {
            items: [
              { timestamp: `2026-07-12 12:00:0${page}`, worker: '验收数字员工', eventType: 'REJECT', detail: `aone#${page} 评审驳回`, riskLevel: 'medium' },
            ],
            total: 51,
          },
        });
      }),
    );

    const user = userEvent.setup();
    renderPage();
    expect(await screen.findByText('数据洞察')).toBeInTheDocument();

    await user.click(screen.getByText('执行审计'));
    await user.click(screen.getByRole('combobox', { name: '数字员工' }));
    const workerOptions = await screen.findAllByText('验收数字员工');
    await user.click(workerOptions[workerOptions.length - 1]);
    await user.click(await screen.findByText('近7天'));
    await user.click(await screen.findByTitle('2'));

    expect(await screen.findByText('评审驳回')).toBeInTheDocument();
    expect(auditRequests[auditRequests.length - 1]).toContain('page=2');
    expect(auditRequests[auditRequests.length - 1]).toContain('worker_id=12');
    expect(auditRequests[auditRequests.length - 1]).toContain('time_range=7d');

    await user.click(screen.getByText('高危', { selector: '.ant-segmented-item-label' }));
    await waitFor(() => {
      const request = auditRequests[auditRequests.length - 1];
      expect(request).toContain('risk_level=high');
      expect(request).toContain('page=1&');
      expect(request).toContain('worker_id=12');
      expect(request).toContain('time_range=7d');
    });
  });
});

const realtimeDashboardPayload = {
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

const costPayload = {
  totalTokens: 100000, avgTokensPerTask: 5000, dailyAvg: 14000, trend: [10, 12, 11, 13, 14, 12, 15],
  totalCredits: 82.61, avgCreditsPerTask: 12.5, dailyAvgCredits: 2.75, creditsTrend: [1.2, 2.5, 3.1, 4, 5.5, 6.2, 7.1],
};

function ok(data: unknown) {
  return { success: true, code: '0', message: '', traceId: null, data };
}

function metricsPayload(cost: unknown = costPayload) {
  return {
    cost,
    efficiency: { completionRate: 80, totalTasks: 20, completedTasks: 16, avgDurationMinutes: 30, trend: [70, 72, 75, 77, 78, 79, 80] },
    stability: { successRate: 90, retryCount: 2, blockedCount: 1, trend: [88, 89, 90, 91, 90, 89, 90] },
    security: { highRiskOps: 1, complianceRate: 98, auditBlocks: 0, trend: [0, 1, 0, 1, 0, 0, 1] },
  };
}

const realtimeDataHandler = http.get('/api/dashboard/realtime', () =>
  HttpResponse.json(ok(realtimeDashboardPayload)),
);

const insightsWorkersHandler = http.get('/api/insights/workers', () =>
  HttpResponse.json(ok([{ id: 12, name: '验收数字员工' }])),
);

// 页面级审计查询与 tab 无关，始终随 InsightsPage 挂载发起，必须一并打桩。
function insightsAuditHandler(requests: string[] = []) {
  return http.get('/api/insights/audit', ({ request }) => {
    requests.push(new URL(request.url).search);
    return HttpResponse.json(ok({ items: [], total: 0 }));
  });
}

function insightsMetricsHandler(requests: string[], cost?: unknown) {
  return http.get('/api/insights/metrics', ({ request }) => {
    requests.push(new URL(request.url).search);
    return HttpResponse.json(ok(metricsPayload(cost)));
  });
}

describe('InsightsPage realtime credits cost', () => {
  beforeEach(() => {
    // 偏好按用户维度落在 localStorage：切 tab / 筛选会跨用例残留，这里逐个用例复位。
    window.localStorage.clear();
  });

  it('renders the credits cost card on the default realtime dashboard path', async () => {
    const metricsRequests: string[] = [];
    server.use(
      realtimeDataHandler,
      insightsWorkersHandler,
      insightsMetricsHandler(metricsRequests),
      insightsAuditHandler(),
    );

    const user = userEvent.setup();
    renderPage();

    const region = await screen.findByRole('region', { name: 'Credits 成本' });
    expect(await within(region).findByText('总 Credits')).toBeInTheDocument();
    expect(within(region).getByText('82.61')).toBeInTheDocument();
    expect(within(region).getByText('12.5')).toBeInTheDocument();
    expect(within(region).getByText('2.75')).toBeInTheDocument();

    // 实时看板既有 KPI 与成本卡同屏可见
    expect(await screen.findByText('正在运行')).toBeInTheDocument();
    // 原指标页的合成卡片不得出现在实时看板
    expect(screen.queryByText('效率')).not.toBeInTheDocument();
    expect(screen.queryByText('稳定')).not.toBeInTheDocument();
    expect(screen.queryByText('安全')).not.toBeInTheDocument();
    // 默认按近 30 天、全部数字员工取数
    const latestMetricsRequest = metricsRequests[metricsRequests.length - 1];
    expect(latestMetricsRequest).toContain('time_range=30d');
    expect(latestMetricsRequest).not.toContain('worker_id');

    await user.hover(within(region).getByText('总 Credits'));
    expect(await screen.findByText('总 Token:')).toBeInTheDocument();
    expect(screen.getByText('100K')).toBeInTheDocument();
    expect(screen.getByText('5.0K')).toBeInTheDocument();
    expect(screen.getByText('14K')).toBeInTheDocument();
  });

  it('renders zero credits without NaN when there is no usage data', async () => {
    server.use(
      realtimeDataHandler,
      insightsWorkersHandler,
      insightsMetricsHandler([], {
        ...costPayload,
        totalTokens: 0, avgTokensPerTask: 0, dailyAvg: 0, trend: [0, 0, 0, 0, 0, 0, 0],
        totalCredits: 0, avgCreditsPerTask: 0, dailyAvgCredits: 0, creditsTrend: [0, 0, 0, 0, 0, 0, 0],
      }),
      insightsAuditHandler(),
    );

    renderPage();

    const region = await screen.findByRole('region', { name: 'Credits 成本' });
    await waitFor(() => expect(within(region).getAllByText('0')).toHaveLength(3));
    expect(screen.queryByText(/NaN/)).not.toBeInTheDocument();
  });

  it('scopes the cost filters to the cost query and leaves audit preferences untouched', async () => {
    const metricsRequests: string[] = [];
    const auditRequests: string[] = [];
    server.use(
      realtimeDataHandler,
      insightsWorkersHandler,
      insightsMetricsHandler(metricsRequests),
      insightsAuditHandler(auditRequests),
    );

    const user = userEvent.setup();
    renderPage();

    const region = await screen.findByRole('region', { name: 'Credits 成本' });
    await user.click(within(region).getByRole('combobox', { name: '成本数字员工' }));
    const workerOptions = await screen.findAllByText('验收数字员工');
    await user.click(workerOptions[workerOptions.length - 1]);
    await user.click(within(region).getByText('近7天'));

    await waitFor(() => {
      const latestMetricsRequest = metricsRequests[metricsRequests.length - 1];
      expect(latestMetricsRequest).toContain('worker_id=12');
      expect(latestMetricsRequest).toContain('time_range=7d');
    });

    // 成本筛选写的是 realtime.cost.*，切到执行审计后 audit.* 仍是默认值
    await user.click(screen.getByText('执行审计'));
    await waitFor(() => {
      const latestAuditRequest = auditRequests[auditRequests.length - 1];
      expect(latestAuditRequest).toContain('time_range=30d');
      expect(latestAuditRequest).not.toContain('worker_id');
    });
  });

  it('keeps the realtime dashboard usable when the cost query fails', async () => {
    server.use(
      realtimeDataHandler,
      insightsWorkersHandler,
      http.get('/api/insights/metrics', () => HttpResponse.json(
        { success: false, code: '50000', message: 'metrics 聚合失败', data: null, traceId: null },
        { status: 500 },
      )),
      insightsAuditHandler(),
    );

    renderPage();

    expect(await screen.findByText('Credits 成本加载失败')).toBeInTheDocument();
    expect(screen.getByText('metrics 聚合失败')).toBeInTheDocument();
    expect(screen.queryByText('总 Credits')).not.toBeInTheDocument();

    // 成本接口失败不影响实时看板其余模块
    expect(await screen.findByText('正在运行')).toBeInTheDocument();
    expect(screen.getByText('工单存量')).toBeInTheDocument();
  });

  it('refetches the cost query from the dashboard refresh button', async () => {
    const metricsRequests: string[] = [];
    let realtimeRequests = 0;
    server.use(
      http.get('/api/dashboard/realtime', () => {
        realtimeRequests += 1;
        return HttpResponse.json(ok(realtimeDashboardPayload));
      }),
      insightsWorkersHandler,
      insightsMetricsHandler(metricsRequests),
      insightsAuditHandler(),
    );

    const user = userEvent.setup();
    renderPage();

    await screen.findByText('正在运行');
    const metricsRequestsBefore = metricsRequests.length;

    // antd 会用「ant-btn」在中文两字间插空格，且图标自带 aria-label，故用正则匹配
    await user.click(screen.getByRole('button', { name: /刷\s*新/ }));

    await waitFor(() => expect(metricsRequests).toHaveLength(metricsRequestsBefore + 1));
    expect(realtimeRequests).toBeGreaterThanOrEqual(2);
  });
});
