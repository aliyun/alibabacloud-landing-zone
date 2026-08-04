import { Alert, Card, Tag, Button, Space, Table, Popconfirm, message, Spin, Result, Tabs, Typography } from 'antd';
import { useState } from 'react';
import { ArrowLeftOutlined, RollbackOutlined, EditOutlined, PoweroffOutlined, PlayCircleOutlined, DeleteOutlined } from '@ant-design/icons';
import { useParams, useNavigate } from 'react-router-dom';
import { useAgent, useAgentVersions, useRollback, useOfflineAgent, useOnlineAgent, useDeleteAgent, useAgentWorkitems, useAgentMemories } from './hooks';
import type { AgentVersionSummary } from './api';
import type { ColumnsType } from 'antd/es/table';
import { AgentStatCards } from './components/AgentStatCards';
import { AgentWorkitemList } from './components/AgentWorkitemList';
import { MarkdownView } from '@/shared/ui/MarkdownView';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';

const { Text } = Typography;

const versionStatusMap: Record<string, { color: string; label: string }> = {
  DRAFT: { color: 'default', label: '草稿' },
  PENDING_REVIEW: { color: 'processing', label: '待审核' },
  APPROVED: { color: 'success', label: '已通过' },
  REJECTED: { color: 'error', label: '已驳回' },
  ONLINE: { color: 'success', label: '在线' },
  ROLLED_BACK: { color: 'warning', label: '已回退' },
};

const agentStatusMap: Record<string, { color: string; label: string }> = {
  DRAFT: { color: 'default', label: '草稿' },
  ONLINE: { color: 'success', label: '在线' },
  OFFLINE: { color: 'default', label: '离线' },
  PENDING_REVIEW: { color: 'processing', label: '待审核' },
};

function AgentIdentitySection({ title, content }: { title: string; content?: string | null }) {
  const value = content?.trim();
  return (
    <div style={{ flex: 1, minWidth: 280 }}>
      <Text strong>{title}</Text>
      <div style={{ marginTop: 8 }}>
        {value ? <MarkdownView content={value} /> : <Text type="secondary">暂未配置</Text>}
      </div>
    </div>
  );
}

export function AgentDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const accessCommand = useAccessCommand();
  const agentId = Number(id);
  const { data: agent, isLoading, isError, error } = useAgent(agentId);
  const { data: versions = [] } = useAgentVersions(agentId);
  const { data: workitems = [], isLoading: wiLoading } = useAgentWorkitems(agentId);
  const { data: memories = [] } = useAgentMemories(agentId);
  const rollback = useRollback();
  const offline = useOfflineAgent();
  const online = useOnlineAgent();
  const remove = useDeleteAgent();
  const [actionFeedback, setActionFeedback] = useState<{ message: string; description: string } | null>(null);
  const [rollbackConfirmVersion, setRollbackConfirmVersion] = useState<number | null>(null);
  const [offlineConfirmOpen, setOfflineConfirmOpen] = useState(false);
  const [onlineConfirmOpen, setOnlineConfirmOpen] = useState(false);
  const [deleteConfirmOpen, setDeleteConfirmOpen] = useState(false);

  if (!agentId || isNaN(agentId)) return (
    <Result status="404" title="无效的 ID" extra={<Button onClick={() => navigate(-1)}>返回</Button>} />
  );
  if (isLoading) return <Spin size="large" style={{ display: 'block', margin: '100px auto' }} />;
  if (isError) return (
    <Result status="error" title="加载失败" subTitle={(error as Error)?.message || '请稍后重试'}
      extra={<Button onClick={() => navigate(-1)}>返回</Button>} />
  );
  if (!agent) return null;

  const handleRollback = (versionNo: number) => {
    accessCommand('READ_WRITE', '回退数字员工版本', () => {
      rollback.mutate({ agentId, versionNo }, {
        onSuccess: () => {
          message.success('已回退');
          setActionFeedback({
            message: `已回退到 v${versionNo}`,
            description: '当前草稿可继续编辑或重新提交审核。',
          });
        },
      });
    });
  };
  const handleOffline = () => {
    accessCommand('READ_WRITE', '下线数字员工', () => {
      offline.mutate(agentId, { onSuccess: () => message.success('已下线') });
    });
  };
  const handleOnline = () => {
    accessCommand('READ_WRITE', '上线数字员工', () => {
      online.mutate(agentId, { onSuccess: () => message.success('已上线') });
    });
  };
  const handleDelete = () => {
    accessCommand('READ_WRITE', '删除数字员工', () => {
      remove.mutate(agentId, {
        onSuccess: () => {
          message.success('已删除');
          navigate('/agents');
        },
      });
    });
  };
  const handleConfirmOpen = (
    open: boolean,
    action: string,
    setOpen: (next: boolean) => void,
  ) => {
    if (!open) {
      setOpen(false);
      return;
    }
    accessCommand('READ_WRITE', action, () => setOpen(true));
  };

  const versionColumns: ColumnsType<AgentVersionSummary> = [
    { title: '版本号', dataIndex: 'versionNo', width: 80, render: (v: number) => `v${v}` },
    { title: '角色名称', dataIndex: 'roleName', ellipsis: true },
    { title: '状态', dataIndex: 'status', width: 100, render: (s: string) => <Tag color={versionStatusMap[s]?.color}>{versionStatusMap[s]?.label || s}</Tag> },
    { title: '创建时间', dataIndex: 'gmtCreate', width: 160, render: (t: string) => new Date(t).toLocaleString('zh-CN') },
    {
      title: '操作', width: 100,
      render: (_: unknown, record: AgentVersionSummary) =>
        record.status === 'ONLINE' || record.status === 'APPROVED' ? (
          <Popconfirm
            title="确定回退到此版本？"
            okText="确定回退"
            cancelText="取消"
            open={rollbackConfirmVersion === record.versionNo}
            onOpenChange={(open) => handleConfirmOpen(
              open,
              '回退数字员工版本',
              (next) => setRollbackConfirmVersion(next ? record.versionNo : null),
            )}
            onConfirm={() => {
              setRollbackConfirmVersion(null);
              handleRollback(record.versionNo);
            }}
          >
            <Button type="link" size="small" icon={<RollbackOutlined />}>回退</Button>
          </Popconfirm>
        ) : null,
    },
  ];

  const statusInfo = agentStatusMap[agent.status] || { color: 'default', label: agent.status };

  return (
    <div>
      <Space style={{ marginBottom: 16 }}>
        <Button type="link" icon={<ArrowLeftOutlined />} onClick={() => navigate('/agents')} style={{ padding: 0 }}>
          返回列表
        </Button>
      </Space>

      {actionFeedback && (
        <Alert
          showIcon
          type="success"
          message={actionFeedback.message}
          description={actionFeedback.description}
          style={{ marginBottom: 16 }}
        />
      )}

      <Card
        style={{ marginBottom: 16, borderTop: '3px solid #f97316' }}
        extra={
          <Space>
            {agent.status === 'ONLINE' && (
              <Popconfirm
                title="确定下线该数字员工？"
                open={offlineConfirmOpen}
                onOpenChange={(open) => handleConfirmOpen(open, '下线数字员工', setOfflineConfirmOpen)}
                onConfirm={() => {
                  setOfflineConfirmOpen(false);
                  handleOffline();
                }}
              >
                <Button icon={<PoweroffOutlined />}>下线</Button>
              </Popconfirm>
            )}
            {agent.status === 'OFFLINE' && (
              <Popconfirm
                title="确定重新上线该数字员工？"
                okText="确定上线"
                cancelText="取消"
                open={onlineConfirmOpen}
                onOpenChange={(open) => handleConfirmOpen(open, '上线数字员工', setOnlineConfirmOpen)}
                onConfirm={() => {
                  setOnlineConfirmOpen(false);
                  handleOnline();
                }}
              >
                <Button icon={<PlayCircleOutlined />}>上线</Button>
              </Popconfirm>
            )}
            {agent.status !== 'ONLINE' && (
              <Popconfirm
                title="确定删除该数字员工？删除后不可恢复。"
                okText="确定删除"
                cancelText="取消"
                open={deleteConfirmOpen}
                onOpenChange={(open) => handleConfirmOpen(open, '删除数字员工', setDeleteConfirmOpen)}
                onConfirm={() => {
                  setDeleteConfirmOpen(false);
                  handleDelete();
                }}
              >
                <Button danger icon={<DeleteOutlined />}>删除</Button>
              </Popconfirm>
            )}
            <Button type="primary" icon={<EditOutlined />}
              onClick={() => accessCommand('READ_WRITE', '编辑数字员工', () => navigate(`/agents/${agentId}/edit`))}>
              编辑配置
            </Button>
          </Space>
        }
      >
        <Space direction="vertical" size={4}>
          <Space size={8}>
            <Text strong style={{ fontSize: 18 }}>{agent.name}</Text>
            <Tag color={statusInfo.color}>{statusInfo.label}</Tag>
          </Space>
          <Text type="secondary">
            最新版本 {agent.latestVersionNo ? `v${agent.latestVersionNo}` : '-'}
            {'  ·  '}创建于 {new Date(agent.gmtCreate).toLocaleString('zh-CN')}
          </Text>
        </Space>
      </Card>

      <Card title="身份配置" style={{ marginBottom: 16 }}>
        <Space direction="vertical" size={16} style={{ width: '100%' }}>
          <Space size={8} wrap>
            {agent.roleName && <Tag color="blue">{agent.roleName}</Tag>}
            {agent.roleCode && <Tag>{agent.roleCode}</Tag>}
          </Space>
          <div style={{ display: 'flex', gap: 24, flexWrap: 'wrap' }}>
            <AgentIdentitySection title="SOUL.md" content={agent.businessBackground} />
            <AgentIdentitySection title="AGENT.md" content={agent.responsibilities} />
          </div>
        </Space>
      </Card>

      <AgentStatCards workitems={workitems} memoryCount={memories.length} />

      <Card>
        <Tabs
          items={[
            {
              key: 'workitems',
              label: '任务列表',
              children: <AgentWorkitemList workitems={workitems} loading={wiLoading} />,
            },
            {
              key: 'versions',
              label: '版本记录',
              children: <Table rowKey="id" columns={versionColumns} dataSource={versions} pagination={false} />,
            },
          ]}
        />
      </Card>
    </div>
  );
}
