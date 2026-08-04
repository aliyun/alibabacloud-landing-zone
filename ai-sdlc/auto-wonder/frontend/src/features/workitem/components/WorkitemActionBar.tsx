import { Button, Space, Tooltip } from 'antd';
import { SwapOutlined, UserSwitchOutlined, CommentOutlined, RocketOutlined, SyncOutlined, DeleteOutlined } from '@ant-design/icons';

interface WorkitemActionBarProps {
  hasSdlc?: boolean;
  onStartDelivery?: () => void;
  onTransition?: () => void;
  onAddComment?: () => void;
  onSyncExternal?: () => void;
  onDelete?: () => void;
  syncExternalLoading?: boolean;
  deleteLoading?: boolean;
  deleteDisabled?: boolean;
  deleteDisabledReason?: string | null;
}

export function WorkitemActionBar({
  hasSdlc,
  onStartDelivery,
  onTransition,
  onAddComment,
  onSyncExternal,
  onDelete,
  syncExternalLoading,
  deleteLoading,
  deleteDisabled,
  deleteDisabledReason,
}: WorkitemActionBarProps) {
  return (
    <div data-testid="workitem-action-bar" style={{ background: '#f5f5f5', borderRadius: 8, padding: '12px 0' }}>
      <Space>
        <Button
          type="primary"
          icon={hasSdlc ? <UserSwitchOutlined /> : <RocketOutlined />}
          style={{ background: '#ff6a00', borderColor: '#ff6a00' }}
          onClick={onStartDelivery}
        >
          {hasSdlc ? '重新指派' : '启动交付'}
        </Button>
        <Button icon={<SwapOutlined />} onClick={onTransition}>
          流转状态
        </Button>
        <Button icon={<SyncOutlined />} onClick={onSyncExternal} loading={syncExternalLoading}>
          同步 Aone
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
