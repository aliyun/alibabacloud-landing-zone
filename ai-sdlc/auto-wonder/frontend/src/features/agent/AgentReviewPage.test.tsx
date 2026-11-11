import { afterEach, beforeEach, describe, it, expect, vi } from 'vitest';
import { render, screen, within, waitFor, act } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider, focusManager } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { message } from 'antd';
import { AgentReviewPage } from './AgentReviewPage';
import { useAuthStore } from '@/shared/auth/store';

function renderPageWith(queryClient: QueryClient) {
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter><AgentReviewPage /></MemoryRouter>
    </QueryClientProvider>,
  );
}

function renderPage() {
  return renderPageWith(new QueryClient({ defaultOptions: { queries: { retry: false } } }));
}

// 回归用例要验证 hook 覆盖了 App 全局默认值（staleTime 30s、refetchOnWindowFocus false），
// 因此这里刻意使用与 App 一致的默认 QueryClient，而不是测试里惯用的「零缓存」配置。
function makeAppLikeClient() {
  return new QueryClient({
    defaultOptions: { queries: { retry: false, staleTime: 30_000, refetchOnWindowFocus: false } },
  });
}

describe('AgentReviewPage', () => {
  beforeEach(() => {
    useAuthStore.getState().clear();
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_WRITE');
  });

  // antd 静态 message 的 toast 挂在 document.body 上，RTL 的 cleanup 不会移除，
  // 不清掉会残留到后续用例造成重复文本命中。
  afterEach(() => {
    message.destroy();
  });

  it('renders empty state when no pending reviews', async () => {
    server.use(
      http.get('/api/agents', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [],
      })),
    );
    renderPage();
    expect(await screen.findByText(/暂无待审核/)).toBeInTheDocument();
  });

  it('renders pending agent for review', async () => {
    server.use(
      http.get('/api/agents', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [
          { id: 1, name: 'PendingBot', avatarUrl: null, status: 'PENDING_REVIEW', onlineVersionId: null, editingVersionId: 5, latestVersionNo: 2, version: 1, gmtCreate: '2026-07-01' },
        ],
      })),
    );
    renderPage();
    expect(await screen.findByText('PendingBot')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /通过/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /驳回/ })).toBeInTheDocument();
  });

  it('shows persistent feedback after approving an agent', async () => {
    server.use(
      http.get('/api/agents', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [
          { id: 1, name: 'PendingBot', avatarUrl: null, status: 'PENDING_REVIEW', onlineVersionId: null, editingVersionId: 5, latestVersionNo: 2, version: 1, gmtCreate: '2026-07-01' },
        ],
      })),
      http.post('/api/agents/1/approve', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: { id: 1, name: 'PendingBot', avatarUrl: null, status: 'ONLINE', onlineVersionId: 5, editingVersionId: null, latestVersionNo: 2, version: 2, gmtCreate: '2026-07-01' },
      })),
    );

    renderPage();
    await screen.findByText('PendingBot');

    await userEvent.click(screen.getByRole('button', { name: /通过/ }));

    const alert = await screen.findByRole('alert');
    expect(within(alert).getByText(/PendingBot 已通过审核/)).toBeInTheDocument();
    expect(within(alert).getByText(/已可进入上线流转/)).toBeInTheDocument();
  });

  it('surfaces backend error message and refetches list when approve fails', async () => {
    const errorSpy = vi.spyOn(message, 'error').mockImplementation(() => undefined as never);
    let listHits = 0;
    server.use(
      http.get('/api/agents', () => {
        listHits += 1;
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: [
            { id: 1, name: 'PendingBot', avatarUrl: null, status: 'PENDING_REVIEW', onlineVersionId: null, editingVersionId: 5, latestVersionNo: 2, version: 1, gmtCreate: '2026-07-01' },
          ],
        });
      }),
      http.post('/api/agents/1/approve', () => HttpResponse.json(
        {
          success: false,
          code: '14005',
          message: '当前版本未处于待审核状态',
          data: null,
          traceId: 'abc',
        },
        { status: 400 },
      )),
    );

    renderPage();
    await screen.findByText('PendingBot');
    const hitsBefore = listHits;

    await userEvent.click(screen.getByRole('button', { name: /通过/ }));

    await waitFor(() => {
      expect(errorSpy).toHaveBeenCalled();
    });
    const calls = errorSpy.mock.calls.map(([content]) => String(content));
    expect(calls.some((text) => text.includes('当前版本未处于待审核状态'))).toBe(true);

    await waitFor(() => {
      expect(listHits).toBeGreaterThan(hitsBefore);
    });

    errorSpy.mockRestore();
  });

  it('refetches the pending list on remount even inside the app default staleTime', async () => {
    let listHits = 0;
    server.use(
      http.get('/api/agents', () => {
        listHits += 1;
        return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: [] });
      }),
    );

    const client = makeAppLikeClient();
    const first = renderPageWith(client);
    await screen.findByText(/暂无待审核/);
    expect(listHits).toBe(1);
    first.unmount();

    renderPageWith(client);
    await waitFor(() => expect(listHits).toBe(2));
  });

  it('refetches the pending list on window focus despite the app default disabling it', async () => {
    let listHits = 0;
    server.use(
      http.get('/api/agents', () => {
        listHits += 1;
        return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: [] });
      }),
    );

    renderPageWith(makeAppLikeClient());
    await screen.findByText(/暂无待审核/);
    expect(listHits).toBe(1);

    act(() => focusManager.setFocused(false));
    act(() => focusManager.setFocused(true));

    await waitFor(() => expect(listHits).toBe(2));
  });

  const pendingAgent = {
    id: 1, name: 'PendingBot', avatarUrl: null, status: 'PENDING_REVIEW',
    onlineVersionId: 5, editingVersionId: 6, latestVersionNo: 6, version: 1, gmtCreate: '2026-07-01',
  };

  function versionDetail(versionNo: number, overrides: Record<string, unknown> = {}) {
    return {
      id: versionNo, agentId: 1, versionNo, status: 'PENDING_REVIEW',
      roleName: 'Frontend Dev', roleCode: 'FRONTEND_DEV',
      businessBackground: 'soul line', responsibilities: 'agent line',
      sdlcId: 1, identityJson: '{"evolutionMode":"ASSISTED"}',
      submitNote: null, creatorId: 7, creatorName: '蔡何',
      reviewerId: null, reviewComment: null, reviewedAt: null,
      version: 0, gmtCreate: '2026-07-01',
      repoPerms: [], skills: [], memoryRefs: [], environmentVariables: [],
      ...overrides,
    };
  }

  it('opens the diff against the online baseline and shows the submit note', async () => {
    const fetched: string[] = [];
    server.use(
      http.get('/api/agents', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: [pendingAgent],
      })),
      http.get('/api/agents/1/versions', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [{ id: 5, versionNo: 5, status: 'ONLINE', roleName: 'Frontend Dev', roleCode: 'FRONTEND_DEV', gmtCreate: '2026-07-01' }],
      })),
      http.get('/api/agents/1/versions/5', () => {
        fetched.push('base');
        return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: versionDetail(5, { status: 'ONLINE', businessBackground: 'old soul' }) });
      }),
      http.get('/api/agents/1/versions/6', () => {
        fetched.push('target');
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: versionDetail(6, { businessBackground: 'new soul', submitNote: '升级 SOUL' }),
        });
      }),
    );

    renderPage();
    await userEvent.click(await screen.findByRole('button', { name: /查看变更/ }));

    expect(await screen.findByText(/提交人：蔡何/)).toBeInTheDocument();
    expect(screen.getByText('升级 SOUL')).toBeInTheDocument();
    expect(screen.getAllByText('在线版本 v5').length).toBeGreaterThan(0);
    expect(screen.getAllByTestId(/^awdiff-section-/)).toHaveLength(7);
    expect(fetched.sort()).toEqual(['base', 'target']);
  });

  it('renders the first-version view without requesting a baseline', async () => {
    const fetched: string[] = [];
    server.use(
      http.get('/api/agents', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [{ ...pendingAgent, onlineVersionId: null }],
      })),
      http.get('/api/agents/1/versions', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: [],
      })),
      http.get('/api/agents/1/versions/6', () => {
        fetched.push('target');
        return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: versionDetail(6) });
      }),
    );

    renderPage();
    await userEvent.click(await screen.findByRole('button', { name: /查看变更/ }));

    expect(await screen.findByText(/该数字员工尚无在线版本/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '上一处' })).not.toBeInTheDocument();
    expect(fetched).toEqual(['target']);
  });

  it('approves from inside the diff dialog after confirmation', async () => {
    const approvals: unknown[] = [];
    server.use(
      http.get('/api/agents', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: [pendingAgent],
      })),
      http.get('/api/agents/1/versions', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [{ id: 5, versionNo: 5, status: 'ONLINE', roleName: 'Frontend Dev', roleCode: 'FRONTEND_DEV', gmtCreate: '2026-07-01' }],
      })),
      http.get('/api/agents/1/versions/5', () => HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: versionDetail(5) })),
      http.get('/api/agents/1/versions/6', () => HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: versionDetail(6) })),
      http.post('/api/agents/1/approve', async ({ request }) => {
        approvals.push(await request.json());
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: { ...pendingAgent, status: 'ONLINE', onlineVersionId: 6, editingVersionId: null },
        });
      }),
    );

    renderPage();
    await userEvent.click(await screen.findByRole('button', { name: /查看变更/ }));
    await userEvent.click(await screen.findByRole('button', { name: '通过并上线' }));
    await userEvent.click(await screen.findByRole('button', { name: '确认通过' }));

    await waitFor(() => expect(approvals).toHaveLength(1));
    await waitFor(() => expect(screen.queryByText(/提交人：蔡何/)).not.toBeInTheDocument());
  });

  it('rejects from inside the diff dialog with a required reason', async () => {
    const rejections: unknown[] = [];
    server.use(
      http.get('/api/agents', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: [pendingAgent],
      })),
      http.get('/api/agents/1/versions', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [{ id: 5, versionNo: 5, status: 'ONLINE', roleName: 'Frontend Dev', roleCode: 'FRONTEND_DEV', gmtCreate: '2026-07-01' }],
      })),
      http.get('/api/agents/1/versions/5', () => HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: versionDetail(5) })),
      http.get('/api/agents/1/versions/6', () => HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: versionDetail(6) })),
      http.post('/api/agents/1/reject', async ({ request }) => {
        rejections.push(await request.json());
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: { ...pendingAgent, status: 'ONLINE' },
        });
      }),
    );

    renderPage();
    await userEvent.click(await screen.findByRole('button', { name: /查看变更/ }));
    await userEvent.click(await screen.findByRole('button', { name: /拒\s*绝/ }));
    await userEvent.type(await screen.findByLabelText('请输入驳回原因...'), '权限过宽');
    await userEvent.click(screen.getByRole('button', { name: '确认驳回' }));

    await waitFor(() => expect(rejections).toEqual([{ comment: '权限过宽' }]));
    await waitFor(() => expect(screen.queryByText(/提交人：蔡何/)).not.toBeInTheDocument());
  });

  it('disables the review actions for read-only members', async () => {
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_ONLY');
    server.use(
      http.get('/api/agents', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: [pendingAgent],
      })),
      http.get('/api/agents/1/versions', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [{ id: 5, versionNo: 5, status: 'ONLINE', roleName: 'Frontend Dev', roleCode: 'FRONTEND_DEV', gmtCreate: '2026-07-01' }],
      })),
      http.get('/api/agents/1/versions/5', () => HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: versionDetail(5) })),
      http.get('/api/agents/1/versions/6', () => HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: versionDetail(6) })),
    );

    renderPage();
    await userEvent.click(await screen.findByRole('button', { name: /查看变更/ }));

    expect(await screen.findByRole('button', { name: '通过并上线' })).toBeDisabled();
    expect(screen.getByRole('button', { name: /拒\s*绝/ })).toBeDisabled();
  });

  it('shows the load error with a retry when the pending version fails', async () => {
    let attempts = 0;
    server.use(
      http.get('/api/agents', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: [pendingAgent],
      })),
      http.get('/api/agents/1/versions', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [{ id: 5, versionNo: 5, status: 'ONLINE', roleName: 'Frontend Dev', roleCode: 'FRONTEND_DEV', gmtCreate: '2026-07-01' }],
      })),
      http.get('/api/agents/1/versions/5', () => HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: versionDetail(5) })),
      http.get('/api/agents/1/versions/6', () => {
        attempts += 1;
        if (attempts === 1) {
          return HttpResponse.json({ success: false, code: '14001', message: '版本读取失败', traceId: null, data: null }, { status: 500 });
        }
        return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: versionDetail(6) });
      }),
    );

    renderPage();
    await userEvent.click(await screen.findByRole('button', { name: /查看变更/ }));

    expect(await screen.findByText('版本加载失败')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /重\s*试/ }));

    expect(await screen.findByText(/提交人：蔡何/)).toBeInTheDocument();
    expect(attempts).toBe(2);
  });
});
