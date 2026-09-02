import { useState } from 'react';
import { Button, Empty, Input, Modal, Radio, Space, Table, Tag } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';
import { ACCESS_LEVEL_LABEL } from '@/shared/auth/access';
import { useAccessRequests, useApproveAccessRequest, useRejectAccessRequest } from './hooks';
import type { AccessRequestVO } from './api';

type RequestStatus = 'PENDING' | 'APPROVED' | 'REJECTED';

const LEVEL_COLORS: Record<string, string> = {
  READ_ONLY: 'default',
  READ_WRITE: 'blue',
  ADMIN: 'green',
};

const STATUS_LABELS: Record<RequestStatus, string> = {
  PENDING: '待审批',
  APPROVED: '已通过',
  REJECTED: '已拒绝',
};

export function AccessRequestsPanel() {
  const accessCommand = useAccessCommand();
  const [status, setStatus] = useState<RequestStatus>('PENDING');
  const { data: requests = [], isLoading, isError } = useAccessRequests(status);
  const approveMutation = useApproveAccessRequest();
  const rejectMutation = useRejectAccessRequest();
  const [rejectTarget, setRejectTarget] = useState<AccessRequestVO | null>(null);
  const [reason, setReason] = useState('');

  const handleApprove = (request: AccessRequestVO) => {
    accessCommand('ADMIN', '通过权限申请', () => {
      approveMutation.mutate(request.id);
    });
  };

  const openReject = (request: AccessRequestVO) => {
    accessCommand('ADMIN', '拒绝权限申请', () => {
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
      title: '申请人',
      key: 'requester',
      render: (_, request) => request.requesterName || String(request.requesterId),
    },
    {
      title: '申请权限',
      dataIndex: 'requestedLevel',
      render: (level: string) => (
        <Tag color={LEVEL_COLORS[level]}>{ACCESS_LEVEL_LABEL[level as keyof typeof ACCESS_LEVEL_LABEL]}</Tag>
      ),
    },
    {
      title: '提交时间',
      dataIndex: 'gmtCreate',
      render: (value: string) => (value ? new Date(value).toLocaleDateString() : '-'),
    },
    ...(status !== 'PENDING'
      ? [{
          title: '审批人',
          key: 'reviewer',
          render: (_, request: AccessRequestVO) => request.reviewerName
            || (request.reviewerId != null ? String(request.reviewerId) : '-'),
        } as ColumnsType<AccessRequestVO>[number]]
      : []),
    ...(status === 'REJECTED'
      ? [{
          title: '拒绝原因',
          dataIndex: 'rejectReason',
          render: (value: string | null) => value || '-',
        } as ColumnsType<AccessRequestVO>[number]]
      : []),
    ...(status === 'PENDING'
      ? [{
          title: '操作',
          key: 'action',
          render: (_, request: AccessRequestVO) => (
            <Space>
              <Button
                type="link"
                size="small"
                onClick={() => handleApprove(request)}
              >
                通过
              </Button>
              <Button
                type="link"
                size="small"
                danger
                onClick={() => openReject(request)}
              >
                拒绝
              </Button>
            </Space>
          ),
        } as ColumnsType<AccessRequestVO>[number]]
      : []),
  ];

  return (
    <div>
      <Space style={{ marginBottom: 16 }}>
        <Radio.Group
          value={status}
          onChange={(event) => setStatus(event.target.value as RequestStatus)}
          options={[
            { label: STATUS_LABELS.PENDING, value: 'PENDING' },
            { label: STATUS_LABELS.APPROVED, value: 'APPROVED' },
            { label: STATUS_LABELS.REJECTED, value: 'REJECTED' },
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
            ? <Empty description="权限申请加载失败，请稍后重试" />
            : <Empty description={status === 'PENDING' ? '暂无待审批的申请' : `暂无${STATUS_LABELS[status]}的申请`} />,
        }}
      />
      <Modal
        title="拒绝权限申请"
        open={rejectTarget !== null}
        onCancel={() => setRejectTarget(null)}
        onOk={handleRejectConfirm}
        confirmLoading={rejectMutation.isPending}
        okText="确认拒绝"
        cancelText="取消"
        okButtonProps={{ danger: true }}
      >
        <p style={{ color: '#697386' }}>
          拒绝 {rejectTarget ? (rejectTarget.requesterName || rejectTarget.requesterId) : ''} 的申请
        </p>
        <Input.TextArea
          value={reason}
          onChange={(event) => setReason(event.target.value)}
          placeholder="拒绝原因（可选）"
          maxLength={512}
          rows={3}
        />
      </Modal>
    </div>
  );
}
