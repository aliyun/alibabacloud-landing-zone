import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { i18n } from '@/shared/i18n';
import { StepDurationChart } from './StepDurationChart';
import type { StepTiming } from '@/shared/types/workitem';

const steps: StepTiming[] = [
  { stepId: 1, name: '核实请求与输入', status: 'done', durationMs: 60_000 },
  { stepId: 2, name: '查证选路', status: 'done', durationMs: 180_000 },
  { stepId: 3, name: '补齐路线证据', status: 'active', durationMs: 120_000 },
  { stepId: 4, name: '核对并交接', status: 'pending', durationMs: null },
];
afterEach(async () => { await act(async () => { await i18n.changeLanguage('zh-CN'); }); });

describe('StepDurationChart', () => {
  it('shows the bottleneck, measured shares, and unstarted steps without inventing zero time', () => {
    render(<StepDurationChart dispatchId={11574} steps={steps} />);
    expect(screen.getByText('步骤累计 6分')).toBeVisible();
    expect(screen.getByText('本次执行 #11574')).toBeVisible();
    const rows = screen.getAllByRole('listitem');
    expect(within(rows[1]).getByText('耗时最长')).toBeVisible();
    expect(within(rows[1]).getByText('50%')).toBeVisible();
    expect(within(rows[3]).getByText('未开始')).toBeVisible();
    expect(within(rows[3]).getByText('—')).toBeVisible();
    expect(within(rows[3]).queryByText('0秒')).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '按耗时排序' }));
    expect(screen.getAllByRole('listitem')[0]).toHaveTextContent('查证选路');
  });

  it('refreshes running measurements and switches language while preserving authored names', async () => {
    const { rerender } = render(<StepDurationChart dispatchId={11574} steps={steps} />);
    rerender(<StepDurationChart dispatchId={11574} steps={steps.map(step => step.stepId === 3 ? { ...step, durationMs: 360_000 } : step)} />);
    expect(screen.getByText('耗时最长').closest('li')).toHaveTextContent('补齐路线证据');
    await act(async () => { await i18n.changeLanguage('en-US'); });
    expect(screen.getByRole('region', { name: 'SDLC step duration' })).toBeVisible();
    expect(screen.getByText('Longest').closest('li')).toHaveTextContent('补齐路线证据');
    expect(screen.getByText('Recorded step time: 10m')).toBeVisible();
  });

  it('handles absent, invalid, and zero-duration records without NaN or false bottlenecks', () => {
    const { rerender } = render(<StepDurationChart dispatchId={1} />);
    expect(screen.getByText('本次执行暂无步骤耗时记录')).toBeVisible();
    rerender(<StepDurationChart dispatchId={1} steps={[
      { ...steps[0], durationMs: NaN }, { ...steps[1], durationMs: -1 },
      { ...steps[2], durationMs: 0, status: 'done' },
    ]} />);
    expect(screen.getByText('步骤累计 0秒')).toBeVisible();
    expect(screen.getByText('部分步骤缺少时间记录，未计入累计耗时和占比。')).toBeVisible();
    expect(screen.queryByText('耗时最长')).not.toBeInTheDocument();
    expect(screen.queryByText(/NaN/)).not.toBeInTheDocument();
  });
});
