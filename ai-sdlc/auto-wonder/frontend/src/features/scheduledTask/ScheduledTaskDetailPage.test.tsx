import { afterEach as resetAfterEach } from 'vitest';
import { act } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { createAppRoutes } from '@/app/router';
import { i18n } from '@/shared/i18n';
import { describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { message } from 'antd';
import { server } from '@/test/mocks/server';
import { useAuthStore } from '@/shared/auth/store';
import { ScheduledTaskDetailPage } from './ScheduledTaskDetailPage';

describe('ScheduledTaskDetailPage', () => {
  it('shows task policy and paged run history', async () => {
    const user = userEvent.setup();
    const heightSpy = vi.spyOn(HTMLElement.prototype, 'offsetHeight', 'get').mockImplementation(function (this: HTMLElement) {
      return this.querySelectorAll('tbody tr').length * 60 + 50;
    });
    let finishSecondPage!: () => void;
    const secondPage = new Promise<void>((resolve) => { finishSecondPage = resolve; });
    server.use(
      http.get('/api/scheduled-tasks/901', () => HttpResponse.json({ success: true, code: '0', message: '', data: { id: 901, name: '主干夜间回归', instructionMd: '执行回归', squadId: 2, initialAgentId: 5, status: 'ACTIVE', scheduleType: 'CRON', cronExpression: '0 0 2 * * *', timezone: 'Asia/Shanghai', overlapPolicy: 'SKIP', misfirePolicy: 'FIRE_LATEST', sessionMode: 'ISOLATED', nextFireAt: '2026-08-12T18:00:00Z', version: 1 } })),
      http.get('/api/scheduled-tasks/901/runs', async ({ request }) => {
        const offset = Number(new URL(request.url).searchParams.get('offset'));
        if (offset) await secondPage;
        return HttpResponse.json({ success: true, code: '0', message: '', data: Array.from({ length: offset ? 1 : 10 }, (_, index) => ({ id: 10482 + offset + index, scheduledTaskId: 901, status: 'SUCCEEDED', triggerType: 'SCHEDULED' })) });
      }),
      http.get('/api/scheduled-tasks/901/documents', () => HttpResponse.json({ success: true, code: '0', message: '', data: [] })),
      http.get('/api/scheduled-tasks/901/health', () => HttpResponse.json({ success: true, code: '0', message: '', data: { success30d: 8, completed30d: 10 } })),
      http.get('/api/squads', () => HttpResponse.json({ success: true, code: '0', message: '', data: { list: [{ id: 2, name: '功能增量分析小队' }], total: 1, pageNum: 1, pageSize: 100 } })),
      http.get('/api/agents', () => HttpResponse.json({ success: true, code: '0', message: '', data: [{ id: 5, name: '功能增量分析员' }] })),
    );
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<QueryClientProvider client={client}><MemoryRouter initialEntries={['/scheduled-tasks/901']}><Routes><Route path="/scheduled-tasks/:id" element={<ScheduledTaskDetailPage />} /></Routes></MemoryRouter></QueryClientProvider>);
    expect(await screen.findByText('主干夜间回归')).toBeInTheDocument();
    expect(screen.getByText('运行历史')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Run #10482' })).toHaveAttribute('href', '/scheduled-task-runs/10482');
    expect(await screen.findByText('功能增量分析小队 (2)')).toBeInTheDocument();
    expect(screen.getByText('功能增量分析员 (5)')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '返回定时任务' })).toBeInTheDocument();
    expect(screen.getAllByText('成功')).toHaveLength(10);
    const pagination = document.querySelector('.ant-table-pagination') as HTMLElement;
    expect(pagination).toHaveClass('ant-table-pagination-right');
    const history = pagination.parentElement as HTMLElement;
    expect(history).toHaveStyle({ minHeight: '650px' });
    await user.click(within(pagination).getByTitle('2'));
    expect(screen.getByRole('link', { name: 'Run #10482' })).toBeInTheDocument();
    finishSecondPage();
    expect(await screen.findByRole('link', { name: 'Run #10492' })).toBeInTheDocument();
    expect(history).toHaveStyle({ minHeight: '650px' });
    heightSpy.mockRestore();
  });

  it('falls back to raw ids when squad or agent names are unavailable', async () => {
    server.use(
      http.get('/api/scheduled-tasks/901', () => HttpResponse.json({ success: true, code: '0', message: '', data: { id: 901, name: '主干夜间回归', instructionMd: '执行回归', squadId: 2, initialAgentId: 5, status: 'ACTIVE', scheduleType: 'CRON', cronExpression: '0 0 2 * * *', timezone: 'Asia/Shanghai', overlapPolicy: 'SKIP', misfirePolicy: 'FIRE_LATEST', sessionMode: 'ISOLATED', nextFireAt: '2026-08-12T18:00:00Z', version: 1 } })),
      http.get('/api/scheduled-tasks/901/runs', () => HttpResponse.json({ success: true, code: '0', message: '', data: [] })),
      http.get('/api/scheduled-tasks/901/documents', () => HttpResponse.json({ success: true, code: '0', message: '', data: [] })),
      http.get('/api/scheduled-tasks/901/health', () => HttpResponse.json({ success: true, code: '0', message: '', data: { success30d: 0, completed30d: 0 } })),
      http.get('/api/squads', () => HttpResponse.json({ success: true, code: '0', message: '', data: { list: [], total: 0, pageNum: 1, pageSize: 100 } })),
      http.get('/api/agents', () => HttpResponse.json({ success: true, code: '0', message: '', data: [] })),
    );
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<QueryClientProvider client={client}><MemoryRouter initialEntries={['/scheduled-tasks/901']}><Routes><Route path="/scheduled-tasks/:id" element={<ScheduledTaskDetailPage />} /></Routes></MemoryRouter></QueryClientProvider>);
    expect(await screen.findByText('小队 #2')).toBeInTheDocument();
    expect(screen.getByText('数字员工 #5')).toBeInTheDocument();
  });

  it('deletes the task after confirmation and returns to the list', async () => {
    useAuthStore.setState({ accessLevel: 'READ_WRITE' });
    const user = userEvent.setup();
    let deletedUrl: string | null = null;
    const successSpy = vi.spyOn(message, 'success').mockImplementation(() => undefined as never);
    server.use(
      http.get('/api/scheduled-tasks/901', () => HttpResponse.json({ success: true, code: '0', message: '', data: { id: 901, name: '主干夜间回归', instructionMd: '执行回归', squadId: 2, initialAgentId: 5, status: 'PAUSED', scheduleType: 'CRON', cronExpression: '0 0 2 * * *', timezone: 'Asia/Shanghai', overlapPolicy: 'SKIP', misfirePolicy: 'FIRE_LATEST', sessionMode: 'ISOLATED', nextFireAt: '2026-08-12T18:00:00Z', version: 1 } })),
      http.get('/api/scheduled-tasks/901/runs', () => HttpResponse.json({ success: true, code: '0', message: '', data: [] })),
      http.get('/api/scheduled-tasks/901/documents', () => HttpResponse.json({ success: true, code: '0', message: '', data: [] })),
      http.get('/api/scheduled-tasks/901/health', () => HttpResponse.json({ success: true, code: '0', message: '', data: { success30d: 0, completed30d: 0 } })),
      http.get('/api/squads', () => HttpResponse.json({ success: true, code: '0', message: '', data: { list: [], total: 0, pageNum: 1, pageSize: 100 } })),
      http.get('/api/agents', () => HttpResponse.json({ success: true, code: '0', message: '', data: [] })),
      http.delete('/api/scheduled-tasks/901', ({ request }) => {
        deletedUrl = request.url;
        return HttpResponse.json({ success: true, code: '0', message: '', data: null });
      }),
    );
    const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
    render(<QueryClientProvider client={client}><MemoryRouter initialEntries={['/scheduled-tasks/901']}><Routes><Route path="/scheduled-tasks/:id" element={<ScheduledTaskDetailPage />} /><Route path="/scheduled-tasks" element={<div>定时任务列表</div>} /></Routes></MemoryRouter></QueryClientProvider>);

    await user.click(await screen.findByRole('button', { name: '删除' }));
    const titles = await screen.findAllByText('删除后不再调度，且不可恢复');
    const popup = titles[titles.length - 1].closest('.ant-popconfirm') ?? document;
    const buttons = popup.querySelectorAll('.ant-popconfirm-buttons button');
    expect(buttons.length).toBeGreaterThan(0);
    await user.click(buttons[buttons.length - 1] as HTMLButtonElement);

    await waitFor(() => expect(deletedUrl).toContain('version=1'));
    expect(successSpy).toHaveBeenCalledWith('定时任务已删除');
    expect(await screen.findByText('定时任务列表')).toBeInTheDocument();
    successSpy.mockRestore();
  });

  it('deletes a task attachment after confirmation and refreshes the document list', async () => {
    useAuthStore.setState({ accessLevel: 'READ_WRITE' });
    const user = userEvent.setup();
    let deletedUrl: string | null = null;
    let documentList = [{ id: 77, workitemId: 0, dispatchId: null, name: '需求说明.md', type: 'MARKDOWN', size: 128, gmtCreate: '2026-08-12T00:00:00Z' }];
    const successSpy = vi.spyOn(message, 'success').mockImplementation(() => undefined as never);
    server.use(
      http.get('/api/scheduled-tasks/901', () => HttpResponse.json({ success: true, code: '0', message: '', data: { id: 901, name: '主干夜间回归', instructionMd: '执行回归', squadId: 2, initialAgentId: 5, status: 'ACTIVE', scheduleType: 'CRON', cronExpression: '0 0 2 * * *', timezone: 'Asia/Shanghai', overlapPolicy: 'SKIP', misfirePolicy: 'FIRE_LATEST', sessionMode: 'ISOLATED', nextFireAt: '2026-08-12T18:00:00Z', version: 1 } })),
      http.get('/api/scheduled-tasks/901/runs', () => HttpResponse.json({ success: true, code: '0', message: '', data: [] })),
      http.get('/api/scheduled-tasks/901/documents', () => HttpResponse.json({ success: true, code: '0', message: '', data: documentList })),
      http.get('/api/scheduled-tasks/901/health', () => HttpResponse.json({ success: true, code: '0', message: '', data: { success30d: 0, completed30d: 0 } })),
      http.get('/api/squads', () => HttpResponse.json({ success: true, code: '0', message: '', data: { list: [], total: 0, pageNum: 1, pageSize: 100 } })),
      http.get('/api/agents', () => HttpResponse.json({ success: true, code: '0', message: '', data: [] })),
      http.delete('/api/scheduled-tasks/901/documents/77', ({ request }) => {
        deletedUrl = request.url;
        documentList = [];
        return HttpResponse.json({ success: true, code: '0', message: '', data: null });
      }),
    );
    const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
    render(<QueryClientProvider client={client}><MemoryRouter initialEntries={['/scheduled-tasks/901']}><Routes><Route path="/scheduled-tasks/:id" element={<ScheduledTaskDetailPage />} /></Routes></MemoryRouter></QueryClientProvider>);

    const artifactName = await screen.findByText('需求说明.md');
    const artifactCard = artifactName.closest('.ant-card') as HTMLElement;
    await user.click(within(artifactCard).getByRole('button', { name: '删除' }));
    const titles = await screen.findAllByText('删除后不可恢复，同名文件可重新上传');
    const popup = titles[titles.length - 1].closest('.ant-popconfirm') ?? document;
    const buttons = popup.querySelectorAll('.ant-popconfirm-buttons button');
    expect(buttons.length).toBeGreaterThan(0);
    await user.click(buttons[buttons.length - 1] as HTMLButtonElement);

    await waitFor(() => expect(deletedUrl).toContain('/api/scheduled-tasks/901/documents/77'));
    expect(successSpy).toHaveBeenCalledWith('附件已删除');
    expect(await screen.findByText('暂无产物')).toBeInTheDocument();
    successSpy.mockRestore();
  });

  it('blocks attachment deletion without READ_WRITE access', async () => {
    useAuthStore.setState({ accessLevel: 'READ_ONLY' });
    const user = userEvent.setup();
    let deleteCalled = false;
    const errorSpy = vi.spyOn(message, 'error').mockImplementation(() => undefined as never);
    server.use(
      http.get('/api/scheduled-tasks/901', () => HttpResponse.json({ success: true, code: '0', message: '', data: { id: 901, name: '主干夜间回归', instructionMd: '执行回归', squadId: 2, initialAgentId: 5, status: 'ACTIVE', scheduleType: 'CRON', cronExpression: '0 0 2 * * *', timezone: 'Asia/Shanghai', overlapPolicy: 'SKIP', misfirePolicy: 'FIRE_LATEST', sessionMode: 'ISOLATED', nextFireAt: '2026-08-12T18:00:00Z', version: 1 } })),
      http.get('/api/scheduled-tasks/901/runs', () => HttpResponse.json({ success: true, code: '0', message: '', data: [] })),
      http.get('/api/scheduled-tasks/901/documents', () => HttpResponse.json({ success: true, code: '0', message: '', data: [{ id: 77, workitemId: 0, dispatchId: null, name: '需求说明.md', type: 'MARKDOWN', size: 128, gmtCreate: '2026-08-12T00:00:00Z' }] })),
      http.get('/api/scheduled-tasks/901/health', () => HttpResponse.json({ success: true, code: '0', message: '', data: { success30d: 0, completed30d: 0 } })),
      http.get('/api/squads', () => HttpResponse.json({ success: true, code: '0', message: '', data: { list: [], total: 0, pageNum: 1, pageSize: 100 } })),
      http.get('/api/agents', () => HttpResponse.json({ success: true, code: '0', message: '', data: [] })),
      http.delete('/api/scheduled-tasks/901/documents/77', () => {
        deleteCalled = true;
        return HttpResponse.json({ success: true, code: '0', message: '', data: null });
      }),
    );
    const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
    render(<QueryClientProvider client={client}><MemoryRouter initialEntries={['/scheduled-tasks/901']}><Routes><Route path="/scheduled-tasks/:id" element={<ScheduledTaskDetailPage />} /></Routes></MemoryRouter></QueryClientProvider>);

    const artifactName = await screen.findByText('需求说明.md');
    const artifactCard = artifactName.closest('.ant-card') as HTMLElement;
    await user.click(within(artifactCard).getByRole('button', { name: '删除' }));
    const titles = await screen.findAllByText('删除后不可恢复，同名文件可重新上传');
    const popup = titles[titles.length - 1].closest('.ant-popconfirm') ?? document;
    const buttons = popup.querySelectorAll('.ant-popconfirm-buttons button');
    await user.click(buttons[buttons.length - 1] as HTMLButtonElement);

    await waitFor(() => expect(errorSpy).toHaveBeenCalledWith(expect.stringContaining('删除定时任务附件')));
    expect(deleteCalled).toBe(false);
    expect(screen.getByText('需求说明.md')).toBeInTheDocument();
    errorSpy.mockRestore();
  });
});

it('switches the cached detail policy, history and open confirmation while preserving authored content', async () => {
  useAuthStore.setState({ accessLevel: 'READ_WRITE' });
  server.use(
    http.get('/api/scheduled-tasks/901', () => HttpResponse.json({ success: true, code: '0', data: { id: 901, name: '<b>任务名</b>', instructionMd: '## 原始任务指令', squadId: 2, initialAgentId: 5, status: 'ACTIVE', scheduleType: 'CRON', cronExpression: '0 0 2 * * *', timezone: 'Asia/Shanghai', sessionMode: 'ISOLATED', overlapPolicy: 'SKIP', misfirePolicy: 'FIRE_LATEST', nextFireAt: '2026-08-12T18:00:00Z', version: 1 } })),
    http.get('/api/scheduled-tasks/901/runs', () => HttpResponse.json({ success: true, code: '0', data: [{ id: 7, status: 'TIMED_OUT', triggerType: 'MISFIRE' }, { id: 8, status: 'CUSTOM', triggerType: 'toString' }] })),
    http.get('/api/scheduled-tasks/901/health', () => HttpResponse.json({ success: true, code: '0', data: { success30d: 1, completed30d: 2 } })),
    http.get('/api/scheduled-tasks/901/documents', () => HttpResponse.json({ success: true, code: '0', data: [] })),
  );
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const route = createAppRoutes().flatMap(route => route.children ?? []).find(route => route.path === '/scheduled-tasks/:id')!;
  const router = createMemoryRouter([route], { initialEntries: ['/scheduled-tasks/901'] });
  const { container } = render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>);
  await userEvent.setup().click(await screen.findByRole('button', { name: '立即运行' }));
  await act(() => i18n.changeLanguage('en-US'));
  expect(screen.getByRole('button', { name: 'Create run' })).toBeInTheDocument();
  expect(screen.getByText('Active')).toBeInTheDocument();
  expect(screen.getByText('Isolated session / Skip this run')).toBeInTheDocument();
  expect(screen.getByText('Run the latest missed trigger')).toBeInTheDocument();
  expect(screen.getByText('Timed out')).toBeInTheDocument();
  expect(screen.getByText('Catch-up')).toBeInTheDocument();
  expect(screen.getByText('CUSTOM')).toBeInTheDocument();
  expect(screen.getByText('toString')).toBeInTheDocument();
  expect(screen.getByText('Succeeded 1/2 (50%)')).toBeInTheDocument();
  expect(screen.getByText('<b>任务名</b>')).toBeInTheDocument();
  expect(container.querySelector('b')).toBeNull();
  expect(screen.getByRole('heading', { name: '原始任务指令' })).toBeInTheDocument();
  await act(() => i18n.changeLanguage('zh-CN'));
});

resetAfterEach(async () => { await act(() => i18n.changeLanguage('zh-CN')); });
