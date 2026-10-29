import { act, renderHook } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { useDeliveryProgress, useWorkitem } from './hooks';
import * as api from './api';

vi.mock('./api', () => ({ getWorkitem: vi.fn(), getDeliveryProgress: vi.fn() }));

const workitemFixture: Awaited<ReturnType<typeof api.getWorkitem>> = {
  id: 42, workType: 'TASK', title: 'Polling fixture', contentMd: '',
  templateId: null, statusNodeId: null, statusName: '待修复', sdlcId: null, sdlcName: null,
  assigneeType: 'HUMAN', assigneeRef: null, assigneeName: null,
  priority: 2, version: 0, gmtCreate: '', gmtModified: '',
};

afterEach(() => { vi.useRealTimers(); vi.clearAllMocks(); });

function makeClient() {
  return new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: 30_000, refetchOnWindowFocus: false } } });
}

function makeWrapper(client: QueryClient) {
  return ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}

function detailCalls() {
  return vi.mocked(api.getWorkitem).mock.calls.length;
}

describe('workitem detail refresh (issue 116)', () => {
  it('polls the detail every 30s so the business status converges without manual refetch', async () => {
    vi.useFakeTimers();
    let serverStatus = '待修复';
    vi.mocked(api.getWorkitem).mockImplementation(async () => ({
      ...workitemFixture, statusName: serverStatus, statusCategory: serverStatus === '待修复' ? 'NEW' : 'IN_PROGRESS',
    }));
    const client = makeClient();
    const { result, unmount } = renderHook(() => useWorkitem('42'), { wrapper: makeWrapper(client) });
    await act(async () => { await vi.advanceTimersByTimeAsync(10); });
    expect(detailCalls()).toBe(1);
    serverStatus = '修复中';
    await act(async () => { await vi.advanceTimersByTimeAsync(25_000); });
    expect(detailCalls()).toBe(1);
    await act(async () => { await vi.advanceTimersByTimeAsync(10_000); });
    expect(detailCalls()).toBe(2);
    expect(result.current.data?.statusName).toBe('修复中');
    unmount(); client.clear();
  });

  it('backs off detail polling to 60s after a failed fetch', async () => {
    vi.useFakeTimers();
    vi.mocked(api.getWorkitem)
      .mockRejectedValueOnce(new Error('503'))
      .mockResolvedValue({ ...workitemFixture, statusName: '修复中', statusCategory: 'IN_PROGRESS' });
    const client = makeClient();
    const { result, unmount } = renderHook(() => useWorkitem('42'), { wrapper: makeWrapper(client) });
    await act(async () => { await vi.advanceTimersByTimeAsync(10); });
    expect(result.current.isError).toBe(true);
    expect(detailCalls()).toBe(1);
    await act(async () => { await vi.advanceTimersByTimeAsync(59_000); });
    expect(detailCalls()).toBe(1);
    await act(async () => { await vi.advanceTimersByTimeAsync(2_000); });
    expect(detailCalls()).toBe(2);
    expect(result.current.isSuccess).toBe(true);
    unmount(); client.clear();
  });

  it('keeps refreshing after delivery reaches a terminal state instead of stopping early', async () => {
    vi.useFakeTimers();
    let executionStatus = 'RUNNING';
    let serverStatus = '待修复';
    vi.mocked(api.getWorkitem).mockImplementation(async () => ({
      ...workitemFixture, statusName: serverStatus, statusCategory: serverStatus === '待修复' ? 'NEW' : 'IN_PROGRESS',
    }));
    vi.mocked(api.getDeliveryProgress).mockImplementation(async () => ({
      steps: [], agents: [], executions: [{ dispatchId: 7, status: executionStatus, executorName: null, startedAt: null, durationMs: null }],
    }));
    const client = makeClient();
    const { result, unmount } = renderHook(() => {
      const detail = useWorkitem('42');
      const progress = useDeliveryProgress('42', detail.data?.statusCategory);
      return { detail, progress };
    }, { wrapper: makeWrapper(client) });
    await act(async () => { await vi.advanceTimersByTimeAsync(5_100); });
    expect(detailCalls()).toBe(1);
    executionStatus = 'SUCCEEDED';
    serverStatus = '修复中';
    await act(async () => { await vi.advanceTimersByTimeAsync(30_000); });
    expect(detailCalls()).toBe(2);
    expect(result.current.detail.data?.statusName).toBe('修复中');
    const progressCallsAfterTerminal = vi.mocked(api.getDeliveryProgress).mock.calls.length;
    await act(async () => { await vi.advanceTimersByTimeAsync(30_000); });
    expect(vi.mocked(api.getDeliveryProgress).mock.calls.length).toBeGreaterThan(progressCallsAfterTerminal);
    unmount(); client.clear();
  });

  it('polls progress every 5s while an agent is active, not only on active executions', async () => {
    vi.useFakeTimers();
    vi.mocked(api.getWorkitem).mockResolvedValue({ ...workitemFixture, statusName: '待修复', statusCategory: 'NEW' });
    vi.mocked(api.getDeliveryProgress).mockImplementation(async () => ({
      steps: [], agents: [{ agentId: 9, agentName: 'AW', status: 'active', durationMs: null, steps: [] }],
    }));
    const client = makeClient();
    const { unmount } = renderHook(() => {
      const detail = useWorkitem('42');
      const progress = useDeliveryProgress('42', detail.data?.statusCategory);
      return { detail, progress };
    }, { wrapper: makeWrapper(client) });
    await act(async () => { await vi.advanceTimersByTimeAsync(11_000); });
    expect(vi.mocked(api.getDeliveryProgress).mock.calls.length).toBeGreaterThanOrEqual(3);
    expect(detailCalls()).toBe(1);
    unmount(); client.clear();
  });
});
