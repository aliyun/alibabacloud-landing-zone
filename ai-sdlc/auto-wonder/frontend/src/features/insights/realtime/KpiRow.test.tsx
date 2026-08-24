import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import KpiRow from './KpiRow';

const sampleKpi = {
  runningDispatches: 4,
  todayCompletedTasks: 12,
  weekCompletedTasks: 57,
  avgTaskDurationMinutes: 34,
  inProgressWorkitems: 9,
  queuedDispatches: 3,
  activeSquads: 2,
  onlineAgents: 6,
  avgLoad: 0.67,
};

describe('KpiRow', () => {
  it('renders all KPI metrics', () => {
    render(<KpiRow kpi={sampleKpi} />);

    expect(screen.getByText('今日完成')).toBeInTheDocument();
    expect(screen.getByText('本周完成')).toBeInTheDocument();
    expect(screen.getByText('平均耗时')).toBeInTheDocument();
    expect(screen.getByText('12')).toBeInTheDocument();
    expect(screen.getByText('57')).toBeInTheDocument();
    expect(screen.getByText('34分钟')).toBeInTheDocument();
  });

  it('converts 平均耗时 to hours when >= 60 minutes', () => {
    render(<KpiRow kpi={{ ...sampleKpi, avgTaskDurationMinutes: 90 }} />);

    expect(screen.getByText('1小时30分')).toBeInTheDocument();
  });

  it('clickable KPIs have button role and cursor pointer', () => {
    render(<KpiRow kpi={sampleKpi} />);

    const running = screen.getByText('正在运行').parentElement!;
    const todayCompleted = screen.getByText('今日完成').parentElement!;
    const weekCompleted = screen.getByText('本周完成').parentElement!;

    expect(running).toHaveAttribute('role', 'button');
    expect(todayCompleted).toHaveAttribute('role', 'button');
    expect(weekCompleted).toHaveAttribute('role', 'button');
    expect(running).toHaveStyle({ cursor: 'pointer' });
  });

  it('non-clickable KPIs have no button role', () => {
    render(<KpiRow kpi={sampleKpi} />);

    const avgDuration = screen.getByText('平均耗时').parentElement!;
    expect(avgDuration).not.toHaveAttribute('role');
    expect(avgDuration).toHaveStyle({ cursor: 'default' });
  });

  it('calls onKpiClick with correct key when clicking clickable KPI', async () => {
    const handleClick = vi.fn();
    render(<KpiRow kpi={sampleKpi} onKpiClick={handleClick} />);

    const user = userEvent.setup();
    await user.click(screen.getByText('今日完成').parentElement!);
    expect(handleClick).toHaveBeenCalledWith('todayCompletedTasks');

    await user.click(screen.getByText('本周完成').parentElement!);
    expect(handleClick).toHaveBeenCalledWith('weekCompletedTasks');

    await user.click(screen.getByText('正在运行').parentElement!);
    expect(handleClick).toHaveBeenCalledWith('runningDispatches');
  });

  it('does not fire click for non-clickable KPIs', async () => {
    const handleClick = vi.fn();
    render(<KpiRow kpi={sampleKpi} onKpiClick={handleClick} />);

    const user = userEvent.setup();
    await user.click(screen.getByText('平均耗时').parentElement!);
    expect(handleClick).not.toHaveBeenCalled();
  });
});
