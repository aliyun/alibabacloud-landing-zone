import { PageHeading } from '@/shared/ui/PageHeading';
import { Tabs } from 'antd';
import type { ReactNode } from 'react';
import { useSearchParams } from 'react-router-dom';
import './HubPage.css';

/** Whitelisted so an unknown `tab` value keeps the default tab instead of blanking the pane. */
export function resolveHubTab(tabParam: string | null, keys: readonly string[], defaultKey: string): string {
  return tabParam !== null && keys.includes(tabParam) ? tabParam : defaultKey;
}

/**
 * URL-persisted tab state shared by the merged hub pages. The default tab omits the
 * `tab` param entirely so the canonical URL stays clean; every switch replaces
 * history so refresh / back / forward keep the active tab.
 */
export function useHubTabParam(keys: readonly string[], defaultKey: string) {
  const [searchParams, setSearchParams] = useSearchParams();
  const activeTab = resolveHubTab(searchParams.get('tab'), keys, defaultKey);

  const setTab = (key: string) => {
    const next = new URLSearchParams(searchParams);
    if (key === defaultKey) {
      next.delete('tab');
    } else {
      next.set('tab', key);
    }
    setSearchParams(next, { replace: true });
  };

  return { activeTab, setTab };
}

export interface HubPageShellTab {
  key: string;
  label: ReactNode;
  children: ReactNode;
}

export interface HubPageShellProps {
  title: string;
  description: ReactNode;
  extra?: ReactNode;
  activeTab: string;
  onTabChange: (key: string) => void;
  items: HubPageShellTab[];
}

/** Unified hub rhythm: page header (title + description + actions) → tab bar → content. */
export function HubPageShell({ title, description, extra, activeTab, onTabChange, items }: HubPageShellProps) {
  return (
    <section className="hub-page">
      <PageHeading title={title} description={description} extra={extra} />
      <Tabs
        className="hub-page-tabs"
        activeKey={activeTab}
        onChange={onTabChange}
        items={items.map((item) => ({ key: item.key, label: item.label, children: item.children }))}
      />
    </section>
  );
}
