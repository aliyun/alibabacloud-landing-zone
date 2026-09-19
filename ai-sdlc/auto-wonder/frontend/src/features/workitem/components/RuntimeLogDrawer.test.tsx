import { describe, it, expect, vi, beforeEach } from 'vitest';
import { act, render, screen, fireEvent, waitFor } from '@testing-library/react';
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
