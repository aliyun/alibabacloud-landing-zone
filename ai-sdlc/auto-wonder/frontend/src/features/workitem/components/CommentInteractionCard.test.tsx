import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { CommentInteractionCard } from './CommentInteractionCard';
import { getRuntimeLog } from '../api';
import type { TimelineItem } from '@/shared/types/workitem';

vi.mock('../api', () => ({ getRuntimeLog: vi.fn() }));
const interaction: NonNullable<TimelineItem['interactions']>[number] = {
  guidanceId: 9, dispatchId: 12, targetAgentId: 20, targetAgentName: 'npm 发布工程师', status: 'DELIVERED',
};
const start = '2026-09-19T00:00:00Z';
const snapshot = { changed: true, lastSeq: 2, events: [
  { seq: 1, eventType: 'agent.progress', eventTime: start, detail: { message: '正在使用 Read', interaction: true } },
  { seq: 2, eventType: 'agent.thinking', detail: { content: 'private reasoning must not appear' } },
] };
async function flush() { await act(async () => { await Promise.resolve(); }); }
describe('comment execution activity', () => {
  beforeEach(() => { vi.useFakeTimers(); vi.setSystemTime(new Date(start)); vi.mocked(getRuntimeLog).mockReset(); vi.mocked(getRuntimeLog).mockResolvedValue(snapshot as never); });
  afterEach(() => { cleanup(); vi.useRealTimers(); });

  it('shows real activity, retains unchanged snapshots, and stops polling after completion', async () => {
    const { rerender, unmount } = render(<CommentInteractionCard interaction={interaction} createdAt={start} />);
    await flush();
    expect(screen.getAllByText('正在使用 Read').length).toBeGreaterThan(0);
    expect(screen.queryByText(/private reasoning/)).not.toBeInTheDocument();
    vi.mocked(getRuntimeLog).mockResolvedValue({ changed: false, lastSeq: 2, events: [] } as never);
    await act(async () => { vi.advanceTimersByTime(3000); });
    expect(getRuntimeLog).toHaveBeenLastCalledWith(12, 2, expect.any(AbortSignal));
    expect(screen.getAllByText('正在使用 Read').length).toBeGreaterThan(0);
    rerender(<CommentInteractionCard interaction={{ ...interaction, status: 'APPLIED', replyContent: '已发布', repliedAt: start }} createdAt={start} />);
    await flush();
    const calls = vi.mocked(getRuntimeLog).mock.calls.length;
    await act(async () => { vi.advanceTimersByTime(10000); });
    expect(getRuntimeLog).toHaveBeenCalledTimes(calls);
    expect(screen.getByText('已发布')).toBeInTheDocument();
    unmount();
  });

  it('displays actual provider error even when a reply is present, preserving lines and redacting credentials', async () => {
    const error = 'qoder response timeout after 30s\nprovider=qodercn exit code 1\nAuthorization: Bearer secret-test-token';
    render(<CommentInteractionCard interaction={{ ...interaction, status: 'FAILED', error, replyContent: '查询未完成' }} createdAt={start} />);
    await flush();
    expect(screen.getByText('回复失败')).toBeInTheDocument();
    expect(screen.getByTestId('comment-provider-error').textContent).toContain('qoder response timeout after 30s\nprovider=qodercn exit code 1');
    expect(screen.queryByText(/secret-test-token/)).not.toBeInTheDocument();
    expect(screen.getByText('查询未完成')).toBeInTheDocument();
  });

  it('fetches completed history only on request and never invents missing errors', async () => {
    const { rerender } = render(<CommentInteractionCard interaction={{ ...interaction, status: 'APPLIED', replyContent: '完成' }} createdAt={start} />);
    expect(getRuntimeLog).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: /展开执行记录/ }));
    await flush();
    expect(getRuntimeLog).toHaveBeenCalledTimes(1);
    rerender(<CommentInteractionCard interaction={{ ...interaction, status: 'FAILED' }} createdAt={start} />);
    await flush();
    expect(screen.getByText(/未收到底层原始错误/)).toBeInTheDocument();
    expect(screen.queryByText(/qoder response timeout/)).not.toBeInTheDocument();
  });

  it('keeps reported provider diagnostics separate from a log loading failure', async () => {
    vi.mocked(getRuntimeLog).mockRejectedValue(new Error('HTTP 503'));
    render(<CommentInteractionCard interaction={{ ...interaction, status: 'FAILED', error: 'quota exceeded: qoder' }} createdAt={start} />);
    await flush();
    expect(screen.getByTestId('comment-provider-error')).toHaveTextContent('quota exceeded: qoder');
    expect(screen.getByText(/执行记录暂时无法加载/)).toBeInTheDocument();
    await act(async () => { vi.advanceTimersByTime(9000); });
    expect(getRuntimeLog).toHaveBeenCalledTimes(1);
  });

  it('supplements a generic error with the actual turn failure event', async () => {
    vi.mocked(getRuntimeLog).mockResolvedValue({ ...snapshot, events: [
      { eventType: 'session.failed', detail: { errorMessage: 'qodercn cli exited: insufficient credits' } },
    ] } as never);
    render(<CommentInteractionCard interaction={{ ...interaction, status: 'FAILED', error: '查询失败' }} createdAt={start} />);
    await flush();
    expect(screen.getByTestId('comment-provider-error')).toHaveTextContent('qodercn cli exited: insufficient credits');
  });

  it('stops polling on a dispatch terminal event before the parent timeline catches up', async () => {
    vi.mocked(getRuntimeLog).mockResolvedValue({ ...snapshot, events: [
      { eventType: 'dispatch.failed', eventTime: start, detail: { error: 'qoder response timeout' } },
    ] } as never);
    render(<CommentInteractionCard interaction={interaction} createdAt={start} />);
    await flush();
    const calls = vi.mocked(getRuntimeLog).mock.calls.length;
    await act(async () => { vi.advanceTimersByTime(10000); });
    expect(getRuntimeLog).toHaveBeenCalledTimes(calls);
    expect(screen.getByText('回复失败')).toBeInTheDocument();
  });

  it('reloads final diagnostics when an expanded inactive interaction becomes failed', async () => {
    const { rerender } = render(<CommentInteractionCard interaction={{ ...interaction, status: 'APPLIED', replyContent: '回复' }} createdAt={start} />);
    fireEvent.click(screen.getByRole('button', { name: /展开执行记录/ }));
    await flush();
    vi.mocked(getRuntimeLog).mockResolvedValue({ ...snapshot, events: [
      { eventType: 'session.failed', detail: { error: 'qoder response timeout' } },
    ] } as never);
    rerender(<CommentInteractionCard interaction={{ ...interaction, status: 'FAILED' }} createdAt={start} />);
    await flush();
    expect(screen.getByTestId('comment-provider-error')).toHaveTextContent('qoder response timeout');
  });

  it('aborts the active request on cancellation and preserves the reader scroll position', async () => {
    const { rerender } = render(<CommentInteractionCard interaction={interaction} createdAt={start} />);
    await flush();
    const viewport = screen.getByLabelText('执行活动记录');
    Object.defineProperties(viewport, { scrollHeight: { value: 600, configurable: true }, clientHeight: { value: 132, configurable: true } });
    viewport.scrollTop = 100;
    fireEvent.scroll(viewport);
    await act(async () => { vi.advanceTimersByTime(3000); });
    expect(viewport.scrollTop).toBe(100);
    fireEvent.click(screen.getByRole('button', { name: '回到最新' }));
    expect(viewport.scrollTop).toBe(600);
    const signal = vi.mocked(getRuntimeLog).mock.calls[0][2];
    rerender(<CommentInteractionCard interaction={{ ...interaction, status: 'CANCELED' }} createdAt={start} />);
    expect(signal?.aborted).toBe(true);
    const calls = vi.mocked(getRuntimeLog).mock.calls.length;
    await act(async () => { vi.advanceTimersByTime(10000); });
    expect(getRuntimeLog).toHaveBeenCalledTimes(calls);
    expect(screen.getByText('已取消')).toBeInTheDocument();
  });
});
