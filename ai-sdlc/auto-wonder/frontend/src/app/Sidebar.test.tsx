import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { buildMenuItems, NAV_GROUPS, resolveSelectedNavKey, Sidebar } from './Sidebar';
import type { ItemType, MenuItemGroupType, MenuItemType } from 'antd/es/menu/interface';

const testQueryClient = new QueryClient({
  defaultOptions: { queries: { retry: false } },
});

function TestWrapper({ children }: { children: React.ReactNode }) {
  return (
    <QueryClientProvider client={testQueryClient}>
      <MemoryRouter initialEntries={['/workitems']}>
        {children}
      </MemoryRouter>
    </QueryClientProvider>
  );
}

function isGroupItem(item: ItemType | null | undefined): item is MenuItemGroupType<MenuItemType> {
  return Boolean(item && item.type === 'group' && 'children' in item);
}

function childKeys(item: ItemType | null | undefined) {
  return isGroupItem(item) ? (item.children ?? []).map((child) => child?.key) : [];
}

describe('Sidebar helpers', () => {
  it('places insights and audit logs in a dedicated group instead of config', () => {
    const items = buildMenuItems();
    const configGroup = items.find((item) => item?.key === 'config-group');
    const insightGroup = items.find((item) => item?.key === 'insight-group');

    expect(isGroupItem(configGroup)).toBe(true);
    expect(isGroupItem(insightGroup)).toBe(true);
    expect(childKeys(configGroup)).not.toContain('/insights');
    expect(childKeys(configGroup)).not.toContain('/audit-logs');
    expect(childKeys(insightGroup)).toContain('/insights');
    expect(childKeys(insightGroup)).toContain('/audit-logs');
  });

  it('exposes a single member management entry', () => {
    const items = buildMenuItems();
    const configGroup = items.find((item) => item?.key === 'config-group');

    expect(childKeys(configGroup)).toContain('/settings/members');
    expect(childKeys(configGroup)).not.toContain('/settings/members-roles');
    expect(childKeys(configGroup)).not.toContain('/settings/roles');
    expect(childKeys(configGroup)).not.toContain('/platform/branding');
    expect(resolveSelectedNavKey('/platform/branding')).toBe('');
  });

  it('exposes the about AutoWonder page', () => {
    const items = buildMenuItems();
    const aboutGroup = items.find((item) => item?.key === 'about-group');

    expect(isGroupItem(aboutGroup)).toBe(true);
    expect(childKeys(aboutGroup)).toContain('/about');
    expect(resolveSelectedNavKey('/about')).toBe('/about');
  });

  it('labels the skills navigation item as capability', () => {
    const items = buildMenuItems();
    const knowledgeGroup = items.find((item) => item?.key === 'knowledge-group');
    const skillsItem = isGroupItem(knowledgeGroup) ? (knowledgeGroup.children ?? []).find((child) => child?.key === '/skills') : undefined;

    expect(skillsItem && 'label' in skillsItem ? skillsItem.label : undefined).toBe('能力');
    expect(resolveSelectedNavKey('/skills')).toBe('/skills');
  });

  it('no longer exposes the open platform entry because MCP moved to personal settings', () => {
    const items = buildMenuItems();
    const visibleKeys = items.flatMap(childKeys);

    expect(items.find((item) => item?.key === 'open-group')).toBeUndefined();
    expect(visibleKeys).not.toContain('/open-platform');
    expect(resolveSelectedNavKey('/open-platform')).toBe('');
  });

  it('places about directly after insights once the open group is gone', () => {
    const groupKeys = buildMenuItems().map((item) => item?.key);

    expect(groupKeys).not.toContain('open-group');
    expect(groupKeys.indexOf('about-group')).toBe(groupKeys.indexOf('insight-group') + 1);
  });

  it('hides the system settings entry from the menu while keeping its route metadata', () => {
    const items = buildMenuItems();
    const configGroup = items.find((item) => item?.key === 'config-group');

    expect(childKeys(configGroup)).not.toContain('/settings');
    expect(NAV_GROUPS.flatMap((group) => group.items).map((item) => item.key)).toContain('/settings');
    expect(resolveSelectedNavKey('/settings')).toBe('/settings');
  });

  it('always exposes every configured visible navigation item', () => {
    const items = buildMenuItems();
    const visibleKeys = items.flatMap(childKeys);
    const configuredVisibleKeys = NAV_GROUPS.flatMap((group) =>
      group.items.filter((item) => !item.hidden).map((item) => item.key),
    );

    expect(visibleKeys).toEqual(configuredVisibleKeys);
  });

  it('hides the evolution entry from the menu while keeping its route resolvable', () => {
    const items = buildMenuItems();
    const insightGroup = items.find((item) => item?.key === 'insight-group');

    expect(items.flatMap(childKeys)).not.toContain('/evolution');
    expect(childKeys(insightGroup)).toEqual(['/insights', '/audit-logs']);
    expect(resolveSelectedNavKey('/evolution')).toBe('/evolution');
  });

  it('does not render the evolution label in the rendered menu', () => {
    render(
      <TestWrapper>
        <Sidebar />
      </TestWrapper>,
    );

    expect(screen.queryByText('自进化')).toBeNull();
    expect(screen.getByText('数据洞察')).toBeInTheDocument();
    expect(screen.getByText('审计日志')).toBeInTheDocument();
  });

  it('matches the most specific navigation item for nested and legacy routes', () => {
    expect(resolveSelectedNavKey('/agents/reviews')).toBe('/agents/reviews');
    expect(resolveSelectedNavKey('/settings/members-roles/roles')).toBe('/settings/members');
    expect(resolveSelectedNavKey('/settings/roles')).toBe('/settings/members');
  });

  it('renders badge count on matching items when count > 0', () => {
    const items = buildMenuItems({ '/agents/reviews': 5 });
    const workersGroup = items.find((item) => item?.key === 'workers-group');
    const reviewItem = isGroupItem(workersGroup)
      ? (workersGroup.children ?? []).find((child) => child?.key === '/agents/reviews')
      : undefined;

    expect(reviewItem && 'label' in reviewItem ? reviewItem.label : undefined).not.toBe('版本审核');
  });

  it('keeps plain label when badge count is 0 or absent', () => {
    const items = buildMenuItems({ '/agents/reviews': 0 });
    const workersGroup = items.find((item) => item?.key === 'workers-group');
    const reviewItem = isGroupItem(workersGroup)
      ? (workersGroup.children ?? []).find((child) => child?.key === '/agents/reviews')
      : undefined;

    expect(reviewItem && 'label' in reviewItem ? reviewItem.label : undefined).toBe('版本审核');
  });

  it('renders badge dot on icon in collapsed mode when count > 0', () => {
    const items = buildMenuItems({ '/agents/reviews': 3 }, true);
    const workersGroup = items.find((item) => item?.key === 'workers-group');
    const reviewItem = isGroupItem(workersGroup)
      ? (workersGroup.children ?? []).find((child) => child?.key === '/agents/reviews')
      : undefined;

    expect(reviewItem && 'label' in reviewItem ? reviewItem.label : undefined).toBe('版本审核');
  });
});
