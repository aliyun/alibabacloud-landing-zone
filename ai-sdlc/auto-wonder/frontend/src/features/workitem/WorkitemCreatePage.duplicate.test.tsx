import { beforeEach, describe, it, expect, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { WorkitemCreatePage } from './WorkitemCreatePage';
import { useAuthStore } from '@/shared/auth/store';

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/workitems/new']}>
        <Routes>
          <Route path="/workitems/new" element={<WorkitemCreatePage />} />
          <Route path="/workitems/:id" element={<div>工单详情</div>} />
          <Route path="/workitems" element={<div>工单列表</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('WorkitemCreatePage duplicate checks', () => {
  beforeEach(() => {
    useAuthStore.getState().clear();
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_WRITE');
    vi.restoreAllMocks();
  });

  it('reminds about an identical title after the input loses focus', async () => {
    server.use(
      http.post('/api/workitems/duplicate-checks', () => HttpResponse.json({
        success: true,
        code: '0',
        message: '',
        traceId: 'trace-dup',
        data: {
          checkId: null,
          title: '同名工单',
          exactMatches: [{
            id: 77,
            title: '同名工单',
            statusName: '新建',
            statusCategory: 'NEW',
            url: 'https://aw.example.com/workitems/77',
            matchType: 'EXACT_TITLE',
          }],
          exactTotal: 1,
          exactTruncated: false,
          intentEnabled: false,
          intentStatus: null,
          intentMatches: [],
          intentPartial: false,
        },
      })),
    );
    renderPage();

    await userEvent.type(screen.getByLabelText('标题'), '同名工单');
    await userEvent.tab();

    await waitFor(() => expect(screen.getByText('发现重复工单')).toBeInTheDocument());
    const link = screen.getByRole('link', { name: '在新标签页查看' });
    expect(link).toHaveAttribute('target', '_blank');
    // 提醒不改变表单内容。
    expect(screen.getByLabelText('标题')).toHaveValue('同名工单');
  });

  it('keeps creating without waiting when nothing is duplicated', async () => {
    let posted = false;
    server.use(
      http.post('/api/workitems', async () => {
        posted = true;
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: { id: 88, workType: 'REQ', title: '独立工单', contentMd: 'body', priority: 2 },
        });
      }),
    );
    renderPage();

    await userEvent.type(screen.getByLabelText('标题'), '独立工单');
    await userEvent.type(screen.getByLabelText('描述'), 'body');
    await userEvent.click(screen.getByRole('button', { name: /创\s*建/ }));

    await waitFor(() => expect(posted).toBe(true));
    expect(screen.queryByText('发现重复工单')).not.toBeInTheDocument();
  });
});
