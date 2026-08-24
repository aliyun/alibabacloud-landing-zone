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
  const [targetUserId, setTargetUserId] = useState<number>();

  useEffect(() => {
    if (!open) {
      setTargetUserId(undefined);
    }
  }, [open]);

  return (
    <Modal
      title="移交工作空间 Owner"
      open={open}
      okText="确认移交"
      cancelText="取消"
      okButtonProps={{ disabled: targetUserId === undefined }}
      confirmLoading={loading}
      onCancel={onClose}
      onOk={() => targetUserId !== undefined && onConfirm(targetUserId)}
      destroyOnHidden
    >
      <Alert
        type="warning"
        showIcon
        message="移交后，新 Owner 将获得管理员权限；当前 Owner 保留管理员权限。"
        style={{ marginBottom: 16 }}
      />
      <Select
        aria-label="目标成员"
        value={targetUserId}
        placeholder="请选择目标成员"
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
