import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { ScheduledTaskRunDetailPage } from './ScheduledTaskRunDetailPage';

vi.mock('@/shared/realtime/useRealtime', () => ({ useRealtime: () => undefined }));
vi.mock('@/shared/auth/useAccessCommand', () => ({ useAccessCommand: () => (_required: unknown, _action: string, command: () => unknown) => command() }));

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
    { stepId: 1, name: '代码分析', status: 'done', executorName: '缺陷分析数字人', error: null, subSteps: null, durationMs: 12000, attempts: null },
    { stepId: 2, name: '修复验证', status: 'active', executorName: '缺陷分析数字人', error: null, subSteps: null, durationMs: null, attempts: null },
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
            stepId: 9, name: '修复验证', status: 'active', executorName: '缺陷分析数字人',
            error: null, subSteps: null, durationMs: 30000,
            attempts: [{ dispatchId: 555, executorName: '缺陷分析数字人', status: 'RUNNING', error: null, startedAt: null, durationMs: 30000 }],
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
    expect(await screen.findByTestId('agent-live-activity')).toBeInTheDocument();
    expect(await screen.findByText('正在运行测试')).toBeInTheDocument();
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
