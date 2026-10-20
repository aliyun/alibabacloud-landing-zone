import { afterEach as resetAfterEach } from 'vitest';
import { useAuthStore } from '@/shared/auth/store';
import { message } from 'antd';
import { act } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { createAppRoutes } from '@/app/router';
import { i18n } from '@/shared/i18n';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { ScheduledTaskRunDetailPage } from './ScheduledTaskRunDetailPage';

vi.mock('@/shared/realtime/useRealtime', () => ({ useRealtime: () => undefined }));
beforeEach(() => useAuthStore.setState({ accessLevel: 'READ_WRITE' }));

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={client}><MemoryRouter initialEntries={['/scheduled-task-runs/10482']}><Routes><Route path="/scheduled-task-runs/:runId" element={<ScheduledTaskRunDetailPage />} /></Routes></MemoryRouter></QueryClientProvider>);
}

const mockParticipants = [
  { userId: 12, targetType: 'AGENT', name: '缺陷分析数字人', displayId: null, role: 'AGENT', roleName: '执行者', isAgent: true, online: true, status: null, executorStatus: null },
  { userId: 5, targetType: 'HUMAN', name: '张三', displayId: null, role: 'HUMAN', roleName: '观察者', isAgent: false, online: false, status: null, executorStatus: null },
];

const mockDeliveryProgress = {
  steps: [
    { stepId: 1, name: '代码分析', status: 'done', agentName: '缺陷分析数字人', executorName: null, error: null, subSteps: null, durationMs: 12000, attempts: null },
    { stepId: 2, name: '修复验证', status: 'active', agentName: '缺陷分析数字人', executorName: null, error: null, subSteps: null, durationMs: null, attempts: null },
  ],
  totalDurationMs: 12000,
};

describe('ScheduledTaskRunDetailPage', () => {
  it('renders dual-panel layout with participants and delivery progress', async () => {
    server.use(
      http.get('/api/scheduled-task-runs/10482', () => HttpResponse.json({ success: true, code: '0', message: '', data: { id: 10482, scheduledTaskId: 901, status: 'FAILED', currentAgentId: 12, currentStepId: 9, version: 2 } })),
      http.get('/api/scheduled-task-runs/10482/comments', () => HttpResponse.json({ success: true, code: '0', message: '', data: [] })),
      http.get('/api/scheduled-task-runs/10482/artifacts', () => HttpResponse.json({ success: true, code: '0', message: '', data: [{ id: 3, fileName: 'failure-analysis.md', name: 'failure-analysis.md' }] })),
      http.get('/api/scheduled-task-runs/10482/derived-workitems', () => HttpResponse.json({ success: true, code: '0', message: '', data: [{ id: 13892, title: 'BUG #13892' }] })),
      http.get('/api/scheduled-task-runs/10482/participants', () => HttpResponse.json({ success: true, code: '0', message: '', data: mockParticipants })),
      http.get('/api/scheduled-task-runs/10482/delivery-progress', () => HttpResponse.json({ success: true, code: '0', message: '', data: mockDeliveryProgress })),
    );
    renderPage();
    expect(await screen.findByText('Run #10482')).toBeInTheDocument();
    // run 已 FAILED：状态标签与收敛后的最后一个 active 步骤都显示失败
    expect(screen.getAllByText('失败')).toHaveLength(2);
    expect(within(screen.getByTestId('delivery-step-1')).getByText(/已完成/)).toBeInTheDocument();
    expect(within(screen.getByTestId('delivery-step-2')).getByText('失败')).toBeInTheDocument();
    expect(screen.queryByText('执行中')).not.toBeInTheDocument();
    expect(await screen.findByText('成员')).toBeInTheDocument();
    expect(await screen.findByText('缺陷分析数字人')).toBeInTheDocument();
    expect(await screen.findByText('代码分析')).toBeInTheDocument();
    expect(await screen.findByText('修复验证')).toBeInTheDocument();
    // 独立的「运行产物」卡片已移除，产物仅在交付进度跟踪的每个数字人下展示
    expect(screen.queryByText('运行产物')).not.toBeInTheDocument();
    expect(screen.queryByText('failure-analysis.md')).not.toBeInTheDocument();
    expect(screen.getByText('BUG #13892')).toBeInTheDocument();

    const scrollRegion = screen.getByTestId('scheduled-run-scroll-region');
    const scrollArea = screen.getByTestId('scheduled-run-left-scroll');
    const commentBar = screen.getByTestId('scheduled-run-sticky-comment');
    expect(scrollRegion).toHaveStyle({ position: 'relative', flex: '1', minHeight: '0', overflow: 'hidden' });
    expect(scrollRegion.contains(scrollArea)).toBe(true);
    expect(scrollArea).toHaveStyle({ overflowY: 'auto', paddingRight: '38px' });
    expect(commentBar.previousElementSibling).toBe(scrollRegion);
  });

  it('posts a comment to the Run resource', async () => {
    let contentMd = '';
    server.use(
      http.get('/api/scheduled-task-runs/10482', () => HttpResponse.json({ success: true, code: '0', message: '', data: { id: 10482, scheduledTaskId: 901, status: 'RUNNING', version: 2 } })),
      http.get('/api/scheduled-task-runs/10482/comments', () => HttpResponse.json({ success: true, code: '0', message: '', data: [] })),
      http.get('/api/scheduled-task-runs/10482/artifacts', () => HttpResponse.json({ success: true, code: '0', message: '', data: [] })),
      http.get('/api/scheduled-task-runs/10482/derived-workitems', () => HttpResponse.json({ success: true, code: '0', message: '', data: [] })),
      http.get('/api/scheduled-task-runs/10482/participants', () => HttpResponse.json({ success: true, code: '0', message: '', data: [] })),
      http.get('/api/scheduled-task-runs/10482/delivery-progress', () => HttpResponse.json({ success: true, code: '0', message: '', data: { steps: [] } })),
      http.post('/api/scheduled-task-runs/10482/comments', async ({ request }) => { contentMd = (await request.json() as { contentMd: string }).contentMd; return HttpResponse.json({ success: true, code: '0', message: '', data: { id: 1, contentMd } }); }),
    );
    renderPage();
    fireEvent.change(await screen.findByPlaceholderText(/评论本次运行/), { target: { value: '请补充失败日志' } });
    fireEvent.click(screen.getByRole('button', { name: '发送' }));
    await waitFor(() => expect(contentMd).toBe('请补充失败日志'));
  });

  it('force cancels an active run after confirmation', async () => {
    let cancelSearch = '';
    server.use(
      http.get('/api/scheduled-task-runs/10482', () => HttpResponse.json({ success: true, code: '0', message: '', data: { id: 10482, scheduledTaskId: 901, status: 'RUNNING', version: 2 } })),
      http.get('/api/scheduled-task-runs/10482/comments', () => HttpResponse.json({ success: true, code: '0', message: '', data: [] })),
      http.get('/api/scheduled-task-runs/10482/artifacts', () => HttpResponse.json({ success: true, code: '0', message: '', data: [] })),
      http.get('/api/scheduled-task-runs/10482/derived-workitems', () => HttpResponse.json({ success: true, code: '0', message: '', data: [] })),
      http.get('/api/scheduled-task-runs/10482/participants', () => HttpResponse.json({ success: true, code: '0', message: '', data: [] })),
      http.get('/api/scheduled-task-runs/10482/delivery-progress', () => HttpResponse.json({ success: true, code: '0', message: '', data: { steps: [] } })),
      http.post('/api/scheduled-task-runs/10482/cancel', ({ request }) => {
        cancelSearch = new URL(request.url).search;
        return HttpResponse.json({ success: true, code: '0', message: '', data: { id: 10482, scheduledTaskId: 901, status: 'CANCELED', version: 3 } });
      }),
    );
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: '强制取消' }));
    fireEvent.click(await screen.findByRole('button', { name: '确认强制取消' }));
    await waitFor(() => expect(cancelSearch).toContain('force=true'));
    expect(cancelSearch).toContain('version=2');
  });

  it('renders the run result as markdown in a dedicated panel', async () => {
    server.use(
      http.get('/api/scheduled-task-runs/10482', () => HttpResponse.json({ success: true, code: '0', message: '', data: { id: 10482, scheduledTaskId: 901, status: 'SUCCEEDED', version: 2, resultSummary: '## 结果概要\n\n- 条目甲 已完成' } })),
      http.get('/api/scheduled-task-runs/10482/comments', () => HttpResponse.json({ success: true, code: '0', message: '', data: [] })),
      http.get('/api/scheduled-task-runs/10482/artifacts', () => HttpResponse.json({ success: true, code: '0', message: '', data: [] })),
      http.get('/api/scheduled-task-runs/10482/derived-workitems', () => HttpResponse.json({ success: true, code: '0', message: '', data: [] })),
      http.get('/api/scheduled-task-runs/10482/participants', () => HttpResponse.json({ success: true, code: '0', message: '', data: [] })),
      http.get('/api/scheduled-task-runs/10482/delivery-progress', () => HttpResponse.json({ success: true, code: '0', message: '', data: { steps: [] } })),
    );
    renderPage();
    expect(await screen.findByText('执行结果')).toBeInTheDocument();
    expect(await screen.findByRole('heading', { name: '结果概要' })).toBeInTheDocument();
    expect(screen.getByText('条目甲 已完成')).toBeInTheDocument();
    expect(screen.queryByText('结果', { selector: '.ant-descriptions-item-label' })).not.toBeInTheDocument();
  });

  it('mounts the agent live activity panel from delivery progress agents', async () => {
    server.use(
      http.get('/api/scheduled-task-runs/10482', () => HttpResponse.json({ success: true, code: '0', message: '', data: { id: 10482, scheduledTaskId: 901, status: 'RUNNING', version: 2 } })),
      http.get('/api/scheduled-task-runs/10482/comments', () => HttpResponse.json({ success: true, code: '0', message: '', data: [] })),
      http.get('/api/scheduled-task-runs/10482/artifacts', () => HttpResponse.json({ success: true, code: '0', message: '', data: [] })),
      http.get('/api/scheduled-task-runs/10482/derived-workitems', () => HttpResponse.json({ success: true, code: '0', message: '', data: [] })),
      http.get('/api/scheduled-task-runs/10482/participants', () => HttpResponse.json({ success: true, code: '0', message: '', data: [] })),
      http.get('/api/scheduled-task-runs/10482/delivery-progress', () => HttpResponse.json({ success: true, code: '0', message: '', data: {
        steps: [],
        agents: [{
          agentId: 12,
          agentName: '缺陷分析数字人',
          status: 'active',
          durationMs: 30000,
          steps: [{
            stepId: 9, name: '修复验证', status: 'active', agentName: '缺陷分析数字人', executorName: null,
            error: null, subSteps: null, durationMs: 30000,
            attempts: [{ dispatchId: 555, agentName: '缺陷分析数字人', executorName: null, status: 'RUNNING', error: null, startedAt: null, durationMs: 30000 }],
          }],
        }],
      } })),
      http.get('/api/dispatches/555/runtime-trace/activities', () => HttpResponse.json({ success: true, code: '0', message: '', data: { dispatchId: 555, activities: [] } })),
      http.get('/api/dispatches/555/live-activity', () => HttpResponse.json({ success: true, code: '0', message: '', data: {
        schemaVersion: '1', dispatchId: 555, agentId: 12, workitemId: null, sourceType: 'SCHEDULED_TASK_RUN',
        attempt: 1, dispatchStatus: 'RUNNING', changed: true, lastSeq: 1, lastUpdatedAt: '2026-09-02T10:00:00Z',
        currentAction: null,
        actions: [{ eventId: 'e1', seq: 1, eventTime: '2026-09-02T10:00:00Z', eventType: 'bash.started', actionType: 'COMMAND', summary: '正在运行测试', status: 'RUNNING', stepId: null, stepKey: null, stepName: null, agentId: 12, dispatchId: 555, attempt: 1 }],
        totalActions: 1, truncated: false, awaitingRuntime: false,
      } })),
    );
    renderPage();
    expect(await screen.findByTestId('delivery-current-execution')).toBeInTheDocument();
    expect(screen.getByTestId('current-agent-name')).toHaveTextContent('缺陷分析数字人');
    expect(await screen.findByText('正在运行测试')).toBeInTheDocument();
  });

  it('renders both stages of the selected dispatch snapshot in the details and timing views', async () => {
    server.use(
      http.get('/api/scheduled-task-runs/10482', () => HttpResponse.json({ success: true, code: '0', data: { id: 10482, scheduledTaskId: 901, status: 'FAILED', version: 2 } })),
      ...['comments', 'artifacts', 'derived-workitems', 'participants'].map(resource => http.get(`/api/scheduled-task-runs/10482/${resource}`, () => HttpResponse.json({ success: true, code: '0', data: [] }))),
      http.get('/api/scheduled-task-runs/10482/delivery-progress', () => HttpResponse.json({ success: true, code: '0', data: {
        steps: [
          { stepId: 10088, name: '收集工单执行日志', status: 'done', agentName: '任务蒸馏数字员工', executorName: null, error: null, subSteps: null, durationMs: 3000, attempts: null },
          { stepId: 10131, name: '分析日志、总结报告并提交 CR', status: 'failed', agentName: '任务蒸馏数字员工', executorName: null, error: null, subSteps: null, durationMs: 4000, attempts: null },
        ],
        totalDurationMs: 7000,
        agents: [{
          agentId: 10033, agentName: '任务蒸馏数字员工', status: 'failed', durationMs: 7000,
          steps: [{
            stepId: 10088, name: '收集工单执行日志', status: 'done', agentName: '任务蒸馏数字员工', executorName: null, error: null, subSteps: null, durationMs: 3000,
            attempts: [{ dispatchId: 12238, agentName: '任务蒸馏数字员工', executorName: null, status: 'FAILED', error: null, startedAt: null, durationMs: 7000 }],
          }],
        }],
        executions: [{
          dispatchId: 12238, agentId: 10033, agentName: '任务蒸馏数字员工', executorName: null, status: 'FAILED', startedAt: '2026-10-16T02:00:00Z', durationMs: 7000,
          error: 'step "10131" gate: checklist not fully checked: "cl_2" remains pending',
          stepTimings: [
            { stepId: 10088, name: '收集工单执行日志', status: 'done', durationMs: 3000 },
            { stepId: 10131, name: '分析日志、总结报告并提交 CR', status: 'failed', durationMs: 4000 },
          ],
        }],
      } })),
      http.get('/api/dispatches/12238/runtime-trace/activities', () => HttpResponse.json({ success: true, code: '0', data: { dispatchId: 12238, activities: [] } })),
      http.get('/api/dispatches/12238/runtime-trace/events', () => HttpResponse.json({ success: true, code: '0', data: { dispatchId: 12238, events: [], changed: true } })),
      http.get('/api/dispatches/12238/live-activity', () => HttpResponse.json({ success: true, code: '0', data: {
        schemaVersion: '1', dispatchId: 12238, agentId: 10033, workitemId: null, sourceType: 'SCHEDULED_TASK_RUN',
        attempt: 1, dispatchStatus: 'FAILED', changed: true, lastSeq: 0, lastUpdatedAt: '2026-10-16T02:00:00Z',
        currentAction: null, actions: [], totalActions: 0, truncated: false, awaitingRuntime: false,
      } })),
      http.get('/api/dispatches/12238/guidance', () => HttpResponse.json({ success: true, code: '0', data: [] })),
    );
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: '打开终端' }));
    const workbench = await screen.findByTestId('delivery-workbench');
    fireEvent.click(within(workbench).getByRole('tab', { name: '执行详情' }));
    expect(within(workbench).getByTestId('delivery-step-10088')).toHaveTextContent(/已完成/);
    expect(within(workbench).getByTestId('delivery-step-10131')).toHaveTextContent('失败');
    expect(within(workbench).getByText('检查项 cl_2 尚未完成。')).toBeInTheDocument();
    expect(within(workbench).getByText(/checklist not fully checked/, { selector: 'pre' })).toBeInTheDocument();
    fireEvent.click(within(workbench).getByRole('tab', { name: 'SDLC 阶段耗时' }));
    const timing = await within(workbench).findByTestId('step-duration-chart');
    expect(timing).toHaveTextContent('本次执行 #12238');
    expect(timing).toHaveTextContent('步骤累计 7秒');
    expect(timing).toHaveTextContent('收集工单执行日志');
    expect(timing).toHaveTextContent('分析日志、总结报告并提交 CR');
    expect(timing).toHaveTextContent('4秒');
  });

  it('mentions a frozen agent from the candidate menu and posts the agent target', async () => {
    let postedBody: { contentMd: string; targetAgentIds?: number[]; targetHumanIds?: number[] } | null = null;
    server.use(
      http.get('/api/scheduled-task-runs/10482', () => HttpResponse.json({ success: true, code: '0', message: '', data: { id: 10482, scheduledTaskId: 901, status: 'RUNNING', version: 2 } })),
      http.get('/api/scheduled-task-runs/10482/comments', () => HttpResponse.json({ success: true, code: '0', message: '', data: [] })),
      http.get('/api/scheduled-task-runs/10482/artifacts', () => HttpResponse.json({ success: true, code: '0', message: '', data: [] })),
      http.get('/api/scheduled-task-runs/10482/derived-workitems', () => HttpResponse.json({ success: true, code: '0', message: '', data: [] })),
      http.get('/api/scheduled-task-runs/10482/participants', () => HttpResponse.json({ success: true, code: '0', message: '', data: mockParticipants })),
      http.get('/api/scheduled-task-runs/10482/delivery-progress', () => HttpResponse.json({ success: true, code: '0', message: '', data: { steps: [] } })),
      http.get('/api/scheduled-task-runs/10482/mention-candidates', () => HttpResponse.json({ success: true, code: '0', message: '', data: [
        { userId: 12, targetType: 'AGENT', name: '缺陷分析数字人', displayId: '40013', isAgent: true, online: true, mentionable: true, mentionDisabledReason: null },
        { userId: 21, targetType: 'AGENT', name: '归档数字人', displayId: '40021', isAgent: true, online: false, mentionable: false, mentionDisabledReason: '不在本次运行的冻结快照中，无法 @ 触发执行' },
      ] })),
      http.post('/api/scheduled-task-runs/10482/comments', async ({ request }) => {
        postedBody = await request.json() as typeof postedBody;
        return HttpResponse.json({ success: true, code: '0', message: '', data: { id: 1, contentMd: 'x' } });
      }),
    );
    renderPage();
    const textarea = await screen.findByPlaceholderText(/评论本次运行/) as HTMLTextAreaElement;
    fireEvent.change(textarea, { target: { value: '@', selectionStart: 1 } });
    fireEvent.keyUp(textarea);

    const disabledItem = await screen.findByRole('menuitem', { name: /归档数字人/ });
    expect(disabledItem).toBeDisabled();
    expect(disabledItem).toHaveAttribute('aria-disabled', 'true');

    fireEvent.click(await screen.findByRole('menuitem', { name: /缺陷分析数字人/ }));
    expect(textarea.value).toBe('@缺陷分析数字人 ');

    fireEvent.click(screen.getByRole('button', { name: '发送' }));
    await waitFor(() => expect(postedBody).toEqual({
      contentMd: '@缺陷分析数字人',
      targetAgentIds: [12],
      targetHumanIds: [],
    }));
  });
});

it('switches the cached run route while retaining raw result and millisecond duration', async () => {
  server.use(
    http.get('/api/scheduled-task-runs/10482', () => HttpResponse.json({ success: true, code: '0', data: { id: 10482, scheduledTaskId: 901, status: 'TIMED_OUT', triggerType: 'MANUAL', sessionMode: 'CONTINUOUS', scheduledAt: '2026-08-12T18:00:00Z', startedAt: '2026-08-12T18:00:00Z', finishedAt: '2026-08-12T19:00:00Z', version: 2, resultSummary: '## 原始AI结果', resumeFromRunId: 7 } })),
    ...['comments', 'artifacts', 'derived-workitems', 'participants'].map(resource => http.get(`/api/scheduled-task-runs/10482/${resource}`, () => HttpResponse.json({ success: true, code: '0', data: [] }))),
    http.get('/api/scheduled-task-runs/10482/delivery-progress', () => HttpResponse.json({ success: true, code: '0', data: { steps: [] } })),
  );
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const route = createAppRoutes().flatMap(route => route.children ?? []).find(route => route.path === '/scheduled-task-runs/:runId')!;
  const router = createMemoryRouter([route], { initialEntries: ['/scheduled-task-runs/10482'] });
  render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>);
  await screen.findByRole('heading', { name: '原始AI结果' });
  await act(() => i18n.changeLanguage('en-US'));
  expect(screen.getByText('Timed out')).toBeInTheDocument();
  expect(screen.getByText(/Trigger: Manual.*Duration: 1h/)).toBeInTheDocument();
  expect(screen.getByText('Continuous session')).toBeInTheDocument();
  expect(screen.getByText('Run #7')).toBeInTheDocument();
  expect(screen.getByText('Derived work items')).toBeInTheDocument();
  expect(screen.getByRole('heading', { name: '原始AI结果' })).toBeInTheDocument();
  await act(() => i18n.changeLanguage('zh-CN'));
  expect(screen.getByText(/耗时：1时/)).toBeInTheDocument();
});

it('refuses run mutations for read-only members in the current language', async () => {
  useAuthStore.setState({ accessLevel: 'READ_ONLY' });
  let mutations = 0;
  const errors = vi.spyOn(message, 'error').mockImplementation(() => undefined as never);
  server.use(http.get('/api/scheduled-task-runs/10482', () => HttpResponse.json({ success: true, code: '0', data: { id: 10482, scheduledTaskId: 901, status: 'RUNNING', version: 2 } })),
    ...['comments', 'artifacts', 'derived-workitems', 'participants'].map(resource => http.get(`/api/scheduled-task-runs/10482/${resource}`, () => HttpResponse.json({ success: true, code: '0', data: [] }))),
    http.get('/api/scheduled-task-runs/10482/delivery-progress', () => HttpResponse.json({ success: true, code: '0', data: { steps: [] } })),
    http.post('/api/scheduled-task-runs/10482/pause', () => { mutations += 1; return HttpResponse.json({ success: true, code: '0', data: {} }); }),
  );
  renderPage();
  await screen.findByRole('button', { name: /暂\s*停/ });
  await act(() => i18n.changeLanguage('en-US'));
  fireEvent.click(screen.getByRole('button', { name: 'Pause' }));
  expect(mutations).toBe(0);
  expect(errors).toHaveBeenCalledWith('Current access: Read-only access. Pause requires Read and write access.');
  await act(() => i18n.changeLanguage('zh-CN'));
  errors.mockRestore();
});

resetAfterEach(async () => { await act(() => i18n.changeLanguage('zh-CN')); });

it.each([
  [59_500, '1分', '1m'],
  [60_000, '1分', '1m'],
  [3_599_500, '1时', '1h'],
  [3_600_000, '1时', '1h'],
  [3_660_000, '1时1分', '1h 1m'],
])('keeps millisecond duration rounding at %s ms in both locales', async (elapsed, chinese, english) => {
  const start = Date.parse('2026-08-12T18:00:00Z');
  server.use(http.get('/api/scheduled-task-runs/10482', () => HttpResponse.json({ success: true, code: '0', data: { id: 10482, scheduledTaskId: 901, status: 'SUCCEEDED', triggerType: 'SCHEDULED', startedAt: new Date(start).toISOString(), finishedAt: new Date(start + Number(elapsed)).toISOString(), version: 2 } })),
    ...['comments', 'artifacts', 'derived-workitems', 'participants'].map(resource => http.get(`/api/scheduled-task-runs/10482/${resource}`, () => HttpResponse.json({ success: true, code: '0', data: [] }))),
    http.get('/api/scheduled-task-runs/10482/delivery-progress', () => HttpResponse.json({ success: true, code: '0', data: { steps: [] } })),
  );
  renderPage();
  expect(await screen.findByText(new RegExp(`耗时：${chinese}$`))).toBeInTheDocument();
  await act(() => i18n.changeLanguage('en-US'));
  expect(screen.getByText(new RegExp(`Duration: ${english}$`))).toBeInTheDocument();
});


it('projects only explicit frozen-origin reasons in the held cache with no locale query or draft reset', async () => {
  let reads = 0;
  const raw = '不在本次运行的冻结快照中，无法 @ 触发执行';
  const candidates = [
    { userId: 12, targetType: 'AGENT', name: 'Not in this run’s frozen snapshot', mentionable: true },
    { userId: 21, targetType: 'AGENT', name: '系统', mentionable: false, mentionDisabledReason: raw, mentionDisabledReasonCode: 'NOT_FROZEN' },
    { userId: 22, targetType: 'AGENT', name: 'legacy', mentionable: false, mentionDisabledReason: raw },
    { userId: 23, targetType: 'AGENT', name: 'unknown', mentionable: false, mentionDisabledReason: '<b>raw {0}</b>', mentionDisabledReasonCode: 'FUTURE' },
  ];
  server.use(
    http.get('/api/scheduled-task-runs/10482', () => { reads += 1; return HttpResponse.json({ success: true, code: '0', data: { id: 10482, status: 'RUNNING', version: 2, error: raw } }); }),
    ...['comments', 'artifacts', 'derived-workitems', 'participants'].map(resource => http.get(`/api/scheduled-task-runs/10482/${resource}`, () => { reads += 1; return HttpResponse.json({ success: true, code: '0', data: [] }); })),
    http.get('/api/scheduled-task-runs/10482/delivery-progress', () => { reads += 1; return HttpResponse.json({ success: true, code: '0', data: { steps: [] } }); }),
    http.get('/api/scheduled-task-runs/10482/mention-candidates', () => { reads += 1; return HttpResponse.json({ success: true, code: '0', data: candidates }); }),
  );
  const { container } = renderPage();
  const textarea = await screen.findByRole('textbox', { name: '评论本次运行' }) as HTMLTextAreaElement;
  fireEvent.change(textarea, { target: { value: 'draft @', selectionStart: 7 } });
  fireEvent.keyUp(textarea);
  const systemItem = await screen.findByRole('menuitem', { name: /系统/ });
  expect(systemItem).toBeDisabled();
  expect(systemItem.textContent).toContain(raw);
  const readCount = reads;
  await act(() => i18n.changeLanguage('en-US'));
  expect(screen.getByRole('textbox', { name: 'Comment on this run' })).toBe(textarea);
  expect(textarea.value).toBe('draft @');
  expect(systemItem.textContent).toContain('Not in this run’s frozen snapshot; cannot trigger execution with @');
  expect(screen.getByRole('menuitem', { name: /legacy/ }).textContent).toContain(raw);
  expect(screen.getByRole('menuitem', { name: /unknown/ }).textContent).toContain('<b>raw {0}</b>');
  expect(screen.getByRole('menuitem', { name: /Not in this run’s frozen snapshot Agent/ })).toBeEnabled();
  expect(container.querySelector('b')).toBeNull();
  expect(reads).toBe(readCount);
  expect(candidates[1].mentionDisabledReason).toBe(raw);
  await act(() => i18n.changeLanguage('zh-CN'));
  expect(systemItem.textContent).toContain(raw);
  expect(reads).toBe(readCount);
});
