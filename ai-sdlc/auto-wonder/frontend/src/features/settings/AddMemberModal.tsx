import { useTranslation } from 'react-i18next';
import { useEffect, useState } from 'react';
import { Modal, Select, Space } from 'antd';
import { useMemberCandidates } from './hooks';
import { workspaceRoleLabel } from './roles';
import type { WorkspaceRoleVO } from './api';

interface AddMemberModalProps {
  open: boolean;
  roles: WorkspaceRoleVO[];
  loading: boolean;
  onClose: () => void;
  onConfirm: (userId: number, roleId?: number) => void;
}

export function AddMemberModal({
  open,
  roles,
  loading,
  onClose,
  onConfirm,
}: AddMemberModalProps) {
  const { t } = useTranslation('settings');
  const [keyword, setKeyword] = useState('');
  const [selectedUserId, setSelectedUserId] = useState<number>();
  const [selectedRoleId, setSelectedRoleId] = useState<number>();
  const { data: candidates = [], isFetching } = useMemberCandidates(keyword);

  useEffect(() => {
    if (!open) {
      setKeyword('');
      setSelectedUserId(undefined);
      setSelectedRoleId(undefined);
    }
  }, [open]);

  const defaultRoleId = roles.find((role) => role.code === 'READ_ONLY')?.id;

  const handleOk = () => {
    // 未选择人员时确认按钮禁用，走到这里 selectedUserId 必然有值。
    onConfirm(selectedUserId!, selectedRoleId);
  };

  return (
    <Modal
      closable={{ 'aria-label': t('close') }}
      title={t('addMember')}
      open={open}
      okText={t('addMember')}
      cancelText={t('cancel')}
      okButtonProps={{ 'aria-label': t('addMember'), disabled: selectedUserId === undefined }}
      confirmLoading={loading}
      onCancel={onClose}
      onOk={handleOk}
      destroyOnHidden
    >
      <Space direction="vertical" size={16} style={{ width: '100%' }}>
        <Select
          showSearch
          allowClear
          filterOption={false}
          aria-label={t('searchPeople')}
          placeholder={t('searchPeople')}
          value={selectedUserId}
          loading={isFetching}
          style={{ width: '100%' }}
          onSearch={setKeyword}
          onClear={() => {
            setSelectedUserId(undefined);
            setKeyword('');
          }}
          onChange={setSelectedUserId}
          options={candidates.map((candidate) => ({
            value: candidate.userId,
            label: `${candidate.nickname || candidate.username}${candidate.email ? ` (${candidate.email})` : ''}`,
          }))}
          notFoundContent={
            keyword.trim() ? t('noCandidates') : t('searchPeopleHint')
          }
        />
        <Select
          aria-label={t('newMemberRole')}
          style={{ width: '100%' }}
          value={selectedRoleId ?? defaultRoleId}
          onChange={setSelectedRoleId}
          options={roles.map((role) => ({ value: role.id, label: workspaceRoleLabel(role) }))}
        />
      </Space>
    </Modal>
  );
}
