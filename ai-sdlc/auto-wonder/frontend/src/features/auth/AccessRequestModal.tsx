import { i18n } from '@/shared/i18n';
import { useTranslation } from 'react-i18next';
import { useEffect, useState } from 'react';
import { Modal, Radio, Space, Typography, message } from 'antd';
import { ApiError } from '@/shared/types/common';
import type { WorkspaceAccessLevel, WorkspaceListItem } from '@/shared/types/common';
import { useSubmitAccessRequest } from './workspaceDiscoveryApi';

const { Text } = Typography;

const LEVEL_OPTIONS: Array<{
  value: WorkspaceAccessLevel;
  label: string;
  description: string;
}> = [
  { value: 'READ_ONLY', get label() { return i18n.t('auth:access.readOnly'); }, get description() { return i18n.t('auth:access.readOnlyDescription'); } },
  { value: 'READ_WRITE', get label() { return i18n.t('auth:access.readWrite'); }, get description() { return i18n.t('auth:access.readWriteDescription'); } },
  { value: 'ADMIN', get label() { return i18n.t('auth:access.admin'); }, get description() { return i18n.t('auth:access.adminDescription'); } },
];

interface AccessRequestModalProps {
  workspace: WorkspaceListItem | null;
  onClose: () => void;
}

export function AccessRequestModal({ workspace, onClose }: AccessRequestModalProps) {
  const { t } = useTranslation('auth');
  const [requestedLevel, setRequestedLevel] = useState<WorkspaceAccessLevel>('READ_ONLY');
  const { mutateAsync, isPending } = useSubmitAccessRequest();

  // Keyed on the workspace id rather than on open/close alone: the component instance
  // survives between openings, so a plain useState would silently carry the previous
  // choice into the next workspace's submission. Depending on the `workspace` object
  // instead of its id would reset the user's in-progress choice on any re-render that
  // hands back a new object identity (e.g. a list refetch), which is a subtler form of
  // the same bug.
  useEffect(() => {
    if (workspace) {
      setRequestedLevel('READ_ONLY');
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [workspace?.id]);

  const handleSubmit = async () => {
    if (!workspace) return;
    try {
      await mutateAsync({ workspaceId: workspace.id, requestedLevel });
      message.success(i18n.t('auth:access.submitted'));
      onClose();
    } catch (e) {
      // The mutation hook deliberately carries no onError, so the specific business
      // message (already pending, already a member, invalid level) is surfaced here.
      message.error(e instanceof ApiError ? e.message : i18n.t('auth:access.failed'));
    }
  };

  return (
    <Modal
      title={workspace ? t('access.title', { name: workspace.name }) : ''}
      open={workspace !== null}
      okText={t('access.submit')}
      cancelText={t('action.cancel')}
      confirmLoading={isPending}
      onCancel={onClose}
      onOk={handleSubmit}
      destroyOnHidden
    >
      <Text style={{ display: 'block', marginBottom: 12, color: 'var(--aw-muted)' }}>{t('access.description')}</Text>
      <Radio.Group
        value={requestedLevel}
        onChange={(event) => setRequestedLevel(event.target.value as WorkspaceAccessLevel)}
      >
        <Space direction="vertical" size={12}>
          {LEVEL_OPTIONS.map((option) => (
            <Radio key={option.value} value={option.value}>
              <span style={{ fontWeight: 600, color: 'var(--aw-text)' }}>{option.label}</span>
              <Text style={{ display: 'block', color: 'var(--aw-muted)', fontSize: 12 }}>
                {option.description}
              </Text>
            </Radio>
          ))}
        </Space>
      </Radio.Group>
    </Modal>
  );
}
