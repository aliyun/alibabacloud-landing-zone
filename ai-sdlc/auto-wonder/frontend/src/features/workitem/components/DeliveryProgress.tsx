import { formatDuration } from '../duration';
import { formatDateTime } from '@/shared/i18n/format';
import { i18n } from '@/shared/i18n';
import { runtimeStatusLabel, dispatchExecutionLabel, runtimeEdgeLabel, artifactTypeLabel, subStepName, graphAgentName } from '../runtimeLabels';
import { useTranslation } from 'react-i18next';
import { useEffect, useMemo, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { Card, Typography, Space, Spin, Collapse, Tag, Tooltip, Button, Select, ConfigProvider, Modal, Tabs, Alert, Empty } from 'antd';
import { CheckCircleFilled, ClockCircleOutlined, CloseCircleFilled, CompressOutlined, DownloadOutlined, EyeOutlined, FileTextOutlined, FullscreenOutlined, LoadingOutlined, PauseCircleOutlined, PlayCircleOutlined, ReloadOutlined, ZoomInOutlined, ZoomOutOutlined } from '@ant-design/icons';
import type { AgentDeliveryProgress, Artifact, DeliveryProgress as DeliveryProgressModel, DeliveryStep, DispatchAttempt, ProcessGraph, ProcessGraphEdge, ProcessGraphNode, StepTiming, SubStep, WorkflowPlan } from '@/shared/types/workitem';
import { artifactCategories, artifactsForReadingCategory, groupArtifactsByExecution } from '../artifactGrouping';
import { basename } from '@/shared/lib/artifactLinking';
import { getArtifactDownloadUrl } from '../api';
import { ArtifactPreviewModal } from './ArtifactPreviewModal';
import { DispatchActivityFeed } from './DispatchActivityFeed';
import { LiveActivityPanel } from './LiveActivityPanel';
import { RuntimeTraceDrawer } from './RuntimeTraceDrawer';
import { RuntimeLogPanel } from './RuntimeLogDrawer';
import './DeliveryWorkbench.css';
import { TokenUsageBadge, StepTokenBadge } from './TokenUsageBadge';
import { StepDurationChart } from './StepDurationChart';

const { Text } = Typography;

type DisplayAttempt = DispatchAttempt & {
  displayIndex: number;
  isActivityFeedHost: boolean;
};

type GraphSelection = { node: ProcessGraphNode } | { edge: ProcessGraphEdge; index: number; source: ProcessGraphNode; target: ProcessGraphNode };

interface DeliveryProgressProps {
  showWorkflowPlan?: boolean;
  renderRecovery?: (dispatchId: number) => ReactNode;
  restartHistory?: ReactNode;
  steps?: DeliveryStep[];
  progress?: DeliveryProgressModel | null;
  artifacts?: Artifact[];
  artifactsLoading?: boolean;
  loading?: boolean;
  terminalStatus?: string | null;
  onContinue?: (dispatchId: number) => void;
  continuingDispatchId?: number | null;
  onPause?: (dispatchId: number) => void;
  pausingDispatchId?: number | null;
}

export { formatDuration } from '../duration';

export type StepDisplayStatus = DeliveryStep['status'] | 'cancelled' | 'interrupted';

export type ConvergedDeliveryStep = Omit<DeliveryStep, 'status' | 'subSteps'> & {
  status: StepDisplayStatus;
  subSteps: (Omit<SubStep, 'status'> & { status: SubStep['status'] | 'cancelled' })[] | null;
};

type TerminalKind = 'SUCCEEDED' | 'FAILED' | 'CANCELLED';

function normalizeTerminalStatus(terminalStatus?: string | null): TerminalKind | null {
  if (!terminalStatus) return null;
  const value = terminalStatus.trim().toUpperCase();
  if (value === 'SUCCEEDED' || value === 'FAILED' || value === 'CANCELLED') return value;
  if (value === 'CANCELED') return 'CANCELLED';
  if (value === 'CLOSED' || terminalStatus.trim() === '已关闭') return 'SUCCEEDED';
  return null;
}

function convergedSubStepStatus(status: SubStep['status'], terminal: TerminalKind, isFailurePoint: boolean): SubStep['status'] | 'cancelled' {
  if (status !== 'active') return status;
  if (terminal === 'SUCCEEDED') return 'done';
  if (terminal === 'CANCELLED') return 'cancelled';
  return isFailurePoint ? 'failed' : 'done';
}

// 终态收敛：run/工单已终态但步骤事件流不完整（缺 completed 事件）时，把仍为 active 的步骤映射到与终态一致的展示状态。
export function convergeStepsForTerminalStatus(steps: DeliveryStep[], terminalStatus?: string | null): ConvergedDeliveryStep[] {
  const terminal = normalizeTerminalStatus(terminalStatus);
  if (!terminal) return steps.map((step) => ({ ...step }));

  let lastActiveIndex = -1;
  if (terminal === 'FAILED') {
    for (let i = steps.length - 1; i >= 0; i--) {
      if (steps[i].status === 'active') {
        lastActiveIndex = i;
        break;
      }
    }
  }

  return steps.map((step, index) => {
    if (step.status !== 'active') return { ...step };
    const isFailurePoint = index === lastActiveIndex;
    const status: StepDisplayStatus = terminal === 'SUCCEEDED'
      ? 'done'
      : terminal === 'CANCELLED'
        ? 'cancelled'
        : isFailurePoint ? 'failed' : 'done';
    return {
      ...step,
      status,
      subSteps: step.subSteps?.map((sub) => ({ ...sub, status: convergedSubStepStatus(sub.status, terminal, isFailurePoint) })) ?? null,
    };
  });
}

function StepIcon({ step, index, pausing = false }: { step: ConvergedDeliveryStep; index: number; pausing?: boolean }) {
  if (step.status === 'done' || step.status === 'reused' || step.planStatus === 'REUSED') {
    return <CheckCircleFilled aria-hidden="true" style={{ color: 'var(--aw-success)', fontSize: 16 }} />;
  }
  if (pausing) {
    return <PauseCircleOutlined aria-hidden="true" style={{ color: 'var(--aw-warning)', fontSize: 16 }} />;
  }
  if (step.status === 'active' && step.executionStatus && step.executionStatus !== 'RUNNING') {
    return <ClockCircleOutlined aria-hidden="true" style={{ color: 'var(--aw-muted)', fontSize: 16 }} />;
  }
  if (step.status === 'active') {
    return <LoadingOutlined aria-hidden="true" style={{ color: 'var(--aw-accent-text)', fontSize: 16 }} />;
  }
  if (step.status === 'cancelled') {
    return <PauseCircleOutlined aria-hidden="true" style={{ color: 'var(--aw-muted)', fontSize: 16 }} />;
  }
  if (step.status === 'failed') {
    return <CloseCircleFilled aria-hidden="true" style={{ color: 'var(--aw-error)', fontSize: 16 }} />;
  }
  if (step.status === 'paused') {
    return <PauseCircleOutlined aria-hidden="true" style={{ color: 'var(--aw-warning)', fontSize: 16 }} />;
  }
  if (step.status === 'skipped' || step.status === 'stale' || step.status === 'interrupted' || step.planStatus === 'SKIPPED') {
    return <span style={{ color: step.status === 'stale' || step.status === 'interrupted' ? 'var(--aw-warning)' : 'var(--aw-muted)', fontSize: 11 }}>—</span>;
  }
  // pending
  return (
    <span
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        justifyContent: 'center',
        width: 16,
        height: 16,
        borderRadius: '50%',
        border: '1.5px solid var(--aw-border)',
        fontSize: 10,
        color: 'var(--aw-muted)',
        lineHeight: 1,
      }}
    >
      {index + 1}
    </span>
  );
}

function SubStepDot({ status }: { status: SubStep['status'] | 'cancelled' }) {
  const colorMap: Record<SubStep['status'] | 'cancelled', string> = {
    done: 'var(--aw-success)',
    active: 'var(--aw-accent-text)',
    pending: 'var(--aw-border)',
    failed: 'var(--aw-error)',
    cancelled: 'var(--aw-muted)',
  };
  return (
    <span
      style={{
        display: 'inline-block',
        width: 6,
        height: 6,
        borderRadius: '50%',
        backgroundColor: colorMap[status],
        marginRight: 6,
        flexShrink: 0,
      }}
    />
  );
}

function findActionTargetStep(steps: ConvergedDeliveryStep[], ownerStep: ConvergedDeliveryStep, attempt: DispatchAttempt): ConvergedDeliveryStep | null {
  if (attempt.resumeMode === 'COMMENT_INTERACTION'
    || attempt.resumeMode === 'SIDE_INTERACTION'
    || attempt.resumeMode === 'CANONICAL_INTERACTION') {
    return null;
  }
  if (attempt.canPause) {
    if (ownerStep.status === 'active') {
      return ownerStep;
    }
    return steps.find((step) => step.status === 'active') ?? null;
  }
  if (attempt.canContinue) {
    if (ownerStep.status === 'paused' || ownerStep.status === 'failed') {
      return ownerStep;
    }
    return steps.find((step) => step.status === 'paused' || step.status === 'failed') ?? null;
  }
  return null;
}

function buildAttemptViews(steps: ConvergedDeliveryStep[]): Map<number, DisplayAttempt[]> {
  const byStep = new Map<number, DisplayAttempt[]>();
  const relocatedActions = new Map<number, Set<number>>();

  steps.forEach((step) => {
    step.attempts?.forEach((attempt, i) => {
      const target = findActionTargetStep(steps, step, attempt);
      if (target && target.stepId !== step.stepId) {
        relocatedActions.set(step.stepId, (relocatedActions.get(step.stepId) ?? new Set()).add(attempt.dispatchId));
        byStep.set(target.stepId, [
          ...(byStep.get(target.stepId) ?? []),
          { ...attempt, displayIndex: i, isActivityFeedHost: true },
        ]);
      }
    });
  });

  steps.forEach((step) => {
    const strippedActionIds = relocatedActions.get(step.stepId);
    const attempts = step.attempts?.map((attempt, i) => {
      const isRelocatedAction = strippedActionIds?.has(attempt.dispatchId) ?? false;
      return {
        ...attempt,
        ...(isRelocatedAction ? { canContinue: false, canPause: false } : {}),
        displayIndex: i,
        isActivityFeedHost: !isRelocatedAction,
      };
    }) ?? [];
    byStep.set(step.stepId, [...attempts, ...(byStep.get(step.stepId) ?? [])]);
  });

  return byStep;
}

function pauseLabel(status: string | null | undefined): string {
  if (status === 'PAUSING') {
    return i18n.t('workitem:delivery.pausing');
  }
  if (status === 'PAUSE_FAILED') {
    return i18n.t('workitem:delivery.retryPause');
  }
  return i18n.t('workitem:delivery.pause');
}

function stepIsPausing(step: ConvergedDeliveryStep): boolean {
  return step.attempts?.some((attempt) => attempt.status === 'PAUSING') ?? false;
}

function AttemptActionButton({
  attempt,
  onContinue,
  continuingDispatchId,
  onPause,
  pausingDispatchId,
}: {
  attempt: DispatchAttempt;
  onContinue?: (dispatchId: number) => void;
  continuingDispatchId?: number | null;
  onPause?: (dispatchId: number) => void;
  pausingDispatchId?: number | null;
}) {
  const { t } = useTranslation('workitem');
  if (attempt.error?.startsWith('CANCEL_REQUESTED:')) return <Tag color="warning">{t('delivery.cancelling')}</Tag>;
  if (attempt.canContinue && onContinue) {
    return (
      <Button
        size="small"
        type="link"
        aria-label={t('delivery.continue')}
        icon={<PlayCircleOutlined aria-hidden="true" />}
        loading={continuingDispatchId === attempt.dispatchId}
        onClick={() => onContinue(attempt.dispatchId)}
      >
        {t('delivery.continue')}
      </Button>
    );
  }
  if (attempt.canPause && onPause) {
    const label = pauseLabel(attempt.status);
    const pausing = attempt.status === 'PAUSING';
    return (
      <Button
        size="small"
        type="link"
        danger={!pausing}
        aria-label={label}
        icon={<PauseCircleOutlined aria-hidden="true" />}
        loading={pausingDispatchId === attempt.dispatchId}
        disabled={pausing}
        onClick={pausing ? undefined : () => onPause(attempt.dispatchId)}
      >
        {label}
      </Button>
    );
  }
  return null;
}

function StepCard({ step, index, attempts, onContinue, continuingDispatchId, onPause, pausingDispatchId }: {
  step: ConvergedDeliveryStep;
  index: number;
  attempts: DisplayAttempt[];
  onContinue?: (dispatchId: number) => void;
  continuingDispatchId?: number | null;
  onPause?: (dispatchId: number) => void;
  pausingDispatchId?: number | null;
}) {
  const { t } = useTranslation('workitem');
  const isPausing = attempts.some((attempt) => attempt.status === 'PAUSING');
  const isActive = step.status === 'active';
  const isPaused = step.status === 'paused';
  const isCancelled = step.status === 'cancelled';
  const isDone = step.status === 'done' || step.status === 'reused' || step.status === 'skipped'
    || step.planStatus === 'REUSED' || step.planStatus === 'SKIPPED';
  const isFailed = step.status === 'failed';
  const isInterrupted = step.status === 'interrupted';
  const executionLabel = step.planStatus === 'SKIPPED' ? t('delivery.noExecution') : isDone ? t('delivery.completed') : isPausing ? t('delivery.pausing') : isActive ? step.executionStatus ? dispatchExecutionLabel(step.executionStatus, step.waitingReason) : t('delivery.running') : isPaused ? t('delivery.paused') : isCancelled ? t('delivery.cancelled') : isFailed ? t('delivery.failed') : isInterrupted ? t('stepTiming.status.interrupted') : t('delivery.pending');
  const planLabel = step.planStatus === 'RUN'
    ? t('delivery.rerun')
    : step.planStatus === 'REUSED'
      ? step.sourceAttempt ? t('delivery.reusedResult', { number: step.sourceAttempt }) : t('delivery.reusedPrevious')
      : step.planStatus === 'SKIPPED'
        ? t('delivery.skipped')
        : null;
  const primaryActionAttempt = attempts.find((attempt) =>
    (attempt.canContinue && onContinue) || (attempt.canPause && onPause));
  const hasAttemptDetails = attempts.length > 0 || Boolean(step.agentName) || Boolean(step.executorName) || Boolean(step.subSteps?.length);
  const attentionStatus = isActive || isPaused || isFailed;
  const [recordsOpen, setRecordsOpen] = useState(attentionStatus);
  const previousAttentionStatus = useRef(attentionStatus);
  const attemptRecords = attempts.length > 0 ? attempts : [];

  useEffect(() => {
    if (attentionStatus && !previousAttentionStatus.current) {
      setRecordsOpen(true);
    }
    previousAttentionStatus.current = attentionStatus;
  }, [attentionStatus]);

  return (
    <div
      data-testid={`delivery-step-${step.stepId}`}
      style={{
        padding: '6px 8px',
        borderRadius: 6,
        border: isPausing || isPaused ? '1px solid color-mix(in srgb, var(--aw-warning) 35%, var(--aw-border))' : isActive ? '1px solid var(--aw-accent-text)' : isFailed ? '1px solid color-mix(in srgb, var(--aw-error) 35%, var(--aw-border))' : '1px solid var(--aw-border)',
        backgroundColor: isPausing || isPaused ? 'color-mix(in srgb, var(--aw-warning) 10%, var(--aw-panel))' : isActive ? 'rgba(var(--aw-accent-rgb),.10)' : isFailed ? 'color-mix(in srgb, var(--aw-error) 10%, var(--aw-panel))' : 'var(--aw-panel)',
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
        <StepIcon step={step} index={index} pausing={isPausing} />
        <Text
          style={{
            flex: 1,
            fontSize: 12,
            color: isDone ? 'var(--aw-muted)' : isPausing || isPaused ? 'var(--aw-warning)' : isActive ? 'var(--aw-accent-text)' : isFailed ? 'var(--aw-error)' : isInterrupted ? 'var(--aw-warning)' : 'var(--aw-muted)',
            fontWeight: isActive || isPausing || isPaused || isFailed ? 500 : 400,
          }}
        >
          {step.name}
        </Text>
        {step.error && (
          <Tooltip title={step.error}>
            <Tag color="error" style={{ marginInlineEnd: 0, maxWidth: 140, overflow: 'hidden', textOverflow: 'ellipsis' }}>
              {t('delivery.failureDetail', { error: step.error })}
            </Tag>
          </Tooltip>
        )}
        <Text
          type="secondary"
          style={{ fontSize: 11, color: isPausing || isPaused ? 'var(--aw-warning)' : isActive ? 'var(--aw-accent-text)' : isFailed ? 'var(--aw-error)' : isInterrupted ? 'var(--aw-warning)' : undefined }}
        >
          {planLabel ? t('delivery.stepStatus', { plan: planLabel, status: executionLabel }) : step.status === 'reused' ? t('delivery.reusedPrevious') : step.status === 'skipped' ? t('delivery.skipped') : step.status === 'stale' ? t('delivery.stale') : executionLabel}
          {step.durationMs != null && `  ${formatDuration(step.durationMs)}`}
        </Text>
        {step.usage && <StepTokenBadge usage={step.usage} />}
        {primaryActionAttempt && (
          <AttemptActionButton
            attempt={primaryActionAttempt}
            onContinue={onContinue}
            continuingDispatchId={continuingDispatchId}
            onPause={onPause}
            pausingDispatchId={pausingDispatchId}
          />
        )}
      </div>

      {hasAttemptDetails && (
        <div style={{ marginTop: 4, paddingLeft: 22 }}>
          <Collapse
            ghost
            size="small"
            activeKey={recordsOpen ? ['records'] : []}
            onChange={(keys) => setRecordsOpen((Array.isArray(keys) ? keys : [keys]).includes('records'))}
            items={[
              {
                key: 'records',
                label: <Text type="secondary" style={{ fontSize: 12 }}>{t('artifacts.runtime')}</Text>,
                children: (
                  <Space direction="vertical" size={4} style={{ width: '100%' }}>
                    {step.agentName && (
                      <Text type="secondary" style={{ fontSize: 11, display: 'block' }}>
                        {t('delivery.executor', { name: step.agentName })}
                      </Text>
                    )}
                    {step.executorName && (
                      <Text type="secondary" style={{ fontSize: 11, display: 'block' }}>
                        {t('delivery.runner', { name: step.executorName })}
                      </Text>
                    )}
                    {recordsOpen && attemptRecords.map((attempt) => (
                      <div key={String(attempt.dispatchId)}>
                        <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                          <Text type="secondary" style={{ fontSize: 11, flex: 1 }}>
                            {attempt.executorName
                              ? t('delivery.attemptOnRunner', { number: attempt.displayIndex + 1, name: attempt.agentName ?? '—', runner: attempt.executorName, status: dispatchExecutionLabel(attempt.status, attempt.waitingReason) })
                              : t('delivery.attempt', { number: attempt.displayIndex + 1, name: attempt.agentName ?? '—', status: dispatchExecutionLabel(attempt.status, attempt.waitingReason) })}
                            {attempt.durationMs != null && ` · ${formatDuration(attempt.durationMs)}`}
                            {attempt.error && ` · ${attempt.error}`}
                          </Text>
                          {attempt.dispatchId !== primaryActionAttempt?.dispatchId && (
                            <AttemptActionButton
                              attempt={attempt}
                              onContinue={onContinue}
                              continuingDispatchId={continuingDispatchId}
                              onPause={onPause}
                              pausingDispatchId={pausingDispatchId}
                            />
                          )}
                        </div>
                        {recordsOpen && attempt.isActivityFeedHost && (
                          <DispatchActivityFeed dispatchId={attempt.dispatchId} status={attempt.status} />
                        )}
                      </div>
                    ))}
                    {step.subSteps && step.subSteps.length > 0 && (
                      <Space direction="vertical" size={2} style={{ width: '100%' }}>
                        {step.subSteps.map((sub, i) => (
                          <div key={i} style={{ display: 'flex', alignItems: 'center' }}>
                            <SubStepDot status={sub.status} />
                            <Text style={{ fontSize: 12, color: sub.status === 'pending' ? 'var(--aw-muted)' : 'var(--aw-muted)' }}>
                              {subStepName(sub)}
                            </Text>
                          </div>
                        ))}
                      </Space>
                    )}
                  </Space>
                ),
              },
            ]}
          />
        </div>
      )}
    </div>
  );
}

function StepList({
  steps,
  onContinue,
  continuingDispatchId,
  onPause,
  pausingDispatchId,
}: {
  steps: ConvergedDeliveryStep[];
  onContinue?: (dispatchId: number) => void;
  continuingDispatchId?: number | null;
  onPause?: (dispatchId: number) => void;
  pausingDispatchId?: number | null;
}) {
  const attemptsByStep = buildAttemptViews(steps);

  return (
    <Space direction="vertical" style={{ width: '100%' }} size={4}>
      {steps.map((step, i) => (
        <StepCard
          key={String(step.stepId)}
          step={step}
          index={i}
          attempts={attemptsByStep.get(step.stepId) ?? []}
          onContinue={onContinue}
          continuingDispatchId={continuingDispatchId}
          onPause={onPause}
          pausingDispatchId={pausingDispatchId}
        />
      ))}
    </Space>
  );
}

export function WorkflowPlanSummary({ plan }: { plan: WorkflowPlan }) {
  const { t } = useTranslation('workitem');
  const grouped = (status: WorkflowPlan['steps'][number]['planStatus']) => plan.steps
    .filter((step) => step.planStatus === status)
    .map((step) => step.name || step.stepKey)
    .filter((name): name is string => Boolean(name));
  const reused = grouped('REUSED');
  const run = grouped('RUN');
  const skipped = grouped('SKIPPED');

  return (
    <Card
      size="small"
      title={t('delivery.plan')}
      data-testid="workflow-plan-card"
      styles={{ body: { padding: '8px 10px' } }}
    >
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8, marginBottom: 7 }}>
        <Tag color="blue" style={{ marginInlineEnd: 0 }}>{t('delivery.round', { number: plan.revision })}</Tag>
      </div>
      <Space size={[6, 6]} wrap>
        {reused.length > 0 && <Tag color="green">{t('delivery.reuseNames', { names: reused.join(i18n.language === 'en-US' ? ', ' : '、') })}</Tag>}
        {run.length > 0 && <Tag color="volcano">{t('delivery.rerunNames', { names: run.join(i18n.language === 'en-US' ? ', ' : '、') })}</Tag>}
        <Tag>{t('delivery.skipNames', { names: skipped.length > 0 ? skipped.join(i18n.language === 'en-US' ? ', ' : '、') : t('delivery.none') })}</Tag>
      </Space>
      {plan.reason && (
        <Text type="secondary" style={{ display: 'block', marginTop: 7, fontSize: 11 }}>
          {t('delivery.reason', { reason: plan.reason })}
        </Text>
      )}
      {plan.agentName && (
        <Text type="secondary" style={{ display: 'block', marginTop: 3, fontSize: 11 }}>
          {t('delivery.executor', { name: plan.agentName })}
        </Text>
      )}
    </Card>
  );
}

function statusLabel(status: AgentDeliveryProgress['status'], executionStatus?: string | null, waitingReason?: string | null) {
  if (status === 'active' && executionStatus) return { text: dispatchExecutionLabel(executionStatus, waitingReason), color: executionStatus === 'PENDING' ? 'default' : 'processing' };
  if (status === 'cancelled') return { text: i18n.t('workitem:delivery.cancelled'), color: 'default' };
  if (status === 'active') return { text: i18n.t('workitem:delivery.running'), color: 'processing' };
  if (status === 'paused') return { text: i18n.t('workitem:delivery.paused'), color: 'warning' };
  if (status === 'finished') return { text: i18n.t('workitem:delivery.completed'), color: 'success' };
  if (status === 'failed') return { text: i18n.t('workitem:delivery.failed'), color: 'error' };
  return { text: i18n.t('workitem:delivery.notRun'), color: 'default' };
}

export type ConvergedAgentProgress = Omit<AgentDeliveryProgress, 'steps'> & {
  steps: ConvergedDeliveryStep[];
};

function artifactDispatchIds(agent: ConvergedAgentProgress): Set<number> {
  const ids = new Set<number>();
  agent.steps.forEach((step) => {
    step.attempts?.forEach((attempt) => {
      if (attempt.dispatchId != null) {
        ids.add(Number(attempt.dispatchId));
      }
    });
  });
  return ids;
}

// Distinct dispatch ids for an agent in step/attempt order (oldest first), so retries and
// multi-dispatch history stay selectable instead of only exposing the latest one.
function orderedDispatchIds(agent: ConvergedAgentProgress): number[] {
  return Array.from(artifactDispatchIds(agent));
}

function artifactsForAgent(agent: ConvergedAgentProgress, artifacts: Artifact[], executions?: DispatchAttempt[] | null): Artifact[] {
  const dispatchIds = artifactDispatchIds(agent);
  executions?.filter(execution => execution.agentId === agent.agentId).forEach(execution => dispatchIds.add(execution.dispatchId));
  return artifacts.filter((artifact) => artifact.dispatchId != null && dispatchIds.has(Number(artifact.dispatchId)));
}

function clampZoom(value: number): number {
  return Math.min(2.4, Math.max(0.45, Number(value.toFixed(2))));
}

function markerDefs() {
  return <defs>
    <marker id="execution-graph-arrow-pass" markerWidth="9" markerHeight="9" refX="8" refY="4.5" orient="auto"><path d="M0,0 L9,4.5 L0,9 Z" fill="var(--aw-info)" /></marker>
    <marker id="execution-graph-arrow-reject" markerWidth="9" markerHeight="9" refX="8" refY="4.5" orient="auto"><path d="M0,0 L9,4.5 L0,9 Z" fill="var(--aw-error)" /></marker>
    <marker id="execution-graph-arrow-pause" markerWidth="9" markerHeight="9" refX="8" refY="4.5" orient="auto"><path d="M0,0 L9,4.5 L0,9 Z" fill="var(--aw-warning)" /></marker>
  </defs>;
}

function authoritativeNodeTone(status?: string | null) {
  const value = status?.toUpperCase();
  if (value === 'RUNNING' || value === 'ACKED' || value === 'DISPATCHED') return { border: 'var(--aw-accent-text)', background: 'rgba(var(--aw-accent-rgb),.10)' };
  if (value === 'FAILED' || value === 'TIMEOUT' || value === 'CANCELED') return { border: 'var(--aw-error)', background: 'color-mix(in srgb, var(--aw-error) 10%, var(--aw-panel))' };
  if (value?.includes('PAUS')) return { border: 'var(--aw-warning)', background: 'color-mix(in srgb, var(--aw-warning) 10%, var(--aw-panel))' };
  if (value === 'SUCCEEDED' || value === 'HUMAN') return { border: 'var(--aw-success)', background: 'color-mix(in srgb, var(--aw-success) 10%, var(--aw-panel))' };
  return { border: 'var(--aw-border)', background: 'var(--aw-raised)' };
}

function authoritativeEdgeTone(type: ProcessGraphEdge['type']) {
  if (type === 'COMMENT_REWORK') return { color: 'var(--aw-error)', background: 'color-mix(in srgb, var(--aw-error) 10%, var(--aw-panel))', border: 'color-mix(in srgb, var(--aw-error) 35%, var(--aw-border))', markerId: 'execution-graph-arrow-reject' };
  if (type === 'CONTINUE') return { color: 'var(--aw-warning)', background: 'color-mix(in srgb, var(--aw-warning) 10%, var(--aw-panel))', border: 'color-mix(in srgb, var(--aw-warning) 35%, var(--aw-border))', markerId: 'execution-graph-arrow-pause' };
  return { color: 'var(--aw-info)', background: 'color-mix(in srgb, var(--aw-info) 10%, var(--aw-panel))', border: 'var(--aw-info)', markerId: 'execution-graph-arrow-pass' };
}

function AuthoritativeExecutionProcessGraph({ processGraph }: { processGraph?: ProcessGraph | null }) {
  const { t } = useTranslation('workitem');
  const dragState = useRef<{ active: boolean; startX: number; startY: number; panX: number; panY: number }>({ active: false, startX: 0, startY: 0, panX: 0, panY: 0 });
  const [zoom, setZoom] = useState(1);
  const [pan, setPan] = useState({ x: 0, y: 0 });
  const [fullscreen, setFullscreen] = useState(false);
  const [selected, setSelection] = useState<GraphSelection | null>(null);
  const selection = selected && ('node' in selected ? {
    title: graphAgentName(selected.node), status: runtimeStatusLabel(selected.node.status),
    duration: formatDuration(selected.node.durationMs) || t('delivery.none'),
    detail: `${selected.node.stepName || t('delivery.humanHandoff')}${selected.node.dispatchId != null ? ` · dispatch ${selected.node.dispatchId}` : ''}${selected.node.error ? ` · ${selected.node.error}` : ''}`,
  } : {
    title: t('delivery.transition', { number: selected.index + 1 }), status: runtimeEdgeLabel(selected.edge, selected.source.status), duration: t('delivery.lineage'),
    detail: `${graphAgentName(selected.source)} -> ${graphAgentName(selected.target)} · dispatch ${selected.edge.sourceDispatchId ?? '-'} -> ${selected.edge.targetDispatchId ?? '-'}`,
  });
  const [traceNode, setTraceNode] = useState<ProcessGraphNode | null>(null);

  if (!processGraph || processGraph.nodes.length === 0) return null;

  const nodesByKey = new Map(processGraph.nodes.map((node) => [node.key, node]));
  const laneKey = (node: ProcessGraphNode) => node.agentId != null ? `agent:${node.agentId}` : node.key;
  const lanes = Array.from(new Set(processGraph.nodes.map(laneKey)));
  const laneSpacing = 220;
  const rowSpacing = 112;
  const width = Math.max(680, 150 + Math.max(0, lanes.length - 1) * laneSpacing + 150);
  const height = Math.max(340, 80 + Math.max(0, processGraph.nodes.length - 1) * rowSpacing + 100);
  const positions = new Map(processGraph.nodes.map((node, index) => [node.key, {
    x: 150 + lanes.indexOf(laneKey(node)) * laneSpacing,
    y: 70 + index * rowSpacing,
  }]));

  const zoomBy = (delta: number) => setZoom((value) => clampZoom(value + delta));
  const resetView = () => { setZoom(1); setPan({ x: 0, y: 0 }); };
  const beginDrag = (x: number, y: number) => { dragState.current = { active: true, startX: x, startY: y, panX: pan.x, panY: pan.y }; };
  const moveDrag = (x: number, y: number) => {
    if (!dragState.current.active) return;
    setPan({ x: dragState.current.panX + x - dragState.current.startX, y: dragState.current.panY + y - dragState.current.startY });
  };
  const endDrag = () => { dragState.current.active = false; };

  const graph = (
    <Card
      size="small"
      title={t('delivery.graph')}
      data-testid="execution-process-graph"
      extra={<Space size={4}>
        <Tooltip title={t('delivery.zoomOut')}><Button size="small" aria-label={t('delivery.zoomOut')} icon={<ZoomOutOutlined aria-hidden="true" />} onClick={() => zoomBy(-0.12)} /></Tooltip>
        <Tooltip title={t('delivery.zoomIn')}><Button size="small" aria-label={t('delivery.zoomIn')} icon={<ZoomInOutlined aria-hidden="true" />} onClick={() => zoomBy(0.12)} /></Tooltip>
        <Tooltip title={t('delivery.resetView')}><Button size="small" aria-label={t('delivery.resetView')} icon={<ReloadOutlined aria-hidden="true" />} onClick={resetView} /></Tooltip>
        <Tooltip title={fullscreen ? t('delivery.exitFullscreen') : t('delivery.fullscreen')}>
          <Button size="small" aria-label={fullscreen ? t('delivery.exitFullscreen') : t('delivery.fullscreen')} icon={fullscreen ? <CompressOutlined aria-hidden="true" /> : <FullscreenOutlined aria-hidden="true" />} onClick={() => setFullscreen((value) => !value)} />
        </Tooltip>
      </Space>}
      styles={{ body: { padding: '8px 10px' } }}
    >
      <div
        data-testid="execution-graph-viewport"
        data-zoom={zoom.toFixed(2)}
        data-pan={`${Math.round(pan.x)},${Math.round(pan.y)}`}
        onWheel={(event) => { event.preventDefault(); zoomBy(event.deltaY > 0 ? -0.08 : 0.08); }}
        onMouseDown={(event) => beginDrag(event.clientX, event.clientY)}
        onMouseMove={(event) => moveDrag(event.clientX, event.clientY)}
        onMouseUp={endDrag}
        onMouseLeave={endDrag}
        style={{ height: fullscreen ? 'calc(100vh - 190px)' : 420, border: '1px solid var(--aw-accent)', borderRadius: 6, background: 'var(--aw-panel)', overflow: 'hidden', cursor: 'grab' }}
      >
        <svg width="100%" height="100%" viewBox={`0 0 ${width} ${height}`} role="img" aria-label={t('delivery.graphAria')}>
          {markerDefs()}
          <g transform={`translate(${pan.x} ${pan.y}) scale(${zoom})`}>
            {lanes.map((lane, index) => <line key={lane} x1={150 + index * laneSpacing} y1="24" x2={150 + index * laneSpacing} y2={height - 24} stroke="var(--aw-border)" strokeWidth="2" strokeDasharray="5 5" />)}
            {processGraph.edges.map((edge, index) => {
              const source = nodesByKey.get(edge.sourceKey);
              const target = nodesByKey.get(edge.targetKey);
              const from = positions.get(edge.sourceKey);
              const to = positions.get(edge.targetKey);
              if (!source || !target || !from || !to) return null;
              const tone = authoritativeEdgeTone(edge.type);
              const sameLane = from.x === to.x;
              const path = sameLane
                ? `M ${from.x + 66} ${from.y + 24} C ${from.x + 136} ${from.y + 44}, ${to.x + 136} ${to.y - 44}, ${to.x + 66} ${to.y - 24}`
                : `M ${from.x} ${from.y + 31} C ${from.x} ${from.y + 64}, ${to.x} ${to.y - 64}, ${to.x} ${to.y - 31}`;
              const labelX = sameLane ? from.x + 138 : (from.x + to.x) / 2;
              const labelY = (from.y + to.y) / 2;
              return <Tooltip key={`${edge.sourceKey}-${edge.targetKey}-${index}`} title={t('delivery.edgeSummary', { label: runtimeEdgeLabel(edge, source.status), source: edge.sourceDispatchId ?? '-', target: edge.targetDispatchId ?? '-' })}>
                <g data-testid="execution-graph-edge" role="button" tabIndex={0} aria-label={t('delivery.edgeAria', { number: index + 1, source: graphAgentName(source), target: graphAgentName(target), label: runtimeEdgeLabel(edge, source.status) })} onClick={(event) => {
                  event.stopPropagation();
                  setSelection({ edge, index, source, target });
                }} style={{ cursor: 'pointer' }}>
                  <path d={path} fill="none" stroke={tone.color} strokeWidth="2" markerEnd={`url(#${tone.markerId})`} vectorEffect="non-scaling-stroke" />
                  <rect x={labelX - 104} y={labelY - 12} width="208" height="24" rx="5" fill={tone.background} stroke={tone.border} />
                  <text x={labelX} y={labelY + 4} textAnchor="middle" style={{ fontSize: 11, fill: tone.color, fontWeight: 600 }}>{t('delivery.edgeText', { number: index + 1, source: graphAgentName(source), target: graphAgentName(target), label: runtimeEdgeLabel(edge, source.status) })}</text>
                </g>
              </Tooltip>;
            })}
            {processGraph.nodes.map((node) => {
              const position = positions.get(node.key)!;
              const tone = authoritativeNodeTone(node.status);
              return <Tooltip key={node.key} title={t('delivery.nodeSummary', { name: graphAgentName(node), step: node.stepName || t('delivery.humanHandoff'), status: runtimeStatusLabel(node.status) })}>
                <g data-testid="execution-graph-worker" data-status={node.status || ''} role="button" tabIndex={0} aria-label={t('delivery.nodeAria', { name: graphAgentName(node), status: runtimeStatusLabel(node.status) })} onClick={(event) => {
                  event.stopPropagation();
                  setSelection({ node });
                  if (node.dispatchId != null) setTraceNode(node);
                }} style={{ cursor: 'pointer' }}>
                  <rect x={position.x - 66} y={position.y - 31} width="132" height="62" rx="7" fill={tone.background} stroke={tone.border} strokeWidth="1.4" />
                  <text x={position.x} y={position.y - 9} textAnchor="middle" style={{ fontSize: 13, fontWeight: 600, fill: 'var(--aw-text)' }}>{graphAgentName(node)}</text>
                  <text x={position.x} y={position.y + 9} textAnchor="middle" style={{ fontSize: 11, fill: 'var(--aw-muted)' }}>{node.stepName || t('delivery.humanHandoff')}</text>
                  <text x={position.x} y={position.y + 24} textAnchor="middle" style={{ fontSize: 10, fill: 'var(--aw-muted)' }}>{node.dispatchId != null ? `#${node.dispatchId}` : ''} {formatDuration(node.durationMs)}</text>
                </g>
              </Tooltip>;
            })}
          </g>
        </svg>
      </div>
      <div data-testid="execution-graph-detail-panel" style={{ marginTop: 8, minHeight: 58, padding: '7px 9px', border: '1px solid var(--aw-border)', borderRadius: 6, background: 'var(--aw-raised)' }}>
        {selection ? <Space direction="vertical" size={1}><Text strong style={{ fontSize: 12 }}>{selection.title}</Text><Text type="secondary" style={{ fontSize: 12 }}>{t('delivery.selection', { status: selection.status, duration: selection.duration })}</Text><Text type="secondary" style={{ fontSize: 12 }}>{selection.detail}</Text></Space> : <Text type="secondary" style={{ fontSize: 12 }}>{t('delivery.graphHint')}</Text>}
      </div>
      <RuntimeTraceDrawer node={traceNode} processGraph={processGraph} onClose={() => setTraceNode(null)} />
    </Card>
  );

  return fullscreen ? <div data-testid="execution-graph-fullscreen" style={{ position: 'fixed', inset: 0, zIndex: 1000, padding: 16, background: 'var(--aw-panel)' }}>{graph}</div> : graph;
}

async function downloadArtifact(artifact: Artifact) {
  const url = await getArtifactDownloadUrl(artifact.id);
  const link = document.createElement('a');
  link.href = url;
  link.download = basename(artifact.name);
  link.rel = 'noreferrer';
  link.style.display = 'none';
  document.body.appendChild(link);
  link.click();
  link.remove();
}

function ArtifactRows({
  artifacts,
  loading,
  onPreview,
}: {
  artifacts: Artifact[];
  loading?: boolean;
  onPreview: (artifact: Artifact) => void;
}) {
  const { t } = useTranslation('workitem');
  if (loading) {
    return <Spin size="small" />;
  }
  if (artifacts.length === 0) {
    return <Text type="secondary" style={{ fontSize: 12 }}>{t('artifacts.none')}</Text>;
  }
  return (
    <Space direction="vertical" size={4} style={{ width: '100%' }}>
      {artifacts.map((artifact) => (
        <Tooltip
          key={String(artifact.id)}
          title={t('artifacts.fileSummary', { name: artifact.name, type: artifactTypeLabel(artifact.type), size: artifact.size != null ? t('artifacts.bytes', { size: artifact.size }) : '' })}
        >
          <div style={{ display: 'flex', alignItems: 'center', gap: 4, width: '100%' }}>
            <FileTextOutlined aria-hidden="true" style={{ color: 'var(--aw-muted)', flexShrink: 0 }} />
            <Text
              style={{
                flex: 1,
                minWidth: 0,
                fontSize: 12,
                overflow: 'hidden',
                textOverflow: 'ellipsis',
                whiteSpace: 'nowrap',
              }}
            >
              {basename(artifact.name)}
            </Text>
            <Button
              type="text"
              size="small"
              aria-label={t('artifacts.previewName', { name: artifact.name })}
              icon={<EyeOutlined aria-hidden="true" />}
              onClick={() => onPreview(artifact)}
              style={{ flexShrink: 0 }}
            />
            <Button
              type="text"
              size="small"
              aria-label={t('artifacts.downloadName', { name: artifact.name })}
              icon={<DownloadOutlined aria-hidden="true" />}
              onClick={() => void downloadArtifact(artifact)}
              style={{ flexShrink: 0 }}
            />
          </div>
        </Tooltip>
      ))}
    </Space>
  );
}

function ArtifactGroup({ artifacts, steps, executions: attempts, loading, onPreview }: {
  artifacts: Artifact[];
  steps: ConvergedDeliveryStep[];
  executions?: DispatchAttempt[];
  loading?: boolean;
  onPreview: (artifact: Artifact) => void;
}) {
  const { t } = useTranslation('workitem');
  if (loading) return <Spin size="small" />;
  const executions = groupArtifactsByExecution(steps, artifacts, attempts);
  if (!artifacts.length) return <Text type="secondary">{t('artifacts.none')}</Text>;
  return <ConfigProvider theme={{
    token: { fontSize: 12, lineHeight: 1.5 },
    components: { Collapse: { headerPadding: '4px 0', contentPadding: '0 0 4px 12px' } },
  }}><Collapse
    ghost
    size="small"
    defaultActiveKey={executions[0] ? [String(executions[0].dispatchId)] : []}
    items={executions.map((execution, index) => ({
      key: String(execution.dispatchId),
      label: <span title={`Dispatch ${execution.dispatchId}`} style={{ fontSize: 12, lineHeight: '18px', overflowWrap: 'anywhere' }}>{t('artifacts.executionSummary', { latest: index === 0 ? t('artifacts.latest') : '', label: execution.label, count: execution.artifacts.length })}</span>,
      children: execution.artifacts.length === 0 ? <Text type="secondary">{t('artifacts.executionEmpty')}</Text> : <Collapse
        ghost
        size="small"
        defaultActiveKey={['handoff', 'evidence']}
        items={artifactCategories.flatMap(category => {
          const files = artifactsForReadingCategory(execution.artifacts, category.key);
          return files.length ? [{
            key: category.key,
            label: <Tooltip title={category.hint}><Text style={{ fontSize: 12 }}>{t('artifacts.categoryCount', { label: category.label, count: files.length })}</Text></Tooltip>,
            children: <>
              <ArtifactRows artifacts={files} onPreview={onPreview} />
            </>,
          }] : [];
        })}
      />,
    }))}
  /></ConfigProvider>;
}

type ExecutionEntry = { agent: ConvergedAgentProgress; dispatchId: number; attempt: DispatchAttempt; steps: ConvergedDeliveryStep[] };

function stageFromTiming(timing: StepTiming): ConvergedDeliveryStep {
  return {
    stepId: timing.stepId,
    stepKey: null,
    name: timing.name,
    status: timing.status,
    planStatus: null,
    sourceAttempt: null,
    agentName: null,
    executorId: null,
    executorName: null,
    error: null,
    subSteps: null,
    durationMs: timing.durationMs,
    attempts: null,
    usage: null,
  };
}

// 后端按 dispatch 投影的阶段快照：本 dispatch 执行过的阶段即使没有落在派生 attempt 关联里
// （例如同一 dispatch 连续跑完入口阶段与后续阶段）也必须进入详情；pending 表示该 dispatch
// 未执行该阶段，不进详情，保证切换 dispatch 时只看到当前所选执行的阶段。
function withExecutionStages(entry: ExecutionEntry): ExecutionEntry {
  const timings = entry.attempt.stepTimings;
  if (!timings?.length) return entry;
  const known = new Map(entry.steps.map(step => [step.stepId, step]));
  const snapshotIds = new Set(timings.map(timing => timing.stepId));
  const stages = timings.flatMap(timing => {
    const step = known.get(timing.stepId);
    if (step) return [step];
    return timing.status === 'pending' ? [] : [stageFromTiming(timing)];
  });
  if (!stages.length) return entry;
  return { ...entry, steps: [...stages, ...entry.steps.filter(step => !snapshotIds.has(step.stepId))] };
}

// Dispatch IDs are creation-ordered; traversing agent/step arrays is not a handoff timeline.
function executionEntries(agents: ConvergedAgentProgress[], executions?: DispatchAttempt[] | null): ExecutionEntry[] {
  const entries = new Map<number, ExecutionEntry>();
  agents.forEach(agent => agent.steps.forEach(step => step.attempts?.forEach(attempt => {
    const existing = entries.get(attempt.dispatchId);
    if (existing) {
      if (!existing.steps.some(value => value.stepId === step.stepId)) existing.steps.push(step);
      // A later step may carry more recent status/action metadata for the same dispatch.
      if (attempt.canPause || attempt.canContinue) existing.attempt = attempt;
    } else entries.set(attempt.dispatchId, { agent, dispatchId: attempt.dispatchId, attempt, steps: [step] });
  })));
  executions?.forEach(attempt => {
    const existing = entries.get(attempt.dispatchId);
    if (existing) {
      existing.attempt = { ...existing.attempt, ...attempt, canPause: existing.attempt.canPause, canContinue: existing.attempt.canContinue };
    } else {
      const agent = agents.find(value => value.agentId === attempt.agentId) ?? {
        agentId: attempt.agentId ?? -attempt.dispatchId, agentName: attempt.agentName ?? '—',
        status: 'pending' as const, durationMs: null, steps: [],
      };
      entries.set(attempt.dispatchId, { agent, dispatchId: attempt.dispatchId, attempt, steps: [] });
    }
  });
  return [...entries.values()].sort((a, b) => a.dispatchId - b.dispatchId).map(withExecutionStages);
}

function DispatchFailureDetails({ dispatchId, error, status }: { dispatchId: number; error: string; status: string | null }) {
  const { t } = useTranslation('workitem');
  const heading = t(status === 'FAILED' || status === 'TIMEOUT' ? 'delivery.dispatchFailure' : 'delivery.dispatchDiagnostic', { id: dispatchId });
  const checklist = /checklist not fully checked: "([^"]+)" remains pending/.exec(error);
  const artifact = /code=ARTIFACT_REFERENCE_INVALID path="([^"]+)"/.exec(error);
  return <div role="note" aria-label={heading}>
    <Text strong>{heading}</Text>
    {checklist && <div>{t('delivery.checklistPending', { id: checklist[1] })}</div>}
    {artifact && <div>{t('delivery.invalidArtifactReference', { path: artifact[1] })}</div>}
    <pre style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere', color: 'var(--aw-error)' }}>{error}</pre>
  </div>;
}

function DeliveryWorkbench({ agents, progress, artifacts, artifactsLoading, onContinue, continuingDispatchId, onPause, pausingDispatchId, renderRecovery, restartHistory }: {
  agents: ConvergedAgentProgress[];
  progress: DeliveryProgressModel | null | undefined;
  artifacts: Artifact[];
  artifactsLoading?: boolean;
} & Pick<DeliveryProgressProps, 'onContinue' | 'continuingDispatchId' | 'onPause' | 'pausingDispatchId' | 'renderRecovery' | 'restartHistory'>) {
  const { t } = useTranslation('workitem');
  const entries = useMemo(() => executionEntries(agents, progress?.executions), [agents, progress?.executions]);
  const currentAgent = [...agents].filter(agent => agent.status === 'active').sort((a, b) =>
    Math.max(0, ...orderedDispatchIds(b)) - Math.max(0, ...orderedDispatchIds(a)))[0] ?? null;
  const attentionAgent = currentAgent ?? [...agents].reverse().find(agent => agent.status === 'paused' || agent.status === 'failed') ?? null;
  const currentEntry = currentAgent ? [...entries].reverse().find(entry => entry.agent.agentId === currentAgent.agentId && ['PENDING', 'PACKAGING', 'DISPATCHED', 'ACKED', 'RUNNING', 'PAUSING', 'PAUSE_FAILED', 'WAITING_FOR_PAUSE'].includes(entry.attempt.status ?? ''))
    ?? [...entries].reverse().find(entry => entry.agent.agentId === currentAgent.agentId && entry.attempt.canPause)
    ?? [...entries].reverse().find(entry => entry.agent.agentId === currentAgent.agentId) : null;
  const defaultEntry = currentEntry ?? [...entries].reverse().find(entry => entry.agent.agentId === attentionAgent?.agentId) ?? entries[entries.length - 1];
  const [expanded, setExpanded] = useState(false);
  const [previewArtifact, setPreviewArtifact] = useState<Artifact | null>(null);
  const [tab, setTab] = useState('terminal');
  const [pinnedDispatch, setPinnedDispatch] = useState<number | null>(null);
  const [pinnedAgent, setPinnedAgent] = useState<number | null>(null);
  const [selectedStepId, setSelectedStepId] = useState<number | null>(null);
  const [railVisible, setRailVisible] = useState(() => window.innerWidth >= 760);
  const [railWidth, setRailWidth] = useState(260);
  const followCurrent = pinnedDispatch === null && pinnedAgent === null;
  const selectedEntry = pinnedDispatch !== null ? entries.find(entry => entry.dispatchId === pinnedDispatch) : pinnedAgent !== null ? [...entries].reverse().find(entry => entry.agent.agentId === pinnedAgent) : defaultEntry;
  const selectedAgent = pinnedAgent !== null ? agents.find(agent => agent.agentId === pinnedAgent) : selectedEntry?.agent ?? attentionAgent ?? agents[agents.length - 1];
  const selectedStep = selectedAgent?.steps.find(step => step.stepId === selectedStepId);
  const currentStep = currentAgent?.steps.find(step => step.status === 'active');
  const selectedLive = selectedEntry?.dispatchId === currentEntry?.dispatchId && currentAgent !== null;
  const actionAttempt = [...(attentionAgent?.steps.flatMap(step => step.attempts ?? []) ?? [])]
    .filter(attempt => attempt.dispatchId === defaultEntry?.dispatchId && !['COMMENT_INTERACTION', 'SIDE_INTERACTION', 'CANONICAL_INTERACTION'].includes(attempt.resumeMode ?? ''))
    .sort((a, b) => b.dispatchId - a.dispatchId)
    .find(attempt => currentAgent ? attempt.canPause : attempt.canContinue || attempt.canPause);
  const [handoffId, setHandoffId] = useState<number | null>(null);
  const previousCurrent = useRef(currentEntry?.dispatchId ?? null);
  useEffect(() => {
    const next = currentEntry?.dispatchId ?? null;
    if (previousCurrent.current !== null && next !== null && next !== previousCurrent.current) setHandoffId(next);
    previousCurrent.current = next;
  }, [currentEntry?.dispatchId]);
  const open = (nextTab: string) => { setTab(nextTab); setExpanded(true); };
  const selectEntry = (entry: ExecutionEntry) => { setPinnedDispatch(entry.dispatchId); setPinnedAgent(null); setSelectedStepId(null); };
  const returnToCurrent = () => { setPinnedDispatch(null); setPinnedAgent(null); setSelectedStepId(null); setHandoffId(null); };
  const attentionLabel = attentionAgent?.steps.some(stepIsPausing) ? { text: t('delivery.pausing'), color: 'warning' } : attentionAgent?.steps.some(step => step.attempts?.some(attempt => attempt.error?.startsWith('CANCEL_REQUESTED:'))) ? { text: t('delivery.cancelling'), color: 'warning' } : attentionAgent ? statusLabel(attentionAgent.status, currentEntry?.attempt.status ?? attentionAgent.executionStatus, currentEntry?.attempt.waitingReason ?? attentionAgent.waitingReason) : { text: t('workbench.noExecution'), color: 'default' };
  const stageStatus = currentStep ? [currentStep.planStatus === 'RUN' ? t('delivery.rerun') : null, attentionLabel.text, formatDuration(currentStep.durationMs)].filter(Boolean).join(' · ') : null;
  const summary = <section className="delivery-workbench-current" data-testid="delivery-current-execution">
    <div className="delivery-workbench-heading">
      <div className="delivery-workbench-identity">
        {currentAgent && !currentAgent.steps.some(stepIsPausing) && <LoadingOutlined aria-hidden="true" className="delivery-workbench-active" />}
        <div>
          <Text strong data-testid="current-agent-name">{attentionAgent?.agentName ?? t('workbench.noActiveAgent')}</Text>
          <Tag color={attentionLabel.color}>{attentionLabel.text}</Tag>
          {currentStep && <div>{currentStep.name}</div>}
          {stageStatus && <Text type="secondary" style={{ fontSize: 12 }}>{stageStatus}</Text>}
        </div>
      </div>
      <Space wrap>
        {!expanded && <Button type="primary" onClick={() => open('terminal')}>{t('workbench.openTerminal')}</Button>}
        {actionAttempt && <AttemptActionButton attempt={actionAttempt} onContinue={onContinue} continuingDispatchId={continuingDispatchId} onPause={onPause} pausingDispatchId={pausingDispatchId} />}
      </Space>
    </div>
    {attentionAgent?.currentActivity && <Text type="secondary" data-testid="agent-current-activity">{attentionAgent.currentActivity}</Text>}
    {currentEntry && <div className="delivery-workbench-activity"><LiveActivityPanel key={currentEntry.dispatchId} dispatchId={currentEntry.dispatchId} enabled summaryOnly /></div>}
  </section>;
  const recentEntries = [...entries].reverse().slice(0, 3);
  if (currentEntry && !recentEntries.some(entry => entry.dispatchId === currentEntry.dispatchId)) recentEntries[recentEntries.length - 1] = currentEntry;
  const sidebarEntries = selectedEntry && !recentEntries.some(entry => entry.dispatchId === selectedEntry.dispatchId) ? [selectedEntry, ...recentEntries] : recentEntries;
  const executionStatusColor = (status: string | null) => status === 'SUCCEEDED' ? 'success' : status === 'FAILED' || status === 'TIMEOUT' ? 'error' : status === 'PAUSED' || status === 'PAUSING' || status === 'PAUSE_FAILED' ? 'warning' : status === 'CANCELED' || !status ? 'default' : 'processing';
  const executionTimes = (attempt: DispatchAttempt) => <div className="delivery-workbench-entry-meta">
    <div>{t('workbench.startedAt', { time: attempt.startedAt ? formatDateTime(attempt.startedAt) : t('workbench.unknownTime') })}</div>
    <div>{t('workbench.endedAt', { time: attempt.endedAt ? formatDateTime(attempt.endedAt) : attempt.status && ['SUCCEEDED', 'FAILED', 'TIMEOUT', 'CANCELED'].includes(attempt.status) ? t('workbench.unknownTime') : t('workbench.notEnded') })}</div>
  </div>;
  const timeline = (visibleEntries: ExecutionEntry[], showPending = false) => <div className="delivery-workbench-timeline" aria-label={t('workbench.handoffs')}>
    {visibleEntries.map(entry => <div key={entry.dispatchId}>
      <button type="button" className={`delivery-workbench-entry ${selectedEntry?.dispatchId === entry.dispatchId ? 'is-selected' : ''}`}
        aria-pressed={selectedEntry?.dispatchId === entry.dispatchId} onClick={() => { selectEntry(entry); open('terminal'); }}>
        <Tag color={executionStatusColor(entry.attempt.status)}>{dispatchExecutionLabel(entry.attempt.status, entry.attempt.waitingReason)}</Tag>
        <span className="delivery-workbench-entry-name" data-testid="agent-progress-name">{entry.agent.agentName}</span>
        <span className="delivery-workbench-entry-meta">{t('workbench.sessionDuration', { id: entry.dispatchId, duration: formatDuration(entry.attempt.durationMs) })}</span>
        {entry.attempt.executorName && <span className="delivery-workbench-entry-meta">{t('delivery.runner', { name: entry.attempt.executorName })}</span>}
        {expanded && executionTimes(entry.attempt)}
      </button>
    </div>)}
    {showPending && agents.filter(agent => !entries.some(entry => entry.agent.agentId === agent.agentId)).slice(0, 3).map(agent => <button key={agent.agentId} type="button" className="delivery-workbench-entry" onClick={() => { setPinnedAgent(agent.agentId); setPinnedDispatch(null); setSelectedStepId(null); if (!expanded) open('details'); }}>
      <span>{statusLabel(agent.status, agent.executionStatus, agent.waitingReason).text}</span><span className="delivery-workbench-entry-name" data-testid="agent-progress-name">{agent.agentName}</span><span className="delivery-workbench-entry-meta">{formatDuration(agent.durationMs)}</span>
    </button>)}
  </div>;
  const stagePlanLabel = (step: ConvergedDeliveryStep) => step.planStatus === 'REUSED' || step.status === 'reused' ? t('delivery.reusedPrevious') : step.planStatus === 'RUN' ? t('delivery.rerun') : null;
  const stageList = selectedAgent && <div className="delivery-workbench-stages" aria-label={t('workbench.stages')}>
    {selectedAgent.steps.map((step, index) => <button key={step.stepId} type="button" className={`delivery-workbench-stage ${step.stepId === selectedStepId ? 'is-selected' : ''}`} aria-pressed={step.stepId === selectedStepId}
      onClick={() => { setPinnedDispatch(selectedEntry?.dispatchId ?? null); setPinnedAgent(selectedAgent.agentId); setSelectedStepId(step.stepId); setTab('details'); }}>
      <StepIcon step={step} index={index} pausing={stepIsPausing(step)} /><span>{step.name}</span><span className="delivery-workbench-entry-meta">{stagePlanLabel(step)}</span>
    </button>)}
  </div>;
  const details = selectedAgent && <>
    <div className="delivery-workbench-detail-heading"><Text strong>{selectedStep?.name ?? selectedAgent.agentName}</Text><Space wrap>
      {selectedAgent.durationMs != null && <Text type="secondary">{formatDuration(selectedAgent.durationMs)}</Text>}
      {selectedAgent.usage && <TokenUsageBadge usage={selectedAgent.usage} />}
    </Space></div>
    {selectedEntry && executionTimes(selectedEntry.attempt)}
    {selectedEntry?.attempt.error && <DispatchFailureDetails dispatchId={selectedEntry.dispatchId} error={selectedEntry.attempt.error} status={selectedEntry.attempt.status} />}
    {selectedEntry && selectedEntry.steps.length === 0 ? <>
      <DispatchActivityFeed dispatchId={selectedEntry.dispatchId} status={selectedEntry.attempt.status} />
    </> : <StepList steps={selectedStep ? [selectedStep] : progress?.executions?.length ? selectedEntry?.steps ?? selectedAgent.steps : selectedAgent.steps} onContinue={onContinue} continuingDispatchId={continuingDispatchId} onPause={onPause} pausingDispatchId={pausingDispatchId} />}
  </>;
  return <>
    {!expanded && summary}
    <div className="delivery-workbench-section-heading"><Text strong>{t('workbench.handoffs')}</Text><Space wrap><Button type="text" onClick={() => open('history')}>{t('workbench.historyCount', { count: entries.length })}</Button><Button type="text" onClick={() => open('details')}>{t('workbench.viewDetails')}</Button></Space></div>
    {!expanded && timeline(recentEntries, true)}
    <Space wrap className="delivery-workbench-footer"><Button onClick={() => open('timing')}>{t('stepTiming.title')}</Button><Button onClick={() => open('activity')}>{t('workbench.activity')}</Button><Button onClick={() => open('artifacts')}>{t('artifacts.agentCount', { count: artifacts.length })}</Button><Button onClick={() => open('graph')}>{t('workbench.graph')}</Button></Space>
    <Modal open={expanded} onCancel={() => setExpanded(false)} footer={null} width="calc(100vw - 32px)" style={{ top: 16 }} className="delivery-workbench-modal"
      title={<div className="delivery-workbench-heading"><span>{t('delivery.title')}</span><Button icon={<CompressOutlined aria-hidden="true" />} onClick={() => setExpanded(false)}>{t('workbench.exit')}</Button></div>}>
      <div className="delivery-workbench-expanded" data-testid="delivery-workbench">
        {expanded && summary}
        {handoffId !== null && <Alert showIcon type="info" message={t(followCurrent ? 'workbench.handoffFollowing' : 'workbench.handoffPinned', { name: currentAgent?.agentName ?? '' })} closable onClose={() => setHandoffId(null)} />}
        <div className={`delivery-workbench-layout ${railVisible ? '' : 'without-rail'}`} style={{ '--delivery-rail-width': `${railWidth}px` } as React.CSSProperties}>
          <aside className="delivery-workbench-rail" hidden={!railVisible}>
            <div className="delivery-workbench-section-heading"><Text strong>{t('workbench.handoffs')}</Text></div>
            {timeline(sidebarEntries, true)}
            <Button type="text" onClick={() => setTab('history')}>{t('workbench.historyCount', { count: entries.length })}</Button>
            <div className="delivery-workbench-section-heading"><Text strong>{t('workbench.stages')}</Text></div>
            {stageList}
            <label className="delivery-workbench-width">{t('workbench.sidebarWidth')}<input type="range" min={200} max={400} step={20} value={railWidth} onChange={event => setRailWidth(Number(event.target.value))} /></label>
          </aside>
          <main className="delivery-workbench-main">
            <div className="delivery-workbench-session">
              <Button size="small" onClick={() => setRailVisible(!railVisible)}>{t(railVisible ? 'workbench.hideSidebar' : 'workbench.showSidebar')}</Button>
              <Text>{selectedAgent?.agentName}</Text>
              {entries.length > 0 && <Select aria-label={t('workbench.executionSession')} data-testid="live-activity-dispatch-select" value={selectedEntry?.dispatchId} onChange={id => { const entry = entries.find(value => value.dispatchId === id); if (entry) selectEntry(entry); }} options={entries.map(entry => ({ value: entry.dispatchId, label: t('delivery.dispatchOption', { id: entry.dispatchId, latest: entry.dispatchId === defaultEntry?.dispatchId ? t('delivery.latest') : '' }) }))} />}
              <Text type="secondary">{t(followCurrent ? currentEntry ? 'workbench.following' : 'workbench.latestSession' : 'workbench.history')}</Text>
              {!followCurrent && <Button onClick={returnToCurrent}>{t('workbench.returnCurrent')}</Button>}
            </div>
            {selectedEntry && renderRecovery?.(selectedEntry.dispatchId)}
            <Tabs activeKey={tab} onChange={setTab} items={[
              { key: 'terminal', label: t('runtimeLog.terminal'), children: selectedEntry ? <RuntimeLogPanel key={selectedEntry.dispatchId} dispatchId={selectedEntry.dispatchId} terminal embedded enabled={expanded && tab === 'terminal'} /> : <Empty description={t('workbench.noSession')} /> },
              { key: 'activity', label: t('workbench.activity'), children: selectedEntry ? <LiveActivityPanel key={selectedEntry.dispatchId} dispatchId={selectedEntry.dispatchId} enabled={selectedLive && expanded && tab === 'activity'} /> : <Empty description={t('workbench.noSession')} /> },
              { key: 'history', label: t('workbench.historyCount', { count: entries.length }), children: <>{restartHistory}{timeline([...entries].reverse())}</> },
              { key: 'details', label: t('workbench.details'), children: details },
              { key: 'timing', label: t('stepTiming.title'), children: selectedEntry ? <StepDurationChart key={selectedEntry.dispatchId} dispatchId={selectedEntry.dispatchId} steps={selectedEntry.attempt.stepTimings} /> : <Empty description={t('workbench.noSession')} /> },
              { key: 'artifacts', label: t('artifacts.agentCount', { count: selectedAgent ? artifactsForAgent(selectedAgent, artifacts, progress?.executions).length : artifacts.length }), children: <ArtifactGroup key={selectedAgent?.agentId ?? 'all'} executions={progress?.executions?.filter(attempt => !selectedAgent || attempt.agentId === selectedAgent.agentId)} steps={selectedAgent?.steps ?? []} artifacts={selectedAgent ? artifactsForAgent(selectedAgent, artifacts, progress?.executions) : artifacts} loading={artifactsLoading} onPreview={setPreviewArtifact} /> },
              { key: 'graph', label: t('workbench.graph'), children: <AuthoritativeExecutionProcessGraph processGraph={progress?.processGraph} /> },
            ]} />
          </main>
        </div>
      </div>
      <ArtifactPreviewModal
        open={expanded && previewArtifact != null}
        artifact={previewArtifact}
        onClose={() => setPreviewArtifact(null)}
      />
    </Modal>
  </>;
}

export function DeliveryProgress({ showWorkflowPlan = true, steps = [], progress, artifacts = [], artifactsLoading, loading, terminalStatus, onContinue, continuingDispatchId, onPause, pausingDispatchId, renderRecovery, restartHistory }: DeliveryProgressProps) {
  const { t } = useTranslation('workitem');

  const agents = useMemo(() => {
    const terminal = normalizeTerminalStatus(terminalStatus);
    const agents: ConvergedAgentProgress[] = (progress?.agents ?? []).map((agent) => ({
      ...agent,
      status: terminal && (agent.status === 'active' || agent.status === 'paused')
        ? terminal === 'SUCCEEDED' ? 'finished' : terminal === 'FAILED' ? 'failed' : 'cancelled'
        : agent.status,
      steps: convergeStepsForTerminalStatus(agent.steps, terminalStatus),
    }));
    const seenAgentIds = new Set<number>();
    [...(progress?.executions ?? [])].sort((a, b) => b.dispatchId - a.dispatchId).forEach(execution => {
      const agentId = execution.agentId ?? -execution.dispatchId;
      if (seenAgentIds.has(agentId)) return;
      seenAgentIds.add(agentId);
      const status: AgentDeliveryProgress['status'] = execution.status === 'SUCCEEDED' ? 'finished'
        : execution.status === 'FAILED' || execution.status === 'TIMEOUT' ? 'failed'
        : execution.status === 'CANCELED' ? 'cancelled' : execution.status === 'PAUSED' ? 'paused' : 'active';
      const existing = agents.find(agent => agent.agentId === agentId);
      if (existing) {
        if (!terminal) {
          existing.status = status;
          existing.currentDispatchId = execution.dispatchId;
          existing.executionStatus = execution.status;
          existing.waitingReason = execution.waitingReason;
        }
        return;
      }
      agents.push({ agentId, agentName: execution.agentName ?? '—', currentDispatchId: execution.dispatchId, executionStatus: execution.status, waitingReason: execution.waitingReason,
        status: terminal ? terminal === 'SUCCEEDED' ? 'finished' : terminal === 'FAILED' ? 'failed' : 'cancelled' : status,
        durationMs: execution.durationMs, steps: [] });
    });
    return agents;
  }, [progress?.agents, progress?.executions, terminalStatus]);

  if (loading) {
    return (
      <Card size="small" title={t('delivery.title')}>
        <div style={{ textAlign: 'center', padding: 16 }}>
          <Spin size="small" />
        </div>
      </Card>
    );
  }

  const displaySteps = agents.length > 0 ? [] : convergeStepsForTerminalStatus(progress?.steps ?? steps, terminalStatus);
  const cardTitle = (
    <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
      <span style={{ flex: 1 }}>{t('delivery.title')}</span>
      {progress?.totalDurationMs != null && (
        <Tooltip title={t('delivery.durationHint')}>
          <Text type="secondary" style={{ fontSize: 12, fontWeight: 400 }}>
            {t('delivery.totalDuration', { duration: formatDuration(progress.totalDurationMs) })}
          </Text>
        </Tooltip>
      )}
    </div>
  );

  if (agents.length > 0) {
    return (
      <Space direction="vertical" style={{ width: '100%' }} size={8}>
        {showWorkflowPlan && progress?.workflowPlan && <WorkflowPlanSummary plan={progress.workflowPlan} />}
        <Card size="small" title={cardTitle} data-testid="delivery-progress-card" styles={{ body: { padding: '6px 8px' } }}>
          <DeliveryWorkbench renderRecovery={renderRecovery} restartHistory={restartHistory} agents={agents} progress={progress} artifacts={artifacts} artifactsLoading={artifactsLoading}
            onContinue={onContinue} continuingDispatchId={continuingDispatchId} onPause={onPause} pausingDispatchId={pausingDispatchId} />
        </Card>
        <AuthoritativeExecutionProcessGraph processGraph={progress?.processGraph} />
      </Space>
    );
  }

  return (
    <Space direction="vertical" style={{ width: '100%' }} size={8}>
      {showWorkflowPlan && progress?.workflowPlan && <WorkflowPlanSummary plan={progress.workflowPlan} />}
      <Card size="small" title={cardTitle} data-testid="delivery-progress-card" styles={{ body: { padding: '6px 8px' } }}>
        <StepList steps={displaySteps} onContinue={onContinue} continuingDispatchId={continuingDispatchId} onPause={onPause} pausingDispatchId={pausingDispatchId} />
      </Card>
    </Space>
  );
}
