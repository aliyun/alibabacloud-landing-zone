import { Button, Dropdown, Modal, Space, Tooltip } from 'antd';
import { MoreOutlined, PauseCircleOutlined, PlayCircleOutlined, StopOutlined, WarningOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { DispatchAttempt } from '@/shared/types/workitem';
import type { ExecutionActionKind } from '../hooks';
import { executionActionLabel, resolveExecutionActions } from '../executionControl';

interface ExecutionActionBarProps {
  attempt: DispatchAttempt;
  pending?: { dispatchId: number; action: ExecutionActionKind } | null;
  onAction: (action: ExecutionActionKind) => void;
}

function actionIcon(action: ExecutionActionKind) {
  if (action === 'pause') return <PauseCircleOutlined aria-hidden="true" />;
  if (action === 'resume') return <PlayCircleOutlined aria-hidden="true" />;
  if (action === 'forceEnd') return <WarningOutlined aria-hidden="true" />;
  return <StopOutlined aria-hidden="true" />;
}

export function ExecutionActionBar({ attempt, pending, onAction }: ExecutionActionBarProps) {
  const { t } = useTranslation('workitem');
  const resolved = resolveExecutionActions(attempt);
  if (!resolved.primary && resolved.overflow.length === 0 && resolved.disabled.length === 0) return null;

  const isPending = (action: ExecutionActionKind) =>
    pending?.dispatchId === attempt.dispatchId && pending.action === action;

  const request = (action: ExecutionActionKind) => {
    if (action === 'resume') {
      const resume = attempt.control?.resume;
      const target = resume?.targetDispatchId ?? attempt.dispatchId;
      if (resume?.targetStopPending) {
        const timeout = resume.targetStatus === 'TIMEOUT';
        Modal.confirm({
          title: t(timeout ? 'delivery.resumeTimeoutTitle' : 'delivery.resumeStopPendingTitle', { id: target }),
          content: t(timeout ? 'delivery.resumeTimeoutBody' : 'delivery.resumeStopPendingBody', { id: target }),
          okText: t('delivery.retry'),
          okButtonProps: { danger: true },
          cancelText: t('delivery.cancelAction'),
          onOk: () => onAction(action),
        });
        return;
      }
    }
    if (action === 'end') {
      Modal.confirm({
        title: t('delivery.endConfirmTitle', { id: attempt.dispatchId }),
        content: t('delivery.endConfirmBody', {
          id: attempt.dispatchId,
          agent: attempt.agentName || '—',
        }),
        okText: t('delivery.endExecution'),
        okButtonProps: { danger: true },
        cancelText: t('delivery.cancelAction'),
        onOk: () => onAction(action),
      });
      return;
    }
    if (action === 'forceEnd') {
      Modal.confirm({
        title: t('delivery.forceConfirmTitle', { id: attempt.dispatchId }),
        content: t('delivery.forceConfirmBody'),
        okText: t('delivery.forceEnd'),
        okButtonProps: { danger: true },
        cancelText: t('delivery.cancelAction'),
        onOk: () => onAction(action),
      });
      return;
    }
    onAction(action);
  };

  const reasonText = (reasonKey: string, reasonArgs?: string[]) => {
    const values = Object.fromEntries((reasonArgs ?? []).map((value, index) => [String(index), value]));
    return String(t(reasonKey as never, values as never));
  };

  const renderDisabled = (action: ExecutionActionKind, reasonKey: string, reasonArgs?: string[]) => {
    const label = executionActionLabel(attempt, action, t);
    return (
      <Tooltip key={`disabled-${action}`} title={reasonText(reasonKey, reasonArgs)}>
        <Button size="small" disabled aria-label={label} icon={actionIcon(action)}>{label}</Button>
      </Tooltip>
    );
  };

  const renderButton = (action: ExecutionActionKind) => {
    const label = executionActionLabel(attempt, action, t);
    const danger = action === 'end' || action === 'forceEnd';
    return (
      <Tooltip key={action} title={danger ? label : undefined}>
        <Button
          size="small"
          type={danger && resolved.primary === action ? 'primary' : 'default'}
          danger={danger}
          aria-label={label}
          icon={actionIcon(action)}
          loading={isPending(action)}
          onClick={() => request(action)}
        >
          {label}
        </Button>
      </Tooltip>
    );
  };

  return (
    <Space size={4} wrap>
      {resolved.primary && renderButton(resolved.primary)}
      {resolved.disabled.map((item) => renderDisabled(item.action, item.reasonKey, item.reasonArgs))}
      {resolved.overflow.length > 0 && (
        <Dropdown
          trigger={['click']}
          menu={{
            items: resolved.overflow.map((action) => ({
              key: action,
              danger: action === 'end' || action === 'forceEnd',
              icon: actionIcon(action),
              label: executionActionLabel(attempt, action, t),
              disabled: isPending(action),
              onClick: () => request(action),
            })),
          }}
        >
          <Button
            size="small"
            aria-label={t('delivery.moreActions')}
            icon={<MoreOutlined aria-hidden="true" />}
            loading={resolved.overflow.some(isPending)}
          />
        </Dropdown>
      )}
    </Space>
  );
}
