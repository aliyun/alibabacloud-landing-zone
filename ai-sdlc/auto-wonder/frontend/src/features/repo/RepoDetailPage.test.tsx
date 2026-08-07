import { describe, it, expect, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { RepoDetailPage } from './RepoDetailPage';
import { useAuthStore } from '@/shared/auth/store';
import { message } from 'antd';

vi.mock('@/shared/realtime/useRealtime', () => ({
  useRealtime: () => undefined,
}));

function renderPage(initialEntry = '/repos/1', accessLevel: 'READ_ONLY' | 'READ_WRITE' = 'READ_WRITE') {
  useAuthStore.setState({ accessLevel });
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[initialEntry]}>
        <Routes><Route path="/repos/:id" element={<RepoDetailPage />} /></Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

const mockRepo = {
  id: 1, name: 'auto-wonder', url: 'https://github.com/auto-wonder',
  defaultBranch: 'main', description: '主仓库',
  scanStatus: 'CONCLUDED', version: 1, gmtCreate: '2026-07-01',
};

const mockConclusion = {
  id: 1, repoId: 1, purpose: '平台主仓库', keyBusiness: '工单系统',
  upstreams: null, downstreams: 'frontend', summaryMd: '# Summary',
  aiSessionId: null, version: 1, gmtCreate: '2026-07-01',
};

describe('RepoDetailPage', () => {
  it('renders only info and relation tabs with the scan entry hidden', async () => {
    server.use(
      http.get('/api/repos/1', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: mockRepo,
      })),
      http.get('/api/repos/1/conclusion', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: mockConclusion,
      })),
      http.get('/api/repos/relations', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: [],
      })),
      http.get('/api/repos', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: [mockRepo],
      })),
    );
    renderPage();
    expect(await screen.findByRole('tab', { name: '基础信息' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: '仓库关系' })).toBeInTheDocument();
    expect(screen.getAllByRole('tab')).toHaveLength(2);
    expect(screen.queryByRole('tab', { name: '扫描结论' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /发起扫描/ })).not.toBeInTheDocument();
    expect(screen.queryByText(/发起扫描/)).not.toBeInTheDocument();
    expect(screen.queryByText('扫描状态')).not.toBeInTheDocument();
    expect(screen.queryByText('上次扫描时间')).not.toBeInTheDocument();
    expect(screen.queryByText('认证方式')).not.toBeInTheDocument();
  });

  it('exposes no reachable entry to start a repo scan session', async () => {
    let legacyScanCalled = false;
    let aiSessionCreated = false;

    server.use(
      http.get('/api/repos/1', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: mockRepo,
      })),
      http.get('/api/repos/1/conclusion', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: mockConclusion,
      })),
      http.get('/api/repos/relations', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: [],
      })),
      http.get('/api/repos', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: [mockRepo],
      })),
      http.post('/api/repos/1/scan', () => {
        legacyScanCalled = true;
        return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: null });
      }),
      http.post('/api/ai/sessions', () => {
        aiSessionCreated = true;
        return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: 100 });
      }),
    );

    renderPage();
    await screen.findByRole('tab', { name: '基础信息' });

    expect(screen.queryByRole('button', { name: /发起扫描/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('dialog', { name: /AI 仓库扫描/ })).not.toBeInTheDocument();
    await waitFor(() => {
      expect(aiSessionCreated).toBe(false);
      expect(legacyScanCalled).toBe(false);
    });
  });

  it('keeps snowflake repo id precise when loading detail', async () => {
    const repoId = '334225079997042688';
    const repo = { ...mockRepo, id: repoId };
    let requestedDetailPath = '';

    server.use(
      http.get(`/api/repos/${repoId}`, ({ request }) => {
        requestedDetailPath = new URL(request.url).pathname;
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null, data: repo,
        });
      }),
      http.get(`/api/repos/${repoId}/conclusion`, () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: mockConclusion,
      })),
      http.get('/api/repos/relations', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: [],
      })),
      http.get('/api/repos', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: [repo],
      })),
    );

    renderPage(`/repos/${repoId}`);

    expect(await screen.findByRole('tab', { name: '基础信息' })).toBeInTheDocument();
    expect(requestedDetailPath).toBe(`/api/repos/${repoId}`);
  });

  it('shows numeric relation ids on string route repo id', async () => {
    const repo = { ...mockRepo, id: 10000 };
    const peerRepo = { ...mockRepo, id: 10001, name: 'client-runtime' };

    server.use(
      http.get('/api/repos/10000', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: repo,
      })),
      http.get('/api/repos/10000/conclusion', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: null,
      })),
      http.get('/api/repos/relations', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [{
          id: 10000,
          fromRepoId: 10001,
          toRepoId: 10000,
          relationType: 'SERVICE',
          description: 'auto-wonder是整个SDLC的服务端平台',
          aiSessionId: null,
          gmtCreate: '2026-07-11T08:27:22.730+00:00',
        }],
      })),
      http.get('/api/repos', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: [repo, peerRepo],
      })),
    );

    renderPage('/repos/10000');
    await userEvent.click(await screen.findByRole('tab', { name: '仓库关系' }));

    expect(await screen.findByText(/client-runtime/)).toBeInTheDocument();
    expect(screen.getByText('服务调用')).toBeInTheDocument();
  });

  it('hides the scan entry for read-only members without raising a permission error', async () => {
    const errorSpy = vi.spyOn(message, 'error').mockImplementation(() => undefined as never);
    let sessionRequests = 0;
    server.use(
      http.get('/api/repos/1', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: mockRepo,
      })),
      http.get('/api/repos/1/conclusion', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: mockConclusion,
      })),
      http.get('/api/repos/relations', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: [],
      })),
      http.get('/api/repos', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: [mockRepo],
      })),
      http.post('/api/ai/sessions', () => {
        sessionRequests += 1;
        return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: 100 });
      }),
    );

    renderPage('/repos/1', 'READ_ONLY');
    await screen.findByRole('tab', { name: '基础信息' });

    expect(screen.queryByRole('button', { name: /发起扫描/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('dialog', { name: /AI 仓库扫描/ })).not.toBeInTheDocument();
    expect(errorSpy).not.toHaveBeenCalled();
    expect(sessionRequests).toBe(0);
    errorSpy.mockRestore();
  });

  it('deletes the repo from detail page with confirmation', async () => {
    const user = userEvent.setup();
    let deleteCalled = false;
    const successSpy = vi.spyOn(message, 'success').mockImplementation(() => undefined as never);
    server.use(
      http.get('/api/repos/1', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: mockRepo,
      })),
      http.get('/api/repos/1/conclusion', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: null,
      })),
      http.get('/api/repos/relations', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: [],
      })),
      http.get('/api/repos', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: [mockRepo],
      })),
      http.delete('/api/repos/1', () => {
        deleteCalled = true;
        return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: null });
      }),
    );

    renderPage('/repos/1');
    await screen.findByRole('tab', { name: '基础信息' });

    await user.click(screen.getByRole('button', { name: /删除仓库/ }));
    expect(await screen.findByText(/确认删除仓库/)).toBeInTheDocument();
    const confirmBtn = document.querySelector('.ant-popconfirm .ant-popconfirm-buttons button:last-child') as HTMLButtonElement;
    expect(confirmBtn).toBeTruthy();
    await user.click(confirmBtn);

    await waitFor(() => expect(deleteCalled).toBe(true));
    expect(successSpy).toHaveBeenCalledWith('仓库已删除');
    successSpy.mockRestore();
  });
});
