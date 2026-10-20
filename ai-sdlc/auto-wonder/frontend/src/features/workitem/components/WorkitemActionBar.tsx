import { useTranslation } from 'react-i18next';
import type { ReactNode } from 'react';
import { Button, Tooltip } from 'antd';
import { SwapOutlined, UserSwitchOutlined, CommentOutlined, RocketOutlined, SyncOutlined, DeleteOutlined, UserAddOutlined, StarOutlined, StarFilled } from '@ant-design/icons';

interface WorkitemActionBarProps {
  deliveryControl?: ReactNode;
  hasSdlc?: boolean;
  watched?: boolean;
  onStartDelivery?: () => void;
  onAssignHuman?: () => void;
  onTransition?: () => void;
  onAddComment?: () => void;
  onSyncExternal?: () => void;
  onDelete?: () => void;
  onToggleWatch?: () => void;
  watchLoading?: boolean;
  syncExternalLoading?: boolean;
  deleteLoading?: boolean;
  deleteDisabled?: boolean;
  deleteDisabledReason?: string | null;
}

export function WorkitemActionBar({
  deliveryControl,
  hasSdlc,
  watched,
  onStartDelivery,
  onAssignHuman,
  onTransition,
  onAddComment,
  onSyncExternal,
  onDelete,
  onToggleWatch,
  watchLoading,
  syncExternalLoading,
  deleteLoading,
  deleteDisabled,
  deleteDisabledReason,
}: WorkitemActionBarProps) {
  const { t } = useTranslation('workitem');
  return (
    <div data-testid="workitem-action-bar" className="aw-workitem-actions">
      <Button type="primary" icon={hasSdlc ? <UserSwitchOutlined aria-hidden="true" /> : <RocketOutlined aria-hidden="true" />} onClick={onStartDelivery}>
        {hasSdlc ? t('detail.reassign') : t('detail.startDelivery')}
      </Button>
      <Button icon={<UserAddOutlined aria-hidden="true" />} onClick={onAssignHuman}>{t('assignment.title')}</Button>
      <Button icon={<SwapOutlined aria-hidden="true" />} onClick={onTransition}>{t('detail.transitionTitle')}</Button>
      <Tooltip title={watched ? t('actions.unwatchAria') : t('actions.watchAria')}>
        <Button
          data-testid="workitem-watch-toggle"
          type={watched ? 'primary' : 'default'}
          ghost={watched}
          icon={watched ? <StarFilled aria-hidden="true" /> : <StarOutlined aria-hidden="true" />}
          aria-label={watched ? t('actions.unwatchAria') : t('actions.watchAria')}
          aria-pressed={!!watched}
          onClick={onToggleWatch}
          loading={watchLoading}
        />
      </Tooltip>
      <Tooltip title={t('detail.reconcile')}>
        <Button aria-label={t('detail.reconcile')} icon={<SyncOutlined aria-hidden="true" />} onClick={onSyncExternal} loading={syncExternalLoading} />
      </Tooltip>
      <Tooltip title={t('comment.add')}>
        <Button aria-label={t('comment.add')} icon={<CommentOutlined aria-hidden="true" />} onClick={onAddComment} />
      </Tooltip>
      <Tooltip title={deleteDisabled ? deleteDisabledReason : t('actions.delete')}>
        <span>
          <Button danger aria-label={t('actions.delete')} icon={<DeleteOutlined aria-hidden="true" />} onClick={onDelete}
            loading={deleteLoading} disabled={deleteDisabled} />
        </span>
      </Tooltip>
      {deliveryControl}
    </div>
  );
}
