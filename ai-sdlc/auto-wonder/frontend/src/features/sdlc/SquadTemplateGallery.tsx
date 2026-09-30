import { useState } from 'react';
import { Card, Row, Col, Tag, Button, Modal, message, Space, Typography, Avatar, Spin, Steps, Alert } from 'antd';
import { TeamOutlined, UserOutlined, UsergroupAddOutlined, ArrowRightOutlined } from '@ant-design/icons';
import { useQuery, useMutation } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { listSquadTemplates, applySquadTemplate, getSquadTemplateDetail } from './api';
import type { SquadTemplateItem, SquadTemplateDetail, SquadTemplateAgent } from './api';
import { ApiError } from '@/shared/types/common';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';

const { Text, Paragraph } = Typography;

const iconMap: Record<string, React.ReactNode> = {
  solo: <UserOutlined style={{ fontSize: 32 }} />,
  pair: <UsergroupAddOutlined style={{ fontSize: 32 }} />,
  team: <TeamOutlined style={{ fontSize: 32 }} />,
};

const kindColors: Record<string, string> = {
  WORK: 'blue',
  HANDOFF: 'orange',
  GATE: 'red',
};

// apiClient 对网络层失败统一抛 code='10000' 且无 traceId（英文 message），需要转成用户可读文案；
// 其余 ApiError 透出服务端 message，并附 traceId 便于排查。
function applyErrorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.code === '10000' && !error.traceId) {
      return '网络异常，创建失败，请稍后重试';
    }
    if (error.traceId) {
      return `${error.message}（TraceId: ${error.traceId}）`;
    }
    return error.message || '创建失败，请稍后重试';
  }
  return '创建失败，请稍后重试';
}

export function SquadTemplateGallery() {
  const navigate = useNavigate();
  const accessCommand = useAccessCommand();
  const [previewId, setPreviewId] = useState<number | null>(null);
  const [confirming, setConfirming] = useState<SquadTemplateItem | null>(null);
  const [applyErrorText, setApplyErrorText] = useState<string | null>(null);

  const { data: templates = [], isLoading } = useQuery({
    queryKey: ['squad-templates'],
    queryFn: listSquadTemplates,
  });

  const { data: detail, isLoading: detailLoading } = useQuery({
    queryKey: ['squad-template-detail', previewId],
    queryFn: () => getSquadTemplateDetail(previewId!),
    enabled: previewId !== null,
  });

  const applyMut = useMutation({
    mutationFn: applySquadTemplate,
    onSuccess: () => {
      message.success('创建成功');
      setApplyErrorText(null);
      setConfirming(null);
      setPreviewId(null);
      navigate('/agents?tab=squads');
    },
    onError: (error) => {
      setApplyErrorText(applyErrorMessage(error));
    },
  });

  const handleApply = (template: SquadTemplateItem) => {
    accessCommand('READ_WRITE', '应用小队模版', () => {
      setApplyErrorText(null);
      setConfirming(template);
    });
  };

  const handleConfirmOk = () => {
    accessCommand('READ_WRITE', '应用小队模版', () => {
      if (confirming) {
        setApplyErrorText(null);
        applyMut.mutate(confirming.id);
      }
    });
  };

  const closeConfirm = () => {
    setConfirming(null);
    setApplyErrorText(null);
  };

  if (isLoading) {
    return <Card loading />;
  }

  return (
    <>
      <Row gutter={[16, 16]} style={{ padding: '16px 0' }}>
        {templates.map((t) => (
          <Col key={t.id} xs={24} sm={12} lg={8}>
            <Card
              hoverable
              style={{ height: '100%', cursor: 'pointer' }}
              onClick={() => setPreviewId(t.id)}
            >
              <Space direction="vertical" style={{ width: '100%' }} size="middle">
                <Space align="start">
                  <Avatar
                    size={48}
                    icon={iconMap[t.icon || ''] || <TeamOutlined />}
                    style={{ backgroundColor: 'rgba(var(--aw-accent-rgb), .10)', color: 'var(--aw-accent-text)' }}
                  />
                  <div>
                    <Text strong style={{ fontSize: 16 }}>{t.name}</Text>
                    <br />
                    <Text type="secondary">{t.squadSize} 人小队</Text>
                  </div>
                </Space>
                <Paragraph type="secondary" ellipsis={{ rows: 2 }} style={{ marginBottom: 0 }}>
                  {t.description}
                </Paragraph>
                <Space wrap>
                  {t.tags?.map((tag) => <Tag key={tag}>{tag}</Tag>)}
                </Space>
                <Button
                  type="primary"
                  block
                  onClick={(e) => { e.stopPropagation(); handleApply(t); }}
                  loading={applyMut.isPending}
                >
                  基于此模版创建
                </Button>
              </Space>
            </Card>
          </Col>
        ))}
      </Row>

      <Modal
        open={previewId !== null}
        onCancel={() => setPreviewId(null)}
        width={760}
        footer={
          <Space>
            <Button onClick={() => setPreviewId(null)}>关闭</Button>
            <Button
              type="primary"
              loading={applyMut.isPending}
              onClick={() => {
                const t = templates.find((x) => x.id === previewId);
                if (t) handleApply(t);
              }}
            >
              基于此模版创建
            </Button>
          </Space>
        }
        title={null}
        destroyOnClose
      >
        {detailLoading || !detail ? (
          <div style={{ textAlign: 'center', padding: 48 }}><Spin /></div>
        ) : (
          <TemplatePreview detail={detail} />
        )}
      </Modal>

      <Modal
        open={confirming !== null}
        title={confirming ? `基于「${confirming.name}」创建小队` : null}
        okText="确定创建"
        cancelText="取消"
        confirmLoading={applyMut.isPending}
        onOk={handleConfirmOk}
        onCancel={closeConfirm}
      >
        <p style={{ marginBottom: applyErrorText ? 16 : 0 }}>将自动创建小队、数字员工、SDLC 并绑定所有仓库。</p>
        {applyErrorText && (
          <Alert
            type="error"
            showIcon
            message="创建失败"
            description={applyErrorText}
          />
        )}
      </Modal>
    </>
  );
}

function TemplatePreview({ detail }: { detail: SquadTemplateDetail }) {
  return (
    <div style={{ margin: '-20px -24px 0' }}>
      {/* Header */}
      <div style={{ padding: '20px 24px', borderBottom: '1px solid var(--aw-border)', background: 'var(--aw-raised)' }}>
        <Space align="start" style={{ width: '100%', justifyContent: 'space-between' }}>
          <div>
            <div style={{ fontSize: 20, fontWeight: 700 }}>{detail.name}</div>
            <div style={{ color: 'var(--aw-muted)', marginTop: 4 }}>{detail.description}</div>
          </div>
          <Space>
            <Tag color="blue">{detail.squadSize} 人小队</Tag>
            {detail.tags?.map((tag) => <Tag key={tag}>{tag}</Tag>)}
          </Space>
        </Space>
      </div>

      {/* Workflow flow */}
      <div style={{ padding: '16px 24px', borderBottom: '1px solid var(--aw-border)' }}>
        <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--aw-muted)', marginBottom: 10 }}>协作流程</div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
          {detail.agents.map((agent, idx) => (
            <span key={agent.roleCode} style={{ display: 'inline-flex', alignItems: 'center', gap: 8 }}>
              <Tag color="geekblue" style={{ margin: 0, fontSize: 13, padding: '2px 10px' }}>
                {agent.roleName}
              </Tag>
              {idx < detail.agents.length - 1 && (
                <ArrowRightOutlined style={{ color: 'var(--aw-muted)', fontSize: 12 }} />
              )}
            </span>
          ))}
        </div>
      </div>

      {/* Agent details */}
      <div style={{ padding: 24 }}>
        <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--aw-muted)', marginBottom: 14 }}>成员详情</div>
        <div style={{ display: 'grid', gap: 16 }}>
          {detail.agents.map((agent, idx) => (
            <AgentCard key={agent.roleCode} agent={agent} index={idx} />
          ))}
        </div>
      </div>
    </div>
  );
}

function AgentCard({ agent }: { agent: SquadTemplateAgent; index: number }) {

  return (
    <div style={{ border: '1px solid var(--aw-border)', borderRadius: 8, overflow: 'hidden' }}>
      <div style={{ padding: '12px 16px', borderBottom: '1px solid var(--aw-border)', background: 'var(--aw-raised)' }}>
        <Space>
          <div style={{
            width: 28, height: 28, borderRadius: '50%', background: 'var(--aw-primary)',
            color: 'var(--aw-primary-ink)', display: 'grid', placeItems: 'center', fontSize: 12, fontWeight: 700,
          }}>
            {agent.roleCode.slice(0, 2)}
          </div>
          <div>
            <span style={{ fontWeight: 600 }}>{agent.name}</span>
            <Text type="secondary" style={{ marginLeft: 8, fontSize: 12 }}>{agent.roleName}</Text>
          </div>
        </Space>
      </div>
      <div style={{ padding: 16 }}>
        <Paragraph
          type="secondary"
          ellipsis={{ rows: 3, expandable: true, symbol: '展开' }}
          style={{ marginBottom: 12, fontSize: 13, whiteSpace: 'pre-wrap' }}
        >
          {agent.responsibilities}
        </Paragraph>
        <div style={{ fontSize: 12, fontWeight: 600, color: 'var(--aw-muted)', marginBottom: 8 }}>
          SDLC 步骤 · {agent.sdlc.name}
        </div>
        <Steps
          direction="vertical"
          size="small"
          current={-1}
          items={agent.sdlc.steps.map((step) => ({
            title: <span style={{ fontSize: 13 }}>{step.name}</span>,
            description: <Tag color={kindColors[step.kind] || 'default'} style={{ fontSize: 11 }}>{step.kind}</Tag>,
          }))}
        />
      </div>
    </div>
  );
}
