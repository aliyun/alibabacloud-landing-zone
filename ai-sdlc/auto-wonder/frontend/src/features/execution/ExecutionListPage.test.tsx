import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor, act, within } from '@testing-library/react';
import { MemoryRouter, createMemoryRouter, RouterProvider } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
// Load the real route graph during collection, outside the interaction test's timeout.
import { createAppRoutes } from '@/app/router';
import { ExecutionListPage } from './ExecutionListPage';
import { ExecutionDetailDrawer } from './components/ExecutionDetailDrawer';
import { statusMeta } from './statusMeta';
import { i18n } from '@/shared/i18n';
import userEvent from '@testing-library/user-event';

const ROW = {
  id: 312,
  workitemId: 312,
  sdlcStepId: 1,
  agentId: 20,
  agentVersionId: 30,
  executorId: 40,
  status: 'SUCCEEDED',
  attempt: 1,
  resultSummary: '构建通过, PR 已创建',
  error: null,
  packageOssRef: null,
  gmtCreate: '2026-07-11T14:22:00',
  gmtModified: '2026-07-11T14:22:40',
  workitemTitle: '登录页重构',
  agentName: '前端开发',
  agentVersionNo: 7,
  executorName: 'dev-01',
  artifacts: null,
};

const LONG_MARKDOWN = [
  '### 执行结果',
  '',
  '- 构建通过',
  '- 已创建 PR',
  '',
  '```text',
  'very long output',
  '```',
].join('\n');

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <ExecutionListPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(async () => {
  await i18n.changeLanguage('zh-CN');
  window.localStorage.removeItem('autowonder.executions.pageSize');
});

// Catch missing route subscriptions, translated query values, and locale-driven state resets.
it('updates the actual cached executions route while retaining filters, page and open detail', async () => {
  const requests: URL[] = [];
  let supportingRequests = 0;
  const raw = { ...ROW, workitemTitle: '<b>自定义工单</b>', agentName: '<i>自定义 Agent</i>', executorName: '<em>原始执行器</em>' };
  server.use(
    http.get('/api/dispatches', ({ request }) => {
      requests.push(new URL(request.url));
      return HttpResponse.json({ success: true, data: { list: [raw], total: 21, page: 1, pageSize: 10 } });
    }),
    http.get('/api/agents', () => {
      supportingRequests += 1;
      return HttpResponse.json({ success: true, data: [{ id: 20, name: '筛选 Agent', avatarUrl: null, status: 'ONLINE', onlineVersionId: 30, editingVersionId: null, latestVersionNo: 7, version: 1, gmtCreate: ROW.gmtCreate }] });
    }),
    http.get('/api/dispatches/312', () => {
      supportingRequests += 1;
      return HttpResponse.json({ success: true, data: { ...raw, resultSummary: LONG_MARKDOWN, artifacts: [] } });
    }),
  );
  const route = createAppRoutes().flatMap(r => r.children ?? []).find(r => r.path === '/executions');
  expect(route).toBeDefined();
  const router = createMemoryRouter([route!], { initialEntries: ['/executions'] });
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>);
  const user = userEvent.setup();
  const heading = await screen.findByRole('heading', { name: '执行记录' });
  const input = screen.getByPlaceholderText('工单 ID');
  await act(async () => { await user.type(input, '312'); });
  await waitFor(() => expect(requests[requests.length - 1]?.searchParams.get('workitem_id')).toBe('312'));
  await act(async () => { await user.click(screen.getByText('近 7 天')); });
  await act(async () => { await user.click(screen.getAllByRole('combobox')[0]); });
  const agentOption = await screen.findByText('筛选 Agent');
  await act(async () => { await user.click(agentOption); });
  await screen.findByTitle('2');
  await act(async () => { await user.click(screen.getByTitle('2')); });
  await waitFor(() => {
    expect(requests[requests.length - 1]?.searchParams.get('page')).toBe('2');
    expect(requests[requests.length - 1]?.searchParams.get('agent_id')).toBe('20');
    expect(requests[requests.length - 1]?.searchParams.get('time_range')).toBe('7d');
  });
  await act(async () => { await user.click(screen.getByText('详情')); });
  const dialog = await screen.findByRole('dialog');
  await within(dialog).findByText('执行详情 · #312');
  const markdown = within(dialog).getByRole('heading', { name: '执行结果' });
  await waitFor(() => expect(client.isFetching()).toBe(0));
  const cachedQueries = client.getQueryCache().getAll().map(query => [query, query.state.data] as const);
  const requestCount = requests.length;
  const supportingRequestCount = supportingRequests;
  await act(async () => { await i18n.changeLanguage('en-US'); });
  expect(screen.getByRole('heading', { name: 'Executions' })).toBe(heading);
  expect(screen.getByPlaceholderText('Work item ID')).toBe(input);
  expect(input).toHaveValue('312');
  expect(screen.getByTitle('2')).toHaveClass('ant-pagination-item-active');
  expect(screen.getByText('Last 7 days').closest('label')).toHaveClass('ant-segmented-item-selected');
  expect(screen.getByText('21 executions')).toBeInTheDocument();
  expect(requests).toHaveLength(requestCount);
  expect(supportingRequests).toBe(supportingRequestCount);
  for (const [query, data] of cachedQueries) expect(query.state.data).toBe(data);
  expect(screen.getByRole('dialog')).toBe(dialog);
  expect(within(dialog).getByText('Execution details · #312')).toBeInTheDocument();
  expect(within(dialog).getByRole('heading', { name: '执行结果' })).toBe(markdown);
  expect(within(dialog).getByText('No artifacts')).toBeInTheDocument();
  expect(within(dialog).getByRole('button', { name: 'View Agent' })).toBeEnabled();
  expect(within(dialog).getAllByText('Succeeded')).toHaveLength(2);
  expect(screen.getAllByText('<b>自定义工单</b>')[0].querySelector('b')).toBeNull();
  expect(within(dialog).getByText('#312 <b>自定义工单</b>').querySelector('b')).toBeNull();
  expect(within(dialog).getByText('<i>自定义 Agent</i>').querySelector('i')).toBeNull();
  const cell = screen.getByText('<em>原始执行器</em>', { selector: 'td' }).closest('tr')!;
  expect(within(cell).getByText('7/11/2026, 14:22:00')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Refresh' })).toBeInTheDocument();
  await act(async () => { await user.click(screen.getAllByRole('combobox')[1]); });
  const statusOption = await screen.findByText('Succeeded', { selector: '.ant-select-item-option-content' });
  await act(async () => { await user.click(statusOption); });
  await waitFor(() => expect(requests[requests.length - 1]?.searchParams.get('status')).toBe('SUCCEEDED'));
  await screen.findByTitle('2');
  await act(async () => { await user.click(screen.getByTitle('2')); });
  await waitFor(() => expect(requests[requests.length - 1]?.searchParams.get('page')).toBe('2'));
  const filteredRequestCount = requests.length;
  await act(async () => { await i18n.changeLanguage('zh-CN'); });
  expect(screen.getByRole('heading', { name: '执行记录' })).toBe(heading);
  expect(screen.getByText('共 21 条')).toBeInTheDocument();
  expect(screen.getByText('成功', { selector: '.ant-select-selection-item' })).toBeInTheDocument();
  expect(screen.getByTitle('2')).toHaveClass('ant-pagination-item-active');
  expect(requests).toHaveLength(filteredRequestCount);
  expect(supportingRequests).toBe(supportingRequestCount);
  expect(input).toHaveValue('312');
});

// Catch a drawer that only updates via a parent rerender or rewrites server/user content.
it('updates an independently mounted failed drawer and keeps raw errors and artifacts safe', async () => {
  server.use(http.get('/api/dispatches/312', () => HttpResponse.json({ success: true, data: {
    ...ROW, status: 'FAILED', error: '<b>服务器原始错误</b>', resultSummary: LONG_MARKDOWN,
    artifacts: [{ id: 9, workitemId: 312, dispatchId: 312, name: '<i>产物原名</i>', type: '<b>自定义类型</b>', size: 15, gmtCreate: ROW.gmtCreate }],
  } })));
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(<QueryClientProvider client={client}><MemoryRouter><ExecutionDetailDrawer dispatchId={312} open onClose={() => {}} /></MemoryRouter></QueryClientProvider>);
  const dialog = await screen.findByRole('dialog');
  await within(dialog).findByText('错误');
  expect(within(within(dialog).getByRole('alert')).queryByRole('img')).not.toBeInTheDocument();
  const rawError = within(dialog).getByText('<b>服务器原始错误</b>');
  await act(async () => { await i18n.changeLanguage('en-US'); });
  expect(within(dialog).getByText('Error')).toBeInTheDocument();
  expect(within(dialog).getAllByText('Failed')).toHaveLength(2);
  expect(within(dialog).getByText('Pending')).toBeInTheDocument();
  expect(within(dialog).getByText('Acknowledged')).toBeInTheDocument();
  expect(within(dialog).getByText('1 artifact')).toBeInTheDocument();
  expect(within(dialog).getByText('<b>服务器原始错误</b>')).toBe(rawError);
  expect(rawError.querySelector('b')).toBeNull();
  expect(within(dialog).getByText('<i>产物原名</i>').querySelector('i')).toBeNull();
  expect(within(dialog).getByText('<b>自定义类型</b>').querySelector('b')).toBeNull();
  expect(within(dialog).getByRole('button', { name: 'Close' })).toBeInTheDocument();
  await act(async () => { await i18n.changeLanguage('zh-CN'); });
  expect(within(dialog).getByText('错误')).toBeInTheDocument();
  expect(within(dialog).getByText('产物 (1)')).toBeInTheDocument();
  expect(within(dialog).getAllByText('失败')).toHaveLength(2);
});

// Catch stale module-load translations and inherited-object lookup as a known status.
it('resolves finite status metadata in the current language with safe raw unknown fallback', async () => {
  const known = [
    ['PENDING', '待处理', 'Pending', 'default'], ['PACKAGING', '打包中', 'Packaging', 'default'],
    ['DISPATCHED', '已派发', 'Dispatched', 'default'], ['ACKED', '已确认', 'Acknowledged', 'default'],
    ['RUNNING', '运行中', 'Running', 'orange'], ['SUCCEEDED', '成功', 'Succeeded', 'success'],
    ['FAILED', '失败', 'Failed', 'error'], ['TIMEOUT', '已超时', 'Timed out', 'default'], ['CANCELED', '已取消', 'Canceled', 'default'],
  ];
  for (const [locale, labelIndex] of [['zh-CN', 1], ['en-US', 2]] as const) {
    await i18n.changeLanguage(locale);
    for (const row of known) expect(statusMeta(row[0])).toEqual({ label: row[labelIndex], color: row[3] });
    for (const status of ['FUTURE_STATUS', 'constructor', '__proto__', 'toString', '<b>未知状态</b>']) {
      expect(statusMeta(status)).toEqual({ label: status, color: 'default' });
    }
  }
});

it.each(['constructor', '__proto__', 'toString'])('keeps inherited code %s as raw fallback', status => {
  expect(statusMeta(status)).toEqual({ label: status, color: 'default' });
});

describe('ExecutionListPage', () => {
  it('renders a dispatch row from the API', async () => {
    server.use(
      http.get('/api/dispatches', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: { list: [ROW], total: 1, page: 1, pageSize: 50 },
      })),
      http.get('/api/agents', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [],
      })),
    );
    renderPage();
    expect(await screen.findByText('执行记录')).toBeInTheDocument();
    expect(await screen.findByText('前端开发')).toBeInTheDocument();
    expect(screen.getByText('成功')).toBeInTheDocument();
  });

  it('opens the detail drawer on row action click', async () => {
    server.use(
      http.get('/api/dispatches', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: { list: [ROW], total: 1, page: 1, pageSize: 50 },
      })),
      http.get('/api/agents', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [],
      })),
      http.get('/api/dispatches/312', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: { ...ROW, resultSummary: LONG_MARKDOWN, artifacts: [] },
      })),
    );
    renderPage();
    const detail = await screen.findByText('详情');
    fireEvent.click(detail);
    await waitFor(() =>
      expect(screen.getByText('执行详情 · #312')).toBeInTheDocument(),
    );
    expect(screen.getByText('结果摘要')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '执行结果' })).toBeInTheDocument();
  });

  it('sends time_range=7d when segment changed', async () => {
    let lastUrl = '';
    server.use(
      http.get('/api/dispatches', ({ request }) => {
        lastUrl = request.url;
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: { list: [], total: 0, page: 1, pageSize: 50 },
        });
      }),
      http.get('/api/agents', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [],
      })),
    );
    renderPage();
    await screen.findByText('执行记录');
    fireEvent.click(screen.getByText('近 7 天'));
    await waitFor(() => expect(lastUrl).toContain('time_range=7d'));
  });
});
