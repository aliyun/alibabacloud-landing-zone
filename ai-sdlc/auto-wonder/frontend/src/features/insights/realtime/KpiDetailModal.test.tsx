import { i18n } from '@/shared/i18n';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { act, cleanup, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import KpiDetailModal from './KpiDetailModal';
import { server } from '@/test/mocks/server';

function renderModal(kpiKey: 'runningDispatches' | 'todayCompletedTasks' | 'weekCompletedTasks' | null) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <KpiDetailModal kpiKey={kpiKey} onClose={() => {}} />
    </QueryClientProvider>,
  );
}

function ok<T>(data: T) {
  return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data });
}

describe('KpiDetailModal', () => {
  it('does not render modal content when kpiKey is null', () => {
    renderModal(null);
    expect(screen.queryByText('正在运行')).not.toBeInTheDocument();
    expect(screen.queryByText('今日完成')).not.toBeInTheDocument();
  });

  it('shows today completed workitems when opened', async () => {
    server.use(
      http.get('/api/dashboard/completed/today', () =>
        ok([
          { workitemId: 101, title: '修复登录页' },
          { workitemId: 202, title: '优化看板性能' },
        ]),
      ),
    );

    renderModal('todayCompletedTasks');

    await waitFor(() => {
      expect(screen.getByText('#101 修复登录页')).toBeInTheDocument();
    });
    expect(screen.getByText('#202 优化看板性能')).toBeInTheDocument();
  });

  it('shows week completed workitems when opened', async () => {
    server.use(
      http.get('/api/dashboard/completed/week', () =>
        ok([{ workitemId: 303, title: '重构数据层' }]),
      ),
    );

    renderModal('weekCompletedTasks');

    await waitFor(() => {
      expect(screen.getByText('#303 重构数据层')).toBeInTheDocument();
    });
  });

  it('shows running workitems when opened', async () => {
    server.use(
      http.get('/api/dashboard/running', () =>
        ok([
          { dispatchId: 1, agentId: 10, agentName: 'Alice', workitemId: 401, workitemTitle: '部署流水线', stepName: '编码', runningMinutes: 5 },
          { dispatchId: 2, agentId: 20, agentName: 'Bob', workitemId: 402, workitemTitle: '修复缓存', stepName: '测试', runningMinutes: 12 },
        ]),
      ),
    );

    renderModal('runningDispatches');

    await waitFor(() => {
      expect(screen.getByText('#401 部署流水线')).toBeInTheDocument();
    });
    expect(screen.getByText('#402 修复缓存')).toBeInTheDocument();
  });

  it('shows empty state when list is empty', async () => {
    server.use(
      http.get('/api/dashboard/completed/today', () => ok([])),
    );

    renderModal('todayCompletedTasks');

    await waitFor(() => {
      expect(screen.getByText('暂无工单')).toBeInTheDocument();
    });
  });
});

beforeEach(async () => { await i18n.changeLanguage('zh-CN'); });
afterEach(async () => { cleanup(); await i18n.changeLanguage('zh-CN'); });
it('switches an independent open modal error, retry and running empty state in place', async () => {
  let requests = 0;
  server.use(http.get('/api/dashboard/running', () => ++requests === 1 ? HttpResponse.json({ success: false, code: '50000', message: '后端错误', data: null, traceId: null }, { status: 500 }) : ok([])));
  const router = createMemoryRouter([{ path: '/', element: <KpiDetailModal kpiKey="runningDispatches" onClose={() => {}} /> }]);
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><RouterProvider router={router} /></QueryClientProvider>);
  await screen.findByText('工单加载失败');
  const dialog = screen.getByRole('dialog');
  await act(async () => { await i18n.changeLanguage('en-US'); });
  expect(screen.getByRole('dialog')).toBe(dialog);
  expect(screen.getByText('Failed to load work items')).toBeInTheDocument();
  expect(screen.getByText('Running')).toBeInTheDocument();
  await userEvent.click(screen.getByRole('button', { name: 'Retry' }));
  expect(await screen.findByText('No running work items')).toBeInTheDocument();
  expect(requests).toBe(2);
  await act(async () => { await i18n.changeLanguage('zh-CN'); });
  expect(screen.getByText('暂无运行中的工单')).toBeInTheDocument();
});

it('switches loading and completed empty UI while preserving the completed-today query', async () => {
  let release!: () => void;
  const pending = new Promise<void>(resolve => { release = resolve; });
  let requests = 0;
  server.use(http.get('/api/dashboard/completed/today', async () => { requests++; await pending; return ok([]); }));
  renderModal('todayCompletedTasks');
  expect(screen.getByText('加载工单…')).toBeInTheDocument();
  await act(async () => { await i18n.changeLanguage('en-US'); });
  expect(screen.getByText('Completed today')).toBeInTheDocument();
  expect(screen.getByText('Loading work items…')).toBeInTheDocument();
  release();
  expect(await screen.findByText('No work items')).toBeInTheDocument();
  expect(requests).toBe(1);
  await act(async () => { await i18n.changeLanguage('zh-CN'); });
  expect(screen.getByText('暂无工单')).toBeInTheDocument();
});
