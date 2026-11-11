import { Button, Modal, Tooltip } from 'antd';
import { StopOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { useState } from 'react';
import type { CommentInteraction } from '@/shared/types/workitem';

export type InteractionStopAction = 'end' | 'forceEnd';

interface InteractionStopControlProps {
  interaction: CommentInteraction;
  pending?: InteractionStopAction | null;
  onAction: (action: InteractionStopAction) => Promise<void>;
  onRefresh: () => void;
}

/** Icon-only end control for one comment interaction dispatch. */
export function InteractionStopControl({ interaction, pending, onAction, onRefresh }: InteractionStopControlProps) {
  const { t } = useTranslation('workitem');
  const [error, setError] = useState(false);
  const dispatchId = interaction.dispatchId;
  const endAllowed = interaction.control?.end?.allowed === true;
  const forceEndAllowed = interaction.control?.forceEnd?.allowed === true;
  const action: InteractionStopAction | null = endAllowed ? 'end' : forceEndAllowed ? 'forceEnd' : null;
  if (!dispatchId || !action) return null;

  const label = t(action === 'end' ? 'interaction.stopEnd' : 'interaction.stopForce');
  const request = (kind: InteractionStopAction) => Modal.confirm({
    title: t(kind === 'end' ? 'interaction.endConfirmTitle' : 'interaction.forceConfirmTitle', { id: dispatchId }),
    content: t(kind === 'end' ? 'interaction.endConfirmBody' : 'delivery.forceConfirmBody'),
    okText: t(kind === 'end' ? 'delivery.endExecution' : 'delivery.forceEnd'),
    okButtonProps: { danger: true },
    cancelText: t('delivery.cancelAction'),
    onOk: async () => {
      setError(false);
      try {
        await onAction(kind);
      } catch {
        setError(true);
      }
    },
  });

  return (
    <>
      <Tooltip title={label}>
        <Button
          className={`aw-comment-stop${action === 'forceEnd' ? ' aw-comment-stop--force' : ''}`}
          aria-label={label}
          icon={<StopOutlined aria-hidden="true" />}
          loading={pending === action}
          onClick={() => request(action)}
        />
      </Tooltip>
      {error && (
        <div className="aw-comment-stop-error" role="alert">
          <span>{t('interaction.stopFailed')}</span>
          <button type="button" onClick={() => { setError(false); onRefresh(); }}>{t('interaction.refresh')}</button>
        </div>
      )}
    </>
  );
}
