import { useCallback, useEffect, useRef, useState } from 'react';
import { Button, Space, Tooltip } from 'antd';
import { MessageOutlined, ArrowLeftOutlined, FullscreenOutlined, FullscreenExitOutlined } from '@ant-design/icons';
import { WorkitemClarificationPanel } from '../clarification/WorkitemClarificationPanel';
import { CLARIFICATION_THEME } from '../clarification/theme';
import { SquadMembers } from './SquadMembers';
import { WatcherList } from './WatcherList';
import { DeliveryProgress } from './DeliveryProgress';
import { DebugLogList } from './DebugLogList';
import { ExternalCollaborationCard } from './ExternalCollaborationCard';
import { ResizeHandle } from '@/shared/ui/ResizeHandle';
import type {
  Participant,
  DeliveryProgress as DeliveryProgressModel,
  DeliveryStep,
  Artifact,
  ExternalCollaboration,
} from '@/shared/types/workitem';
import { useContinueDispatch, usePauseDispatch } from '../hooks';
import { AI_CLARIFICATION_ENABLED } from '../featureFlags';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';
import type { ClarifyContext, ClarifyPanelMode } from '../clarifyView';

const CLARIFY_MIN_HEIGHT = 280;

interface RightPanelProps {
  workitemId: string;
  participants: Participant[];
  participantsLoading?: boolean;
  externalCollaboration?: ExternalCollaboration | null;
  steps: DeliveryStep[];
  progress?: DeliveryProgressModel | null;
  stepsLoading?: boolean;
  terminalStatus?: string | null;
  artifacts: Artifact[];
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
  participants,
  participantsLoading,
  externalCollaboration,
  steps,
  progress,
  stepsLoading,
  terminalStatus,
  artifacts,
  artifactsLoading,
  onModeChange,
  initialMode,
  initialFullscreen,
  clarifyContext,
  onFullscreenChange,
  onClarifyContextChange,
}: RightPanelProps) {
  const [mode, setMode] = useState<ClarifyPanelMode>(initialMode ?? 'progress');
  const [clarifyHeight, setClarifyHeight] = useState<number | null>(null);
  const [clarifyFullscreen, setClarifyFullscreen] = useState(initialFullscreen ?? false);
  const clarifyBoxRef = useRef<HTMLDivElement>(null);
  /** 记录“用户主动退出过全屏”。不要以为它和面板内部的上报守卫重复：
   *  onAgentConfirmed 在同一次挂载内可以多次触发——交付进度是轮询的，
   *  某次响应缺少 agents 会让面板的 hasDeliveryAgents 翻转、内部守卫复位，
   *  下一次轮询恢复 agents 时回调会再次触发。没有这个意图记录，
   *  用户按 Esc 退出后会被下一次轮询硬拽回全屏（违反需求 D4）。
   *  仅在本次澄清会话内有效：返回进度再次进入澄清时重置，重新享受自动全屏。 */
  const userExitedFullscreenRef = useRef(false);
  const continueMutation = useContinueDispatch(workitemId);
  const pauseMutation = usePauseDispatch(workitemId);
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
            agents={progress?.agents ?? []}
            fullscreen={clarifyFullscreen}
            headerPrefix={(
              <Button
                size={clarifyFullscreen ? 'middle' : 'small'}
                icon={<ArrowLeftOutlined />}
                aria-label="返回"
                style={{ flexShrink: 0 }}
                onClick={() => switchMode('progress')}
              >
                返回
              </Button>
            )}
            headerSuffix={clarifyFullscreen ? (
              <Tooltip title="退出全屏（Esc）">
                <Button
                  size="middle"
                  icon={<FullscreenExitOutlined />}
                  aria-label="退出全屏"
                  style={{ flexShrink: 0 }}
                  onClick={exitFullscreen}
                />
              </Tooltip>
            ) : (
              <Tooltip title="全屏">
                <Button
                  size="small"
                  icon={<FullscreenOutlined />}
                  aria-label="全屏"
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
            onClick={() => accessCommand('READ_WRITE', '发起 AI 需求澄清', () => switchMode('clarify'))}
          >
            AI 需求澄清
          </Button>
        ) : null}

        <ExternalCollaborationCard collaboration={externalCollaboration} />
        <SquadMembers participants={participants} loading={participantsLoading} />
        <WatcherList workitemId={workitemId} />
        <DeliveryProgress
          steps={steps}
          progress={progress}
          terminalStatus={terminalStatus}
          artifacts={artifacts}
          artifactsLoading={artifactsLoading}
          loading={stepsLoading}
          onContinue={(dispatchId) => accessCommand(
            'READ_WRITE',
            '恢复交付任务',
            () => continueMutation.mutate({ dispatchId }),
          )}
          continuingDispatchId={continueMutation.isPending ? continueMutation.variables?.dispatchId : null}
          onPause={(dispatchId) => accessCommand(
            'READ_WRITE',
            '暂停交付任务',
            () => pauseMutation.mutate({ dispatchId }),
          )}
          pausingDispatchId={pauseMutation.isPending ? pauseMutation.variables?.dispatchId : null}
        />
        <DebugLogList workitemId={workitemId} />
      </Space>
    </div>
  );
}
