import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { createElement, type ReactNode } from 'react';
import { beforeEach, describe, expect, it } from 'vitest';
import { server } from '@/test/mocks/server';
import { ApiError } from '@/shared/types/common';
import {
  allWorkspacesQueryKey,
  useAllWorkspaces,
  useCancelAccessRequest,
  useSubmitAccessRequest,
} from './workspaceDiscoveryApi';

function wrapper({ children }: { children: ReactNode }) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return createElement(QueryClientProvider, { client }, children);
}

// Pagination values are deliberately distinct from every page/size argument passed by
// the tests below, so an implementation that fabricates the numbers instead of passing
// the response body through cannot satisfy the assertions.
const payload = {
  list: [
    {
      id: 10008,
      name: 'Terraform',
      description: '基础设施即代码',
      membershipStatus: 'MEMBER',
      accessLevel: 'ADMIN',
    },
    {
      id: 10009,
      name: 'Kubernetes',
      description: '容器编排',
      membershipStatus: 'NOT_MEMBER',
      accessLevel: null,
    },
  ],
  total: 137,
  pageNum: 4,
  pageSize: 7,
};

let capturedUrl: URL | null = null;
let requestCount = 0;

beforeEach(() => {
  capturedUrl = null;
  requestCount = 0;
});

function mockAllWorkspaces() {
  server.use(
    http.get('/api/workspaces/all', ({ request }) => {
      requestCount += 1;
      capturedUrl = new URL(request.url);
      return HttpResponse.json({
        success: true, code: '0', message: '', data: payload, traceId: null,
      });
    }),
  );
}

describe('allWorkspacesQueryKey', () => {
  it('keys on keyword, page and size under a shared prefix', () => {
    expect(allWorkspacesQueryKey('terra', 2, 20)).toEqual([
      'workspaces', 'all', 'terra', 2, 20,
    ]);
    expect(allWorkspacesQueryKey('', 1, 20)).toEqual(['workspaces', 'all', '', 1, 20]);
  });
});

describe('useAllWorkspaces', () => {
  it('returns the paginated payload with pageNum and pageSize', async () => {
    mockAllWorkspaces();

    const { result } = renderHook(() => useAllWorkspaces('', 1, 20), { wrapper });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(result.current.data?.list).toHaveLength(2);
    expect(result.current.data?.list[0]).toMatchObject({
      id: 10008,
      name: 'Terraform',
      membershipStatus: 'MEMBER',
      accessLevel: 'ADMIN',
    });
    expect(result.current.data?.list[1].accessLevel).toBeNull();
    // Deep-equals the fixture rather than restating its numbers, so an implementation
    // that substitutes its own total/pageNum/pageSize is caught instead of coincidentally
    // agreeing with the assertion.
    expect(result.current.data).toEqual(payload);
    // Guards against a page/size naming regression in the payload contract.
    expect(result.current.data).not.toHaveProperty('page');
    expect(result.current.data).not.toHaveProperty('size');
  });

  it('sends page and size and omits keyword when it is empty', async () => {
    mockAllWorkspaces();

    const { result } = renderHook(() => useAllWorkspaces('', 3, 50), { wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(capturedUrl!.searchParams.get('page')).toBe('3');
    expect(capturedUrl!.searchParams.get('size')).toBe('50');
    expect(capturedUrl!.searchParams.has('keyword')).toBe(false);
  });

  it('sends keyword when it is non-empty', async () => {
    mockAllWorkspaces();

    const { result } = renderHook(() => useAllWorkspaces('terra', 1, 20), { wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(capturedUrl!.searchParams.get('keyword')).toBe('terra');
    expect(capturedUrl!.searchParams.get('page')).toBe('1');
    expect(capturedUrl!.searchParams.get('size')).toBe('20');
  });

  it('treats a whitespace-only keyword as empty', async () => {
    mockAllWorkspaces();

    const { result } = renderHook(() => useAllWorkspaces('   ', 1, 20), { wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(capturedUrl!.searchParams.has('keyword')).toBe(false);
  });

  it('keeps the previous results on screen while the next keyword is in flight', async () => {
    // Every part of the key changes when the debounced keyword changes, so without
    // placeholderData `data` would drop to undefined and the list would blank out.
    let releaseSecond: (() => void) | null = null;
    const secondInFlight = new Promise<void>((resolve) => { releaseSecond = resolve; });

    server.use(
      http.get('/api/workspaces/all', async ({ request }) => {
        const keyword = new URL(request.url).searchParams.get('keyword');
        if (keyword === 'kube') {
          await secondInFlight;
        }
        return HttpResponse.json({
          success: true,
          code: '0',
          message: '',
          data: { ...payload, list: [{ ...payload.list[0], name: `hit-${keyword}` }] },
          traceId: null,
        });
      }),
    );

    const client = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });
    function sharedWrapper({ children }: { children: ReactNode }) {
      return createElement(QueryClientProvider, { client }, children);
    }

    const { result, rerender } = renderHook(
      ({ keyword }) => useAllWorkspaces(keyword, 1, 20),
      { wrapper: sharedWrapper, initialProps: { keyword: 'terra' } },
    );

    await waitFor(() => expect(result.current.data?.list[0].name).toBe('hit-terra'));

    rerender({ keyword: 'kube' });

    // Mid-transition: the new key has no cache entry yet, so this asserts the
    // placeholder is serving the old page instead of an empty loading state.
    await waitFor(() => expect(result.current.isPlaceholderData).toBe(true));
    expect(result.current.data).not.toBeUndefined();
    expect(result.current.data?.list[0].name).toBe('hit-terra');
    expect(result.current.isLoading).toBe(false);

    releaseSecond!();

    await waitFor(() => expect(result.current.data?.list[0].name).toBe('hit-kube'));
    expect(result.current.isPlaceholderData).toBe(false);
  });

  it('normalizes the keyword into the query key so padded variants share one cache entry', async () => {
    // useAllWorkspaces trims ONCE and feeds the trimmed value into both the key and the
    // request (allWorkspacesQueryKey itself is a pure helper and does not normalize).
    // Keying on the raw keyword would still produce a keyword-less URL, because
    // listAllWorkspaces trims separately -- so only a fetch count taken across both
    // spellings can distinguish a shared cache entry from a split one.
    mockAllWorkspaces();

    const client = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });
    function sharedWrapper({ children }: { children: ReactNode }) {
      return createElement(QueryClientProvider, { client }, children);
    }

    const { result, rerender } = renderHook(
      ({ keyword }) => useAllWorkspaces(keyword, 1, 20),
      { wrapper: sharedWrapper, initialProps: { keyword: 'terra' } },
    );

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(requestCount).toBe(1);

    rerender({ keyword: ' terra ' });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    // A cache split would show up here as a second fetch for the padded variant.
    expect(result.current.isPlaceholderData).toBe(false);
    expect(requestCount).toBe(1);
  });
});

describe('useSubmitAccessRequest', () => {
  it('POSTs the requested level to the workspace access-requests path', async () => {
    let capturedBody: unknown = null;
    let capturedPath: string | null = null;
    server.use(
      http.post('/api/workspaces/:id/access-requests', async ({ request }) => {
        capturedPath = new URL(request.url).pathname;
        capturedBody = await request.json();
        return HttpResponse.json({
          success: true, code: '0', message: '', data: null, traceId: null,
        });
      }),
    );

    const { result } = renderHook(() => useSubmitAccessRequest(), { wrapper });

    result.current.mutate({ workspaceId: 10009, requestedLevel: 'READ_WRITE' });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(capturedPath).toBe('/api/workspaces/10009/access-requests');
    expect(capturedBody).toEqual({ requestedLevel: 'READ_WRITE' });
  });

  it('invalidates the all-workspaces list prefix on success', async () => {
    mockAllWorkspaces();
    server.use(
      http.post('/api/workspaces/:id/access-requests', () => HttpResponse.json({
        success: true, code: '0', message: '', data: null, traceId: null,
      })),
    );

    const client = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });
    function sharedWrapper({ children }: { children: ReactNode }) {
      return createElement(QueryClientProvider, { client }, children);
    }

    const { result } = renderHook(
      () => ({
        list: useAllWorkspaces('terra', 2, 20),
        submit: useSubmitAccessRequest(),
      }),
      { wrapper: sharedWrapper },
    );

    await waitFor(() => expect(result.current.list.isSuccess).toBe(true));
    const before = result.current.list.dataUpdatedAt;

    result.current.submit.mutate({ workspaceId: 10009, requestedLevel: 'READ_ONLY' });
    await waitFor(() => expect(result.current.submit.isSuccess).toBe(true));

    // A keyword/page/size-specific key would be missed by an exact-key
    // invalidation, so the refetch proves the prefix match works.
    await waitFor(() => expect(result.current.list.dataUpdatedAt).toBeGreaterThan(before));
  });

  it('surfaces a business error as a rejected mutation carrying the ApiError code', async () => {
    server.use(
      http.post('/api/workspaces/:id/access-requests', () => HttpResponse.json({
        success: false,
        code: '12012',
        message: '已有待审批的申请',
        data: null,
        traceId: 'trace-dup',
      })),
    );

    const { result } = renderHook(() => useSubmitAccessRequest(), { wrapper });

    result.current.mutate({ workspaceId: 10009, requestedLevel: 'READ_WRITE' });

    await waitFor(() => expect(result.current.isError).toBe(true));

    const error = result.current.error;
    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).code).toBe('12012');
    expect((error as ApiError).message).toBe('已有待审批的申请');
  });
});

describe('useCancelAccessRequest', () => {
  it('POSTs to the workspace-specific cancel path for the given request id', async () => {
    let capturedPath: string | null = null;
    server.use(
      http.post('/api/workspaces/:id/access-requests/:requestId/cancel', ({ request }) => {
        capturedPath = new URL(request.url).pathname;
        return HttpResponse.json({
          success: true, code: '0', message: '', data: null, traceId: null,
        });
      }),
    );

    const { result } = renderHook(() => useCancelAccessRequest(), { wrapper });

    result.current.mutate({ workspaceId: 10008, requestId: 77 });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    // Both ids come from the arguments: a fabricated path or swapped placeholders
    // would not match these exact numbers.
    expect(capturedPath).toBe('/api/workspaces/10008/access-requests/77/cancel');
  });

  it('invalidates the all-workspaces list prefix on success', async () => {
    mockAllWorkspaces();
    server.use(
      http.post('/api/workspaces/:id/access-requests/:requestId/cancel', () => HttpResponse.json({
        success: true, code: '0', message: '', data: null, traceId: null,
      })),
    );

    const client = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });
    function sharedWrapper({ children }: { children: ReactNode }) {
      return createElement(QueryClientProvider, { client }, children);
    }

    const { result } = renderHook(
      () => ({
        list: useAllWorkspaces('terra', 2, 20),
        cancel: useCancelAccessRequest(),
      }),
      { wrapper: sharedWrapper },
    );

    await waitFor(() => expect(result.current.list.isSuccess).toBe(true));
    const before = result.current.list.dataUpdatedAt;

    result.current.cancel.mutate({ workspaceId: 10008, requestId: 77 });
    await waitFor(() => expect(result.current.cancel.isSuccess).toBe(true));

    // Without a prefix invalidation the pending card would keep its 撤销申请
    // button after the record is physically deleted server-side.
    await waitFor(() => expect(result.current.list.dataUpdatedAt).toBeGreaterThan(before));
  });

  it('surfaces a race-lost business error as an ApiError instead of a system failure', async () => {
    server.use(
      http.post('/api/workspaces/:id/access-requests/:requestId/cancel', () => HttpResponse.json({
        success: false,
        code: '12014',
        message: '权限申请记录不存在',
        data: null,
        traceId: 'trace-gone',
      })),
    );

    const { result } = renderHook(() => useCancelAccessRequest(), { wrapper });

    result.current.mutate({ workspaceId: 10008, requestId: 77 });

    await waitFor(() => expect(result.current.isError).toBe(true));

    const error = result.current.error;
    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).code).toBe('12014');
    expect((error as ApiError).message).toBe('权限申请记录不存在');
  });
});
