import { beforeEach, describe, it, expect, vi } from 'vitest';
import { render, screen, waitFor, act } from '@testing-library/react';
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
          <Route path="/workitems" element={<div>工单列表</div>} />
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

  it('does not render deprecated SDLC field nor the scheduled delivery module', () => {
    renderPage();

    expect(screen.queryByLabelText(/SDLC 流程/)).not.toBeInTheDocument();
    expect(screen.queryByText('定时交付（可选）')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('交付小队')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('执行 Agent')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('定时执行时间')).not.toBeInTheDocument();

    expect(screen.getByLabelText('类型')).toBeInTheDocument();
    expect(screen.getByLabelText('标题')).toBeInTheDocument();
    expect(screen.getByLabelText('描述')).toBeInTheDocument();
    expect(screen.getByLabelText('优先级')).toBeInTheDocument();
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

  it('never queries squads nor sends assignment fields when squads exist', async () => {
    let squadCalls = 0;
    let memberCalls = 0;
    let requestedBody: Record<string, unknown> | null = null;
    server.use(
      http.get('/api/squads', () => {
        squadCalls += 1;
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: { list: [{ id: 1, name: 'AW交付组', description: '', memberCount: 1, gmtCreate: '' }], total: 1, pageNum: 1, pageSize: 100 },
        });
      }),
      http.get('/api/squads/:squadId/members', () => {
        memberCalls += 1;
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: [{ agentId: 77, agentName: 'Agent-77', roleCode: 'AW_FS_DEV' }],
        });
      }),
      http.post('/api/workitems', async ({ request }) => {
        requestedBody = await request.json() as Record<string, unknown>;
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: {
            id: 99, workType: 'REQ', title: '普通工单', contentMd: '无定时配置', priority: 2,
            assigneeType: 'HUMAN', assigneeRef: 10000, version: 0,
            gmtCreate: '2026-09-08T10:00:00Z', gmtModified: '2026-09-08T10:00:00Z',
          },
        });
      }),
    );

    renderPage();

    await userEvent.type(screen.getByLabelText('标题'), '普通工单');
    await userEvent.type(screen.getByLabelText('描述'), '无定时配置');
    expect(screen.queryByText('AW交付组')).not.toBeInTheDocument();
    expect(screen.queryByPlaceholderText('留空则立即执行')).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: /创\s*建/ }));

    await waitFor(() => {
      expect(requestedBody).toEqual({
        workType: 'REQ',
        title: '普通工单',
        contentMd: '无定时配置',
        priority: 2,
      });
    });
    expect(await screen.findByText('工单详情')).toBeInTheDocument();
    expect(squadCalls).toBe(0);
    expect(memberCalls).toBe(0);
  });

  it('stays on the form and shows no success toast when create fails', async () => {
    let createCalls = 0;
    const success = vi.spyOn(message, 'success').mockImplementation(
      () => undefined as unknown as ReturnType<typeof message.success>,
    );
    server.use(
      http.post('/api/workitems', () => {
        createCalls += 1;
        return HttpResponse.json({ success: false, code: '500', message: '创建失败', traceId: null, data: null });
      }),
    );

    renderPage();

    await userEvent.type(screen.getByLabelText('标题'), '失败工单');
    await userEvent.type(screen.getByLabelText('描述'), '提交失败');
    await userEvent.click(screen.getByRole('button', { name: /创\s*建/ }));

    await waitFor(() => expect(createCalls).toBe(1));
    // mutateAsync 的拒绝沿微任务传播到 handleSubmit 的 catch，需显式 flush 后再断言
    await act(async () => {
      await Promise.resolve();
    });

    expect(success).not.toHaveBeenCalled();
    expect(screen.getByText('新建工单')).toBeInTheDocument();
    expect(screen.queryByText('工单详情')).not.toBeInTheDocument();
  });

  it('returns to the workitem list when cancel is clicked', async () => {
    renderPage();

    await userEvent.click(screen.getByRole('button', { name: /取\s*消/ }));

    expect(await screen.findByText('工单列表')).toBeInTheDocument();
  });
});
