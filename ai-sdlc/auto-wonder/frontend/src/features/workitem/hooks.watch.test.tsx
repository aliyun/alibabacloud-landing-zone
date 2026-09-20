import { describe, it, expect, beforeEach, vi } from 'vitest';
import { act, renderHook } from '@testing-library/react';
import type { ReactNode } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { message } from 'antd';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { useToggleWatch } from './hooks';

function ok(data: unknown) {
  return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data });
}

// 用非鉴权类错误码，避免触发 client 的 workspace 重新同步逻辑
function fail(errorMessage: string, code = '10500') {
  return HttpResponse.json({ success: false, code, message: errorMessage, traceId: null, data: null });
}

// useToggleWatch 是列表页与详情页共用的关注入口逻辑：
// 这里直接跑真实 hook（不 mock api），覆盖「关注/取消」的 HTTP 方法分流、
// 成功后的 toast 与三处缓存失效、失败时的两种文案分支。
describe('useToggleWatch', () => {
  let queryClient: QueryClient;

  function wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
  }

  beforeEach(() => {
    queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });
    vi.restoreAllMocks();
  });

  it('未关注态发起 POST /watch（关注）', async () => {
    const requests: Array<{ method: string; url: string }> = [];
    server.use(
      http.post('/api/workitems/:id/watch', ({ request }) => {
        requests.push({ method: request.method, url: new URL(request.url).pathname });
        return ok({ workitemId: 42, watched: true, watcherCount: 1 });
      }),
      http.delete('/api/workitems/:id/watch', ({ request }) => {
        requests.push({ method: request.method, url: new URL(request.url).pathname });
        return ok({ workitemId: 42, watched: false, watcherCount: 0 });
      }),
    );

    const { result } = renderHook(() => useToggleWatch(), { wrapper });
    await act(async () => {
      await result.current.mutateAsync({ id: 42, watched: false });
    });

    expect(requests).toEqual([{ method: 'POST', url: '/api/workitems/42/watch' }]);
  });

  it('已关注态发起 DELETE /watch（取消关注）', async () => {
    const requests: Array<{ method: string; url: string }> = [];
    server.use(
      http.post('/api/workitems/:id/watch', ({ request }) => {
        requests.push({ method: request.method, url: new URL(request.url).pathname });
        return ok({ workitemId: 42, watched: true, watcherCount: 1 });
      }),
      http.delete('/api/workitems/:id/watch', ({ request }) => {
        requests.push({ method: request.method, url: new URL(request.url).pathname });
        return ok({ workitemId: 42, watched: false, watcherCount: 0 });
      }),
    );

    const { result } = renderHook(() => useToggleWatch(), { wrapper });
    await act(async () => {
      await result.current.mutateAsync({ id: 42, watched: true });
    });

    expect(requests).toEqual([{ method: 'DELETE', url: '/api/workitems/42/watch' }]);
  });

  it('关注成功后 toast「已关注」并失效详情/关注人/列表三处缓存', async () => {
    const success = vi.spyOn(message, 'success').mockImplementation(
      () => undefined as unknown as ReturnType<typeof message.success>,
    );
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');
    server.use(
      http.post('/api/workitems/:id/watch', () => ok({ workitemId: 7, watched: true, watcherCount: 2 })),
    );

    const { result } = renderHook(() => useToggleWatch(), { wrapper });
    await act(async () => {
      await result.current.mutateAsync({ id: 7, watched: false });
    });

    expect(success).toHaveBeenCalledWith('已关注');
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['workitem', 7] });
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['workitem', 7, 'watchers'] });
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['workitems'] });
  });

  it('取消成功后 toast「已取消关注」', async () => {
    const success = vi.spyOn(message, 'success').mockImplementation(
      () => undefined as unknown as ReturnType<typeof message.success>,
    );
    server.use(
      http.delete('/api/workitems/:id/watch', () => ok({ workitemId: 7, watched: false, watcherCount: 1 })),
    );

    const { result } = renderHook(() => useToggleWatch(), { wrapper });
    await act(async () => {
      await result.current.mutateAsync({ id: 7, watched: true });
    });

    expect(success).toHaveBeenCalledWith('已取消关注');
  });

  it('失败时用后端返回的错误信息 toast', async () => {
    const error = vi.spyOn(message, 'error').mockImplementation(
      () => undefined as unknown as ReturnType<typeof message.error>,
    );
    server.use(http.post('/api/workitems/:id/watch', () => fail('无访问权限')));

    const { result } = renderHook(() => useToggleWatch(), { wrapper });
    await act(async () => {
      await result.current.mutateAsync({ id: 9, watched: false }).catch(() => undefined);
    });

    expect(result.current.isError).toBe(true);
    expect(error).toHaveBeenCalledWith('无访问权限');
  });

  it('失败且后端无错误信息时回退到默认文案', async () => {
    const error = vi.spyOn(message, 'error').mockImplementation(
      () => undefined as unknown as ReturnType<typeof message.error>,
    );
    server.use(http.post('/api/workitems/:id/watch', () => fail('')));

    const { result } = renderHook(() => useToggleWatch(), { wrapper });
    await act(async () => {
      await result.current.mutateAsync({ id: 9, watched: false }).catch(() => undefined);
    });

    expect(result.current.isError).toBe(true);
    expect(error).toHaveBeenCalledWith('操作失败');
  });
});
