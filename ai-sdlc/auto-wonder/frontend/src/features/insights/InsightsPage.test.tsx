import { describe, it, expect, beforeEach } from 'vitest';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { InsightsPage } from './InsightsPage';

// antd Tooltip 延迟出现，负向断言必须等过该延迟，否则回归会被漏过。
async function waitPastTooltipDelay() {
  await act(async () => { await new Promise((resolve) => setTimeout(resolve, 300)); });
}

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

// 页面默认 realtime tab 会挂载 RealtimeDashboard 并触发快照、流水线、实时与数字员工查询；
// 各用例按需显式打桩，避免 MSW 严格模式报未处理请求。
const overviewHandler = http.get('/api/dashboard/overview', () =>
  HttpResponse.json(ok(overviewDashboardPayload)),
);

const pipelineHealthHandler = http.get('/api/dashboard/pipeline-health', () =>
  HttpResponse.json(ok(pipelineHealthPayload)),
);

beforeEach(() => {
  window.localStorage.clear();
});

describe('InsightsPage', () => {
  it('falls back from the retired requirement delivery tab to work item delivery and rewrites the preference', async () => {
    const legacyRequests: string[] = [];
    server.use(
      http.get('/api/dashboard/realtime', () => HttpResponse.json(ok(realtimeDashboardPayload))),
      overviewHandler,
      pipelineHealthHandler,
      http.get('/api/insights/metrics', () => HttpResponse.json(ok(metricsPayload()))),
      http.get('/api/insights/audit', () => HttpResponse.json(ok({ items: [], total: 0 }))),
      http.get('/api/insights/workers', () => HttpResponse.json(ok([{ id: 12, name: '验收数字员工' }]))),
      http.get('/api/insights/member-delivery', () => HttpResponse.json(ok(memberDeliveryPayload))),
      // 下线后页面（含切换 tab）不得再请求需求交付接口：命中即记录，断言为零。
      http.get('/api/insights/requirement-delivery', ({ request }) => {
        legacyRequests.push(new URL(request.url).search);
        return HttpResponse.json(ok(null));
      }),
    );
    window.localStorage.setItem('aw:insights:v1:anonymous:none:tab', JSON.stringify('delivery'));

    const user = userEvent.setup();
    const { container } = renderPage();

    // 首帧即按「工单交付」渲染，旧偏好在一次 effect 后写回 members
    expect(await screen.findByText('所选周期统计')).toBeInTheDocument();
    expect(await screen.findByText('成员甲')).toBeInTheDocument();
    const tabBar = container.querySelector('.insights-page .ant-segmented') as HTMLElement;
    expect(tabBar.querySelector('.ant-segmented-item-selected')?.textContent).toBe('工单交付');
    expect(screen.queryByRole('radio', { name: '需求交付' })).toBeNull();
    await waitFor(() => expect(window.localStorage.getItem('aw:insights:v1:anonymous:none:tab')).toBe('"members"'));

    // antd Segmented 的隐藏 input 带 pointer-events:none，点击可见的标签文本切换
    await user.click(screen.getByText('实时看板'));
    expect(await screen.findByText('正在运行')).toBeInTheDocument();
    expect(legacyRequests).toHaveLength(0);
  });
});

const memberDeliveryPayload = {
  summary: { created: 1, total: 1, completed: 1, inProgress: 0, notStarted: 0, pendingDecision: 0, requirements: 0 },
  members: [{ memberId: 1, memberName: '成员甲', created: 1, total: 1, completed: 1, inProgress: 0, notStarted: 0, pendingDecision: 0, requirements: 0 }],
};

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

const overviewDashboardPayload = {
  kpi: { todayCreatedWorkitems: 2, runningWorkitems: 2, todayCompletedTasks: 5, weekCompletedTasks: 20 },
  inventory: { byLifecycle: { init: 1, inProgress: 2, done: 3, canceled: 4 }, byType: { req: 1, task: 2, bug: 3 } },
  generatedAt: '2026-09-29T02:00:00Z',
};

const pipelineHealthPayload = {
  range: 'today', successRate: 95, successSub: { succeeded: 19, total: 20 }, failedOrTimeout: 0,
  rework: { rate: 10, reworkedWorkitems: 1, executedWorkitems: 10, retryCount: 1 },
  avgWorkitemDurationMinutes: 30, generatedAt: '2026-09-29T02:00:00Z',
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

function insightsMetricsHandler(requests: string[] = []) {
  return http.get('/api/insights/metrics', ({ request }) => {
    requests.push(new URL(request.url).search);
    return HttpResponse.json(ok(metricsPayload()));
  });
}

const realtimeCostKpi = { range: 7, agentId: 'all', total: 82.61, perTask: 12.5, perDay: 2.75 };

function realtimeCostPayload(range = 7, agentId = 'all') {
  return {
    kpi: { cost: { ...realtimeCostKpi, range, agentId } },
    costComposition: {
      range,
      scope: 'ALL_AGENTS',
      total: 82.61,
      sources: [
        { key: 'WORKITEM_EXECUTION', name: '工单执行', credits: 76, share: 0.92 },
        { key: 'REQUIREMENT_CLARIFY', name: '需求澄清', credits: 6.61, share: 0.08 },
      ],
    },
  };
}

// 成本面板与实时快照共用 /api/dashboard/realtime：只有带 range/agentId 的请求属于成本取数。
function realtimeRouteHandler(requests: string[], resolve = realtimeCostPayload) {
  return http.get('/api/dashboard/realtime', ({ request }) => {
    const url = new URL(request.url);
    if (!url.searchParams.has('range')) {
      return HttpResponse.json(ok(realtimeDashboardPayload));
    }
    const range = Number(url.searchParams.get('range'));
    const agentId = url.searchParams.get('agentId') ?? 'all';
    requests.push(url.search);
    return HttpResponse.json(ok(resolve(range, agentId)));
  });
}

describe('InsightsPage realtime credits cost', () => {
  beforeEach(() => {
    // 偏好按用户维度落在 localStorage：切 tab / 筛选会跨用例残留，这里逐个用例复位。
    window.localStorage.clear();
  });

  it('renders the credits cost card on the default realtime dashboard path', async () => {
    const costRequests: string[] = [];
    server.use(
      realtimeRouteHandler(costRequests),
      overviewHandler,
      pipelineHealthHandler,
      insightsWorkersHandler,
      insightsMetricsHandler(),
      insightsAuditHandler(),
    );

    const user = userEvent.setup();
    renderPage();

    const region = await screen.findByRole('region', { name: '成本分析' });
    expect(await within(region).findByText('总 Credits')).toBeInTheDocument();
    const stats = within(within(region).getByText('总 Credits').closest('.cost-card') as HTMLElement);
    expect(stats.getByText('82.61')).toBeInTheDocument();
    expect(stats.getByText('12.5')).toBeInTheDocument();
    expect(stats.getByText('2.75')).toBeInTheDocument();

    // 来源构成紧凑条与统计块同屏，图例带绝对值与占比
    expect(within(region).getByTestId('cost-composition-bar')).toBeInTheDocument();
    expect(within(region).getByRole('list', { name: 'Credits 来源图例' })).toHaveTextContent('工单执行');
    expect(screen.getByTestId('cost-composition-chip')).toHaveTextContent('全部数字员工 · 近7天');

    // 实时看板既有 KPI 与成本卡同屏可见
    expect(await screen.findByText('正在运行')).toBeInTheDocument();
    // 原指标页的合成卡片不得出现在实时看板
    expect(screen.queryByText('效率')).not.toBeInTheDocument();
    expect(screen.queryByText('稳定')).not.toBeInTheDocument();
    expect(screen.queryByText('安全')).not.toBeInTheDocument();
    // 首屏按近 7 天、全部数字员工取数
    expect(costRequests.at(-1)).toBe('?range=7&agentId=all');

    await user.hover(within(region).getByText('总 Credits'));
    await waitPastTooltipDelay();

    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument();
    expect(screen.queryByText('总 Token:')).not.toBeInTheDocument();
    expect(within(region).getByText('总 Credits')).toBeInTheDocument();
  });

  it('renders zero credits without NaN when there is no usage data', async () => {
    const costRequests: string[] = [];
    server.use(
      realtimeRouteHandler(costRequests, () => ({
        kpi: { cost: { range: 7, agentId: 'all', total: 0, perTask: 0, perDay: 0 } },
        costComposition: { range: 7, scope: 'ALL_AGENTS', total: 0, sources: [] },
      })),
      overviewHandler,
      pipelineHealthHandler,
      insightsWorkersHandler,
      insightsMetricsHandler(),
      insightsAuditHandler(),
    );

    renderPage();

    const region = await screen.findByRole('region', { name: '成本分析' });
    await within(region).findByText('总 Credits');
    const stats = within(within(region).getByText('总 Credits').closest('.cost-card') as HTMLElement);
    await waitFor(() => expect(stats.getAllByText('0')).toHaveLength(3));
    expect(within(region).getByText('所选范围暂无 Credits 消耗')).toBeInTheDocument();
    expect(screen.queryByText(/NaN/)).not.toBeInTheDocument();
  });

  it('scopes the cost filters to the cost query and leaves audit preferences untouched', async () => {
    const costRequests: string[] = [];
    const auditRequests: string[] = [];
    server.use(
      realtimeRouteHandler(costRequests),
      overviewHandler,
      pipelineHealthHandler,
      insightsWorkersHandler,
      insightsMetricsHandler(),
      insightsAuditHandler(auditRequests),
    );

    const user = userEvent.setup();
    renderPage();

    const region = await screen.findByRole('region', { name: '成本分析' });
    await user.click(within(region).getByRole('combobox', { name: '成本数字员工' }));
    const workerOptions = await screen.findAllByText('验收数字员工');
    await user.click(workerOptions[workerOptions.length - 1]);
    await user.click(within(region).getByText('近30天'));

    await waitFor(() => expect(costRequests.at(-1)).toBe('?range=30&agentId=12'));
    // 切员工后构成条锁定为全员口径
    expect(screen.getByTestId('cost-composition-chip')).toHaveTextContent('全部数字员工口径 · 不随上方员工筛选联动');

    // 成本筛选写的是 realtime.cost.*，页面级 audit 查询仍沿用 audit.* 的默认值
    await waitFor(() => {
      expect(auditRequests.length).toBeGreaterThan(0);
      const latestAuditRequest = auditRequests[auditRequests.length - 1];
      expect(latestAuditRequest).toContain('time_range=30d');
      expect(latestAuditRequest).not.toContain('worker_id');
    });
  });

  it('keeps the realtime dashboard usable when the cost query fails', async () => {
    server.use(
      http.get('/api/dashboard/realtime', ({ request }) => {
        const url = new URL(request.url);
        if (!url.searchParams.has('range')) {
          return HttpResponse.json(ok(realtimeDashboardPayload));
        }
        return HttpResponse.json(
          { success: false, code: '50000', message: 'realtime 成本聚合失败', data: null, traceId: null },
          { status: 500 },
        );
      }),
      overviewHandler,
      pipelineHealthHandler,
      insightsWorkersHandler,
      insightsMetricsHandler(),
      insightsAuditHandler(),
    );

    renderPage();

    expect(await screen.findByText('Credits 成本加载失败')).toBeInTheDocument();
    expect(screen.getByText('realtime 成本聚合失败')).toBeInTheDocument();
    expect(screen.queryByText('总 Credits')).not.toBeInTheDocument();

    // 成本接口失败不影响实时看板其余模块
    expect(await screen.findByText('正在运行')).toBeInTheDocument();
    expect(screen.getByText('工单分类')).toBeInTheDocument();
    expect(screen.getByRole('region', { name: '流水线健康' })).toBeInTheDocument();
  });

  it('keeps the cost query independent from the overview refresh button', async () => {
    const costRequests: string[] = [];
    let overviewRequests = 0;
    server.use(
      http.get('/api/dashboard/overview', () => {
        overviewRequests += 1;
        return HttpResponse.json(ok(overviewDashboardPayload));
      }),
      pipelineHealthHandler,
      realtimeRouteHandler(costRequests),
      insightsWorkersHandler,
      insightsMetricsHandler(),
      insightsAuditHandler(),
    );

    const user = userEvent.setup();
    renderPage();

    await screen.findByText('正在运行');
    const costRequestsBefore = costRequests.length;
    const overviewRequestsBefore = overviewRequests;

    // 工单概况的手动刷新只刷新自身快照，成本筛选在自身面板内独立取数。
    // antd 会用「ant-btn」在中文两字间插空格，且图标自带 aria-label，故用正则匹配
    await user.click(screen.getByRole('button', { name: /刷\s*新/ }));

    await waitFor(() => expect(overviewRequests).toBeGreaterThan(overviewRequestsBefore));
    expect(costRequests).toHaveLength(costRequestsBefore);
  });
});
