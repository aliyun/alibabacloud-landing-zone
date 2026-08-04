import { useRef, useState } from 'react';
import { Card, Typography, Space, Spin, Collapse, Tag, Tooltip, Button } from 'antd';
import { CheckCircleFilled, CloseCircleFilled, CompressOutlined, DownloadOutlined, EyeOutlined, FileTextOutlined, FullscreenOutlined, LoadingOutlined, PauseCircleOutlined, PlayCircleOutlined, ReloadOutlined, ZoomInOutlined, ZoomOutOutlined } from '@ant-design/icons';
import type { AgentDeliveryProgress, Artifact, DeliveryProgress as DeliveryProgressModel, DeliveryStep, DispatchAttempt, ProcessGraph, ProcessGraphEdge, ProcessGraphNode, SubStep, WorkflowPlan } from '@/shared/types/workitem';
import { basename } from '@/shared/lib/artifactLinking';
import { getArtifactDownloadUrl } from '../api';
import { ArtifactPreviewModal } from './ArtifactPreviewModal';
import { RuntimeTraceDrawer } from './RuntimeTraceDrawer';

const { Text } = Typography;

type DisplayAttempt = DispatchAttempt & {
  displayIndex: number;
};

interface GraphSelection {
  title: string;
  status: string;
  duration: string;
  detail: string;
}

interface DeliveryProgressProps {
  steps?: DeliveryStep[];
  progress?: DeliveryProgressModel | null;
  artifacts?: Artifact[];
  artifactsLoading?: boolean;
  loading?: boolean;
  onContinue?: (dispatchId: number) => void;
  continuingDispatchId?: number | null;
  onPause?: (dispatchId: number) => void;
  pausingDispatchId?: number | null;
}

export function formatDuration(ms: number | null | undefined): string {
  if (ms == null || ms < 0) {
    return '';
  }
  const totalSeconds = Math.round(ms / 1000);
  if (totalSeconds < 60) {
    return `${totalSeconds}秒`;
  }
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  if (minutes < 60) {
    return seconds > 0 ? `${minutes}分${seconds}秒` : `${minutes}分`;
  }
  const hours = Math.floor(minutes / 60);
  const remMinutes = minutes % 60;
  return remMinutes > 0 ? `${hours}时${remMinutes}分` : `${hours}时`;
}

function StepIcon({ step, index, pausing = false }: { step: DeliveryStep; index: number; pausing?: boolean }) {
  if (step.status === 'done' || step.status === 'reused' || step.planStatus === 'REUSED') {
    return <CheckCircleFilled style={{ color: '#52c41a', fontSize: 16 }} />;
  }
  if (pausing) {
    return <PauseCircleOutlined style={{ color: '#d48806', fontSize: 16 }} />;
  }
  if (step.status === 'active') {
    return <LoadingOutlined style={{ color: '#ff6a00', fontSize: 16 }} />;
  }
  if (step.status === 'failed') {
    return <CloseCircleFilled style={{ color: '#ff4d4f', fontSize: 16 }} />;
  }
  if (step.status === 'paused') {
    return <PauseCircleOutlined style={{ color: '#d48806', fontSize: 16 }} />;
  }
  if (step.status === 'skipped' || step.status === 'stale' || step.planStatus === 'SKIPPED') {
    return <span style={{ color: step.status === 'stale' ? '#d46b08' : '#8c8c8c', fontSize: 11 }}>—</span>;
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
        border: '1.5px solid #d9d9d9',
        fontSize: 10,
        color: '#bfbfbf',
        lineHeight: 1,
      }}
    >
      {index + 1}
    </span>
  );
}

function SubStepDot({ status }: { status: SubStep['status'] }) {
  const colorMap: Record<SubStep['status'], string> = {
    done: '#52c41a',
    active: '#ff6a00',
    pending: '#d9d9d9',
    failed: '#ff4d4f',
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

function findActionTargetStep(steps: DeliveryStep[], ownerStep: DeliveryStep, attempt: DispatchAttempt): DeliveryStep | null {
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

function buildAttemptViews(steps: DeliveryStep[]): Map<number, DisplayAttempt[]> {
  const byStep = new Map<number, DisplayAttempt[]>();
  const relocatedActions = new Map<number, Set<number>>();

  steps.forEach((step) => {
    step.attempts?.forEach((attempt, i) => {
      const target = findActionTargetStep(steps, step, attempt);
      if (target && target.stepId !== step.stepId) {
        relocatedActions.set(step.stepId, (relocatedActions.get(step.stepId) ?? new Set()).add(attempt.dispatchId));
        byStep.set(target.stepId, [
          ...(byStep.get(target.stepId) ?? []),
          { ...attempt, displayIndex: i },
        ]);
      }
    });
  });

  steps.forEach((step) => {
    const strippedActionIds = relocatedActions.get(step.stepId);
    const attempts = step.attempts?.map((attempt, i) => ({
      ...attempt,
      ...(strippedActionIds?.has(attempt.dispatchId) ? { canContinue: false, canPause: false } : {}),
      displayIndex: i,
    })) ?? [];
    byStep.set(step.stepId, [...attempts, ...(byStep.get(step.stepId) ?? [])]);
  });

  return byStep;
}

function pauseLabel(status: string | null | undefined): string {
  if (status === 'PAUSING') {
    return '暂停中';
  }
  if (status === 'PAUSE_FAILED') {
    return '重试暂停';
  }
  return '暂停';
}

function stepIsPausing(step: DeliveryStep): boolean {
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
  if (attempt.canContinue && onContinue) {
    return (
      <Button
        size="small"
        type="link"
        aria-label="继续"
        icon={<PlayCircleOutlined />}
        loading={continuingDispatchId === attempt.dispatchId}
        onClick={() => onContinue(attempt.dispatchId)}
      >
        继续
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
        icon={<PauseCircleOutlined />}
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
  step: DeliveryStep;
  index: number;
  attempts: DisplayAttempt[];
  onContinue?: (dispatchId: number) => void;
  continuingDispatchId?: number | null;
  onPause?: (dispatchId: number) => void;
  pausingDispatchId?: number | null;
}) {
  const isPausing = attempts.some((attempt) => attempt.status === 'PAUSING');
  const isActive = step.status === 'active';
  const isPaused = step.status === 'paused';
  const isDone = step.status === 'done' || step.status === 'reused' || step.status === 'skipped'
    || step.planStatus === 'REUSED' || step.planStatus === 'SKIPPED';
  const isFailed = step.status === 'failed';
  const executionLabel = step.planStatus === 'SKIPPED' ? '不执行' : isDone ? '已完成' : isPausing ? '暂停中' : isActive ? '执行中' : isPaused ? '已暂停' : isFailed ? '失败' : '待执行';
  const planLabel = step.planStatus === 'RUN'
    ? '本轮重跑'
    : step.planStatus === 'REUSED'
      ? `复用${step.sourceAttempt ? `第 ${step.sourceAttempt} 次` : '上一轮'}结果`
      : step.planStatus === 'SKIPPED'
        ? '本轮跳过'
        : null;
  const primaryActionAttempt = attempts.find((attempt) =>
    (attempt.canContinue && onContinue) || (attempt.canPause && onPause));
  const hasAttemptDetails = attempts.length > 0 || Boolean(step.executorName) || Boolean(step.subSteps?.length);
  const detailsDefaultActiveKey = isActive || isPaused || isFailed ? ['records'] : [];
  const attemptRecords = attempts.length > 0 ? attempts : [];

  return (
    <div
      data-testid={`delivery-step-${step.stepId}`}
      style={{
        padding: '6px 8px',
        borderRadius: 6,
        border: isPausing || isPaused ? '1px solid #ffe58f' : isActive ? '1px solid #ff6a00' : isFailed ? '1px solid #ffccc7' : '1px solid #f0f0f0',
        backgroundColor: isPausing || isPaused ? '#fffbe6' : isActive ? '#fff7f0' : isFailed ? '#fff1f0' : '#fff',
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
        <StepIcon step={step} index={index} pausing={isPausing} />
        <Text
          style={{
            flex: 1,
            fontSize: 12,
            color: isDone ? '#8c8c8c' : isPausing || isPaused ? '#ad6800' : isActive ? '#ff6a00' : isFailed ? '#cf1322' : '#8c8c8c',
            fontWeight: isActive || isPausing || isPaused || isFailed ? 500 : 400,
          }}
        >
          {step.name}
        </Text>
        {step.error && (
          <Tooltip title={step.error}>
            <Tag color="error" style={{ marginInlineEnd: 0, maxWidth: 140, overflow: 'hidden', textOverflow: 'ellipsis' }}>
              failed. {step.error}
            </Tag>
          </Tooltip>
        )}
        <Text
          type="secondary"
          style={{ fontSize: 11, color: isPausing || isPaused ? '#ad6800' : isActive ? '#ff6a00' : isFailed ? '#cf1322' : undefined }}
        >
          {planLabel ? `${planLabel} · ${executionLabel}` : step.status === 'reused' ? '复用上一轮' : step.status === 'skipped' ? '本轮跳过' : step.status === 'stale' ? '旧结果失效' : executionLabel}
          {step.durationMs != null && `  ${formatDuration(step.durationMs)}`}
        </Text>
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
            defaultActiveKey={detailsDefaultActiveKey}
            items={[
              {
                key: 'records',
                label: <Text type="secondary" style={{ fontSize: 12 }}>执行记录</Text>,
                children: (
                  <Space direction="vertical" size={4} style={{ width: '100%' }}>
                    {step.executorName && (
                      <Text type="secondary" style={{ fontSize: 11, display: 'block' }}>
                        执行者: {step.executorName}
                      </Text>
                    )}
                    {attemptRecords.map((attempt) => (
                      <div key={String(attempt.dispatchId)} style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                        <Text type="secondary" style={{ fontSize: 11, flex: 1 }}>
                          第{attempt.displayIndex + 1}次: {attempt.executorName ?? '—'} · {attempt.status ?? 'UNKNOWN'}
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
                    ))}
                    {step.subSteps && step.subSteps.length > 0 && (
                      <Space direction="vertical" size={2} style={{ width: '100%' }}>
                        {step.subSteps.map((sub, i) => (
                          <div key={i} style={{ display: 'flex', alignItems: 'center' }}>
                            <SubStepDot status={sub.status} />
                            <Text style={{ fontSize: 12, color: sub.status === 'pending' ? '#bfbfbf' : '#595959' }}>
                              {sub.name}
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
  steps: DeliveryStep[];
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

function WorkflowPlanSummary({ plan }: { plan: WorkflowPlan }) {
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
      title="本轮执行计划"
      data-testid="workflow-plan-card"
      styles={{ body: { padding: '8px 10px' } }}
    >
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8, marginBottom: 7 }}>
        <Tag color="blue" style={{ marginInlineEnd: 0 }}>第 {plan.revision} 轮</Tag>
      </div>
      <Space size={[6, 6]} wrap>
        {reused.length > 0 && <Tag color="green">复用 · {reused.join('、')}</Tag>}
        {run.length > 0 && <Tag color="volcano">重跑 · {run.join('、')}</Tag>}
        <Tag>跳过 · {skipped.length > 0 ? skipped.join('、') : '无'}</Tag>
      </Space>
      {plan.reason && (
        <Text type="secondary" style={{ display: 'block', marginTop: 7, fontSize: 11 }}>
          原因：{plan.reason}
        </Text>
      )}
      {plan.agentName && (
        <Text type="secondary" style={{ display: 'block', marginTop: 3, fontSize: 11 }}>
          执行者：{plan.agentName}
        </Text>
      )}
    </Card>
  );
}

function statusLabel(status: AgentDeliveryProgress['status']) {
  if (status === 'active') return { text: '执行中', color: 'processing' };
  if (status === 'paused') return { text: '已暂停', color: 'warning' };
  if (status === 'finished') return { text: '已完成', color: 'success' };
  if (status === 'failed') return { text: '失败', color: 'error' };
  return { text: '未执行', color: 'default' };
}

function artifactDispatchIds(agent: AgentDeliveryProgress): Set<number> {
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

function artifactsForAgent(agent: AgentDeliveryProgress, artifacts: Artifact[]): Artifact[] {
  const dispatchIds = artifactDispatchIds(agent);
  return artifacts.filter((artifact) => artifact.dispatchId != null && dispatchIds.has(Number(artifact.dispatchId)));
}

function agentPanelKey(agent: AgentDeliveryProgress, index: number): string {
  return String(agent.agentId ?? index);
}

function defaultAgentActiveKeys(agents: AgentDeliveryProgress[]): string[] {
  const runningKeys = agents
    .map((agent, index) => ({ agent, index }))
    .filter(({ agent }) => agent.status === 'active' || agent.status === 'paused' || agent.status === 'failed')
    .map(({ agent, index }) => agentPanelKey(agent, index));

  if (runningKeys.length > 0) {
    return runningKeys;
  }

  return agents
    .map((agent, index) => ({ agent, index }))
    .filter(({ agent }) => agent.status === 'finished')
    .map(({ agent, index }) => agentPanelKey(agent, index));
}

function clampZoom(value: number): number {
  return Math.min(2.4, Math.max(0.45, Number(value.toFixed(2))));
}

function markerDefs() {
  return <defs>
    <marker id="execution-graph-arrow-pass" markerWidth="9" markerHeight="9" refX="8" refY="4.5" orient="auto"><path d="M0,0 L9,4.5 L0,9 Z" fill="#1677ff" /></marker>
    <marker id="execution-graph-arrow-reject" markerWidth="9" markerHeight="9" refX="8" refY="4.5" orient="auto"><path d="M0,0 L9,4.5 L0,9 Z" fill="#ff4d4f" /></marker>
    <marker id="execution-graph-arrow-pause" markerWidth="9" markerHeight="9" refX="8" refY="4.5" orient="auto"><path d="M0,0 L9,4.5 L0,9 Z" fill="#d48806" /></marker>
  </defs>;
}

function authoritativeNodeTone(status?: string | null) {
  const value = status?.toUpperCase();
  if (value === 'RUNNING' || value === 'ACKED' || value === 'DISPATCHED') return { border: '#ff6a00', background: '#fff7f0' };
  if (value === 'FAILED' || value === 'TIMEOUT' || value === 'CANCELED') return { border: '#ff4d4f', background: '#fff1f0' };
  if (value?.includes('PAUS')) return { border: '#d48806', background: '#fffbe6' };
  if (value === 'SUCCEEDED' || value === 'HUMAN') return { border: '#52c41a', background: '#f6ffed' };
  return { border: '#d9d9d9', background: '#fafafa' };
}

function authoritativeEdgeTone(type: ProcessGraphEdge['type']) {
  if (type === 'COMMENT_REWORK') return { color: '#ff4d4f', background: '#fff1f0', border: '#ffccc7', markerId: 'execution-graph-arrow-reject' };
  if (type === 'CONTINUE') return { color: '#d48806', background: '#fffbe6', border: '#ffe58f', markerId: 'execution-graph-arrow-pause' };
  return { color: '#1677ff', background: '#f0f7ff', border: '#b7d4ff', markerId: 'execution-graph-arrow-pass' };
}

function AuthoritativeExecutionProcessGraph({ processGraph }: { processGraph?: ProcessGraph | null }) {
  const dragState = useRef<{ active: boolean; startX: number; startY: number; panX: number; panY: number }>({ active: false, startX: 0, startY: 0, panX: 0, panY: 0 });
  const [zoom, setZoom] = useState(1);
  const [pan, setPan] = useState({ x: 0, y: 0 });
  const [fullscreen, setFullscreen] = useState(false);
  const [selection, setSelection] = useState<GraphSelection | null>(null);
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
      title="执行过程 Graph"
      data-testid="execution-process-graph"
      extra={<Space size={4}>
        <Tooltip title="缩小"><Button size="small" aria-label="缩小" icon={<ZoomOutOutlined />} onClick={() => zoomBy(-0.12)} /></Tooltip>
        <Tooltip title="放大"><Button size="small" aria-label="放大" icon={<ZoomInOutlined />} onClick={() => zoomBy(0.12)} /></Tooltip>
        <Tooltip title="重置视图"><Button size="small" aria-label="重置视图" icon={<ReloadOutlined />} onClick={resetView} /></Tooltip>
        <Tooltip title={fullscreen ? '退出全屏' : '全屏查看'}>
          <Button size="small" aria-label={fullscreen ? '退出全屏' : '全屏查看'} icon={fullscreen ? <CompressOutlined /> : <FullscreenOutlined />} onClick={() => setFullscreen((value) => !value)} />
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
        style={{ height: fullscreen ? 'calc(100vh - 190px)' : 420, border: '1px solid #d6e4ff', borderRadius: 6, background: '#fff', overflow: 'hidden', cursor: 'grab' }}
      >
        <svg width="100%" height="100%" viewBox={`0 0 ${width} ${height}`} role="img" aria-label="执行过程流转图">
          {markerDefs()}
          <g transform={`translate(${pan.x} ${pan.y}) scale(${zoom})`}>
            {lanes.map((lane, index) => <line key={lane} x1={150 + index * laneSpacing} y1="24" x2={150 + index * laneSpacing} y2={height - 24} stroke="#f0f0f0" strokeWidth="2" strokeDasharray="5 5" />)}
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
              return <Tooltip key={`${edge.sourceKey}-${edge.targetKey}-${index}`} title={`${edge.label} · dispatch ${edge.sourceDispatchId ?? '-'} -> ${edge.targetDispatchId ?? '-'}`}>
                <g data-testid="execution-graph-edge" role="button" tabIndex={0} aria-label={`第 ${index + 1} 次 ${source.agentName} 到 ${target.agentName} ${edge.label}`} onClick={(event) => {
                  event.stopPropagation();
                  setSelection({ title: `第 ${index + 1} 次流转`, status: edge.label, duration: '权威血缘', detail: `${source.agentName} -> ${target.agentName} · dispatch ${edge.sourceDispatchId ?? '-'} -> ${edge.targetDispatchId ?? '-'}` });
                }} style={{ cursor: 'pointer' }}>
                  <path d={path} fill="none" stroke={tone.color} strokeWidth="2" markerEnd={`url(#${tone.markerId})`} vectorEffect="non-scaling-stroke" />
                  <rect x={labelX - 104} y={labelY - 12} width="208" height="24" rx="5" fill={tone.background} stroke={tone.border} />
                  <text x={labelX} y={labelY + 4} textAnchor="middle" style={{ fontSize: 11, fill: tone.color, fontWeight: 600 }}>{`${index + 1}. ${source.agentName} -> ${target.agentName} · ${edge.label}`}</text>
                </g>
              </Tooltip>;
            })}
            {processGraph.nodes.map((node) => {
              const position = positions.get(node.key)!;
              const tone = authoritativeNodeTone(node.status);
              return <Tooltip key={node.key} title={`${node.agentName} · ${node.stepName || '交接真人'} · ${node.status || '未知'}`}>
                <g data-testid="execution-graph-worker" data-status={node.status || ''} role="button" tabIndex={0} aria-label={`${node.agentName} ${node.status || '未知'}`} onClick={(event) => {
                  event.stopPropagation();
                  setSelection({ title: node.agentName, status: node.status || '未知', duration: formatDuration(node.durationMs) || '无', detail: `${node.stepName || '交接真人'}${node.dispatchId != null ? ` · dispatch ${node.dispatchId}` : ''}${node.error ? ` · ${node.error}` : ''}` });
                  if (node.dispatchId != null) setTraceNode(node);
                }} style={{ cursor: 'pointer' }}>
                  <rect x={position.x - 66} y={position.y - 31} width="132" height="62" rx="7" fill={tone.background} stroke={tone.border} strokeWidth="1.4" />
                  <text x={position.x} y={position.y - 9} textAnchor="middle" style={{ fontSize: 13, fontWeight: 600, fill: '#1f1f1f' }}>{node.agentName}</text>
                  <text x={position.x} y={position.y + 9} textAnchor="middle" style={{ fontSize: 11, fill: '#595959' }}>{node.stepName || '交接真人'}</text>
                  <text x={position.x} y={position.y + 24} textAnchor="middle" style={{ fontSize: 10, fill: '#8c8c8c' }}>{node.dispatchId != null ? `#${node.dispatchId}` : ''} {formatDuration(node.durationMs)}</text>
                </g>
              </Tooltip>;
            })}
          </g>
        </svg>
      </div>
      <div data-testid="execution-graph-detail-panel" style={{ marginTop: 8, minHeight: 58, padding: '7px 9px', border: '1px solid #f0f0f0', borderRadius: 6, background: '#fafafa' }}>
        {selection ? <Space direction="vertical" size={1}><Text strong style={{ fontSize: 12 }}>{selection.title}</Text><Text type="secondary" style={{ fontSize: 12 }}>状态：{selection.status} · 耗时：{selection.duration}</Text><Text type="secondary" style={{ fontSize: 12 }}>{selection.detail}</Text></Space> : <Text type="secondary" style={{ fontSize: 12 }}>点击节点或箭头查看权威流转详情</Text>}
      </div>
      <RuntimeTraceDrawer node={traceNode} processGraph={processGraph} onClose={() => setTraceNode(null)} />
    </Card>
  );

  return fullscreen ? <div data-testid="execution-graph-fullscreen" style={{ position: 'fixed', inset: 0, zIndex: 1000, padding: 16, background: '#fff' }}>{graph}</div> : graph;
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

function ArtifactGroup({
  artifacts,
  loading,
  onPreview,
}: {
  artifacts: Artifact[];
  loading?: boolean;
  onPreview: (artifact: Artifact) => void;
}) {
  if (loading) {
    return <Spin size="small" />;
  }
  if (artifacts.length === 0) {
    return <Text type="secondary" style={{ fontSize: 12 }}>无产物</Text>;
  }
  return (
    <Space direction="vertical" size={4} style={{ width: '100%' }}>
      {artifacts.map((artifact) => (
        <Tooltip
          key={String(artifact.id)}
          title={`${artifact.type}${artifact.size != null ? ` · ${artifact.size} bytes` : ''}`}
        >
          <div style={{ display: 'flex', alignItems: 'center', gap: 4, width: '100%' }}>
            <FileTextOutlined style={{ color: '#8c8c8c', flexShrink: 0 }} />
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
              {artifact.name}
            </Text>
            <Button
              type="text"
              size="small"
              aria-label={`预览产物 ${artifact.name}`}
              icon={<EyeOutlined />}
              onClick={() => onPreview(artifact)}
              style={{ flexShrink: 0 }}
            />
            <Button
              type="text"
              size="small"
              aria-label={`下载产物 ${artifact.name}`}
              icon={<DownloadOutlined />}
              onClick={() => void downloadArtifact(artifact)}
              style={{ flexShrink: 0 }}
            />
          </div>
        </Tooltip>
      ))}
    </Space>
  );
}

function AgentPanel({
  agent,
  index,
  artifacts,
  artifactsLoading,
  onContinue,
  continuingDispatchId,
  onPause,
  pausingDispatchId,
  onArtifactPreview,
}: {
  agent: AgentDeliveryProgress;
  index: number;
  artifacts: Artifact[];
  artifactsLoading?: boolean;
  onContinue?: (dispatchId: number) => void;
  continuingDispatchId?: number | null;
  onPause?: (dispatchId: number) => void;
  pausingDispatchId?: number | null;
  onArtifactPreview: (artifact: Artifact) => void;
}) {
  const pausing = agent.steps.some(stepIsPausing);
  const label = pausing ? { text: '暂停中', color: 'warning' } : statusLabel(agent.status);
  const agentArtifacts = artifactsForAgent(agent, artifacts);
  const active = agent.status === 'active' && !pausing;
  const paused = agent.status === 'paused' || pausing;
  const failed = agent.status === 'failed';

  return {
    key: agentPanelKey(agent, index),
    label: (
      <div style={{ display: 'flex', alignItems: 'center', gap: 6, width: '100%', minWidth: 0 }}>
        <Tooltip title={agent.agentName}>
          <Text
            strong
            data-testid="agent-progress-name"
            style={{
              flex: 1,
              minWidth: 0,
              color: active ? '#ff6a00' : paused ? '#ad6800' : failed ? '#cf1322' : undefined,
              display: 'block',
              whiteSpace: 'nowrap',
              overflow: 'hidden',
              textOverflow: 'ellipsis',
            }}
          >
            {agent.agentName}
          </Text>
        </Tooltip>
        <Tag color={label.color} style={{ marginInlineEnd: 0, flexShrink: 0 }}>{label.text}</Tag>
        <Text type="secondary" style={{ fontSize: 12, flexShrink: 0 }}>
          {agentArtifacts.length > 0 ? `${agentArtifacts.length} artifacts` : '无产物'}
        </Text>
      </div>
    ),
    style: {
      border: active ? '1px solid #ff6a00' : paused ? '1px solid #ffe58f' : failed ? '1px solid #ffccc7' : '1px solid #f0f0f0',
      borderRadius: 6,
      marginBottom: 6,
      overflow: 'hidden',
      background: active ? '#fff7f0' : paused ? '#fffbe6' : '#fff',
    },
    children: (
      <Space direction="vertical" style={{ width: '100%' }} size={6}>
        {agent.currentActivity && (
          <Text type="secondary" data-testid="agent-current-activity" style={{ fontSize: 12 }}>
            {agent.currentActivity}
          </Text>
        )}
        <StepList steps={agent.steps} onContinue={onContinue} continuingDispatchId={continuingDispatchId} onPause={onPause} pausingDispatchId={pausingDispatchId} />
        <Collapse
          ghost
          size="small"
          items={[
            {
              key: 'artifacts',
              label: (
                <Text strong style={{ fontSize: 12 }}>
                  产物（{agentArtifacts.length > 0 ? `${agentArtifacts.length} artifacts` : '无产物'}）
                </Text>
              ),
              children: <ArtifactGroup artifacts={agentArtifacts} loading={artifactsLoading} onPreview={onArtifactPreview} />,
            },
          ]}
          style={{ borderRadius: 6, background: '#fafafa' }}
        />
      </Space>
    ),
  };
}

export function DeliveryProgress({ steps = [], progress, artifacts = [], artifactsLoading, loading, onContinue, continuingDispatchId, onPause, pausingDispatchId }: DeliveryProgressProps) {
  const [previewArtifact, setPreviewArtifact] = useState<Artifact | null>(null);

  if (loading) {
    return (
      <Card size="small" title="交付进度跟踪">
        <div style={{ textAlign: 'center', padding: 16 }}>
          <Spin size="small" />
        </div>
      </Card>
    );
  }

  const agents = progress?.agents ?? [];
  const displaySteps = agents.length > 0 ? [] : (progress?.steps ?? steps);
  const activeKeys = defaultAgentActiveKeys(agents);

  if (agents.length > 0) {
    return (
      <Space direction="vertical" style={{ width: '100%' }} size={8}>
        {progress?.workflowPlan && <WorkflowPlanSummary plan={progress.workflowPlan} />}
        <Card size="small" title="交付进度跟踪" data-testid="delivery-progress-card" styles={{ body: { padding: '6px 8px' } }}>
          <Collapse
            bordered={false}
            defaultActiveKey={activeKeys}
            items={agents.map((agent, i) => AgentPanel({ agent, index: i, artifacts, artifactsLoading, onContinue, continuingDispatchId, onPause, pausingDispatchId, onArtifactPreview: setPreviewArtifact }))}
            style={{ background: 'transparent' }}
          />
        </Card>
        <AuthoritativeExecutionProcessGraph processGraph={progress?.processGraph} />
        <ArtifactPreviewModal
          open={previewArtifact != null}
          artifact={previewArtifact}
          onClose={() => setPreviewArtifact(null)}
        />
      </Space>
    );
  }

  return (
    <Space direction="vertical" style={{ width: '100%' }} size={8}>
      {progress?.workflowPlan && <WorkflowPlanSummary plan={progress.workflowPlan} />}
      <Card size="small" title="交付进度跟踪" data-testid="delivery-progress-card" styles={{ body: { padding: '6px 8px' } }}>
        <StepList steps={displaySteps} onContinue={onContinue} continuingDispatchId={continuingDispatchId} onPause={onPause} pausingDispatchId={pausingDispatchId} />
      </Card>
    </Space>
  );
}
