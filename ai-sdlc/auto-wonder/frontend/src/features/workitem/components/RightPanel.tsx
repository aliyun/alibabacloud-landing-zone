import { useEffect, useRef, useState } from 'react';
import { Button, Space } from 'antd';
import { MessageOutlined, ArrowLeftOutlined, FullscreenOutlined, FullscreenExitOutlined } from '@ant-design/icons';
import { WorkitemClarificationPanel } from '../clarification/WorkitemClarificationPanel';
import { SquadMembers } from './SquadMembers';
import { DeliveryProgress } from './DeliveryProgress';
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
  onModeChange?: (mode: 'progress' | 'clarify') => void;
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
}: RightPanelProps) {
  const [mode, setMode] = useState<'progress' | 'clarify'>('progress');
  const [clarifyHeight, setClarifyHeight] = useState<number | null>(null);
  const [clarifyFullscreen, setClarifyFullscreen] = useState(false);
  const clarifyBoxRef = useRef<HTMLDivElement>(null);
  const continueMutation = useContinueDispatch(workitemId);
  const pauseMutation = usePauseDispatch(workitemId);
  const accessCommand = useAccessCommand();

  const switchMode = (next: 'progress' | 'clarify') => {
    setMode(next);
    if (next === 'progress') {
      setClarifyHeight(null);
      setClarifyFullscreen(false);
    }
    onModeChange?.(next);
  };

  useEffect(() => {
    if (!clarifyFullscreen) return;
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setClarifyFullscreen(false);
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [clarifyFullscreen]);

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
          backgroundColor: '#fff',
        } : {
          position: 'relative',
          width: '100%',
          height: clarifyHeight ?? '100%',
          maxHeight: '100%',
          marginTop: 'auto',
          display: 'flex',
          flexDirection: 'column',
          overflow: 'hidden',
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
        <div
          style={{
            padding: '8px 12px',
            borderBottom: '1px solid #f0f0f0',
            flexShrink: 0,
            display: 'flex',
            alignItems: 'center',
            gap: 8,
          }}
        >
          <Button
            type="text"
            size="small"
            icon={<ArrowLeftOutlined />}
            onClick={() => switchMode('progress')}
          >
            返回进度
          </Button>
          <Button
            type="primary"
            size="large"
            icon={clarifyFullscreen ? <FullscreenExitOutlined /> : <FullscreenOutlined />}
            aria-label={clarifyFullscreen ? '退出全屏' : '全屏'}
            title={clarifyFullscreen ? '退出全屏（Esc）' : '全屏'}
            style={{ marginLeft: 'auto' }}
            onClick={() => setClarifyFullscreen((v) => !v)}
          >
            {clarifyFullscreen ? '退出全屏' : '全屏'}
          </Button>
        </div>
        <div style={{ flex: 1, minHeight: 0, overflow: 'hidden' }}>
          <WorkitemClarificationPanel
            workitemId={workitemId}
            agents={progress?.agents ?? []}
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
            style={{ backgroundColor: '#ff6a00', borderColor: '#ff6a00' }}
            onClick={() => accessCommand('READ_WRITE', '发起 AI 需求澄清', () => switchMode('clarify'))}
          >
            AI 需求澄清
          </Button>
        ) : null}

        <ExternalCollaborationCard collaboration={externalCollaboration} />
        <SquadMembers participants={participants} loading={participantsLoading} />
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
      </Space>
    </div>
  );
}
