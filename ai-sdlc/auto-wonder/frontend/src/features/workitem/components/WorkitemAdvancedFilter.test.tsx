import { describe, it, expect, vi } from 'vitest';
import { useState } from 'react';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import dayjs from 'dayjs';
import { i18n } from '@/shared/i18n';
import { WorkitemAdvancedFilter } from './WorkitemAdvancedFilter';
import { EMPTY_CREATED_TIME_RANGE, type CreatedTimeRangeDraft } from '../createdTimeFilter';

function renderFilter(applied: CreatedTimeRangeDraft = EMPTY_CREATED_TIME_RANGE) {
  const onApply = vi.fn();
  render(<WorkitemAdvancedFilter applied={applied} onApply={onApply} />);
  return onApply;
}

async function openPanel() {
  await userEvent.click(screen.getByRole('button', { name: /高级筛选/ }));
  return screen.findByRole('dialog');
}

const dateInput = (dialog: HTMLElement, placeholder: string) => within(dialog).getByPlaceholderText(placeholder);

/** 日期录入按 antd 自身的用例写法：先打开面板，改输入值后回车提交，避免依赖日历单元格。 */
function typeDate(dialog: HTMLElement, placeholder: string, value: string) {
  const input = dateInput(dialog, placeholder);
  fireEvent.mouseDown(input);
  fireEvent.focus(input);
  fireEvent.change(input, { target: { value } });
  fireEvent.keyDown(input, { key: 'Enter', code: 'Enter', keyCode: 13 });
}

const dateValue = (dialog: HTMLElement, placeholder: string) => (dateInput(dialog, placeholder) as HTMLInputElement).value;

describe('WorkitemAdvancedFilter', () => {
  it('opens the created-time panel and shows no applied tag by default', async () => {
    const onApply = renderFilter();

    expect(screen.queryByTestId('workitem-created-filter-tag')).not.toBeInTheDocument();

    const dialog = await openPanel();

    expect(within(dialog).getByText('创建时间')).toBeInTheDocument();
    expect(dateInput(dialog, '起始日期')).toBeInTheDocument();
    expect(dateInput(dialog, '结束日期')).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: /应\s*用/ })).toBeEnabled();
    expect(onApply).not.toHaveBeenCalled();
  });

  it('submits both bounds and closes the panel', async () => {
    const onApply = renderFilter();
    const dialog = await openPanel();

    typeDate(dialog, '起始日期', '2026-10-01');
    typeDate(dialog, '结束日期', '2026-10-10');
    await userEvent.click(within(dialog).getByRole('button', { name: /应\s*用/ }));

    expect(onApply).toHaveBeenCalledTimes(1);
    const applied = onApply.mock.calls[0][0] as CreatedTimeRangeDraft;
    expect(applied.from?.format('YYYY-MM-DD')).toBe('2026-10-01');
    expect(applied.to?.format('YYYY-MM-DD')).toBe('2026-10-10');
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  });

  it('fills both bounds from the last-week preset in one click', async () => {
    const onApply = renderFilter();
    const dialog = await openPanel();
    const today = dayjs().startOf('day');

    await userEvent.click(within(dialog).getByRole('button', { name: '近一周' }));

    expect(dateValue(dialog, '起始日期')).toBe(today.subtract(6, 'day').format('YYYY-MM-DD'));
    expect(dateValue(dialog, '结束日期')).toBe(today.format('YYYY-MM-DD'));

    await userEvent.click(within(dialog).getByRole('button', { name: /应\s*用/ }));

    expect(onApply).toHaveBeenCalledTimes(1);
    const applied = onApply.mock.calls[0][0] as CreatedTimeRangeDraft;
    expect(applied.from?.format('YYYY-MM-DD')).toBe(today.subtract(6, 'day').format('YYYY-MM-DD'));
    expect(applied.to?.format('YYYY-MM-DD')).toBe(today.format('YYYY-MM-DD'));
  });

  it('fills the last-month preset and lets the user adjust one bound before applying', async () => {
    const onApply = renderFilter();
    const dialog = await openPanel();
    const today = dayjs().startOf('day');

    await userEvent.click(within(dialog).getByRole('button', { name: '近一个月' }));

    expect(dateValue(dialog, '起始日期')).toBe(today.subtract(29, 'day').format('YYYY-MM-DD'));
    expect(dateValue(dialog, '结束日期')).toBe(today.format('YYYY-MM-DD'));

    const adjustedEnd = today.subtract(1, 'day').format('YYYY-MM-DD');
    typeDate(dialog, '结束日期', adjustedEnd);
    await userEvent.click(within(dialog).getByRole('button', { name: /应\s*用/ }));

    const applied = onApply.mock.calls[0][0] as CreatedTimeRangeDraft;
    expect(applied.from?.format('YYYY-MM-DD')).toBe(today.subtract(29, 'day').format('YYYY-MM-DD'));
    expect(applied.to?.format('YYYY-MM-DD')).toBe(adjustedEnd);
  });

  it('warns and disables apply when the start day is later than the end day', async () => {
    const onApply = renderFilter();
    const dialog = await openPanel();

    typeDate(dialog, '起始日期', '2026-10-10');
    typeDate(dialog, '结束日期', '2026-10-01');

    expect(within(dialog).getByRole('alert')).toHaveTextContent('起始日期不能晚于结束日期');
    expect(within(dialog).getByRole('button', { name: /应\s*用/ })).toBeDisabled();
    expect(onApply).not.toHaveBeenCalled();
  });

  it('resets the draft and the applied condition', async () => {
    const onApply = renderFilter({ from: dayjs('2026-10-01'), to: dayjs('2026-10-10') });
    const dialog = await openPanel();
    expect(dateValue(dialog, '起始日期')).toBe('2026-10-01');

    await userEvent.click(within(dialog).getByRole('button', { name: /重\s*置/ }));

    expect(onApply).toHaveBeenCalledWith(EMPTY_CREATED_TIME_RANGE);
    await waitFor(() => expect(dateValue(dialog, '起始日期')).toBe(''));
  });

  it('starts from the applied condition each time the panel is reopened', async () => {
    renderFilter({ from: dayjs('2026-10-01'), to: null });
    let dialog = await openPanel();
    expect(dateValue(dialog, '起始日期')).toBe('2026-10-01');
    expect(dateValue(dialog, '结束日期')).toBe('');

    await userEvent.click(within(dialog).getByRole('button', { name: /取\s*消/ }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());

    dialog = await openPanel();
    expect(dateValue(dialog, '起始日期')).toBe('2026-10-01');
    expect(dateValue(dialog, '结束日期')).toBe('');
  });

  it('labels a start-only and an end-only condition separately', () => {
    const first = render(<WorkitemAdvancedFilter applied={{ from: dayjs('2026-10-01'), to: null }} onApply={vi.fn()} />);
    expect(screen.getByTestId('workitem-created-filter-tag').textContent).toMatch(/^创建时间：.+ 起$/);
    first.unmount();

    render(<WorkitemAdvancedFilter applied={{ from: null, to: dayjs('2026-10-10') }} onApply={vi.fn()} />);
    expect(screen.getByTestId('workitem-created-filter-tag').textContent).toMatch(/^创建时间：.+ 及之前$/);
  });

  it('keeps the applied range visible as a closable tag and clears it on demand', async () => {
    function Harness() {
      const [applied, setApplied] = useState<CreatedTimeRangeDraft>(EMPTY_CREATED_TIME_RANGE);
      return <WorkitemAdvancedFilter applied={applied} onApply={setApplied} />;
    }
    render(<Harness />);
    const dialog = await openPanel();

    typeDate(dialog, '起始日期', '2026-10-01');
    typeDate(dialog, '结束日期', '2026-10-10');
    await userEvent.click(within(dialog).getByRole('button', { name: /应\s*用/ }));

    const tag = await screen.findByTestId('workitem-created-filter-tag');
    expect(tag.textContent).toMatch(/^创建时间：.+ 至 .+$/);

    await userEvent.click(screen.getByTestId('workitem-created-filter-clear'));

    await waitFor(() => expect(screen.queryByTestId('workitem-created-filter-tag')).not.toBeInTheDocument());
  });

  it('stays bilingual through the shared workitem namespace', async () => {
    renderFilter();
    const trigger = screen.getByRole('button', { name: /高级筛选/ });

    await act(async () => { await i18n.changeLanguage('en-US'); });
    try {
      expect(screen.getByRole('button', { name: /Advanced filters/ })).toBe(trigger);
      // 语言切换后按钮名不再是中文，这里直接点击已定位的入口再按 role 取面板。
      await userEvent.click(trigger);
      const dialog = await screen.findByRole('dialog');
      expect(dateInput(dialog, 'Start date')).toBeInTheDocument();
      expect(dateInput(dialog, 'End date')).toBeInTheDocument();
      expect(within(dialog).getByRole('button', { name: 'Apply' })).toBeInTheDocument();
      expect(within(dialog).getByRole('button', { name: 'Reset' })).toBeInTheDocument();
      expect(within(dialog).getByRole('button', { name: 'Last 7 days' })).toBeInTheDocument();
      expect(within(dialog).getByRole('button', { name: 'Last 30 days' })).toBeInTheDocument();
    } finally {
      await act(async () => { await i18n.changeLanguage('zh-CN'); });
    }
  });
});
