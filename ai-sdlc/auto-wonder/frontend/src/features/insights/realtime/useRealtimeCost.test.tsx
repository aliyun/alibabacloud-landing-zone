import type { ReactNode } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { server } from '@/test/mocks/server';
import { useRealtimeCost } from './hooks';
import type { CostComposition, RealtimeDashboard } from './api';

function ok(data: unknown) {
  return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data });
}

function compositionFor(range: number): CostComposition {
  return {
    range,
    scope: 'ALL_AGENTS',
    total: 100 + range,
    sources: [
      { key: 'WORKITEM_EXECUTION', name: '工单执行', credits: 100 + range, share: 1 },
    ],
  };
}

function dashboard(range: number, agentId: string, composition: CostComposition | null = compositionFor(range)) {
  return {
    kpi: {
      runningDispatches: 0,
      todayCompletedTasks: 0,
      weekCompletedTasks: 0,
      queuedDispatches: 0,
      onlineAgents: 0,
      cost: {
        range,
        agentId,
        total: agentId === 'all' ? composition?.total ?? 0 : 40.2,
        perTask: 1,
        perDay: 2,
      },
    },
    costComposition: composition,
  } as unknown as RealtimeDashboard;
}

function wrapper(client: QueryClient) {
  return ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  );
}

function renderCostHook() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return { client, ...renderHook(() => useRealtimeCost(), { wrapper: wrapper(client) }) };
}

let requests: string[] = [];

function stubCost(compositionForAgent: (range: number, agentId: string) => CostComposition | null = compositionFor) {
  requests = [];
  server.use(
    http.get('/api/dashboard/realtime', ({ request }) => {
      const url = new URL(request.url);
      requests.push(url.search);
      const range = Number(url.searchParams.get('range'));
      const agentId = url.searchParams.get('agentId') ?? 'all';
      return ok(dashboard(range, agentId, compositionForAgent(range, agentId)));
    }),
    http.get('/api/insights/workers', () => ok([{ id: 12, name: '验收数字员工' }])),
  );
}

beforeEach(() => {
  localStorage.clear();
  stubCost();
});

afterEach(() => {
  localStorage.clear();
});

describe('useRealtimeCost', () => {
  it('loads the scoped cost and the all-agent composition in one request', async () => {
    const { result } = renderCostHook();

    await waitFor(() => expect(result.current.composition?.range).toBe(7));

    expect(requests).toEqual(['?range=7&agentId=all']);
    expect(result.current.cost).toEqual({ range: 7, agentId: 'all', total: 107, perTask: 1, perDay: 2 });
    expect(result.current.composition?.scope).toBe('ALL_AGENTS');
    expect(result.current.workers).toEqual([{ id: 12, name: '验收数字员工' }]);
    expect(result.current.isLoading).toBe(false);
  });

  it('replaces both blocks when the date range changes', async () => {
    const { result } = renderCostHook();
    await waitFor(() => expect(result.current.composition?.range).toBe(7));

    act(() => result.current.setTimeRange('30d'));

    await waitFor(() => expect(result.current.composition?.range).toBe(30));
    expect(requests.at(-1)).toBe('?range=30&agentId=all');
    expect(result.current.cost?.range).toBe(30);
    expect(result.current.cost?.total).toBe(130);
  });

  it('refreshes only the scoped cost when the agent changes and keeps the composition snapshot', async () => {
    stubCost((range, agentId) => agentId === 'all'
      ? compositionFor(range)
      // 服务端契约保证全员口径；这里故意返回不同数据，验证前端状态层不会覆盖已有构成。
      : { ...compositionFor(range), total: 9999, sources: [{ key: 'WORKITEM_EXECUTION', name: '工单执行', credits: 9999, share: 1 }] });
    const { result } = renderCostHook();
    await waitFor(() => expect(result.current.composition?.total).toBe(107));

    act(() => result.current.setWorkerId(12));

    await waitFor(() => expect(result.current.cost?.agentId).toBe('12'));
    expect(requests.at(-1)).toBe('?range=7&agentId=12');
    expect(result.current.cost?.total).toBe(40.2);
    expect(result.current.composition?.total).toBe(107);
    expect(result.current.composition?.sources).toHaveLength(1);
  });

  it('applies the new range composition even while a specific agent stays selected', async () => {
    stubCost((range, agentId) => agentId === 'all'
      ? compositionFor(range)
      : { ...compositionFor(range), total: 9999 });
    const { result } = renderCostHook();
    await waitFor(() => expect(result.current.composition?.range).toBe(7));

    act(() => result.current.setWorkerId(12));
    await waitFor(() => expect(result.current.cost?.agentId).toBe('12'));
    expect(result.current.composition?.range).toBe(7);

    act(() => result.current.setTimeRange('90d'));

    await waitFor(() => expect(result.current.composition?.range).toBe(90));
    expect(requests.at(-1)).toBe('?range=90&agentId=12');
    // 日期变化会覆盖构成快照：桩数据刻意与全员合计不同，以 range 判断已换成新响应。
    expect(result.current.composition?.total).toBe(9999);
    expect(result.current.cost?.agentId).toBe('12');
  });

  it('persists the range and agent preferences under the cost panel keys', async () => {
    const { result } = renderCostHook();
    await waitFor(() => expect(result.current.composition?.range).toBe(7));

    act(() => {
      result.current.setWorkerId(12);
      result.current.setTimeRange('90d');
    });

    expect(localStorage.getItem('aw:insights:v1:anonymous:none:realtime.cost.worker')).toBe('12');
    expect(localStorage.getItem('aw:insights:v1:anonymous:none:realtime.cost.range')).toBe('"90d"');
  });

  it('degrades to no cost blocks for a legacy realtime payload', async () => {
    server.use(
      http.get('/api/dashboard/realtime', () => ok({
        kpi: { runningDispatches: 0, todayCompletedTasks: 0, weekCompletedTasks: 0, queuedDispatches: 0, onlineAgents: 0 },
      })),
      http.get('/api/insights/workers', () => ok([])),
    );
    const { result } = renderCostHook();

    await waitFor(() => expect(result.current.isLoading).toBe(false));

    expect(result.current.cost).toBeUndefined();
    expect(result.current.composition).toBeNull();
    expect(result.current.isError).toBe(false);
  });

  it('surfaces the query error while keeping the block state local', async () => {
    server.use(
      http.get('/api/dashboard/realtime', () => HttpResponse.json(
        { success: false, code: '50000', message: 'realtime 失败', data: null, traceId: null },
        { status: 500 },
      )),
      http.get('/api/insights/workers', () => ok([])),
    );
    const { result } = renderCostHook();

    await waitFor(() => expect(result.current.isError).toBe(true));

    expect(result.current.error?.message).toBe('realtime 失败');
    expect(result.current.cost).toBeUndefined();
    expect(result.current.composition).toBeNull();
  });
});
