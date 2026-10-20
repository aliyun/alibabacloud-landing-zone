import { act, cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, expect, it } from 'vitest';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { i18n } from '@/shared/i18n';
import RealtimeDashboard from './RealtimeDashboard';
import InventoryPanel from './InventoryPanel';
import HealthPanel from './HealthPanel';
import SquadLines from './SquadLines';
import KpiRow from './KpiRow';
import type { RealtimeDashboard as DashboardData } from './api';

// antd Tooltip 延迟出现，负向断言必须等过该延迟，否则回归会被漏过。
async function waitPastTooltipDelay() {
  await act(async () => { await new Promise((resolve) => setTimeout(resolve, 300)); });
}

const data: DashboardData = {
  kpi: { runningDispatches: 2, todayCompletedTasks: 12, weekCompletedTasks: 57, avgTaskDurationMinutes: 90, inProgressWorkitems: 9, queuedDispatches: 3, activeSquads: 2, onlineAgents: 6, avgLoad: 0.67 },
  inventory: { byLifecycle: { init: 1, inProgress: 2, done: 3, canceled: 4 }, byType: { req: 1, task: 2, bug: 3 } },
  health: { successRate: 80, failedOrTimeout: 1, retries: 2, avgDurationMinutes: 90 },
  squads: [{ squadId: 1, name: '<b>交付小队</b>', members: 4, online: 3, busy: 1, runningTasks: 2, inProgressWorkitems: 3, load: 0.5 }],
  workstations: [], runningFeed: [], recentFeed: [], generatedAt: '2026-09-25T12:30:00Z',
};
const cost = { totalTokens: 100000, avgTokensPerTask: 5000, dailyAvg: 14000, trend: [10], totalCredits: 82.61, avgCreditsPerTask: 12.5, dailyAvgCredits: 2.75, creditsTrend: [1, 2] };
const metrics = { cost, efficiency: { completionRate: 80, totalTasks: 20, completedTasks: 16, avgDurationMinutes: 30, trend: [80] }, stability: { successRate: 90, retryCount: 2, blockedCount: 1, trend: [90] }, security: { highRiskOps: 1, complianceRate: 98, auditBlocks: 3, trend: [1] } };
const ok = (value: unknown) => HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: value });
function routed(element: React.ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const router = createMemoryRouter([{ path: '/insights', element }], { initialEntries: ['/insights'] });
  return render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>);
}
beforeEach(async () => { localStorage.clear(); await i18n.changeLanguage('zh-CN'); });
afterEach(async () => { cleanup(); await i18n.changeLanguage('zh-CN'); });

it('switches the cached dashboard with its open KPI modal and canonical cost filters intact', async () => {
  let dashboardRequests = 0;
  let runningRequests = 0;
  const costRequests: string[] = [];
  server.use(
    http.get('/api/dashboard/realtime', () => { dashboardRequests++; return ok(data); }),
    http.get('/api/insights/workers', () => ok([{ id: 12, name: '<em>数字员工</em>' }])),
    http.get('/api/insights/metrics', ({ request }) => { costRequests.push(new URL(request.url).search); return ok(metrics); }),
    http.get('/api/dashboard/running', () => { runningRequests++; return ok([
      { dispatchId: 1, agentId: 12, agentName: '<em>数字员工</em>', workitemId: 7, workitemTitle: '<strong>工单</strong>', stepName: null, runningMinutes: 1 },
      { dispatchId: 2, agentId: 12, agentName: '<em>数字员工</em>', workitemId: 8, workitemTitle: '原始工单', stepName: null, runningMinutes: 2 },
    ]); }),
  );
  const user = userEvent.setup();
  const { container } = routed(<RealtimeDashboard />);
  await screen.findByText('正在运行');
  expect(screen.getByRole('region', { name: '成本分析' })).toBeInTheDocument();
  expect(screen.getByRole('region', { name: '实时运行概览' })).toBeInTheDocument();
  expect(screen.getByText('上下两区统计口径独立 · 筛选互不影响')).toBeInTheDocument();
  expect(screen.getByText('该组筛选仅作用于本面板的成本数据，不影响下方实时指标')).toBeInTheDocument();
  expect(screen.getByText('固定全量视角，不受上方面板的数字员工 / 日期筛选影响')).toBeInTheDocument();
  expect(screen.getByRole('status', { name: '自动刷新' })).toHaveTextContent('15s');
  await user.click(screen.getByText('关闭'));
  expect(screen.getByRole('status', { name: '自动刷新' })).toHaveTextContent('—');
  await user.click(screen.getByRole('combobox', { name: '成本数字员工' }));
  const options = await screen.findAllByText('<em>数字员工</em>');
  await user.click(options[options.length - 1]);
  await user.click(screen.getByText('近7天'));
  await waitFor(() => expect(costRequests.at(-1)).toBe('?worker_id=12&time_range=7d'));
  await user.click(screen.getByRole('button', { name: '正在运行 2' }));
  const dialog = await screen.findByRole('dialog');
  expect(await within(dialog).findByText('1分钟')).toBeInTheDocument();
  const selector = screen.getByRole('combobox', { name: '成本数字员工' });
  const requestCount = costRequests.length;
  await act(async () => { await i18n.changeLanguage('en-US'); });
  expect(screen.getByRole('button', { name: 'Running 2' })).toBeInTheDocument();
  expect(screen.getByRole('dialog')).toBe(dialog);
  expect(within(dialog).getByText('1 minute')).toBeInTheDocument();
  expect(within(dialog).getByText('2 minutes')).toBeInTheDocument();
  expect(within(dialog).getByText('#7 <strong>工单</strong>')).toBeInTheDocument();
  expect(screen.getByRole('combobox', { name: 'Cost Agent' })).toBe(selector);
  expect(screen.getByRole('radio', { name: 'Off' })).toBeChecked();
  expect(screen.getByRole('radio', { name: 'Last 7 days' })).toBeChecked();
  expect(screen.getByText('Total Credits')).toBeInTheDocument();
  expect(screen.getByText('82.61')).toBeInTheDocument();
  expect(screen.getByText('<b>交付小队</b>')).toBeInTheDocument();
  const timestamp = new Intl.DateTimeFormat('en-US', { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false }).format(new Date(data.generatedAt));
  expect(screen.getByText(`Updated at ${timestamp}`)).toBeInTheDocument();
  expect(container.querySelector('em, strong strong')).toBeNull();
  expect(dashboardRequests).toBe(1);
  expect(runningRequests).toBe(1);
  expect(costRequests).toHaveLength(requestCount);
  expect(localStorage.getItem('aw:insights:v1:anonymous:none:realtime.interval')).toBe('false');
  expect(localStorage.getItem('aw:insights:v1:anonymous:none:realtime.cost.worker')).toBe('12');
  expect(localStorage.getItem('aw:insights:v1:anonymous:none:realtime.cost.range')).toBe('"7d"');
  await user.hover(screen.getByText('Total Credits'));
  await waitPastTooltipDelay();
  expect(screen.queryByRole('tooltip')).not.toBeInTheDocument();
  expect(screen.queryByText('Total tokens:')).not.toBeInTheDocument();
  expect(screen.queryByText('100K')).not.toBeInTheDocument();
  await act(async () => { await i18n.changeLanguage('zh-CN'); });
  expect(screen.getByRole('dialog')).toBe(dialog);
  expect(within(dialog).getByText('1分钟')).toBeInTheDocument();
  expect(screen.getByText('总 Credits')).toBeInTheDocument();
  expect(screen.queryByText('总 Token:')).not.toBeInTheDocument();
});

it('keeps cost filters and realtime refresh controls independent', async () => {
  let dashboardRequests = 0;
  const costRequests: string[] = [];
  server.use(
    http.get('/api/dashboard/realtime', () => { dashboardRequests += 1; return ok(data); }),
    http.get('/api/insights/workers', () => ok([{ id: 12, name: '数字员工' }])),
    http.get('/api/insights/metrics', ({ request }) => { costRequests.push(new URL(request.url).search); return ok(metrics); }),
  );
  const user = userEvent.setup();
  routed(<RealtimeDashboard />);
  await screen.findByText('正在运行');

  expect(screen.getByText('该组筛选仅作用于本面板的成本数据，不影响下方实时指标')).toBeInTheDocument();
  expect(screen.getByText('固定全量视角，不受上方面板的数字员工 / 日期筛选影响')).toBeInTheDocument();
  expect(screen.getByText('上下两区统计口径独立 · 筛选互不影响')).toBeInTheDocument();

  const costCountBefore = costRequests.length;
  const dashboardCountBefore = dashboardRequests;

  await user.click(screen.getByText('10s'));
  expect(screen.getByRole('status', { name: '自动刷新' })).toHaveTextContent('10s');
  await user.click(screen.getByRole('button', { name: /刷\s*新/ }));
  await waitFor(() => expect(dashboardRequests).toBeGreaterThan(dashboardCountBefore));
  expect(costRequests).toHaveLength(costCountBefore);

  await user.click(screen.getByText('关闭'));
  expect(screen.getByRole('status', { name: '自动刷新' })).toHaveTextContent('—');
  expect(costRequests).toHaveLength(costCountBefore);
});

it('restores the all-agent cost scope from the chip without refetching realtime data', async () => {
  let dashboardRequests = 0;
  let runningRequests = 0;
  const costRequests: string[] = [];
  server.use(
    http.get('/api/dashboard/realtime', () => { dashboardRequests += 1; return ok(data); }),
    http.get('/api/insights/workers', () => ok([{ id: 12, name: '数字员工' }])),
    http.get('/api/insights/metrics', ({ request }) => { costRequests.push(new URL(request.url).search); return ok(metrics); }),
    http.get('/api/dashboard/running', () => { runningRequests += 1; return ok([]); }),
  );
  const user = userEvent.setup();
  routed(<RealtimeDashboard />);
  await screen.findByText('正在运行');

  await user.click(screen.getByRole('combobox', { name: '成本数字员工' }));
  const options = await screen.findAllByRole('option');
  expect(options[0]).toHaveAttribute('aria-label', '全部数字员工');
  const workerOptions = await screen.findAllByText('数字员工');
  await user.click(workerOptions[workerOptions.length - 1]);
  await waitFor(() => expect(costRequests.at(-1)).toContain('worker_id=12'));
  expect(screen.getByText('数字员工 · 近30天')).toBeInTheDocument();

  const dashboardBefore = dashboardRequests;
  const costCountBefore = costRequests.length;
  await user.click(screen.getByRole('button', { name: '清除数字员工筛选' }));
  expect(screen.getByText('全部数字员工 · 近30天')).toBeInTheDocument();
  expect(localStorage.getItem('aw:insights:v1:anonymous:none:realtime.cost.worker')).toBe('null');
  // 全量口径的结果仍在 5 分钟 staleTime 缓存内，回退不重复请求成本接口。
  expect(costRequests).toHaveLength(costCountBefore);
  expect(dashboardRequests).toBe(dashboardBefore);
  expect(runningRequests).toBe(0);
});

it('updates independent cached panels, accessible charts and compact values without resetting keyboard focus', async () => {
  const clicks: string[] = [];
  routed(<><KpiRow kpi={data.kpi} onKpiClick={key => clicks.push(key)} /><InventoryPanel inventory={data.inventory} /><HealthPanel health={data.health} /><SquadLines squads={data.squads} /></>);
  const running = screen.getByRole('button', { name: '正在运行 2' });
  running.focus();
  await act(async () => { await i18n.changeLanguage('en-US'); });
  expect(screen.getByRole('button', { name: 'Running 2' })).toBe(running);
  expect(running).toHaveFocus();
  await userEvent.keyboard('{Enter} ');
  expect(clicks).toEqual(['runningDispatches', 'runningDispatches']);
  expect(screen.getByText('Work item inventory')).toBeInTheDocument();
  expect(screen.getByText('Pipeline health')).toBeInTheDocument();
  expect(screen.getAllByText('1h 30m')).toHaveLength(2);
  expect(screen.getByRole('img', { name: 'Not started 1, In progress 2, Completed 3' })).toBeInTheDocument();
  expect(screen.getByRole('img', { name: 'Busy members 1 / 4' }).firstElementChild).toHaveStyle({ width: '25%' });
  expect(screen.getByText(/Canceled/)).toBeInTheDocument();
  expect(screen.getByText(/Requirements/)).toBeInTheDocument();
  expect(screen.getByText('Squad production lines')).toBeInTheDocument();
});

it('renders an in-flight refresh failure in the current language and preserves the server detail and previous data', async () => {
  let release!: () => void;
  const pending = new Promise<void>(resolve => { release = resolve; });
  let requests = 0;
  server.use(
    http.get('/api/dashboard/realtime', async () => { if (++requests > 1) { await pending; return HttpResponse.json({ success: false, code: '50000', message: '原始后端错误', data: null, traceId: null }, { status: 500 }); } return ok(data); }),
    http.get('/api/insights/workers', () => ok([])),
    http.get('/api/insights/metrics', () => ok(metrics)),
  );
  routed(<RealtimeDashboard />);
  await screen.findByText('正在运行');
  await userEvent.click(screen.getByRole('button', { name: /刷\s*新/ }));
  expect(await screen.findByText('更新中…')).toBeInTheDocument();
  await act(async () => { await i18n.changeLanguage('en-US'); });
  expect(screen.getByText('Updating…')).toBeInTheDocument();
  release();
  expect(await screen.findByText('Failed to load dashboard data')).toBeInTheDocument();
  expect(screen.getByText('原始后端错误')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Running 2' })).toBeInTheDocument();
});

it('switches dashboard loading to the current empty message and leaves empty panels reactive', async () => {
  let release!: () => void;
  const pending = new Promise<void>(resolve => { release = resolve; });
  server.use(http.get('/api/dashboard/realtime', async () => { await pending; return ok(null); }), http.get('/api/insights/workers', () => ok([])), http.get('/api/insights/metrics', () => ok(metrics)));
  routed(<RealtimeDashboard />);
  await screen.findByText('加载实时看板…');
  await act(async () => { await i18n.changeLanguage('en-US'); });
  expect(screen.getByText('Loading realtime dashboard…')).toBeInTheDocument();
  release();
  expect(await screen.findByText('No realtime data. Refresh again later.')).toBeInTheDocument();
  await act(async () => { await i18n.changeLanguage('zh-CN'); });
  expect(screen.getByText('暂无实时数据，请稍后刷新')).toBeInTheDocument();
  cleanup();
  routed(<><InventoryPanel inventory={{ byLifecycle: { init: 0, inProgress: 0, done: 0, canceled: 0 }, byType: { req: 0, task: 0, bug: 0 } }} /><SquadLines squads={[]} /></>);
  await act(async () => { await i18n.changeLanguage('en-US'); });
  expect(screen.getByText('No Squads')).toBeInTheDocument();
  expect(screen.getByText(/No data available/)).toBeInTheDocument();
  expect(screen.getByRole('img', { name: 'Not started 0, In progress 0, Completed 0' })).toBeInTheDocument();
});
