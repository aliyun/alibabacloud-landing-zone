import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { message } from 'antd';
import { server } from '@/test/mocks/server';
import { useAuthStore } from '@/shared/auth/store';
import { ScheduledWorkitemsPanel } from './ScheduledWorkitemsPanel';

const FUTURE = new Date(Date.now() + 3_600_000).toISOString();
const PAST = new Date(Date.now() - 3_600_000).toISOString();

const PENDING_ITEM = {
  id: 1, title: '定时需求A', workType: 'REQ', statusName: '新建', priority: 2,
  assigneeType: 'AGENT', assigneeRef: 11, version: 1,
  gmtCreate: '2026-09-01', gmtModified: '2026-09-01',
  scheduledStartAt: FUTURE, scheduledStartTriggeredAt: null, scheduledPhase: 'PENDING',
};

const DONE_ITEM = {
  id: 2, title: '定时需求B', workType: 'REQ', statusName: '已发布', priority: 2,
  assigneeType: 'AGENT', assigneeRef: 12, version: 1,
  gmtCreate: '2026-09-02', gmtModified: '2026-09-02',
  scheduledStartAt: null, scheduledStartTriggeredAt: PAST, scheduledPhase: 'DONE',
};

function renderPanel() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/scheduled-tasks']}>
        <Routes>
          <Route path="/scheduled-tasks" element={<ScheduledWorkitemsPanel />} />
          <Route path="/workitems/:id" element={<div>工单详情页</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

function useWorkitemHandler(list: unknown[], requests: string[]) {
  server.use(
    http.get('/api/workitems', ({ request }) => {
      requests.push(new URL(request.url).searchParams.toString());
      return HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: { list, total: list.length, pageNum: 1, pageSize: 20 },
      });
    }),
  );
}

function useScheduledStartHandler(calls: { id: string; body: unknown }[]) {
  server.use(
    http.put('/api/workitems/:id/scheduled-start', async ({ request, params }) => {
      calls.push({ id: String(params.id), body: await request.json() });
      return HttpResponse.json({ success: true, code: '0', message: '', data: PENDING_ITEM });
    }),
  );
}

describe('ScheduledWorkitemsPanel', () => {
  const warnings: unknown[] = [];

  beforeEach(() => {
    useAuthStore.setState({ accessLevel: 'READ_WRITE' });
    warnings.length = 0;
    vi.spyOn(message, 'success').mockImplementation(() => undefined as never);
    vi.spyOn(message, 'warning').mockImplementation((content) => {
      warnings.push(content);
      return undefined as never;
    });
  });

  it('aggregates pending and triggered scheduled workitems with the derived phase and status', async () => {
    const requests: string[] = [];
    useWorkitemHandler([PENDING_ITEM, DONE_ITEM], requests);

    renderPanel();

    expect(await screen.findByText('定时需求A')).toBeInTheDocument();
    expect(screen.getByText('定时需求B')).toBeInTheDocument();
    expect(screen.getByText('当前所处阶段')).toBeInTheDocument();
    expect(screen.getByText('待触发')).toBeInTheDocument();
    expect(screen.getByText('已完成')).toBeInTheDocument();
    expect(screen.getByText('新建')).toBeInTheDocument();
    expect(screen.getByText('已发布')).toBeInTheDocument();
    expect(requests[0]).toContain('scheduledStart=ALL');
    expect(requests[0]).not.toContain('mineScope');
  });

  it('keeps all three actions visible and only enables them for pending schedules', async () => {
    const requests: string[] = [];
    useWorkitemHandler([PENDING_ITEM, DONE_ITEM], requests);

    renderPanel();
    await screen.findByText('定时需求A');

    const pendingRow = screen.getByText('定时需求A').closest('tr')!;
    expect(within(pendingRow).getByRole('button', { name: '修改时间 #1' })).toBeEnabled();
    expect(within(pendingRow).getByRole('button', { name: '立即启动 #1' })).toBeEnabled();
    expect(within(pendingRow).getByRole('button', { name: '取消定时 #1' })).toBeEnabled();

    const doneRow = screen.getByText('定时需求B').closest('tr')!;
    expect(within(doneRow).getByRole('button', { name: '修改时间 #2' })).toBeDisabled();
    expect(within(doneRow).getByRole('button', { name: '立即启动 #2' })).toBeDisabled();
    expect(within(doneRow).getByRole('button', { name: '取消定时 #2' })).toBeDisabled();
    expect(within(doneRow).getByRole('button', { name: '查看 #2' })).toBeEnabled();
  });

  it('explains why the actions are unavailable on an already finished workitem', async () => {
    const user = userEvent.setup();
    const requests: string[] = [];
    useWorkitemHandler([DONE_ITEM], requests);

    renderPanel();
    await screen.findByText('定时需求B');

    await user.hover(screen.getByRole('button', { name: '取消定时 #2' }).parentElement!);

    expect(await screen.findByText('工单已完成，无法再调整定时')).toBeInTheDocument();
  });

  it('narrows to my own workitems and searches by title', async () => {
    const user = userEvent.setup();
    const requests: string[] = [];
    useWorkitemHandler([PENDING_ITEM], requests);

    renderPanel();
    await screen.findByText('定时需求A');

    await user.click(screen.getByText('我创建的'));
    await waitFor(() => expect(requests[requests.length - 1]).toContain('mineScope=CREATED'));

    await user.type(screen.getByLabelText('工单标题筛选'), '定时需求{Enter}');
    await waitFor(() => {
      const last = requests[requests.length - 1];
      expect(last).toContain(`keyword=${encodeURIComponent('定时需求')}`);
      expect(last).toContain('mineScope=CREATED');
      expect(last).toContain('page=1');
    });
  });

  it('cancels the schedule through the existing scheduled-start endpoint', async () => {
    const user = userEvent.setup();
    const requests: string[] = [];
    const calls: { id: string; body: unknown }[] = [];
    useWorkitemHandler([PENDING_ITEM], requests);
    useScheduledStartHandler(calls);

    renderPanel();
    await user.click(await screen.findByRole('button', { name: '取消定时 #1' }));

    await waitFor(() => expect(calls).toHaveLength(1));
    expect(calls[0].id).toBe('1');
    expect(calls[0].body).toEqual({ scheduledStartAt: null });
  });

  it('starts the workitem immediately through the existing scheduled-start endpoint', async () => {
    const user = userEvent.setup();
    const requests: string[] = [];
    const calls: { id: string; body: unknown }[] = [];
    useWorkitemHandler([PENDING_ITEM], requests);
    useScheduledStartHandler(calls);

    renderPanel();
    await user.click(await screen.findByRole('button', { name: '立即启动 #1' }));

    await waitFor(() => expect(calls).toHaveLength(1));
    expect(calls[0].body).toEqual({ executeNow: true });
  });

  it('reschedules to the already planned future time without touching the date picker', async () => {
    const user = userEvent.setup();
    const requests: string[] = [];
    const calls: { id: string; body: unknown }[] = [];
    useWorkitemHandler([PENDING_ITEM], requests);
    useScheduledStartHandler(calls);

    renderPanel();
    await user.click(await screen.findByRole('button', { name: '修改时间 #1' }));
    await user.click(await screen.findByRole('button', { name: /保\s*存/ }));

    await waitFor(() => expect(calls).toHaveLength(1));
    const body = calls[0].body as { scheduledStartAt?: string };
    expect(typeof body.scheduledStartAt).toBe('string');
    expect(new Date(body.scheduledStartAt!).getTime()).toBeGreaterThan(Date.now());
  });

  it('warns instead of calling the api when the planned time is already in the past', async () => {
    const user = userEvent.setup();
    const requests: string[] = [];
    const calls: { id: string; body: unknown }[] = [];
    useWorkitemHandler([{ ...PENDING_ITEM, scheduledStartAt: PAST }], requests);
    useScheduledStartHandler(calls);

    renderPanel();
    await user.click(await screen.findByRole('button', { name: '修改时间 #1' }));
    await user.click(await screen.findByRole('button', { name: /保\s*存/ }));

    await waitFor(() => expect(warnings).toEqual(['计划执行时间必须是将来的时间点']));
    expect(calls).toHaveLength(0);
  });

  it('opens the existing workitem detail page from 查看', async () => {
    const user = userEvent.setup();
    const requests: string[] = [];
    useWorkitemHandler([PENDING_ITEM], requests);

    renderPanel();
    await user.click(await screen.findByRole('button', { name: '查看 #1' }));

    expect(await screen.findByText('工单详情页')).toBeInTheDocument();
  });

  it('refuses to cancel the schedule for read-only members without calling the api', async () => {
    const user = userEvent.setup();
    useAuthStore.setState({ accessLevel: 'READ_ONLY' });
    const requests: string[] = [];
    const calls: { id: string; body: unknown }[] = [];
    useWorkitemHandler([PENDING_ITEM], requests);
    useScheduledStartHandler(calls);
    const errorSpy = vi.spyOn(message, 'error').mockImplementation(() => undefined as never);

    renderPanel();
    await user.click(await screen.findByRole('button', { name: '取消定时 #1' }));

    await waitFor(() => expect(errorSpy).toHaveBeenCalledWith('当前为只读权限，取消工单定时执行需要读写权限'));
    expect(calls).toHaveLength(0);
    errorSpy.mockRestore();
  });
});
