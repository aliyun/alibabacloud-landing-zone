import { beforeEach, describe, it, expect, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { message } from 'antd';
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
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('WorkitemCreatePage', () => {
  beforeEach(() => {
    useAuthStore.getState().clear();
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_WRITE');
    vi.restoreAllMocks();
  });

  it('does not render deprecated SDLC and first agent fields', () => {
    renderPage();

    expect(screen.queryByLabelText(/SDLC 流程/)).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/首步执行 Agent/)).not.toBeInTheDocument();
  });

  it('creates a workitem without auto assignment payload', async () => {
    let requestedBody: Record<string, unknown> | null = null;
    server.use(
      http.post('/api/workitems', async ({ request }) => {
        requestedBody = await request.json() as Record<string, unknown>;
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: {
            id: 88,
            workType: 'REQ',
            title: requestedBody.title,
            contentMd: requestedBody.contentMd,
            priority: requestedBody.priority,
            assigneeType: 'HUMAN',
            assigneeRef: 10000,
            assigneeName: '真人',
            assigneeDisplayName: '真人(10000)',
            version: 0,
            gmtCreate: '2026-07-19T10:00:00Z',
            gmtModified: '2026-07-19T10:00:00Z',
          },
        });
      }),
    );

    renderPage();

    await userEvent.type(screen.getByLabelText('标题'), '删除无效字段');
    await userEvent.type(screen.getByLabelText('描述'), '只创建工单');
    await userEvent.click(screen.getByRole('button', { name: /创\s*建/ }));

    await waitFor(() => {
      expect(requestedBody).toEqual({
        workType: 'REQ',
        title: '删除无效字段',
        contentMd: '只创建工单',
        priority: 2,
      });
    });
    expect(await screen.findByText('工单详情')).toBeInTheDocument();
  });

  it('does not create from the direct route for a read-only member', async () => {
    let createCalls = 0;
    const error = vi.spyOn(message, 'error').mockImplementation(
      () => undefined as unknown as ReturnType<typeof message.error>,
    );
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_ONLY');
    server.use(
      http.post('/api/workitems', () => {
        createCalls += 1;
        return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: {} });
      }),
    );
    renderPage();

    await userEvent.type(screen.getByLabelText('标题'), '只读工单');
    await userEvent.type(screen.getByLabelText('描述'), '不能提交');
    await userEvent.click(screen.getByRole('button', { name: /创\s*建/ }));

    expect(error).toHaveBeenCalledWith('当前为只读权限，创建工单需要读写权限');
    expect(createCalls).toBe(0);
    expect(screen.getByText('新建工单')).toBeInTheDocument();
  });
});
