import { useState } from 'react';
import type { ReactNode } from 'react';
import { Button, Empty, Pagination, Spin, Tag } from 'antd';
import { ApiOutlined, DatabaseOutlined, PlusOutlined, RobotOutlined, SafetyCertificateOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useAgentList } from './hooks';
import type { Agent } from './api';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';
import './AgentListPage.css';

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
  const { data: agents = [], isLoading } = useAgentList(page, size);

  const activeCount = agents.filter((agent) => agent.status === 'ONLINE').length;
  const executorOnlineCount = agents.reduce((sum, agent) => sum + (agent.executorOnlineCount ?? 0), 0);
  const reviewCount = agents.filter((agent) => agent.status === 'PENDING_REVIEW').length;

  return (
    <section className="agent-card-page">
      <div className="agent-card-header">
        <div>
          <h2>数字员工</h2>
          <div className="agent-card-subtitle">按角色、启用状态和执行器在线状态快速扫描</div>
        </div>
        <Button type="primary" icon={<PlusOutlined />}
          onClick={() => accessCommand('READ_WRITE', '新建数字员工', () => navigate('/agents/new'))}>新建</Button>
      </div>

      <div className="agent-summary-strip">
        <SummaryPill label="全部数字员工" value={agents.length} />
        <SummaryPill label="使用中" value={activeCount} />
        <SummaryPill label="执行器在线" value={executorOnlineCount} />
        <SummaryPill label="待审核" value={reviewCount} />
      </div>

      <Spin spinning={isLoading}>
        {agents.length === 0 && !isLoading ? (
          <Empty description="暂无数字员工">
            <Button type="primary" icon={<PlusOutlined />}
              onClick={() => accessCommand('READ_WRITE', '新建数字员工', () => navigate('/agents/new'))}>
              新建数字员工
            </Button>
          </Empty>
        ) : (
          <div className="agent-card-grid">
            {agents.map((agent, index) => (
              <article key={agent.id} className="agent-person-card">
                <div className="agent-card-topline" />
                <div className="agent-card-main">
                  <button className="agent-card-avatar" type="button" onClick={() => navigate(`/agents/${agent.id}`)}>
                    <RobotOutlined />
                    <span>{agentInitials(agent, index)}</span>
                  </button>
                  <div className="agent-card-title-area">
                    <button className="agent-card-title" type="button" onClick={() => navigate(`/agents/${agent.id}`)}>
                      {agent.name}
                    </button>
                    <div className="agent-card-role">{agent.roleName || agent.roleCode || versionText(agent)}</div>
                  </div>
                  <Tag className={`agent-status-tag ${statusMeta(agent).className}`} color={statusMeta(agent).color}>
                    {statusMeta(agent).label}
                  </Tag>
                </div>

                <div className="agent-executor-row">
                  <span className={`agent-online-dot ${(agent.executorOnlineCount ?? 0) > 0 ? 'is-online' : ''}`} />
                  <span>{executorText(agent)}</span>
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
              </article>
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
