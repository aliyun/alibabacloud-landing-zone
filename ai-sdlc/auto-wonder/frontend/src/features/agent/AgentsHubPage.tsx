import { useTranslation } from 'react-i18next';
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

export function AgentsHubPage() {
  const { t } = useTranslation('agent');
  const descriptions = new Map([['list', t('display.hubListDescription')], ['reviews', t('display.hubReviewsDescription')], ['squads', t('display.hubSquadsDescription')]]);
  const { activeTab, setTab } = useHubTabParam(AGENTS_HUB_TAB_KEYS, DEFAULT_AGENTS_HUB_TAB);
  const { data: pendingReviewCount = 0 } = useAgentPendingReviewCount();

  const reviewsLabel = pendingReviewCount > 0 ? (
    <span className="hub-tab-label">
      {t('display.reviews')}
      <Badge count={pendingReviewCount} overflowCount={99} />
    </span>
  ) : t('display.reviews');

  return (
    <HubPageShell
      title={t('display.hubTitle')}
      description={descriptions.get(activeTab)}
      activeTab={activeTab}
      onTabChange={setTab}
      items={[
        { key: 'list', label: t('display.agents'), children: <AgentListPage /> },
        // 审核面板只在激活时挂载：Tabs 默认保留已挂载面板，重新激活不会重新挂载，
        // 也就不会触发 usePendingReviews 的 refetchOnMount:'always'，切回 Tab 时会看到旧列表。
        { key: 'reviews', label: reviewsLabel, children: activeTab === 'reviews' ? <AgentReviewPage /> : null },
        { key: 'squads', label: t('display.squads'), children: <SquadListPage /> },
      ]}
    />
  );
}
