import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createMemoryRouter, MemoryRouter, RouterProvider } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { createAppRoutes } from '@/app/router';
import { useAuthStore } from '@/shared/auth/store';
import { USER_IM_IDENTITIES_QUERY_KEY } from './profileApi';
import { ProfileSettingsPage } from './ProfileSettingsPage';
import { MCP_PERMISSION_HINT } from '@/features/open-platform/McpTokenSettingsPanel';

const successMock = vi.hoisted(() => vi.fn());
const errorMock = vi.hoisted(() => vi.fn());

vi.mock('antd', async () => {
  const actual = await vi.importActual<typeof import('antd')>('antd');
  return {
    ...actual,
    message: {
      ...actual.message,
      success: successMock,
      error: errorMock,
    },
  };
});

vi.mock('@/shared/ui/NotificationBell', () => ({
  NotificationBell: () => <div data-testid="notification-bell">bell</div>,
}));

function identityPayload(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    success: true,
    code: '0',
    message: '',
    traceId: null,
    data: [
      {
        provider: 'DINGTALK',
        externalUserId: 'staff-42',
        configured: true,
        platformReady: true,
        testAvailable: true,
        ...overrides,
      },
    ],
  };
}

function renderPage(initialEntry = '/profile/settings') {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const utils = render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[initialEntry]}>
        <ProfileSettingsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return { queryClient, ...utils };
}

function mcpPayload(data: unknown) {
  return { success: true, code: '0', message: '', traceId: null, data };
}

function stubMcpEndpoints(tokens: unknown[] = []) {
  server.use(
    http.get('/api/mcp/tokens', () => HttpResponse.json(mcpPayload(tokens))),
    http.get('/api/mcp/tokens/tools', () => HttpResponse.json(mcpPayload([
      {
        name: 'autowonder.get_workitem',
        description: 'Get one AutoWonder workitem by id.',
        inputSchema: {
          type: 'object',
          properties: {
            orgId: { type: 'integer', description: 'Required. Target organization id.' },
            id: { type: 'integer' },
          },
          required: ['orgId', 'id'],
        },
      },
    ]))),
  );
}

function stubMemoryMcpTools() {
  server.use(
    http.get('/api/mcp/tokens', () => HttpResponse.json(mcpPayload([]))),
    http.get('/api/mcp/tokens/tools', () => HttpResponse.json(mcpPayload([
      {
        name: 'autowonder.create_memory',
        description: 'Create a reusable memory.',
        inputSchema: { type: 'object', properties: {}, required: [] },
      },
      {
        name: 'autowonder.search_memories',
        description: 'Search reusable memories.',
        inputSchema: { type: 'object', properties: {}, required: [] },
      },
    ]))),
  );
}

describe('ProfileSettingsPage', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
    successMock.mockClear();
    errorMock.mockClear();
    useAuthStore.getState().clear();
    server.use(
      http.get('/api/users/me/im-identities', () => HttpResponse.json(identityPayload())),
    );
  });

  it('loads existing DINGTALK identity and saves updated staff id', async () => {
    let savedBody: Record<string, unknown> | null = null;
    server.use(
      http.put('/api/users/me/im-identities/dingtalk', async ({ request }) => {
        savedBody = await request.json() as Record<string, unknown>;
        const externalUserId = String(savedBody.externalUserId ?? '');
        return HttpResponse.json({
          ...identityPayload(),
          data: [{ ...identityPayload().data[0], externalUserId }],
        });
      }),
    );

    renderPage();

    const staffInput = await screen.findByLabelText('钉钉工号');
    await waitFor(() => {
      expect(staffInput).toHaveValue('staff-42');
      expect(staffInput).not.toBeDisabled();
    });

    await userEvent.clear(staffInput);
    await userEvent.type(staffInput, 'staff-99');
    await userEvent.click(screen.getByRole('button', { name: /保存/ }));

    await waitFor(() => {
      expect(savedBody).toEqual({ externalUserId: 'staff-99' });
      expect(successMock).toHaveBeenCalledWith('IM 工号已保存');
    });
  });

  it('does not overwrite a dirty staff id when identities refetch', async () => {
    let backendExternalUserId = 'staff-42';
    server.use(
      http.get('/api/users/me/im-identities', () => HttpResponse.json(identityPayload({
        externalUserId: backendExternalUserId,
      }))),
    );
    const { queryClient } = renderPage();

    const staffInput = await screen.findByLabelText('钉钉工号');
    await waitFor(() => {
      expect(staffInput).toHaveValue('staff-42');
      expect(staffInput).not.toBeDisabled();
    });

    await userEvent.clear(staffInput);
    await userEvent.type(staffInput, 'draft-99');

    backendExternalUserId = 'staff-43';
    await queryClient.refetchQueries({ queryKey: USER_IM_IDENTITIES_QUERY_KEY });
    await waitFor(() => {
      expect(queryClient.getQueryData(USER_IM_IDENTITIES_QUERY_KEY)).toMatchObject([
        { externalUserId: 'staff-43' },
      ]);
    });

    expect(staffInput).toHaveValue('draft-99');
  });

  it('disables test button and shows robot not configured hint when test is unavailable', async () => {
    server.use(
      http.get('/api/users/me/im-identities', () => HttpResponse.json(identityPayload({ testAvailable: false, platformReady: false }))),
    );

    renderPage();

    expect(await screen.findByText('系统统一钉钉机器人未配置')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /发送测试/ })).toBeDisabled();
  });

  it('shows save staff id hint instead of robot hint when personal identity is not configured', async () => {
    server.use(
      http.get('/api/users/me/im-identities', () => HttpResponse.json(identityPayload({
        externalUserId: '',
        configured: false,
        platformReady: true,
        testAvailable: false,
      }))),
    );

    renderPage();

    const staffInput = await screen.findByLabelText('钉钉工号');
    await waitFor(() => {
      expect(staffInput).toHaveValue('');
      expect(staffInput).not.toBeDisabled();
    });
    expect(screen.getByText('请先保存钉钉工号后再测试')).toBeInTheDocument();
    expect(screen.queryByText('系统统一钉钉机器人未配置')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /发送测试/ })).toBeDisabled();
  });

  it('shows save staff id hint instead of robot hint when identities list is empty', async () => {
    server.use(
      http.get('/api/users/me/im-identities', () => HttpResponse.json({
        ...identityPayload(),
        data: [],
      })),
    );

    renderPage();

    const staffInput = await screen.findByLabelText('钉钉工号');
    await waitFor(() => {
      expect(staffInput).toHaveValue('');
      expect(staffInput).not.toBeDisabled();
    });
    expect(screen.getByText('请先保存钉钉工号后再测试')).toBeInTheDocument();
    expect(screen.queryByText('系统统一钉钉机器人未配置')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /发送测试/ })).toBeDisabled();
  });

  it('sends one-click test when test is available and staff id is saved', async () => {
    let testCalled = false;
    server.use(
      http.post('/api/users/me/im-identities/dingtalk/test', () => {
        testCalled = true;
        return HttpResponse.json({
          success: true,
          code: '0',
          message: '',
          traceId: null,
          data: null,
        });
      }),
    );

    renderPage();

    await screen.findByDisplayValue('staff-42');
    await userEvent.click(screen.getByRole('button', { name: /发送测试/ }));

    await waitFor(() => {
      expect(testCalled).toBe(true);
      expect(successMock).toHaveBeenCalledWith('测试消息已发送');
    });
  });

  it('saves empty staff id to disable DINGTALK identity', async () => {
    let savedBody: Record<string, unknown> | null = null;
    server.use(
      http.put('/api/users/me/im-identities/dingtalk', async ({ request }) => {
        savedBody = await request.json() as Record<string, unknown>;
        return HttpResponse.json({
          ...identityPayload(),
          data: [{ ...identityPayload().data[0], externalUserId: '', configured: false }],
        });
      }),
    );

    renderPage();

    const staffInput = await screen.findByLabelText('钉钉工号');
    await waitFor(() => {
      expect(staffInput).toHaveValue('staff-42');
      expect(staffInput).not.toBeDisabled();
    });
    await userEvent.clear(staffInput);
    await userEvent.click(screen.getByRole('button', { name: /保存/ }));

    await waitFor(() => {
      expect(savedBody).toEqual({ externalUserId: '' });
      expect(successMock).toHaveBeenCalledWith('IM 工号已保存');
    });
  });

  it('keeps personal settings reachable without a current organization', async () => {
    useAuthStore.getState().setTokens('test-access', 'test-refresh');
    useAuthStore.getState().setUser({
      id: 1,
      username: 'alice',
      nickname: '爱丽丝',
      email: 'alice@example.com',
    });
    server.use(
      http.get('/api/orgs/mine', () => HttpResponse.json({
        success: true,
        code: '0',
        message: '',
        traceId: null,
        data: [],
      })),
    );
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const router = createMemoryRouter(createAppRoutes(), {
      initialEntries: ['/profile/settings'],
    });

    render(
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>,
    );

    await waitFor(() => {
      expect(router.state.location.pathname).toBe('/profile/settings');
    });
    expect(await screen.findByRole('heading', { name: '个人设置' })).toBeInTheDocument();
  });

  it('renders MCP token management inside personal settings', async () => {
    stubMcpEndpoints([
      {
        id: 10,
        name: 'local-codex',
        tokenPrefix: 'awmcp_abcdef',
        lastUsedAt: null,
        revokedAt: null,
        gmtCreate: '2026-08-03 10:00:00',
      },
    ]);

    renderPage('/profile/settings?tab=mcp');

    expect(await screen.findByText('local-codex')).toBeInTheDocument();
    expect(screen.getByText(MCP_PERMISSION_HINT)).toBeInTheDocument();
  });

  it('drops the configured and effective access level columns', async () => {
    stubMcpEndpoints([
      {
        id: 10,
        name: 'local-codex',
        tokenPrefix: 'awmcp_abcdef',
        lastUsedAt: null,
        revokedAt: null,
        gmtCreate: '2026-08-03 10:00:00',
      },
    ]);

    renderPage('/profile/settings?tab=mcp');

    await screen.findByText('local-codex');
    expect(screen.queryByText('配置权限')).not.toBeInTheDocument();
    expect(screen.queryByText('生效权限')).not.toBeInTheDocument();
  });

  it('creates a token with only a name in the request body', async () => {
    let createdBody: unknown = null;
    stubMcpEndpoints();
    server.use(
      http.post('/api/mcp/tokens', async ({ request }) => {
        createdBody = await request.json();
        return HttpResponse.json(mcpPayload({
          id: 11,
          name: 'local-codex',
          tokenPrefix: 'awmcp_abcdef',
          token: 'awmcp_' + 'a'.repeat(43),
        }));
      }),
    );

    renderPage('/profile/settings?tab=mcp');

    const createButton = await screen.findByRole('button', { name: /新建令牌/ });
    await waitFor(() => expect(createButton).toBeEnabled());
    await userEvent.click(createButton);
    expect(await screen.findByText('新建 MCP 令牌')).toBeInTheDocument();
    const nameInput = await screen.findByLabelText('名称');
    await userEvent.clear(nameInput);
    await userEvent.type(nameInput, 'local-codex');
    await userEvent.click(screen.getByRole('button', { name: /创\s*建/ }));

    await waitFor(() => {
      expect(createdBody).toEqual({ name: 'local-codex' });
    });
  });

  it('shows the required orgId parameter in the tool schema', async () => {
    stubMcpEndpoints();

    renderPage('/profile/settings?tab=mcp');

    await userEvent.click(await screen.findByRole('tab', { name: '工具' }));

    expect(await screen.findByText('orgId')).toBeInTheDocument();
  });

  it('groups memory MCP tools under memory management', async () => {
    stubMemoryMcpTools();

    renderPage('/profile/settings?tab=mcp');

    await userEvent.click(await screen.findByRole('tab', { name: '工具' }));

    expect(await screen.findByText('记忆管理')).toBeInTheDocument();
    expect(screen.getByText('autowonder.create_memory')).toBeInTheDocument();
    expect(screen.getByText('autowonder.search_memories')).toBeInTheDocument();
    expect(screen.queryByText('其他能力')).not.toBeInTheDocument();
  });

  it('uses a wider shell for MCP tab than for IM tab', async () => {
    stubMcpEndpoints();

    renderPage('/profile/settings');

    const imShell = await screen.findByTestId('profile-settings-shell');
    expect(imShell.style.maxWidth).toBe('1100px');

    document.body.innerHTML = '';
    renderPage('/profile/settings?tab=mcp');

    const mcpShell = await screen.findByTestId('profile-settings-shell');
    expect(mcpShell.style.maxWidth).toBe('1440px');
  });

  it('renders MCP tools table and schema tables with stable test ids', async () => {
    stubMcpEndpoints();

    renderPage('/profile/settings?tab=mcp');

    await userEvent.click(await screen.findByRole('tab', { name: '工具' }));

    expect(await screen.findByText('autowonder.get_workitem')).toBeInTheDocument();
    expect(screen.getByTestId('mcp-tools-table')).toBeInTheDocument();

    expect(await screen.findByText('orgId')).toBeInTheDocument();
    expect(screen.getByTestId('mcp-input-schema-table')).toBeInTheDocument();
    expect(screen.getByTestId('mcp-output-schema-table')).toBeInTheDocument();
  });

  it('redirects the legacy open platform route into personal settings', async () => {
    useAuthStore.getState().setTokens('test-access', 'test-refresh');
    useAuthStore.getState().setUser({
      id: 1,
      username: 'alice',
      nickname: '爱丽丝',
      email: 'alice@example.com',
    });
    stubMcpEndpoints();
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const router = createMemoryRouter(createAppRoutes(), {
      initialEntries: ['/open-platform'],
    });

    render(
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>,
    );

    await waitFor(() => {
      expect(router.state.location.pathname).toBe('/profile/settings');
      expect(router.state.location.search).toBe('?tab=mcp');
    });
  });
});
