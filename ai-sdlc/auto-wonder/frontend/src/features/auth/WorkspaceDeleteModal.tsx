import { i18n } from '@/shared/i18n';
import { useTranslation } from 'react-i18next';
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
  const { t } = useTranslation('auth');
  const { mutateAsync, isPending } = useDeleteWorkspace();

  const handleDelete = async () => {
    if (!workspace) return;
    try {
      await mutateAsync(workspace.id);
      message.success(i18n.t('auth:workspace.deleted'));
      onDeleted(workspace);
      onClose();
    } catch (e) {
      message.error(e instanceof ApiError ? e.message : i18n.t('auth:workspace.deleteFailed'));
    }
  };

  return (
    <Modal
      title={workspace ? t('workspace.deleteTitle', { name: workspace.name }) : ''}
      open={workspace !== null}
      okText={t('workspace.confirmDelete')}
      cancelText={t('action.cancel')}
      okButtonProps={{ danger: true }}
      confirmLoading={isPending}
      onCancel={onClose}
      onOk={handleDelete}
      destroyOnHidden
    >
      <ul data-testid="workspace-delete-consequences" style={listStyle}>
        <li>{t('workspace.deleteMove')}</li>
        <li>{t('workspace.deleteAccess')}</li>
        <li>{t('workspace.deleteData')}</li>
      </ul>
      <Text type="secondary" style={{ fontSize: 12 }}>{t('workspace.deletePause')}</Text>
    </Modal>
  );
}

const listStyle: CSSProperties = {
  margin: '0 0 12px',
  paddingLeft: 20,
  color: 'var(--aw-text)',
  lineHeight: 1.9,
};
