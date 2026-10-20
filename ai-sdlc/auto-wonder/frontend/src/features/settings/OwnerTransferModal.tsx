import { useTranslation } from 'react-i18next';
import { useEffect, useState } from 'react';
import { Alert, Modal, Select } from 'antd';
import type { MemberVO } from './api';

interface OwnerTransferModalProps {
  open: boolean;
  candidates: MemberVO[];
  loading: boolean;
  onClose: () => void;
  onConfirm: (targetUserId: number) => void | Promise<void>;
}

export function OwnerTransferModal({
  open,
  candidates,
  loading,
  onClose,
  onConfirm,
}: OwnerTransferModalProps) {
  const { t } = useTranslation('settings');
  const [targetUserId, setTargetUserId] = useState<number>();

  useEffect(() => {
    if (!open) {
      setTargetUserId(undefined);
    }
  }, [open]);

  return (
    <Modal
      closable={{ 'aria-label': t('close') }}
      title={t('transferOwnerTitle')}
      open={open}
      okText={t('confirmTransfer')}
      cancelText={t('cancel')}
      okButtonProps={{ 'aria-label': t('confirmTransfer'), disabled: targetUserId === undefined }}
      confirmLoading={loading}
      onCancel={onClose}
      onOk={() => targetUserId !== undefined && onConfirm(targetUserId)}
      destroyOnHidden
    >
      <Alert
        type="warning"
        showIcon
        message={t('transferOwnerHint')}
        style={{ marginBottom: 16 }}
      />
      <Select
        aria-label={t('targetMember')}
        value={targetUserId}
        placeholder={t('chooseTarget')}
        style={{ width: '100%' }}
        onChange={setTargetUserId}
        options={candidates.map((member) => ({
          value: member.userId,
          label: `${member.nickname || member.username}${member.email ? ` (${member.email})` : ''}`,
        }))}
      />
    </Modal>
  );
}
