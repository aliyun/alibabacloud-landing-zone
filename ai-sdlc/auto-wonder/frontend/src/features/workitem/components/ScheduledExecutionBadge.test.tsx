import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { ScheduledExecutionBadge } from './ScheduledExecutionBadge';

describe('ScheduledExecutionBadge', () => {
  it('renders the scheduled icon with time tooltip when scheduledStartAt is set', () => {
    render(<ScheduledExecutionBadge scheduledStartAt="2026-09-01T02:00:00Z" />);
    expect(screen.getByLabelText('定时执行')).toBeInTheDocument();
  });

  it('keeps the badge after the scheduled start fired, showing the trigger time', () => {
    render(<ScheduledExecutionBadge scheduledStartTriggeredAt="2026-08-26T10:00:00Z" />);
    expect(screen.getByLabelText('定时执行已触发')).toBeInTheDocument();
  });

  it('marks 7×24 scheduled-task derived workitems with the run trigger time', () => {
    render(
      <ScheduledExecutionBadge
        origin={{ type: 'SCHEDULED_TASK_RUN', id: 9, scheduledTaskId: 77, scheduledTaskName: '每日巡检' }}
        gmtCreate="2026-08-26T02:00:00Z"
      />,
    );
    expect(screen.getByLabelText('定时任务执行')).toBeInTheDocument();
  });

  it('prefers the pending planned start over triggered/derived states', () => {
    render(
      <ScheduledExecutionBadge
        scheduledStartAt="2026-09-01T02:00:00Z"
        scheduledStartTriggeredAt="2026-08-26T10:00:00Z"
        origin={{ type: 'SCHEDULED_TASK_RUN', id: 9 }}
        gmtCreate="2026-08-26T02:00:00Z"
      />,
    );
    expect(screen.getByLabelText('定时执行')).toBeInTheDocument();
    expect(screen.queryByLabelText('定时执行已触发')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('定时任务执行')).not.toBeInTheDocument();
  });

  it('renders nothing when scheduledStartAt is absent', () => {
    const { container: nullCase } = render(<ScheduledExecutionBadge scheduledStartAt={null} />);
    expect(nullCase.firstChild).toBeNull();
    const { container: undefinedCase } = render(<ScheduledExecutionBadge />);
    expect(undefinedCase.firstChild).toBeNull();
  });
});
