import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { afterEach, expect, it, vi } from 'vitest';
import { apiClient } from '@/shared/api/client';
import { server } from '@/test/mocks/server';
import OverviewPanel from './OverviewPanel';
import type { OverviewData } from './api';

const overview: OverviewData = {
  kpi: {
    todayCreatedWorkitems: 5,
    runningWorkitems: 7,
    todayCompletedTasks: 3,
    weekCompletedTasks: 21,
  },
  inventory: {
    byLifecycle: { init: 1, inProgress: 2, done: 3, canceled: 4 },
    byType: { req: 6, bug: 2, task: 2 },
  },
  generatedAt: '2026-10-13T04:05:06Z',
};

const ok = (value: unknown) => HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: value });

function renderPanel(props: Parameters<typeof OverviewPanel>[0] = {}) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <OverviewPanel {...props} />
    </QueryClientProvider>,
  );
}

function overviewHandler(payload: unknown = overview) {
  return http.get('/api/dashboard/overview', () => ok(payload));
}

afterEach(() => {
  vi.restoreAllMocks();
  vi.useRealTimers();
});

it('renders the snapshot KPIs, category bars and the 100% status stack including canceled items', async () => {
  server.use(overviewHandler());
  const { container } = renderPanel();
  const panel = within(await screen.findByRole('region', { name: '工单概况' }));
  await waitFor(() => expect(container.querySelectorAll('.insight-kpi-value')).toHaveLength(4));

  expect(panel.getByRole('status', { name: '自动刷新' })).toHaveTextContent('30s');
  expect(panel.getByText('全部数字员工 · 实时快照')).toBeInTheDocument();
  expect(panel.getByText('固定 30 秒自动刷新，不再提供刷新频率选项')).toBeInTheDocument();
  expect(panel.getByText('与下方「流水线健康」的时间段互不关联')).toBeInTheDocument();

  const values = Array.from(container.querySelectorAll('.insight-kpi-value')).map((node) => node.textContent);
  expect(values).toEqual(['5', '7', '3', '21']);

  const categories = Array.from(container.querySelectorAll('.insight-cat-row')).map((row) => ({
    label: row.querySelector('.insight-cat-label')?.textContent,
    value: row.querySelector('.insight-cat-value')?.textContent,
    percent: row.querySelector('.insight-cat-pct')?.textContent,
    width: (row.querySelector('.insight-cat-fill') as HTMLElement).style.width,
  }));
  expect(categories).toEqual([
    { label: '需求', value: '6', percent: '60.0%', width: '100.0%' },
    { label: '缺陷', value: '2', percent: '20.0%', width: '33.3%' },
    { label: '任务', value: '2', percent: '20.0%', width: '33.3%' },
  ]);
  expect(panel.getByText('按工单类型统计全部工单（含已取消）')).toBeInTheDocument();

  expect(panel.getByRole('img', { name: '工单状态占比堆叠条' })).toBeInTheDocument();
  const segments = Array.from(container.querySelectorAll('.insight-status-seg')) as HTMLElement[];
  expect(segments.map((node) => node.style.width)).toEqual(['10.00%', '20.00%', '30.00%', '40.00%']);
  expect(segments.map((node) => node.getAttribute('aria-label'))).toEqual([
    '待开始：1 工单 · 10.0%',
    '进行中：2 工单 · 20.0%',
    '已完成：3 工单 · 30.0%',
    '已取消：4 工单 · 40.0%',
  ]);
  const legend = Array.from(container.querySelectorAll('.insight-status-item')).map((item) => ({
    label: item.querySelector('span')?.textContent,
    value: item.querySelector('b')?.textContent,
    percent: item.querySelector('.insight-status-pct')?.textContent,
  }));
  expect(legend).toEqual([
    { label: '待开始', value: '1', percent: '10.0%' },
    { label: '进行中', value: '2', percent: '20.0%' },
    { label: '已完成', value: '3', percent: '30.0%' },
    { label: '已取消', value: '4', percent: '40.0%' },
  ]);
  expect(panel.getByText('占比基于全部工单；已取消以灰色段呈现，不参与高亮')).toBeInTheDocument();
});

it('shows the status tooltip on hover with count and share', async () => {
  server.use(overviewHandler());
  const user = userEvent.setup();
  renderPanel();
  await waitFor(() => expect(document.querySelectorAll('.insight-status-seg')).toHaveLength(4));

  const done = document.querySelectorAll('.insight-status-seg')[2];
  await user.hover(done);

  expect(await screen.findByRole('tooltip')).toHaveTextContent('已完成：3 工单 · 30.0%');
});

it('normalizes an all-zero inventory without NaN or division artifacts', async () => {
  server.use(overviewHandler({
    ...overview,
    inventory: { byLifecycle: { init: 0, inProgress: 0, done: 0, canceled: 0 }, byType: { req: 0, bug: 0, task: 0 } },
  }));
  const { container } = renderPanel();
  await waitFor(() => expect(container.querySelectorAll('.insight-cat-row')).toHaveLength(3));

  expect(screen.queryByText(/NaN/)).not.toBeInTheDocument();
  const percents = Array.from(container.querySelectorAll('.insight-cat-pct, .insight-status-pct')).map((node) => node.textContent);
  expect(percents.every((text) => text === '0.0%')).toBe(true);
  const widths = Array.from(container.querySelectorAll('.insight-cat-fill, .insight-status-seg')) as HTMLElement[];
  expect(widths.every((node) => node.style.width === '0%')).toBe(true);
});

it('does not call a single workitem an empty state', async () => {
  server.use(overviewHandler({
    ...overview,
    inventory: { byLifecycle: { init: 1, inProgress: 0, done: 0, canceled: 0 }, byType: { req: 1, bug: 0, task: 0 } },
  }));
  const { container } = renderPanel();
  await waitFor(() => expect(container.querySelector('.insight-cat-value')?.textContent).toBe('1'));

  expect(screen.queryByText(/暂无数据/)).not.toBeInTheDocument();
  const firstCategory = container.querySelector('.insight-cat-row');
  expect(firstCategory?.querySelector('.insight-cat-value')?.textContent).toBe('1');
  expect(firstCategory?.querySelector('.insight-cat-pct')?.textContent).toBe('100.0%');
});

it('refreshes the snapshot every 30 seconds without any interval selector', async () => {
  vi.useFakeTimers();
  const get = vi.spyOn(apiClient, 'get').mockResolvedValue({ data: overview } as never);
  try {
    const { unmount } = renderPanel();
    await act(async () => { await vi.advanceTimersByTimeAsync(0); });

    expect(get).toHaveBeenCalledTimes(1);
    expect(get.mock.calls[0][0]).toBe('/api/dashboard/overview');
    expect(screen.getByText('正在运行')).toBeInTheDocument();
    expect(screen.queryByRole('combobox')).not.toBeInTheDocument();
    expect(screen.queryByRole('radio')).not.toBeInTheDocument();
    expect(screen.queryByText('10s')).not.toBeInTheDocument();
    expect(screen.queryByText('关闭')).not.toBeInTheDocument();

    await act(async () => { await vi.advanceTimersByTimeAsync(29_999); });
    expect(get).toHaveBeenCalledTimes(1);

    await act(async () => { await vi.advanceTimersByTimeAsync(1); });
    expect(get).toHaveBeenCalledTimes(2);
    unmount();
  } finally {
    vi.restoreAllMocks();
    vi.useRealTimers();
  }
});

it('refreshes only the snapshot from its own refresh button', async () => {
  const requests: string[] = [];
  server.use(http.get('/api/dashboard/overview', ({ request }) => {
    requests.push(new URL(request.url).pathname);
    return ok(overview);
  }));
  const user = userEvent.setup();
  renderPanel();
  await screen.findByText('正在运行');

  await user.click(screen.getByRole('button', { name: /刷\s*新/ }));

  await waitFor(() => expect(requests).toHaveLength(2));
});

it('keeps the snapshot usable when the query fails and recovers on retry', async () => {
  let calls = 0;
  server.use(http.get('/api/dashboard/overview', () => {
    calls += 1;
    return calls === 1
      ? HttpResponse.json({ success: false, code: '50000', message: 'overview 聚合失败', data: null, traceId: null }, { status: 500 })
      : ok(overview);
  }));
  const user = userEvent.setup();
  renderPanel();

  expect(await screen.findByText('工单概况加载失败')).toBeInTheDocument();
  expect(screen.getByText('overview 聚合失败')).toBeInTheDocument();
  expect(screen.queryByText('正在运行')).not.toBeInTheDocument();

  await user.click(screen.getByRole('button', { name: /重\s*试/ }));

  expect(await screen.findByText('正在运行')).toBeInTheDocument();
  expect(calls).toBe(2);
});

it('shows an empty state when the backend returns no snapshot', async () => {
  server.use(overviewHandler(null));
  renderPanel();

  expect(await screen.findByText('暂无工单概况数据')).toBeInTheDocument();
  expect(screen.queryByText('工单概况加载失败')).not.toBeInTheDocument();
});

it('keeps a missing or unparsable generatedAt visible as a placeholder or raw value', async () => {
  server.use(overviewHandler({ ...overview, generatedAt: undefined }));
  const { unmount } = renderPanel();
  await screen.findByText('正在运行');
  expect(screen.getByText(/刷新时间/, { selector: '.insight-stamp' })).toHaveTextContent('刷新时间 —');
  unmount();

  server.resetHandlers();
  server.use(overviewHandler({ ...overview, generatedAt: 'not-a-date' }));
  renderPanel();
  await waitFor(() => expect(screen.getByText(/刷新时间/, { selector: '.insight-stamp' })).toHaveTextContent('刷新时间 not-a-date'));
});

it('forwards only the drill-down KPIs to the modal owner', async () => {
  server.use(overviewHandler());
  const onKpiClick = vi.fn();
  const user = userEvent.setup();
  renderPanel({ onKpiClick });

  await screen.findByText('正在运行');
  await user.click(screen.getByText('正在运行'));
  expect(onKpiClick).toHaveBeenCalledWith('runningWorkitems');

  await user.click(screen.getByText('今日新建'));
  expect(onKpiClick).toHaveBeenCalledTimes(1);
});
