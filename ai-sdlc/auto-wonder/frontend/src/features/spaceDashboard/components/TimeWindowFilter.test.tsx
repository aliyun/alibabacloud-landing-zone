import { describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import dayjs from 'dayjs';
import { TimeWindowFilter, isDashboardDateDisabled } from './TimeWindowFilter';

describe('isDashboardDateDisabled', () => {
  it('blocks future dates and dates before the 181-day floor', () => {
    expect(isDashboardDateDisabled(dayjs().add(1, 'day'))).toBe(true);
    expect(isDashboardDateDisabled(dayjs().subtract(1, 'day').subtract(181, 'day'))).toBe(true);
  });

  it('allows yesterday and the earliest selectable day', () => {
    expect(isDashboardDateDisabled(dayjs().subtract(1, 'day'))).toBe(false);
    expect(isDashboardDateDisabled(dayjs().subtract(1, 'day').subtract(180, 'day'))).toBe(false);
  });
});

describe('TimeWindowFilter', () => {
  it('applies the last-7-days preset ending yesterday', async () => {
    const onChange = vi.fn();
    render(
      <TimeWindowFilter
        value={[dayjs().subtract(30, 'day'), dayjs().subtract(1, 'day')]}
        onChange={onChange}
        ariaLabel="统计日期范围"
      />,
    );

    await userEvent.click(screen.getAllByRole('textbox')[0]);
    // 窄屏适配依赖该弹层类名（spaceDashboard.css 的 max-width/overflow-x）。
    await waitFor(() => expect(document.querySelector('.ant-picker-dropdown.space-dashboard-date-popup'))
      .not.toBeNull());
    await userEvent.click(await screen.findByText('近7天'));

    expect(onChange).toHaveBeenCalledTimes(1);
    const [range] = onChange.mock.calls[0];
    expect(range[1].format('YYYY-MM-DD')).toBe(dayjs().subtract(1, 'day').format('YYYY-MM-DD'));
    expect(range[1].diff(range[0], 'day')).toBe(6);
  });
});
