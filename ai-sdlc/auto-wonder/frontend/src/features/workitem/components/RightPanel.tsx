import { useTranslation } from 'react-i18next';
import { useCallback, useEffect, useRef, useState } from 'react';
import { Alert, Button, Collapse, Space, Tooltip } from 'antd';
import { RightOutlined, MessageOutlined, ArrowLeftOutlined, FullscreenOutlined, FullscreenExitOutlined } from '@ant-design/icons';
import { WorkitemClarificationPanel } from '../clarification/WorkitemClarificationPanel';
import { CLARIFICATION_THEME } from '../clarification/theme';
import { SquadMembers } from './SquadMembers';
import { WatcherList } from './WatcherList';
import { DeliveryProgress, WorkflowPlanSummary } from './DeliveryProgress';
import { ExecutionControlTimeline } from './ExecutionControlTimeline';
import { RecoveryControls } from './RecoveryControls';
import { DebugLogList } from './DebugLogList';
import { ExternalCollaborationCard } from './ExternalCollaborationCard';
import { ResizeHandle } from '@/shared/ui/ResizeHandle';
import type {
  Participant,
  TimelineItem,
  DeliveryProgress as DeliveryProgressModel,
  DeliveryStep,
  Artifact,
  ExternalCollaboration,
} from '@/shared/types/workitem';
import { useExecutionControl } from '../hooks';
import { AI_CLARIFICATION_ENABLED } from '../featureFlags';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';
import type { ClarifyContext, ClarifyPanelMode } from '../clarifyView';

const CLARIFY_MIN_HEIGHT = 280;

interface RightPanelProps {
  workitemId: string;
  timeline?: TimelineItem[];
  participants: Participant[];
  participantsLoading?: boolean;
  externalCollaboration?: ExternalCollaboration | null;
  steps: DeliveryStep[];
  progress?: DeliveryProgressModel | null;
  stepsLoading?: boolean;
  progressError?: boolean;
  onRetryProgress?: () => void;
  terminalStatus?: string | null;
  artifacts: Artifact[];
  clarificationArtifacts?: Artifact[];
  artifactsLoading?: boolean;
  onClarifyConfirm?: (result: string) => void;
  onModeChange?: (mode: ClarifyPanelMode) => void;
  /** 刷新恢复：URL 里记着的初始面板模式（工单 53035）。 */
  initialMode?: ClarifyPanelMode;
  /** 刷新恢复：URL 里记着的初始全屏态，仅澄清模式下有意义。 */
  initialFullscreen?: boolean;
  /** 刷新恢复：URL 里记着的数字人与会话，原样透传给澄清面板。 */
  clarifyContext?: ClarifyContext;
  /** 全屏态变化时上报，供页面写回 URL。 */
  onFullscreenChange?: (fullscreen: boolean) => void;
  /** 澄清面板的数字人/会话变化时上报，供页面写回 URL。
   *  入参只保证「已经落定」的字段：恢复还没落定的字段不会带上，
   *  否则页面会把 URL 里的恢复源自己抹掉（CR53035-001）。 */
  onClarifyContextChange?: (context: Partial<ClarifyContext>) => void;
}

export function RightPanel({
  workitemId,
  timeline = [],
  participants,
  participantsLoading,
  externalCollaboration,
  steps,
  progress,
  stepsLoading,
  progressError,
  onRetryProgress,
  terminalStatus,
  artifacts,
  clarificationArtifacts,
  artifactsLoading,
  onModeChange,
  initialMode,
  initialFullscreen,
  clarifyContext,
  onFullscreenChange,
  onClarifyContextChange,
}: RightPanelProps) {
  const { t } = useTranslation('workitem');
  const [mode, setMode] = useState<ClarifyPanelMode>(initialMode ?? 'progress');
  const [clarifyHeight, setClarifyHeight] = useState<number | null>(null);
  const [clarifyFullscreen, setClarifyFullscreen] = useState(initialFullscreen ?? false);
  const clarifyBoxRef = useRef<HTMLDivElement>(null);
  /** 记录“用户主动退出过全屏”。不要以为它和面板内部的上报守卫重复：
   *  onAgentConfirmed 在同一次挂载内可以多次触发（URL 恢复落定、重选数字人都会让
   *  面板的派生身份经过一次 null 后重新确定，内部守卫随之复位），
   *  没有这个意图记录，用户按 Esc 退出后会被下一次回调硬拽回全屏（违反需求 D4）。
   *  仅在本次澄清会话内有效：返回进度再次进入澄清时重置，重新享受自动全屏。 */
  const userExitedFullscreenRef = useRef(false);
  const executionControl = useExecutionControl(workitemId);
  const accessCommand = useAccessCommand();

  const applyMode = useCallback((next: ClarifyPanelMode) => {
    setMode(next);
    if (next === 'progress') {
      setClarifyHeight(null);
      setClarifyFullscreen(false);
      userExitedFullscreenRef.current = false;
    }
  }, []);

  const switchMode = (next: 'progress' | 'clarify') => {
    applyMode(next);
    onModeChange?.(next);
  };

  /** URL 是澄清视图的唯一真源，页面级入口（「启动交付」前的澄清引导，工单 53315）改的也是 URL。
   *  initialMode 原本只在挂载时读一次，那种入口就切不动面板，所以这里跟着 prop 变化同步内部态。
   *  ref 守卫是必须的：只在 prop 真变化时同步。否则内部 switchMode 已经把 mode 改成 clarify、
   *  而调用方没有接 onModeChange（URL 不动）时，拿 mode 去比对会立刻把用户拽回进度态。 */
  const lastInitialModeRef = useRef<ClarifyPanelMode | undefined>(initialMode);
  useEffect(() => {
    if (lastInitialModeRef.current === initialMode) return;
    lastInitialModeRef.current = initialMode;
    applyMode(initialMode ?? 'progress');
  }, [initialMode, applyMode]);

  // 主动退出全屏的唯一入口，保证 Esc 与按钮两条路径不会走偏
  const exitFullscreen = useCallback(() => {
    userExitedFullscreenRef.current = true;
    setClarifyFullscreen(false);
    onFullscreenChange?.(false);
  }, [onFullscreenChange]);

  const enterFullscreen = () => {
    userExitedFullscreenRef.current = false;
    setClarifyFullscreen(true);
    onFullscreenChange?.(true);
  };

  useEffect(() => {
    if (!clarifyFullscreen) return;
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') exitFullscreen();
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [clarifyFullscreen, exitFullscreen]);

  const clarifyMaxHeight =
    clarifyBoxRef.current?.parentElement?.getBoundingClientRect().height || window.innerHeight;
  const currentHeight = clarifyHeight ?? clarifyBoxRef.current?.getBoundingClientRect().height ?? 0;

  if (AI_CLARIFICATION_ENABLED && mode === 'clarify') {
    return (
      <div
        ref={clarifyBoxRef}
        data-testid="clarify-resize-box"
        style={clarifyFullscreen ? {
          position: 'fixed',
          inset: 0,
          zIndex: 1000,
          display: 'flex',
          flexDirection: 'column',
          overflow: 'hidden',
          backgroundColor: CLARIFICATION_THEME.surface,
        } : {
          position: 'relative',
          width: '100%',
          height: clarifyHeight ?? '100%',
          maxHeight: '100%',
          marginTop: 'auto',
          display: 'flex',
          flexDirection: 'column',
          overflow: 'hidden',
          backgroundColor: CLARIFICATION_THEME.surface,
        }}
      >
        {!clarifyFullscreen && (
          <ResizeHandle
            direction="vertical"
            value={currentHeight}
            measureValue={() => clarifyBoxRef.current?.getBoundingClientRect().height ?? 0}
            min={CLARIFY_MIN_HEIGHT}
            max={clarifyMaxHeight}
            onChange={(height) => setClarifyHeight(height)}
          />
        )}
        {/* 头部融合：全屏与停靠都不再渲染外层独立头行。「返回」与
            「全屏/退出全屏」作为插槽并入澄清面板自己的头部——
            全屏融成一行；停靠排成「导航/身份 + 会话操作」两行。 */}
        <div style={{ flex: 1, minHeight: 0, overflow: 'hidden' }}>
          <WorkitemClarificationPanel
            workitemId={workitemId}
            artifacts={clarificationArtifacts ?? artifacts}
            fullscreen={clarifyFullscreen}
            headerPrefix={(
              <Button
                size={clarifyFullscreen ? 'middle' : 'small'}
                icon={<ArrowLeftOutlined />}
                aria-label={t('detail.back')}
                style={{ flexShrink: 0 }}
                onClick={() => switchMode('progress')}
              >
                {t('detail.back')}
              </Button>
            )}
            headerSuffix={clarifyFullscreen ? (
              <Tooltip title={t('detail.exitFullscreenHint')}>
                <Button
                  size="middle"
                  icon={<FullscreenExitOutlined />}
                  aria-label={t('detail.exitFullscreen')}
                  style={{ flexShrink: 0 }}
                  onClick={exitFullscreen}
                />
              </Tooltip>
            ) : (
              <Tooltip title={t('detail.fullscreen')}>
                <Button
                  size="small"
                  icon={<FullscreenOutlined />}
                  aria-label={t('detail.fullscreen')}
                  style={{ flexShrink: 0 }}
                  onClick={enterFullscreen}
                />
              </Tooltip>
            )}
            initialAgentId={clarifyContext?.agentId ?? null}
            initialConversationId={clarifyContext?.conversationId ?? null}
            onContextChange={onClarifyContextChange}
            onAgentConfirmed={() => {
              // 用户已主动退出过全屏，本次会话内不再自动全屏
              if (userExitedFullscreenRef.current) return;
              setClarifyFullscreen(true);
              // 自动全屏也要落到 URL，否则刷新后这一态会丢（工单 53035）
              onFullscreenChange?.(true);
            }}
          />
        </div>
      </div>
    );
  }

  return (
    <div style={{ padding: 0, overflow: 'auto', height: '100%' }}>
      <Space direction="vertical" style={{ width: '100%' }} size={8}>
        {AI_CLARIFICATION_ENABLED ? (
          <Button
            type="primary"
            icon={<MessageOutlined />}
            block
            onClick={() => accessCommand('READ_WRITE', t('detail.clarifyCommand'), () => switchMode('clarify'))}
          >
            {t('detail.aiClarification')}
          </Button>
        ) : null}

        <Collapse
          size="small"
          defaultActiveKey={[]}
          expandIcon={({ isActive }) => <RightOutlined aria-hidden="true" rotate={isActive ? 90 : 0} />}
          items={[{
            key: 'task-info',
            label: t('detail.taskInfo'),
            children: (
              <Space direction="vertical" style={{ width: '100%' }} size={8}>
                <ExternalCollaborationCard collaboration={externalCollaboration} />
                <SquadMembers participants={participants} loading={participantsLoading} />
                <WatcherList workitemId={workitemId} />
                {progress?.workflowPlan && <WorkflowPlanSummary plan={progress.workflowPlan} />}
              </Space>
            ),
          }]}
        />
        {progressError && <Alert type="warning" showIcon
          message={t(progress ? 'delivery.refreshFailed' : 'delivery.loadFailed')}
          action={<Button size="small" onClick={onRetryProgress}>{t('delivery.retryLoad')}</Button>} />}
        {executionControl.isError && <Alert type="error" showIcon closable
          message={executionControl.error?.message || t('messages.executionFailed')}
          onClose={() => executionControl.reset()} />}
        <DeliveryProgress
          renderControlTimeline={(dispatchId, compact) => <ExecutionControlTimeline key={`controls-${dispatchId}`} dispatchId={dispatchId} items={timeline} compact={compact} />}
          showWorkflowPlan={false}
          renderRecovery={(dispatchId) => <RecoveryControls key={`recovery-${dispatchId}`} workitemId={workitemId} variant="execution" dispatchId={dispatchId} />}
          restartHistory={<RecoveryControls workitemId={workitemId} variant="history" />}
          steps={steps}
          progress={progress}
          terminalStatus={terminalStatus}
          artifacts={artifacts}
          artifactsLoading={artifactsLoading}
          loading={stepsLoading}
          onExecutionAction={(attempt, action) => accessCommand(
            'READ_WRITE',
            action === 'pause' ? t('detail.pauseCommand') : action === 'resume' ? t('detail.resumeCommand') : t('detail.endExecutionCommand'),
            () => executionControl.mutate({ dispatchId: attempt.dispatchId, action }),
          )}
          pendingExecutionAction={executionControl.isPending ? executionControl.variables ?? null : null}
        />
        <DebugLogList workitemId={workitemId} />
      </Space>
    </div>
  );
}
