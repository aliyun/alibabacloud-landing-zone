import { CreditsCostCard } from '../components/CreditsCostCard';
import { i18n } from '@/shared/i18n';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { afterEach, beforeEach, describe, it, expect, vi } from 'vitest';
import { act, cleanup, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import RealtimeCostPanel, { type RealtimeCostPanelProps } from './RealtimeCostPanel';
import type { InsightMetrics, InsightWorker } from '../types';

// antd Tooltip 延迟出现，负向断言必须等过该延迟，否则回归会被漏过。
async function waitPastTooltipDelay() {
  await act(async () => { await new Promise((resolve) => setTimeout(resolve, 300)); });
}

const cost = {
  totalTokens: 100000,
  avgTokensPerTask: 5000,
  dailyAvg: 14000,
  trend: [10, 12, 11, 13, 14, 12, 15],
  totalCredits: 82.61,
  avgCreditsPerTask: 12.5,
  dailyAvgCredits: 2.75,
  creditsTrend: [1.2, 2.5, 3.1, 4, 5.5, 6.2, 7.1],
};

const metrics: InsightMetrics = {
  cost,
  efficiency: { completionRate: 80, totalTasks: 20, completedTasks: 16, avgDurationMinutes: 30, trend: [80] },
  stability: { successRate: 90, retryCount: 2, blockedCount: 1, trend: [90] },
  security: { highRiskOps: 1, complianceRate: 98, auditBlocks: 3, trend: [1] },
};

const workers: InsightWorker[] = [
  { id: 12, name: '验收数字员工' },
  { id: 13, name: '备用数字员工' },
];

function renderPanel(overrides: Partial<RealtimeCostPanelProps> = {}) {
  const props: RealtimeCostPanelProps = {
    workerId: undefined,
    onWorkerChange: vi.fn(),
    timeRange: '30d',
    onTimeRangeChange: vi.fn(),
    workers,
    metrics,
    isLoading: false,
    isError: false,
    error: null,
    ...overrides,
  };
  return { ...render(<RealtimeCostPanel {...props} />), props };
}

function costRegion() {
  return within(screen.getByRole('region', { name: '成本分析' }));
}

describe('RealtimeCostPanel', () => {
  it('shows credits as the main KPI and no tooltip on hover', async () => {
    const user = userEvent.setup();
    renderPanel();

    expect(costRegion().getByText('总 Credits')).toBeInTheDocument();
    expect(costRegion().getByText('82.61')).toBeInTheDocument();
    expect(costRegion().getByText('12.5')).toBeInTheDocument();
    expect(costRegion().getByText('2.75')).toBeInTheDocument();

    await user.hover(costRegion().getByText('总 Credits'));
    await waitPastTooltipDelay();

    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument();
    expect(screen.queryByText('总 Token:')).not.toBeInTheDocument();
    expect(screen.queryByText('均/任务:')).not.toBeInTheDocument();
    expect(screen.queryByText('日均:')).not.toBeInTheDocument();
    expect(screen.queryByText('100K')).not.toBeInTheDocument();
    expect(screen.queryByText('5.0K')).not.toBeInTheDocument();
    expect(screen.queryByText('14K')).not.toBeInTheDocument();
  });

  it('centers the three cost KPIs as one group without changing their order', () => {
    renderPanel();

    const group = costRegion().getByText('总 Credits').closest('.metric-card-kpis');
    expect(group).not.toBeNull();
    expect(group).toHaveClass('metric-card-kpis', 'is-centered');
    const labels = Array.from(group!.querySelectorAll('span'))
      .map((node) => node.textContent)
      .filter((text) => text === '总 Credits' || text === '均/任务' || text === '日均');
    expect(labels).toEqual(['总 Credits', '均/任务', '日均']);
  });

  it('renders zero credits without NaN when there is no usage data', () => {
    renderPanel({
      metrics: {
        ...metrics,
        cost: {
          ...cost,
          totalTokens: 0,
          avgTokensPerTask: 0,
          dailyAvg: 0,
          trend: [0, 0, 0, 0, 0, 0, 0],
          totalCredits: 0,
          avgCreditsPerTask: 0,
          dailyAvgCredits: 0,
          creditsTrend: [0, 0, 0, 0, 0, 0, 0],
        },
      },
    });

    expect(costRegion().getAllByText('0')).toHaveLength(3);
    expect(screen.queryByText(/NaN/)).not.toBeInTheDocument();
  });

  it('falls back to zero credits when a legacy payload has no credits fields', async () => {
    const user = userEvent.setup();
    // 前后端独立发布：旧版后端只返回 token 三项，credits 字段缺失时不得崩溃或显示 NaN。
    renderPanel({
      metrics: {
        ...metrics,
        cost: {
          totalTokens: 100000,
          avgTokensPerTask: 5000,
          dailyAvg: 14000,
          trend: [10, 12, 11, 13, 14, 12, 15],
        } as InsightMetrics['cost'],
      },
    });

    expect(costRegion().getAllByText('0')).toHaveLength(3);
    expect(screen.queryByText(/NaN/)).not.toBeInTheDocument();

    await user.hover(costRegion().getByText('总 Credits'));
    await waitPastTooltipDelay();
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument();
    expect(screen.queryByText('总 Token:')).not.toBeInTheDocument();
    expect(screen.queryByText('100K')).not.toBeInTheDocument();
  });

  it('keeps the filters usable and shows only a local loading indicator while loading', () => {
    renderPanel({ metrics: undefined, isLoading: true });

    expect(screen.queryByText('总 Credits')).not.toBeInTheDocument();
    expect(costRegion().getByRole('combobox', { name: '成本数字员工' })).toBeInTheDocument();
    expect(document.querySelector('.ant-spin')).not.toBeNull();
  });

  it('shows a local failure hint when the cost query fails', () => {
    renderPanel({ metrics: undefined, isError: true, error: new Error('metrics 聚合失败') });

    expect(costRegion().getByText('Credits 成本加载失败')).toBeInTheDocument();
    expect(costRegion().getByText('metrics 聚合失败')).toBeInTheDocument();
    expect(screen.queryByText('总 Credits')).not.toBeInTheDocument();
  });

  it('falls back to a generic hint when the failure carries no message', () => {
    renderPanel({ metrics: undefined, isError: true, error: null });

    expect(costRegion().getByText('Credits 成本加载失败')).toBeInTheDocument();
    expect(costRegion().getByText('请稍后重试')).toBeInTheDocument();
  });

  it('keeps the last cost data visible when a later refresh fails', () => {
    renderPanel({ isError: true, error: new Error('metrics 聚合失败') });

    expect(costRegion().getByText('总 Credits')).toBeInTheDocument();
    expect(costRegion().getByText('Credits 成本加载失败')).toBeInTheDocument();
  });

  it('forwards worker and time range changes to the owner', async () => {
    const user = userEvent.setup();
    const { props } = renderPanel();

    await user.click(screen.getByRole('combobox', { name: '成本数字员工' }));
    const workerOptions = await screen.findAllByText('验收数字员工');
    await user.click(workerOptions[workerOptions.length - 1]);
    expect(props.onWorkerChange).toHaveBeenCalledWith(12);

    await user.click(screen.getByText('近7天'));
    expect(props.onTimeRangeChange).toHaveBeenCalledWith('7d');
  });

  it('defaults to the all-agent scope with the first dropdown option and no clear control', async () => {
    const user = userEvent.setup();
    renderPanel();

    expect(costRegion().getByText('全部数字员工')).toBeInTheDocument();
    expect(costRegion().getByText('全部数字员工 · 近30天')).toBeInTheDocument();
    expect(costRegion().getByText('该组筛选仅作用于本面板的成本数据，不影响下方实时指标')).toBeInTheDocument();
    expect(costRegion().queryByRole('button', { name: '清除数字员工筛选' })).not.toBeInTheDocument();

    await user.click(costRegion().getByRole('combobox', { name: '成本数字员工' }));
    const options = await screen.findAllByRole('option');
    expect(options[0]).toHaveAttribute('aria-label', '全部数字员工');
    expect(options[0]).toHaveAttribute('aria-selected', 'true');
  });

  it('clears a specific agent from the scope chip back to the all-agent view', async () => {
    const user = userEvent.setup();
    const { props } = renderPanel({ workerId: 12, timeRange: '7d' });

    expect(costRegion().getByText('验收数字员工 · 近7天')).toBeInTheDocument();
    const clear = costRegion().getByRole('button', { name: '清除数字员工筛选' });
    expect(clear).toHaveAttribute('title', '清除筛选，回到全部数字员工');
    await user.click(clear);

    expect(props.onWorkerChange).toHaveBeenCalledWith(undefined);
  });

  it('returns to the all-agent scope from the dropdown default row', async () => {
    const user = userEvent.setup();
    const { props } = renderPanel({ workerId: 12 });

    await user.click(costRegion().getByRole('combobox', { name: '成本数字员工' }));
    const options = await screen.findAllByRole('option');
    expect(options[0]).toHaveAttribute('aria-label', '全部数字员工');
    const allAgentLabels = await screen.findAllByText('全部数字员工');
    await user.click(allAgentLabels[allAgentLabels.length - 1]);

    expect(props.onWorkerChange).toHaveBeenCalledWith(undefined);
  });

  it('falls back to the stored worker id when the worker list has no matching name', () => {
    renderPanel({ workerId: 99, workers: [] });

    expect(costRegion().getByText('99 · 近30天')).toBeInTheDocument();
  });
});

beforeEach(async () => { await i18n.changeLanguage('zh-CN'); });
afterEach(async () => { cleanup(); await i18n.changeLanguage('zh-CN'); });
it('switches the independently cached cost panel without token detail while preserving filter codes', async () => {
  const onWorkerChange = vi.fn();
  const onTimeRangeChange = vi.fn();
  const router = createMemoryRouter([{ path: '/', element: <RealtimeCostPanel workerId={12} onWorkerChange={onWorkerChange} timeRange="7d" onTimeRangeChange={onTimeRangeChange} workers={workers} metrics={metrics} isLoading={false} isError={true} error={new Error('<b>原始错误</b>')} /> }]);
  render(<RouterProvider router={router} />);
  const select = screen.getByRole('combobox', { name: '成本数字员工' });
  await userEvent.hover(screen.getByText('总 Credits'));
  await waitPastTooltipDelay();
  expect(screen.queryByRole('tooltip')).not.toBeInTheDocument();
  await act(async () => { await i18n.changeLanguage('en-US'); });
  expect(screen.getByRole('combobox', { name: 'Cost Agent' })).toBe(select);
  expect(screen.getByText('验收数字员工')).toBeInTheDocument();
  expect(screen.getByText('Failed to load Credits cost')).toBeInTheDocument();
  expect(screen.getByText('<b>原始错误</b>')).toBeInTheDocument();
  expect(screen.getByText('Total Credits')).toBeInTheDocument();
  expect(screen.queryByText('Total tokens:')).not.toBeInTheDocument();
  expect(screen.queryByText('100K')).not.toBeInTheDocument();
  expect(screen.getByRole('radio', { name: 'Last 7 days' })).toBeChecked();
  await userEvent.click(screen.getByText('Last 90 days'));
  expect(onTimeRangeChange).toHaveBeenCalledWith('90d');
  expect(onWorkerChange).not.toHaveBeenCalled();
});

it('updates a directly cached shared Credits card across languages without token detail', async () => {
  const router = createMemoryRouter([{ path: '/', element: <CreditsCostCard cost={cost} /> }]);
  render(<RouterProvider router={router} />);
  await userEvent.hover(screen.getByText('总 Credits'));
  await waitPastTooltipDelay();
  expect(screen.queryByRole('tooltip')).not.toBeInTheDocument();
  await act(async () => { await i18n.changeLanguage('en-US'); });
  expect(screen.getByText('Total Credits')).toBeInTheDocument();
  expect(screen.getByText('82.61')).toBeInTheDocument();
  expect(screen.queryByText('Total tokens:')).not.toBeInTheDocument();
  expect(screen.queryByText('100K')).not.toBeInTheDocument();
  expect(screen.queryByText('5.0K')).not.toBeInTheDocument();
  expect(screen.queryByText('14K')).not.toBeInTheDocument();
  expect(screen.queryByRole('img', { name: 'dollar' })).not.toBeInTheDocument();
});
it('updates local loading and generic error text in both languages', async () => {
  const { rerender, props } = renderPanel({ metrics: undefined, isLoading: true });
  expect(screen.getByText('加载 Credits 成本…')).toBeInTheDocument();
  await act(async () => { await i18n.changeLanguage('en-US'); });
  expect(screen.getByText('Loading Credits cost…')).toBeInTheDocument();
  rerender(<RealtimeCostPanel {...props} isLoading={false} isError error={null} />);
  expect(screen.getByText('Failed to load Credits cost')).toBeInTheDocument();
  expect(screen.getByText('Try again later')).toBeInTheDocument();
  await act(async () => { await i18n.changeLanguage('zh-CN'); });
  expect(screen.getByText('请稍后重试')).toBeInTheDocument();
});
