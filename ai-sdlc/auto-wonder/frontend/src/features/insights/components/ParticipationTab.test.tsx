import { expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ParticipationTab } from './ParticipationTab';
import { useHumanAgentParticipation } from '../hooks';
vi.mock('../hooks', () => ({ useHumanAgentParticipation: vi.fn(), useForceRefreshParticipation: () => ({ mutate: vi.fn() }) }));
vi.mock('./SlowTailTable', () => ({ SlowTailTable: () => <div>慢尾数据</div> }));
vi.mock('./ParticipationChart', () => ({ default: () => <div>交互图表</div> }));
it('keeps date and granularity controls available when data is empty or fails', async () => {
  vi.mocked(useHumanAgentParticipation).mockReturnValue({ data: { available: false }, isLoading: false } as ReturnType<typeof useHumanAgentParticipation>);
  const { rerender } = render(<ParticipationTab />);
  expect(screen.getAllByLabelText('统计日期范围')).toHaveLength(2);
  await userEvent.click(screen.getByText('按周'));
  expect(useHumanAgentParticipation).toHaveBeenLastCalledWith(expect.any(String), expect.any(String), 'WEEK');
  const refetch = vi.fn();
  vi.mocked(useHumanAgentParticipation).mockReturnValue({ isError: true, refetch } as unknown as ReturnType<typeof useHumanAgentParticipation>);
  rerender(<ParticipationTab />);
  await userEvent.click(screen.getByRole('button', { name: /重\s*试/ }));
  expect(refetch).toHaveBeenCalledOnce();
  expect(screen.getByRole('radio', { name: '按月' })).toBeInTheDocument();
});

it('distinguishes unavailable runtime evidence from measured zero and exposes scope on demand', async () => {
  const data = { available: true, sampleSize: 2, medianTotalSeconds: 60, agentWorkitemCount: 1,
    averageExecutionSeconds: null, executionSampleSize: 0, executionMissingCount: 1, identifiedCompletedCount: 3,
    exclusions: { MISSING_CREATE: 1 }, average: { totalDurationSeconds: 90, humanDurationSeconds: 30, agentDurationSeconds: 60 }, trend: [] };
  vi.mocked(useHumanAgentParticipation).mockReturnValue({ data, isLoading: false } as unknown as ReturnType<typeof useHumanAgentParticipation>);
  const { container, rerender } = render(<ParticipationTab />);
  expect(screen.getByText('50.0%')).toBeInTheDocument();
  const executionCard = () => [...container.querySelectorAll('.participation-stat')].find(el => el.textContent?.includes('Agent 平均会话执行时长'))!;
  expect(executionCard().querySelector('.ant-statistic-content')).toHaveTextContent('—');
  await userEvent.hover(screen.getByRole('button', { name: '查看人机协作统计口径' }));
  expect(await screen.findByRole('tooltip')).toHaveTextContent('缺少创建事件：1 条');
  vi.mocked(useHumanAgentParticipation).mockReturnValue({ data: { ...data, averageExecutionSeconds: 0, executionSampleSize: 1 }, isLoading: false } as unknown as ReturnType<typeof useHumanAgentParticipation>);
  rerender(<ParticipationTab />);
  expect(executionCard().querySelector('.ant-statistic-content')).not.toHaveTextContent('—');
});
