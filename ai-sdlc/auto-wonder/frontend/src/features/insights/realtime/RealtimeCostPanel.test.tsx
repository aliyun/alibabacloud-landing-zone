import { CreditsCostCard } from '../components/CreditsCostCard';
import { i18n } from '@/shared/i18n';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { afterEach, beforeEach, describe, it, expect, vi } from 'vitest';
import { act, cleanup, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import RealtimeCostPanel, { type RealtimeCostPanelProps } from './RealtimeCostPanel';
import type { CostComposition, CostKpi } from './api';
import type { InsightWorker } from '../types';

// antd Tooltip 延迟出现，负向断言必须等过该延迟，否则回归会被漏过。
async function waitPastTooltipDelay() {
  await act(async () => { await new Promise((resolve) => setTimeout(resolve, 300)); });
}

const cost: CostKpi = { range: 30, agentId: 'all', total: 82.61, perTask: 12.5, perDay: 2.75 };

const composition: CostComposition = {
  range: 30,
  scope: 'ALL_AGENTS',
  total: 82.61,
  sources: [
    { key: 'WORKITEM_EXECUTION', name: '工单执行', credits: 76, share: 0.92 },
    { key: 'REQUIREMENT_CLARIFY', name: '需求澄清', credits: 6.61, share: 0.08 },
  ],
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
    cost,
    composition,
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

function compositionRegion() {
  return within(screen.getByRole('region', { name: 'Credits 来源构成' }));
}

// 成本分析面板内含统计块与来源构成两块，金额断言必须收窄到统计卡，避免与构成合计重名。
function statsRegion() {
  return within(costRegion().getByText('总 Credits').closest('.cost-card') as HTMLElement);
}

describe('RealtimeCostPanel', () => {
  it('shows the scoped kpi.cost as the main KPI and no token tooltip', async () => {
    const user = userEvent.setup();
    renderPanel();

    expect(costRegion().getByText('总 Credits')).toBeInTheDocument();
    expect(statsRegion().getByText('82.61')).toBeInTheDocument();
    expect(statsRegion().getByText('12.5')).toBeInTheDocument();
    expect(statsRegion().getByText('2.75')).toBeInTheDocument();

    await user.hover(costRegion().getByText('总 Credits'));
    await waitPastTooltipDelay();

    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument();
    expect(screen.queryByText('总 Token:')).not.toBeInTheDocument();
    expect(screen.queryByText('100K')).not.toBeInTheDocument();
    expect(screen.queryByText('5.0K')).not.toBeInTheDocument();
    expect(screen.queryByText('14K')).not.toBeInTheDocument();
  });

  it('centers the three cost KPIs as one group without changing their order', () => {
    renderPanel();

    const group = costRegion().getByText('总 Credits').closest('.metric-card-kpis');
    expect(group).not.toBeNull();
    expect(group).toHaveClass('metric-card-kpis', 'is-centered', 'cost-kpis');
    const card = group!.parentElement;
    expect(card).toHaveClass('cost-card');
    const items = Array.from(card!.querySelectorAll('.cost-kpi'));
    expect(items).toHaveLength(3);
    expect(items.map((item) => item.querySelector('.cost-kpi-label')?.textContent)).toEqual(['总 Credits', '均/任务', '日均']);
    expect(items.map((item) => item.querySelector('.cost-kpi-value')?.textContent)).toEqual(['82.61', '12.5', '2.75']);
  });

  it('drops the duplicate cost title and sparkline from the cost panel card', () => {
    renderPanel();

    const card = costRegion().getByText('总 Credits').closest('.cost-card');
    expect(card).not.toBeNull();
    expect(within(card as HTMLElement).queryByText('成本')).not.toBeInTheDocument();
    expect(card!.querySelector('svg')).toBeNull();
    expect(costRegion().getByRole('heading', { name: '成本分析' })).toBeInTheDocument();
  });

  it('renders zero credits without NaN when there is no usage data', () => {
    renderPanel({
      cost: { range: 30, agentId: 'all', total: 0, perTask: 0, perDay: 0 },
      composition: { range: 30, scope: 'ALL_AGENTS', total: 0, sources: [] },
    });

    expect(statsRegion().getAllByText('0')).toHaveLength(3);
    expect(screen.queryByText(/NaN/)).not.toBeInTheDocument();
    expect(compositionRegion().getByText('所选范围暂无 Credits 消耗')).toBeInTheDocument();
  });

  it('no longer renders the scope note under the panel header', () => {
    renderPanel();

    expect(screen.queryByText('统计口径：')).not.toBeInTheDocument();
    expect(screen.queryByText('所选数字员工 × 日期范围')).not.toBeInTheDocument();
    expect(screen.queryByText('该组筛选仅作用于本面板的成本数据，不影响下方实时指标')).not.toBeInTheDocument();
    expect(document.querySelector('.insight-panel--cost .insight-panel-note')).toBeNull();
  });

  it('keeps the filters usable and shows only a local loading indicator while loading', () => {
    renderPanel({ cost: undefined, composition: null, isLoading: true });

    expect(screen.queryByText('总 Credits')).not.toBeInTheDocument();
    expect(costRegion().getByRole('combobox', { name: '成本数字员工' })).toBeInTheDocument();
    expect(document.querySelector('.ant-spin')).not.toBeNull();
  });

  it('shows a local failure hint when the cost query fails', () => {
    renderPanel({ cost: undefined, composition: null, isError: true, error: new Error('realtime 聚合失败') });

    expect(costRegion().getByText('Credits 成本加载失败')).toBeInTheDocument();
    expect(costRegion().getByText('realtime 聚合失败')).toBeInTheDocument();
    expect(screen.queryByText('总 Credits')).not.toBeInTheDocument();
  });

  it('falls back to a generic hint when the failure carries no message', () => {
    renderPanel({ cost: undefined, composition: null, isError: true, error: null });

    expect(costRegion().getByText('Credits 成本加载失败')).toBeInTheDocument();
    expect(costRegion().getByText('请稍后重试')).toBeInTheDocument();
  });

  it('keeps the last cost data visible when a later refresh fails', () => {
    renderPanel({ isError: true, error: new Error('realtime 聚合失败') });

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
    expect(screen.getByTestId('cost-composition-chip')).toHaveTextContent('全部数字员工 · 近30天');
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

  it('renders the composition bar and legend with absolute credits and shares', () => {
    renderPanel();

    expect(compositionRegion().getByRole('heading', { name: 'Credits 来源构成' })).toBeInTheDocument();
    const bar = screen.getByTestId('cost-composition-bar');
    const segments = Array.from(bar.querySelectorAll('i'));
    expect(segments.map(segment => segment.getAttribute('data-source'))).toEqual(['WORKITEM_EXECUTION', 'REQUIREMENT_CLARIFY']);
    expect(segments.map(segment => (segment as HTMLElement).style.width)).toEqual(['92%', '8%']);
    expect(segments[0]).toHaveAttribute('title', '工单执行: 76 credits · 92.0%');

    const rows = within(compositionRegion().getByRole('list', { name: 'Credits 来源图例' })).getAllByRole('listitem');
    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveTextContent('工单执行');
    expect(rows[0]).toHaveTextContent('7692.0%');
    expect(rows[1]).toHaveTextContent('需求澄清');
    expect(rows[1]).toHaveTextContent('6.618.0%');
    expect(compositionRegion().getByText('82.61')).toBeInTheDocument();
  });

  it('shows the dashed all-agent chip when no worker is selected', () => {
    renderPanel();

    const chip = screen.getByTestId('cost-composition-chip');
    expect(chip).toHaveTextContent('全部数字员工 · 近30天');
    expect(chip).not.toHaveClass('is-locked');
    expect(chip.querySelector('.anticon-lock')).toBeNull();
  });

  it('locks the composition chip when a specific agent is selected while keeping the all-agent data', async () => {
    const user = userEvent.setup();
    renderPanel({ workerId: 12 });

    const chip = screen.getByTestId('cost-composition-chip');
    expect(chip).toHaveClass('is-locked');
    expect(chip).toHaveTextContent('全部数字员工口径 · 不随上方员工筛选联动');
    expect(chip.querySelector('.anticon-lock')).not.toBeNull();
    // 构成条数据仍是全员口径，不随上方员工筛选变化
    expect(compositionRegion().getByRole('list', { name: 'Credits 来源图例' })).toHaveTextContent('工单执行');

    await user.hover(chip);
    expect(await screen.findByRole('tooltip')).toHaveTextContent('来源构成不区分数字员工，仅随日期范围变化');
  });

  it('omits the composition strip entirely for legacy payloads without costComposition', () => {
    renderPanel({ composition: null });

    expect(costRegion().getByText('总 Credits')).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Credits 来源构成' })).not.toBeInTheDocument();
    expect(screen.queryByTestId('cost-composition-bar')).not.toBeInTheDocument();
  });

  it('keeps the composition data unchanged and flashes only the stats when the agent changes', () => {
    const { rerender, props } = renderPanel();
    const scopedCost = { range: 30, agentId: '12', total: 40.2, perTask: 8.04, perDay: 1.34 };

    rerender(<RealtimeCostPanel {...props} workerId={12} cost={scopedCost} composition={composition} />);

    const statsValue = costRegion().getByText('40.2').closest('.insight-flash');
    expect(statsValue).toHaveClass('is-flash');
    const compositionValue = compositionRegion().getByText('76').closest('.insight-flash');
    expect(compositionValue).not.toHaveClass('is-flash');
    expect(compositionRegion().getByText('6.61')).toBeInTheDocument();
  });

  it('flashes both the stats and the composition when the date range changes', () => {
    const { rerender, props } = renderPanel();
    const nextComposition: CostComposition = {
      range: 7,
      scope: 'ALL_AGENTS',
      total: 20,
      sources: [{ key: 'WORKITEM_EXECUTION', name: '工单执行', credits: 20, share: 1 }],
    };

    rerender(<RealtimeCostPanel
      {...props}
      timeRange="7d"
      cost={{ range: 7, agentId: 'all', total: 20, perTask: 5, perDay: 2.86 }}
      composition={nextComposition}
    />);

    expect(statsRegion().getByText('20').closest('.insight-flash')).toHaveClass('is-flash');
    expect(compositionRegion().getByText('100.0%').closest('.insight-flash')).toHaveClass('is-flash');
    const chip = screen.getByTestId('cost-composition-chip');
    expect(chip).toHaveTextContent('全部数字员工 · 近7天');
    expect(chip).not.toHaveClass('is-locked');
  });

  it('falls back to the server display name for an unknown source key', () => {
    renderPanel({
      composition: {
        ...composition,
        sources: [{ key: 'CUSTOM_SOURCE', name: '自定义来源', credits: 3, share: 1 }],
      },
    });

    const rows = within(compositionRegion().getByRole('list', { name: 'Credits 来源图例' })).getAllByRole('listitem');
    expect(rows[0]).toHaveTextContent('自定义来源');
  });
});

beforeEach(async () => {
  localStorage.clear();
  await i18n.changeLanguage('zh-CN');
});
afterEach(async () => {
  cleanup();
  await i18n.changeLanguage('zh-CN');
});

it('switches the independently cached cost panel without token detail while preserving filter codes', async () => {
  const onWorkerChange = vi.fn();
  const onTimeRangeChange = vi.fn();
  const router = createMemoryRouter([{
    path: '/',
    element: <RealtimeCostPanel
      workerId={12}
      onWorkerChange={onWorkerChange}
      timeRange="7d"
      onTimeRangeChange={onTimeRangeChange}
      workers={workers}
      cost={{ range: 7, agentId: '12', total: 10, perTask: 2, perDay: 1.43 }}
      composition={{ ...composition, range: 7 }}
      isLoading={false}
      isError={true}
      error={new Error('<b>原始错误</b>')}
    />,
  }]);
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
  expect(screen.getByText('Per task')).toBeInTheDocument();
  expect(screen.getByText('Daily average')).toBeInTheDocument();
  expect(screen.queryByText('Total tokens:')).not.toBeInTheDocument();
  expect(screen.getByRole('radio', { name: 'Last 7 days' })).toBeChecked();
  expect(screen.getByTestId('cost-composition-chip')).toHaveTextContent('All agents scope · unaffected by the agent filter above');
  expect(screen.getByText('Task execution')).toBeInTheDocument();
  await userEvent.click(screen.getByText('Last 90 days'));
  expect(onTimeRangeChange).toHaveBeenCalledWith('90d');
  expect(onWorkerChange).not.toHaveBeenCalled();
});

it('updates a directly cached shared Credits card across languages without token detail', async () => {
  const router = createMemoryRouter([{ path: '/', element: <CreditsCostCard cost={{
    totalTokens: 100000,
    avgTokensPerTask: 5000,
    dailyAvg: 14000,
    trend: [10, 12, 11, 13, 14, 12, 15],
    totalCredits: 82.61,
    avgCreditsPerTask: 12.5,
    dailyAvgCredits: 2.75,
    creditsTrend: [1.2, 2.5, 3.1, 4, 5.5, 6.2, 7.1],
  }} /> }]);
  render(<RouterProvider router={router} />);
  await userEvent.hover(screen.getByText('总 Credits'));
  await waitPastTooltipDelay();
  expect(screen.queryByRole('tooltip')).not.toBeInTheDocument();
  await act(async () => { await i18n.changeLanguage('en-US'); });
  expect(screen.getByText('Total Credits')).toBeInTheDocument();
  expect(screen.getByText('82.61')).toBeInTheDocument();
  expect(screen.queryByText('Total tokens:')).not.toBeInTheDocument();
  expect(screen.queryByText('100K')).not.toBeInTheDocument();
  expect(screen.queryByRole('img', { name: 'dollar' })).not.toBeInTheDocument();
});

it('updates local loading and generic error text in both languages', async () => {
  const { rerender, props } = renderPanel({ cost: undefined, composition: null, isLoading: true });
  expect(screen.getByText('加载 Credits 成本…')).toBeInTheDocument();
  await act(async () => { await i18n.changeLanguage('en-US'); });
  expect(screen.getByText('Loading Credits cost…')).toBeInTheDocument();
  rerender(<RealtimeCostPanel {...props} isLoading={false} isError error={null} />);
  expect(screen.getByText('Failed to load Credits cost')).toBeInTheDocument();
  expect(screen.getByText('Try again later')).toBeInTheDocument();
  await act(async () => { await i18n.changeLanguage('zh-CN'); });
  expect(screen.getByText('请稍后重试')).toBeInTheDocument();
});
