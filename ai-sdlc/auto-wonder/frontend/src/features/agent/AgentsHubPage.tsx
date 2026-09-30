import { Badge } from 'antd';
import { AgentListPage } from './AgentListPage';
import { AgentReviewPage } from './AgentReviewPage';
import { useAgentPendingReviewCount } from './hooks';
import { SquadListPage } from '@/features/squad/SquadListPage';
import { HubPageShell, resolveHubTab, useHubTabParam } from '@/shared/ui/HubPage';

export const AGENTS_HUB_TAB_KEYS = ['list', 'reviews', 'squads'] as const;
export const DEFAULT_AGENTS_HUB_TAB = 'list';

export function resolveAgentsHubTab(tabParam: string | null): string {
  return resolveHubTab(tabParam, AGENTS_HUB_TAB_KEYS, DEFAULT_AGENTS_HUB_TAB);
}

const TAB_DESCRIPTIONS: Record<string, string> = {
  list: '按角色、启用状态和执行器在线状态快速扫描数字员工',
  reviews: '审核数字员工版本提交，通过后进入上线流转',
  squads: '按小队规模、角色构成和执行器状态查看交付阵容',
};

export function AgentsHubPage() {
  const { activeTab, setTab } = useHubTabParam(AGENTS_HUB_TAB_KEYS, DEFAULT_AGENTS_HUB_TAB);
  const { data: pendingReviewCount = 0 } = useAgentPendingReviewCount();

  const reviewsLabel = pendingReviewCount > 0 ? (
    <span className="hub-tab-label">
      版本审核
      <Badge count={pendingReviewCount} overflowCount={99} />
    </span>
  ) : '版本审核';

  return (
    <HubPageShell
      title="数字员工管理"
      description={TAB_DESCRIPTIONS[activeTab]}
      activeTab={activeTab}
      onTabChange={setTab}
      items={[
        { key: 'list', label: '数字员工', children: <AgentListPage /> },
        { key: 'reviews', label: reviewsLabel, children: <AgentReviewPage /> },
        { key: 'squads', label: '小队', children: <SquadListPage /> },
      ]}
    />
  );
}
