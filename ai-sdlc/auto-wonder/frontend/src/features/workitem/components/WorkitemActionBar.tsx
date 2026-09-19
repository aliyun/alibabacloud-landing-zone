import { Button, Space, Tooltip } from 'antd';
import { SwapOutlined, UserSwitchOutlined, CommentOutlined, RocketOutlined, SyncOutlined, DeleteOutlined, UserAddOutlined, StarOutlined, StarFilled } from '@ant-design/icons';

interface WorkitemActionBarProps {
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
    <div data-testid="workitem-action-bar" style={{ background: '#f5f5f5', borderRadius: 8, padding: '12px 0' }}>
      <Space wrap>
        <Button
          data-testid="workitem-watch-toggle"
          type={watched ? 'primary' : 'default'}
          ghost={watched}
          icon={watched ? <StarFilled /> : <StarOutlined />}
          aria-label={watched ? '取消关注工单' : '关注工单'}
          style={watched ? { background: '#ff6a00', borderColor: '#ff6a00' } : undefined}
          onClick={onToggleWatch}
          loading={watchLoading}
        >
          {watched ? '已关注' : '关注'}
        </Button>
        <Button
          type="primary"
          icon={hasSdlc ? <UserSwitchOutlined /> : <RocketOutlined />}
          style={{ background: '#ff6a00', borderColor: '#ff6a00' }}
          onClick={onStartDelivery}
        >
          {hasSdlc ? '重新指派' : '启动交付'}
        </Button>
        <Button icon={<UserAddOutlined />} onClick={onAssignHuman}>
          指派给真人
        </Button>
        <Button icon={<SwapOutlined />} onClick={onTransition}>
          流转状态
        </Button>
        <Button icon={<SyncOutlined />} onClick={onSyncExternal} loading={syncExternalLoading}>
          立即对账
        </Button>
        <Button icon={<CommentOutlined />} onClick={onAddComment}>
          添加评论
        </Button>
        <Tooltip title={deleteDisabled ? deleteDisabledReason : null}>
          <span>
            <Button
              danger
              icon={<DeleteOutlined />}
              onClick={onDelete}
              loading={deleteLoading}
              disabled={deleteDisabled}
            >
              删除工单
            </Button>
          </span>
        </Tooltip>
      </Space>
    </div>
  );
}
