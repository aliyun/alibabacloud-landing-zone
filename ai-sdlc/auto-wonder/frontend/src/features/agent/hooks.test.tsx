import { beforeEach, describe, expect, it } from 'vitest';
import { act, renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider, focusManager } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import type { ReactNode } from 'react';
import { server } from '@/test/mocks/server';
import { useAuthStore } from '@/shared/auth/store';
import {
  PENDING_REVIEW_COUNT_QUERY_KEY,
  useAgentPendingReviewCount,
  usePendingReviews,
} from './hooks';

function pendingAgent(id: number, name: string) {
  return {
    id,
    name,
    avatarUrl: null,
    status: 'PENDING_REVIEW',
    onlineVersionId: null,
    editingVersionId: 5,
    latestVersionNo: 2,
    version: 1,
    gmtCreate: '2026-07-01',
  };
}

function jsonOk(data: unknown) {
  return HttpResponse.json({ success: true, code: '0', message: '', data, traceId: null });
}

// 与 App.tsx 的全局默认保持一致，用例只由 hook 自身的刷新策略驱动
function makeClient() {
  return new QueryClient({
    defaultOptions: { queries: { retry: false, staleTime: 30_000, refetchOnWindowFocus: false } },
  });
}

function makeWrapper(client: QueryClient) {
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  };
}

async function flushAsync() {
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, 50));
  });
}

describe('审核角标与待审核列表的缓存一致性', () => {
  beforeEach(() => {
    useAuthStore.getState().clear();
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_WRITE');
  });

  it('角标缓存落后于列表时，列表刷新联动角标重取服务端真值', async () => {
    let countCalls = 0;
    const client = makeClient();
    const wrapper = makeWrapper(client);
    server.use(
      http.get('/api/agents/reviews/count', () => {
        countCalls += 1;
        return jsonOk(0);
      }),
    );
    const badge = renderHook(() => useAgentPendingReviewCount(), { wrapper });
    await waitFor(() => expect(badge.result.current.data).toBe(0));

    // 后台新增待审数字员工：列表查得到，角标此刻才返回新值
    let listCalls = 0;
    server.use(
      http.get('/api/agents', () => {
        listCalls += 1;
        return jsonOk([pendingAgent(1, 'PendingBot')]);
      }),
      http.get('/api/agents/reviews/count', () => {
        countCalls += 1;
        return jsonOk(1);
      }),
    );

    const list = renderHook(() => usePendingReviews(), { wrapper });
    await waitFor(() => expect(list.result.current.data).toHaveLength(1));
    await waitFor(() => expect(badge.result.current.data).toBe(1));

    await flushAsync();
    expect(listCalls).toBe(1);
    expect(countCalls).toBe(2);
  });

  it('角标缓存有值而列表已无待审条目时，角标同步清零', async () => {
    let countCalls = 0;
    const client = makeClient();
    const wrapper = makeWrapper(client);
    server.use(
      http.get('/api/agents/reviews/count', () => {
        countCalls += 1;
        return jsonOk(1);
      }),
    );
    const badge = renderHook(() => useAgentPendingReviewCount(), { wrapper });
    await waitFor(() => expect(badge.result.current.data).toBe(1));

    // 其他成员/MCP 审核通过后，列表与角标接口都应返回空
    server.use(
      http.get('/api/agents', () => jsonOk([])),
      http.get('/api/agents/reviews/count', () => {
        countCalls += 1;
        return jsonOk(0);
      }),
    );

    const list = renderHook(() => usePendingReviews(), { wrapper });
    await waitFor(() => expect(list.result.current.data).toEqual([]));
    await waitFor(() => expect(badge.result.current.data).toBe(0));

    await flushAsync();
    expect(countCalls).toBe(2);
  });

  it('列表与角标一致时不产生额外请求', async () => {
    let listCalls = 0;
    let countCalls = 0;
    const client = makeClient();
    const wrapper = makeWrapper(client);
    server.use(
      http.get('/api/agents', () => {
        listCalls += 1;
        return jsonOk([pendingAgent(1, 'A'), pendingAgent(2, 'B')]);
      }),
      http.get('/api/agents/reviews/count', () => {
        countCalls += 1;
        return jsonOk(2);
      }),
    );

    const badge = renderHook(() => useAgentPendingReviewCount(), { wrapper });
    const list = renderHook(() => usePendingReviews(), { wrapper });
    await waitFor(() => expect(list.result.current.data).toHaveLength(2));
    await waitFor(() => expect(badge.result.current.data).toBe(2));

    await flushAsync();
    expect(listCalls).toBe(1);
    expect(countCalls).toBe(1);
    expect(client.getQueryData(PENDING_REVIEW_COUNT_QUERY_KEY)).toBe(2);
  });

  it('窗口重新获得焦点时列表与角标都重取最新数据', async () => {
    let listCalls = 0;
    let countCalls = 0;
    const client = makeClient();
    const wrapper = makeWrapper(client);
    server.use(
      http.get('/api/agents', () => {
        listCalls += 1;
        return jsonOk([]);
      }),
      http.get('/api/agents/reviews/count', () => {
        countCalls += 1;
        return jsonOk(0);
      }),
    );

    const list = renderHook(() => usePendingReviews(), { wrapper });
    const badge = renderHook(() => useAgentPendingReviewCount(), { wrapper });
    await waitFor(() => expect(list.result.current.data).toEqual([]));
    await waitFor(() => expect(badge.result.current.data).toBe(0));
    expect(listCalls).toBe(1);
    expect(countCalls).toBe(1);

    act(() => {
      focusManager.setFocused(false);
    });
    act(() => {
      focusManager.setFocused(true);
    });

    await waitFor(() => expect(listCalls).toBe(2));
    await waitFor(() => expect(countCalls).toBe(2));

    await flushAsync();
    expect(listCalls).toBe(2);
    expect(countCalls).toBe(2);
  });

  it('审核列表每次挂载都重新拉取，不命中旧缓存', async () => {
    let listCalls = 0;
    const client = makeClient();
    const wrapper = makeWrapper(client);
    server.use(
      http.get('/api/agents', () => {
        listCalls += 1;
        return jsonOk(listCalls === 1 ? [] : [pendingAgent(9, 'LaterBot')]);
      }),
    );

    const first = renderHook(() => usePendingReviews(), { wrapper });
    await waitFor(() => expect(first.result.current.data).toEqual([]));
    first.unmount();

    const second = renderHook(() => usePendingReviews(), { wrapper });
    await waitFor(() => expect(second.result.current.data).toHaveLength(1));

    await flushAsync();
    expect(listCalls).toBe(2);
  });

  it('角标刷新值变化且与列表不一致时，联动列表重取服务端真值', async () => {
    let listCalls = 0;
    let countCalls = 0;
    const client = makeClient();
    const wrapper = makeWrapper(client);
    server.use(
      http.get('/api/agents', () => {
        listCalls += 1;
        return jsonOk([]);
      }),
      http.get('/api/agents/reviews/count', () => {
        countCalls += 1;
        return jsonOk(0);
      }),
    );

    const badge = renderHook(() => useAgentPendingReviewCount(), { wrapper });
    await waitFor(() => expect(badge.result.current.data).toBe(0));
    const list = renderHook(() => usePendingReviews(), { wrapper });
    await waitFor(() => expect(list.result.current.data).toEqual([]));

    // 后台新增待审数字员工：角标轮询先看到新值，列表此刻仍是旧缓存
    server.use(
      http.get('/api/agents', () => {
        listCalls += 1;
        return jsonOk([pendingAgent(11, 'PollBot')]);
      }),
      http.get('/api/agents/reviews/count', () => {
        countCalls += 1;
        return jsonOk(1);
      }),
    );

    await act(async () => {
      await badge.result.current.refetch();
    });

    await waitFor(() => expect(list.result.current.data).toHaveLength(1));
    await waitFor(() => expect(badge.result.current.data).toBe(1));

    await flushAsync();
    expect(listCalls).toBe(2);
    expect(countCalls).toBe(2);
  });

  it('角标值未变化时，列表刷新不会反向触发额外请求', async () => {
    let listCalls = 0;
    let countCalls = 0;
    const client = makeClient();
    const wrapper = makeWrapper(client);
    server.use(
      http.get('/api/agents', () => {
        listCalls += 1;
        const name = listCalls === 1 ? 'AlphaBot' : 'BetaBot';
        return jsonOk([pendingAgent(12, name)]);
      }),
      http.get('/api/agents/reviews/count', () => {
        countCalls += 1;
        return jsonOk(1);
      }),
    );

    const badge = renderHook(() => useAgentPendingReviewCount(), { wrapper });
    const list = renderHook(() => usePendingReviews(), { wrapper });
    await waitFor(() => expect(badge.result.current.data).toBe(1));
    await waitFor(() => expect(list.result.current.data).toHaveLength(1));

    await act(async () => {
      await list.result.current.refetch();
    });
    await waitFor(() => expect(list.result.current.data?.[0]?.name).toBe('BetaBot'));

    await flushAsync();
    expect(listCalls).toBe(2);
    expect(countCalls).toBe(1);
  });

  it('角标首次加载与列表不一致时不触发列表刷新', async () => {
    let listCalls = 0;
    let countCalls = 0;
    const client = makeClient();
    const wrapper = makeWrapper(client);
    server.use(
      http.get('/api/agents', () => {
        listCalls += 1;
        return jsonOk([pendingAgent(13, 'CachedBot')]);
      }),
      http.get('/api/agents/reviews/count', () => {
        countCalls += 1;
        return jsonOk(2);
      }),
    );

    const list = renderHook(() => usePendingReviews(), { wrapper });
    await waitFor(() => expect(list.result.current.data).toHaveLength(1));

    const badge = renderHook(() => useAgentPendingReviewCount(), { wrapper });
    await waitFor(() => expect(badge.result.current.data).toBe(2));

    await flushAsync();
    expect(listCalls).toBe(1);
    expect(countCalls).toBe(1);
  });
});
