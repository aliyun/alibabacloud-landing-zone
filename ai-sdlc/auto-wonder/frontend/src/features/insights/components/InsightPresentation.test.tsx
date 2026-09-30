import { expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { Sparkline } from './Sparkline';
import SquadLines from '../realtime/SquadLines';
import InventoryPanel from '../realtime/InventoryPanel';
import { SlowTailTable } from './SlowTailTable';
import { useHumanAgentSlowTail } from '../hooks';
vi.mock('../hooks', () => ({ useHumanAgentSlowTail: vi.fn() }));

it('renders one-point sparklines without NaN coordinates and assigns unique gradient ids', () => {
  const { container } = render(<><Sparkline data={[10]} color="var(--aw-success)" /><Sparkline data={[10]} color="var(--aw-success)" /></>);
  expect(container.innerHTML).not.toContain('NaN');
  const gradients = container.querySelectorAll('linearGradient');
  expect(gradients[0].id).not.toBe(gradients[1].id);
});

it('represents busy members as the true fraction, including zero', () => {
  const { container } = render(<SquadLines squads={[{ squadId: 1, name: '交付小队', members: 4, online: 4, busy: 1, runningTasks: 3, load: 0.75, inProgressWorkitems: 2 },
    { squadId: 2, name: '空小队', members: 0, online: 0, busy: 0, runningTasks: 0, load: 0, inProgressWorkitems: 0 }]} />);
  expect(screen.getByRole('img', { name: '忙碌成员 1 / 4' }).firstElementChild).toHaveStyle({ width: '25%' });
  expect(container.querySelectorAll('[role="img"]')[1].firstElementChild).toHaveStyle({ width: '0%' });
});

it('does not label a single workitem as no data', () => {
  render(<InventoryPanel inventory={{ byLifecycle: { init: 1, inProgress: 0, done: 0, canceled: 0 }, byType: { req: 1, task: 0, bug: 0 } }} />);
  expect(screen.queryByText(/暂无数据/)).not.toBeInTheDocument();
});

it('distinguishes slow-tail loading and errors from an empty result', () => {
  vi.mocked(useHumanAgentSlowTail).mockReturnValue({ isLoading: true } as ReturnType<typeof useHumanAgentSlowTail>);
  const { rerender } = render(<SlowTailTable startDate="2026-09-01" endDate="2026-09-25" />);
  expect(document.querySelector('.ant-spin-spinning')).toBeInTheDocument();
  vi.mocked(useHumanAgentSlowTail).mockReturnValue({ isError: true, refetch: vi.fn() } as unknown as ReturnType<typeof useHumanAgentSlowTail>);
  rerender(<SlowTailTable startDate="2026-09-01" endDate="2026-09-25" />);
  expect(screen.getByText('慢尾工单加载失败')).toBeInTheDocument();
  expect(screen.queryByText('无慢尾工单')).not.toBeInTheDocument();
});
