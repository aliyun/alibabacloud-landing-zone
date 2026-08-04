import { describe, it, expect, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { MemoryReviewPage } from './MemoryReviewPage';
import { useAuthStore } from '@/shared/auth/store';
import { message } from 'antd';

function renderPage(accessLevel: 'READ_ONLY' | 'READ_WRITE' = 'READ_WRITE') {
  useAuthStore.setState({ accessLevel });
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <MemoryReviewPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('MemoryReviewPage', () => {
  it('renders richer review dimensions including status and source reference', async () => {
    server.use(
      http.get('/api/memories/reviews', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [
          {
            id: 1,
            scope: 'ORG',
            ownerRef: null,
            type: 'RULE',
            title: '接口约束',
            contentMd: '统一返回 Result 包装',
            status: 'PENDING',
            source: 'LEARNING_DELTA',
            sourceRef: 'dispatch-42',
            version: 0,
            gmtCreate: '2026-07-01T10:00:00Z',
          },
        ],
      })),
    );

    renderPage();

    expect(await screen.findByText('接口约束')).toBeInTheDocument();
    expect(screen.getByText('组织全局')).toBeInTheDocument();
    expect(screen.getByText('待审核')).toBeInTheDocument();
    expect(screen.getByText('LEARNING_DELTA')).toBeInTheDocument();
    expect(screen.getByText('dispatch-42')).toBeInTheDocument();
  });

  it('prefills memory type and ownership when edit-approving then can promote to squad memory', async () => {
    let updateBody: Record<string, unknown> | undefined;
    let reviewBody: Record<string, unknown> | undefined;

    server.use(
      http.get('/api/memories/reviews', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [
          {
            id: 1,
            scope: 'AGENT',
            ownerRef: 30,
            type: 'RULE',
            title: '接口约束',
            contentMd: '统一返回 Result 包装',
            status: 'PENDING',
            source: 'LEARNING_DELTA',
            sourceRef: 'dispatch-42',
            version: 0,
            gmtCreate: '2026-07-01T10:00:00Z',
          },
        ],
      })),
      http.put('/api/memories/1', async ({ request }) => {
        updateBody = await request.json() as Record<string, unknown>;
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: {
            id: 1,
            scope: 'AGENT',
            ownerRef: 30,
            type: updateBody.type,
            title: '接口约束',
            contentMd: updateBody.contentMd,
            status: 'PENDING',
            source: 'LEARNING_DELTA',
            sourceRef: 'dispatch-42',
            version: 1,
            gmtCreate: '2026-07-01T10:00:00Z',
          },
        });
      }),
      http.post('/api/memories/1/review', async ({ request }) => {
        reviewBody = await request.json() as Record<string, unknown>;
        return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: null });
      }),
    );

    renderPage();
    await screen.findByText('接口约束');

    await userEvent.click(screen.getByRole('button', { name: /编辑采纳/ }));

    const dialog = await screen.findByRole('dialog', { name: /编辑后采纳/ });
    expect(within(dialog).getByText('当前类型：规则')).toBeInTheDocument();
    expect(within(dialog).getByDisplayValue('30')).toBeInTheDocument();

    await userEvent.click(within(dialog).getByText('偏好'));
    await userEvent.click(within(dialog).getByText('小队'));
    await userEvent.clear(within(dialog).getByLabelText('记忆归属 ID'));
    await userEvent.type(within(dialog).getByLabelText('记忆归属 ID'), '9');
    await userEvent.click(within(dialog).getByRole('button', { name: /确认采纳/ }));

    expect(updateBody).toMatchObject({ type: 'PREFERENCE', contentMd: '统一返回 Result 包装' });
    expect(reviewBody).toMatchObject({ decision: 'ADOPT', scope: 'SQUAD', ownerRef: 9 });
    const alert = await screen.findByRole('alert');
    expect(within(alert).getByText(/编辑后采纳成功/)).toBeInTheDocument();
    expect(within(alert).getByText(/已按最新内容和类型采纳/)).toBeInTheDocument();
  });

  it('renders full memory content in the card without truncation', async () => {
    const longContent = '这是一段较长的记忆内容，用于验证卡片不再截断。'.repeat(20);
    server.use(
      http.get('/api/memories/reviews', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [
          {
            id: 1,
            scope: 'ORG',
            ownerRef: null,
            type: 'FACT',
            title: '长文记忆',
            contentMd: longContent,
            status: 'PENDING',
            source: null,
            sourceRef: null,
            version: 0,
            gmtCreate: '2026-07-01T10:00:00Z',
          },
        ],
      })),
    );

    renderPage();

    expect(await screen.findByText(longContent)).toBeInTheDocument();
  });

  it('removes only the approved card from the list, leaving others untouched', async () => {
    let reviewRequests = 0;
    server.use(
      http.get('/api/memories/reviews', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [
          {
            id: 101, scope: 'ORG', ownerRef: null, type: 'FACT',
            title: '记忆A', contentMd: '内容A', status: 'PENDING',
            source: null, sourceRef: null, version: 0,
            gmtCreate: '2026-08-01T00:00:00Z',
          },
          {
            id: 102, scope: 'ORG', ownerRef: null, type: 'RULE',
            title: '记忆B', contentMd: '内容B', status: 'PENDING',
            source: null, sourceRef: null, version: 0,
            gmtCreate: '2026-08-02T00:00:00Z',
          },
        ],
      })),
      http.post('/api/memories/101/review', () => {
        reviewRequests += 1;
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null, data: null,
        });
      }),
    );

    renderPage();
    const cardA = await screen.findByText('记忆A').then((el) => el.closest('.ant-card') as HTMLElement);
    expect(screen.getByText('记忆B')).toBeInTheDocument();

    // Click 采纳 on card A only.
    await userEvent.click(within(cardA).getByRole('button', { name: /check 采纳/ }));
    expect(reviewRequests).toBe(1);

    // After success: card A is optimistically removed; card B remains in place
    // (proves the per-item cache update — a whole-list refetch would have re-fetched
    // and potentially caused visible jitter or a flash of empty state).
    await vi.waitFor(() => {
      expect(screen.queryByText('记忆A')).not.toBeInTheDocument();
    });
    expect(screen.getByText('记忆B')).toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: /check 采纳/ })).toHaveLength(1);
  });

  it('paginates to the next page when the current page is full', async () => {
    const requestedPages: number[] = [];
    server.use(
      http.get('/api/memories/reviews', ({ request }) => {
        const url = new URL(request.url);
        const page = Number(url.searchParams.get('page') ?? '1');
        requestedPages.push(page);
        const list = page === 1
          ? Array.from({ length: 20 }, (_, i) => ({
              id: i + 1,
              scope: 'ORG',
              ownerRef: null,
              type: 'FACT',
              title: `记忆${i + 1}`,
              contentMd: `内容${i + 1}`,
              status: 'PENDING',
              source: null,
              sourceRef: null,
              version: 0,
              gmtCreate: '2026-07-01T10:00:00Z',
            }))
          : [{
              id: 21,
              scope: 'ORG',
              ownerRef: null,
              type: 'FACT',
              title: '末页记忆',
              contentMd: '末页内容',
              status: 'PENDING',
              source: null,
              sourceRef: null,
              version: 0,
              gmtCreate: '2026-07-01T10:00:00Z',
            }];
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null, data: list,
        });
      }),
    );

    renderPage();
    await screen.findByText('记忆1');
    expect(requestedPages).toContain(1);
    expect(screen.getByText(/可能还有更多待审核记忆/)).toBeInTheDocument();

    await userEvent.click(screen.getByTitle('2'));

    await screen.findByText('末页记忆');
    expect(requestedPages).toContain(2);
    expect(screen.getByText(/共 1 条待审核/)).toBeInTheDocument();
  });

  it('keeps review commands visible but blocks them for read-only members', async () => {
    const errorSpy = vi.spyOn(message, 'error').mockImplementation(() => undefined as never);
    let reviewRequests = 0;
    server.use(
      http.get('/api/memories/reviews', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [{
          id: 1,
          scope: 'ORG',
          ownerRef: null,
          type: 'RULE',
          title: '接口约束',
          contentMd: '统一返回 Result 包装',
          status: 'PENDING',
          source: 'LEARNING_DELTA',
          sourceRef: 'dispatch-42',
          version: 0,
          gmtCreate: '2026-07-01T10:00:00Z',
        }],
      })),
      http.post('/api/memories/1/review', () => {
        reviewRequests += 1;
        return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: null });
      }),
    );

    renderPage('READ_ONLY');
    await screen.findByText('接口约束');
    await userEvent.click(screen.getByRole('button', { name: /check 采纳/ }));

    expect(errorSpy).toHaveBeenCalledWith('当前为只读权限，采纳记忆需要读写权限');
    expect(reviewRequests).toBe(0);

    await userEvent.click(screen.getByRole('button', { name: /编辑采纳/ }));
    expect(screen.queryByRole('dialog', { name: /编辑后采纳/ })).not.toBeInTheDocument();
    errorSpy.mockRestore();
  });

  it('isolates card loading to the reviewed card only', async () => {
    let reviewCount = 0;
    server.use(
      http.get('/api/memories/reviews', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [
          { id: 1, scope: 'ORG', ownerRef: null, type: 'FACT', title: '记忆A', contentMd: '内容A', status: 'PENDING', source: null, sourceRef: null, version: 0, gmtCreate: '2026-07-01T10:00:00Z' },
          { id: 2, scope: 'ORG', ownerRef: null, type: 'FACT', title: '记忆B', contentMd: '内容B', status: 'PENDING', source: null, sourceRef: null, version: 0, gmtCreate: '2026-07-01T10:00:00Z' },
        ],
      })),
      http.post('/api/memories/1/review', async () => {
        await new Promise((r) => setTimeout(r, 200));
        reviewCount++;
        return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: null });
      }),
      http.post('/api/memories/2/review', () => HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: null })),
    );

    renderPage();
    await screen.findByText('记忆A');
    await screen.findByText('记忆B');

    const adoptButtons = screen.getAllByRole('button', { name: /采纳/ })
      .filter(btn => btn.textContent?.trim() === '采纳');
    expect(adoptButtons).toHaveLength(2);

    await userEvent.click(adoptButtons[0]);

    expect(adoptButtons[0]).toHaveClass('ant-btn-loading');
    expect(screen.getByText('内容A')).toBeVisible();
    expect(screen.getByText('内容B')).toBeVisible();
    expect(document.querySelectorAll('.ant-card-loading')).toHaveLength(0);

    await vi.waitFor(() => expect(reviewCount).toBe(1), { timeout: 3000 });
  });
});
