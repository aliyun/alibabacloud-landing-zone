import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { WatcherList } from './WatcherList';

// WatcherList.test.tsx 用 vi.mock 打桩了 useWatchers，只验证渲染分支；
// 这里不打桩，跑真实的 useWatchers 查询，验证它确实按 workitemId 发 GET /watchers、
// 用正确的 queryKey 写缓存，且 workitemId 为空时 enabled=false 不发请求。
function renderList(workitemId: string) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const utils = render(
    <QueryClientProvider client={queryClient}>
      <WatcherList workitemId={workitemId} />
    </QueryClientProvider>,
  );
  return { queryClient, ...utils };
}

describe('WatcherList（真实 useWatchers 查询）', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('按 workitemId 请求关注人列表、渲染并写入缓存', async () => {
    const requested: string[] = [];
    server.use(
      http.get('/api/workitems/:workitemId/watchers', ({ params }) => {
        requested.push(String(params.workitemId));
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: [
            { userId: 100, name: '蔡何', displayId: '10000', role: 'HUMAN', roleName: '关注人', isAgent: false, online: false, status: '0' },
          ],
        });
      }),
    );

    const { queryClient } = renderList('42');

    expect(await screen.findByText('蔡何')).toBeInTheDocument();
    expect(screen.getByText('关注人 (1)')).toBeInTheDocument();
    expect(screen.getByText('工号: 10000')).toBeInTheDocument();
    await waitFor(() => expect(requested).toContain('42'));
    expect(queryClient.getQueryData(['workitem', '42', 'watchers'])).toBeDefined();
  });

  it('workitemId 为空时不发请求并展示空态', async () => {
    const requested: string[] = [];
    server.use(
      http.get('/api/workitems/:workitemId/watchers', ({ params }) => {
        requested.push(String(params.workitemId));
        return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: [] });
      }),
    );

    renderList('');

    expect(await screen.findByText('暂无关注人')).toBeInTheDocument();
    expect(requested).toHaveLength(0);
  });
});
