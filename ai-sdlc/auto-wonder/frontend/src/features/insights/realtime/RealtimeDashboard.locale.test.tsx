import { act, cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, expect, it } from 'vitest';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { i18n } from '@/shared/i18n';
import RealtimeDashboard from './RealtimeDashboard';
import KpiRow from './KpiRow';
import OverviewPanel from './OverviewPanel';
import PipelineHealthPanel from './PipelineHealthPanel';
import SquadLines from './SquadLines';
import type { OverviewData, PipelineHealth, PipelineRange, RealtimeDashboard as DashboardData } from './api';

const overview: OverviewData = {
  kpi: { todayCreatedWorkitems: 3, runningWorkitems: 2, todayCompletedTasks: 12, weekCompletedTasks: 57 },
  inventory: { byLifecycle: { init: 1, inProgress: 2, done: 3, canceled: 4 }, byType: { req: 1, task: 2, bug: 3 } },
  generatedAt: '2026-09-25T12:30:00Z',
};

function pipeline(range: PipelineRange): PipelineHealth {
  return {
    range,
    successRate: 80,
    successSub: { succeeded: 8, total: 10 },
    failedOrTimeout: 1,
    rework: { rate: 25, reworkedWorkitems: 2, executedWorkitems: 8, retryCount: 2 },
    avgWorkitemDurationMinutes: 90,
    generatedAt: '2026-09-25T12:30:00Z',
  };
}

const data: DashboardData = {
  kpi: { runningDispatches: 2, todayCompletedTasks: 12, weekCompletedTasks: 57, avgTaskDurationMinutes: 90, inProgressWorkitems: 9, queuedDispatches: 3, activeSquads: 2, onlineAgents: 6, avgLoad: 0.67 },
  inventory: { byLifecycle: { init: 1, inProgress: 2, done: 3, canceled: 4 }, byType: { req: 1, task: 2, bug: 3 } },
  health: { successRate: 80, failedOrTimeout: 1, retries: 2, avgDurationMinutes: 90 },
  squads: [{ squadId: 1, name: '<b>交付小队</b>', members: 4, online: 3, busy: 1, runningTasks: 2, inProgressWorkitems: 3, load: 0.5 }],
  workstations: [], runningFeed: [], recentFeed: [], generatedAt: '2026-09-25T12:30:00Z',
};
const ok = (value: unknown) => HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: value });

const ALL_AGENT_COMPOSITION_TOTAL = 110;

function isCostRequest(url: URL) {
  return url.searchParams.has('range') || url.searchParams.has('agentId');
}

// 成本面板与实时快照共用 /api/dashboard/realtime：带 range/agentId 的请求只返回成本块。
function costRealtimePayload(range: number, agentId: string) {
  const total = agentId === 'all' ? ALL_AGENT_COMPOSITION_TOTAL : 82.61;
  return {
    kpi: { cost: { range, agentId, total, perTask: 12.5, perDay: 2.75 } },
    costComposition: {
      range,
      scope: 'ALL_AGENTS',
      total: ALL_AGENT_COMPOSITION_TOTAL,
      sources: [{ key: 'WORKITEM_EXECUTION', name: '工单执行', credits: ALL_AGENT_COMPOSITION_TOTAL, share: 1 }],
    },
  };
}

function stubDashboard() {
  const counts = { overview: 0, realtime: 0, pipeline: 0, cost: 0, workers: 0, running: 0 };
  const costRequests: string[] = [];
  const pipelineRequests: string[] = [];
  server.use(
    http.get('/api/dashboard/overview', () => { counts.overview += 1; return ok(overview); }),
    http.get('/api/dashboard/pipeline-health', ({ request }) => {
      counts.pipeline += 1;
      pipelineRequests.push(new URL(request.url).search);
      const range = (new URL(request.url).searchParams.get('range') ?? 'today') as PipelineRange;
      return ok(pipeline(range));
    }),
    http.get('/api/dashboard/realtime', ({ request }) => {
      const url = new URL(request.url);
      if (isCostRequest(url)) {
        counts.cost += 1;
        costRequests.push(url.search);
        const range = Number(url.searchParams.get('range') ?? '7');
        return ok(costRealtimePayload(range, url.searchParams.get('agentId') ?? 'all'));
      }
      counts.realtime += 1;
      return ok(data);
    }),
    http.get('/api/dashboard/running', () => {
      counts.running += 1;
      return ok([
        { workitemId: 7, title: '<strong>工单</strong>', agentName: '<em>数字员工</em>', stepName: null, runningMinutes: 1, runningDispatchCount: 1 },
        { workitemId: 8, title: '原始工单', agentName: '<em>数字员工</em>', stepName: '编码', runningMinutes: 2, runningDispatchCount: 2 },
      ]);
    }),
    http.get('/api/insights/workers', () => { counts.workers += 1; return ok([{ id: 12, name: '<em>数字员工</em>' }]); }),
  );
  return { counts, costRequests, pipelineRequests };
}

function routed(element: React.ReactNode, client = new QueryClient({ defaultOptions: { queries: { retry: false } } })) {
  const router = createMemoryRouter([{ path: '/insights', element }], { initialEntries: ['/insights'] });
  return render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>);
}

function costRegion() {
  return within(screen.getByRole('region', { name: '成本分析' }));
}

function overviewRegion() {
  return within(screen.getByRole('region', { name: '工单概况' }));
}

function pipelineRegion() {
  return within(screen.getByRole('region', { name: '流水线健康' }));
}

beforeEach(async () => { localStorage.clear(); await i18n.changeLanguage('zh-CN'); });
afterEach(async () => { cleanup(); await i18n.changeLanguage('zh-CN'); });

it('keeps the snapshot, pipeline panel and open KPI modal intact across a language switch', async () => {
  const { counts, costRequests } = stubDashboard();
  const user = userEvent.setup();
  const { container } = routed(<RealtimeDashboard />);

  await screen.findByRole('region', { name: '工单概况' });
  const costSection = screen.getByRole('region', { name: '成本分析' });
  expect(screen.getByRole('region', { name: '流水线健康' })).toBeInTheDocument();
  expect(screen.getByRole('status', { name: '自动刷新' })).toHaveTextContent('30s');
  expect(screen.getByText('固定 30 秒自动刷新，不再提供刷新频率选项')).toBeInTheDocument();
  expect(screen.getByText('两版块统计口径独立 · 工单概况 = 实时快照 ／ 流水线健康 = 时间段聚合')).toBeInTheDocument();
  expect(screen.getByText('上下两区统计口径独立 · 筛选互不影响')).toBeInTheDocument();
  // v3：成本面板头部下不再有「统计口径：…」说明，口径改由来源构成 chip 表达
  expect(within(costSection).queryByText('统计口径：')).not.toBeInTheDocument();
  expect(screen.queryByText('所选数字员工 × 日期范围')).not.toBeInTheDocument();
  expect(await screen.findByTestId('cost-composition-bar')).toBeInTheDocument();
  expect(screen.getByTestId('cost-composition-chip')).toHaveTextContent('全部数字员工 · 近7天');
  expect(costRegion().getByRole('list', { name: 'Credits 来源图例' })).toHaveTextContent('工单执行');
  expect(screen.getByText('不受上方实时快照影响')).toBeInTheDocument();
  expect(screen.queryByText('10s')).not.toBeInTheDocument();
  expect(screen.queryByText('关闭')).not.toBeInTheDocument();

  await user.click(costRegion().getByRole('combobox', { name: '成本数字员工' }));
  const options = await screen.findAllByText('<em>数字员工</em>');
  await user.click(options[options.length - 1]);
  await user.click(costRegion().getByText('近30天'));
  await waitFor(() => expect(costRequests.at(-1)).toBe('?range=30&agentId=12'));
  expect(screen.getByTestId('cost-composition-chip')).toHaveTextContent('全部数字员工口径 · 不随上方员工筛选联动');

  await user.click(screen.getByText('正在运行'));
  const dialog = await screen.findByRole('dialog');
  expect(await within(dialog).findByText('1分钟')).toBeInTheDocument();
  expect(within(dialog).getByText(/2 个执行中 ·/)).toBeInTheDocument();
  const selector = screen.getByRole('combobox', { name: '成本数字员工' });
  const requestCount = costRequests.length;

  await act(async () => { await i18n.changeLanguage('en-US'); });

  expect(screen.getByRole('button', { name: /Running 2/ })).toBeInTheDocument();
  expect(screen.getByRole('dialog')).toBe(dialog);
  expect(within(dialog).getByText('1 minute')).toBeInTheDocument();
  expect(within(dialog).getByText('2 minutes')).toBeInTheDocument();
  expect(within(dialog).getByText(/2 running ·/)).toBeInTheDocument();
  expect(within(dialog).getByText('#7 <strong>工单</strong>')).toBeInTheDocument();
  expect(screen.getByRole('combobox', { name: 'Cost Agent' })).toBe(selector);
  expect(within(selector.closest('.ant-select') as HTMLElement).getByText('<em>数字员工</em>')).toBeInTheDocument();
  expect(within(costSection).getByRole('radio', { name: 'Last 30 days' })).toBeChecked();
  expect(screen.getByText('Total Credits')).toBeInTheDocument();
  expect(screen.getByText('82.61')).toBeInTheDocument();
  expect(screen.getByText('Credits by source')).toBeInTheDocument();
  expect(screen.getByText('Task execution')).toBeInTheDocument();
  expect(screen.getByTestId('cost-composition-chip')).toHaveTextContent('All agents scope · unaffected by the agent filter above');
  expect(screen.getByText('<b>交付小队</b>')).toBeInTheDocument();
  expect(screen.getByText('Work item overview')).toBeInTheDocument();
  expect(screen.getByText('Work item categories')).toBeInTheDocument();
  expect(screen.getByRole('img', { name: 'Work item status share stack' })).toBeInTheDocument();
  expect(screen.getByText('Pipeline health')).toBeInTheDocument();
  expect(screen.getByText('Success rate')).toBeInTheDocument();
  expect(screen.getByText('1h 30m')).toBeInTheDocument();
  const timestamp = new Intl.DateTimeFormat('en-US', { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false }).format(new Date(overview.generatedAt));
  expect(screen.getByText(/Updated at/, { selector: '.insight-stamp' })).toHaveTextContent(`Updated at ${timestamp}`);
  expect(container.querySelector('em, strong strong')).toBeNull();
  expect(counts).toEqual({ overview: 1, realtime: 1, pipeline: 1, cost: 3, workers: 1, running: 1 });
  expect(costRequests).toHaveLength(requestCount);
  expect(localStorage.getItem('aw:insights:v1:anonymous:none:realtime.cost.worker')).toBe('12');
  expect(localStorage.getItem('aw:insights:v1:anonymous:none:realtime.cost.range')).toBe('"30d"');
  expect(localStorage.getItem('aw:insights:v1:anonymous:none:realtime.interval')).toBeNull();

  await act(async () => { await i18n.changeLanguage('zh-CN'); });
  expect(screen.getByRole('dialog')).toBe(dialog);
  expect(within(dialog).getByText('1分钟')).toBeInTheDocument();
  expect(screen.getByText('总 Credits')).toBeInTheDocument();
  expect(screen.getByText('工单概况')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: /正在运行 2/ })).toBeInTheDocument();
});

it('keeps the overview refresh, pipeline range and cost filters independent', async () => {
  const { counts, costRequests, pipelineRequests } = stubDashboard();
  const user = userEvent.setup();
  routed(<RealtimeDashboard />);

  await screen.findByRole('region', { name: '工单概况' });
  expect(await screen.findByText('80.0%')).toBeInTheDocument();
  expect(counts).toEqual({ overview: 1, realtime: 1, pipeline: 1, cost: 1, workers: 1, running: 0 });

  await user.click(overviewRegion().getByRole('button', { name: /刷\s*新/ }));
  await waitFor(() => expect(counts.overview).toBe(2));
  expect(counts.realtime).toBe(1);
  expect(counts.pipeline).toBe(1);
  expect(counts.cost).toBe(1);

  await user.click(pipelineRegion().getByText('近7天'));
  await waitFor(() => expect(pipelineRequests.at(-1)).toBe('?range=7d'));
  expect(counts.overview).toBe(2);
  expect(counts.realtime).toBe(1);
  expect(counts.cost).toBe(1);
  const beforeCostFilter = { ...counts };

  await user.click(costRegion().getByRole('combobox', { name: '成本数字员工' }));
  const options = await screen.findAllByText('<em>数字员工</em>');
  await user.click(options[options.length - 1]);
  await waitFor(() => expect(counts.cost).toBe(2));
  expect(costRequests.at(-1)).toBe('?range=7&agentId=12');
  expect(counts.overview).toBe(beforeCostFilter.overview);
  expect(counts.realtime).toBe(beforeCostFilter.realtime);
  expect(counts.pipeline).toBe(beforeCostFilter.pipeline);
});

it('restores the all-agent cost scope from the chip without refetching the snapshot', async () => {
  const { counts, costRequests } = stubDashboard();
  const user = userEvent.setup();
  routed(<RealtimeDashboard />);
  await screen.findByRole('region', { name: '工单概况' });

  await user.click(costRegion().getByRole('combobox', { name: '成本数字员工' }));
  const options = await screen.findAllByRole('option');
  expect(options[0]).toHaveAttribute('aria-label', '全部数字员工');
  const workerOptions = await screen.findAllByText('<em>数字员工</em>');
  await user.click(workerOptions[workerOptions.length - 1]);
  await waitFor(() => expect(costRequests.at(-1)).toContain('agentId=12'));
  expect(screen.getByTestId('cost-composition-chip')).toHaveTextContent('全部数字员工口径 · 不随上方员工筛选联动');

  const snapshotBefore = { ...counts };
  const costCountBefore = costRequests.length;
  await user.click(screen.getByRole('button', { name: '清除数字员工筛选' }));
  expect(screen.getByTestId('cost-composition-chip')).toHaveTextContent('全部数字员工 · 近7天');
  expect(localStorage.getItem('aw:insights:v1:anonymous:none:realtime.cost.worker')).toBe('null');
  // 全量口径的结果仍在 5 分钟 staleTime 缓存内，回退不重复请求成本接口。
  expect(costRequests).toHaveLength(costCountBefore);
  expect(counts).toEqual(snapshotBefore);
});

it('updates the cached panels and accessible charts without resetting keyboard focus', async () => {
  stubDashboard();
  const clicks: string[] = [];
  routed(<><KpiRow kpi={overview.kpi} onKpiClick={(key) => clicks.push(key)} /><OverviewPanel /><PipelineHealthPanel /><SquadLines squads={data.squads} /></>);
  await screen.findByText('80.0%');

  const running = screen.getByRole('button', { name: /正在运行 2/ });
  running.focus();
  await act(async () => { await i18n.changeLanguage('en-US'); });
  expect(screen.getByRole('button', { name: /Running 2/ })).toBe(running);
  expect(running).toHaveFocus();
  await userEvent.keyboard('{Enter} ');
  expect(clicks).toEqual(['runningWorkitems', 'runningWorkitems']);

  expect(screen.getByText('Work item overview')).toBeInTheDocument();
  expect(screen.getByText('Work item categories')).toBeInTheDocument();
  expect(screen.getByText('Work item status')).toBeInTheDocument();
  expect(screen.getByRole('img', { name: 'Work item status share stack' })).toBeInTheDocument();
  expect(screen.getByText('Canceled')).toBeInTheDocument();
  expect(screen.getByText('Requirements')).toBeInTheDocument();
  expect(screen.getByText('Pipeline health')).toBeInTheDocument();
  expect(screen.getByText('Success rate')).toBeInTheDocument();
  expect(screen.getByText('1h 30m')).toBeInTheDocument();
  expect(screen.getByRole('img', { name: 'Busy members 1 / 4' }).firstElementChild).toHaveStyle({ width: '25%' });
  expect(screen.getByText('<b>交付小队</b>')).toBeInTheDocument();
  expect(screen.getByText('Squad production lines')).toBeInTheDocument();
});

it('renders an in-flight overview failure in the current language and preserves the last snapshot', async () => {
  let release!: () => void;
  const pending = new Promise<void>((resolve) => { release = resolve; });
  let overviewCalls = 0;
  server.use(
    http.get('/api/dashboard/overview', async () => {
      overviewCalls += 1;
      if (overviewCalls > 1) {
        await pending;
        return HttpResponse.json({ success: false, code: '50000', message: '原始后端错误', data: null, traceId: null }, { status: 500 });
      }
      return ok(overview);
    }),
    http.get('/api/dashboard/pipeline-health', () => ok(pipeline('today'))),
    http.get('/api/dashboard/realtime', ({ request }) => {
      const url = new URL(request.url);
      return ok(isCostRequest(url)
        ? costRealtimePayload(Number(url.searchParams.get('range') ?? '30'), url.searchParams.get('agentId') ?? 'all')
        : data);
    }),
    http.get('/api/insights/workers', () => ok([])),
  );
  const user = userEvent.setup();
  routed(<RealtimeDashboard />);
  await screen.findByText('正在运行');

  await user.click(overviewRegion().getByRole('button', { name: /刷\s*新/ }));
  expect(await screen.findByText('更新中…')).toBeInTheDocument();
  await act(async () => { await i18n.changeLanguage('en-US'); });
  expect(screen.getByText('Updating…')).toBeInTheDocument();

  await act(async () => { release(); });

  expect(await screen.findByText('Failed to load overview data')).toBeInTheDocument();
  expect(screen.getByText('原始后端错误')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: /Running 2/ })).toBeInTheDocument();
});

it('renders the realtime failure alert without blocks or an empty state and retries only the realtime query', async () => {
  let failRealtime = true;
  const counts = { overview: 0, pipeline: 0, realtime: 0, workers: 0, cost: 0 };
  server.use(
    http.get('/api/dashboard/overview', () => { counts.overview += 1; return ok(overview); }),
    http.get('/api/dashboard/pipeline-health', () => { counts.pipeline += 1; return ok(pipeline('today')); }),
    http.get('/api/dashboard/realtime', ({ request }) => {
      const url = new URL(request.url);
      if (isCostRequest(url)) {
        counts.cost += 1;
        return ok(costRealtimePayload(Number(url.searchParams.get('range') ?? '30'), url.searchParams.get('agentId') ?? 'all'));
      }
      counts.realtime += 1;
      if (failRealtime) {
        return HttpResponse.json({ success: false, code: '50000', message: '实时看板后端错误', data: null, traceId: null }, { status: 500 });
      }
      return ok(data);
    }),
    http.get('/api/insights/workers', () => { counts.workers += 1; return ok([]); }),
  );
  const user = userEvent.setup();
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const { container } = routed(<RealtimeDashboard />, client);

  expect(await screen.findByText('看板数据加载失败')).toBeInTheDocument();
  expect(screen.getByText('实时看板后端错误')).toBeInTheDocument();
  expect(container.querySelector('.insight-realtime-blocks')).toBeNull();
  expect(screen.queryByText('暂无实时数据，请稍后刷新')).not.toBeInTheDocument();
  const before = { ...counts };

  failRealtime = false;
  await user.click(screen.getByRole('button', { name: /重\s*试/ }));

  expect(await screen.findByText('<b>交付小队</b>')).toBeInTheDocument();
  await waitFor(() => expect(screen.queryByText('看板数据加载失败')).not.toBeInTheDocument());
  expect(counts.realtime).toBe(before.realtime + 1);
  expect(counts.overview).toBe(before.overview);
  expect(counts.pipeline).toBe(before.pipeline);
  expect(counts.workers).toBe(before.workers);
  expect(counts.cost).toBe(before.cost);
});

it('keeps the previous realtime blocks while showing the failure alert after a refresh error', async () => {
  let failRealtime = false;
  const counts = { overview: 0, pipeline: 0, realtime: 0, workers: 0, cost: 0 };
  server.use(
    http.get('/api/dashboard/overview', () => { counts.overview += 1; return ok(overview); }),
    http.get('/api/dashboard/pipeline-health', () => { counts.pipeline += 1; return ok(pipeline('today')); }),
    http.get('/api/dashboard/realtime', ({ request }) => {
      const url = new URL(request.url);
      if (isCostRequest(url)) {
        counts.cost += 1;
        return ok(costRealtimePayload(Number(url.searchParams.get('range') ?? '30'), url.searchParams.get('agentId') ?? 'all'));
      }
      counts.realtime += 1;
      if (failRealtime) {
        return HttpResponse.json({ success: false, code: '50000', message: '实时看板后端错误', data: null, traceId: null }, { status: 500 });
      }
      return ok(data);
    }),
    http.get('/api/insights/workers', () => { counts.workers += 1; return ok([]); }),
  );
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  routed(<RealtimeDashboard />, client);

  expect(await screen.findByText('<b>交付小队</b>')).toBeInTheDocument();
  const before = { ...counts };

  failRealtime = true;
  await act(async () => { await client.refetchQueries({ queryKey: ['dashboard-realtime'] }); });

  expect(await screen.findByText('看板数据加载失败')).toBeInTheDocument();
  expect(screen.getByText('实时看板后端错误')).toBeInTheDocument();
  expect(screen.getByText('<b>交付小队</b>')).toBeInTheDocument();
  expect(counts.realtime).toBe(before.realtime + 1);
  expect(counts.overview).toBe(before.overview);
  expect(counts.pipeline).toBe(before.pipeline);
  expect(counts.workers).toBe(before.workers);
  expect(counts.cost).toBe(before.cost);
});

it('switches loading and empty messages to the current language for all three panels', async () => {
  let releaseOverview!: () => void;
  let releasePipeline!: () => void;
  let releaseRealtime!: () => void;
  const overviewPending = new Promise<void>((resolve) => { releaseOverview = resolve; });
  const pipelinePending = new Promise<void>((resolve) => { releasePipeline = resolve; });
  const realtimePending = new Promise<void>((resolve) => { releaseRealtime = resolve; });
  server.use(
    http.get('/api/dashboard/overview', async () => { await overviewPending; return ok(null); }),
    http.get('/api/dashboard/pipeline-health', async () => { await pipelinePending; return ok(null); }),
    http.get('/api/dashboard/realtime', async ({ request }) => {
      const url = new URL(request.url);
      if (isCostRequest(url)) {
        return ok(costRealtimePayload(Number(url.searchParams.get('range') ?? '30'), url.searchParams.get('agentId') ?? 'all'));
      }
      await realtimePending;
      return ok(null);
    }),
    http.get('/api/insights/workers', () => ok([])),
  );
  routed(<RealtimeDashboard />);

  expect(await screen.findByText('加载工单概况…')).toBeInTheDocument();
  expect(screen.getByText('加载流水线健康…')).toBeInTheDocument();
  expect(screen.getByText('加载实时看板…')).toBeInTheDocument();
  await act(async () => { await i18n.changeLanguage('en-US'); });
  expect(screen.getByText('Loading overview…')).toBeInTheDocument();
  expect(screen.getByText('Loading pipeline health…')).toBeInTheDocument();
  expect(screen.getByText('Loading realtime dashboard…')).toBeInTheDocument();

  await act(async () => { releaseOverview(); releasePipeline(); releaseRealtime(); });

  expect(await screen.findByText('No overview data available')).toBeInTheDocument();
  expect(await screen.findByText('No pipeline health data')).toBeInTheDocument();
  expect(await screen.findByText('No realtime data. Refresh again later.')).toBeInTheDocument();
  await act(async () => { await i18n.changeLanguage('zh-CN'); });
  expect(screen.getByText('暂无工单概况数据')).toBeInTheDocument();
  expect(screen.getByText('暂无流水线健康数据')).toBeInTheDocument();
  expect(screen.getByText('暂无实时数据，请稍后刷新')).toBeInTheDocument();

  cleanup();
  routed(<SquadLines squads={[]} />);
  await act(async () => { await i18n.changeLanguage('en-US'); });
  expect(screen.getByText('No Squads')).toBeInTheDocument();
});
