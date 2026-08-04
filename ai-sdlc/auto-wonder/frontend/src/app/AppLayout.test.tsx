import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { ReactElement } from 'react';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import {
  AppLayout,
  buildHeaderContext,
  buildUserDisplay,
  getOrgDeepLinkId,
  removeOrgDeepLink,
  shouldUseMobileLayout,
} from './AppLayout';
import { useAuthStore } from '@/shared/auth/store';
import { refreshCurrentMembership } from '@/shared/auth/refreshCurrentMembership';

vi.mock('@/shared/ui/NotificationBell', () => ({
  NotificationBell: () => <div data-testid="notification-bell">bell</div>,
}));
vi.mock('@/shared/auth/refreshCurrentMembership', () => ({
  refreshCurrentMembership: vi.fn(),
}));

function renderWithQueryClient(ui: ReactElement) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return {
    queryClient,
    ...render(
      <QueryClientProvider client={queryClient}>
        {ui}
      </QueryClientProvider>,
    ),
  };
}

function LocationProbe() {
  const location = useLocation();
  return <div data-testid="location-probe">{location.pathname}{location.search}</div>;
}

describe('AppLayout', () => {
  beforeEach(() => {
    useAuthStore.getState().clear();
    vi.clearAllMocks();
  });

  it('builds organization-aware header context for nested routes', () => {
    expect(buildHeaderContext('/settings/roles', '星云工坊')).toEqual({
      orgName: '星云工坊',
      sectionTitle: '系统设置',
      pageTitle: '成员管理',
    });
    expect(buildHeaderContext('/repos/map', '星云工坊')).toEqual({
      orgName: '星云工坊',
      sectionTitle: '仓库',
      pageTitle: '仓库关系图',
    });
    expect(buildHeaderContext('/open-platform', '星云工坊')).toEqual({
      orgName: '星云工坊',
      sectionTitle: '',
      pageTitle: '',
    });
    expect(buildHeaderContext('/about', '星云工坊')).toEqual({
      orgName: '星云工坊',
      sectionTitle: '',
      pageTitle: '关于 AutoWonder',
    });
  });

  it('builds complete user display information', () => {
    expect(buildUserDisplay({
      id: 1,
      username: 'alice',
      nickname: '爱丽丝',
      email: 'alice@example.com',
    })).toEqual({
      primaryText: '爱丽丝',
      secondaryText: 'alice@example.com',
      avatarText: '爱',
    });
  });

  it('uses the mobile shell only for real narrow breakpoints', () => {
    expect(shouldUseMobileLayout({ xs: true, md: false })).toBe(true);
    expect(shouldUseMobileLayout({ xs: true, md: true })).toBe(false);
    expect(shouldUseMobileLayout({ xs: false, md: false })).toBe(false);
  });

  it('refreshes current membership on mount and when the window regains focus', () => {
    useAuthStore.getState().setCurrentOrg(
      { id: 7, name: '星云工坊', description: '' },
      'ADMIN',
    );
    renderWithQueryClient(
      <MemoryRouter initialEntries={['/workitems']}>
        <Routes>
          <Route element={<AppLayout />}>
            <Route path="/workitems" element={<div>工单页</div>} />
          </Route>
        </Routes>
      </MemoryRouter>,
    );

    expect(refreshCurrentMembership).toHaveBeenCalledTimes(1);
    window.dispatchEvent(new Event('focus'));

    expect(refreshCurrentMembership).toHaveBeenCalledTimes(2);
  });

  it('parses and removes one-shot org deep-link parameters', () => {
    expect(getOrgDeepLinkId('?orgId=8')).toBe(8);
    expect(getOrgDeepLinkId('?orgId=0')).toBeNull();
    expect(getOrgDeepLinkId('?orgId=abc')).toBeNull();
    expect(removeOrgDeepLink('?orgId=8&tab=timeline')).toBe('?tab=timeline');
    expect(removeOrgDeepLink('?orgId=8')).toBe('');
  });

  it('renders org context and complete user information in the header', () => {
    useAuthStore.getState().setUser({
      id: 1,
      username: 'alice',
      nickname: '爱丽丝',
      email: 'alice@example.com',
    });
    useAuthStore.getState().setCurrentOrg({ id: 7, name: '星云工坊', description: '研发组织' }, 'READ_ONLY');

    renderWithQueryClient(
      <MemoryRouter initialEntries={['/settings/roles']}>
        <Routes>
          <Route element={<AppLayout />}>
            <Route path="/settings/roles" element={<div>角色权限页</div>} />
          </Route>
        </Routes>
      </MemoryRouter>,
    );

    expect(screen.getAllByText('星云工坊')).toHaveLength(1);
    const orgInitialMarks = screen.getAllByText('星');
    expect(orgInitialMarks).toHaveLength(1);
    orgInitialMarks.forEach((mark) => {
      expect(mark).toHaveStyle({ color: '#ff6a00', borderColor: 'rgba(255, 106, 0, 0.28)' });
    });
    expect(screen.getByText('爱')).toHaveStyle({ color: '#ff6a00', borderColor: 'rgba(255, 106, 0, 0.28)' });
    expect(screen.getAllByText('系统设置')).toHaveLength(1);
    expect(screen.getAllByText('成员管理').length).toBeGreaterThanOrEqual(2);
    expect(screen.getByText('爱丽丝')).toBeInTheDocument();
    expect(screen.getByText('alice@example.com')).toBeInTheDocument();
    expect(screen.getByText('角色权限页')).toBeInTheDocument();
  });

  it('navigates to personal settings from user menu', async () => {
    useAuthStore.getState().setUser({
      id: 1,
      username: 'alice',
      nickname: '爱丽丝',
      email: 'alice@example.com',
    });
    useAuthStore.getState().setCurrentOrg({ id: 7, name: '星云工坊', description: '研发组织' }, 'READ_ONLY');

    renderWithQueryClient(
      <MemoryRouter initialEntries={['/workitems']}>
        <Routes>
          <Route element={<AppLayout />}>
            <Route path="/workitems" element={<div>工作项页</div>} />
            <Route path="/profile/settings" element={<div>个人设置页</div>} />
          </Route>
        </Routes>
      </MemoryRouter>,
    );

    await userEvent.click(screen.getByText('爱丽丝'));
    await userEvent.click(await screen.findByText('个人设置'));

    expect(await screen.findByText('个人设置页')).toBeInTheDocument();
  });

  it('switches to the deep-linked workitem organization and cleans the url', async () => {
    useAuthStore.getState().setUser({
      id: 1,
      username: 'alice',
      nickname: '爱丽丝',
      email: 'alice@example.com',
    });
    useAuthStore.getState().setAccessToken('org-7-token');
    useAuthStore.getState().setCurrentOrg({ id: 7, name: '星云工坊', description: '研发组织' }, 'READ_ONLY');
    server.use(
      http.get('/api/orgs/mine', () => HttpResponse.json({
        success: true,
        code: '0',
        message: '',
        traceId: null,
        data: [
          { id: 7, name: '星云工坊', description: '研发组织' },
          { id: 8, name: '平台商业化', description: '私有化组织' },
        ],
      })),
      http.post('/api/orgs/8/switch', () => HttpResponse.json({
        success: true,
        code: '0',
        message: '',
        traceId: null,
        data: { accessToken: 'org-8-token', accessLevel: 'READ_ONLY' },
      })),
    );

    const { queryClient } = renderWithQueryClient(
      <MemoryRouter initialEntries={['/workitems/42?orgId=8']}>
        <Routes>
          <Route element={<AppLayout />}>
            <Route path="/workitems/:id" element={<><LocationProbe /><div>工单详情页</div></>} />
          </Route>
        </Routes>
      </MemoryRouter>,
    );
    queryClient.setQueryData(['workitems', { page: 1 }], { list: [{ id: 101 }] });
    queryClient.setQueryData(['workitem', '42'], { id: 42, title: '旧组织详情缓存' });
    queryClient.setQueryData(['workitem', '42', 'unified-timeline'], [{ id: 1 }]);

    await waitFor(() => {
      expect(useAuthStore.getState().currentOrg?.id).toBe(8);
    });
    expect(useAuthStore.getState().accessToken).toBe('org-8-token');
    expect(await screen.findByTestId('location-probe')).toHaveTextContent('/workitems/42');
    await waitFor(() => expect(screen.getByTestId('location-probe')).not.toHaveTextContent('orgId=8'));
    expect(queryClient.getQueryData(['workitems', { page: 1 }])).toBeUndefined();
    expect(queryClient.getQueryData(['workitem', '42'])).toBeUndefined();
    expect(queryClient.getQueryData(['workitem', '42', 'unified-timeline'])).toBeUndefined();
  });

  it('opens org dropdown menu from the sidebar org chip', async () => {
    useAuthStore.getState().setUser({
      id: 1,
      username: 'alice',
      nickname: '爱丽丝',
      email: 'alice@example.com',
    });
    useAuthStore.getState().setAccessToken('org-7-token');
    useAuthStore.getState().setCurrentOrg({ id: 7, name: '星云工坊', description: '研发组织' }, 'READ_ONLY');
    server.use(
      http.get('/api/orgs/mine', () => HttpResponse.json({
        success: true,
        code: '0',
        message: '',
        traceId: null,
        data: [
          { id: 7, name: '星云工坊', description: '研发组织' },
          { id: 8, name: '平台商业化', description: '私有化组织' },
        ],
      })),
    );

    renderWithQueryClient(
      <MemoryRouter initialEntries={['/workitems']}>
        <Routes>
          <Route element={<AppLayout />}>
            <Route path="/workitems" element={<div>工单页</div>} />
          </Route>
        </Routes>
      </MemoryRouter>,
    );

    await screen.findByText('星云工坊');
    await userEvent.click(screen.getByText('星云工坊'));

    expect(await screen.findByText('平台商业化')).toBeInTheDocument();
    expect(await screen.findByText('管理组织...')).toBeInTheDocument();
  });

  it('switches org from the sidebar dropdown without navigating away', async () => {
    useAuthStore.getState().setUser({
      id: 1,
      username: 'alice',
      nickname: '爱丽丝',
      email: 'alice@example.com',
    });
    useAuthStore.getState().setAccessToken('org-7-token');
    useAuthStore.getState().setCurrentOrg({ id: 7, name: '星云工坊', description: '研发组织' }, 'READ_ONLY');
    server.use(
      http.get('/api/orgs/mine', () => HttpResponse.json({
        success: true,
        code: '0',
        message: '',
        traceId: null,
        data: [
          { id: 7, name: '星云工坊', description: '研发组织' },
          { id: 8, name: '平台商业化', description: '私有化组织' },
        ],
      })),
      http.post('/api/orgs/8/switch', () => HttpResponse.json({
        success: true,
        code: '0',
        message: '',
        traceId: null,
        data: { accessToken: 'org-8-token', accessLevel: 'READ_ONLY' },
      })),
    );

    const { queryClient } = renderWithQueryClient(
      <MemoryRouter initialEntries={['/workitems']}>
        <Routes>
          <Route element={<AppLayout />}>
            <Route path="/workitems" element={<><LocationProbe /><div>工单页</div></>} />
            <Route path="/orgs" element={<div>组织选择页</div>} />
          </Route>
        </Routes>
      </MemoryRouter>,
    );

    queryClient.setQueryData(['workitems', { page: 1 }], { list: [{ id: 101 }] });
    queryClient.setQueryData(['agents', 1, 10], { list: [{ id: 1, name: 'stale-agent' }] });

    await screen.findByText('星云工坊');
    await userEvent.click(screen.getByText('星云工坊'));
    await userEvent.click(await screen.findByText('平台商业化'));

    await waitFor(() => {
      expect(useAuthStore.getState().currentOrg?.id).toBe(8);
    });
    expect(useAuthStore.getState().accessToken).toBe('org-8-token');
    expect(await screen.findByTestId('location-probe')).toHaveTextContent('/workitems');
    expect(screen.queryByText('组织选择页')).not.toBeInTheDocument();
    expect(queryClient.getQueryData(['workitems', { page: 1 }])).toBeUndefined();
    expect(queryClient.getQueryData(['agents', 1, 10])).toBeUndefined();
  });

  it('removes the legacy "切换组织" menu item from the user dropdown', async () => {
    useAuthStore.getState().setUser({
      id: 1,
      username: 'alice',
      nickname: '爱丽丝',
      email: 'alice@example.com',
    });
    useAuthStore.getState().setAccessToken('org-7-token');
    useAuthStore.getState().setCurrentOrg({ id: 7, name: '星云工坊', description: '研发组织' }, 'READ_ONLY');

    renderWithQueryClient(
      <MemoryRouter initialEntries={['/workitems']}>
        <Routes>
          <Route element={<AppLayout />}>
            <Route path="/workitems" element={<div>工作项页</div>} />
          </Route>
        </Routes>
      </MemoryRouter>,
    );

    await userEvent.click(screen.getByText('爱丽丝'));
    expect(screen.queryByText('切换组织')).not.toBeInTheDocument();
    expect(screen.getByText('个人设置')).toBeInTheDocument();
    expect(screen.getByText('退出登录')).toBeInTheDocument();
  });

  it('applies truncation constraints for long org and user text', () => {
    const longOrgName = '超长组织名称'.repeat(12);
    const longNickname = '超长昵称'.repeat(12);
    const longEmail = `${'verylong'.repeat(8)}@example.com`;

    useAuthStore.getState().setUser({
      id: 2,
      username: 'long-user',
      nickname: longNickname,
      email: longEmail,
    });
    useAuthStore.getState().setCurrentOrg({ id: 8, name: longOrgName, description: '长组织' }, 'READ_ONLY');

    renderWithQueryClient(
      <MemoryRouter initialEntries={['/status-templates']}>
        <Routes>
          <Route element={<AppLayout />}>
            <Route path="/status-templates" element={<div>状态模版页</div>} />
          </Route>
        </Routes>
      </MemoryRouter>,
    );

    const orgTexts = screen.getAllByTitle(longOrgName);
    expect(orgTexts).toHaveLength(1);
    expect(orgTexts[0]).toHaveStyle({ textOverflow: 'ellipsis', whiteSpace: 'nowrap' });
    expect(screen.getByTitle(longNickname)).toHaveStyle({ textOverflow: 'ellipsis', maxWidth: '220px' });
    expect(screen.getByTitle(longEmail)).toHaveStyle({ textOverflow: 'ellipsis', maxWidth: '220px' });
    expect(screen.getByTitle('状态模版')).toHaveStyle({ textOverflow: 'ellipsis', whiteSpace: 'nowrap' });
  });
});
