import { Modal, Typography, message } from 'antd';
import type { CSSProperties } from 'react';
import { ApiError } from '@/shared/types/common';
import type { WorkspaceInfo } from '@/shared/types/common';
import { useDeleteWorkspace } from './workspaceLifecycleApi';

const { Text } = Typography;

interface WorkspaceDeleteModalProps {
  workspace: WorkspaceInfo | null;
  /** Called after a successful delete so the caller can drop a binding to the deleted workspace. */
  onDeleted: (workspace: WorkspaceInfo) => void;
  onClose: () => void;
}

export function WorkspaceDeleteModal({ workspace, onDeleted, onClose }: WorkspaceDeleteModalProps) {
  const { mutateAsync, isPending } = useDeleteWorkspace();

  const handleDelete = async () => {
    if (!workspace) return;
    try {
      await mutateAsync(workspace.id);
      message.success('工作空间已移入回收站');
      onDeleted(workspace);
      onClose();
    } catch (e) {
      message.error(e instanceof ApiError ? e.message : '删除失败，请稍后重试');
    }
  };

  return (
    <Modal
      title={workspace ? `删除「${workspace.name}」` : ''}
      open={workspace !== null}
      okText="确认删除"
      cancelText="取消"
      okButtonProps={{ danger: true }}
      confirmLoading={isPending}
      onCancel={onClose}
      onOk={handleDelete}
      destroyOnHidden
    >
      <ul data-testid="workspace-delete-consequences" style={listStyle}>
        <li>删除后该工作空间将移入回收站。</li>
        <li>成员暂时无法进入或访问该工作空间。</li>
        <li>数据不会物理删除，可通过回收站恢复。</li>
      </ul>
      <Text type="secondary" style={{ fontSize: 12 }}>
        进行中的定时任务与交付会被暂停，恢复后需要手动重新启用。
      </Text>
    </Modal>
  );
}

const listStyle: CSSProperties = {
  margin: '0 0 12px',
  paddingLeft: 20,
  color: 'var(--aw-text)',
  lineHeight: 1.9,
};
