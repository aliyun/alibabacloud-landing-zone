import { describe, it, expect, beforeEach } from 'vitest';
import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { useAuthStore } from '@/shared/auth/store';
import { WorkspaceSelectPage } from './WorkspaceSelectPage';

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return {
    queryClient,
    ...render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter><WorkspaceSelectPage /></MemoryRouter>
      </QueryClientProvider>,
    ),
  };
}

function LocationProbe() {
  const location = useLocation();
  return <span data-testid="location-path">{location.pathname}</span>;
}

function renderPageWithLocation() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/workspaces']}>
        <WorkspaceSelectPage />
        <LocationProbe />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('WorkspaceSelectPage', () => {
  beforeEach(() => {
    useAuthStore.getState().clear();
  });

  it('renders workspace as orange-white square cards', async () => {
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: '星云工坊', description: '研发工作空间' }, 'READ_ONLY');
    server.use(
      http.get('/api/workspaces/mine', () => HttpResponse.json({
        success: true,
        code: '0',
        message: '',
        traceId: null,
        data: [
          { id: 1, name: '星云工坊', description: '多 Agent 研发协作空间' },
          { id: 2, name: '云效集成平台', description: '连接 Aone 工单与执行器集群' },
        ],
      })),
    );

    renderPage();

    expect(await screen.findByText('云效集成平台')).toBeInTheDocument();
    expect(screen.getAllByText('星云工坊').length).toBeGreaterThan(0);
    expect(screen.getByTestId('workspace-select-grid')).toHaveStyle({
      display: 'grid',
    });
    expect(screen.getByTestId('workspace-card-1')).toHaveStyle({
      background: '#fff',
      borderColor: '#ff6a00',
      boxShadow: '0 0 0 2px rgba(255, 106, 0, 0.08), 0 14px 28px rgba(255, 106, 0, 0.12)',
    });
    expect(screen.getByTestId('workspace-create-card')).toBeInTheDocument();
  });

  it('stores the access level returned when switching workspace', async () => {
    const user = userEvent.setup();
    server.use(
      http.get('/api/workspaces/mine', () => HttpResponse.json({
        success: true,
        code: '0',
        message: '',
        traceId: null,
        data: [{ id: 2, name: '云效集成平台', description: '研发工作空间' }],
      })),
      http.post('/api/workspaces/2/switch', () => HttpResponse.json({
        success: true,
        code: '0',
        message: '',
        traceId: null,
        data: {
          accessToken: 'workspace-access-token',
          accessLevel: 'ADMIN',
        },
      })),
    );

    renderPage();
    await user.click(await screen.findByTestId('workspace-card-2'));

    await waitFor(() => {
      expect(useAuthStore.getState().accessToken).toBe('workspace-access-token');
      expect(useAuthStore.getState().currentWorkspace?.id).toBe(2);
      expect(useAuthStore.getState().accessLevel).toBe('ADMIN');
    });
  });

  it('links to global branding settings outside workspace workspaces', async () => {
    server.use(
      http.get('/api/workspaces/mine', () => HttpResponse.json({
        success: true,
        code: '0',
        message: '',
        traceId: null,
        data: [{ id: 2, name: '云效集成平台', description: '研发工作空间' }],
      })),
    );

    renderPageWithLocation();

    await userEvent.click(await screen.findByRole('button', { name: /品牌配置/ }));

    await waitFor(() => {
      expect(screen.getByTestId('location-path')).toHaveTextContent('/workspaces/branding');
    });
  });

  it('clears cached workitem lists after switching workspace', async () => {
    useAuthStore.getState().setCurrentWorkspace(
      { id: 1, name: '星云工坊', description: '研发工作空间' },
      'READ_ONLY',
    );
    server.use(
      http.get('/api/workspaces/mine', () => HttpResponse.json({
        success: true,
        code: '0',
        message: '',
        traceId: null,
        data: [
          { id: 1, name: '星云工坊', description: '多 Agent 研发协作空间' },
          { id: 2, name: '云效集成平台', description: '连接 Aone 工单与执行器集群' },
        ],
      })),
      http.post('/api/workspaces/2/switch', () => HttpResponse.json({
        success: true,
        code: '0',
        message: '',
        traceId: null,
        data: { accessToken: 'workspace-2-token', accessLevel: 'READ_ONLY' },
      })),
    );
    const { queryClient } = renderPage();
    queryClient.setQueryData(['workitems', { page: 1, size: 20 }], { content: [{ id: 101, title: '旧工作空间工单' }] });
    queryClient.setQueryData(['workitem', '101'], { id: 101, title: '旧工作空间详情' });

    const targetWorkspace = await screen.findByTestId('workspace-card-2');
    await act(async () => {
      await userEvent.click(targetWorkspace);
    });

    expect(queryClient.getQueryData(['workitems', { page: 1, size: 20 }])).toBeUndefined();
    expect(queryClient.getQueryData(['workitem', '101'])).toBeUndefined();
    expect(useAuthStore.getState().currentWorkspace?.id).toBe(2);
    expect(useAuthStore.getState().accessToken).toBe('workspace-2-token');
  });

  it('renders both tabs and keeps 我的工作空间 active with the own-workspaces grid', async () => {
    server.use(
      http.get('/api/workspaces/mine', () => HttpResponse.json({
        success: true,
        code: '0',
        message: '',
        traceId: null,
        data: [{ id: 1, name: '星云工坊', description: '多 Agent 研发协作空间' }],
      })),
    );

    renderPage();

    expect(screen.getByRole('tab', { name: '我的工作空间' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: '所有工作空间' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: '我的工作空间' })).toHaveAttribute('aria-selected', 'true');
    expect(await screen.findByTestId('workspace-select-grid')).toBeInTheDocument();
    expect(await screen.findByText('星云工坊')).toBeInTheDocument();
  });

  it('does not fetch all workspaces until the discovery tab is opened', async () => {
    const user = userEvent.setup();
    let allWorkspacesRequests = 0;
    server.use(
      http.get('/api/workspaces/mine', () => HttpResponse.json({
        success: true,
        code: '0',
        message: '',
        traceId: null,
        data: [{ id: 1, name: '星云工坊', description: '多 Agent 研发协作空间' }],
      })),
      http.get('/api/workspaces/all', () => {
        allWorkspacesRequests += 1;
        return HttpResponse.json({
          success: true,
          code: '0',
          message: '',
          traceId: null,
          data: {
            list: [{ id: 9, name: '公开空间', description: '', membershipStatus: 'NOT_MEMBER', accessLevel: null }],
            total: 1,
            pageNum: 1,
            pageSize: 20,
          },
        });
      }),
    );

    renderPage();

    expect(await screen.findByText('星云工坊')).toBeInTheDocument();
    expect(allWorkspacesRequests).toBe(0);

    await user.click(screen.getByRole('tab', { name: '所有工作空间' }));

    expect(await screen.findByText('共 1 个工作空间')).toBeInTheDocument();
    expect(allWorkspacesRequests).toBe(1);
  });

  it('shows my workspaces again after switching back from the discovery tab', async () => {
    const user = userEvent.setup();
    server.use(
      http.get('/api/workspaces/mine', () => HttpResponse.json({
        success: true,
        code: '0',
        message: '',
        traceId: null,
        data: [{ id: 1, name: '星云工坊', description: '多 Agent 研发协作空间' }],
      })),
      http.get('/api/workspaces/all', () => HttpResponse.json({
        success: true,
        code: '0',
        message: '',
        traceId: null,
        data: {
          list: [{ id: 9, name: '公开空间', description: '', membershipStatus: 'NOT_MEMBER', accessLevel: null }],
          total: 1,
          pageNum: 1,
          pageSize: 20,
        },
      })),
    );

    renderPage();

    await user.click(await screen.findByRole('tab', { name: '所有工作空间' }));
    expect(await screen.findByText('共 1 个工作空间')).toBeInTheDocument();

    await user.click(screen.getByRole('tab', { name: '我的工作空间' }));

    expect(await screen.findByTestId('workspace-select-grid')).toBeVisible();
    expect(screen.getByText('星云工坊')).toBeVisible();
    expect(screen.getByText('共 1 个工作空间')).not.toBeVisible();
  });

  it('opens the create-workspace form inside the mine pane', async () => {
    const user = userEvent.setup();
    server.use(
      http.get('/api/workspaces/mine', () => HttpResponse.json({
        success: true,
        code: '0',
        message: '',
        traceId: null,
        data: [{ id: 1, name: '星云工坊', description: '多 Agent 研发协作空间' }],
      })),
    );

    renderPage();

    await user.click(await screen.findByTestId('workspace-create-card'));

    expect(screen.getByText('工作空间名称')).toBeInTheDocument();
    expect(screen.getByPlaceholderText('输入工作空间名称')).toBeInTheDocument();
  });

  it('shows the empty state with a create card when the user has no workspaces', async () => {
    server.use(
      http.get('/api/workspaces/mine', () => HttpResponse.json({
        success: true,
        code: '0',
        message: '',
        traceId: null,
        data: [],
      })),
    );

    renderPage();

    expect(await screen.findByText('暂无已加入的工作空间，请创建一个')).toBeInTheDocument();
    expect(screen.getByTestId('workspace-create-card')).toBeInTheDocument();
    expect(screen.queryByTestId('workspace-card-1')).not.toBeInTheDocument();
  });

  it('refetches the workspace list for the new account without showing the previous account data', async () => {
    useAuthStore.getState().setUser({
      id: 1,
      username: 'alice',
      nickname: '爱丽丝',
      email: 'alice@example.com',
    });
    server.use(
      http.get('/api/workspaces/mine', () => HttpResponse.json({
        success: true,
        code: '0',
        message: '',
        traceId: null,
        data: [{ id: 11, name: '账号A的工作空间', description: '账号A的数据' }],
      })),
    );

    renderPage();
    expect(await screen.findByText('账号A的工作空间')).toBeInTheDocument();

    server.use(
      http.get('/api/workspaces/mine', () => HttpResponse.json({
        success: true,
        code: '0',
        message: '',
        traceId: null,
        data: [{ id: 21, name: '账号B的工作空间', description: '账号B的数据' }],
      })),
    );

    act(() => {
      useAuthStore.getState().clear();
      useAuthStore.getState().setUser({
        id: 2,
        username: 'bob',
        nickname: '鲍勃',
        email: 'bob@example.com',
      });
    });

    expect(screen.queryByText('账号A的工作空间')).not.toBeInTheDocument();
    expect(await screen.findByText('账号B的工作空间')).toBeInTheDocument();
  });
});
