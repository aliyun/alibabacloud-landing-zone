import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { WorkitemActionBar } from './WorkitemActionBar';

describe('WorkitemActionBar watch toggle', () => {
  it('shows an unwatched "关注" entry when the user is not watching', () => {
    render(<WorkitemActionBar watched={false} />);

    const button = screen.getByTestId('workitem-watch-toggle');
    expect(button).toBeInTheDocument();
    expect(button).toHaveAccessibleName('关注工单');
    expect(button).toHaveTextContent('关注');
    expect(button).not.toHaveTextContent('已关注');
  });

  it('shows a watched "已关注" state when the user is already watching', () => {
    render(<WorkitemActionBar watched />);

    const button = screen.getByTestId('workitem-watch-toggle');
    expect(button).toHaveAccessibleName('取消关注工单');
    expect(button).toHaveTextContent('已关注');
  });

  it('calls onToggleWatch when the watch button is clicked', async () => {
    const onToggleWatch = vi.fn();
    render(<WorkitemActionBar watched={false} onToggleWatch={onToggleWatch} />);

    await userEvent.click(screen.getByTestId('workitem-watch-toggle'));

    expect(onToggleWatch).toHaveBeenCalledTimes(1);
  });

  it('reflects the pending state while the toggle request is in flight', () => {
    render(<WorkitemActionBar watched={false} watchLoading />);

    const button = screen.getByTestId('workitem-watch-toggle');
    expect(button.querySelector('.ant-btn-loading-icon')).toBeInTheDocument();
  });
});
