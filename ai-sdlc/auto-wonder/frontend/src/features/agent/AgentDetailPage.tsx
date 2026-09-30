import { PageHeading } from '@/shared/ui/PageHeading';
import { PageBackButton } from '@/shared/ui/PageBackButton';
import { Alert, Card, Tag, Button, Space, Popconfirm, message, Spin, Result, Tabs, Typography } from 'antd';
import { Table } from '@/shared/theme/ThemedTable';
import { useState } from 'react';
import { RollbackOutlined, EditOutlined, PoweroffOutlined, PlayCircleOutlined, DeleteOutlined } from '@ant-design/icons';
import { useParams, useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { useAgent, useAgentVersions, useAgentVersion, useRollback, useOfflineAgent, useOnlineAgent, useDeleteAgent, useAgentWorkitems, useAgentMemories } from './hooks';
import { getVersion, type AgentVersion, type AgentVersionSummary, type RepoPermItem, type SkillItem, type AgentEnvironmentVariableRef } from './api';
import { getSdlcTemplate } from '@/features/sdlc/api';
import type { ColumnsType } from 'antd/es/table';
import { AgentStatCards } from './components/AgentStatCards';
import { AgentWorkitemList } from './components/AgentWorkitemList';
import { MarkdownView } from '@/shared/ui/MarkdownView';
import { EllipsisText } from '@/shared/ui/EllipsisText';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';

import { usePageSizePreference } from '@/shared/lib/usePageSizePreference';

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

const evolutionModeLabels: Record<string, string> = {
  MANUAL: '纯手动',
  ASSISTED: '辅助审核（推荐）',
  AUTO_PROPOSAL: '自动生成候选',
};

function evolutionModeLabel(mode?: string | null) {
  return evolutionModeLabels[mode ?? ''] ?? '未设置';
}

/** Mirrors the edit page: a version without an explicit mode is treated as ASSISTED. */
function versionEvolutionMode(version: AgentVersion) {
  if (version.evolutionMode) return version.evolutionMode;
  if (!version.identityJson) return 'ASSISTED';
  try {
    const parsed = JSON.parse(version.identityJson) as { evolutionMode?: string };
    return parsed.evolutionMode || 'ASSISTED';
  } catch {
    return 'ASSISTED';
  }
}

/** 优先显示模板名称（当前生效值可复用已查到的 name）；未知/未加载时退回 #id。 */
function sdlcLabel(sdlcId?: number | null, name?: string) {
  if (sdlcId == null) return '未绑定';
  return name || `#${sdlcId}`;
}

interface DraftDiffRow {
  key: string;
  field: string;
  current: string;
  draft: string;
}

const draftDiffColumns: ColumnsType<DraftDiffRow> = [
  { title: '字段', dataIndex: 'field', width: 120 },
  {
    title: '当前生效', dataIndex: 'current', ellipsis: { showTitle: false },
    render: (v: string) => <EllipsisText tooltip={v}>{v}</EllipsisText>,
  },
  {
    title: '草稿', dataIndex: 'draft', ellipsis: { showTitle: false },
    render: (v: string) => <EllipsisText tooltip={v}>{v}</EllipsisText>,
  },
];

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

function EmptyHint({ text }: { text: string }) {
  return <Text type="secondary">{text}</Text>;
}

const repoPermColumns: ColumnsType<RepoPermItem> = [
  { title: '仓库', dataIndex: 'repoName', render: (v: string, r) => v || `#${r.repoId}` },
  { title: '权限', dataIndex: 'permLevel', width: 100, render: (v: string) => <Tag>{v}</Tag> },
  {
    title: '提交分支', dataIndex: 'allowedBranchPatterns',
    render: (patterns?: string[]) => !patterns || patterns.length === 0
      ? <Text type="secondary">不限制</Text>
      : <Space size={[0, 4]} wrap>{patterns.map(pattern => <Tag key={pattern}>{pattern}</Tag>)}</Space>,
  },
];

const skillColumns: ColumnsType<SkillItem> = [
  { title: '能力', dataIndex: 'skillName', render: (v: string, r) => v || `#${r.skillId}` },
];

const environmentVariableColumns: ColumnsType<AgentEnvironmentVariableRef> = [
  { title: '变量名', dataIndex: 'name', width: '36%' },
  {
    title: '说明', dataIndex: 'description',
    render: (description: string | null) => description || <Text type="secondary">暂无说明</Text>,
  },
];

/** 仅绑定了 SDLC 时才发起单查，解析不出名称时退回 #id。 */
function useSdlcName(sdlcId?: number | null) {
  const { data } = useQuery({
    queryKey: ['sdlc', sdlcId],
    queryFn: () => getSdlcTemplate(sdlcId as number),
    enabled: sdlcId != null,
    staleTime: 5 * 60 * 1000,
  });
  return data?.name;
}

export function AgentDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const accessCommand = useAccessCommand();
  const agentId = Number(id);
  const { data: agent, isLoading, isError, error } = useAgent(agentId);
  const { data: versions = [] } = useAgentVersions(agentId);
  const { data: workitems = [] } = useAgentWorkitems(agentId);
  const { data: memories = [] } = useAgentMemories(agentId);
  const sdlcName = useSdlcName(agent?.sdlcId);
  const [versionPageNo, setVersionPageNo] = useState(1);
  const [versionPageSize, setVersionPageSize] = usePageSizePreference(
    'autowonder.agentDetail.versions.pageSize', [10, 20, 50], 10);
  // 当前生效版本的完整绑定（仓库/能力/环境变量）：编辑页同一数据源，详情页只读展示。
  const { data: onlineVersion, isLoading: versionDetailLoading } = useAgentVersion(
    agentId, agent?.latestVersionNo ?? 0,
  );
  const rollback = useRollback();
  const offline = useOfflineAgent();
  const online = useOnlineAgent();
  const remove = useDeleteAgent();
  const [actionFeedback, setActionFeedback] = useState<{ message: string; description: string } | null>(null);
  const [rollbackConfirmVersion, setRollbackConfirmVersion] = useState<number | null>(null);
  const [offlineConfirmOpen, setOfflineConfirmOpen] = useState(false);
  const [onlineConfirmOpen, setOnlineConfirmOpen] = useState(false);
  const [deleteConfirmOpen, setDeleteConfirmOpen] = useState(false);
  const [draftDiffOpen, setDraftDiffOpen] = useState(false);

  const draftVersionNo = agent?.hasDraft ? (agent.draftVersionNo ?? null) : null;
  // Fetched only when the comparison is opened, so the detail page stays on two requests otherwise.
  const { data: draftVersion } = useQuery({
    queryKey: ['agent', agentId, 'version', draftVersionNo ?? 0],
    queryFn: () => getVersion(agentId, draftVersionNo as number),
    enabled: draftDiffOpen && draftVersionNo != null && draftVersionNo > 0,
  });

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
    {
      title: '角色名称', dataIndex: 'roleName', ellipsis: { showTitle: false },
      render: (v: string) => <EllipsisText tooltip={v}>{v ?? '—'}</EllipsisText>,
    },
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
  const isPlatform = agent.kind === 'PLATFORM';

  const draftDiffRows: DraftDiffRow[] = draftVersion
    ? [
      { key: 'roleName', field: '角色名称', current: agent.roleName || '-', draft: draftVersion.roleName || '-' },
      { key: 'roleCode', field: '角色码', current: agent.roleCode || '-', draft: draftVersion.roleCode || '-' },
      { key: 'sdlcId', field: 'SDLC 模版', current: sdlcLabel(agent.sdlcId, sdlcName), draft: sdlcLabel(draftVersion.sdlcId) },
      {
        key: 'evolutionMode', field: '自进化模式',
        current: evolutionModeLabel(agent.evolutionMode),
        draft: evolutionModeLabel(versionEvolutionMode(draftVersion)),
      },
      { key: 'soul', field: 'SOUL.md', current: agent.businessBackground || '-', draft: draftVersion.businessBackground || '-' },
      { key: 'agentmd', field: 'AGENT.md', current: agent.responsibilities || '-', draft: draftVersion.responsibilities || '-' },
    ]
    : [];

  return (
    <div>
      <Card
        className="aw-content-card"
        title={<PageHeading title={<span className="aw-detail-title"><PageBackButton to="/agents" label="返回列表" /><span>{agent.name}</span><Tag color={statusInfo.color}>{statusInfo.label}</Tag>{isPlatform && <Tag color="gold">平台</Tag>}</span>}
          description={<>最新版本 {agent.latestVersionNo ? `v${agent.latestVersionNo}` : '-'} · 创建于 {new Date(agent.gmtCreate).toLocaleString('zh-CN')}</>} />}
        extra={
          <Space>
            {agent.status === 'ONLINE' && !isPlatform && (
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
            {agent.status !== 'ONLINE' && !isPlatform && (
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
      </Card>



      {actionFeedback && (
        <Alert
          showIcon
          type="success"
          message={actionFeedback.message}
          description={actionFeedback.description}
          style={{ marginBottom: 16 }}
        />
      )}

      {isPlatform && (
        <Alert
          showIcon
          type="info"
          message="平台智能体"
          description="出厂自带的 Chief of Staff：不可删除、不可下线，名称与头像锁定，不绑定 SDLC、不承接开发任务；AGENT.md/SOUL.md、技能与记忆可维护，变更仍需审核。无需配置平台智能体的仓库配置，其默认拥有平台所有的仓库的读取权限，进行平台智能的管理。"
          style={{ marginBottom: 16 }}
        />
      )}

      {agent.status === 'DRAFT' && (
        <Alert
          showIcon
          type="info"
          message="数字员工尚未提交审核"
          description="请先编辑配置，完成 SOUL.md 和 AGENT.md 后提交审核。"
          style={{ marginBottom: 16 }}
        />
      )}

      {draftVersionNo != null && agent.status !== 'DRAFT' && (
        <Alert
          showIcon
          type="warning"
          message={`存在未发布的草稿 v${draftVersionNo}`}
          description="下方展示的是当前生效的配置；草稿里的修改要提交审核并通过后才会生效。"
          style={{ marginBottom: 16 }}
          action={
            <Space direction="vertical" size={4}>
              <Button size="small" onClick={() => setDraftDiffOpen((open) => !open)}>
                {draftDiffOpen ? '收起差异' : '查看草稿差异'}
              </Button>
              <Button size="small" type="link"
                onClick={() => accessCommand('READ_WRITE', '编辑数字员工', () => navigate(`/agents/${agentId}/edit`))}>
                编辑草稿
              </Button>
            </Space>
          }
        />
      )}

      {draftDiffOpen && draftVersionNo != null && (
        <Card size="small" title={`当前生效 vs 草稿 v${draftVersionNo}`} style={{ marginBottom: 16 }}>
          <Table rowKey="key" size="small" columns={draftDiffColumns} dataSource={draftDiffRows}
            pagination={false} loading={!draftVersion} />
        </Card>
      )}

      <Card title="身份配置" style={{ marginBottom: 16 }}>
        <Space direction="vertical" size={16} style={{ width: '100%' }}>
          <Space size={8} wrap>
            {agent.roleName && <Tag color="blue">{agent.roleName}</Tag>}
            {agent.roleCode && <Tag>{agent.roleCode}</Tag>}
            <Tag color="geekblue">SDLC 模版：{sdlcLabel(agent.sdlcId, sdlcName)}</Tag>
            <Tag color="purple">自进化模式：{evolutionModeLabel(agent.evolutionMode)}</Tag>
            {agent.executorTotalCount != null && (
              <Tag color={agent.executorOnlineCount ? 'success' : 'default'}>
                执行器：{agent.executorOnlineCount ?? 0}/{agent.executorTotalCount} 在线
              </Tag>
            )}
          </Space>
          {!!agent.squadNames?.length && (
            <Space size={8} wrap>
              {agent.squadNames.map((name, i) => <Tag key={name + i} color="cyan">{name}</Tag>)}
            </Space>
          )}
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
              children: <AgentWorkitemList agentId={agentId} />,
            },
            {
              key: 'repos',
              label: `仓库权限${onlineVersion?.repoPerms?.length ? ` (${onlineVersion.repoPerms.length})` : ''}`,
              children: isPlatform ? (
                <Alert
                  type="info" showIcon
                  message="无需配置平台智能体的仓库配置"
                  description="其默认拥有平台所有的仓库的读取权限，进行平台智能的管理。"
                />
              ) : (
                <Table rowKey="repoId" size="small" columns={repoPermColumns}
                  dataSource={onlineVersion?.repoPerms ?? []} pagination={false}
                  loading={versionDetailLoading}
                  locale={{ emptyText: <EmptyHint text="暂未绑定仓库" /> }} />
              ),
            },
            {
              key: 'skills',
              label: `能力配置${onlineVersion?.skills?.length ? ` (${onlineVersion.skills.length})` : ''}`,
              children: (
                <Table rowKey="skillId" size="small" columns={skillColumns}
                  dataSource={onlineVersion?.skills ?? []} pagination={false}
                  loading={versionDetailLoading}
                  locale={{ emptyText: <EmptyHint text="暂未绑定能力" /> }} />
              ),
            },
            {
              key: 'envvars',
              label: `环境变量${agent.environmentVariables?.length ? ` (${agent.environmentVariables.length})` : ''}`,
              children: (
                <Table rowKey="id" size="small" columns={environmentVariableColumns}
                  dataSource={agent.environmentVariables ?? []} pagination={false}
                  locale={{ emptyText: <EmptyHint text="暂无已挂载的环境变量" /> }} />
              ),
            },
            {
              key: 'versions',
              label: '版本记录',
              children: (
                <Table rowKey="id" columns={versionColumns} dataSource={versions}
                  pagination={{
                    current: versionPageNo,
                    pageSize: versionPageSize,
                    total: versions.length,
                    onChange: (nextPage, nextSize) => {
                      setVersionPageNo(nextPage);
                      if (nextSize !== versionPageSize) setVersionPageSize(nextSize);
                    },
                    showSizeChanger: true,
                    showTotal: (t) => `共 ${t} 条`,
                  }} />
              ),
            },
          ]}
        />
      </Card>
    </div>
  );
}
