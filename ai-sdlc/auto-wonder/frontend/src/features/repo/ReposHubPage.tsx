import { RepoListPage } from './RepoListPage';
import { RepoMapPage } from './RepoMapPage';
import { HubPageShell, resolveHubTab, useHubTabParam } from '@/shared/ui/HubPage';

export const REPOS_HUB_TAB_KEYS = ['list', 'map'] as const;
export const DEFAULT_REPOS_HUB_TAB = 'list';

export function resolveReposHubTab(tabParam: string | null): string {
  return resolveHubTab(tabParam, REPOS_HUB_TAB_KEYS, DEFAULT_REPOS_HUB_TAB);
}

const TAB_DESCRIPTIONS: Record<string, string> = {
  list: '管理托管仓库与访问配置',
  map: '查看与编辑仓库之间的依赖与调用关系',
};

export function ReposHubPage() {
  const { activeTab, setTab } = useHubTabParam(REPOS_HUB_TAB_KEYS, DEFAULT_REPOS_HUB_TAB);

  return (
    <HubPageShell
      title="仓库"
      description={TAB_DESCRIPTIONS[activeTab]}
      activeTab={activeTab}
      onTabChange={setTab}
      items={[
        { key: 'list', label: '仓库', children: <RepoListPage /> },
        { key: 'map', label: '关系图', children: <RepoMapPage /> },
      ]}
    />
  );
}
