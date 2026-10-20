import { useTranslation } from 'react-i18next';
import { i18n } from '@/shared/i18n';
import { formatDateTime } from '@/shared/i18n/format';
import { getAgentRoleLabel, getAgentStatusLabel } from './constants';
import { useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import { Button, Empty, Segmented, Select, Spin, Tag, Tooltip } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { ApiOutlined, DatabaseOutlined, InfoCircleOutlined, PlusOutlined, SafetyCertificateOutlined } from '@ant-design/icons';
import { Link, useNavigate } from 'react-router-dom';
import { Table } from '@/shared/theme/ThemedTable';
import { EllipsisText } from '@/shared/ui/EllipsisText';
import { useAgentList } from './hooks';
import type { Agent, AgentKind } from './api';
import { SquadFilterBar, useSquadOptions } from '@/features/squad/SquadFilterBar';
import { SquadTags } from '@/features/squad/SquadTags';
import { groupBySquad } from '@/features/squad/squadGrouping';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';
import { readViewPreference, writeViewPreference } from '@/shared/lib/viewPreference';
import './AgentListPage.css';

const AGENTS_VIEW_STORAGE_KEY = 'autowonder.agents.view';
const AGENTS_VIEW_OPTIONS = ['list', 'grouped'] as const;

const statusMap: Record<string, { color: string; className: string }> = {
  DRAFT: { color: 'default', className: 'is-muted' },
  ONLINE: { color: 'success', className: 'is-active' },
  OFFLINE: { color: 'default', className: 'is-muted' },
  PENDING_REVIEW: { color: 'processing', className: 'is-review' },
};

export function AgentListPage() {
  const { t } = useTranslation('agent');
  const agentColumns = getAgentColumns();
  const navigate = useNavigate();
  const accessCommand = useAccessCommand();
  const [kindTab, setKindTab] = useState<AgentKind>('STANDARD');
  const [squadFilter, setSquadFilter] = useState<number[]>([]);
  const [statusFilter, setStatusFilter] = useState<string | undefined>(undefined);
  const [grouped, setGrouped] = useState(
    () => readViewPreference(AGENTS_VIEW_STORAGE_KEY, AGENTS_VIEW_OPTIONS, 'grouped') === 'grouped',
  );
  const { options: squadOptions, nameById: squadNameById, isLoading: squadsLoading } = useSquadOptions();
  const { data: agents = [], isLoading } = useAgentList(statusFilter, kindTab, squadFilter);

  const activeCount = agents.filter((agent) => agent.status === 'ONLINE').length;
  const executorOnlineCount = agents.reduce((sum, agent) => sum + (agent.executorOnlineCount ?? 0), 0);
  const reviewCount = agents.filter((agent) => agent.status === 'PENDING_REVIEW').length;

  const squadGroups = useMemo(
    () => groupBySquad(agents, (agent) => agent.squadIds, squadNameById),
    [agents, squadNameById, t],
  );

  return (
    <section className="agent-card-page">
      <div className="agent-list-toolbar">
        <Segmented
          value={kindTab}
          onChange={(key) => setKindTab(key as AgentKind)}
          options={[
            { label: t('display.standardAgents'), value: 'STANDARD' },
            { label: t('display.platformAgents'), value: 'PLATFORM' },
          ]}
        />
        <SquadFilterBar
          options={squadOptions}
          value={squadFilter}
          onChange={setSquadFilter}
          grouped={grouped}
          onGroupedChange={(next) => {
            setGrouped(next);
            writeViewPreference(AGENTS_VIEW_STORAGE_KEY, next ? 'grouped' : 'list');
          }}
          loading={squadsLoading}
        />
        {!grouped && (
          <Select
            allowClear
            placeholder={t('display.filterStatus')}
            style={{ minWidth: 140 }}
            value={statusFilter}
            onChange={setStatusFilter}
            options={Object.keys(statusMap).map((value) => ({ value, label: statusMeta({ status: value }).label }))}
            aria-label={t('display.filterStatus')}
          />
        )}
        {kindTab === 'STANDARD' && (
          <Button type="primary" icon={<PlusOutlined aria-hidden />}
            onClick={() => accessCommand('READ_WRITE', t('display.createAction'), () => navigate('/agents/new'))}>{t('display.create')}</Button>
        )}
      </div>

      <div className="agent-summary-strip">
        <SummaryPill label={kindTab === 'PLATFORM' ? t('display.platformAgent') : t('display.allAgents')} value={agents.length} />
        <SummaryPill label={t('display.inUse')} value={activeCount} />
        <SummaryPill label={t('display.executorOnline')} value={executorOnlineCount} />
        <SummaryPill label={t('display.status.PENDING_REVIEW')} value={reviewCount} />
        <Tooltip title={<><div>{t('display.configHelpOpen')}</div><div>{t('display.configHelpContent')}</div></>}>
          <Button type="text" size="small" aria-label={t('display.configHelp')} icon={<InfoCircleOutlined aria-hidden />} className="agent-config-help">{t('display.configHelp')}</Button>
        </Tooltip>
      </div>

      <Spin spinning={isLoading}>
        {agents.length === 0 && !isLoading ? (
          kindTab === 'PLATFORM' ? (
            <Empty description={t('display.emptyPlatform')} />
          ) : (
            <Empty description={t('display.emptyAgents')} />
          )
        ) : grouped ? (
          <div className="agent-squad-groups">
            {squadGroups.map((group) => (
              <section key={group.key} className="agent-squad-group">
                <header className="agent-squad-group-header">
                  {group.squadId == null ? (
                    <span>{group.label}</span>
                  ) : (
                    <Link to={`/agents?tab=squads&squadId=${group.squadId}`}>{group.label}</Link>
                  )}
                  <span className="agent-squad-group-count">{t('display.groupCount', { count: group.items.length })}</span>
                </header>
                <div className="agent-card-grid">
                  {group.items.map((agent, index) => (
                    <AgentCard key={agent.id} agent={agent} index={index} />
                  ))}
                </div>
              </section>
            ))}
          </div>
        ) : (
          <Table<Agent>
            className="agent-list-table"
            rowKey="id"
            columns={agentColumns}
            dataSource={agents}
            pagination={false}
            locale={{ emptyText: kindTab === 'PLATFORM' ? t('display.emptyPlatform') : t('display.emptyAgents') }}
            tableLayout="fixed"
            scroll={{ x: 900 }}
          />
        )}
      </Spin>
    </section>
  );
}

function getAgentColumns(): ColumnsType<Agent> {
  const t = i18n.getFixedT(null, 'agent');
  return [
    { title: 'ID', dataIndex: 'id', width: 84 },
    {
      title: t('fields.name'),
      dataIndex: 'name',
      align: 'left',
      width: 220,
      render: (_, agent) => (
        <EllipsisText tooltip={agent.name}>
          <Link to={`/agents/${agent.id}`}>{agent.name}</Link>
        </EllipsisText>
      ),
    },
    {
      title: t('display.role'),
      dataIndex: 'roleName',
      align: 'left',
      width: 200,
      render: (_, agent) => (
        <EllipsisText tooltip={getAgentRoleLabel(agent.roleCode, agent.roleName)}>
          {getAgentRoleLabel(agent.roleCode, agent.roleName) || '-'}
        </EllipsisText>
      ),
    },
    {
      title: t('display.statusTitle'),
      render: (_, agent) => (
        <Tag className={`agent-status-tag ${statusMeta(agent).className}`} color={statusMeta(agent).color}>
          {statusMeta(agent).label}
        </Tag>
      ),
    },
    {
      title: t('display.executor'),
      render: (_, agent) => executorText(agent, true),
    },
    {
      title: t('display.squads'),
      align: 'left',
      render: (_, agent) => <SquadTags squadIds={agent.squadIds} squadNames={agent.squadNames} />,
    },
    { title: t('display.skills'), width: 72, render: (_, agent) => agent.skillCount ?? 0 },
    { title: t('display.memories'), width: 72, render: (_, agent) => agent.memoryCount ?? 0 },
    { title: t('fields.repository'), width: 72, render: (_, agent) => agent.repoPermCount ?? 0 },
    { title: t('fields.version'), width: 76, render: (_, agent) => versionText(agent) },
    {
      title: t('fields.actions'),
      width: 76,
      render: (_, agent) => <Link to={`/agents/${agent.id}`}>{t('display.details')}</Link>,
    },
  ];
}

function AgentCard({ agent, index }: { agent: Agent; index: number }) {
  const { t } = useTranslation('agent');
  return (
    <article className="agent-person-card">
      <Link
        className="agent-card-detail-link"
        to={`/agents/${agent.id}`}
        aria-label={t('display.viewDetails', { name: agent.name })}
      />
      <div className="agent-card-content">
        <div className="agent-card-topline" />
        <div className="agent-card-main">
          <div className="agent-card-avatar">
            {agent.avatarUrl ? (
              <img className="agent-card-avatar-img" src={agent.avatarUrl} alt={agent.name} />
            ) : (
              <span className="agent-card-avatar-initials">{(agent.name || '?').slice(0, 2)}</span>
            )}
            <span className="agent-card-avatar-badge">{agentInitials(agent, index)}</span>
          </div>
          <div className="agent-card-title-area">
            <div className="agent-card-title">
              {agent.name}
            </div>
            <div className="agent-card-role">{getAgentRoleLabel(agent.roleCode, agent.roleName) || versionText(agent)}</div>
          </div>
          <div className="agent-card-status">
          <Tag className={`agent-status-tag ${statusMeta(agent).className}`} color={statusMeta(agent).color}>
            {statusMeta(agent).label}
          </Tag>
          {agent.kind === 'PLATFORM' && <Tag color="gold">{t('display.platform')}</Tag>}
          </div>
        </div>

        <div className="agent-executor-row">
          <span className={`agent-online-dot ${(agent.executorOnlineCount ?? 0) > 0 ? 'is-online' : ''}`} />
          <span>{executorText(agent)}</span>
        </div>

        <div className="agent-card-squad-row">
          <SquadTags squadIds={agent.squadIds} squadNames={agent.squadNames} />
        </div>

        <div className="agent-meta-line">
          <span>{versionText(agent)}</span>
          <span>ID {agent.id}</span>
          <span>{formatDate(agent.gmtCreate)}</span>
        </div>

        <div className="agent-metric-grid">
          <Metric icon={<SafetyCertificateOutlined aria-hidden />} label={t('display.skills')} value={agent.skillCount ?? 0} />
          <Metric icon={<DatabaseOutlined aria-hidden />} label={t('display.memories')} value={agent.memoryCount ?? 0} />
          <Metric icon={<ApiOutlined aria-hidden />} label={t('fields.repository')} value={agent.repoPermCount ?? 0} />
        </div>
      </div>
    </article>
  );
}

function SummaryPill({ label, value }: { label: string; value: number }) {
  return (
    <div className="agent-summary-pill">
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  );
}

function Metric({ icon, label, value }: { icon: ReactNode; label: string; value: number }) {
  return (
    <div className="agent-metric">
      <span className="agent-metric-icon">{icon}</span>
      <strong>{value}</strong>
      <span>{label}</span>
    </div>
  );
}

function statusMeta(agent: { status: string }) {
  const meta = Object.prototype.hasOwnProperty.call(statusMap, agent.status) ? statusMap[agent.status] : undefined;
  const label = agent.status === 'ONLINE' ? i18n.t('display.inUse', { ns: 'agent' })
    : agent.status === 'OFFLINE' ? i18n.t('display.notEnabled', { ns: 'agent' }) : getAgentStatusLabel(agent.status);
  return { ...(meta || { color: 'default', className: 'is-muted' }), label };
}

function executorText(agent: Agent, short = false) {
  const online = agent.executorOnlineCount ?? 0;
  const total = agent.executorTotalCount ?? 0;
  if (total <= 1) {
    return short ? getAgentStatusLabel(online > 0 ? 'ONLINE' : 'OFFLINE')
      : i18n.t(online > 0 ? 'display.executorOnline' : 'display.executorOffline', { ns: 'agent' });
  }
  return i18n.t('display.onlineRatio', { ns: 'agent', online, total });
}

function versionText(agent: Agent) {
  return agent.latestVersionNo ? `v${agent.latestVersionNo}` : '-';
}

function formatDate(value: string) {
  return formatDateTime(value, { dateStyle: 'medium' });
}

function agentInitials(agent: Agent, index: number) {
  const text = agent.roleCode || agent.name || String(index + 1);
  const parts = text.split(/[_\s-]+/).filter(Boolean);
  if (parts.length >= 2) {
    return `${parts[0][0]}${parts[1][0]}`.toUpperCase();
  }
  return text.slice(0, 2).toUpperCase();
}
