import React, { useState } from 'react';
import { createRoot } from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { LocaleProvider } from '../src/shared/i18n/LocaleProvider';
import { ExecutionActionBar } from '../src/features/workitem/components/ExecutionActionBar';
import { ExecutionControlTimeline } from '../src/features/workitem/components/ExecutionControlTimeline';
import { DeliveryProgress } from '../src/features/workitem/components/DeliveryProgress';
import type { TimelineItem, DispatchAttempt, DispatchControlCapabilities, ExecutionControlDetail, DeliveryControlDetail } from '../src/shared/types/workitem';
import type { ExecutionActionKind } from '../src/features/workitem/hooks';

// 工单执行控制面板浏览器 fixture：真实 Chrome 验证统一执行操作栏按控制能力渲染
// 主操作/溢出/禁用操作及原因、结束/继续超时/强制结束确认弹窗、控制时间线按派发
// 过滤与加载更早、交付进度执行卡片长状态不挤压执行名称、中英文文案。
// 无后端/DB：mock 控制能力与时间线事件，onAction 回调记录到页面日志。
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
        artifacts={[]}
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

const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
createRoot(document.getElementById('root')!).render(
  <LocaleProvider>
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/']}>
        <ActionBarShowcase />
        <ControlTimelineShowcase />
        <WorkbenchShowcase />
      </MemoryRouter>
    </QueryClientProvider>
  </LocaleProvider>,
);
