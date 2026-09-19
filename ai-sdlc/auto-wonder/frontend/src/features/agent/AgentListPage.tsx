import { useEffect, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import { Alert, Button, Empty, Pagination, Spin, Tabs, Tag } from 'antd';
import { ApiOutlined, ArrowRightOutlined, DatabaseOutlined, PlusOutlined, RobotOutlined, SafetyCertificateOutlined } from '@ant-design/icons';
import { Link, useNavigate } from 'react-router-dom';
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

const statusMap: Record<string, { color: string; label: string; className: string }> = {
  DRAFT: { color: 'default', label: '草稿', className: 'is-muted' },
  ONLINE: { color: 'success', label: '使用中', className: 'is-active' },
  OFFLINE: { color: 'default', label: '未启用', className: 'is-muted' },
  PENDING_REVIEW: { color: 'processing', label: '待审核', className: 'is-review' },
};

export function AgentListPage() {
  const navigate = useNavigate();
  const accessCommand = useAccessCommand();
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(20);
  const [kindTab, setKindTab] = useState<AgentKind>('STANDARD');
  const [squadFilter, setSquadFilter] = useState<number[]>([]);
  const [grouped, setGrouped] = useState(
    () => readViewPreference(AGENTS_VIEW_STORAGE_KEY, AGENTS_VIEW_OPTIONS, 'grouped') === 'grouped',
  );
  const { options: squadOptions, nameById: squadNameById, isLoading: squadsLoading } = useSquadOptions();
  const { data: agents = [], isLoading } = useAgentList(page, size, undefined, kindTab, squadFilter);

  // The squad filter is applied server-side, so page 1 is the only page that can hold the new result set.
  useEffect(() => {
    setPage(1);
  }, [squadFilter]);

  const activeCount = agents.filter((agent) => agent.status === 'ONLINE').length;
  const executorOnlineCount = agents.reduce((sum, agent) => sum + (agent.executorOnlineCount ?? 0), 0);
  const reviewCount = agents.filter((agent) => agent.status === 'PENDING_REVIEW').length;

  const squadGroups = useMemo(
    () => groupBySquad(agents, (agent) => agent.squadIds, squadNameById),
    [agents, squadNameById],
  );

  return (
    <section className="agent-card-page">
      <div className="agent-card-header">
        <div>
          <h2>数字员工</h2>
          <div className="agent-card-subtitle">按角色、启用状态和执行器在线状态快速扫描</div>
        </div>
        {kindTab === 'STANDARD' && (
          <Button type="primary" icon={<PlusOutlined />}
            onClick={() => accessCommand('READ_WRITE', '新建数字员工', () => navigate('/agents/new'))}>新建</Button>
        )}
      </div>

      <Tabs
        activeKey={kindTab}
        onChange={(key) => { setKindTab(key as AgentKind); setPage(1); }}
        items={[
          { key: 'STANDARD', label: '数字员工' },
          { key: 'PLATFORM', label: '系统平台智能体' },
        ]}
      />

      <Alert
        className="agent-card-guidance"
        type="info"
        showIcon
        message="点击任一数字人卡片进入详情与配置。"
        description="可维护 SOUL.md、AGENT.md、记忆、仓库权限、SDLC 模板及技能/能力配置。"
      />

      <div className="agent-filter-bar">
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
      </div>

      <div className="agent-summary-strip">
        <SummaryPill label={kindTab === 'PLATFORM' ? '平台智能体' : '全部数字员工'} value={agents.length} />
        <SummaryPill label="使用中" value={activeCount} />
        <SummaryPill label="执行器在线" value={executorOnlineCount} />
        <SummaryPill label="待审核" value={reviewCount} />
      </div>

      <Spin spinning={isLoading}>
        {agents.length === 0 && !isLoading ? (
          kindTab === 'PLATFORM' ? (
            <Empty description="暂无平台智能体" />
          ) : (
            <Empty description="暂无数字员工">
              <Button type="primary" icon={<PlusOutlined />}
                onClick={() => accessCommand('READ_WRITE', '新建数字员工', () => navigate('/agents/new'))}>
                新建数字员工
              </Button>
            </Empty>
          )
        ) : grouped ? (
          <div className="agent-squad-groups">
            {squadGroups.map((group) => (
              <section key={group.key} className="agent-squad-group">
                <header className="agent-squad-group-header">
                  {group.squadId == null ? (
                    <span>{group.label}</span>
                  ) : (
                    <Link to={`/squads?squadId=${group.squadId}`}>{group.label}</Link>
                  )}
                  <span className="agent-squad-group-count">{group.items.length} 个</span>
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
          <div className="agent-card-grid">
            {agents.map((agent, index) => (
              <AgentCard key={agent.id} agent={agent} index={index} />
            ))}
          </div>
        )}
      </Spin>

      <Pagination
        className="agent-card-pagination"
        current={page}
        pageSize={size}
        total={agents.length}
        showSizeChanger
        showTotal={(total) => `共 ${total} 条`}
        onChange={(nextPage, nextSize) => {
          setPage(nextPage);
          setSize(nextSize);
        }}
      />
    </section>
  );
}

function AgentCard({ agent, index }: { agent: Agent; index: number }) {
  return (
    <article className="agent-person-card">
      <Link
        className="agent-card-detail-link"
        to={`/agents/${agent.id}`}
        aria-label={`查看 ${agent.name} 的详情与配置`}
      >
        <span className="agent-card-open-hint" aria-hidden="true">
          <span>查看详情与配置</span>
          <ArrowRightOutlined />
        </span>
      </Link>
      <div className="agent-card-content">
        <div className="agent-card-topline" />
        <div className="agent-card-main">
          <div className="agent-card-avatar">
            <RobotOutlined />
            <span>{agentInitials(agent, index)}</span>
          </div>
          <div className="agent-card-title-area">
            <div className="agent-card-title">
              {agent.name}
            </div>
            <div className="agent-card-role">{agent.roleName || agent.roleCode || versionText(agent)}</div>
          </div>
          <Tag className={`agent-status-tag ${statusMeta(agent).className}`} color={statusMeta(agent).color}>
            {statusMeta(agent).label}
          </Tag>
          {agent.kind === 'PLATFORM' && <Tag color="gold">平台</Tag>}
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
          <Metric icon={<SafetyCertificateOutlined />} label="技能" value={agent.skillCount ?? 0} />
          <Metric icon={<DatabaseOutlined />} label="记忆" value={agent.memoryCount ?? 0} />
          <Metric icon={<ApiOutlined />} label="仓库" value={agent.repoPermCount ?? 0} />
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

function statusMeta(agent: Agent) {
  return statusMap[agent.status] || { color: 'default', label: agent.status, className: 'is-muted' };
}

function executorText(agent: Agent) {
  const online = agent.executorOnlineCount ?? 0;
  const total = agent.executorTotalCount ?? 0;
  if (total <= 1) {
    return online > 0 ? '执行器在线' : '执行器离线';
  }
  return `${online}/${total} 在线`;
}

function versionText(agent: Agent) {
  return agent.latestVersionNo ? `v${agent.latestVersionNo}` : '-';
}

function formatDate(value: string) {
  if (!value) {
    return '-';
  }
  return new Date(value).toLocaleDateString('zh-CN');
}

function agentInitials(agent: Agent, index: number) {
  const text = agent.roleCode || agent.name || String(index + 1);
  const parts = text.split(/[_\s-]+/).filter(Boolean);
  if (parts.length >= 2) {
    return `${parts[0][0]}${parts[1][0]}`.toUpperCase();
  }
  return text.slice(0, 2).toUpperCase();
}
