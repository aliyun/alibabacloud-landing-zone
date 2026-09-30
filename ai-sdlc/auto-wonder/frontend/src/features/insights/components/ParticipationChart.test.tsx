import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import ParticipationChart from './ParticipationChart';
import { init } from 'echarts/core';
vi.mock('echarts/core', () => ({ use: vi.fn(), init: vi.fn() }));
const chart = { setOption: vi.fn(), resize: vi.fn(), dispose: vi.fn(), dispatchAction: vi.fn(), on: vi.fn(), getDataURL: vi.fn() };
const data = [{ label: '2026-09-24', averageTotalSeconds: 120, averageHumanSeconds: 30, averageAgentSeconds: 90 },
  { label: '2026-09-25', averageTotalSeconds: 240, averageHumanSeconds: 60, averageAgentSeconds: 180 }];
beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(init).mockImplementation(node => {
    if (!node) throw new Error('Chart host is required');
    node.appendChild(document.createElement('svg'));
    return { ...chart, dispose: () => { node.replaceChildren(); chart.dispose(); } } as unknown as ReturnType<typeof init>;
  });
  vi.stubGlobal('ResizeObserver', class { observe() {} disconnect() {} });
});
describe('participation chart controls', () => {
  it.each(['ratio', 'duration'] as const)('lets ordinary wheels scroll the page while reserving Ctrl-wheel for %s zoom', kind => {
    const { container } = render(<ParticipationChart kind={kind} data={data} average={null} />);
    const host = container.querySelector('.participation-chart-canvas')!;
    // ECharts cancels wheels at its child renderer even when its Ctrl condition fails.
    const renderer = host.firstElementChild!;
    renderer.addEventListener('wheel', event => event.preventDefault());
    const scroll = new WheelEvent('wheel', { bubbles: true, cancelable: true, deltaY: 100 });
    renderer.dispatchEvent(scroll);
    expect(scroll.defaultPrevented).toBe(false);
    const zoom = new WheelEvent('wheel', { bubbles: true, cancelable: true, deltaY: 100, ctrlKey: true });
    renderer.dispatchEvent(zoom);
    expect(zoom.defaultPrevented).toBe(true);
  });
  it.each(['distribution', 'ratio', 'duration'] as const)('keeps %s table DOM separate from the disposed chart', async kind => {
    const { container } = render(<ParticipationChart kind={kind} data={data}
      average={{ totalDurationSeconds: 120, humanDurationSeconds: 30, agentDurationSeconds: 90 }} />);
    const toggle = screen.getByRole('button', { name: /数据表$/ });
    for (let i = 0; i < 2; i++) {
      await userEvent.click(toggle);
      expect(screen.getByRole('table')).toBeInTheDocument();
      await userEvent.click(toggle);
      expect(screen.queryByRole('table')).not.toBeInTheDocument();
      expect(container.querySelector('.participation-chart-canvas svg')).toBeInTheDocument();
    }
  });
  it('zooms, resets, toggles series and exposes values in an accessible data table', async () => {
    const { unmount } = render(<ParticipationChart kind="duration" data={data} average={null} />);
    await userEvent.click(screen.getByRole('button', { name: '平均完成时长趋势放大区间' }));
    expect(chart.dispatchAction).toHaveBeenLastCalledWith({ type: 'dataZoom', start: 20, end: 80 });
    await userEvent.click(screen.getByRole('button', { name: 'Agent' }));
    expect(screen.getByRole('button', { name: 'Agent' })).toHaveAttribute('aria-pressed', 'false');
    await userEvent.click(screen.getByRole('button', { name: '平均完成时长趋势复位' }));
    expect(chart.dispatchAction).toHaveBeenLastCalledWith({ type: 'dataZoom', start: 0, end: 100 });
    await userEvent.click(screen.getByRole('button', { name: '平均完成时长趋势数据表' }));
    expect(within(screen.getByRole('table')).getByText('2026-09-25')).toBeInTheDocument();
    expect(chart.dispose).toHaveBeenCalled();
    unmount();
  });
  it('opens a larger view and keeps empty states usable', async () => {
    const { rerender } = render(<ParticipationChart kind="ratio" data={data} average={null} />);
    await userEvent.click(screen.getByRole('button', { name: '人机占比趋势放大查看' }));
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    rerender(<ParticipationChart kind="ratio" data={[]} average={null} />);
    expect(screen.getAllByText('此范围暂无完成工单，请调整日期范围')).toHaveLength(2);
  });
});
