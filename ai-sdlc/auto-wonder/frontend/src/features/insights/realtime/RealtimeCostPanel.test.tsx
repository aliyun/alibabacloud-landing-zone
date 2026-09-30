import { describe, it, expect, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import RealtimeCostPanel, { type RealtimeCostPanelProps } from './RealtimeCostPanel';
import type { InsightMetrics, InsightWorker } from '../types';

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
  return within(screen.getByRole('region', { name: 'Credits 成本' }));
}

describe('RealtimeCostPanel', () => {
  it('shows credits as the main KPI and keeps token detail behind hover', async () => {
    const user = userEvent.setup();
    renderPanel();

    expect(costRegion().getByText('总 Credits')).toBeInTheDocument();
    expect(costRegion().getByText('82.61')).toBeInTheDocument();
    expect(costRegion().getByText('12.5')).toBeInTheDocument();
    expect(costRegion().getByText('2.75')).toBeInTheDocument();
    expect(screen.queryByText('总 Token:')).not.toBeInTheDocument();

    await user.hover(costRegion().getByText('总 Credits'));

    expect(await screen.findByText('总 Token:')).toBeInTheDocument();
    expect(screen.getByText('100K')).toBeInTheDocument();
    expect(screen.getByText('5.0K')).toBeInTheDocument();
    expect(screen.getByText('14K')).toBeInTheDocument();
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
    expect(await screen.findByText('总 Token:')).toBeInTheDocument();
    expect(screen.getByText('100K')).toBeInTheDocument();
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
});
