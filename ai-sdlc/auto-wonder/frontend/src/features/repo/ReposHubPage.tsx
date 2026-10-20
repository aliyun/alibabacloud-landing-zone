import { useTranslation } from 'react-i18next';
import { RepoListPage } from './RepoListPage';
import { RepoMapPage } from './RepoMapPage';
import { HubPageShell, resolveHubTab, useHubTabParam } from '@/shared/ui/HubPage';

export const REPOS_HUB_TAB_KEYS = ['list', 'map'] as const;
export const DEFAULT_REPOS_HUB_TAB = 'list';

export function resolveReposHubTab(tabParam: string | null): string {
  return resolveHubTab(tabParam, REPOS_HUB_TAB_KEYS, DEFAULT_REPOS_HUB_TAB);
}

export function ReposHubPage() {
  const { t } = useTranslation('repo');
  const { activeTab, setTab } = useHubTabParam(REPOS_HUB_TAB_KEYS, DEFAULT_REPOS_HUB_TAB);

  const descriptions: Record<string, string> = { list: t('hub.listHint'), map: t('hub.mapHint') };

  return (
    <HubPageShell
      title={t('hub.title')}
      description={descriptions[activeTab]}
      activeTab={activeTab}
      onTabChange={setTab}
      items={[
        { key: 'list', label: t('hub.title'), children: <RepoListPage /> },
        { key: 'map', label: t('actions.map'), children: <RepoMapPage /> },
      ]}
    />
  );
}
