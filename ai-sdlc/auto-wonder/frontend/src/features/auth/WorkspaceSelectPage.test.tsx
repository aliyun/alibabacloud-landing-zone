import { describe, it, expect, beforeEach } from 'vitest';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { useAuthStore } from '@/shared/auth/store';
import { WorkspaceSelectPage } from './WorkspaceSelectPage';

// antd inserts a thin space between exactly two CJK characters in a Button label (`保存` renders
// as `保 存`), so every button lookup goes through a whitespace-tolerant matcher.
function buttonName(label: string) {
  return new RegExp(label.split('').join('\\s*'));
}

const MANAGEABLE_WORKSPACE = {
  id: 1,
  name: '星云工坊',
  description: '多 Agent 研发协作空间',
  background: '云原生研发',
  version: 3,
  canManage: true,
};

function mockMine(data: unknown[]) {
  server.use(
    http.get('/api/workspaces/mine', () => HttpResponse.json({
      success: true,
      code: '0',
      message: '',
      traceId: null,
      data,
    })),
  );
}

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
    expect(screen.getByTestId('workspace-card-shell-1')).toHaveStyle({
      background: '#fff',
      borderColor: '#ff6a00',
      boxShadow: '0 0 0 2px rgba(255, 106, 0, 0.08), 0 14px 28px rgba(255, 106, 0, 0.12)',
    });
    // F1.2: the shell owns the chrome, the inner control only owns the click. The border reset is
    // asserted as 0px because jsdom drops a `border: none` shorthand entirely; see cardEnterStyle.
    expect(screen.getByTestId('workspace-card-1')).toHaveStyle({
      background: 'transparent',
      border: '0px',
      width: '100%',
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

    await userEvent.click(await screen.findByRole('button', { name: /平台配置/ }));

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

  it('shows the manage controls only for workspaces the server marks as manageable', async () => {
    mockMine([
      MANAGEABLE_WORKSPACE,
      { id: 2, name: '只读工作空间', description: '', canManage: false },
      { id: 3, name: '旧接口工作空间', description: '' },
    ]);

    renderPage();

    expect(await screen.findByTestId('workspace-manage-area-1')).toBeInTheDocument();
    expect(screen.getByTestId('edit-workspace-1')).toBeInTheDocument();
    expect(screen.getByTestId('delete-workspace-1')).toBeInTheDocument();
    // `canManage === true` is the only gate: an explicit false and a missing field (a payload
    // from an older backend) both hide the controls, so the UI never offers an edit or a delete
    // that the service would reject with a permission error.
    expect(screen.queryByTestId('workspace-manage-area-2')).not.toBeInTheDocument();
    expect(screen.queryByTestId('workspace-manage-area-3')).not.toBeInTheDocument();
    expect(screen.queryByTestId('edit-workspace-2')).not.toBeInTheDocument();
    expect(screen.queryByTestId('delete-workspace-3')).not.toBeInTheDocument();
  });

  it('keeps the manage controls out of the enter control so managing cannot enter the workspace', async () => {
    let switchCalls = 0;
    mockMine([MANAGEABLE_WORKSPACE]);
    server.use(
      http.post('/api/workspaces/1/switch', () => {
        switchCalls += 1;
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: { accessToken: 'workspace-1-token', accessLevel: 'ADMIN' },
        });
      }),
    );
    const user = userEvent.setup();

    renderPage();

    const enter = await screen.findByTestId('workspace-card-1');
    const manageArea = screen.getByTestId('workspace-manage-area-1');
    // F1.2: a control nested inside a <button> is invalid HTML, and its click would bubble into
    // the card's enter handler and switch workspace instead of opening the dialog.
    expect(enter).not.toContainElement(manageArea);
    expect(enter).not.toContainElement(screen.getByTestId('edit-workspace-1'));
    expect(enter).not.toContainElement(screen.getByTestId('delete-workspace-1'));
    expect(manageArea.parentElement).toBe(enter.parentElement);
    // In normal flow under the enter control rather than absolutely positioned over it, so it
    // neither covers the card nor swallows its clicks (acceptance #18).
    expect(manageArea).not.toHaveStyle({ position: 'absolute' });
    expect(manageArea).toHaveStyle({ display: 'flex', justifyContent: 'flex-end' });

    await user.click(screen.getByTestId('edit-workspace-1'));
    expect(await screen.findByText('编辑「星云工坊」')).toBeInTheDocument();
    expect(switchCalls).toBe(0);
    expect(useAuthStore.getState().accessToken).toBeNull();
  });

  it('prefills the edit dialog and shows the new name and description right after saving', async () => {
    let savedBody: Record<string, unknown> | null = null;
    let mineCalls = 0;
    server.use(
      http.get('/api/workspaces/mine', () => {
        mineCalls += 1;
        // Echoes the save so the assertion below can only pass if the list was really re-read.
        const saved = savedBody as { name?: string; description?: string } | null;
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: [{
            ...MANAGEABLE_WORKSPACE,
            name: saved ? saved.name : MANAGEABLE_WORKSPACE.name,
            description: saved ? saved.description : MANAGEABLE_WORKSPACE.description,
            version: saved ? 4 : 3,
          }],
        });
      }),
      http.put('/api/workspaces/1', async ({ request }) => {
        savedBody = await request.json() as Record<string, unknown>;
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null, data: { id: 1 },
        });
      }),
    );
    const user = userEvent.setup();

    renderPage();

    await user.click(await screen.findByTestId('edit-workspace-1'));

    // F1.3 回显: all three stored fields land in the dialog, including the background that the
    // card itself never renders.
    const nameInput = await screen.findByPlaceholderText('输入工作空间名称');
    expect(nameInput).toHaveValue('星云工坊');
    const descriptionInput = screen.getByPlaceholderText('简要描述工作空间用途');
    expect(descriptionInput).toHaveValue('多 Agent 研发协作空间');
    expect(screen.getByPlaceholderText('工作空间的行业背景、技术栈、团队规模等信息'))
      .toHaveValue('云原生研发');

    const mineBefore = mineCalls;
    await user.clear(nameInput);
    await user.type(nameInput, '星云工坊 2');
    await user.clear(descriptionInput);
    await user.type(descriptionInput, '改名后的描述');
    await user.click(screen.getByRole('button', { name: buttonName('保存') }));

    await waitFor(() => expect(savedBody).not.toBeNull());
    // F1.5: the optimistic-lock version travels with the save, taken from the row as loaded.
    expect(savedBody).toEqual({
      name: '星云工坊 2',
      description: '改名后的描述',
      background: '云原生研发',
      version: 3,
    });

    // F1.3: the dialog closes and the list shows the new name and description with no manual
    // refresh, because the save invalidates the shared ['workspaces'] prefix.
    await waitFor(() => expect(screen.queryByText('编辑「星云工坊」')).not.toBeInTheDocument());
    expect(mineCalls).toBeGreaterThan(mineBefore);
    expect(await screen.findByText('星云工坊 2')).toBeInTheDocument();
    expect(await screen.findByText('改名后的描述')).toBeInTheDocument();
    expect(screen.queryByText('多 Agent 研发协作空间')).not.toBeInTheDocument();
  });

  it('opens the delete dialog from the card and states all three consequences', async () => {
    let switchCalls = 0;
    let deleteCalls = 0;
    mockMine([MANAGEABLE_WORKSPACE]);
    server.use(
      http.post('/api/workspaces/1/switch', () => {
        switchCalls += 1;
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: { accessToken: 'workspace-1-token', accessLevel: 'ADMIN' },
        });
      }),
      http.delete('/api/workspaces/1', () => {
        deleteCalls += 1;
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null, data: { id: 1 },
        });
      }),
    );
    const user = userEvent.setup();

    renderPage();

    await user.click(await screen.findByTestId('delete-workspace-1'));

    expect(await screen.findByText('删除「星云工坊」')).toBeInTheDocument();
    // F2.1: the confirmation must name the recycle bin, the loss of access, and the fact that
    // nothing is physically destroyed -- in that order, as three separate list items.
    expect(
      within(screen.getByTestId('workspace-delete-consequences'))
        .getAllByRole('listitem')
        .map((item) => item.textContent),
    ).toEqual([
      '删除后该工作空间将移入回收站。',
      '成员暂时无法进入或访问该工作空间。',
      '数据不会物理删除，可通过回收站恢复。',
    ]);
    // Opening the dialog neither switches nor deletes.
    expect(switchCalls).toBe(0);
    expect(deleteCalls).toBe(0);
  });

  it('drops the workspace binding when the workspace bound to the token is deleted', async () => {
    useAuthStore.getState().setTokens('bound-token', 'refresh-token');
    useAuthStore.getState().setCurrentWorkspace(
      { id: 1, name: '星云工坊', description: '' },
      'ADMIN',
    );
    const deletedIds: number[] = [];
    mockMine([MANAGEABLE_WORKSPACE]);
    server.use(
      http.delete('/api/workspaces/:id', ({ params }) => {
        deletedIds.push(Number(params.id));
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null, data: { id: Number(params.id) },
        });
      }),
    );
    const user = userEvent.setup();

    renderPage();

    await user.click(await screen.findByTestId('delete-workspace-1'));
    await user.click(await screen.findByRole('button', { name: buttonName('确认删除') }));

    await waitFor(() => expect(deletedIds).toEqual([1]));
    // F6: AuthFilter now rejects every workspace-scoped call made with this token, so the binding
    // goes at the moment of deletion instead of after the first failed request.
    await waitFor(() => {
      expect(useAuthStore.getState().currentWorkspace).toBeNull();
      expect(useAuthStore.getState().accessLevel).toBeNull();
    });
    // The session itself survives: the user is still logged in and only has to pick another
    // workspace, which is why the tokens are deliberately kept.
    expect(useAuthStore.getState().accessToken).toBe('bound-token');
    expect(useAuthStore.getState().refreshToken).toBe('refresh-token');
  });

  it('keeps the current workspace binding when a different workspace is deleted', async () => {
    useAuthStore.getState().setTokens('bound-token', 'refresh-token');
    useAuthStore.getState().setCurrentWorkspace(
      { id: 2, name: '云效集成平台', description: '' },
      'READ_WRITE',
    );
    mockMine([MANAGEABLE_WORKSPACE, { id: 2, name: '云效集成平台', description: '', canManage: true }]);
    server.use(
      http.delete('/api/workspaces/:id', ({ params }) => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: { id: Number(params.id) },
      })),
    );
    const user = userEvent.setup();

    renderPage();

    await user.click(await screen.findByTestId('delete-workspace-1'));
    await user.click(await screen.findByRole('button', { name: buttonName('确认删除') }));

    await waitFor(() => expect(screen.queryByText('删除「星云工坊」')).not.toBeInTheDocument());
    // Deleting a workspace the token is not bound to must not sign the user out of the one they
    // are actually working in.
    expect(useAuthStore.getState().currentWorkspace?.id).toBe(2);
    expect(useAuthStore.getState().accessLevel).toBe('READ_WRITE');
    expect(useAuthStore.getState().accessToken).toBe('bound-token');
  });

  it('routes to the recycle bin from a real bottom-right entry', async () => {
    mockMine([MANAGEABLE_WORKSPACE]);
    const user = userEvent.setup();

    renderPageWithLocation();

    const entry = await screen.findByTestId('workspace-recycle-bin-entry');
    // F4.1: an interactive button with an icon and a label, not a decorative element.
    expect(entry).toHaveAttribute('type', 'button');
    expect(entry).toHaveAccessibleName('打开工作空间回收站');
    expect(entry).toHaveTextContent('工作空间回收站');
    // Right-aligned in its own bar below the list, so it never overlays a card (acceptance #18).
    expect(entry.parentElement).toHaveStyle({ display: 'flex', justifyContent: 'flex-end' });
    expect(entry).not.toHaveStyle({ position: 'fixed' });

    await user.click(entry);

    await waitFor(() => {
      expect(screen.getByTestId('location-path')).toHaveTextContent('/workspaces/recycle-bin');
    });
  });
});
