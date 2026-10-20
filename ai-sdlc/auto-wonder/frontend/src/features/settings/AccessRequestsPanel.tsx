import { useTranslation } from 'react-i18next';
import { formatDateTime } from '@/shared/i18n/format';
import { isWorkspaceAccessLevel } from '@/shared/auth/access';
import { useState } from 'react';
import { Button, Empty, Input, Modal, Radio, Space, Tag } from 'antd';
import { Table } from '@/shared/theme/ThemedTable';
import type { ColumnsType } from 'antd/es/table';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';
import { workspaceRoleLabel } from './roles';
import { useAccessRequests, useApproveAccessRequest, useRejectAccessRequest } from './hooks';
import type { AccessRequestVO } from './api';

type RequestStatus = 'PENDING' | 'APPROVED' | 'REJECTED';

const LEVEL_COLORS: Record<string, string> = {
  READ_ONLY: 'default',
  READ_WRITE: 'blue',
  ADMIN: 'green',
};

export function AccessRequestsPanel() {
  useTranslation('shared');
  const { t } = useTranslation('settings');
  const accessCommand = useAccessCommand();
  const [status, setStatus] = useState<RequestStatus>('PENDING');
  const { data: requests = [], isLoading, isError } = useAccessRequests(status);
  const approveMutation = useApproveAccessRequest();
  const rejectMutation = useRejectAccessRequest();
  const [rejectTarget, setRejectTarget] = useState<AccessRequestVO | null>(null);
  const [reason, setReason] = useState('');

  const handleApprove = (request: AccessRequestVO) => {
    accessCommand('ADMIN', t('approveRequest'), () => {
      approveMutation.mutate(request.id);
    });
  };

  const openReject = (request: AccessRequestVO) => {
    accessCommand('ADMIN', t('rejectRequest'), () => {
      setReason('');
      setRejectTarget(request);
    });
  };

  const handleRejectConfirm = () => {
    if (!rejectTarget) return;
    const trimmed = reason.trim();
    rejectMutation.mutate(
      { requestId: rejectTarget.id, reason: trimmed ? trimmed : undefined },
      { onSuccess: () => setRejectTarget(null) },
    );
  };

  const columns: ColumnsType<AccessRequestVO> = [
    {
      title: t('requester'),
      key: 'requester',
      render: (_, request) => request.requesterName || String(request.requesterId),
    },
    {
      title: t('requestedAccess'),
      dataIndex: 'requestedLevel',
      render: (level: string) => (
        <Tag color={isWorkspaceAccessLevel(level) ? LEVEL_COLORS[level] : undefined}>{isWorkspaceAccessLevel(level) ? workspaceRoleLabel({ id: 0, code: level, name: null }) : level}</Tag>
      ),
    },
    {
      title: t('submittedAt'),
      dataIndex: 'gmtCreate',
      render: (value: string) => (value ? formatDateTime(value, { year: 'numeric', month: 'numeric', day: 'numeric' }) : '-'),
    },
    ...(status !== 'PENDING'
      ? [{
          title: t('reviewer'),
          key: 'reviewer',
          render: (_, request: AccessRequestVO) => request.reviewerName
            || (request.reviewerId != null ? String(request.reviewerId) : '-'),
        } as ColumnsType<AccessRequestVO>[number]]
      : []),
    ...(status === 'REJECTED'
      ? [{
          title: t('rejectReason'),
          dataIndex: 'rejectReason',
          render: (value: string | null) => value || '-',
        } as ColumnsType<AccessRequestVO>[number]]
      : []),
    ...(status === 'PENDING'
      ? [{
          title: t('actions'),
          key: 'action',
          render: (_, request: AccessRequestVO) => (
            <Space>
              <Button
                type="link"
                size="small"
                onClick={() => handleApprove(request)}
              >
                {t('approve')}
              </Button>
              <Button
                type="link"
                size="small"
                danger
                onClick={() => openReject(request)}
              >
                {t('reject')}
              </Button>
            </Space>
          ),
        } as ColumnsType<AccessRequestVO>[number]]
      : []),
  ];

  const emptyDescription = status === 'PENDING' ? t('emptyPending') : status === 'APPROVED' ? t('emptyApproved') : t('emptyRejected');

  return (
    <div>
      <Space style={{ marginBottom: 16 }}>
        <Radio.Group
          value={status}
          onChange={(event) => setStatus(event.target.value as RequestStatus)}
          options={[
            { label: t('pending'), value: 'PENDING' },
            { label: t('approved'), value: 'APPROVED' },
            { label: t('rejected'), value: 'REJECTED' },
          ]}
          optionType="button"
        />
      </Space>
      <Table
        rowKey="id"
        columns={columns}
        dataSource={requests}
        loading={isLoading}
        pagination={false}
        locale={{
          emptyText: isError
            ? <Empty description={t('requestsLoadFailed')} />
            : <Empty description={emptyDescription} />,
        }}
      />
      <Modal
        closable={{ 'aria-label': t('close') }}
        title={t('rejectRequest')}
        open={rejectTarget !== null}
        onCancel={() => setRejectTarget(null)}
        onOk={handleRejectConfirm}
        confirmLoading={rejectMutation.isPending}
        okText={t('confirmReject')}
        cancelText={t('cancel')}
        okButtonProps={{ danger: true, 'aria-label': t('confirmReject') }}
      >
        <p style={{ color: 'var(--aw-muted)' }}>
          {t('rejectDescription', { name: rejectTarget ? (rejectTarget.requesterName || rejectTarget.requesterId) : '' })}
        </p>
        <Input.TextArea
          aria-label={t('optionalReason')}
          value={reason}
          onChange={(event) => setReason(event.target.value)}
          placeholder={t('optionalReason')}
          maxLength={512}
          rows={3}
        />
      </Modal>
    </div>
  );
}
