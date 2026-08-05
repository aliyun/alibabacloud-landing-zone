import { beforeEach, describe, it, expect, vi } from 'vitest';
import { render, screen, within, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { message } from 'antd';
import { AgentReviewPage } from './AgentReviewPage';
import { useAuthStore } from '@/shared/auth/store';

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter><AgentReviewPage /></MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('AgentReviewPage', () => {
  beforeEach(() => {
    useAuthStore.getState().clear();
    useAuthStore.getState().setCurrentOrg({ id: 1, name: 'O', description: '' }, 'READ_WRITE');
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
});
