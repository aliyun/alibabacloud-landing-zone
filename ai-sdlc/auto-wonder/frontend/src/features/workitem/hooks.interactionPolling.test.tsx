import { act, renderHook } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { useUnifiedTimeline } from './hooks';
import * as api from './api';
import type { CommentInteraction, TimelineItem } from '@/shared/types/workitem';

vi.mock('./api', () => ({ getUnifiedTimeline: vi.fn() }));

afterEach(() => { vi.useRealTimers(); vi.clearAllMocks(); });

function makeClient() {
  return new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: 0, refetchOnWindowFocus: false } } });
}

function makeWrapper(client: QueryClient) {
  return ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}

function timelineWith(interaction: Partial<CommentInteraction>): TimelineItem[] {
  return [{
    id: 1,
    type: 'comment',
    authorId: 7,
    authorName: '淘飞',
    authorType: 'HUMAN',
    isAgent: false,
    content: '请检查接口兼容性',
    gmtCreate: '2026-10-29T06:00:00Z',
    interactions: [{
      guidanceId: 9, dispatchId: 91, targetAgentId: 40013, targetAgentName: 'AW全栈开发',
      status: 'DELIVERED', ...interaction,
    }],
  }];
}

function timelineCalls() {
  return vi.mocked(api.getUnifiedTimeline).mock.calls.length;
}

async function startPolling(items: TimelineItem[]) {
  vi.mocked(api.getUnifiedTimeline).mockResolvedValue({ items, hasMore: false });
  const client = makeClient();
  const view = renderHook(() => useUnifiedTimeline('42'), { wrapper: makeWrapper(client) });
  await act(async () => { await vi.advanceTimersByTimeAsync(10); });
  expect(timelineCalls()).toBe(1);
  return { ...view, client };
}

describe('comment interaction polling cadence', () => {
  it('polls every 2s while an executor stop is still unconfirmed after a forced end', async () => {
    vi.useFakeTimers();
    const { unmount, client } = await startPolling(timelineWith({
      status: 'CANCELED', executionStatus: 'CANCELED', cancelRequested: true, stopPending: true,
    }));

    await act(async () => { await vi.advanceTimersByTimeAsync(2_100); });

    expect(timelineCalls()).toBe(2);
    unmount(); client.clear();
  });

  it('polls every 2s while a cancel request waits for the executor on a non-terminal dispatch', async () => {
    vi.useFakeTimers();
    const { unmount, client } = await startPolling(timelineWith({
      status: 'CANCELED', executionStatus: 'PAUSING', cancelRequested: true, stopPending: false,
    }));

    await act(async () => { await vi.advanceTimersByTimeAsync(2_100); });

    expect(timelineCalls()).toBe(2);
    unmount(); client.clear();
  });

  it('falls back to the slow cadence once a canceled dispatch is terminal', async () => {
    vi.useFakeTimers();
    const { unmount, client } = await startPolling(timelineWith({
      status: 'CANCELED', executionStatus: 'CANCELED', cancelRequested: true, stopPending: false,
    }));

    await act(async () => { await vi.advanceTimersByTimeAsync(29_000); });
    expect(timelineCalls()).toBe(1);
    await act(async () => { await vi.advanceTimersByTimeAsync(1_500); });
    expect(timelineCalls()).toBe(2);
    unmount(); client.clear();
  });

  it('keeps the slow cadence without any active interaction', async () => {
    vi.useFakeTimers();
    const { unmount, client } = await startPolling(timelineWith({
      status: 'APPLIED', executionStatus: 'SUCCEEDED', replyContent: '已完成',
    }));

    await act(async () => { await vi.advanceTimersByTimeAsync(29_000); });
    expect(timelineCalls()).toBe(1);
    await act(async () => { await vi.advanceTimersByTimeAsync(1_500); });
    expect(timelineCalls()).toBe(2);
    unmount(); client.clear();
  });

  it('treats a cancel request without an execution status as still in flight', async () => {
    vi.useFakeTimers();
    const { unmount, client } = await startPolling(timelineWith({
      status: 'CANCELED', executionStatus: undefined, cancelRequested: true,
    }));

    await act(async () => { await vi.advanceTimersByTimeAsync(2_100); });

    expect(timelineCalls()).toBe(2);
    unmount(); client.clear();
  });
});
