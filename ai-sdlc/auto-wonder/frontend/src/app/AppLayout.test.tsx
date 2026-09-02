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
  getWorkspaceDeepLinkId,
  removeWorkspaceDeepLink,
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

  it('builds workspace-aware header context for nested routes', () => {
    expect(buildHeaderContext('/settings/roles', '星云工坊')).toEqual({
      workspaceName: '星云工坊',
      sectionTitle: '系统设置',
      pageTitle: '成员管理',
    });
    expect(buildHeaderContext('/repos/map', '星云工坊')).toEqual({
      workspaceName: '星云工坊',
      sectionTitle: '仓库',
      pageTitle: '仓库关系图',
    });
    expect(buildHeaderContext('/open-platform', '星云工坊')).toEqual({
      workspaceName: '星云工坊',
      sectionTitle: '',
      pageTitle: '',
    });
    expect(buildHeaderContext('/about', '星云工坊')).toEqual({
      workspaceName: '星云工坊',
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
    useAuthStore.getState().setCurrentWorkspace(
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

  it('parses and removes one-shot workspace deep-link parameters', () => {
    expect(getWorkspaceDeepLinkId('?workspaceId=8')).toBe(8);
    expect(getWorkspaceDeepLinkId('?workspaceId=0')).toBeNull();
    expect(getWorkspaceDeepLinkId('?workspaceId=abc')).toBeNull();
    expect(removeWorkspaceDeepLink('?workspaceId=8&tab=timeline')).toBe('?tab=timeline');
    expect(removeWorkspaceDeepLink('?workspaceId=8')).toBe('');
  });

  it('renders workspace context and complete user information in the header', () => {
    useAuthStore.getState().setUser({
      id: 1,
      username: 'alice',
      nickname: '爱丽丝',
      email: 'alice@example.com',
    });
    useAuthStore.getState().setCurrentWorkspace({ id: 7, name: '星云工坊', description: '研发工作空间' }, 'READ_ONLY');

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
    const workspaceInitialMarks = screen.getAllByText('星');
    expect(workspaceInitialMarks).toHaveLength(1);
    workspaceInitialMarks.forEach((mark) => {
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
    useAuthStore.getState().setCurrentWorkspace({ id: 7, name: '星云工坊', description: '研发工作空间' }, 'READ_ONLY');

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

  it('switches to the deep-linked workitem workspace and cleans the url', async () => {
    useAuthStore.getState().setUser({
      id: 1,
      username: 'alice',
      nickname: '爱丽丝',
      email: 'alice@example.com',
    });
    useAuthStore.getState().setAccessToken('workspace-7-token');
    useAuthStore.getState().setCurrentWorkspace({ id: 7, name: '星云工坊', description: '研发工作空间' }, 'READ_ONLY');
    server.use(
      http.get('/api/workspaces/mine', () => HttpResponse.json({
        success: true,
        code: '0',
        message: '',
        traceId: null,
        data: [
          { id: 7, name: '星云工坊', description: '研发工作空间' },
          { id: 8, name: '平台商业化', description: '私有化工作空间' },
        ],
      })),
      http.post('/api/workspaces/8/switch', () => HttpResponse.json({
        success: true,
        code: '0',
        message: '',
        traceId: null,
        data: { accessToken: 'workspace-8-token', accessLevel: 'READ_ONLY' },
      })),
    );

    const { queryClient } = renderWithQueryClient(
      <MemoryRouter initialEntries={['/workitems/42?workspaceId=8']}>
        <Routes>
          <Route element={<AppLayout />}>
            <Route path="/workitems/:id" element={<><LocationProbe /><div>工单详情页</div></>} />
          </Route>
        </Routes>
      </MemoryRouter>,
    );
    queryClient.setQueryData(['workitems', { page: 1 }], { list: [{ id: 101 }] });
    queryClient.setQueryData(['workitem', '42'], { id: 42, title: '旧工作空间详情缓存' });
    queryClient.setQueryData(['workitem', '42', 'unified-timeline'], [{ id: 1 }]);

    await waitFor(() => {
      expect(useAuthStore.getState().currentWorkspace?.id).toBe(8);
    });
    expect(useAuthStore.getState().accessToken).toBe('workspace-8-token');
    expect(await screen.findByTestId('location-probe')).toHaveTextContent('/workitems/42');
    await waitFor(() => expect(screen.getByTestId('location-probe')).not.toHaveTextContent('workspaceId=8'));
    expect(queryClient.getQueryData(['workitems', { page: 1 }])).toBeUndefined();
    expect(queryClient.getQueryData(['workitem', '42'])).toBeUndefined();
    expect(queryClient.getQueryData(['workitem', '42', 'unified-timeline'])).toBeUndefined();
  });

  it('opens workspace dropdown menu from the sidebar workspace chip', async () => {
    useAuthStore.getState().setUser({
      id: 1,
      username: 'alice',
      nickname: '爱丽丝',
      email: 'alice@example.com',
    });
    useAuthStore.getState().setAccessToken('workspace-7-token');
    useAuthStore.getState().setCurrentWorkspace({ id: 7, name: '星云工坊', description: '研发工作空间' }, 'READ_ONLY');
    server.use(
      http.get('/api/workspaces/mine', () => HttpResponse.json({
        success: true,
        code: '0',
        message: '',
        traceId: null,
        data: [
          { id: 7, name: '星云工坊', description: '研发工作空间' },
          { id: 8, name: '平台商业化', description: '私有化工作空间' },
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
    expect(await screen.findByText('管理工作空间...')).toBeInTheDocument();
  });

  it('switches workspace from the sidebar dropdown without navigating away', async () => {
    useAuthStore.getState().setUser({
      id: 1,
      username: 'alice',
      nickname: '爱丽丝',
      email: 'alice@example.com',
    });
    useAuthStore.getState().setAccessToken('workspace-7-token');
    useAuthStore.getState().setCurrentWorkspace({ id: 7, name: '星云工坊', description: '研发工作空间' }, 'READ_ONLY');
    server.use(
      http.get('/api/workspaces/mine', () => HttpResponse.json({
        success: true,
        code: '0',
        message: '',
        traceId: null,
        data: [
          { id: 7, name: '星云工坊', description: '研发工作空间' },
          { id: 8, name: '平台商业化', description: '私有化工作空间' },
        ],
      })),
      http.post('/api/workspaces/8/switch', () => HttpResponse.json({
        success: true,
        code: '0',
        message: '',
        traceId: null,
        data: { accessToken: 'workspace-8-token', accessLevel: 'READ_ONLY' },
      })),
    );

    const { queryClient } = renderWithQueryClient(
      <MemoryRouter initialEntries={['/workitems']}>
        <Routes>
          <Route element={<AppLayout />}>
            <Route path="/workitems" element={<><LocationProbe /><div>工单页</div></>} />
            <Route path="/workspaces" element={<div>工作空间选择页</div>} />
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
      expect(useAuthStore.getState().currentWorkspace?.id).toBe(8);
    });
    expect(useAuthStore.getState().accessToken).toBe('workspace-8-token');
    expect(await screen.findByTestId('location-probe')).toHaveTextContent('/workitems');
    expect(screen.queryByText('工作空间选择页')).not.toBeInTheDocument();
    expect(queryClient.getQueryData(['workitems', { page: 1 }])).toBeUndefined();
    expect(queryClient.getQueryData(['agents', 1, 10])).toBeUndefined();
  });

  it('removes the legacy "切换工作空间" menu item from the user dropdown', async () => {
    useAuthStore.getState().setUser({
      id: 1,
      username: 'alice',
      nickname: '爱丽丝',
      email: 'alice@example.com',
    });
    useAuthStore.getState().setAccessToken('workspace-7-token');
    useAuthStore.getState().setCurrentWorkspace({ id: 7, name: '星云工坊', description: '研发工作空间' }, 'READ_ONLY');

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
    expect(screen.queryByText('切换工作空间')).not.toBeInTheDocument();
    expect(screen.getByText('个人设置')).toBeInTheDocument();
    expect(screen.getByText('退出登录')).toBeInTheDocument();
  });

  it('clears auth state and the entire query cache on logout', async () => {
    useAuthStore.getState().setUser({
      id: 1,
      username: 'alice',
      nickname: '爱丽丝',
      email: 'alice@example.com',
    });
    useAuthStore.getState().setAccessToken('session-token');
    useAuthStore.getState().setCurrentWorkspace({ id: 7, name: '星云工坊', description: '研发工作空间' }, 'ADMIN');

    const { queryClient } = renderWithQueryClient(
      <MemoryRouter initialEntries={['/workitems']}>
        <Routes>
          <Route element={<AppLayout />}>
            <Route path="/workitems" element={<div>工作项页</div>} />
          </Route>
          <Route path="/login" element={<div>登录页</div>} />
        </Routes>
      </MemoryRouter>,
    );
    queryClient.setQueryData(['workspaces', 'mine', 1], [{ id: 7, name: '星云工坊', description: '研发工作空间' }]);
    queryClient.setQueryData(['workitems', { page: 1, size: 20 }], { content: [{ id: 101, title: '租户数据' }] });

    await userEvent.click(screen.getByText('爱丽丝'));
    await userEvent.click(screen.getByText('退出登录'));

    await waitFor(() => {
      expect(screen.getByText('登录页')).toBeInTheDocument();
    });
    expect(useAuthStore.getState().accessToken).toBeNull();
    expect(useAuthStore.getState().currentWorkspace).toBeNull();
    expect(queryClient.getQueryData(['workspaces', 'mine', 1])).toBeUndefined();
    expect(queryClient.getQueryData(['workitems', { page: 1, size: 20 }])).toBeUndefined();
  });

  it('applies truncation constraints for long workspace and user text', () => {
    const longWorkspaceName = '超长工作空间名称'.repeat(12);
    const longNickname = '超长昵称'.repeat(12);
    const longEmail = `${'verylong'.repeat(8)}@example.com`;

    useAuthStore.getState().setUser({
      id: 2,
      username: 'long-user',
      nickname: longNickname,
      email: longEmail,
    });
    useAuthStore.getState().setCurrentWorkspace({ id: 8, name: longWorkspaceName, description: '长工作空间' }, 'READ_ONLY');

    renderWithQueryClient(
      <MemoryRouter initialEntries={['/status-templates']}>
        <Routes>
          <Route element={<AppLayout />}>
            <Route path="/status-templates" element={<div>状态模版页</div>} />
          </Route>
        </Routes>
      </MemoryRouter>,
    );

    const workspaceTexts = screen.getAllByTitle(longWorkspaceName);
    expect(workspaceTexts).toHaveLength(1);
    expect(workspaceTexts[0]).toHaveStyle({ textOverflow: 'ellipsis', whiteSpace: 'nowrap' });
    expect(screen.getByTitle(longNickname)).toHaveStyle({ textOverflow: 'ellipsis', maxWidth: '220px' });
    expect(screen.getByTitle(longEmail)).toHaveStyle({ textOverflow: 'ellipsis', maxWidth: '220px' });
    expect(screen.getByTitle('状态模版')).toHaveStyle({ textOverflow: 'ellipsis', whiteSpace: 'nowrap' });
  });
});
