import { describe, it, expect, vi, beforeEach } from 'vitest';
import { act, render, screen, fireEvent, waitFor } from '@testing-library/react';
import { i18n } from '@/shared/i18n';
import userEvent from '@testing-library/user-event';
import { RuntimeLogDrawer } from './RuntimeLogDrawer';
import { getRuntimeLog } from '../api';
vi.mock('../api', () => ({ getRuntimeLog: vi.fn() }));
const response = { changed: true, lastSeq: 120, events: Array.from({ length: 120 }, (_, i) => ({ seq: i + 1, eventId: `e${i}`, eventType: 'mcp.result', detail: { outputSummary: `result-${i}`, output: `full-${i}` } })) };
beforeEach(() => { vi.clearAllMocks(); });
describe('RuntimeLogDrawer', () => {
  it('loads beyond the recent 100 items, searches older events and refreshes with the cursor', async () => {
    vi.mocked(getRuntimeLog).mockResolvedValue(response as never);
    render(<RuntimeLogDrawer dispatchId={12} onClose={() => {}} />);
    await screen.findByText('共 120 条');
    fireEvent.change(screen.getByPlaceholderText('搜索事件、工具、输入或输出'), { target: { value: 'full-0' } });
    await screen.findByText('result-0');
    fireEvent.click(screen.getByRole('button', { name: /刷新/ }));
    await waitFor(() => expect(getRuntimeLog).toHaveBeenLastCalledWith(12, 120, expect.any(AbortSignal)));
  });
  it('auto refreshes and clears its timer when closed', async () => {
    vi.mocked(getRuntimeLog).mockResolvedValue(response as never);
    const interval = vi.spyOn(window, 'setInterval');
    const clear = vi.spyOn(window, 'clearInterval');
    const { unmount } = render(<RuntimeLogDrawer dispatchId={13} onClose={() => {}} />);
    await screen.findByText('共 120 条');
    const index = interval.mock.calls.findIndex(args => args[1] === 3000);
    expect(index).toBeGreaterThanOrEqual(0);
    const tick = interval.mock.calls[index][0] as () => void;
    await act(async () => { tick(); });
    await waitFor(() => expect(getRuntimeLog).toHaveBeenCalledTimes(2));
    const timer = interval.mock.results[index].value;
    unmount();
    expect(clear).toHaveBeenCalledWith(timer);
    interval.mockRestore(); clear.mockRestore();
  });
});

afterEach(async () => { await act(() => i18n.changeLanguage('zh-CN')); });
it.each(['zh-CN', 'en-US'])('final widget runtime search and clear keep raw event filtering in %s', async locale => {
 await i18n.changeLanguage(locale); vi.mocked(getRuntimeLog).mockResolvedValue(response as never);
 render(<RuntimeLogDrawer dispatchId={12} onClose={() => {}} />);
 await screen.findByText(locale === 'en-US' ? '120 events' : '共 120 条');
 const input = screen.getByPlaceholderText(locale === 'en-US' ? 'Search events, tools, input or output' : '搜索事件、工具、输入或输出');
 const search = screen.getByRole('button', { name: locale === 'en-US' ? 'Search events, tools, input or output' : '搜索事件、工具、输入或输出' });
 await userEvent.type(input, 'full-0'); await userEvent.click(search);
 expect(screen.getByText('result-0')).toBeInTheDocument(); expect(screen.queryByText('result-119')).not.toBeInTheDocument();
 const clear = screen.getByRole('button', { name: locale === 'en-US' ? 'Clear runtime log search' : '清空运行日志搜索' });
 await userEvent.click(clear); expect(input).toHaveValue(''); expect(screen.getByText('result-119')).toBeInTheDocument();
 expect(getRuntimeLog).toHaveBeenCalledTimes(1);
});
it.each(['zh-CN', 'en-US'])('final widget runtime pending refresh has no named decorative glyph in %s', async locale => {
 await i18n.changeLanguage(locale);
 let finish!: (value: Awaited<ReturnType<typeof getRuntimeLog>>) => void;
 vi.mocked(getRuntimeLog).mockReturnValue(new Promise(resolve => { finish = resolve; }));
 render(<RuntimeLogDrawer dispatchId={12} onClose={() => {}} />);
 const button = screen.getByText(locale === 'en-US' ? 'Refresh' : '刷新', { selector: 'button span' }).closest('button')!;
 try {
  expect(button).toHaveClass('ant-btn-loading'); expect(button).toHaveAccessibleName(locale === 'en-US' ? 'Refresh' : '刷新');
  expect(button.querySelector('.anticon-loading')).toHaveAttribute('aria-hidden', 'true');
 } finally { await act(async () => { finish(response as never); }); }
 expect(button.querySelector('.anticon-reload')).toHaveAttribute('aria-hidden', 'true');
});
it.each(['zh-CN', 'en-US'])('final widget runtime alerts retain raw errors and visible decorative glyphs in %s', async locale => {
 await i18n.changeLanguage(locale); vi.mocked(getRuntimeLog).mockRejectedValue(new Error('<raw>错误'));
 render(<RuntimeLogDrawer dispatchId={12} onClose={() => {}} />);
 await screen.findByText('<raw>错误');
 expect(screen.queryByRole('img', { name: 'info-circle' })).not.toBeInTheDocument();
 expect(screen.queryByRole('img', { name: 'close-circle' })).not.toBeInTheDocument();
 expect(document.querySelector('.ant-alert-info .anticon-info-circle')).not.toBeNull();
 expect(document.querySelector('.ant-alert-error .anticon-close-circle')).not.toBeNull();
});
