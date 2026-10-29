import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { afterEach, expect, it, vi } from 'vitest';
import { i18n } from '@/shared/i18n';
import { server } from '@/test/mocks/server';
import PipelineHealthPanel from './PipelineHealthPanel';
import type { PipelineHealth, PipelineRange } from './api';

const fixedNow = new Date('2026-10-13T15:00:00Z');

function health(range: PipelineRange, overrides: Partial<PipelineHealth> = {}): PipelineHealth {
  return {
    range,
    successRate: 86.666,
    successSub: { succeeded: 13, total: 15 },
    failedOrTimeout: 2,
    rework: { rate: 12.5, reworkedWorkitems: 1, executedWorkitems: 8, retryCount: 3 },
    avgWorkitemDurationMinutes: 90,
    generatedAt: '2026-10-13T04:05:06Z',
    ...overrides,
  };
}

const ok = (value: unknown) => HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: value });

function renderPanel() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <PipelineHealthPanel />
    </QueryClientProvider>,
  );
}

async function panelRegion() {
  return within(await screen.findByRole('region', { name: '流水线健康' }));
}

function cards(container: HTMLElement) {
  return Array.from(container.querySelectorAll('.insight-pl-card')).map((card) => ({
    label: card.querySelector('.insight-pl-label')?.textContent,
    value: card.querySelector('.insight-pl-value')?.textContent,
    sub: card.querySelector('.insight-pl-sub')?.textContent,
  }));
}

function useFixedDate() {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(fixedNow);
}

afterEach(async () => {
  cleanup();
  vi.useRealTimers();
  await i18n.changeLanguage('zh-CN');
});

it('opens on today, renders one-decimal metrics and spells out the Shanghai window', async () => {
  useFixedDate();
  const requests: string[] = [];
  server.use(http.get('/api/dashboard/pipeline-health', ({ request }) => {
    requests.push(new URL(request.url).search);
    return ok(health('today'));
  }));
  const { container } = renderPanel();
  const panel = await panelRegion();
  await waitFor(() => expect(container.querySelectorAll('.insight-pl-card')).toHaveLength(4));

  expect(requests).toEqual(['?range=today']);
  expect(panel.getByRole('radio', { name: '今日' })).toBeChecked();
  expect(panel.getByText('所选时间段内有执行行为的工单')).toBeInTheDocument();
  expect(panel.getByText('切换时间段后以下 4 项指标自动刷新')).toBeInTheDocument();
  expect(panel.getByText('不受上方实时快照影响')).toBeInTheDocument();

  const chip = container.querySelector('.insight-pl-chip') as HTMLElement;
  expect(chip.textContent).toContain('今日');
  expect(chip).toHaveAttribute('title', '今日窗口：2026-10-13 00:00 — 2026-10-14 00:00（Asia/Shanghai）');

  expect(cards(container)).toEqual([
    { label: '成功率', value: '86.7%', sub: '13 / 15 次执行' },
    { label: '失败/超时', value: '2', sub: '含超时中断的执行' },
    { label: '返工重试率', value: '12.5%', sub: '1 / 8 工单有返工' },
    { label: '平均耗时', value: '1h 30m', sub: '工单口径 · 创建→首次完成' },
  ]);
  expect(panel.getByText('实时')).toBeInTheDocument();
});

it('keeps the previous range on screen while the next range loads', async () => {
  useFixedDate();
  let release!: () => void;
  const pending = new Promise<void>((resolve) => { release = resolve; });
  const requests: string[] = [];
  server.use(http.get('/api/dashboard/pipeline-health', async ({ request }) => {
    requests.push(new URL(request.url).search);
    if (requests.length === 1) return ok(health('today'));
    await pending;
    return ok(health('7d', { successRate: 80 }));
  }));
  const user = userEvent.setup();
  const { container } = renderPanel();
  const panel = await panelRegion();
  await waitFor(() => expect(container.querySelector('.insight-pl-value')?.textContent).toBe('86.7%'));

  await user.click(panel.getByText('近7天'));

  await waitFor(() => expect(requests).toEqual(['?range=today', '?range=7d']));
  expect(await panel.findByText('更新中…')).toBeInTheDocument();
  // keepPreviousData：时间段切换期间旧值不闪空
  expect(container.querySelector('.insight-pl-value')?.textContent).toBe('86.7%');
  const chip = container.querySelector('.insight-pl-chip') as HTMLElement;
  expect(chip.textContent).toContain('近7天');
  expect(chip).toHaveAttribute('title', '近7天窗口：2026-10-06 00:00 — 2026-10-13 00:00（Asia/Shanghai，不含今日）');
  expect(panel.queryByText('实时')).not.toBeInTheDocument();

  await act(async () => { release(); });

  expect(await panel.findByText('80.0%')).toBeInTheDocument();
  expect(panel.queryByText('更新中…')).not.toBeInTheDocument();
});

it('requests every range from the segmented control', async () => {
  useFixedDate();
  const requests: string[] = [];
  server.use(http.get('/api/dashboard/pipeline-health', ({ request }) => {
    const range = new URL(request.url).searchParams.get('range');
    requests.push(range ?? '');
    return ok(health(range as PipelineRange));
  }));
  const user = userEvent.setup();
  renderPanel();
  const panel = await panelRegion();

  await user.click(panel.getByText('近30天'));
  await waitFor(() => expect(requests.at(-1)).toBe('30d'));

  await user.click(panel.getByText('近90天'));
  await waitFor(() => expect(requests.at(-1)).toBe('90d'));

  expect(panel.getByRole('radio', { name: '近90天' })).toBeChecked();
});

it('renders em dashes for missing rates and durations', async () => {
  useFixedDate();
  server.use(http.get('/api/dashboard/pipeline-health', () => ok(health('today', {
    successRate: null,
    successSub: { succeeded: 0, total: 0 },
    failedOrTimeout: 0,
    rework: { rate: null, reworkedWorkitems: 0, executedWorkitems: 0, retryCount: 0 },
    avgWorkitemDurationMinutes: null,
  }))));
  const { container } = renderPanel();
  await panelRegion();
  await waitFor(() => expect(container.querySelectorAll('.insight-pl-card')).toHaveLength(4));

  expect(cards(container)).toEqual([
    { label: '成功率', value: '—', sub: '0 / 0 次执行' },
    { label: '失败/超时', value: '0', sub: '含超时中断的执行' },
    { label: '返工重试率', value: '—', sub: '0 / 0 工单有返工' },
    { label: '平均耗时', value: '—', sub: '工单口径 · 创建→首次完成' },
  ]);
  expect(screen.queryByText(/NaN/)).not.toBeInTheDocument();
});

it('explains the rework formula on hover and focus and closes the focused card with Escape', async () => {
  useFixedDate();
  server.use(http.get('/api/dashboard/pipeline-health', () => ok(health('today'))));
  const user = userEvent.setup();
  const { container } = renderPanel();
  await panelRegion();
  await waitFor(() => expect(container.querySelectorAll('.insight-pl-card')).toHaveLength(4));

  const reworkCard = container.querySelectorAll('.insight-pl-card')[2] as HTMLElement;
  expect(reworkCard).toHaveAttribute('tabindex', '0');

  await user.hover(reworkCard);
  const tooltip = await screen.findByRole('tooltip');
  expect(tooltip).toHaveTextContent('返工重试次数：3 次');
  expect(tooltip).toHaveTextContent('返工重试率 = 有返工重试的工单 / 有执行行为的工单（1 / 8）· 今日');

  await user.unhover(reworkCard);
  act(() => { reworkCard.focus(); });
  expect(await screen.findByRole('tooltip')).toHaveTextContent('返工重试次数：3 次');

  fireEvent.keyDown(reworkCard, { key: 'Escape' });
  expect(document.activeElement).not.toBe(reworkCard);
});

it('explains the shared completion caliber on the average duration card', async () => {
  useFixedDate();
  server.use(http.get('/api/dashboard/pipeline-health', () => ok(health('today'))));
  const user = userEvent.setup();
  const { container } = renderPanel();
  await panelRegion();
  await waitFor(() => expect(container.querySelectorAll('.insight-pl-card')).toHaveLength(4));

  const avgCard = container.querySelectorAll('.insight-pl-card')[3] as HTMLElement;
  expect(avgCard).toHaveAttribute('tabindex', '0');
  await user.hover(avgCard);
  const tooltip = await screen.findByRole('tooltip');
  expect(tooltip).toHaveTextContent('平均耗时 = 首次完成时刻 − 创建时刻，按分钟四舍五入。');
  expect(tooltip).toHaveTextContent('与人机协作「平均完成时长」同源：共享 T+1 快照事实层，今日窗口使用实时尾数据。');
});

it('keeps the panel usable when the query fails and recovers on retry', async () => {
  useFixedDate();
  let calls = 0;
  server.use(http.get('/api/dashboard/pipeline-health', () => {
    calls += 1;
    return calls === 1
      ? HttpResponse.json({ success: false, code: '50000', message: 'pipeline 聚合失败', data: null, traceId: null }, { status: 500 })
      : ok(health('today'));
  }));
  const user = userEvent.setup();
  renderPanel();

  expect(await screen.findByText('流水线健康加载失败')).toBeInTheDocument();
  expect(screen.getByText('pipeline 聚合失败')).toBeInTheDocument();
  expect(screen.queryByText('成功率')).not.toBeInTheDocument();

  await user.click(screen.getByRole('button', { name: /重\s*试/ }));

  expect(await screen.findByText('成功率')).toBeInTheDocument();
  expect(calls).toBe(2);
});

it('shows an empty state when the backend returns no health data', async () => {
  useFixedDate();
  server.use(http.get('/api/dashboard/pipeline-health', () => ok(null)));
  renderPanel();

  expect(await screen.findByText('暂无流水线健康数据')).toBeInTheDocument();
  expect(screen.queryByText('流水线健康加载失败')).not.toBeInTheDocument();
});

it('switches the whole panel to the current language without losing the range', async () => {
  useFixedDate();
  server.use(http.get('/api/dashboard/pipeline-health', () => ok(health('7d', { successRate: 80 }))));
  const user = userEvent.setup();
  const { container } = renderPanel();
  await panelRegion();
  await user.click(screen.getByText('近7天'));
  await screen.findByText('80.0%');

  await act(async () => { await i18n.changeLanguage('en-US'); });

  expect(screen.getByText('Pipeline health')).toBeInTheDocument();
  expect(screen.getByText('Success rate')).toBeInTheDocument();
  expect(screen.getByText('Rework rate')).toBeInTheDocument();
  expect(screen.getByText('Average duration')).toBeInTheDocument();
  expect(screen.getByText('Work item scope · created → first completion')).toBeInTheDocument();
  expect(screen.getByRole('radio', { name: 'Last 7 days' })).toBeChecked();
  const chip = container.querySelector('.insight-pl-chip') as HTMLElement;
  expect(chip).toHaveAttribute('title', 'Last 7 days window: 2026-10-06 00:00 — 2026-10-13 00:00 (Asia/Shanghai, excluding today)');
  expect(screen.getByText('Unaffected by the realtime snapshot above')).toBeInTheDocument();
});
