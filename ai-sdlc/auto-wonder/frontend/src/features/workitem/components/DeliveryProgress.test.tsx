import { describe, it, expect, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { DeliveryProgress, formatDuration, convergeStepsForTerminalStatus } from './DeliveryProgress';
import type { Artifact, DeliveryProgress as DeliveryProgressModel, DeliveryStep } from '@/shared/types/workitem';

describe('formatDuration', () => {
  it('returns empty string for null', () => {
    expect(formatDuration(null)).toBe('');
    expect(formatDuration(undefined)).toBe('');
  });

  it('formats sub-minute durations as seconds', () => {
    expect(formatDuration(30_000)).toBe('30秒');
  });

  it('formats minute durations', () => {
    expect(formatDuration(161_000)).toBe('2分41秒');
    expect(formatDuration(120_000)).toBe('2分');
  });

  it('formats hour durations', () => {
    expect(formatDuration(3_600_000)).toBe('1时');
    expect(formatDuration(3_960_000)).toBe('1时6分');
  });
});

describe('DeliveryProgress', () => {
  it('renders the latest workflow plan separately from live execution status', () => {
    const progress: DeliveryProgressModel = {
      steps: [],
      workflowPlan: {
        revision: 2,
        agentId: 41,
        agentName: '开发 Dev',
        targetStepId: 'coding',
        reason: '实现方式发生变化',
        sourceGuidanceIds: [184],
        steps: [
          { stepKey: 'analysis', name: '需求分析', planStatus: 'REUSED', sourceAttempt: 1 },
          { stepKey: 'coding', name: '编码实现', planStatus: 'RUN', sourceAttempt: 2 },
          { stepKey: 'review', name: '代码评审', planStatus: 'RUN', sourceAttempt: 1 },
          { stepKey: 'deploy', name: '部署', planStatus: 'SKIPPED', sourceAttempt: 0 },
        ],
      },
      agents: [{
        agentId: 41,
        agentName: '开发 Dev',
        status: 'active',
        durationMs: 30_000,
        currentActivity: '正在分析评论和当前上下文',
        steps: [{
          stepId: 102,
          stepKey: 'coding',
          name: '编码实现',
          status: 'active',
          planStatus: 'RUN',
          sourceAttempt: 2,
          executorName: '开发 Dev',
          error: null,
          subSteps: null,
          durationMs: 30_000,
          attempts: null,
        }],
      }],
    };

    render(<DeliveryProgress progress={progress} />);

    const planCard = screen.getByTestId('workflow-plan-card');
    const progressCard = screen.getByTestId('delivery-progress-card');
    expect(within(planCard).getByText('本轮执行计划')).toBeInTheDocument();
    expect(within(progressCard).getByText('交付进度跟踪')).toBeInTheDocument();
    expect(screen.getByText('第 2 轮')).toBeInTheDocument();
    expect(screen.getByText(/原因：实现方式发生变化/)).toBeInTheDocument();
    expect(screen.getByText(/复用.*需求分析/)).toBeInTheDocument();
    expect(screen.getByText(/重跑.*编码实现.*代码评审/)).toBeInTheDocument();
    expect(screen.getByText(/跳过.*部署/)).toBeInTheDocument();
    expect(screen.getByText(/本轮重跑.*执行中/)).toBeInTheDocument();
    expect(screen.getByTestId('agent-current-activity')).toHaveTextContent('正在分析评论和当前上下文');
  });

  it('renders step duration next to status', () => {
    const steps: DeliveryStep[] = [
      {
        stepId: 20,
        name: '编码实现',
        status: 'done',
        executorName: 'worker',
        error: null,
        subSteps: null,
        durationMs: 30_000,
        attempts: null,
      },
    ];
    render(<DeliveryProgress steps={steps} />);
    expect(screen.getByText(/30秒/)).toBeInTheDocument();
  });

  it('keeps completed step execution records expandable after one successful attempt', () => {
    const steps: DeliveryStep[] = [
      {
        stepId: 10,
        name: '获取上下文与评论',
        status: 'done',
        executorName: 'AW代码评审工程师',
        error: null,
        subSteps: [
          { name: '读取工单上下文', status: 'done' },
          { name: '发布评审评论', status: 'done' },
        ],
        durationMs: 240_000,
        attempts: [{
          dispatchId: 30,
          executorName: 'AW代码评审工程师',
          status: 'SUCCEEDED',
          error: null,
          startedAt: null,
          durationMs: 240_000,
        }],
      },
    ];

    render(<DeliveryProgress steps={steps} />);

    const stepCard = screen.getByTestId('delivery-step-10');
    fireEvent.click(within(stepCard).getByText('执行记录'));

    expect(within(stepCard).getByText(/执行者: AW代码评审工程师/)).toBeInTheDocument();
    expect(within(stepCard).getByText(/第1次: AW代码评审工程师 · SUCCEEDED · 4分/)).toBeInTheDocument();
    expect(within(stepCard).getByText('读取工单上下文')).toBeInTheDocument();
    expect(within(stepCard).getByText('发布评审评论')).toBeInTheDocument();
  });

  it('keeps every completed step expandable inside a finished agent group', () => {
    const progress: DeliveryProgressModel = {
      steps: [],
      agents: [
        {
          agentId: 41,
          agentName: 'AW全栈开发',
          status: 'finished',
          durationMs: 600_000,
          steps: [
            {
              stepId: 101,
              name: '需求分析与评论',
              status: 'done',
              executorName: 'AW全栈开发',
              error: null,
              subSteps: [{ name: '发布分析评论', status: 'done' }],
              durationMs: 120_000,
              attempts: [
                { dispatchId: 301, executorName: 'AW全栈开发', status: 'SUCCEEDED', error: null, startedAt: null, durationMs: 120_000 },
              ],
            },
            {
              stepId: 102,
              name: '编码实现',
              status: 'done',
              executorName: 'AW全栈开发',
              error: null,
              subSteps: [{ name: '提交修复代码', status: 'done' }],
              durationMs: 480_000,
              attempts: [
                { dispatchId: 302, executorName: 'AW全栈开发', status: 'SUCCEEDED', error: null, startedAt: null, durationMs: 480_000 },
              ],
            },
          ],
        },
      ],
    };

    render(<DeliveryProgress progress={progress} />);

    expect(screen.getByTestId('delivery-step-101')).toBeInTheDocument();
    const secondStep = screen.getByTestId('delivery-step-102');
    fireEvent.click(within(secondStep).getByText('执行记录'));

    expect(within(secondStep).getByText(/第1次: AW全栈开发 · SUCCEEDED · 8分/)).toBeInTheDocument();
    expect(within(secondStep).getByText('提交修复代码')).toBeInTheDocument();
  });

  it('renders per-attempt durations when there are retries', () => {
    const steps: DeliveryStep[] = [
      {
        stepId: 20,
        name: '编码实现',
        status: 'active',
        executorName: 'worker',
        error: null,
        subSteps: null,
        durationMs: 30_000,
        attempts: [
          { dispatchId: 30, executorName: 'worker', status: 'FAILED', error: null, startedAt: null, durationMs: 161_000 },
          { dispatchId: 31, executorName: 'worker', status: 'PENDING', error: null, startedAt: null, durationMs: 30_000 },
        ],
      },
    ];
    render(<DeliveryProgress steps={steps} />);
    expect(screen.getByText(/第1次:/)).toBeInTheDocument();
    expect(screen.getByText(/2分41秒/)).toBeInTheDocument();
    expect(screen.getByText(/第2次:/)).toBeInTheDocument();
  });

  it('offers continue only for a resumable attempt', () => {
    const onContinue = vi.fn();
    const steps: DeliveryStep[] = [{
      stepId: 20,
      name: '编码实现',
      status: 'failed',
      executorName: 'worker',
      error: 'provider unavailable',
      subSteps: null,
      durationMs: 30_000,
      attempts: [{
        dispatchId: 30,
        executorName: 'worker',
        status: 'FAILED',
        error: 'provider unavailable',
        startedAt: null,
        durationMs: 30_000,
        canContinue: true,
      }],
    }];

    render(<DeliveryProgress steps={steps} onContinue={onContinue} />);
    fireEvent.click(screen.getByRole('button', { name: '继续' }));
    expect(onContinue).toHaveBeenCalledWith(30);
  });

  it('offers pause for the active pauseable attempt', () => {
    const onPause = vi.fn();
    const steps: DeliveryStep[] = [{
      stepId: 20,
      name: '编码实现',
      status: 'active',
      executorName: 'worker',
      error: null,
      subSteps: null,
      durationMs: 30_000,
      attempts: [{
        dispatchId: 30,
        executorName: 'worker',
        status: 'RUNNING',
        error: null,
        startedAt: null,
        durationMs: 30_000,
        canPause: true,
      }],
    }];

    render(<DeliveryProgress steps={steps} onPause={onPause} />);
    fireEvent.click(screen.getByRole('button', { name: /暂停/ }));
    expect(onPause).toHaveBeenCalledWith(30);
  });

  it('shows pausing state without offering retry while pause is in flight', () => {
    const onPause = vi.fn();
    const steps: DeliveryStep[] = [{
      stepId: 20,
      name: '编码实现',
      status: 'active',
      executorName: 'worker',
      error: null,
      subSteps: null,
      durationMs: 30_000,
      attempts: [{
        dispatchId: 30,
        executorName: 'worker',
        status: 'PAUSING',
        error: null,
        startedAt: null,
        durationMs: 30_000,
        canPause: true,
      }],
    }];

    render(<DeliveryProgress steps={steps} onPause={onPause} />);

    expect(screen.getByTestId('delivery-step-20')).toHaveTextContent('暂停中');
    expect(screen.getByTestId('delivery-step-20')).not.toHaveTextContent('执行中');
    expect(screen.queryByRole('button', { name: '重试暂停' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: '暂停中' })).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: '暂停中' }));
    expect(onPause).not.toHaveBeenCalled();
  });

  it('shows the worker as pausing while its active attempt is pausing', () => {
    const progress: DeliveryProgressModel = {
      steps: [],
      agents: [{
        agentId: 41,
        agentName: 'AW全栈开发',
        status: 'active',
        durationMs: 30_000,
        steps: [{
          stepId: 20,
          name: '编码实现',
          status: 'active',
          executorName: 'worker',
          error: null,
          subSteps: null,
          durationMs: 30_000,
          attempts: [{
            dispatchId: 30,
            executorName: 'worker',
            status: 'PAUSING',
            error: null,
            startedAt: null,
            durationMs: 30_000,
            canPause: true,
          }],
        }],
      }],
    };

    render(<DeliveryProgress progress={progress} onPause={vi.fn()} />);

    expect(screen.getByText('暂停中', { selector: '.ant-tag' })).toBeInTheDocument();
    expect(screen.queryByText('执行中', { selector: '.ant-tag' })).not.toBeInTheDocument();
  });

  it('offers retry pause for failed pause attempt', () => {
    const onPause = vi.fn();
    const steps: DeliveryStep[] = [{
      stepId: 20,
      name: '编码实现',
      status: 'failed',
      executorName: 'worker',
      error: null,
      subSteps: null,
      durationMs: 30_000,
      attempts: [{
        dispatchId: 30,
        executorName: 'worker',
        status: 'PAUSE_FAILED',
        error: null,
        startedAt: null,
        durationMs: 30_000,
        canPause: true,
      }],
    }];

    render(<DeliveryProgress steps={steps} onPause={onPause} />);
    fireEvent.click(screen.getByRole('button', { name: '重试暂停' }));
    expect(onPause).toHaveBeenCalledWith(30);
  });

  it('moves runnable attempt actions to the current step', () => {
    const onPause = vi.fn();
    const steps: DeliveryStep[] = [
      {
        stepId: 10,
        name: '需求分析',
        status: 'done',
        executorName: 'worker',
        error: null,
        subSteps: null,
        durationMs: 30_000,
        attempts: [{
          dispatchId: 30,
          executorName: 'worker',
          status: 'RUNNING',
          error: null,
          startedAt: null,
          durationMs: 30_000,
          canPause: true,
        }],
      },
      {
        stepId: 20,
        name: '编码实现',
        status: 'active',
        executorName: 'worker',
        error: null,
        subSteps: null,
        durationMs: 10_000,
        attempts: null,
      },
    ];

    render(<DeliveryProgress steps={steps} onPause={onPause} />);

    expect(within(screen.getByTestId('delivery-step-10')).queryByRole('button', { name: /暂停/ })).not.toBeInTheDocument();
    fireEvent.click(within(screen.getByTestId('delivery-step-20')).getByRole('button', { name: /暂停/ }));
    expect(onPause).toHaveBeenCalledWith(30);
  });

  it('does not move comment interaction pause actions to the current sdlc step', () => {
    const onPause = vi.fn();
    const steps: DeliveryStep[] = [
      {
        stepId: 10,
        name: '需求分析',
        status: 'done',
        executorName: 'worker',
        error: null,
        subSteps: null,
        durationMs: 30_000,
        attempts: [{
          dispatchId: 30,
          executorName: 'worker',
          status: 'RUNNING',
          resumeMode: 'SIDE_INTERACTION',
          error: null,
          startedAt: null,
          durationMs: 30_000,
          canPause: true,
        }],
      },
      {
        stepId: 20,
        name: '编码实现',
        status: 'active',
        executorName: 'worker',
        error: null,
        subSteps: null,
        durationMs: 10_000,
        attempts: null,
      },
    ];

    render(<DeliveryProgress steps={steps} onPause={onPause} />);

    expect(within(screen.getByTestId('delivery-step-20')).queryByRole('button', { name: /暂停/ })).not.toBeInTheDocument();
    fireEvent.click(within(screen.getByTestId('delivery-step-10')).getByRole('button', { name: /暂停/ }));
    expect(onPause).toHaveBeenCalledWith(30);
  });

  it('moves resumable attempt actions to the failed step', () => {
    const onContinue = vi.fn();
    const steps: DeliveryStep[] = [
      {
        stepId: 10,
        name: '需求分析',
        status: 'done',
        executorName: 'worker',
        error: null,
        subSteps: null,
        durationMs: 30_000,
        attempts: [{
          dispatchId: 30,
          executorName: 'worker',
          status: 'FAILED',
          error: 'provider unavailable',
          startedAt: null,
          durationMs: 30_000,
          canContinue: true,
        }],
      },
      {
        stepId: 20,
        name: '编码实现',
        status: 'failed',
        executorName: 'worker',
        error: 'provider unavailable',
        subSteps: null,
        durationMs: 10_000,
        attempts: null,
      },
    ];

    render(<DeliveryProgress steps={steps} onContinue={onContinue} />);

    expect(within(screen.getByTestId('delivery-step-10')).queryByRole('button', { name: /继续/ })).not.toBeInTheDocument();
    fireEvent.click(within(screen.getByTestId('delivery-step-20')).getByRole('button', { name: /继续/ }));
    expect(onContinue).toHaveBeenCalledWith(30);
  });

  it('keeps resumable pause actions on their original paused step', () => {
    const onContinue = vi.fn();
    const steps: DeliveryStep[] = [
      {
        stepId: 10,
        name: '需求分析',
        status: 'failed',
        executorName: 'worker',
        error: 'previous failure',
        subSteps: null,
        durationMs: 30_000,
        attempts: null,
      },
      {
        stepId: 20,
        name: '编码实现',
        status: 'paused',
        executorName: 'worker',
        error: null,
        subSteps: null,
        durationMs: 10_000,
        attempts: [{
          dispatchId: 40,
          executorName: 'worker',
          status: 'PAUSED',
          error: null,
          startedAt: null,
          durationMs: 10_000,
          canContinue: true,
        }],
      },
    ];

    render(<DeliveryProgress steps={steps} onContinue={onContinue} />);

    expect(within(screen.getByTestId('delivery-step-10')).queryByRole('button', { name: /继续/ })).not.toBeInTheDocument();
    fireEvent.click(within(screen.getByTestId('delivery-step-20')).getByRole('button', { name: /继续/ }));
    expect(onContinue).toHaveBeenCalledWith(40);
  });

  it('renders agent grouped progress with error and artifact ownership', () => {
    const progress: DeliveryProgressModel = {
      steps: [],
      agents: [
        {
          agentId: 41,
          agentName: 'Agent Dev',
          status: 'finished',
          durationMs: 120_000,
          steps: [
            {
              stepId: 101,
              name: '需求分析与建分支',
              status: 'done',
              executorName: 'Agent Dev',
              error: null,
              subSteps: null,
              durationMs: 120_000,
              attempts: [
                { dispatchId: 301, executorName: 'Agent Dev', status: 'SUCCEEDED', error: null, startedAt: null, durationMs: 120_000 },
              ],
            },
          ],
        },
        {
          agentId: 42,
          agentName: 'Agent CR',
          status: 'active',
          durationMs: 340_000,
          steps: [
            {
              stepId: 201,
              name: 'Code Review',
              status: 'failed',
              executorName: 'Agent CR',
              error: 'execute dispatch: load skills: skill name is required.',
              subSteps: null,
              durationMs: 40_000,
              attempts: [
                {
                  dispatchId: 302,
                  executorName: 'Agent CR',
                  status: 'FAILED',
                  error: 'execute dispatch: load skills: skill name is required.',
                  startedAt: null,
                  durationMs: 40_000,
                },
              ],
            },
            {
              stepId: 202,
              name: '修复意见',
              status: 'active',
              executorName: 'Agent CR',
              error: null,
              subSteps: null,
              durationMs: 300_000,
              attempts: [
                { dispatchId: 303, executorName: 'Agent CR', status: 'RUNNING', error: null, startedAt: null, durationMs: 300_000 },
              ],
            },
          ],
        },
      ],
    };
    const artifacts: Artifact[] = [
      { id: 1, workitemId: 100, dispatchId: 301, name: 'dev-summary.md', type: 'MARKDOWN', size: 1024, gmtCreate: '' },
      { id: 2, workitemId: 100, dispatchId: 301, name: 'screenshot.png', type: 'IMAGE', size: 2048, gmtCreate: '' },
    ];

    render(<DeliveryProgress progress={progress} artifacts={artifacts} />);

    expect(screen.getAllByText(/Agent Dev/).length).toBeGreaterThan(0);
    expect(screen.getAllByText(/Agent CR/).length).toBeGreaterThan(0);
    expect(screen.getAllByText(/执行中/).length).toBeGreaterThan(0);
    expect(screen.getByText(/Code Review/)).toBeInTheDocument();
    expect(screen.getAllByText(/skill name is required/).length).toBeGreaterThan(0);
    fireEvent.click(screen.getAllByTestId('agent-progress-name')[0]);
    expect(screen.getAllByText(/2 artifacts/).length).toBeGreaterThan(0);
    fireEvent.click(screen.getAllByText(/产物/)[0]);
    expect(screen.getByText('dev-summary.md')).toBeInTheDocument();
    expect(screen.getByText('screenshot.png')).toBeInTheDocument();
  });

  it('opens artifact previews from agent artifact lists', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('# Dev Summary\nPASS', { status: 200 })));
    server.use(http.get('/api/artifacts/1/download', () => HttpResponse.json({
      success: true, code: '0', message: '', traceId: null, data: 'https://oss.example/dev-summary.md',
    })));
    const progress: DeliveryProgressModel = {
      steps: [],
      agents: [{
        agentId: 41,
        agentName: 'Agent Dev',
        status: 'finished',
        durationMs: 120_000,
        steps: [{
          stepId: 101,
          name: '编码实现',
          status: 'done',
          executorName: 'Agent Dev',
          error: null,
          subSteps: null,
          durationMs: 120_000,
          attempts: [
            { dispatchId: 301, executorName: 'Agent Dev', status: 'SUCCEEDED', error: null, startedAt: null, durationMs: 120_000 },
          ],
        }],
      }],
    };
    const artifacts: Artifact[] = [
      { id: 1, workitemId: 100, dispatchId: 301, name: 'dev-summary.md', type: 'MARKDOWN', size: 1024, gmtCreate: '' },
    ];

    render(<DeliveryProgress progress={progress} artifacts={artifacts} />);

    fireEvent.click(screen.getByText(/产物/));
    fireEvent.click(screen.getByRole('button', { name: '预览产物 dev-summary.md' }));

    await waitFor(() => expect(fetch).toHaveBeenCalled());
    expect(vi.mocked(fetch).mock.calls[0][0]).toBe('/api/artifacts/1/preview');
    expect(await screen.findByRole('heading', { name: 'Dev Summary' })).toBeInTheDocument();
    expect(screen.getByText('PASS')).toBeInTheDocument();

    vi.unstubAllGlobals();
  });

  it('downloads agent artifacts without opening a popup tab', async () => {
    server.use(http.get('/api/artifacts/1/download', () => HttpResponse.json({
      success: true, code: '0', message: '', traceId: null, data: 'https://oss.example/dev-summary.md',
    })));
    const openSpy = vi.spyOn(window, 'open').mockImplementation(() => null);
    const anchorClickSpy = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
    const progress: DeliveryProgressModel = {
      steps: [],
      agents: [{
        agentId: 41,
        agentName: 'Agent Dev',
        status: 'finished',
        durationMs: 120_000,
        steps: [{
          stepId: 101,
          name: '编码实现',
          status: 'done',
          executorName: 'Agent Dev',
          error: null,
          subSteps: null,
          durationMs: 120_000,
          attempts: [
            { dispatchId: 301, executorName: 'Agent Dev', status: 'SUCCEEDED', error: null, startedAt: null, durationMs: 120_000 },
          ],
        }],
      }],
    };
    const artifacts: Artifact[] = [
      { id: 1, workitemId: 100, dispatchId: 301, name: 'dev-summary.md', type: 'MARKDOWN', size: 1024, gmtCreate: '' },
    ];

    render(<DeliveryProgress progress={progress} artifacts={artifacts} />);

    fireEvent.click(screen.getByText(/产物/));
    fireEvent.click(screen.getByRole('button', { name: '下载产物 dev-summary.md' }));

    await waitFor(() => expect(anchorClickSpy).toHaveBeenCalledTimes(1));
    expect(openSpy).not.toHaveBeenCalled();

    openSpy.mockRestore();
    anchorClickSpy.mockRestore();
  });

  it('renders an interactive execution process graph with every transition as an arrow', () => {
    server.use(http.get('/api/dispatches/305/runtime-trace', () => HttpResponse.json({
      success: true, code: '0', message: '', traceId: null,
      data: {
        dispatchId: 305, runtimeId: null, provider: null, changed: true, lastSeq: 0,
        tokenUsage: { inputTokens: 0, outputTokens: 0, reasoningTokens: 0, cacheReadTokens: 0, cacheWriteTokens: 0, totalTokens: 0 },
        events: [], sessions: [],
      },
    })));
    const progress: DeliveryProgressModel = {
      steps: [],
      agents: [
        {
          agentId: 41,
          agentName: '开发',
          status: 'finished',
          durationMs: 240_000,
          steps: [
            {
              stepId: 101,
              name: '开发首轮',
              status: 'done',
              executorName: '开发',
              error: null,
              subSteps: null,
              durationMs: 120_000,
              attempts: [
                { dispatchId: 301, executorName: '开发', status: 'SUCCEEDED', error: null, startedAt: '2026-07-01T10:00:00Z', durationMs: 120_000 },
              ],
            },
            {
              stepId: 102,
              name: '开发返工',
              status: 'done',
              executorName: '开发',
              error: null,
              subSteps: null,
              durationMs: 120_000,
              attempts: [
                { dispatchId: 303, executorName: '开发', status: 'SUCCEEDED', error: null, startedAt: '2026-07-01T10:20:00Z', durationMs: 120_000 },
              ],
            },
          ],
        },
        {
          agentId: 42,
          agentName: '评审',
          status: 'finished',
          durationMs: 180_000,
          steps: [
            {
              stepId: 201,
              name: '评审驳回',
              status: 'failed',
              executorName: '评审',
              error: '需要返工',
              subSteps: null,
              durationMs: 60_000,
              attempts: [
                { dispatchId: 302, executorName: '评审', status: 'FAILED', error: '需要返工', startedAt: '2026-07-01T10:10:00Z', durationMs: 60_000 },
              ],
            },
            {
              stepId: 202,
              name: '评审通过',
              status: 'done',
              executorName: '评审',
              error: null,
              subSteps: null,
              durationMs: 120_000,
              attempts: [
                { dispatchId: 304, executorName: '评审', status: 'SUCCEEDED', error: null, startedAt: '2026-07-01T10:30:00Z', durationMs: 120_000 },
              ],
            },
          ],
        },
        {
          agentId: 43,
          agentName: '测试',
          status: 'active',
          durationMs: 60_000,
          steps: [
            {
              stepId: 301,
              name: '测试验证',
              status: 'active',
              executorName: '测试',
              error: null,
              subSteps: null,
              durationMs: 60_000,
              attempts: [
                { dispatchId: 305, executorName: '测试', status: 'RUNNING', error: null, startedAt: '2026-07-01T10:40:00Z', durationMs: 60_000 },
              ],
            },
          ],
        },
      ],
      processGraph: {
        nodes: [
          { key: 'dispatch:301', dispatchId: 301, agentId: 41, agentName: '开发', stepName: '开发首轮', status: 'SUCCEEDED', durationMs: 120_000 },
          { key: 'dispatch:302', dispatchId: 302, agentId: 42, agentName: '评审', stepName: '评审驳回', status: 'FAILED', durationMs: 60_000, error: '需要返工' },
          { key: 'dispatch:303', dispatchId: 303, agentId: 41, agentName: '开发', stepName: '开发返工', status: 'SUCCEEDED', durationMs: 120_000, triggerCommentId: 11599 },
          { key: 'dispatch:304', dispatchId: 304, agentId: 42, agentName: '评审', stepName: '评审通过', status: 'SUCCEEDED', durationMs: 120_000 },
          { key: 'dispatch:305', dispatchId: 305, agentId: 43, agentName: '测试', stepName: '测试验证', status: 'RUNNING', durationMs: 60_000 },
        ],
        edges: [
          { sourceKey: 'dispatch:301', targetKey: 'dispatch:302', type: 'HANDOFF', sourceDispatchId: 301, targetDispatchId: 302, label: '交接' },
          { sourceKey: 'dispatch:302', targetKey: 'dispatch:303', type: 'COMMENT_REWORK', sourceDispatchId: 302, targetDispatchId: 303, commentId: 11599, label: '用户返工（评论 #11599）' },
          { sourceKey: 'dispatch:303', targetKey: 'dispatch:304', type: 'HANDOFF', sourceDispatchId: 303, targetDispatchId: 304, label: '交接' },
          { sourceKey: 'dispatch:304', targetKey: 'dispatch:305', type: 'HANDOFF', sourceDispatchId: 304, targetDispatchId: 305, label: '交接' },
        ],
      },
    };

    render(<DeliveryProgress progress={progress} />);

    const graph = screen.getByTestId('execution-process-graph');
    expect(within(graph).getAllByTestId('execution-graph-worker')).toHaveLength(5);
    expect(within(graph).getAllByTestId('execution-graph-edge')).toHaveLength(4);
    expect(within(graph).getByText('2. 评审 -> 开发 · 用户返工（评论 #11599）')).toBeInTheDocument();
    expect(within(graph).getAllByText('开发').length).toBeGreaterThan(0);
    expect(within(graph).getAllByText('评审').length).toBeGreaterThan(0);
    expect(within(graph).getAllByText('测试').length).toBeGreaterThan(0);
    expect(within(graph).getAllByText(/1分/).length).toBeGreaterThan(0);
    const activeWorker = within(graph)
      .getAllByTestId('execution-graph-worker')
      .find((worker) => worker.getAttribute('data-status') === 'RUNNING');
    expect(activeWorker).toHaveTextContent('测试');

    fireEvent.click(activeWorker!);
    expect(within(screen.getByTestId('execution-graph-detail-panel')).getByText('测试')).toBeInTheDocument();
  });

  it('opens a live runtime trace drawer for the selected dispatch slice', async () => {
    let requestedUrl = '';
    server.use(http.get('/api/dispatches/302/runtime-trace', ({ request }) => {
      requestedUrl = request.url;
      return HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: {
          dispatchId: 302, runtimeId: 'rt-1', provider: 'codex', changed: true, lastSeq: 15,
          tokenUsage: { available: true, inputTokens: 1700, outputTokens: 400, reasoningTokens: 80, cacheReadTokens: 400, cacheWriteTokens: 0, totalTokens: 2100 },
          events: [
            { eventId: '302:0', seq: 0, eventType: 'runtime.started', eventTime: '2026-07-30T10:00:00Z', detail: { runtimeId: 'rt-1' } },
            { eventId: '302:1', seq: 1, eventType: 'runtime.recovery_completed', eventTime: '2026-07-30T10:00:01Z', detail: { mode: 'checkpoint' } },
            { eventId: '302:2', seq: 2, eventType: 'step.started', eventTime: '2026-07-30T10:00:02Z', detail: { stepId: 'implementation' } },
            { eventId: '302:14', seq: 14, eventType: 'upload.completed', eventTime: '2026-07-30T10:01:05Z', detail: { status: 'ok' } },
          ],
          sessions: [{
            sessionId: 's1', parentSessionId: null, status: 'INTERRUPTED',
            startedAt: '2026-07-30T10:00:00Z', endedAt: '2026-07-30T10:01:06Z', durationMs: 10000,
            tokenUsage: { available: true, inputTokens: 1700, outputTokens: 400, reasoningTokens: 80, cacheReadTokens: 400, cacheWriteTokens: 0, totalTokens: 2100 },
            eventIds: [],
            boundaries: [
              { eventId: '302:1', kind: 'RESUMED', eventTime: '2026-07-30T10:00:00Z', label: 'RESUMED' },
              { eventId: '302:15', kind: 'INTERRUPTED', eventTime: '2026-07-30T10:01:06Z', label: 'INTERRUPTED · paused · checkpoint #18' },
            ],
            turns: [{
              turnId: 't2', stepId: 'implementation', stepName: 'Implementation', status: 'INTERRUPTED',
              startedAt: '2026-07-30T10:01:01Z', endedAt: '2026-07-30T10:01:05Z', durationMs: 4000,
              prompt: 'Run the implementation tests', systemPrompt: 'Follow the worker SDLC',
              tokenUsage: { available: true, inputTokens: 500, outputTokens: 100, reasoningTokens: 20, cacheReadTokens: 0, cacheWriteTokens: 0, totalTokens: 600 },
              eventIds: [],
              spans: [
                { spanId: 't2:llm', parentSpanId: null, kind: 'LLM', name: 'gpt-5', status: 'COMPLETED', startedAt: '2026-07-30T10:01:01Z', endedAt: '2026-07-30T10:01:04Z', durationMs: 3000, model: 'gpt-5', inputSummary: null, outputSummary: null, errorCategory: null, tokenUsage: { available: true, inputTokens: 500, outputTokens: 100, reasoningTokens: 20, cacheReadTokens: 0, cacheWriteTokens: 0, totalTokens: 600 }, eventIds: [] },
                { spanId: 'thinking-1', parentSpanId: 't2:llm', kind: 'THINKING', name: 'Thinking', status: 'COMPLETED', startedAt: '2026-07-30T10:01:01Z', endedAt: '2026-07-30T10:01:02Z', durationMs: 1000, model: 'gpt-5', inputSummary: null, outputSummary: null, errorCategory: null, tokenUsage: { available: false, inputTokens: 0, outputTokens: 0, reasoningTokens: 20, cacheReadTokens: 0, cacheWriteTokens: 0, totalTokens: 0 }, eventIds: [] },
                { spanId: 'bash-1', parentSpanId: 't2', kind: 'BASH', name: 'bash', status: 'COMPLETED', startedAt: '2026-07-30T10:01:02Z', endedAt: '2026-07-30T10:01:03Z', durationMs: 1000, model: null, inputSummary: 'pnpm test', outputSummary: '26 passed', input: { command: 'pnpm test' }, output: '26 passed', errorCategory: null, tokenUsage: { available: false, inputTokens: 0, outputTokens: 0, reasoningTokens: 0, cacheReadTokens: 0, cacheWriteTokens: 0, totalTokens: 0 }, eventIds: [] },
              ],
            }],
          }],
        },
      });
    }));
    const progress: DeliveryProgressModel = {
      steps: [],
      agents: [{
        agentId: 41,
        agentName: '开发',
        status: 'active',
        durationMs: 10000,
        steps: [{
          stepId: 101,
          name: 'Implementation',
          status: 'active',
          executorName: '开发',
          error: null,
          subSteps: null,
          durationMs: 10000,
          attempts: [
            { dispatchId: 301, executorName: '开发', status: 'PAUSED', error: null, startedAt: '2026-07-30T10:00:00Z', durationMs: 6000 },
            { dispatchId: 302, executorName: '开发', status: 'RUNNING', error: null, startedAt: '2026-07-30T10:01:00Z', durationMs: 4000 },
          ],
        }],
      }],
      processGraph: {
        nodes: [
          { key: 'dispatch:301', dispatchId: 301, agentId: 41, agentName: '开发', stepName: 'Implementation', status: 'PAUSED', durationMs: 6000 },
          { key: 'dispatch:302', dispatchId: 302, agentId: 41, agentName: '开发', stepName: 'Implementation', status: 'RUNNING', durationMs: 4000 },
        ],
        edges: [
          { sourceKey: 'dispatch:301', targetKey: 'dispatch:302', type: 'CONTINUE', sourceDispatchId: 301, targetDispatchId: 302, label: '恢复执行' },
        ],
      },
    };

    render(<DeliveryProgress progress={progress} />);
    fireEvent.click(screen.getAllByTestId('execution-graph-worker')[1]);

    const drawer = await screen.findByTestId('runtime-trace-drawer');
    await waitFor(() => expect(requestedUrl).toContain('/api/dispatches/302/runtime-trace'));
    expect(within(drawer).getByText(/Dispatch #302/)).toBeInTheDocument();
    expect(within(drawer).getByText(/恢复自 #301/)).toBeInTheDocument();
    expect(within(drawer).getByText(/Session s1/)).toBeInTheDocument();
    expect(within(drawer).getAllByText(/2,100 tokens/).length).toBeGreaterThan(0);
    expect(within(drawer).getByText(/Turn t2/)).toBeInTheDocument();
    expect(within(drawer).getByText(/Runtime & SDLC/)).toBeInTheDocument();
    expect(within(drawer).getByText('runtime.recovery_completed')).toBeInTheDocument();
    fireEvent.click(within(drawer).getByText(/Turn t2/));
    expect(within(drawer).getByText('User Prompt')).toBeInTheDocument();
    expect(within(drawer).getByText('System Prompt')).toBeInTheDocument();
    expect(within(drawer).getByText(/pnpm test/)).toBeInTheDocument();
    expect(within(drawer).getByText('Run the implementation tests')).toBeInTheDocument();
    expect(within(drawer).getByText('Follow the worker SDLC')).toBeInTheDocument();
    expect(within(drawer).queryByText(/Usage unavailable/)).not.toBeInTheDocument();
    expect(within(drawer).getByText(/checkpoint #18/)).toBeInTheDocument();
  });

  it('supports zoom, drag, reset, and fullscreen controls for the execution graph', () => {
    const progress: DeliveryProgressModel = {
      steps: [],
      agents: [
        {
          agentId: 41,
          agentName: '开发',
          status: 'finished',
          durationMs: 120_000,
          steps: [{
            stepId: 101,
            name: '开发',
            status: 'done',
            executorName: '开发',
            error: null,
            subSteps: null,
            durationMs: 120_000,
            attempts: [{ dispatchId: 301, executorName: '开发', status: 'SUCCEEDED', error: null, startedAt: '2026-07-01T10:00:00Z', durationMs: 120_000 }],
          }],
        },
        {
          agentId: 42,
          agentName: '评审',
          status: 'active',
          durationMs: 60_000,
          steps: [{
            stepId: 201,
            name: '评审',
            status: 'active',
            executorName: '评审',
            error: null,
            subSteps: null,
            durationMs: 60_000,
            attempts: [{ dispatchId: 302, executorName: '评审', status: 'RUNNING', error: null, startedAt: '2026-07-01T10:10:00Z', durationMs: 60_000 }],
          }],
        },
      ],
      processGraph: {
        nodes: [
          { key: 'dispatch:301', dispatchId: 301, agentId: 41, agentName: '开发', stepName: '开发', status: 'SUCCEEDED', durationMs: 120_000 },
          { key: 'dispatch:302', dispatchId: 302, agentId: 42, agentName: '评审', stepName: '评审', status: 'RUNNING', durationMs: 60_000 },
        ],
        edges: [
          { sourceKey: 'dispatch:301', targetKey: 'dispatch:302', type: 'HANDOFF', sourceDispatchId: 301, targetDispatchId: 302, label: '交接' },
        ],
      },
    };

    render(<DeliveryProgress progress={progress} />);

    const viewport = screen.getByTestId('execution-graph-viewport');
    expect(viewport).toHaveAttribute('data-zoom', '1.00');

    fireEvent.click(screen.getByLabelText('放大'));
    expect(viewport).toHaveAttribute('data-zoom', '1.12');

    fireEvent.wheel(viewport, { deltaY: -1 });
    expect(viewport).toHaveAttribute('data-zoom', '1.20');

    fireEvent.mouseDown(viewport, { clientX: 100, clientY: 100 });
    fireEvent.mouseMove(viewport, { clientX: 124, clientY: 116 });
    fireEvent.mouseUp(viewport, { clientX: 124, clientY: 116 });
    expect(viewport).toHaveAttribute('data-pan', '24,16');

    fireEvent.click(screen.getByLabelText('重置视图'));
    expect(viewport).toHaveAttribute('data-zoom', '1.00');
    expect(viewport).toHaveAttribute('data-pan', '0,0');

    fireEvent.click(screen.getByLabelText('全屏查看'));
    expect(screen.getByTestId('execution-graph-fullscreen')).toBeInTheDocument();
    fireEvent.click(screen.getByLabelText('退出全屏'));
    expect(screen.queryByTestId('execution-graph-fullscreen')).not.toBeInTheDocument();
  });

  it('does not infer execution graph edges from attempt timestamps or dispatch ids', () => {
    const progress: DeliveryProgressModel = {
      steps: [],
      agents: [
        {
          agentId: 41,
          agentName: '开发',
          status: 'finished',
          durationMs: 240_000,
          steps: [
            {
              stepId: 101,
              name: '开发首轮',
              status: 'done',
              executorName: '开发',
              error: null,
              subSteps: null,
              durationMs: 120_000,
              attempts: [
                { dispatchId: 301, executorName: '开发', status: 'SUCCEEDED', error: null, startedAt: null, durationMs: 120_000 },
              ],
            },
            {
              stepId: 102,
              name: '开发返工',
              status: 'done',
              executorName: '开发',
              error: null,
              subSteps: null,
              durationMs: 120_000,
              attempts: [
                { dispatchId: 303, executorName: '开发', status: 'SUCCEEDED', error: null, startedAt: null, durationMs: 120_000 },
              ],
            },
          ],
        },
        {
          agentId: 42,
          agentName: '评审',
          status: 'finished',
          durationMs: 180_000,
          steps: [
            {
              stepId: 201,
              name: '评审驳回',
              status: 'failed',
              executorName: '评审',
              error: '需要返工',
              subSteps: null,
              durationMs: 60_000,
              attempts: [
                { dispatchId: 302, executorName: '评审', status: 'FAILED', error: '需要返工', startedAt: null, durationMs: 60_000 },
              ],
            },
            {
              stepId: 202,
              name: '评审通过',
              status: 'done',
              executorName: '评审',
              error: null,
              subSteps: null,
              durationMs: 120_000,
              attempts: [
                { dispatchId: 304, executorName: '评审', status: 'SUCCEEDED', error: null, startedAt: null, durationMs: 120_000 },
              ],
            },
          ],
        },
        {
          agentId: 43,
          agentName: '测试',
          status: 'active',
          durationMs: 60_000,
          steps: [
            {
              stepId: 301,
              name: '测试验证',
              status: 'active',
              executorName: '测试',
              error: null,
              subSteps: null,
              durationMs: 60_000,
              attempts: [
                { dispatchId: 305, executorName: '测试', status: 'RUNNING', error: null, startedAt: null, durationMs: 60_000 },
              ],
            },
          ],
        },
      ],
    };

    render(<DeliveryProgress progress={progress} />);

    expect(screen.queryByTestId('execution-process-graph')).not.toBeInTheDocument();
  });

  it('renders execution graph when only nodes exist without edges (first worker just started)', () => {
    const progress: DeliveryProgressModel = {
      steps: [],
      agents: [
        {
          agentId: 41,
          agentName: '开发',
          status: 'active',
          durationMs: 30_000,
          steps: [
            {
              stepId: 101,
              name: '需求分析',
              status: 'active',
              executorName: '开发',
              error: null,
              subSteps: null,
              durationMs: 30_000,
              attempts: [
                { dispatchId: 301, executorName: '开发', status: 'RUNNING', error: null, startedAt: '2026-08-03T10:00:00Z', durationMs: 30_000 },
              ],
            },
          ],
        },
      ],
      processGraph: {
        nodes: [
          { key: 'dispatch:301', dispatchId: 301, agentId: 41, agentName: '开发', stepName: '需求分析', status: 'RUNNING', durationMs: 30_000 },
        ],
        edges: [],
      },
    };

    render(<DeliveryProgress progress={progress} />);

    const graph = screen.getByTestId('execution-process-graph');
    expect(within(graph).getAllByTestId('execution-graph-worker')).toHaveLength(1);
    expect(within(graph).queryAllByTestId('execution-graph-edge')).toHaveLength(0);
    expect(within(graph).getByText('开发')).toBeInTheDocument();
  });

  it('opens finished agents by default so completed step history is discoverable', () => {
    const progress: DeliveryProgressModel = {
      steps: [],
      agents: [
        {
          agentId: 41,
          agentName: 'AW代码评审工程师',
          status: 'finished',
          durationMs: 240_000,
          steps: [
            {
              stepId: 101,
              name: '获取上下文与评论',
              status: 'done',
              executorName: 'AW代码评审工程师',
              error: null,
              subSteps: null,
              durationMs: 240_000,
              attempts: [
                { dispatchId: 301, executorName: 'AW代码评审工程师', status: 'SUCCEEDED', error: null, startedAt: null, durationMs: 240_000 },
                { dispatchId: 302, executorName: 'AW代码评审工程师', status: 'SUCCEEDED', error: null, startedAt: null, durationMs: 240_000 },
              ],
            },
          ],
        },
      ],
    };

    render(<DeliveryProgress progress={progress} />);

    const progressCard = screen.getByTestId('delivery-progress-card');
    expect(within(progressCard).getByText('获取上下文与评论')).toBeInTheDocument();
    expect(within(progressCard).getByText('执行记录')).toBeInTheDocument();

    fireEvent.click(within(progressCard).getByText('执行记录'));

    expect(within(progressCard).getByText(/第1次: AW代码评审工程师 · SUCCEEDED · 4分/)).toBeInTheDocument();
    expect(within(progressCard).getByText(/第2次: AW代码评审工程师 · SUCCEEDED · 4分/)).toBeInTheDocument();
  });

  it('keeps long agent names horizontal and collapses artifact files by default', () => {
    const progress: DeliveryProgressModel = {
      steps: [],
      agents: [
        {
          agentId: 41,
          agentName: 'AutoWonder前后端1号开发',
          status: 'finished',
          durationMs: 120_000,
          steps: [
            {
              stepId: 101,
              name: '需求分析与建分支',
              status: 'done',
              executorName: 'AutoWonder前后端1号开发',
              error: null,
              subSteps: null,
              durationMs: 120_000,
              attempts: [
                { dispatchId: 301, executorName: 'AutoWonder前后端1号开发', status: 'SUCCEEDED', error: null, startedAt: null, durationMs: 120_000 },
              ],
            },
          ],
        },
      ],
    };
    const artifacts: Artifact[] = Array.from({ length: 6 }, (_, index) => ({
      id: index + 1,
      workitemId: 100,
      dispatchId: 301,
      name: `artifact-${index + 1}.md`,
      type: 'MARKDOWN',
      size: 1024,
      gmtCreate: '',
    }));

    render(<DeliveryProgress progress={progress} artifacts={artifacts} />);

    const agentName = screen.getByTestId('agent-progress-name');
    expect(agentName).toHaveStyle({ whiteSpace: 'nowrap' });
    expect(screen.getAllByText(/6 artifacts/).length).toBeGreaterThan(0);
    expect(screen.queryByText('artifact-1.md')).not.toBeInTheDocument();

    fireEvent.click(screen.getByText(/产物/));

    expect(screen.getByText('artifact-1.md')).toBeInTheDocument();
  });

  it('renders each agent total duration in the panel header', () => {
    const progress: DeliveryProgressModel = {
      steps: [],
      agents: [{
        agentId: 1,
        agentName: 'AW全栈开发',
        status: 'finished',
        durationMs: 5_520_000,
        currentActivity: null,
        steps: [],
      }],
    };

    render(<DeliveryProgress progress={progress} />);

    expect(screen.getByText('1时32分')).toBeInTheDocument();
  });

  it('renders the workitem total duration in the card title', () => {
    const progress: DeliveryProgressModel = {
      steps: [{
        stepId: 101,
        stepKey: 'coding',
        name: '编码实现',
        status: 'done',
        executorName: null,
        error: null,
        subSteps: null,
        durationMs: null,
        attempts: null,
      }],
      totalDurationMs: 8_040_000,
    };

    render(<DeliveryProgress progress={progress} />);

    expect(screen.getByText(/总耗时/)).toBeInTheDocument();
    expect(screen.getByText('总耗时 2时14分 (Agents耗时)')).toBeInTheDocument();
  });

  it('omits the total duration from the card title when it is null', () => {
    const progress: DeliveryProgressModel = {
      steps: [{
        stepId: 101,
        stepKey: 'coding',
        name: '编码实现',
        status: 'done',
        executorName: null,
        error: null,
        subSteps: null,
        durationMs: null,
        attempts: null,
      }],
      totalDurationMs: null,
    };

    render(<DeliveryProgress progress={progress} />);

    expect(screen.queryByText(/总耗时/)).not.toBeInTheDocument();
  });
});

describe('convergeStepsForTerminalStatus', () => {
  const makeStep = (stepId: number, name: string, status: DeliveryStep['status']): DeliveryStep => ({
    stepId,
    name,
    status,
    executorName: null,
    error: null,
    subSteps: null,
    durationMs: null,
    attempts: null,
  });

  it('keeps statuses unchanged without a terminal status', () => {
    const steps = [makeStep(1, '步骤一', 'done'), makeStep(2, '步骤二', 'active'), makeStep(3, '步骤三', 'pending')];
    expect(convergeStepsForTerminalStatus(steps, null).map((step) => step.status)).toEqual(['done', 'active', 'pending']);
    expect(convergeStepsForTerminalStatus(steps, 'RUNNING').map((step) => step.status)).toEqual(['done', 'active', 'pending']);
  });

  it('converges active steps to done when the run succeeded', () => {
    const steps = [makeStep(1, '步骤一', 'done'), makeStep(2, '步骤二', 'active'), makeStep(3, '步骤三', 'active')];
    expect(convergeStepsForTerminalStatus(steps, 'SUCCEEDED').map((step) => step.status)).toEqual(['done', 'done', 'done']);
  });

  it('marks only the last active step failed and earlier active steps done when the run failed', () => {
    const steps = [makeStep(1, '步骤一', 'active'), makeStep(2, '步骤二', 'done'), makeStep(3, '步骤三', 'active')];
    expect(convergeStepsForTerminalStatus(steps, 'FAILED').map((step) => step.status)).toEqual(['done', 'done', 'failed']);
  });

  it('converges active steps to cancelled when the run was cancelled', () => {
    const steps = [makeStep(1, '步骤一', 'done'), makeStep(2, '步骤二', 'active')];
    expect(convergeStepsForTerminalStatus(steps, 'CANCELLED').map((step) => step.status)).toEqual(['done', 'cancelled']);
  });

  it('treats a closed workitem status as succeeded', () => {
    const steps = [makeStep(1, '步骤一', 'active')];
    expect(convergeStepsForTerminalStatus(steps, '已关闭').map((step) => step.status)).toEqual(['done']);
    expect(convergeStepsForTerminalStatus(steps, 'closed').map((step) => step.status)).toEqual(['done']);
  });

  it('converges active sub-steps together with their step', () => {
    const steps: DeliveryStep[] = [{
      ...makeStep(1, '步骤一', 'active'),
      subSteps: [{ name: '子步骤', status: 'active' }],
    }];
    expect(convergeStepsForTerminalStatus(steps, 'SUCCEEDED')[0].subSteps?.[0].status).toBe('done');
    expect(convergeStepsForTerminalStatus(steps, 'CANCELLED')[0].subSteps?.[0].status).toBe('cancelled');
    expect(convergeStepsForTerminalStatus(steps, 'FAILED')[0].subSteps?.[0].status).toBe('failed');
  });
});

describe('DeliveryProgress terminal convergence rendering', () => {
  const makeStep = (stepId: number, name: string, status: DeliveryStep['status']): DeliveryStep => ({
    stepId,
    name,
    status,
    executorName: null,
    error: null,
    subSteps: null,
    durationMs: null,
    attempts: null,
  });

  it('shows 已完成 instead of a spinning step when the run succeeded with an incomplete event stream', () => {
    render(<DeliveryProgress steps={[makeStep(1, '步骤一', 'done'), makeStep(2, '步骤二', 'active')]} terminalStatus="SUCCEEDED" />);
    expect(within(screen.getByTestId('delivery-step-2')).getByText('已完成')).toBeInTheDocument();
    expect(screen.queryByText('执行中')).not.toBeInTheDocument();
  });

  it('shows the last started step as failed and earlier ones completed when the run failed', () => {
    render(<DeliveryProgress steps={[makeStep(1, '步骤一', 'active'), makeStep(2, '步骤二', 'active')]} terminalStatus="FAILED" />);
    expect(within(screen.getByTestId('delivery-step-1')).getByText('已完成')).toBeInTheDocument();
    expect(within(screen.getByTestId('delivery-step-2')).getByText('失败')).toBeInTheDocument();
    expect(screen.queryByText('执行中')).not.toBeInTheDocument();
  });

  it('shows the running step as cancelled when the run was cancelled', () => {
    render(<DeliveryProgress steps={[makeStep(1, '步骤一', 'active')]} terminalStatus="CANCELLED" />);
    expect(within(screen.getByTestId('delivery-step-1')).getByText('已取消')).toBeInTheDocument();
    expect(screen.queryByText('执行中')).not.toBeInTheDocument();
  });

  it('still renders active steps as running for non-terminal run statuses', () => {
    render(<DeliveryProgress steps={[makeStep(1, '步骤一', 'active')]} terminalStatus="RUNNING" />);
    expect(within(screen.getByTestId('delivery-step-1')).getByText('执行中')).toBeInTheDocument();
  });

  it('converges agent steps inside the agent panel when the run reached a terminal state', () => {
    const progress: DeliveryProgressModel = {
      steps: [],
      agents: [{
        agentId: 41,
        agentName: '执行 Agent',
        status: 'active',
        durationMs: null,
        steps: [makeStep(5, '执行步骤', 'active')],
      }],
    };
    render(<DeliveryProgress progress={progress} terminalStatus="SUCCEEDED" />);
    const stepCard = screen.getByTestId('delivery-step-5');
    expect(within(stepCard).getByText('已完成')).toBeInTheDocument();
    expect(within(stepCard).queryByText('执行中')).not.toBeInTheDocument();
  });
});

describe('DeliveryProgress usage display', () => {
  it('keeps agent and step rows compact: token badges without credits or artifacts count', () => {
    const progress: DeliveryProgressModel = {
      steps: [],
      agents: [{
        agentId: 41,
        agentName: 'AW全栈开发',
        status: 'finished',
        durationMs: 1_050_000,
        usage: { model: 'auto', inputTokens: 800_000, outputTokens: 200_000, credits: 82.61 },
        steps: [{
          stepId: 101,
          name: '编码实现',
          status: 'done',
          executorName: 'AW全栈开发',
          error: null,
          subSteps: null,
          durationMs: 1_050_000,
          usage: { inputTokens: 800_000, outputTokens: 200_000, credits: 82.61 },
          attempts: [
            { dispatchId: 301, executorName: 'AW全栈开发', status: 'SUCCEEDED', error: null, startedAt: null, durationMs: 1_050_000 },
          ],
        }],
      }],
    };
    const artifacts: Artifact[] = [
      { id: 1, workitemId: 100, dispatchId: 301, name: 'dev-summary.md', type: 'MARKDOWN', size: 1024, gmtCreate: '' },
      { id: 2, workitemId: 100, dispatchId: 301, name: 'run.log', type: 'TEXT', size: 128, gmtCreate: '' },
    ];

    render(<DeliveryProgress progress={progress} artifacts={artifacts} />);

    expect(screen.getAllByText('1M').length).toBe(2);
    expect(screen.queryByText(/82\.61/)).not.toBeInTheDocument();
    expect(screen.queryByText(/💰/)).not.toBeInTheDocument();
    expect(screen.queryByText('2 artifacts')).not.toBeInTheDocument();
    expect(screen.queryByText('无产物')).not.toBeInTheDocument();
    expect(screen.getAllByText(/2 artifacts/).length).toBeGreaterThan(0);
  });
});
