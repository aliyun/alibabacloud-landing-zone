import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import KpiRow from './KpiRow';

const sampleKpi = {
  todayCreatedWorkitems: 9,
  runningWorkitems: 4,
  todayCompletedTasks: 12,
  weekCompletedTasks: 57,
};

function kpiCard(label: string): HTMLElement {
  return screen.getByText(label).parentElement!;
}

describe('KpiRow', () => {
  it('renders the four workitem-level KPIs with labels, values and hints in order', () => {
    const { container } = render(<KpiRow kpi={sampleKpi} />);

    const labels = Array.from(container.querySelectorAll('.insight-kpi-label')).map((node) => node.textContent);
    expect(labels).toEqual(['今日新建', '正在运行', '今日完成', '本周完成']);

    const values = Array.from(container.querySelectorAll('.insight-kpi-value')).map((node) => node.textContent);
    expect(values).toEqual(['9', '4', '12', '57']);

    const hints = Array.from(container.querySelectorAll('.insight-kpi-hint')).map((node) => node.textContent);
    expect(hints).toEqual(['今日创建工单（不含已取消）', '执行中工单', '工单', '工单']);
  });

  it('makes all four drill-down KPIs clickable', () => {
    render(<KpiRow kpi={sampleKpi} onKpiClick={() => {}} />);

    for (const label of ['今日新建', '正在运行', '今日完成', '本周完成']) {
      expect(kpiCard(label)).toHaveAttribute('role', 'button');
      expect(kpiCard(label)).toHaveStyle({ cursor: 'pointer' });
    }
  });

  it('calls onKpiClick with the matching detail key', async () => {
    const handleClick = vi.fn();
    render(<KpiRow kpi={sampleKpi} onKpiClick={handleClick} />);

    const user = userEvent.setup();
    await user.click(screen.getByText('今日完成'));
    expect(handleClick).toHaveBeenCalledWith('todayCompletedTasks');

    await user.click(screen.getByText('本周完成'));
    expect(handleClick).toHaveBeenCalledWith('weekCompletedTasks');

    await user.click(screen.getByText('正在运行'));
    expect(handleClick).toHaveBeenCalledWith('runningWorkitems');

    await user.click(screen.getByText('今日新建'));
    expect(handleClick).toHaveBeenCalledWith('todayCreatedWorkitems');
  });

  it('opens the drill-down from the keyboard with Enter and Space', async () => {
    const handleClick = vi.fn();
    render(<KpiRow kpi={sampleKpi} onKpiClick={handleClick} />);

    const user = userEvent.setup();
    kpiCard('正在运行').focus();
    await user.keyboard('{Enter} ');
    expect(handleClick).toHaveBeenCalledTimes(2);
    expect(handleClick).toHaveBeenNthCalledWith(1, 'runningWorkitems');
    expect(handleClick).toHaveBeenNthCalledWith(2, 'runningWorkitems');
    await user.keyboard('{a}');
    expect(handleClick).toHaveBeenCalledTimes(2);

    kpiCard('今日新建').focus();
    await user.keyboard('{Enter}');
    expect(handleClick).toHaveBeenNthCalledWith(3, 'todayCreatedWorkitems');
  });

  it('keeps every KPI static when no drill-down owner is provided', () => {
    render(<KpiRow kpi={sampleKpi} />);

    expect(kpiCard('正在运行')).not.toHaveAttribute('role');
    expect(kpiCard('正在运行')).toHaveStyle({ cursor: 'default' });
  });
});
