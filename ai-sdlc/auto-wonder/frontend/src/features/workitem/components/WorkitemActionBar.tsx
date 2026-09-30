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
  return (
    <div data-testid="workitem-action-bar" className="aw-workitem-actions">
      <Button type="primary" icon={hasSdlc ? <UserSwitchOutlined /> : <RocketOutlined />} onClick={onStartDelivery}>
        {hasSdlc ? '重新指派' : '启动交付'}
      </Button>
      <Button icon={<UserAddOutlined />} onClick={onAssignHuman}>指派给真人</Button>
      <Button icon={<SwapOutlined />} onClick={onTransition}>流转状态</Button>
      <Tooltip title={watched ? '取消关注工单' : '关注工单'}>
        <Button
          data-testid="workitem-watch-toggle"
          type={watched ? 'primary' : 'default'}
          ghost={watched}
          icon={watched ? <StarFilled /> : <StarOutlined />}
          aria-label={watched ? '取消关注工单' : '关注工单'}
          aria-pressed={!!watched}
          onClick={onToggleWatch}
          loading={watchLoading}
        />
      </Tooltip>
      <Tooltip title="立即对账">
        <Button aria-label="立即对账" icon={<SyncOutlined />} onClick={onSyncExternal} loading={syncExternalLoading} />
      </Tooltip>
      <Tooltip title="添加评论">
        <Button aria-label="添加评论" icon={<CommentOutlined />} onClick={onAddComment} />
      </Tooltip>
      <Tooltip title={deleteDisabled ? deleteDisabledReason : '删除工单'}>
        <span>
          <Button danger aria-label="删除工单" icon={<DeleteOutlined />} onClick={onDelete}
            loading={deleteLoading} disabled={deleteDisabled} />
        </span>
      </Tooltip>
      {deliveryControl}
    </div>
  );
}
