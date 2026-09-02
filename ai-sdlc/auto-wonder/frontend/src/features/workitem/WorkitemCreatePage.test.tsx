import { beforeEach, describe, it, expect, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import dayjs from 'dayjs';
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
    server.use(
      http.get('/api/squads', () =>
        HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: { list: [], total: 0, pageNum: 1, pageSize: 100 },
        }),
      ),
    );
  });

  it('does not render deprecated SDLC field but offers optional scheduled delivery', () => {
    renderPage();

    expect(screen.queryByLabelText(/SDLC 流程/)).not.toBeInTheDocument();
    expect(screen.getByText('定时交付（可选）')).toBeInTheDocument();
    expect(screen.getByLabelText('交付小队')).toBeInTheDocument();
    expect(screen.getByLabelText('执行 Agent')).toBeInTheDocument();
    expect(screen.getByLabelText('定时执行时间')).toBeInTheDocument();
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

  it('creates a scheduled agent workitem when squad, agent and time are selected', async () => {
    let requestedBody: Record<string, unknown> | null = null;
    server.use(
      http.get('/api/squads', () =>
        HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: { list: [{ id: 1, name: 'AW交付组', description: '', memberCount: 1, gmtCreate: '' }], total: 1, pageNum: 1, pageSize: 100 },
        }),
      ),
      http.get('/api/squads/:squadId/members', () =>
        HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: [{ agentId: 77, agentName: 'Agent-77', roleCode: 'AW_FS_DEV' }],
        }),
      ),
      http.post('/api/workitems', async ({ request }) => {
        requestedBody = await request.json() as Record<string, unknown>;
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: {
            id: 99, workType: 'REQ', title: '定时工单', contentMd: '按计划执行', priority: 2,
            assigneeType: 'AGENT', assigneeRef: 77, version: 0,
            gmtCreate: '2026-08-26T10:00:00Z', gmtModified: '2026-08-26T10:00:00Z',
          },
        });
      }),
    );

    renderPage();

    await userEvent.type(screen.getByLabelText('标题'), '定时工单');
    await userEvent.type(screen.getByLabelText('描述'), '按计划执行');

    await userEvent.click(screen.getByLabelText('交付小队'));
    await userEvent.click(await screen.findByText('AW交付组'));
    await userEvent.click(await screen.findByLabelText('执行 Agent'));
    await userEvent.click(await screen.findByText('Agent-77 (AW_FS_DEV)'));

    // The picker disables past instants, so derive the input from the current
    // clock instead of a literal date that silently expires.
    const scheduledAt = dayjs().add(1, 'day').hour(10).minute(0).second(0).millisecond(0);
    await userEvent.type(screen.getByPlaceholderText('留空则立即执行'), scheduledAt.format('YYYY-MM-DD HH:mm:ss'));
    await userEvent.tab();

    await userEvent.click(screen.getByRole('button', { name: /创\s*建/ }));

    await waitFor(() => {
      expect(requestedBody).toMatchObject({
        workType: 'REQ',
        title: '定时工单',
        contentMd: '按计划执行',
        priority: 2,
        assigneeType: 'AGENT',
        assigneeRef: 77,
        squadId: 1,
      });
    });
    expect(new Date(requestedBody!.scheduledStartAt as string).toISOString())
      .toBe(scheduledAt.toISOString());
  });
});
