import { useTranslation } from 'react-i18next';
import { i18n } from '@/shared/i18n';
import { formatDateTime } from '@/shared/i18n/format';
import { getAgentRoleLabel, getAgentStatusLabel } from './constants';
import { useState } from 'react';
import { Alert, Card, Tag, Button, Space, Modal, Input, message, Descriptions, Spin, Empty } from 'antd';
import { Table } from '@/shared/theme/ThemedTable';
import { useQueryClient } from '@tanstack/react-query';
import { CheckOutlined, CloseOutlined, EyeOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { PENDING_REVIEW_COUNT_QUERY_KEY, PENDING_REVIEWS_QUERY_KEY, usePendingReviews, useApproveAgent, useRejectAgent, useAgentVersion } from './hooks';
import type { Agent } from './api';
import { ApiError } from '@/shared/types/common';
import type { ColumnsType } from 'antd/es/table';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';

function reviewErrorText(err: unknown, fallback: string): string {
  if (err instanceof ApiError && err.message) return err.message;
  if (err instanceof Error && err.message) return err.message;
  return fallback;
}

export function AgentReviewPage() {
  const { t } = useTranslation('agent');
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { data: pendingAgents = [], isLoading } = usePendingReviews();
  const approveAgent = useApproveAgent();
  const rejectAgent = useRejectAgent();
  const accessCommand = useAccessCommand();

  const [previewAgent, setPreviewAgent] = useState<Agent | null>(null);
  const [rejectModalAgent, setRejectModalAgent] = useState<Agent | null>(null);
  const [rejectComment, setRejectComment] = useState('');
  const [feedback, setFeedback] = useState<{ kind: 'approved' | 'rejected'; name: string } | null>(null);

  const invalidatePendingReviews = () => {
    queryClient.invalidateQueries({ queryKey: PENDING_REVIEWS_QUERY_KEY });
    queryClient.invalidateQueries({ queryKey: PENDING_REVIEW_COUNT_QUERY_KEY });
  };

  const handleApprove = (agent: Agent) => {
    accessCommand('READ_WRITE', t('display.approveAction'), () => {
      approveAgent.mutate({ agentId: agent.id }, {
        onSuccess: () => {
          message.success(i18n.t('display.approvedName', { ns: 'agent', name: agent.name }));
          setFeedback({ kind: 'approved', name: agent.name });
        },
        onError: (err) => {
          message.error(reviewErrorText(err, i18n.t('display.approveError', { ns: 'agent' })));
          invalidatePendingReviews();
        },
      });
    });
  };

  const handleReject = () => {
    if (!rejectModalAgent || !rejectComment.trim()) return;
    const target = rejectModalAgent;
    const comment = rejectComment;
    accessCommand('READ_WRITE', t('display.rejectAction'), () => {
      rejectAgent.mutate({ agentId: target.id, comment }, {
        onSuccess: () => {
          message.success(i18n.t('display.rejectedName', { ns: 'agent', name: target.name }));
          setFeedback({ kind: 'rejected', name: target.name });
          setRejectModalAgent(null);
          setRejectComment('');
        },
        onError: (err) => {
          message.error(reviewErrorText(err, i18n.t('display.rejectError', { ns: 'agent' })));
          invalidatePendingReviews();
        },
      });
    });
  };

  const columns: ColumnsType<Agent> = [
    { title: 'ID', dataIndex: 'id', width: 70 },
    {
      title: t('fields.name'), dataIndex: 'name', align: 'left',
      render: (name: string, record: Agent) => <a onClick={() => navigate(`/agents/${record.id}`)}>{name}</a>,
    },
    { title: t('display.latestVersion'), dataIndex: 'latestVersionNo', width: 90, render: (v: number | null) => v ? `v${v}` : '-' },
    { title: t('display.submittedAt'), dataIndex: 'gmtCreate', width: 160, render: (t: string) => formatDateTime(t) },
    {
      title: t('fields.actions'), width: 240,
      render: (_: unknown, record: Agent) => (
        <Space>
          <Button size="small" icon={<EyeOutlined aria-hidden />} onClick={() => setPreviewAgent(record)}>
            {t('display.preview')}
          </Button>
          <Button size="small" type="primary" icon={<CheckOutlined aria-hidden />}
            onClick={() => handleApprove(record)} loading={approveAgent.isPending}>
            {t('display.approve')}
          </Button>
          <Button size="small" danger icon={<CloseOutlined aria-hidden />}
            onClick={() => accessCommand('READ_WRITE', t('display.rejectAction'), () => setRejectModalAgent(record))}>
            {t('display.reject')}
          </Button>
        </Space>
      ),
    },
  ];

  return (
    <div>
      {feedback && (
        <Alert
          showIcon
          type="success"
          message={t(feedback.kind === 'approved' ? 'display.approvedName' : 'display.rejectedName', { name: feedback.name })}
          description={t(feedback.kind === 'approved' ? 'display.approvedHint' : 'display.rejectedHint')}
          style={{ marginBottom: 16 }}
        />
      )}

      <Card className="aw-content-card">
        {isLoading ? (
          <Spin style={{ display: 'block', margin: '40px auto' }} />
        ) : pendingAgents.length === 0 ? (
          <Empty description={t('display.emptyReviews')} />
        ) : (
          <Table rowKey="id" columns={columns} dataSource={pendingAgents} pagination={false} />
        )}
      </Card>

      {/* Preview Modal */}
      <Modal title={t('display.previewTitle', { name: previewAgent?.name ?? '' })} open={!!previewAgent} closable={{ 'aria-label': t('display.close') }}
        onCancel={() => setPreviewAgent(null)} footer={null} width={640}>
        {previewAgent && <VersionPreview agentId={previewAgent.id} versionNo={previewAgent.latestVersionNo ?? 0} />}
      </Modal>

      {/* Reject Modal */}
      <Modal title={t('display.rejectTitle', { name: rejectModalAgent?.name ?? '' })} open={!!rejectModalAgent} closable={{ 'aria-label': t('display.close') }}
        onOk={handleReject} onCancel={() => { setRejectModalAgent(null); setRejectComment(''); }}
        cancelText={t('actions.cancel')} okText={t('display.confirmReject')} okButtonProps={{ danger: true, disabled: !rejectComment.trim() }}>
        <Input.TextArea rows={3} aria-label={t('display.rejectReason')} placeholder={t('display.rejectReason')}
          value={rejectComment} onChange={e => setRejectComment(e.target.value)} />
      </Modal>
    </div>
  );
}

function VersionPreview({ agentId, versionNo }: { agentId: number; versionNo: number }) {
  const { t } = useTranslation('agent');
  const { data: version, isLoading } = useAgentVersion(agentId, versionNo);

  if (isLoading) return <Spin style={{ display: 'block', margin: '20px auto' }} />;
  if (!version) return <Empty description={t('errors.versionLoad')} />;

  return (
    <Descriptions column={1} bordered size="small">
      <Descriptions.Item label={t('fields.roleName')}>{getAgentRoleLabel(version.roleCode, version.roleName)}</Descriptions.Item>
      <Descriptions.Item label={t('fields.roleCode')}>{version.roleCode}</Descriptions.Item>
      <Descriptions.Item label={t('display.businessBackground')}>
        <div style={{ whiteSpace: 'pre-wrap' }}>{version.businessBackground || '-'}</div>
      </Descriptions.Item>
      <Descriptions.Item label={t('display.responsibilities')}>
        <div style={{ whiteSpace: 'pre-wrap' }}>{version.responsibilities || '-'}</div>
      </Descriptions.Item>
      <Descriptions.Item label={t('display.sdlcId')}>{version.sdlcId ?? '-'}</Descriptions.Item>
      <Descriptions.Item label={t('display.versionStatus')}>
        <Tag color="processing">{getAgentStatusLabel(version.status)}</Tag>
      </Descriptions.Item>
    </Descriptions>
  );
}
