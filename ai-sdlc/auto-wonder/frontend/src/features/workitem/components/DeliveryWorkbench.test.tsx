import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { DeliveryProgress } from './DeliveryProgress';
import { i18n } from '@/shared/i18n';
import type { AgentDeliveryProgress, DeliveryProgress as Progress } from '@/shared/types/workitem';

vi.mock('./RuntimeLogDrawer', () => ({ RuntimeLogPanel: ({ dispatchId }: { dispatchId: number }) => <div data-testid="selected-terminal">{dispatchId}</div> }));
vi.mock('./LiveActivityPanel', () => ({ LiveActivityPanel: ({ dispatchId }: { dispatchId: number }) => <div data-testid="selected-activity">{dispatchId}</div> }));
vi.mock('./DispatchActivityFeed', () => ({ DispatchActivityFeed: () => null }));
const agent = (id: number, name: string, dispatches: number[], status: AgentDeliveryProgress['status']): AgentDeliveryProgress => ({
  agentId: id, agentName: name, status, durationMs: 1000,
  steps: [{ stepId: id, name: `${name} stage`, status: status === 'active' ? 'active' : 'done', agentName: name, executorName: null, subSteps: null, durationMs: 1000,
    attempts: dispatches.map(dispatchId => ({ dispatchId, agentName: name, executorName: null, startedAt: null, durationMs: 1000, status: status === 'active' ? 'RUNNING' : 'SUCCEEDED', canPause: status === 'active' })) }],
});
const initial = (): Progress => ({ steps: [], agents: [agent(1, 'PM', [10], 'finished'), agent(2, 'RD', [20, 40], 'active'), agent(3, 'QA', [30], 'finished')] });
const handedOff = (): Progress => ({ steps: [], agents: [agent(1, 'PM', [10], 'finished'), agent(2, 'RD', [20, 40], 'finished'), agent(3, 'QA', [30, 50], 'active')] });
const open = () => fireEvent.click(screen.getByRole('button', { name: '打开终端' }));
afterEach(async () => { await act(async () => { await i18n.changeLanguage('zh-CN'); }); });

describe('Delivery workbench', () => {
  it('binds recovery to the selected terminal and places restart rounds in execution history', async () => {
    render(<DeliveryProgress progress={initial()} renderRecovery={id => <div data-testid="selected-recovery">{id}</div>} restartHistory={<div>Restart records</div>} />);
    expect(screen.queryByTestId('selected-recovery')).not.toBeInTheDocument();
    open();
    const workbench = await screen.findByTestId('delivery-workbench');
    expect(within(workbench).getByTestId('selected-recovery')).toHaveTextContent('40');
    fireEvent.click(within(workbench).getByText('Dispatch 20 · 1秒'));
    expect(within(workbench).getByTestId('selected-terminal')).toHaveTextContent('20');
    expect(within(workbench).getByTestId('selected-recovery')).toHaveTextContent('20');
    fireEvent.click(within(workbench).getByRole('tab', { name: '全部执行记录（4）' }));
    expect(await within(workbench).findByText('Restart records')).toBeVisible();
  });
  it('shows step timings only inside the workbench and keeps historical execution timings separate', async () => {
    const progress = initial();
    progress.executions = [
      { dispatchId: 20, agentId: 2, agentName: 'RD', executorName: null, status: 'FAILED', startedAt: null, durationMs: 600_000,
        stepTimings: [{ stepId: 2, name: 'Old attempt', status: 'failed', durationMs: 600_000 }] },
      { dispatchId: 40, agentId: 2, agentName: 'RD', executorName: null, status: 'RUNNING', startedAt: null, durationMs: 60_000,
        stepTimings: [{ stepId: 2, name: 'Current attempt', status: 'active', durationMs: 60_000 }] },
    ];
    render(<DeliveryProgress progress={progress} />);
    expect(screen.queryByTestId('step-duration-chart')).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'SDLC 阶段耗时' }));
    const panel = await screen.findByRole('tabpanel', { name: 'SDLC 阶段耗时' });
    await waitFor(() => expect(within(panel).getByText('Current attempt')).toBeVisible());
    expect(within(panel).getByTestId('step-duration-chart')).toHaveTextContent('本次执行 #40');
    expect(within(panel).getByTestId('step-duration-chart')).toHaveTextContent('步骤累计 1分');
    // Pin the old dispatch through the existing execution trail, then inspect its timings.
    fireEvent.click(within(screen.getByTestId('delivery-workbench')).getByText('Dispatch 20 · 10分'));
    fireEvent.click(screen.getByRole('tab', { name: 'SDLC 阶段耗时' }));
    await waitFor(() => expect(within(panel).getByText('Old attempt')).toBeVisible());
    expect(within(panel).getByText('步骤累计 10分')).toBeVisible();
    expect(within(panel).queryByText('Current attempt')).not.toBeInTheDocument();
  });
  it('shows one current owner and orders handoffs by execution rather than agent order', () => {
    render(<DeliveryProgress progress={initial()} />);
    expect(screen.getByTestId('current-agent-name')).toHaveTextContent('RD');
    const entries = [...document.querySelectorAll('.delivery-workbench-entry-meta')].map(element => element.textContent);
    expect(entries).toEqual(['Dispatch 40 · 1秒', 'Dispatch 30 · 1秒', 'Dispatch 20 · 1秒']);
  });
  it('bounds a long compact trail while keeping every historical session selectable', async () => {
    render(<DeliveryProgress progress={{ steps: [], agents: [agent(1, 'RD', Array.from({ length: 20 }, (_, index) => index + 1), 'active')] }} />);
    const card = within(screen.getByTestId('delivery-progress-card'));
    expect(card.getAllByTestId('agent-progress-name')).toHaveLength(3);
    expect(card.getByText('Dispatch 20 · 1秒')).toBeInTheDocument();
    expect(card.queryByText('Dispatch 1 · 1秒')).not.toBeInTheDocument();
    fireEvent.click(card.getByRole('button', { name: '全部执行记录（20）' }));
    const history = await screen.findByRole('tabpanel', { name: '全部执行记录（20）' });
    expect(within(history).getAllByTestId('agent-progress-name')).toHaveLength(20);
    fireEvent.click(within(history).getByText('Dispatch 1 · 1秒'));
    expect(screen.getByTestId('selected-terminal')).toHaveTextContent('1');
    await act(async () => { await i18n.changeLanguage('en-US'); });
    expect(screen.getByRole('tab', { name: 'Execution history (20)' })).toBeInTheDocument();
    expect(screen.getByTestId('selected-terminal')).toHaveTextContent('1');
  });
  it('includes a comment dispatch missing from step attempts, with status tags and lifetime timestamps', async () => {
    const progress = initial();
    progress.agents![1].status = 'failed';
    progress.agents![1].steps[0].attempts!.forEach(attempt => { attempt.status = 'SUCCEEDED'; attempt.canPause = false; });
    progress.executions = [{ dispatchId: 11280, agentId: 2, agentName: 'RD', executorName: null, status: 'FAILED', resumeMode: 'CANONICAL_INTERACTION',
      error: 'raw <error> 中文', startedAt: '2026-10-09T02:00:00Z', endedAt: '2026-10-09T02:01:00Z', durationMs: 60000 }];
    render(<DeliveryProgress progress={progress} />);
    const card = screen.getByTestId('delivery-progress-card');
    expect(within(card).getByText('Dispatch 11280 · 1分')).toBeInTheDocument();
    const latest = card.querySelector('.delivery-workbench-entry')!;
    expect(latest.querySelector('.ant-tag')).toHaveTextContent('失败');
    open();
    expect(screen.getByTestId('selected-terminal')).toHaveTextContent('11280');
    fireEvent.click(screen.getByRole('tab', { name: '执行详情' }));
    expect(screen.getByText('raw <error> 中文', { selector: 'pre' })).toBeInTheDocument();
    expect(screen.queryByTestId('delivery-step-2')).not.toBeInTheDocument();
    expect(screen.getAllByText(/派发时间：/).length).toBeGreaterThan(0);
    expect(screen.getAllByText(/结束时间：/).length).toBeGreaterThan(0);
    await act(async () => { await i18n.changeLanguage('en-US'); });
    expect(screen.getAllByText(/Dispatched:/).length).toBeGreaterThan(0);
    expect(screen.getByText('raw <error> 中文', { selector: 'pre' })).toBeInTheDocument();
  });
  it('shows an interaction without an SDLC agent and does not invent an end time for running work', () => {
    render(<DeliveryProgress progress={{ steps: [], agents: [], executions: [{ dispatchId: 11281, agentId: 9, agentName: 'Comment agent', executorName: null, status: 'RUNNING', startedAt: null, durationMs: null }] }} />);
    expect(screen.getByTestId('current-agent-name')).toHaveTextContent('Comment agent');
    open();
    expect(screen.getByTestId('selected-terminal')).toHaveTextContent('11281');
    expect(screen.getAllByText('结束时间：尚未结束').length).toBeGreaterThan(0);
    expect(screen.getAllByText('派发时间：未记录').length).toBeGreaterThan(0);
  });
  it('uses the latest comment execution for an existing finished agent', async () => {
    const progress: Progress = { steps: [], agents: [agent(1, 'PD', [11592], 'finished')], executions: [
      { dispatchId: 11592, agentId: 1, agentName: 'PD', executorName: null, status: 'SUCCEEDED', startedAt: null, durationMs: 1000 },
      { dispatchId: 11593, agentId: 1, agentName: 'PD', executorName: null, status: 'RUNNING', startedAt: null, durationMs: null },
    ] };
    const { rerender } = render(<DeliveryProgress progress={progress} />);
    expect(screen.getByTestId('current-agent-name')).toHaveTextContent('PD');
    open();
    expect(screen.getByTestId('selected-terminal')).toHaveTextContent('11593');
    await act(async () => { await i18n.changeLanguage('en-US'); });
    expect(screen.getByTestId('current-agent-name')).toHaveTextContent('PD');
    expect(screen.getAllByText('Running').length).toBeGreaterThan(0);
    rerender(<DeliveryProgress progress={progress} terminalStatus="SUCCEEDED" />);
    expect(screen.getByTestId('current-agent-name')).toHaveTextContent('No agent is currently executing');
  });
  it('opens the current terminal directly and follows a handoff', async () => {
    const { rerender } = render(<DeliveryProgress progress={initial()} />);
    open();
    expect(screen.getByTestId('selected-terminal')).toHaveTextContent('40');
    rerender(<DeliveryProgress progress={handedOff()} />);
    expect(screen.getByTestId('current-agent-name')).toHaveTextContent('QA');
    expect(screen.getByTestId('selected-terminal')).toHaveTextContent('50');
    await waitFor(() => expect(screen.getByText('已交接给 QA，终端已跟随切换。')).toBeVisible());
  });
  it('keeps a pinned historical session during handoff and returns explicitly to current', async () => {
    const { rerender } = render(<DeliveryProgress progress={initial()} />);
    open();
    const workbench = within(screen.getByTestId('delivery-workbench'));
    fireEvent.click(workbench.getByRole('tab', { name: '全部执行记录（4）' }));
    fireEvent.click(workbench.getByText('PM', { selector: '[data-testid="agent-progress-name"]' }));
    expect(screen.getByTestId('selected-terminal')).toHaveTextContent('10');
    rerender(<DeliveryProgress progress={handedOff()} />);
    expect(screen.getByTestId('current-agent-name')).toHaveTextContent('QA');
    expect(screen.getByTestId('selected-terminal')).toHaveTextContent('10');
    await waitFor(() => expect(screen.getByText('已交接给 QA，当前保留所查看的历史会话。')).toBeVisible());
    fireEvent.click(screen.getByRole('button', { name: '回到当前执行' }));
    expect(screen.getByTestId('selected-terminal')).toHaveTextContent('50');
  });
  it('retains the selected session and sidebar setting after exit and locale changes', async () => {
    render(<DeliveryProgress progress={initial()} />);
    open();
    fireEvent.click(screen.getByRole('tab', { name: '全部执行记录（4）' }));
    fireEvent.click(within(screen.getByTestId('delivery-workbench')).getByText('PM', { selector: '[data-testid="agent-progress-name"]' }));
    fireEvent.click(screen.getByRole('button', { name: '收起侧栏' }));
    fireEvent.click(screen.getByRole('button', { name: '退出放大' }));
    open();
    expect(screen.getByTestId('selected-terminal')).toHaveTextContent('10');
    await act(async () => { await i18n.changeLanguage('en-US'); });
    await waitFor(() => expect(screen.getByRole('button', { name: 'Show sidebar' })).toBeVisible());
    await waitFor(() => expect(screen.getByRole('button', { name: 'Return to current execution' })).toBeVisible());
    expect(screen.getByTestId('current-agent-name')).toHaveTextContent('RD');
  });
  it('keeps stages compact and opens the selected stage in the shared details panel', async () => {
    render(<DeliveryProgress progress={initial()} />);
    expect(screen.queryByTestId('delivery-step-2')).not.toBeInTheDocument();
    open();
    fireEvent.click(screen.getByText('RD stage', { selector: '.delivery-workbench-stage span' }));
    expect(screen.getByRole('tab', { name: '执行详情' })).toHaveAttribute('aria-selected', 'true');
    await waitFor(() => expect(screen.getByTestId('delivery-step-2')).toBeVisible());
  });
  it('targets the current pause action instead of an older failed retry', () => {
    const progress = initial();
    const rd = progress.agents![1];
    rd.steps[0].attempts![0] = { ...rd.steps[0].attempts![0], canPause: false, canContinue: true, status: 'FAILED' };
    rd.steps[0].attempts![1].status = 'PAUSING';
    const onPause = vi.fn();
    render(<DeliveryProgress progress={progress} onPause={onPause} onContinue={vi.fn()} />);
    expect(screen.queryByRole('button', { name: '继续执行' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: '暂停中' })).toBeDisabled();
    expect(screen.getAllByText(/暂停中/).length).toBeGreaterThan(0);
  });
  it('does not claim live work after terminal convergence and keeps old sessions reachable', () => {
    render(<DeliveryProgress progress={initial()} terminalStatus="SUCCEEDED" />);
    expect(screen.getByTestId('current-agent-name')).toHaveTextContent('当前没有数字人执行');
    open();
    expect(screen.getByTestId('selected-terminal')).toHaveTextContent('40');
  });
  it('handles an active agent before a dispatch session is created', async () => {
    render(<DeliveryProgress progress={{ steps: [], agents: [agent(1, 'RD', [], 'active')] }} />);
    expect(screen.getByTestId('current-agent-name')).toHaveTextContent('RD');
    open();
    await waitFor(() => expect(screen.getByText('暂无执行会话')).toBeVisible());
  });

  it.each([
    ['zh-CN', 'PENDING', 'NO_EXECUTOR_CAPACITY', '等待执行器容量'],
    ['en-US', 'PENDING', 'NO_EXECUTOR_CAPACITY', 'Waiting for executor capacity'],
    ['zh-CN', 'PACKAGING', null, '准备执行上下文'],
    ['zh-CN', 'DISPATCHED', null, '等待接单'],
    ['zh-CN', 'ACKED', null, '已接单'],
    ['zh-CN', 'RUNNING', null, '执行中'],
  ])('shows the actual dispatch phase in %s: %s', async (locale, status, waitingReason, label) => {
    await i18n.changeLanguage(locale!);
    const worker = agent(2, 'RD', [11761], 'active');
    worker.steps[0].attempts![0].status = status;
    worker.steps[0].attempts![0].waitingReason = waitingReason;
    worker.steps[0].executionStatus = status;
    render(<DeliveryProgress progress={{ steps: [], agents: [worker], executions: [{ ...worker.steps[0].attempts![0], agentId: 2 }] }} />);
    expect(screen.getAllByText(label!).length).toBeGreaterThan(0);
    if (status !== 'RUNNING') expect(screen.queryByText(locale === 'en-US' ? 'Running' : '执行中')).not.toBeInTheDocument();
  });
  it('keeps prior gate failures in historical dispatch details while the new handoff is queued', () => {
    const raw = 'step "10083" gate: checklist not fully checked: "cl_2" remains pending; Artifact publication requires repair: code=ARTIFACT_REFERENCE_INVALID path="evidence/step-10083-route-conflict-evidence-r3.md" class="" sizeBytes=0 limitBytes=0 action=reference an existing regular file';
    const worker = agent(2, 'RD', [11664,11761], 'active');
    worker.steps[0].attempts![0].status = 'FAILED'; worker.steps[0].attempts![0].error = raw;
    worker.steps[0].attempts![1].status = 'PENDING'; worker.steps[0].attempts![1].waitingReason = 'NO_EXECUTOR_CAPACITY';
    worker.steps[0].executionStatus = 'PENDING'; worker.steps[0].waitingReason = 'NO_EXECUTOR_CAPACITY';
    render(<DeliveryProgress progress={{ steps: [], agents: [worker] }} />);
    open(); fireEvent.click(screen.getByRole('tab', { name: '执行详情' }));
    expect(screen.queryByText(raw, { selector: 'pre' })).not.toBeInTheDocument();
    fireEvent.click(screen.getAllByRole('button').find(button => button.textContent?.includes('Dispatch 11664'))!);
    fireEvent.click(screen.getByRole('tab', { name: '执行详情' }));
    expect(screen.getByText('dispatch 11664 · 执行失败')).toBeInTheDocument();
    expect(screen.getByText('检查项 cl_2 尚未完成。')).toBeInTheDocument();
    expect(screen.getByText(raw, { selector: 'pre' })).toBeInTheDocument();
  });

  it('uses the selected dispatch stage snapshot for both the details and timing views', async () => {
    const failure = 'step "10131" gate: checklist not fully checked: "statistics" remains pending';
    const progress: Progress = {
      steps: [],
      agents: [{
        agentId: 10033, agentName: '任务蒸馏数字员工', status: 'failed', durationMs: 10_000,
        steps: [
          { stepId: 10088, name: '收集工单执行日志', status: 'done', agentName: '任务蒸馏数字员工', executorName: null, subSteps: null, durationMs: 3000,
            attempts: [{ dispatchId: 12238, agentName: '任务蒸馏数字员工', executorName: null, status: 'FAILED', error: failure, startedAt: null, durationMs: 10_000 }] },
          { stepId: 10131, name: '分析日志、总结报告并提交 CR', status: 'failed', agentName: '任务蒸馏数字员工', executorName: null, subSteps: null, durationMs: null, attempts: [] },
        ],
      }],
      executions: [{
        dispatchId: 12238, agentId: 10033, agentName: '任务蒸馏数字员工', executorName: null, status: 'FAILED',
        error: failure, startedAt: null, durationMs: 10_000,
        stepTimings: [
          { stepId: 10088, name: '收集工单执行日志', status: 'done', durationMs: 3000 },
          { stepId: 10131, name: '分析日志、总结报告并提交 CR', status: 'failed', durationMs: 4000 },
        ],
      }],
    };
    render(<DeliveryProgress progress={progress} />);
    open();
    fireEvent.click(screen.getByRole('tab', { name: '执行详情' }));
    const details = screen.getByRole('tabpanel', { name: '执行详情' });
    expect(within(within(details).getByTestId('delivery-step-10088')).getByText(/已完成/)).toBeInTheDocument();
    const analyze = within(details).getByTestId('delivery-step-10131');
    expect(within(analyze).getByText(/失败/)).toBeInTheDocument();
    expect(within(analyze).getByText(/4秒/)).toBeInTheDocument();
    expect(within(details).getByText(failure, { selector: 'pre' })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('tab', { name: 'SDLC 阶段耗时' }));
    const timing = screen.getByRole('tabpanel', { name: 'SDLC 阶段耗时' });
    await waitFor(() => expect(within(timing).getByTestId('step-duration-chart')).toHaveTextContent('本次执行 #12238'));
    await waitFor(() => expect(within(timing).getByText('步骤累计 7秒')).toBeVisible());
    expect(within(timing).getByText('收集工单执行日志')).toBeVisible();
    expect(within(timing).getByText('分析日志、总结报告并提交 CR')).toBeVisible();
    expect(within(timing).getByText('4秒')).toBeVisible();
  });

  it('keeps each view on the selected dispatch stage snapshot when switching executions', async () => {
    const progress: Progress = {
      steps: [],
      agents: [{
        agentId: 10033, agentName: '任务蒸馏数字员工', status: 'active', durationMs: 30_000,
        steps: [
          { stepId: 10088, name: '收集工单执行日志', status: 'done', agentName: '任务蒸馏数字员工', executorName: null, subSteps: null, durationMs: 3000,
            attempts: [
              { dispatchId: 12238, agentName: '任务蒸馏数字员工', executorName: null, status: 'FAILED', startedAt: null, durationMs: 10_000 },
              { dispatchId: 12255, agentName: '任务蒸馏数字员工', executorName: null, status: 'RUNNING', startedAt: null, durationMs: 20_000, canPause: true },
            ] },
        ],
      }],
      executions: [
        { dispatchId: 12238, agentId: 10033, agentName: '任务蒸馏数字员工', executorName: null, status: 'FAILED', error: 'first run failed on gate', startedAt: null, durationMs: 10_000,
          stepTimings: [
            { stepId: 10088, name: '收集工单执行日志', status: 'done', durationMs: 3000 },
            { stepId: 10131, name: '分析日志、总结报告并提交 CR', status: 'failed', durationMs: 4000 },
          ] },
        { dispatchId: 12255, agentId: 10033, agentName: '任务蒸馏数字员工', executorName: null, status: 'RUNNING', startedAt: null, durationMs: 20_000,
          stepTimings: [
            { stepId: 10088, name: '收集工单执行日志', status: 'done', durationMs: 6000 },
            { stepId: 10131, name: '分析日志、总结报告并提交 CR', status: 'active', durationMs: 20_000 },
          ] },
      ],
    };
    render(<DeliveryProgress progress={progress} />);
    open();
    const workbench = within(screen.getByTestId('delivery-workbench'));
    expect(screen.getByTestId('selected-terminal')).toHaveTextContent('12255');
    fireEvent.click(screen.getByRole('tab', { name: 'SDLC 阶段耗时' }));
    let timing = screen.getByRole('tabpanel', { name: 'SDLC 阶段耗时' });
    await waitFor(() => expect(within(timing).getByTestId('step-duration-chart')).toHaveTextContent('本次执行 #12255'));
    await waitFor(() => expect(within(timing).getByText('步骤累计 26秒')).toBeVisible());
    fireEvent.click(workbench.getByText('Dispatch 12238 · 10秒'));
    fireEvent.click(screen.getByRole('tab', { name: 'SDLC 阶段耗时' }));
    timing = screen.getByRole('tabpanel', { name: 'SDLC 阶段耗时' });
    await waitFor(() => expect(within(timing).getByTestId('step-duration-chart')).toHaveTextContent('本次执行 #12238'));
    await waitFor(() => expect(within(timing).getByText('步骤累计 7秒')).toBeVisible());
    expect(within(timing).queryByText('步骤累计 26秒')).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('tab', { name: '执行详情' }));
    const details = screen.getByRole('tabpanel', { name: '执行详情' });
    expect(within(details).getByText('first run failed on gate', { selector: 'pre' })).toBeInTheDocument();
    const analyze = within(details).getByTestId('delivery-step-10131');
    expect(within(analyze).getByText(/失败/)).toBeInTheDocument();
    // 12255 的 active 阶段不得出现在 12238 的详情中
    expect(within(details).queryByText(/执行中/)).not.toBeInTheDocument();
  });

  it('keeps the attempt-derived stages when a dispatch has no stage snapshot', () => {
    const progress: Progress = {
      steps: [],
      agents: [{
        agentId: 10033, agentName: '任务蒸馏数字员工', status: 'failed', durationMs: 10_000,
        steps: [
          { stepId: 10088, name: '收集工单执行日志', status: 'done', agentName: '任务蒸馏数字员工', executorName: null, subSteps: null, durationMs: 3000,
            attempts: [{ dispatchId: 12238, agentName: '任务蒸馏数字员工', executorName: null, status: 'FAILED', startedAt: null, durationMs: 10_000 }] },
          { stepId: 10131, name: '分析日志、总结报告并提交 CR', status: 'failed', agentName: '任务蒸馏数字员工', executorName: null, subSteps: null, durationMs: null, attempts: [] },
        ],
      }],
      executions: [{ dispatchId: 12238, agentId: 10033, agentName: '任务蒸馏数字员工', executorName: null, status: 'FAILED', startedAt: null, durationMs: 10_000 }],
    };
    render(<DeliveryProgress progress={progress} />);
    open();
    fireEvent.click(screen.getByRole('tab', { name: '执行详情' }));
    const details = screen.getByRole('tabpanel', { name: '执行详情' });
    expect(within(details).getByTestId('delivery-step-10088')).toBeInTheDocument();
    expect(within(details).queryByTestId('delivery-step-10131')).not.toBeInTheDocument();
  });

  it('renders a stage that ended without a completion record explicitly', async () => {
    const progress: Progress = {
      steps: [],
      agents: [{
        agentId: 10033, agentName: '任务蒸馏数字员工', status: 'finished', durationMs: 10_000,
        steps: [
          { stepId: 10088, name: '收集工单执行日志', status: 'done', agentName: '任务蒸馏数字员工', executorName: null, subSteps: null, durationMs: 3000,
            attempts: [{ dispatchId: 12238, agentName: '任务蒸馏数字员工', executorName: null, status: 'SUCCEEDED', startedAt: null, durationMs: 10_000 }] },
        ],
      }],
      executions: [{
        dispatchId: 12238, agentId: 10033, agentName: '任务蒸馏数字员工', executorName: null, status: 'SUCCEEDED', startedAt: null, durationMs: 10_000,
        stepTimings: [
          { stepId: 10088, name: '收集工单执行日志', status: 'done', durationMs: 3000 },
          { stepId: 10131, name: '分析日志、总结报告并提交 CR', status: 'interrupted', durationMs: 2000 },
        ],
      }],
    };
    render(<DeliveryProgress progress={progress} />);
    open();
    fireEvent.click(screen.getByRole('tab', { name: '执行详情' }));
    const details = screen.getByRole('tabpanel', { name: '执行详情' });
    const interrupted = within(details).getByTestId('delivery-step-10131');
    expect(within(interrupted).getByText(/已结束，完成记录缺失/)).toBeInTheDocument();
    expect(within(interrupted).getByText(/2秒/)).toBeInTheDocument();
    await act(async () => { await i18n.changeLanguage('en-US'); });
    expect(within(details).getByText(/Ended without a completion record/)).toBeInTheDocument();
  });
});
