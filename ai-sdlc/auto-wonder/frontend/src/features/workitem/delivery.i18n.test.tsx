import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { within } from '@testing-library/react';
import * as workitemApi from './api';
import { LiveActivityPanel } from './components/LiveActivityPanel';
import { RuntimeLogDrawer } from './components/RuntimeLogDrawer';
import { HumanInterventionBadge } from './components/HumanInterventionBadge';
import { TokenUsageBadge } from './components/TokenUsageBadge';
import { runtimeStatusLabel, artifactTypeLabel } from './runtimeLabels';
import { artifactCategories, groupArtifactsByExecution } from './artifactGrouping';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { Form, message } from 'antd';
import { server } from '@/test/mocks/server';
import { i18n } from '@/shared/i18n';
import { useAuthStore } from '@/shared/auth/store';
import { DeliveryProgress, formatDuration } from './components/DeliveryProgress';
import { RecoveryControls } from './components/RecoveryControls';
import { StartDeliveryModal } from './components/StartDeliveryModal';
import { RuntimeTraceDrawer } from './components/RuntimeTraceDrawer';
import { writeClarificationPrefill } from './clarification/prefill';
import type { DeliveryProgress as Progress } from '@/shared/types/workitem';
const authored = 'Agent <strong>Alice</strong>';
const ok = (data: unknown) => HttpResponse.json({ success: true, code: '0', message: '', data });
function route(element: React.ReactNode) {
  return render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><RouterProvider router={createMemoryRouter([{ path: '/', element }])} /></QueryClientProvider>);
}
const language = async (locale: 'zh-CN' | 'en-US') => { await act(async () => { await i18n.changeLanguage(locale); }); };
beforeEach(async () => { server.use(http.get('/api/dispatches/:id/runtime-trace/events', () => ok({ events: [], changed: true }))); vi.restoreAllMocks(); localStorage.clear(); useAuthStore.setState({ accessLevel: 'READ_WRITE' }); await language('zh-CN'); });
afterEach(async () => { cleanup(); await language('zh-CN'); });
it('formats feature durations with the current locale and retains rounding boundaries', async () => {
  expect([null, -1, 0, 59499, 59500, 61000, 3600000, 3660000].map(formatDuration)).toEqual(['', '', '0秒', '59秒', '1分', '1分1秒', '1时', '1时1分']);
  await language('en-US');
  expect([null, -1, 0, 59499, 59500, 61000, 3600000, 3660000].map(formatDuration)).toEqual(['', '', '0s', '59s', '1m', '1m 1s', '1h', '1h 1m']);
});
it('updates delivery cards and retains expanded history and authored names', async () => {
  server.use(http.get('/api/dispatches/:id/live-activity', () => ok({ actions: [], currentAction: null })), http.get('/api/dispatches/:id/runtime-trace/activities', () => ok({ activities: [] })));
  const progress = { steps: [], totalDurationMs: 61000, agents: [{ agentId: 1, agentName: authored, status: 'failed', durationMs: 61000, steps: [{ stepId: 1, name: 'AI 原文', executorName: null, status: 'failed', durationMs: 61000, attempts: [{ dispatchId: 44, executorName: null, startedAt: null, durationMs: null, status: 'FAILED', canContinue: true }], subSteps: [] }] }], processGraph: { nodes: [], edges: [] } } as Progress;
  route(<DeliveryProgress progress={progress} onContinue={() => {}} artifacts={[{ id: 1, name: authored, type: 'EVIDENCE', dispatchId: 44, workitemId: 42, size: 0, gmtCreate: '' }]} />);
  fireEvent.click(screen.getByText('产物（1 artifacts）'));
  await language('en-US');
  expect(screen.getAllByText('Delivery progress').length).toBeGreaterThan(0);
  expect(screen.getAllByText(/1m 1s/).length).toBeGreaterThan(0);
  expect(screen.getByRole('button', { name: 'Continue' })).toBeInTheDocument();
  await waitFor(() => expect(screen.getByText('Verification evidence (1)')).toBeVisible());
  expect(screen.getByRole('button', { name: `Preview artifact ${authored}` })).toBeVisible();
  await language('zh-CN');
  expect(screen.getByRole('button', { name: `预览产物 ${authored}` })).toBeVisible();
});
it('switches recovery controls without changing submitted command codes or diagnostic text', async () => {
  let body: unknown;
  server.use(http.get('/api/workitems/42/recovery', () => ok({ closed: false, executions: [{ dispatchId: 44, agentId: 1, status: 'FAILED', attempt: 2, error: 'raw <reason> 中文', recovery: {} }], restarts: [] })), http.post('/api/workitems/42/recovery', async ({ request }) => { body = await request.json(); return ok(null); }));
  route(<><RecoveryControls workitemId="42" /><RecoveryControls workitemId="42" variant="execution" dispatchId={44} /></>);
  const toggle = await screen.findByRole('switch', { name: '允许数字员工执行' });
  await language('en-US');
  expect(screen.getByRole('switch', { name: 'Allow agents to execute' })).toBe(toggle);
  expect(screen.getByText('Failed')).toBeVisible();
  expect(screen.getByText('raw <reason> 中文')).toBeVisible();
  fireEvent.click(toggle);
  await waitFor(() => expect(body).toEqual({ action: 'close', force: false }));
});
it('switches lazy trace details while keeping selected turn and raw prompts', async () => {
  server.use(http.get('/api/dispatches/44/runtime-trace', () => ok({ changed: true, source: 'LIVE', events: [], sessions: [{ sessionId: 's1', status: 'COMPLETED', boundaries: [], turns: [{ turnId: '1', durationMs: 61000, prompt: authored, systemPrompt: '系统原文', output: 'AI 原文', observations: [], contextFiles: [], spans: [] }] }] })));
  route(<RuntimeTraceDrawer node={{ key: 'n1', dispatchId: 44, agentName: authored, status: 'SUCCEEDED' }} processGraph={{ nodes: [], edges: [] }} onClose={() => {}} />);
  fireEvent.click(await screen.findByText('Turn 1'));
  expect(screen.getByText('系统原文')).toBeInTheDocument();
  await language('en-US');
  expect(screen.getByText('User prompt')).toBeInTheDocument();
  expect(screen.getByText(authored, { selector: 'pre' })).toBeInTheDocument();
  expect(screen.getByText('AI 原文')).toBeInTheDocument();
  await language('zh-CN');
  expect(screen.getByText('用户提示词')).toBeInTheDocument();
});
it('refreshes visible validation and preserves canonical assignment across async locale changes', async () => {
  let submitted: unknown;
  let finish: (() => void) | undefined;
  server.use(http.get('/api/squads', () => ok({ list: [{ id: 1, name: authored }], total: 1 })), http.get('/api/squads/1/members', () => ok([{ agentId: 77, agentName: authored, roleCode: 'AW_FS_DEV' }])), http.put('/api/workitems/42/assignee', async ({ request }) => { submitted = await request.json(); await new Promise<void>(resolve => { finish = resolve; }); return ok(null); }));
  const success = vi.spyOn(message, 'success');
  const useForm = Form.useForm;
  let form: ReturnType<typeof Form.useForm>[0];
  vi.spyOn(Form, 'useForm').mockImplementation((instance) => { const result = useForm(instance); form = result[0]; return result; });
  route(<StartDeliveryModal open workitemId={42} onClose={() => {}} />);
  await act(async () => { await form!.validateFields().catch(() => undefined); });
  await screen.findByText('请选择小队');
  await language('en-US');
  expect(await screen.findByText('Choose a squad.')).toBeInTheDocument();
  expect(screen.getByText('Choose an agent.')).toBeInTheDocument();
  cleanup();
  writeClarificationPrefill(42, { squadId: 1, agentId: 77 });
  route(<StartDeliveryModal open workitemId={42} onClose={() => {}} />);
  await screen.findByText(`${authored} (AW_FS_DEV)`);
  const agentField = screen.getByLabelText('Agent for the first step');
  await language('zh-CN');
  expect(screen.getByLabelText('首步执行 Agent')).toBe(agentField);
  expect(form!.getFieldsValue()).toMatchObject({ squadId: 1, agentId: 77 });
  await language('en-US');
  fireEvent.click(screen.getByRole('button', { name: 'Start delivery' }));
  await waitFor(() => expect(finish).toBeTypeOf('function'));
  await language('zh-CN');
  await act(async () => finish!());
  await waitFor(() => expect(success).toHaveBeenCalledWith('已启动交付'));
  expect(submitted).toMatchObject({ assigneeType: 'AGENT', assigneeRef: 77, squadId: 1 });
});
it('keeps graph selection, zoom and selected dispatch while independently localized options refresh', async () => {
  server.use(http.get('/api/dispatches/:id/live-activity', () => ok({ actions: [], currentAction: null, lastUpdatedAt: null, truncated: false })), http.get('/api/dispatches/:id/runtime-trace/activities', () => ok({ activities: [] })));
  const progress: Progress = { steps: [], agents: [{ agentId: 1, agentName: authored, status: 'finished', durationMs: 61000, steps: [{ stepId: 1, name: 'AI 原文', executorName: null, status: 'done', durationMs: null, subSteps: [], attempts: [44, 45].map(dispatchId => ({ dispatchId, executorName: null, startedAt: null, durationMs: null, status: 'SUCCEEDED' })) }] }], processGraph: { nodes: [{ key: 'n1', agentName: authored, status: 'SUCCEEDED', durationMs: 61000 }, { key: 'n2', agentName: '需求', status: 'HUMAN' }], edges: [{ sourceKey: 'n1', targetKey: 'n2', type: 'COMMENT_REWORK', commentId: 5, label: '用户返工（评论 #5）' }] } };
  route(<DeliveryProgress progress={progress} />);
  fireEvent.click(screen.getAllByTestId('execution-graph-worker')[0]);
  fireEvent.click(screen.getByRole('button', { name: '放大' }));
  const viewport = screen.getByTestId('execution-graph-viewport');
  const zoom = viewport.getAttribute('data-zoom');
  fireEvent.click(screen.getByRole('button', { name: '打开终端' }));
  const select = screen.getByTestId('live-activity-dispatch-select');
  fireEvent.mouseDown(within(select).getByRole('combobox'));
  fireEvent.click(await screen.findByText('Dispatch 44'));
  await language('en-US');
  expect(viewport).toHaveAttribute('data-zoom', zoom);
  expect(screen.getByTestId('execution-graph-detail-panel')).toHaveTextContent('Status: Succeeded · Duration: 1m 1s');
  expect(select).toHaveTextContent('Dispatch 44');
  fireEvent.mouseDown(within(select).getByRole('combobox'));
  expect(await screen.findByText('Dispatch 45 (latest)')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: `Transition 1 from ${authored} to 需求: User rework (comment #5)` })).toBeInTheDocument();
});
it('localizes live action fallback labels and relative durations without rewriting reported summaries', async () => {
  const eventTime = new Date(Date.now() - 120000).toISOString();
  const action = { eventId: 'e1', seq: 1, actionType: 'COMMAND', status: 'COMPLETED', eventTime, summary: authored };
  server.use(http.get('/api/dispatches/44/live-activity', () => ok({ actions: [action], currentAction: null, lastUpdatedAt: eventTime, changed: true, lastSeq: 1, truncated: true })));
  route(<LiveActivityPanel dispatchId={44} enabled={false} />);
  await screen.findByTestId('live-activity-panel');
  fireEvent.click(screen.getByText('近期动作（1） · 已裁剪'));
  expect(screen.getByText(authored)).toBeVisible();
  await language('en-US');
  expect(screen.getByText('Command execution')).toBeVisible();
  expect(screen.getByText('2 minutes ago')).toBeVisible();
  expect(screen.getByText(authored)).toBeVisible();
  expect(screen.getByText('Recent actions (1) · Trimmed')).toBeVisible();
});
it('preserves log filters and raw event content while drawer controls and timestamps react', async () => {
  server.use(http.get('/api/dispatches/44/runtime-trace/events', () => ok({ changed: true, lastSeq: 1, events: [{ seq: 1, eventType: 'raw.tool', eventTime: '2026-08-01T10:20:30Z', detail: { outputSummary: authored } }] })));
  route(<RuntimeLogDrawer dispatchId={44} onClose={() => {}} />);
  const search = screen.getByPlaceholderText('搜索事件、工具、输入或输出');
  await screen.findByText(authored);
  await userEvent.type(search, 'Alice');
  await language('en-US');
  expect(screen.getByPlaceholderText('Search events, tools, input or output')).toBe(search);
  expect(search).toHaveValue('Alice');
  expect(screen.getByText(authored)).toBeInTheDocument();
  expect(screen.getByRole('columnheader', { name: 'Time' })).toBeInTheDocument();
  expect(screen.getByText(/^Refreshes every 3 seconds · 1 event/)).toBeInTheDocument();
});
it('resolves category and known enum labels on demand while preserving unknown protocol values and grouping payloads', async () => {
  const file = { id: 1, dispatchId: 44, type: 'EVIDENCE', name: authored, workitemId: 42, size: 1, gmtCreate: '' };
  const steps = [{ name: authored, attempts: [{ dispatchId: 44, executorName: null, status: 'FAILED', startedAt: null, durationMs: null }] }];
  expect(artifactCategories.find(category => category.key === 'evidence')?.label).toBe('验证证据');
  await language('en-US');
  expect(artifactCategories.find(category => category.key === 'evidence')?.label).toBe('Verification evidence');
  const group = groupArtifactsByExecution(steps, [file])[0];
  expect(group.label).toBe('Execution 1 · Failed');
  expect(group.status).toBe('FAILED');
  expect(group.artifacts[0]).toBe(file);
  expect(runtimeStatusLabel('FUTURE_STATUS')).toBe('FUTURE_STATUS');
  expect(artifactTypeLabel('EVIDENCE')).toBe('Verification evidence');
  expect(artifactTypeLabel('FUTURE_TYPE')).toBe('FUTURE_TYPE');
});
it('refreshes an already visible usage popover and keeps human-action badges concise', async () => {
  route(<><HumanInterventionBadge item={{ statusCategory: 'PENDING_DECISION', assigneeType: 'HUMAN', assigneeRef: 1, assigneeName: authored }} /><TokenUsageBadge usage={{ model: authored, credits: 1.5 }} /></>);
  fireEvent.mouseEnter(screen.getByText('1.5 credits'));
  await screen.findByText('模型:');
  await language('en-US');
  expect(screen.getByText('Model:')).toBeInTheDocument();
  const badge = screen.getByText('Human action');
  expect(badge.textContent).toBe('Human action');
  expect(screen.getByText(authored)).toBeInTheDocument();
  await language('zh-CN');
  expect(screen.getByText('需要人工').querySelector('strong')).toBeNull();
});
it('rerenders a generic log load failure in the current locale without restarting its request', async () => {
  const load = vi.spyOn(workitemApi, 'getRuntimeLog').mockRejectedValue(null);
  route(<RuntimeLogDrawer dispatchId={44} onClose={() => {}} />);
  await screen.findByText('日志加载失败');
  await language('en-US');
  expect(screen.getByText('Could not load logs')).toBeInTheDocument();
  expect(load).toHaveBeenCalledTimes(1);
});
it('rerenders a generic trace load failure without restarting the trace request', async () => {
  const load = vi.spyOn(workitemApi, 'getRuntimeTrace').mockRejectedValue(null);
  route(<RuntimeTraceDrawer node={{ key: 'n1', dispatchId: 44, agentName: authored, status: 'SUCCEEDED' }} processGraph={{ nodes: [], edges: [] }} onClose={() => {}} />);
  await screen.findByText('Trace 加载失败');
  await language('en-US');
  expect(screen.getByText('Could not load trace')).toBeInTheDocument();
  expect(load).toHaveBeenCalledTimes(1);
});
it.each([
  { status: 'FAILED', chinese: 'Dispatch 失败原因', english: 'Dispatch failure reason' },
  { status: 'SUCCEEDED', chinese: 'Runtime 尚未上报 Session Trace', english: 'The runtime has not reported a session trace yet' },
])('switches the $status trace state between Chinese and English in the same mounted drawer', async ({ status, chinese, english }) => {
  let requests = 0;
  const rawReason = 'tool_hook_blocked: 原始 <reason> diagnostic';
  server.use(http.get('/api/dispatches/44/runtime-trace', () => {
    requests += 1;
    return ok({
      schemaVersion: 'autowonder.runtime-trace.v2', dispatchId: 44, source: 'LIVE', provider: 'qoder',
      changed: true, lastSeq: 1, sessions: [],
      events: status === 'FAILED' ? [{ eventId: 'e-failed', eventType: 'step.failed', detail: { reason: rawReason } }] : [],
    });
  }));
  route(<RuntimeTraceDrawer node={{ key: 'n1', dispatchId: 44, agentName: authored, status }} processGraph={{ nodes: [], edges: [] }} onClose={() => {}} />);
  await screen.findByText('Trace 时间线');
  const drawer = screen.getByTestId('runtime-trace-drawer');
  expect(screen.getByText(chinese)).toBeInTheDocument();
  expect(screen.queryByText(english)).not.toBeInTheDocument();
  await language('en-US');
  expect(screen.getByText(english)).toBeInTheDocument();
  expect(screen.queryByText(chinese)).not.toBeInTheDocument();
  expect(screen.getByTestId('runtime-trace-drawer')).toBe(drawer);
  await language('zh-CN');
  expect(screen.getByText(chinese)).toBeInTheDocument();
  expect(screen.queryByText(english)).not.toBeInTheDocument();
  expect(screen.getByTestId('runtime-trace-drawer')).toBe(drawer);
  expect(requests).toBe(1);
  if (status === 'FAILED') {
    expect(within(screen.getByTestId('dispatch-failure-reason')).getByText(rawReason)).toBeInTheDocument();
  }
});

it('translates only explicitly owned cached substeps and missing human graph names, preserving raw collisions', async () => {
  const progress = { steps: [{ stepId: 1, name: authored, status: 'active', agentName: authored, executorName: null, error: null, durationMs: null, attempts: null, subSteps: [
    { name: '开始执行', status: 'done', fallbackCode: 'START_EXECUTION' },
    { name: '开始执行', status: 'done' },
    { name: '<b>unknown</b>', status: 'done', fallbackCode: 'NEW_CODE' },
  ] }], agents: [], processGraph: { nodes: [{ key: 'human:1', agentName: '真人', agentNameFallback: 'HUMAN', status: 'HUMAN' }, { key: 'human:2', agentName: '真人', status: 'HUMAN' }], edges: [] } } as Progress;
  progress.agents = [{ agentId: 1, agentName: authored, status: 'finished', durationMs: null, steps: progress.steps }];
  route(<DeliveryProgress progress={progress} />);
  fireEvent.click(screen.getByRole('button', { name: '查看执行详情' }));
  fireEvent.click(screen.getByText('执行记录'));
  await language('en-US');
  expect(screen.getByText('Start execution')).toBeInTheDocument();
  expect(screen.getByText('开始执行')).toBeInTheDocument();
  expect(screen.getByText('<b>unknown</b>')).toBeInTheDocument();
  expect(screen.getByText('Human')).toBeInTheDocument();
  expect(screen.getByText('真人')).toBeInTheDocument();
  expect(document.querySelector('b')).toBeNull();
});
