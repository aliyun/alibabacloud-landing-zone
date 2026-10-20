import { useTranslation } from 'react-i18next';
import { i18n } from '@/shared/i18n';
import { formatDateTime } from '@/shared/i18n/format';
import { getAgentRoleLabel, getEvolutionModeLabel, getAgentStatusLabel } from './constants';
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

const versionStatusColors = new Map<string, string>([
  ['DRAFT', 'default'], ['PENDING_REVIEW', 'processing'], ['APPROVED', 'success'],
  ['REJECTED', 'error'], ['ONLINE', 'success'], ['ROLLED_BACK', 'warning'],
]);
const agentStatusColors = new Map<string, string>([
  ['DRAFT', 'default'], ['ONLINE', 'success'], ['OFFLINE', 'default'], ['PENDING_REVIEW', 'processing'],
]);

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
  if (sdlcId == null) return i18n.t('display.notBound', { ns: 'agent' });
  return name || `#${sdlcId}`;
}

function countSuffix(count?: number) {
  return count ? ` (${count})` : '';
}

interface DraftDiffRow {
  key: string;
  field: string;
  current: string;
  draft: string;
}

function AgentIdentitySection({ title, content }: { title: string; content?: string | null }) {
  const { t } = useTranslation('agent');
  const value = content?.trim();
  return (
    <div style={{ flex: 1, minWidth: 280 }}>
      <Text strong>{title}</Text>
      <div style={{ marginTop: 8 }}>
        {value ? <MarkdownView content={value} /> : <Text type="secondary">{t('display.notConfigured')}</Text>}
      </div>
    </div>
  );
}

function EmptyHint({ text }: { text: string }) {
  return <Text type="secondary">{text}</Text>;
}

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
  const { t } = useTranslation('agent');
  const permissionLabels = new Map<string, string>([['READ', t('permissions.READ')], ['WRITE', t('permissions.WRITE')], ['ADMIN', t('permissions.ADMIN')]]);
  const draftDiffColumns: ColumnsType<DraftDiffRow> = [
    { title: t('display.field'), dataIndex: 'field', width: 120 },
    {
      title: t('display.effective'), dataIndex: 'current', ellipsis: { showTitle: false },
      render: (v: string) => <EllipsisText tooltip={v}>{v}</EllipsisText>,
    },
    {
      title: t('display.status.DRAFT'), dataIndex: 'draft', ellipsis: { showTitle: false },
      render: (v: string) => <EllipsisText tooltip={v}>{v}</EllipsisText>,
    },
  ];

  const repoPermColumns: ColumnsType<RepoPermItem> = [
    { title: t('fields.repository'), dataIndex: 'repoName', render: (v: string, r) => v || `#${r.repoId}` },
    { title: t('fields.permission'), dataIndex: 'permLevel', width: 100, render: (v: string) => <Tag>{permissionLabels.get(v) || v}</Tag> },
    {
      title: t('fields.commitBranches'), dataIndex: 'allowedBranchPatterns',
      render: (patterns?: string[]) => !patterns || patterns.length === 0
        ? <Text type="secondary">{t('repository.unrestricted')}</Text>
        : <Space size={[0, 4]} wrap>{patterns.map(pattern => <Tag key={pattern}>{pattern}</Tag>)}</Space>,
    },
  ];

  const skillColumns: ColumnsType<SkillItem> = [
    { title: t('fields.capability'), dataIndex: 'skillName', render: (v: string, r) => v || `#${r.skillId}` },
  ];

  const environmentVariableColumns: ColumnsType<AgentEnvironmentVariableRef> = [
    { title: t('fields.variableName'), dataIndex: 'name', width: '36%' },
    {
      title: t('fields.description'), dataIndex: 'description',
      render: (description: string | null) => description || <Text type="secondary">{t('environment.noDescription')}</Text>,
    },
  ];

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
  const [actionFeedback, setActionFeedback] = useState<number | null>(null);
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
    <Result status="404" title={t('errors.invalidId')} extra={<Button onClick={() => navigate(-1)}>{t('actions.back')}</Button>} />
  );
  if (isLoading) return <Spin size="large" style={{ display: 'block', margin: '100px auto' }} />;
  if (isError) return (
    <Result status="error" title={t('errors.load')} subTitle={(error as Error)?.message || t('display.retryLater')}
      extra={<Button onClick={() => navigate(-1)}>{t('actions.back')}</Button>} />
  );
  if (!agent) return null;

  const handleRollback = (versionNo: number) => {
    accessCommand('READ_WRITE', t('display.rollbackAction'), () => {
      rollback.mutate({ agentId, versionNo }, {
        onError: (error) => message.error(error instanceof Error && error.message || i18n.t('display.rollbackError', { ns: 'agent' })),
        onSuccess: () => {
          message.success(i18n.t('display.rolledBack', { ns: 'agent' }));
          setActionFeedback(versionNo);
        },
      });
    });
  };
  const handleOffline = () => {
    accessCommand('READ_WRITE', t('display.offlineAction'), () => {
      offline.mutate(agentId, { onError: (error) => message.error(error instanceof Error && error.message || i18n.t('display.offlineError', { ns: 'agent' })), onSuccess: () => message.success(i18n.t('display.offlineSuccess', { ns: 'agent' })) });
    });
  };
  const handleOnline = () => {
    accessCommand('READ_WRITE', t('display.onlineAction'), () => {
      online.mutate(agentId, { onError: (error) => message.error(error instanceof Error && error.message || i18n.t('display.onlineError', { ns: 'agent' })), onSuccess: () => message.success(i18n.t('display.onlineSuccess', { ns: 'agent' })) });
    });
  };
  const handleDelete = () => {
    accessCommand('READ_WRITE', t('display.deleteAction'), () => {
      remove.mutate(agentId, {
        onError: (error) => message.error(error instanceof Error && error.message || i18n.t('display.deleteError', { ns: 'agent' })),
        onSuccess: () => {
          message.success(i18n.t('display.deleted', { ns: 'agent' }));
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
    { title: t('display.versionNo'), dataIndex: 'versionNo', width: 80, render: (v: number) => `v${v}` },
    {
      title: t('fields.roleName'), dataIndex: 'roleName', ellipsis: { showTitle: false },
      render: (_: string, row) => {
        const label = getAgentRoleLabel(row.roleCode, row.roleName) || '—';
        return <EllipsisText tooltip={label}>{label}</EllipsisText>;
      },
    },
    { title: t('display.statusTitle'), dataIndex: 'status', width: 100, render: (s: string) => <Tag color={versionStatusColors.get(s)}>{getAgentStatusLabel(s)}</Tag> },
    { title: t('display.createdAt'), dataIndex: 'gmtCreate', width: 160, render: (t: string) => formatDateTime(t) },
    {
      title: t('fields.actions'), width: 100,
      render: (_: unknown, record: AgentVersionSummary) =>
        record.status === 'ONLINE' || record.status === 'APPROVED' ? (
          <Popconfirm
            title={t('display.confirmRollback')}
            okText={t('display.rollbackButton')}
            cancelText={t('actions.cancel')}
            open={rollbackConfirmVersion === record.versionNo}
            onOpenChange={(open) => handleConfirmOpen(
              open,
              t('display.rollbackAction'),
              (next) => setRollbackConfirmVersion(next ? record.versionNo : null),
            )}
            onConfirm={() => {
              setRollbackConfirmVersion(null);
              handleRollback(record.versionNo);
            }}
          >
            <Button type="link" size="small" icon={<RollbackOutlined aria-hidden />}>{t('display.rollback')}</Button>
          </Popconfirm>
        ) : null,
    },
  ];

  const statusInfo = { color: agentStatusColors.get(agent.status) || 'default', label: getAgentStatusLabel(agent.status) };
  const isPlatform = agent.kind === 'PLATFORM';

  const draftDiffRows: DraftDiffRow[] = draftVersion
    ? [
      { key: 'roleName', field: t('fields.roleName'), current: getAgentRoleLabel(agent.roleCode, agent.roleName) || '-', draft: getAgentRoleLabel(draftVersion.roleCode, draftVersion.roleName) || '-' },
      { key: 'roleCode', field: t('fields.roleCode'), current: agent.roleCode || '-', draft: draftVersion.roleCode || '-' },
      { key: 'sdlcId', field: t('fields.sdlc'), current: sdlcLabel(agent.sdlcId, sdlcName), draft: sdlcLabel(draftVersion.sdlcId) },
      {
        key: 'evolutionMode', field: t('fields.evolution'),
        current: getEvolutionModeLabel(agent.evolutionMode),
        draft: getEvolutionModeLabel(versionEvolutionMode(draftVersion)),
      },
      { key: 'soul', field: 'SOUL.md', current: agent.businessBackground || '-', draft: draftVersion.businessBackground || '-' },
      { key: 'agentmd', field: 'AGENT.md', current: agent.responsibilities || '-', draft: draftVersion.responsibilities || '-' },
    ]
    : [];

  return (
    <div>
      <Card
        className="aw-content-card"
        title={<PageHeading title={<span className="aw-detail-title"><PageBackButton to="/agents" label={t('actions.backList')} /><span>{agent.name}</span><Tag color={statusInfo.color}>{statusInfo.label}</Tag>{isPlatform && <Tag color="gold">{t('display.platform')}</Tag>}</span>}
          description={t('display.versionDescription', { version: agent.latestVersionNo ? `v${agent.latestVersionNo}` : '-', date: formatDateTime(agent.gmtCreate) })} />}
        extra={
          <Space>
            {agent.status === 'ONLINE' && !isPlatform && (
              <Popconfirm
                title={t('display.confirmOffline')}
                okText={t('display.offline')}
                cancelText={t('actions.cancel')}
                open={offlineConfirmOpen}
                onOpenChange={(open) => handleConfirmOpen(open, t('display.offlineAction'), setOfflineConfirmOpen)}
                onConfirm={() => {
                  setOfflineConfirmOpen(false);
                  handleOffline();
                }}
              >
                <Button icon={<PoweroffOutlined aria-hidden />}>{t('display.offline')}</Button>
              </Popconfirm>
            )}
            {agent.status === 'OFFLINE' && (
              <Popconfirm
                title={t('display.confirmOnline')}
                okText={t('display.onlineButton')}
                cancelText={t('actions.cancel')}
                open={onlineConfirmOpen}
                onOpenChange={(open) => handleConfirmOpen(open, t('display.onlineAction'), setOnlineConfirmOpen)}
                onConfirm={() => {
                  setOnlineConfirmOpen(false);
                  handleOnline();
                }}
              >
                <Button icon={<PlayCircleOutlined aria-hidden />}>{t('display.online')}</Button>
              </Popconfirm>
            )}
            {agent.status !== 'ONLINE' && !isPlatform && (
              <Popconfirm
                title={t('display.confirmDelete')}
                okText={t('display.deleteButton')}
                cancelText={t('actions.cancel')}
                open={deleteConfirmOpen}
                onOpenChange={(open) => handleConfirmOpen(open, t('display.deleteAction'), setDeleteConfirmOpen)}
                onConfirm={() => {
                  setDeleteConfirmOpen(false);
                  handleDelete();
                }}
              >
                <Button danger icon={<DeleteOutlined aria-hidden />}>{t('display.delete')}</Button>
              </Popconfirm>
            )}
            <Button type="primary" icon={<EditOutlined aria-hidden />}
              onClick={() => accessCommand('READ_WRITE', t('display.editAction'), () => navigate(`/agents/${agentId}/edit`))}>
              {t('display.editConfig')}
            </Button>
          </Space>
        }
      >
      </Card>


      {actionFeedback != null && (
        <Alert
          showIcon
          type="success"
          message={t('display.rolledBackVersion', { version: actionFeedback })}
          description={t('display.rollbackHint')}
          style={{ marginBottom: 16 }}
        />
      )}

      {isPlatform && (
        <Alert
          showIcon
          type="info"
          message={t('display.platformAgent')}
          description={t('display.platformHint')}
          style={{ marginBottom: 16 }}
        />
      )}

      {agent.status === 'DRAFT' && (
        <Alert
          showIcon
          type="info"
          message={t('display.draftAgent')}
          description={t('display.draftAgentHint')}
          style={{ marginBottom: 16 }}
        />
      )}

      {draftVersionNo != null && agent.status !== 'DRAFT' && (
        <Alert
          showIcon
          type="warning"
          message={t('display.unpublishedDraft', { version: draftVersionNo })}
          description={t('display.unpublishedHint')}
          style={{ marginBottom: 16 }}
          action={
            <Space direction="vertical" size={4}>
              <Button size="small" onClick={() => setDraftDiffOpen((open) => !open)}>
                {draftDiffOpen ? t('display.hideDiff') : t('display.showDiff')}
              </Button>
              <Button size="small" type="link"
                onClick={() => accessCommand('READ_WRITE', t('display.editAction'), () => navigate(`/agents/${agentId}/edit`))}>
                {t('display.editDraft')}
              </Button>
            </Space>
          }
        />
      )}

      {draftDiffOpen && draftVersionNo != null && (
        <Card size="small" title={t('display.diffTitle', { version: draftVersionNo })} style={{ marginBottom: 16 }}>
          <Table rowKey="key" size="small" columns={draftDiffColumns} dataSource={draftDiffRows}
            pagination={false} loading={!draftVersion} />
        </Card>
      )}

      <Card title={t('display.identity')} style={{ marginBottom: 16 }}>
        <Space direction="vertical" size={16} style={{ width: '100%' }}>
          <Space size={8} wrap>
            {getAgentRoleLabel(agent.roleCode, agent.roleName) && <Tag color="blue">{getAgentRoleLabel(agent.roleCode, agent.roleName)}</Tag>}
            {agent.roleCode && <Tag>{agent.roleCode}</Tag>}
            <Tag color="geekblue">{t('display.sdlcTag', { name: sdlcLabel(agent.sdlcId, sdlcName) })}</Tag>
            <Tag color="purple">{t('display.evolutionTag', { mode: getEvolutionModeLabel(agent.evolutionMode) })}</Tag>
            {agent.executorTotalCount != null && (
              <Tag color={agent.executorOnlineCount ? 'success' : 'default'}>
                {t('display.executorRatio', { online: agent.executorOnlineCount ?? 0, total: agent.executorTotalCount })}
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
              label: t('display.workitems'),
              children: <AgentWorkitemList agentId={agentId} />,
            },
            {
              key: 'repos',
              label: t('display.repositoryTab', { suffix: countSuffix(onlineVersion?.repoPerms?.length) }),
              children: isPlatform ? (
                <Alert
                  type="info" showIcon
                  message={t('repository.platformTitle')}
                  description={t('repository.platformHint')}
                />
              ) : (
                <Table rowKey="repoId" size="small" columns={repoPermColumns}
                  dataSource={onlineVersion?.repoPerms ?? []} pagination={false}
                  loading={versionDetailLoading}
                  locale={{ emptyText: <EmptyHint text={t('display.emptyRepos')} /> }} />
              ),
            },
            {
              key: 'skills',
              label: t('display.capabilityTab', { suffix: countSuffix(onlineVersion?.skills?.length) }),
              children: (
                <Table rowKey="skillId" size="small" columns={skillColumns}
                  dataSource={onlineVersion?.skills ?? []} pagination={false}
                  loading={versionDetailLoading}
                  locale={{ emptyText: <EmptyHint text={t('display.emptySkills')} /> }} />
              ),
            },
            {
              key: 'envvars',
              label: t('display.environmentTab', { suffix: countSuffix(agent.environmentVariables?.length) }),
              children: (
                <Table rowKey="id" size="small" columns={environmentVariableColumns}
                  dataSource={agent.environmentVariables ?? []} pagination={false}
                  locale={{ emptyText: <EmptyHint text={t('environment.empty')} /> }} />
              ),
            },
            {
              key: 'versions',
              label: t('display.versions'),
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
                    showTotal: (count) => t('display.total', { count }),
                  }} />
              ),
            },
          ]}
        />
      </Card>
    </div>
  );
}
