import { describe, expect, it } from 'vitest';
import { act, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { buildMenuItems, NAV_GROUPS, resolveSelectedNavKey, Sidebar } from './Sidebar';
import type { ItemType, MenuItemGroupType, MenuItemType } from 'antd/es/menu/interface';
import { scheduledTaskCapabilityQueryKey } from '@/features/scheduledTask/hooks';
import type { ScheduledTaskCapability } from '@/features/scheduledTask/types';

const readyCapability: ScheduledTaskCapability = {
  available: true,
  mode: 'V037_READY',
  clusterReady: true,
  reason: null,
};

function TestWrapper({
  children,
  queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } }),
}: {
  children: React.ReactNode;
  queryClient?: QueryClient;
}) {
  return (
    <QueryClientProvider client={queryClient}>
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
  it('shows scheduled tasks under delivery', () => {
    const group = NAV_GROUPS.find((item) => item.key === 'delivery');
    expect(group?.items.map((item) => item.key)).toContain('/scheduled-tasks');
  });

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
      group.items.filter((item) => !item.hidden && item.key !== '/scheduled-tasks').map((item) => item.key),
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

  it('hides scheduled tasks until the capability request is ready', async () => {
    let resolveCapability: (() => void) | undefined;
    server.use(
      http.get('/api/capabilities/scheduled-task', async () => {
        await new Promise<void>((resolve) => { resolveCapability = resolve; });
        return HttpResponse.json({ success: true, code: '0', message: '', data: { available: true, mode: 'V037_READY', clusterReady: true, reason: null } });
      }),
    );

    render(<TestWrapper><Sidebar /></TestWrapper>);

    expect(screen.queryByText('定时任务')).not.toBeInTheDocument();
    expect(screen.getByText('工单')).toBeInTheDocument();
    await waitFor(() => expect(resolveCapability).toBeTypeOf('function'));
    act(() => resolveCapability?.());
    expect(await screen.findByText('定时任务')).toBeInTheDocument();
  });

  it('hides scheduled tasks when the capability is unavailable', async () => {
    server.use(
      http.get('/api/capabilities/scheduled-task', () => HttpResponse.json({ success: true, code: '0', message: '', data: { available: false, mode: 'LEGACY', clusterReady: false, reason: 'DATABASE_UPGRADE_REQUIRED' } })),
    );

    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<TestWrapper queryClient={queryClient}><Sidebar /></TestWrapper>);

    await waitFor(() => expect(queryClient.getQueryState(scheduledTaskCapabilityQueryKey)?.status).toBe('success'));
    expect(screen.queryByText('定时任务')).not.toBeInTheDocument();
  });

  it('hides scheduled tasks when the capability request fails', async () => {
    server.use(
      http.get('/api/capabilities/scheduled-task', () => HttpResponse.json({ success: false, code: '10000', message: 'failed', data: null }, { status: 500 })),
    );

    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<TestWrapper queryClient={queryClient}><Sidebar /></TestWrapper>);

    await waitFor(() => expect(queryClient.getQueryState(scheduledTaskCapabilityQueryKey)?.status).toBe('error'));
    expect(screen.queryByText('定时任务')).not.toBeInTheDocument();
  });

  it('hides scheduled tasks while stale cached readiness is revalidated', async () => {
    let resolveCapability: (() => void) | undefined;
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    queryClient.setQueryData(scheduledTaskCapabilityQueryKey, readyCapability, { updatedAt: 0 });
    server.use(
      http.get('/api/capabilities/scheduled-task', async () => {
        await new Promise<void>((resolve) => { resolveCapability = resolve; });
        return HttpResponse.json({ success: true, code: '0', message: '', data: readyCapability });
      }),
    );

    render(<TestWrapper queryClient={queryClient}><Sidebar /></TestWrapper>);

    await waitFor(() => expect(queryClient.getQueryState(scheduledTaskCapabilityQueryKey)?.fetchStatus).toBe('fetching'));
    expect(screen.queryByText('定时任务')).not.toBeInTheDocument();
    act(() => resolveCapability?.());
    expect(await screen.findByText('定时任务')).toBeInTheDocument();
  });

  it('keeps scheduled tasks hidden when stale cached readiness fails revalidation', async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    queryClient.setQueryData(scheduledTaskCapabilityQueryKey, readyCapability, { updatedAt: 0 });
    server.use(http.get('/api/capabilities/scheduled-task', () => HttpResponse.error()));

    render(<TestWrapper queryClient={queryClient}><Sidebar /></TestWrapper>);

    expect(screen.queryByText('定时任务')).not.toBeInTheDocument();
    await waitFor(() => expect(queryClient.getQueryState(scheduledTaskCapabilityQueryKey)?.status).toBe('error'));
    expect(screen.queryByText('定时任务')).not.toBeInTheDocument();
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
