import { act, renderHook } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { afterEach, expect, it, vi } from 'vitest';
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

it('refreshes business status after asynchronous delivery start without navigation', async () => {
  vi.useFakeTimers();
  let serverStatus = '待修复';
  let executionStatus = 'PENDING';
  vi.mocked(api.getWorkitem).mockImplementation(async () => ({ ...workitemFixture, statusName: serverStatus,
    statusCategory: serverStatus === '待修复' ? 'NEW' : 'IN_PROGRESS' }));
  vi.mocked(api.getDeliveryProgress).mockImplementation(async () => ({ steps: [], agents: [],
    executions: [{ dispatchId: 7, status: executionStatus, executorName: null, startedAt: null, durationMs: null }] }));
  const client = new QueryClient({ defaultOptions: { queries: {
    retry: 1, staleTime: 30_000, refetchOnWindowFocus: false,
  } } });
  const wrapper = ({ children }: { children: ReactNode }) =>
    <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  const { result, unmount } = renderHook(() => {
    const detail = useWorkitem('42');
    const progress = useDeliveryProgress('42', detail.data?.statusCategory);
    return { detail, progress };
  }, { wrapper });
  try {
    await act(async () => { await vi.advanceTimersByTimeAsync(10); });
    expect(result.current.detail.data?.statusName).toBe('待修复');
    serverStatus = '修复中'; executionStatus = 'RUNNING';
    await act(async () => { await vi.advanceTimersByTimeAsync(60_000); });
    expect(result.current.progress.data?.executions?.[0].status).toBe('RUNNING');
    const displayedStatus = result.current.detail.data?.statusName;
    console.log('REPRO', JSON.stringify({ serverStatus, displayedStatus,
      executionStatus: result.current.progress.data?.executions?.[0].status,
      detailRequests: vi.mocked(api.getWorkitem).mock.calls.length,
      progressRequests: vi.mocked(api.getDeliveryProgress).mock.calls.length }));
    // Positive control: fetching the same endpoint returns the new business status.
    await act(async () => { await result.current.detail.refetch(); await vi.advanceTimersByTimeAsync(10); });
    expect(result.current.detail.data?.statusName).toBe('修复中');
    expect(displayedStatus).toBe('修复中');
  } finally { unmount(); client.clear(); }
});
