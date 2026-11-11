import React, { useState } from 'react';
import { createRoot } from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { LocaleProvider } from '../src/shared/i18n/LocaleProvider';
import { ExecutionActionBar } from '../src/features/workitem/components/ExecutionActionBar';
import { ExecutionControlTimeline } from '../src/features/workitem/components/ExecutionControlTimeline';
import { DeliveryProgress } from '../src/features/workitem/components/DeliveryProgress';
import { CommentInteractionCard } from '../src/features/workitem/components/CommentInteractionCard';
import { InteractionStopControl } from '../src/features/workitem/components/InteractionStopControl';
import type { InteractionStopAction } from '../src/features/workitem/components/InteractionStopControl';
import type { Artifact, TimelineItem, DispatchAttempt, DispatchControlCapabilities, ExecutionControlDetail, DeliveryControlDetail, CommentInteraction } from '../src/shared/types/workitem';
import type { ExecutionActionKind } from '../src/features/workitem/hooks';

// 工单执行控制面板浏览器 fixture：真实 Chrome 验证统一执行操作栏按控制能力渲染
// 主操作/溢出/禁用操作及原因、结束/继续超时/强制结束确认弹窗、控制时间线按派发
// 过滤与加载更早、交付进度执行卡片长状态不挤压执行名称、产物行布局与交互、中英文文案。
// 无后端/DB：mock 控制能力、时间线事件与产物，onAction 回调记录到页面日志。
// Run: node e2e-tests/workitem-execution-control-panel/run.cjs

function caps(overrides: Partial<DispatchControlCapabilities>): DispatchControlCapabilities {
  const off = { allowed: false };
  return { pause: off, end: off, forceEnd: off, resume: off, ...overrides } as DispatchControlCapabilities;
}

function attempt(id: number, status: string | null, overrides: Partial<DispatchAttempt> = {}): DispatchAttempt {
  return {
    dispatchId: id,
    agentId: 20000,
    agentName: '数字员工甲',
    executorId: null,
    executorName: null,
    status,
    startedAt: null,
    endedAt: null,
    durationMs: 60000,
    waitingReason: null,
    error: null,
    resumeMode: null,
    ...overrides,
  };
}

const ACTION_ATTEMPTS: Array<{ id: string; attempt: DispatchAttempt }> = [
  {
    id: 'action-row-101',
    attempt: attempt(101, 'RUNNING', { control: caps({ pause: { allowed: true }, end: { allowed: true } }) }),
  },
  {
    id: 'action-row-102',
    attempt: attempt(102, 'PAUSE_FAILED', {
      control: caps({ pause: { allowed: false, reasonKey: 'recovery.waitingPause' }, resume: { allowed: true, targetDispatchId: 103, mode: 'RESUME' } }),
    }),
  },
  {
    id: 'action-row-103',
    attempt: attempt(103, 'TIMEOUT', {
      control: caps({ resume: { allowed: true, targetDispatchId: 104, mode: 'RESUME', targetStatus: 'TIMEOUT', targetStopPending: true } }),
    }),
  },
  {
    id: 'action-row-104',
    attempt: attempt(104, 'FAILED', { control: caps({ end: { allowed: true } }) }),
  },
  {
    id: 'action-row-105',
    attempt: attempt(105, 'RUNNING', { control: caps({ end: { allowed: true }, forceEnd: { allowed: true } }) }),
  },
  {
    id: 'action-row-106',
    attempt: attempt(106, 'CANCELED', { control: caps({ resume: { allowed: false, reasonKey: 'recovery.agentNotPublished' } }) }),
  },
  {
    id: 'action-row-107',
    attempt: attempt(107, 'PAUSED', { canContinue: true }),
  },
  {
    id: 'action-row-108',
    attempt: attempt(108, 'RUNNING', { canPause: true }),
  },
];

function ActionBarShowcase() {
  const [log, setLog] = useState<string[]>([]);
  const onAction = (rowAttempt: DispatchAttempt, action: ExecutionActionKind) => {
    setLog((current) => [...current, `${action}:${rowAttempt.dispatchId}`]);
  };
  return (
    <div data-testid="execution-control-fixture" style={{ maxWidth: 960 }}>
      <output data-testid="action-log">{log.join(',')}</output>
      {ACTION_ATTEMPTS.map(({ id, attempt: rowAttempt }) => (
        <div key={id} data-testid={id} style={{ padding: '6px 0', borderBottom: '1px dashed #ddd' }}>
          <span style={{ display: 'inline-block', width: 90, color: '#888' }}>#{rowAttempt.dispatchId}</span>
          <ExecutionActionBar attempt={rowAttempt} pending={null} onAction={(action) => onAction(rowAttempt, action)} />
        </div>
      ))}
    </div>
  );
}

function sysItem(id: number, executionControl: ExecutionControlDetail): TimelineItem {
  return {
    id,
    type: 'system',
    eventType: 'EXECUTION_CONTROL',
    executionControl,
    deliveryControl: null,
    authorId: null,
    authorName: executionControl.requestedByName ?? null,
    authorType: 'HUMAN',
    isAgent: false,
    content: '',
    gmtCreate: new Date(Date.UTC(2026, 9, 20, 8, 0, 0) - id * 1000).toISOString(),
  };
}

function deliverySysItem(id: number, deliveryControl: DeliveryControlDetail): TimelineItem {
  return {
    id,
    type: 'system',
    eventType: 'DELIVERY_CONTROL',
    executionControl: null,
    deliveryControl,
    authorId: null,
    authorName: deliveryControl.requestedByName ?? null,
    authorType: 'HUMAN',
    isAgent: false,
    content: '',
    gmtCreate: new Date(Date.UTC(2026, 9, 20, 8, 0, 0) - id * 1000).toISOString(),
  };
}

// 最新在前；dispatch 101 共 15 条（含重开记录但重开不归属任何派发），dispatch 103 仅 1 条恢复记录。
function makeControlItems(): TimelineItem[] {
  const items: TimelineItem[] = [
    sysItem(9000, { action: 'START', phase: 'CONFIRMED', origin: 'ASSIGNMENT', dispatchId: 100, requestedByName: '首次指派人' }),
    sysItem(9001, { action: 'PAUSE', phase: 'CONFIRMED', dispatchId: 101, requestedByName: '张三' }),
    sysItem(9002, { action: 'PAUSE', phase: 'REQUESTED', dispatchId: 101, requestedByName: '张三' }),
    sysItem(9003, { action: 'RESUME', phase: 'CONFIRMED', dispatchId: 101, sourceDispatchId: 101, targetDispatchId: 103, requestedByName: '张三' }),
    deliverySysItem(9004, { action: 'CLOSE', requestedByName: '李四', reason: '需求已验收', affectedDispatches: [{ dispatchId: 101, fromStatus: 'RUNNING', toStatus: 'CANCELED' }] }),
    deliverySysItem(9005, { action: 'REOPEN', requestedByName: '李四', affectedDispatches: [] }),
  ];
  for (let seq = 1; seq <= 11; seq += 1) {
    items.push(sysItem(9010 + seq, { action: 'CANCEL', phase: 'REQUESTED', dispatchId: 101, requestedByName: '王五' }));
  }
  return items;
}

function ControlTimelineShowcase() {
  const [items] = useState<TimelineItem[]>(() => makeControlItems());
  return (
    <div data-testid="control-timeline-fixture" style={{ maxWidth: 960, marginTop: 24 }}>
      <div data-testid="timeline-full-100">
        <ExecutionControlTimeline dispatchId={100} items={items} />
      </div>
      <div data-testid="timeline-full-101">
        <ExecutionControlTimeline dispatchId={101} items={items} />
      </div>
      <div data-testid="timeline-full-103">
        <ExecutionControlTimeline dispatchId={103} items={items} />
      </div>
      <div data-testid="timeline-compact-101">
        <ExecutionControlTimeline dispatchId={101} items={items} compact />
      </div>
    </div>
  );
}

const LONG_STATUS = 'WAITING_FOR_EXECUTOR_RECONNECT_AND_PENDING_OPERATOR_CONFIRMATION';

const LONG_ARTIFACT_NAME = `artifacts/output/handoff/${'release-notes-and-a-very-long-artifact-file-name-for-layout-verification-'.repeat(2)}final.md`;

// 产物行断言数据：覆盖 B/KB/MB、size=0、size 缺失、未知类型回退、验证证据分类与超长文件名。
const ARTIFACTS: Artifact[] = [
  { id: 501, workitemId: 100, dispatchId: 101, name: 'artifacts/output/handoff/summary.md', type: 'HANDOFF', size: 12595, gmtCreate: '2026-10-20T08:00:00Z' },
  { id: 502, workitemId: 100, dispatchId: 101, name: 'artifacts/output/handoff/empty.txt', type: 'HANDOFF', size: 0, gmtCreate: '2026-10-20T08:00:01Z' },
  { id: 503, workitemId: 100, dispatchId: 101, name: 'artifacts/output/handoff/bundle.bin', type: 'HANDOFF', size: 2097152, gmtCreate: '2026-10-20T08:00:02Z' },
  { id: 504, workitemId: 100, dispatchId: 101, name: 'artifacts/output/handoff/no-size.md', type: 'HANDOFF', size: null, gmtCreate: '2026-10-20T08:00:03Z' },
  { id: 505, workitemId: 100, dispatchId: 101, name: LONG_ARTIFACT_NAME, type: 'HANDOFF', size: 5120, gmtCreate: '2026-10-20T08:00:04Z' },
  { id: 506, workitemId: 100, dispatchId: 101, name: 'artifacts/output/evidence/probe-result.json', type: 'EVIDENCE', size: 20500, gmtCreate: '2026-10-20T08:00:05Z' },
  { id: 507, workitemId: 100, dispatchId: 101, name: 'artifacts/output/other/custom.bin', type: 'CUSTOM_TYPE', size: 100, gmtCreate: '2026-10-20T08:00:06Z' },
];

function WorkbenchShowcase() {
  const [items] = useState<TimelineItem[]>(() => makeControlItems());
  const [log, setLog] = useState<string[]>([]);
  const activeAttempt = attempt(101, 'RUNNING', {
    control: caps({ pause: { allowed: true }, end: { allowed: true } }),
  });
  const longStatusAttempt = attempt(102, LONG_STATUS, { agentId: 20002, agentName: '数字员工乙' });
  return (
    <div data-testid="delivery-progress-fixture" style={{ maxWidth: 960, marginTop: 24 }}>
      <output data-testid="workbench-action-log">{log.join(',')}</output>
      <DeliveryProgress
        showWorkflowPlan={false}
        progress={{
          executions: [activeAttempt, longStatusAttempt],
          steps: [],
          agents: [
            {
              agentId: 20000,
              agentName: '数字员工甲',
              status: 'active',
              durationMs: 60000,
              executionStatus: 'RUNNING',
              steps: [
                {
                  stepId: 1,
                  name: 'SDLC 步骤',
                  status: 'done',
                  planStatus: null,
                  sourceAttempt: null,
                  agentName: '数字员工甲',
                  executorId: null,
                  executorName: null,
                  error: null,
                  subSteps: null,
                  durationMs: 60000,
                  attempts: [activeAttempt],
                },
              ],
            },
            {
              agentId: 20002,
              agentName: '数字员工乙',
              status: 'finished',
              durationMs: 30000,
              executionStatus: LONG_STATUS,
              steps: [],
            },
          ],
          totalDurationMs: null,
          closed: false,
        }}
        artifacts={ARTIFACTS}
        renderControlTimeline={(dispatchId, compact) => (
          <ExecutionControlTimeline dispatchId={dispatchId} items={items} compact={compact} />
        )}
        onExecutionAction={(rowAttempt, action) => {
          setLog((current) => [...current, `${action}:${rowAttempt.dispatchId}`]);
        }}
      />
    </div>
  );
}

// 评论交互卡片停止控件：真实 CommentInteractionCard + InteractionStopControl，控制能力/运行事件由
// 本 fixture 传入。覆盖图标无障碍名称、精简确认框（无评论编号/数字人行）、取消零请求、按 dispatch
// 隔离的 loading 与错误、取消中/停止未确认状态、只读不传渲染位。
function commentInteraction(dispatchId: number, overrides: Partial<CommentInteraction> = {}): CommentInteraction {
  return {
    guidanceId: dispatchId,
    dispatchId,
    executionStatus: 'RUNNING',
    targetAgentId: 20000,
    targetAgentName: '数字员工甲',
    status: 'DELIVERED',
    error: null,
    replyCommentId: null,
    replyContent: null,
    repliedAt: null,
    controlEligible: true,
    cancelRequested: false,
    stopPending: false,
    control: { end: { allowed: true }, forceEnd: { allowed: false } },
    ...overrides,
  };
}

const COMMENT_CARDS: Array<{ testId: string; interaction: CommentInteraction; withActions: boolean; failOnConfirm?: boolean }> = [
  { testId: 'comment-card-201', interaction: commentInteraction(201), withActions: true },
  {
    testId: 'comment-card-202',
    interaction: commentInteraction(202, {
      status: 'CANCELED',
      executionStatus: 'PAUSING',
      cancelRequested: true,
      stopPending: true,
      control: {
        end: { allowed: false, reasonKey: 'delivery.controlReason.cancelling' },
        forceEnd: { allowed: true },
      },
    }),
    withActions: true,
  },
  {
    testId: 'comment-card-203',
    interaction: commentInteraction(203, {
      status: 'CANCELED',
      executionStatus: 'CANCELED',
      cancelRequested: true,
      stopPending: true,
      control: {
        end: { allowed: false, reasonKey: 'delivery.controlReason.terminal' },
        forceEnd: { allowed: false, reasonKey: 'delivery.controlReason.terminal' },
      },
    }),
    withActions: true,
  },
  {
    testId: 'comment-card-204',
    interaction: commentInteraction(204, {
      status: 'APPLIED',
      executionStatus: 'SUCCEEDED',
      replyCommentId: 300,
      replyContent: '已完成分析。',
      repliedAt: '2026-10-20T08:05:00Z',
      control: {
        end: { allowed: false, reasonKey: 'delivery.controlReason.terminal' },
        forceEnd: { allowed: false, reasonKey: 'delivery.controlReason.terminal' },
      },
    }),
    withActions: true,
  },
  // 只读成员/无渲染位：同一交互但不传 actions，必须没有任何停止控件。
  { testId: 'comment-card-205', interaction: commentInteraction(205), withActions: false },
  { testId: 'comment-card-206', interaction: commentInteraction(206), withActions: true, failOnConfirm: true },
];

function CommentStopShowcase() {
  const [log, setLog] = useState<string[]>([]);
  const [refreshes, setRefreshes] = useState(0);
  const [pending, setPending] = useState<{ dispatchId: number; action: InteractionStopAction } | null>(null);
  const runAction = (dispatchId: number, action: InteractionStopAction, fail: boolean) => async () => {
    setPending({ dispatchId, action });
    try {
      await new Promise((resolve) => setTimeout(resolve, 400));
      if (fail) throw new Error('fixture rejects the end request');
      setLog((current) => [...current, `${action}:${dispatchId}`]);
    } finally {
      setPending(null);
    }
  };
  const renderActions = (dispatchId: number, fail: boolean) => (interaction: CommentInteraction) => (
    <InteractionStopControl
      interaction={interaction}
      pending={pending?.dispatchId === dispatchId ? pending.action : null}
      onAction={(action) => runAction(dispatchId, action, fail)()}
      onRefresh={() => setRefreshes((value) => value + 1)}
    />
  );
  return (
    <div data-testid="comment-stop-fixture" style={{ maxWidth: 960, marginTop: 24 }}>
      <output data-testid="comment-action-log">{log.join(',')}</output>
      <output data-testid="comment-refresh-log">{refreshes}</output>
      {COMMENT_CARDS.map(({ testId, interaction, withActions, failOnConfirm }) => (
        <div key={testId} data-testid={testId} style={{ padding: '6px 0', borderBottom: '1px dashed #ddd' }}>
          <CommentInteractionCard
            interaction={interaction}
            createdAt="2026-10-20T08:00:00Z"
            actions={withActions ? renderActions(interaction.dispatchId!, Boolean(failOnConfirm))(interaction) : undefined}
          />
        </div>
      ))}
    </div>
  );
}

const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
createRoot(document.getElementById('root')!).render(
  <LocaleProvider>
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/']}>
        <ActionBarShowcase />
        <ControlTimelineShowcase />
        <WorkbenchShowcase />
        <CommentStopShowcase />
      </MemoryRouter>
    </QueryClientProvider>
  </LocaleProvider>,
);
