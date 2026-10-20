import { act, renderHook } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { useDeliveryProgress } from './hooks';

vi.mock('./api', () => ({ getDeliveryProgress: vi.fn(async () => ({ agents: [], executions: [] })) }));
import { getDeliveryProgress } from './api';

afterEach(() => { vi.useRealTimers(); vi.clearAllMocks(); });

describe('delivery progress polling', () => {
  it.each([
    ['IN_PROGRESS', [], 5000],
    ['DONE', [{ dispatchId: 1, status: 'RUNNING' }], 5000],
    ['DONE', [{ dispatchId: 1, status: 'SUCCEEDED' }], 30000],
  ] as const)('polls %s with executions %j after %i ms', async (status, executions, interval) => {
    vi.useFakeTimers();
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    client.setQueryData(['workitem', '42', 'delivery-progress'], { agents: [], executions });
    const wrapper = ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>;
    const { unmount } = renderHook(() => useDeliveryProgress('42', status), { wrapper });
    await act(async () => { await vi.advanceTimersByTimeAsync(1); });
    const initialCalls = vi.mocked(getDeliveryProgress).mock.calls.length;
    act(() => client.setQueryData(['workitem', '42', 'delivery-progress'], { agents: [], executions }));
    await act(async () => { await vi.advanceTimersByTimeAsync(interval - 1); });
    expect(getDeliveryProgress).toHaveBeenCalledTimes(initialCalls);
    await act(async () => { await vi.advanceTimersByTimeAsync(1); });
    expect(getDeliveryProgress).toHaveBeenCalledTimes(initialCalls + 1);
    unmount(); client.clear();
  });
});

it('surfaces 503 immediately, backs off polling, and recovers on explicit retry', async () => {
  vi.useFakeTimers();
  vi.mocked(getDeliveryProgress).mockRejectedValueOnce(new Error('503'));
  const client = new QueryClient();
  const wrapper = ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  const { result, unmount } = renderHook(() => useDeliveryProgress('42', 'IN_PROGRESS'), { wrapper });
  await act(async () => { await vi.advanceTimersByTimeAsync(1); });
  expect(result.current.isError).toBe(true);
  expect(result.current.isLoading).toBe(false);
  await act(async () => { await vi.advanceTimersByTimeAsync(29000); });
  expect(getDeliveryProgress).toHaveBeenCalledTimes(1);
  await act(async () => { await result.current.refetch(); await vi.advanceTimersByTimeAsync(1); });
  expect(result.current.isSuccess).toBe(true);
  expect(getDeliveryProgress).toHaveBeenCalledTimes(2);
  unmount(); client.clear();
});
