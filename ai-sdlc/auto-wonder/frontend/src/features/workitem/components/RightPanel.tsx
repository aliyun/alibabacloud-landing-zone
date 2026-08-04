import { useState } from 'react';
import { Button, Space } from 'antd';
import { MessageOutlined, ArrowLeftOutlined } from '@ant-design/icons';
import { WorkitemClarificationPanel } from '../clarification/WorkitemClarificationPanel';
import { SquadMembers } from './SquadMembers';
import { DeliveryProgress } from './DeliveryProgress';
import type { Participant, DeliveryProgress as DeliveryProgressModel, DeliveryStep, Artifact } from '@/shared/types/workitem';
import { useContinueDispatch, usePauseDispatch } from '../hooks';
import { AI_CLARIFICATION_ENABLED } from '../featureFlags';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';

interface RightPanelProps {
  workitemId: string;
  participants: Participant[];
  participantsLoading?: boolean;
  steps: DeliveryStep[];
  progress?: DeliveryProgressModel | null;
  stepsLoading?: boolean;
  artifacts: Artifact[];
  artifactsLoading?: boolean;
  onClarifyConfirm?: (result: string) => void;
}

export function RightPanel({
  workitemId,
  participants,
  participantsLoading,
  steps,
  progress,
  stepsLoading,
  artifacts,
  artifactsLoading,
}: RightPanelProps) {
  const [mode, setMode] = useState<'progress' | 'clarify'>('progress');
  const continueMutation = useContinueDispatch(workitemId);
  const pauseMutation = usePauseDispatch(workitemId);
  const accessCommand = useAccessCommand();

  if (AI_CLARIFICATION_ENABLED && mode === 'clarify') {
    return (
      <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
        <div style={{ padding: '8px 12px', borderBottom: '1px solid #f0f0f0' }}>
          <Button
            type="text"
            size="small"
            icon={<ArrowLeftOutlined />}
            onClick={() => setMode('progress')}
          >
            返回进度
          </Button>
        </div>
        <div style={{ flex: 1, overflow: 'hidden' }}>
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
            onClick={() => accessCommand('READ_WRITE', '发起 AI 需求澄清', () => setMode('clarify'))}
          >
            AI 需求澄清
          </Button>
        ) : null}

        <SquadMembers participants={participants} loading={participantsLoading} />
        <DeliveryProgress
          steps={steps}
          progress={progress}
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
