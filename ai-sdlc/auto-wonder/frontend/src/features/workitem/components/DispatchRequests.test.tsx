import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { act, render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import { i18n } from '@/shared/i18n';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { useAuthStore } from '@/shared/auth/store';
import { DispatchRequests } from './DispatchRequests';
import type { Artifact } from '@/shared/types/workitem';

beforeEach(() => {
  useAuthStore.getState().clear();
  useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_WRITE');
});

afterEach(async () => { vi.unstubAllGlobals(); await act(async () => { await i18n.changeLanguage('zh-CN'); }); });

it('translates declared fallback labels and history headings while retaining raw names and answers', async () => {
  server.use(http.get('/api/workitems/1/dispatch-requests', () => HttpResponse.json({ success: true, data: [
    { id: '1', kind: 'APPROVAL', status: 'PENDING', canReply: true, body: {
      stepName: '当前任务', stepNameKind: 'CURRENT_TASK', approvers: [
        { userId: 9, name: '用户 9', nameKind: 'USER_ID' },
        { userId: 10, name: '用户 10' }, { userId: 11, name: '用户 11', nameKind: 'unknown' },
      ],
    } },
    { id: '2', kind: 'APPROVAL', status: 'PENDING', canReply: false, body: { stepName: '当前任务' } },
    { id: '3', kind: 'INPUT', status: 'RESOLVED', canReply: false, body: {
      stepName: '原始步骤', message: '需要你的确认', schema: { 'x-autowonder-message-kind': 'CONFIRMATION_REQUIRED' },
    }, answer: { action: 'accept', content: { answer: '用户原文 {0}' } } },
  ] })));
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const view = render(<QueryClientProvider client={queryClient}><DispatchRequests workitemId="1" /></QueryClientProvider>);
  await screen.findByText('审批人：用户 9、用户 10、用户 11。任意一位批准即可继续。');
  await act(async () => { await i18n.changeLanguage('en-US'); });
  expect(screen.getByText('Current task · Awaiting human approval')).toBeInTheDocument();
  expect(screen.getByText('当前任务 · Awaiting human approval')).toBeInTheDocument();
  expect(screen.getByText('Approvers: User 9, 用户 10, 用户 11. Approval from any one of them allows execution to continue.')).toBeInTheDocument();
  expect(screen.getByText('Execution questions and human approval history')).toBeInTheDocument();
  expect(await screen.findByText('原始步骤 · Your confirmation is needed')).toBeInTheDocument();
  expect(screen.getByText(/用户原文 \{0\}/)).toBeInTheDocument();
  view.unmount(); queryClient.clear();
});

it('shows the full step name and disables approval controls for other readers', async () => {
  server.use(http.get('/api/workitems/1/dispatch-requests', () => HttpResponse.json({ success: true, data: [{
    id: '8', kind: 'APPROVAL', status: 'PENDING', canReply: false,
    body: { stepName: '编写验收摘要并等待批准', message: '步骤 10109', approvers: [{ userId: 9, name: '审批人甲' }] },
  }] })));
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const view = render(<QueryClientProvider client={queryClient}><DispatchRequests workitemId="1" /></QueryClientProvider>);
  expect(await screen.findByText('编写验收摘要并等待批准 · 等待人工审批')).toBeInTheDocument();
  expect(screen.queryByText('步骤 10109')).not.toBeInTheDocument();
  expect(screen.getByText(/审批人甲/)).toBeInTheDocument();
  expect(screen.getByRole('button', { name: '批准继续' })).toBeDisabled();
  expect(screen.getByRole('button', { name: '要求修改' })).toBeDisabled();
  expect(screen.getByLabelText('确认意见')).toBeDisabled();
  view.unmount(); queryClient.clear();
});

it('requires feedback for revision but allows approval without a note', async () => {
  const answers: unknown[] = [];
  server.use(
    http.get('/api/workitems/1/dispatch-requests', () => HttpResponse.json({ success: true, data: [{
      id: '8', kind: 'APPROVAL', status: 'PENDING', canReply: true, body: { stepName: '验收摘要' },
    }] })),
    http.post('/api/workitems/1/dispatch-requests/8/reply', async ({ request }) => {
      answers.push(await request.json()); return HttpResponse.json({ success: true, data: {} });
    }),
  );
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const view = render(<QueryClientProvider client={queryClient}><DispatchRequests workitemId="1" /></QueryClientProvider>);
  fireEvent.click(await screen.findByRole('button', { name: '要求修改' }));
  expect(await screen.findByText('要求修改时必须填写修改意见')).toBeInTheDocument();
  expect(answers).toEqual([]);
  await act(async () => { await i18n.changeLanguage('en-US'); });
  expect(screen.getByText('验收摘要 · Awaiting human approval')).toBeInTheDocument();
  expect(screen.getByText('Enter a note when requesting changes')).toBeInTheDocument();
  await act(async () => { await i18n.changeLanguage('zh-CN'); });
  fireEvent.click(screen.getByRole('button', { name: '批准继续' }));
  await waitFor(() => expect(answers).toContainEqual({ action: 'approve', content: { note: '' } }));
  await waitFor(() => expect(screen.getByRole('button', { name: '要求修改' })).not.toBeDisabled());
  fireEvent.change(screen.getByLabelText('确认意见'), { target: { value: '  补充测试结果  ' } });
  await act(async () => { await i18n.changeLanguage('en-US'); });
  expect(screen.getByLabelText('Approval note')).toHaveValue('  补充测试结果  ');
  await act(async () => { await i18n.changeLanguage('zh-CN'); });
  fireEvent.click(screen.getByRole('button', { name: '要求修改' }));
  await waitFor(() => expect(answers).toContainEqual({ action: 'reject', content: { note: '补充测试结果' } }));
  view.unmount(); queryClient.clear();
});

it('renders standard multiple choices and submits choices together with a custom answer', async () => {
  let answer: unknown;
  server.use(
    http.get('/api/workitems/1/dispatch-requests', () => HttpResponse.json({ success: true, data: [{
      id: '9', kind: 'INPUT', status: 'PENDING', canReply: true, body: { stepName: '选择验证范围', message: '选择地域', schema: {
        type: 'object', properties: { regions: { type: 'array', items: { type: 'string', enum: ['上海', '北京'] } } }, required: ['regions'],
      } },
    }] })),
    http.post('/api/workitems/1/dispatch-requests/9/reply', async ({ request }) => {
      answer = await request.json(); return HttpResponse.json({ success: true, data: {} });
    }),
  );
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const view = render(<QueryClientProvider client={queryClient}><DispatchRequests workitemId="1" /></QueryClientProvider>);
  fireEvent.click(await screen.findByRole('checkbox', { name: '上海' }));
  fireEvent.click(screen.getByRole('checkbox', { name: '有其他想法' }));
  fireEvent.change(screen.getByTestId('elicitation-other-input-regions'), { target: { value: '深圳' } });
  fireEvent.click(screen.getByTestId('elicitation-submit-9'));
  await waitFor(() => expect(answer).toEqual({ action: 'accept', content: { regions: ['上海', '深圳'] } }));
  view.unmount(); queryClient.clear();
});

it('keeps approval distinct from live choices and submits custom answers', async () => {
  const answers: unknown[] = [];
  server.use(
    http.get('/api/workitems/1/dispatch-requests', () => HttpResponse.json({ success: true, code: '0', data: [
      { id: '1', dispatchId: '10', kind: 'INPUT', status: 'PENDING', canReply: true, body: { message: '选环境', schema: {
        type: 'object', properties: { target: { type: 'string', enum: ['dev', 'test'] } }, required: ['target'],
      } } },
      { id: '2', dispatchId: '10', kind: 'APPROVAL', status: 'PENDING', canReply: true, body: { message: '检查实现', context: { fingerprint: 'version-1' } } },
    ] })),
    http.post('/api/workitems/1/dispatch-requests/:id/reply', async ({ request, params }) => {
      answers.push({ id: params.id, ...await request.json() as object });
      return HttpResponse.json({ success: true, code: '0', data: {} });
    }),
  );
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const invalidate = vi.spyOn(queryClient, 'invalidateQueries');
  const view = render(<QueryClientProvider client={queryClient}><DispatchRequests workitemId="1" /></QueryClientProvider>);
  await screen.findByText('选环境');
  await waitFor(() => expect(invalidate).toHaveBeenCalledWith({ queryKey: ['workitem', '1'] }));
  expect(invalidate).toHaveBeenCalledWith({ queryKey: ['workitems'] });
  expect(screen.getByText('dev')).toBeInTheDocument();
  expect(screen.queryByTestId('elicitation-skip-2')).toBeNull();
  // 工单 56330：收起/展开只由澄清面板开启，派发请求确认这处复用不出现收起入口。
  expect(screen.queryByTestId('elicitation-toggle-1')).toBeNull();
  expect(screen.queryByTestId('elicitation-collapsed-1')).toBeNull();
  fireEvent.click(screen.getByTestId('elicitation-option-target-other'));
  fireEvent.change(screen.getByTestId('elicitation-other-input-target'), { target: { value: 'staging' } });
  fireEvent.click(screen.getByTestId('elicitation-submit-1'));
  await waitFor(() => expect(answers).toContainEqual({ id: '1', action: 'accept', content: { target: 'staging' } }));
  await waitFor(() => expect(screen.getByRole('button', { name: '批准继续' })).not.toHaveClass('ant-btn-loading'));
  fireEvent.change(screen.getByLabelText('确认意见'), { target: { value: 'checked' } });
  fireEvent.click(screen.getByRole('button', { name: '批准继续' }));
  await waitFor(() => expect(answers).toContainEqual({ id: '2', action: 'approve', content: { note: 'checked' } }));
  view.unmount(); queryClient.clear();
});

const historyTitle = '执行问答与人工审批历史';

it.each([{ data: [] }, { data: [{ id: '1', kind: 'INPUT', status: 'PENDING', body: { message: '选环境' } }] }])('only renders sections that have records: %j', async ({ data }) => {
  server.use(http.get('/api/workitems/1/dispatch-requests', () => HttpResponse.json({ success: true, data })));
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const view = render(<QueryClientProvider client={queryClient}><DispatchRequests workitemId="1" /></QueryClientProvider>);
  await waitFor(() => expect(queryClient.getQueryData(['dispatch-requests', '1'])).toEqual(data));
  expect(Boolean(screen.queryByText(historyTitle))).toBe(data.length > 0);
  view.unmount(); queryClient.clear();
});

it('keeps approval controls inside the human history alongside structured answers', async () => {
  const data = [
    { id: '4', kind: 'APPROVAL', status: 'PENDING', canReply: true, body: { stepName: '审批步骤', stepAttempt: 3, context: { evidenceRefs: ['report.md'], fingerprint: 'version-3' } } },
    { id: '3', kind: 'APPROVAL', status: 'RESOLVED', actorId: '10000', actorName: '审批人甲', answeredAt: new Date(2026, 9, 12, 14, 4, 47).getTime(), body: { stepName: '审批步骤', stepAttempt: 2 }, answer: { action: 'reject', content: { note: '补充验收证据' } } },
    { id: '2', kind: 'INPUT', status: 'RESOLVED', actorId: '9', answeredAt: new Date(2026, 9, 12, 13, 3, 21).toISOString(), body: { stepName: '选择范围', message: '需要选哪些地域？', schema: { type: 'object', properties: { regions: { type: 'array', title: '地域' }, confirm: { type: 'boolean', title: '已确认' } } } }, answer: { action: 'accept', content: { regions: ['上海', '北京'], confirm: false } } },
  ];
  server.use(http.get('/api/workitems/1/dispatch-requests', () => HttpResponse.json({ success: true, data })));
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const view = render(<QueryClientProvider client={queryClient}><DispatchRequests workitemId="1" /></QueryClientProvider>);
  const human = await screen.findByRole('region', { name: historyTitle });
  expect(within(human).getByLabelText('确认意见')).toBeVisible();
  expect(within(human).getByRole('button', { name: '批准继续' })).toBeVisible();
  expect(within(human).getByText('审批人：审批人甲')).toBeVisible();
  expect(within(screen.getByTestId('dispatch-request-3')).getByText('审批时间：2026年10月12日 14:04:47')).toBeVisible();
  expect(within(screen.getByTestId('dispatch-request-2')).getByText('回答时间：2026年10月12日 13:03:21')).toBeVisible();
  expect(within(screen.getByTestId('dispatch-request-4')).queryByText(/审批时间/)).not.toBeInTheDocument();
  expect(within(human).getByText('补充验收证据')).toBeVisible();
  expect(within(human).getByText('地域')).toBeVisible();
  expect(within(human).getByText('上海')).toBeVisible();
  expect(within(human).getByText('否')).toBeVisible();
  expect(within(human).getByText('回答人：未知用户')).toBeVisible();
  expect(human.querySelector('pre')).toBeNull();
  expect(within(human).getAllByTestId(/^dispatch-request-/).map(card => card.dataset.testid)).toEqual(['dispatch-request-4', 'dispatch-request-3', 'dispatch-request-2']);
  await act(async () => { await i18n.changeLanguage('en-US'); });
  expect(screen.getByText('Approver: 审批人甲')).toBeVisible();
  expect(screen.getByText('Answered by Unknown user')).toBeVisible();
  expect(screen.getByText('Approval time: Oct 12, 2026, 2:04:47 PM')).toBeVisible();
  expect(screen.getByText('Answered at: Oct 12, 2026, 1:03:21 PM')).toBeVisible();
  expect(screen.getByText('补充验收证据')).toBeVisible();
  expect(screen.getByText('No')).toBeVisible();
  view.unmount(); queryClient.clear();
});

it('sizes the scroll viewport to the first two complete cards', async () => {
  const data = [3, 2, 1].map(id => ({ id: String(id), kind: 'APPROVAL', status: 'RESOLVED', body: { stepName: '审批步骤' }, answer: { action: 'approve', content: { note: `轮次 ${id}` } } }));
  server.use(http.get('/api/workitems/1/dispatch-requests', () => HttpResponse.json({ success: true, data })));
  const bounds = vi.spyOn(Element.prototype, 'getBoundingClientRect').mockImplementation(function (this: Element) {
    return { height: (this as HTMLElement).dataset.testid === 'dispatch-request-3' ? 180 : 220 } as DOMRect;
  });
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const view = render(<QueryClientProvider client={queryClient}><DispatchRequests workitemId="1" /></QueryClientProvider>);
  fireEvent.click(await screen.findByText(historyTitle));
  const region = screen.getByRole('region', { name: historyTitle });
  await waitFor(() => expect(region).toHaveStyle({ maxHeight: '416px', overflowY: 'auto' }));
  expect(within(region).getAllByTestId(/^dispatch-request-/)).toHaveLength(3);
  view.unmount(); queryClient.clear(); bounds.mockRestore();
});

it('opens approval evidence from its own execution in the authenticated preview and retains the version', async () => {
  const artifact: Artifact = { id: 7, workitemId: 1, dispatchId: 10, name: 'artifacts/output/approval-summary.md', type: 'DELIVERABLE', size: 100, gmtCreate: '2026-10-12T05:35:00Z' };
  const requests = [{ id: '8', dispatchId: 10, kind: 'APPROVAL', status: 'PENDING', canReply: false,
    body: { stepName: '审批步骤', context: { fingerprint: 'version-1', evidenceRefs: ['output/approval-summary.md', 'missing.md'] } } }];
  server.use(
    http.get('/api/workitems/1/dispatch-requests', () => HttpResponse.json({ success: true, data: requests })),
    http.get('/api/artifacts/7/download', () => HttpResponse.json({ success: true, data: 'https://oss.example/approval-summary.md' })),
  );
  useAuthStore.getState().setTokens('preview-token', 'refresh-token');
  vi.stubGlobal('fetch', vi.fn(async () => new Response('# 本次审批摘要\n版本一原文 {0}', { status: 200 })));
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const view = render(<QueryClientProvider client={queryClient}><DispatchRequests workitemId="1" artifacts={[artifact, { ...artifact, id: 99, dispatchId: 11 }]} /></QueryClientProvider>);
  fireEvent.click(await screen.findByText('查看本次确认的产物与版本'));
  expect(screen.getByText('version-1')).toBeVisible();
  expect(screen.getByText('missing.md').closest('button, a')).toBeNull();
  fireEvent.click(screen.getByRole('button', { name: 'output/approval-summary.md' }));
  await waitFor(() => expect(screen.getByRole('heading', { name: '本次审批摘要' })).toBeVisible());
  expect(screen.getByText('版本一原文 {0}')).toBeVisible();
  const [url, init] = vi.mocked(fetch).mock.calls[0];
  expect(url).toBe('/api/artifacts/7/preview');
  expect((init?.headers as Headers).get('Authorization')).toBe('Bearer preview-token');
  expect(await screen.findByRole('link', { name: /下载/ })).toHaveAttribute('href', 'https://oss.example/approval-summary.md');
  expect(screen.getByRole('button', { name: '批准继续' })).toBeDisabled();
  await act(async () => { await i18n.changeLanguage('en-US'); });
  expect(screen.getByText('Artifact version')).toBeInTheDocument();
  expect(screen.getByText('version-1')).toBeInTheDocument();
  expect(screen.getByText('版本一原文 {0}')).toBeVisible();
  view.unmount(); queryClient.clear();
});

it('does not link missing, ambiguous or unsafe evidence to unrelated artifacts', async () => {
  const artifact: Artifact = { id: 7, workitemId: 1, dispatchId: 10, name: 'output/a/report.md', type: 'DELIVERABLE', size: 100, gmtCreate: '2026-10-12T05:35:00Z' };
  server.use(http.get('/api/workitems/1/dispatch-requests', () => HttpResponse.json({ success: true, data: [{
    id: '8', dispatchId: '10', kind: 'APPROVAL', status: 'PENDING', canReply: true,
    body: { context: { fingerprint: 'version-1', evidenceRefs: ['report.md', 'other-run.md', 'javascript:alert(1)'] } },
  }] })));
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const view = render(<QueryClientProvider client={queryClient}><DispatchRequests workitemId="1" artifacts={[
    artifact, { ...artifact, id: 8, name: 'output/b/report.md' }, { ...artifact, id: 9, dispatchId: 11, name: 'other-run.md' },
  ]} /></QueryClientProvider>);
  fireEvent.click(await screen.findByText('查看本次确认的产物与版本'));
  for (const name of ['report.md', 'other-run.md', 'javascript:alert(1)']) {
    expect(screen.getByText(name)).toBeVisible();
    expect(screen.getByText(name).closest('button, a')).toBeNull();
  }
  view.unmount(); queryClient.clear();
});
