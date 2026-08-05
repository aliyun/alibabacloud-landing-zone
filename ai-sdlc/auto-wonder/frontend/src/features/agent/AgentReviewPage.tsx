import { useState } from 'react';
import { Alert, Card, Table, Tag, Button, Space, Modal, Input, message, Descriptions, Spin, Empty } from 'antd';
import { useQueryClient } from '@tanstack/react-query';
import { CheckOutlined, CloseOutlined, EyeOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { usePendingReviews, useApproveAgent, useRejectAgent, useAgentVersion } from './hooks';
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
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { data: pendingAgents = [], isLoading } = usePendingReviews();
  const approveAgent = useApproveAgent();
  const rejectAgent = useRejectAgent();
  const accessCommand = useAccessCommand();

  const [previewAgent, setPreviewAgent] = useState<Agent | null>(null);
  const [rejectModalAgent, setRejectModalAgent] = useState<Agent | null>(null);
  const [rejectComment, setRejectComment] = useState('');
  const [feedback, setFeedback] = useState<{ message: string; description: string } | null>(null);

  const invalidatePendingReviews = () => {
    queryClient.invalidateQueries({ queryKey: ['pendingReviews'] });
    queryClient.invalidateQueries({ queryKey: ['agents', 'reviews', 'count'] });
  };

  const handleApprove = (agent: Agent) => {
    accessCommand('READ_WRITE', '通过数字员工审核', () => {
      approveAgent.mutate({ agentId: agent.id }, {
        onSuccess: () => {
          message.success(`${agent.name} 已通过审核`);
          setFeedback({
            message: `${agent.name} 已通过审核`,
            description: '该 Agent 已可进入上线流转，待审核列表会自动刷新。',
          });
        },
        onError: (err) => {
          message.error(reviewErrorText(err, '审核操作失败，请刷新后重试'));
          invalidatePendingReviews();
        },
      });
    });
  };

  const handleReject = () => {
    if (!rejectModalAgent || !rejectComment.trim()) return;
    const target = rejectModalAgent;
    const comment = rejectComment;
    accessCommand('READ_WRITE', '驳回数字员工审核', () => {
      rejectAgent.mutate({ agentId: target.id, comment }, {
        onSuccess: () => {
          message.success(`${target.name} 已驳回`);
          setFeedback({
            message: `${target.name} 已驳回`,
            description: '已记录驳回意见，可在修订后重新提交审核。',
          });
          setRejectModalAgent(null);
          setRejectComment('');
        },
        onError: (err) => {
          message.error(reviewErrorText(err, '驳回操作失败，请刷新后重试'));
          invalidatePendingReviews();
        },
      });
    });
  };

  const columns: ColumnsType<Agent> = [
    { title: 'ID', dataIndex: 'id', width: 70 },
    {
      title: '名称', dataIndex: 'name',
      render: (name: string, record: Agent) => <a onClick={() => navigate(`/agents/${record.id}`)}>{name}</a>,
    },
    { title: '最新版本', dataIndex: 'latestVersionNo', width: 90, render: (v: number | null) => v ? `v${v}` : '-' },
    { title: '提交时间', dataIndex: 'gmtCreate', width: 160, render: (t: string) => new Date(t).toLocaleString('zh-CN') },
    {
      title: '操作', width: 240,
      render: (_: unknown, record: Agent) => (
        <Space>
          <Button size="small" icon={<EyeOutlined />} onClick={() => setPreviewAgent(record)}>
            预览
          </Button>
          <Button size="small" type="primary" icon={<CheckOutlined />}
            onClick={() => handleApprove(record)} loading={approveAgent.isPending}>
            通过
          </Button>
          <Button size="small" danger icon={<CloseOutlined />}
            onClick={() => accessCommand('READ_WRITE', '驳回数字员工审核', () => setRejectModalAgent(record))}>
            驳回
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
          message={feedback.message}
          description={feedback.description}
          style={{ marginBottom: 16 }}
        />
      )}

      <Card title="Agent 版本审核">
        {isLoading ? (
          <Spin style={{ display: 'block', margin: '40px auto' }} />
        ) : pendingAgents.length === 0 ? (
          <Empty description="暂无待审核的数字员工" />
        ) : (
          <Table rowKey="id" columns={columns} dataSource={pendingAgents} pagination={false} />
        )}
      </Card>

      {/* Preview Modal */}
      <Modal title={`配置预览 — ${previewAgent?.name}`} open={!!previewAgent}
        onCancel={() => setPreviewAgent(null)} footer={null} width={640}>
        {previewAgent && <VersionPreview agentId={previewAgent.id} versionNo={previewAgent.latestVersionNo ?? 0} />}
      </Modal>

      {/* Reject Modal */}
      <Modal title={`驳回 — ${rejectModalAgent?.name}`} open={!!rejectModalAgent}
        onOk={handleReject} onCancel={() => { setRejectModalAgent(null); setRejectComment(''); }}
        okText="确认驳回" okButtonProps={{ danger: true, disabled: !rejectComment.trim() }}>
        <Input.TextArea rows={3} placeholder="请输入驳回原因..."
          value={rejectComment} onChange={e => setRejectComment(e.target.value)} />
      </Modal>
    </div>
  );
}

function VersionPreview({ agentId, versionNo }: { agentId: number; versionNo: number }) {
  const { data: version, isLoading } = useAgentVersion(agentId, versionNo);

  if (isLoading) return <Spin style={{ display: 'block', margin: '20px auto' }} />;
  if (!version) return <Empty description="无法加载版本详情" />;

  return (
    <Descriptions column={1} bordered size="small">
      <Descriptions.Item label="角色名称">{version.roleName}</Descriptions.Item>
      <Descriptions.Item label="角色码">{version.roleCode}</Descriptions.Item>
      <Descriptions.Item label="业务背景">
        <div style={{ whiteSpace: 'pre-wrap' }}>{version.businessBackground || '-'}</div>
      </Descriptions.Item>
      <Descriptions.Item label="工作职责">
        <div style={{ whiteSpace: 'pre-wrap' }}>{version.responsibilities || '-'}</div>
      </Descriptions.Item>
      <Descriptions.Item label="SDLC 模版 ID">{version.sdlcId ?? '-'}</Descriptions.Item>
      <Descriptions.Item label="版本状态">
        <Tag color="processing">{version.status}</Tag>
      </Descriptions.Item>
    </Descriptions>
  );
}
