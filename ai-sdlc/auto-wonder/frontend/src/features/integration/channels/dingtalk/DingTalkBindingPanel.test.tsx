import { beforeEach, describe, it, expect, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { DINGTALK_DEFAULT_BASE_URL } from './dingtalkApi';
import { DingTalkBindingPanel } from './DingTalkBindingPanel';
import { useAuthStore } from '@/shared/auth/store';

function renderPanel() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={createMemoryRouter([{ path: '/', element: <DingTalkBindingPanel /> }], { initialEntries: ['/'] })} />
    </QueryClientProvider>,
  );
}

function ok<T>(data: T) {
  return { success: true, code: '0', message: '', traceId: null, data };
}

const agentsBody = ok([
  {
    id: 7,
    name: 'Alpha',
    avatarUrl: null,
    status: 'ONLINE',
    onlineVersionId: null,
    editingVersionId: null,
    latestVersionNo: 1,
    version: 1,
    gmtCreate: '2026-07-01',
    roleName: '评审工程师',
  },
]);

describe('DingTalkBindingPanel', () => {
  beforeEach(() => {
    useAuthStore.getState().clear();
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'ADMIN');
  });

  it('renders existing bindings with the compact identity column and linked agent name', async () => {
    server.use(
      http.get('/api/integrations/dingtalk/bindings', () =>
        HttpResponse.json(
          ok([
            {
              id: 1,
              appKey: 'dingkey',
              appSecretMasked: '••••1234',
              robotCode: 'robot_A',
              agentId: 7,
              transportMode: 'HTTP_CALLBACK',
              streamEnv: 'ONLINE',
              streamStatus: 'NOT_CONNECTED',
              streamError: null,
              streamStatusUpdatedAt: null,
              baseUrl: null,
              regionId: null,
              status: 'ENABLED',
              lastSuccessAt: '2026-07-20T10:00:00Z',
              lastError: null,
              callbackUrl: null,
            },
          ]),
        ),
      ),
      http.get('/api/agents', () => HttpResponse.json(agentsBody)),
    );
    renderPanel();
    expect(await screen.findByText('dingkey')).toBeInTheDocument();
    expect(await screen.findByText('Alpha（评审工程师）')).toBeInTheDocument();
    // Technical health lives in the read-only detail drawer, not the list.
    expect(screen.queryByText('正常')).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: '详情' }));
    const drawer = within(await screen.findByRole('dialog'));
    expect(await drawer.findByText(/健康: 正常/)).toBeInTheDocument();
    expect(drawer.getByText(/robot_A|dingkey/)).toBeInTheDocument();
  });

  it('renders Redis-backed connected health for stream bindings in the detail drawer', async () => {
    server.use(
      http.get('/api/integrations/dingtalk/bindings', () =>
        HttpResponse.json(
          ok([
            {
              id: 1,
              appKey: 'dingkey',
              appSecretMasked: '••••1234',
              robotCode: 'robot_stream',
              agentId: 7,
              transportMode: 'STREAM',
              streamEnv: 'ONLINE',
              streamStatus: 'CONNECTED',
              streamError: null,
              streamStatusUpdatedAt: 1784810000000,
              baseUrl: null,
              regionId: null,
              status: 'ENABLED',
              lastSuccessAt: null,
              lastError: null,
              callbackUrl: null,
            },
          ]),
        ),
      ),
      http.get('/api/agents', () => HttpResponse.json(agentsBody)),
    );
    renderPanel();

    expect(await screen.findByText('dingkey')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: '详情' }));
    const drawer = within(await screen.findByRole('dialog'));
    expect(await drawer.findByText(/已连接钉钉服务/)).toBeInTheDocument();
  });

  it('creates a shared group-chat binding, closes the form and never asks for a confirmation', async () => {
    const user = userEvent.setup();
    let postedBody: Record<string, unknown> | null = null;
    server.use(
      http.get('/api/integrations/dingtalk/bindings', () => HttpResponse.json(ok([]))),
      http.get('/api/agents', () => HttpResponse.json(agentsBody)),
      http.post('/api/integrations/dingtalk/bindings', async ({ request }) => {
        postedBody = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json(
          ok({
            id: 2,
            bindingMode: 'SHARED',
            platformConfigId: 11,
            groupId: 'cid_group',
            routeVersion: 3,
            version: 0,
            appKey: 'dingkey',
            appSecretMasked: null,
            robotCode: 'robot_B',
            agentId: 7,
            transportMode: 'STREAM',
            streamEnv: 'ONLINE',
            streamStatus: 'NOT_CONNECTED',
            streamError: null,
            streamStatusUpdatedAt: null,
            baseUrl: null,
            regionId: null,
            status: 'ENABLED',
            lastSuccessAt: null,
            lastError: null,
            callbackUrl: null,
          }),
        );
      }),
    );
    renderPanel();

    await user.click(await screen.findByText('新建绑定'));
    expect(await screen.findByText('先获取群 ID')).toBeInTheDocument();
    expect(screen.getByText('② 接着输入“群信息”三个字并发送。')).toBeInTheDocument();
    await user.type(await screen.findByLabelText('群 ID'), 'cid_group');
    await user.click(screen.getByRole('combobox', { name: '关联数字员工' }));
    await user.click(await screen.findByText('Alpha（评审工程师）'));
    await user.click(screen.getByRole('button', { name: /保\s*存/ }));

    // Saving is enough: the create form closes, the list refreshes and no confirmation step appears.
    await vi.waitFor(() => expect(screen.queryByRole('dialog', { name: '新建绑定' })).not.toBeInTheDocument());
    expect(screen.queryByRole('dialog', { name: /等待群内确认/ })).not.toBeInTheDocument();
    expect(postedBody).toMatchObject({ bindingMode: 'SHARED', groupId: 'cid_group', agentId: 7, status: 'ENABLED' });
    expect(postedBody).not.toHaveProperty('appKey');
  });

  it('requires a group and agent for shared creation without asking for legacy credentials', async () => {
    const user = userEvent.setup();
    server.use(
      http.get('/api/integrations/dingtalk/bindings', () => HttpResponse.json(ok([]))),
      http.get('/api/agents', () => HttpResponse.json(agentsBody)),
    );
    renderPanel();

    await user.click(await screen.findByText('新建绑定'));
    await user.click(screen.getByRole('button', { name: /保\s*存/ }));

    expect(await screen.findByText('请填写群 ID')).toBeInTheDocument();
    expect(screen.getByText('请选择关联数字员工')).toBeInTheDocument();
    expect(screen.queryByLabelText('appKey')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('robotCode')).not.toBeInTheDocument();
  });

  it('keeps legacy edit with its credentials and advanced transport settings', async () => {
    const user = userEvent.setup();
    server.use(
      http.get('/api/integrations/dingtalk/bindings', () => HttpResponse.json(ok([
        {
          id: 5,
          bindingMode: 'LEGACY',
          groupId: null,
          appKey: 'dingkey',
          appSecretMasked: '••••1234',
          robotCode: 'robot_A',
          agentId: 7,
          transportMode: 'STREAM',
          streamEnv: 'ONLINE',
          streamStatus: 'NOT_CONNECTED',
          streamError: null,
          streamStatusUpdatedAt: null,
          baseUrl: null,
          regionId: null,
          status: 'ENABLED',
          lastSuccessAt: null,
          lastError: null,
          callbackUrl: null,
        },
      ]))),
      http.get('/api/agents', () => HttpResponse.json(agentsBody)),
    );
    renderPanel();

    await user.click(await screen.findByRole('button', { name: /编\s*辑/ }));
    expect(screen.getByLabelText('appKey')).toHaveValue('dingkey');
    expect(screen.getByLabelText('appSecret（留空=不修改）')).toHaveValue('');
    await user.click(screen.getByText('高级设置（传输方式 / 网关地址 / regionId）'));
    expect(screen.getAllByText('Stream').length).toBeGreaterThan(0);
    expect(screen.getByPlaceholderText(DINGTALK_DEFAULT_BASE_URL)).toBeInTheDocument();
    expect(screen.queryByLabelText('群 ID')).not.toBeInTheDocument();
  });

  it('saves shared edits with the row version from the list', async () => {
    const user = userEvent.setup();
    let savedBody: Record<string, unknown> | null = null;
    const shared = {
      id: 6,
      bindingMode: 'SHARED',
      groupId: 'cid_versioned',
      agentId: 7,
      version: 2,
      status: 'ENABLED',
      appKey: '',
      robotCode: '',
      transportMode: 'STREAM',
      streamEnv: 'ONLINE',
      callbackUrl: null,
    };
    server.use(
      http.get('/api/integrations/dingtalk/bindings', () => HttpResponse.json(ok([shared]))),
      http.get('/api/agents', () => HttpResponse.json(agentsBody)),
      http.put('/api/integrations/dingtalk/bindings/6', async ({ request }) => {
        savedBody = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json(ok({ ...shared, version: 3 }));
      }),
    );
    renderPanel();

    await screen.findByText('cid_versioned');
    await user.click(screen.getByRole('button', { name: /编\s*辑/ }));
    const drawer = within(await screen.findByRole('dialog', { name: '编辑绑定' }));
    await user.click(drawer.getByRole('button', { name: /保\s*存/ }));

    await vi.waitFor(() => expect(savedBody).toMatchObject({
      bindingMode: 'SHARED',
      groupId: 'cid_versioned',
      agentId: 7,
      version: 2,
    }));
  });

  it('deletes a binding from the action column only after the confirmation is accepted', async () => {
    const user = userEvent.setup();
    let deleted = 0;
    let gone = false;
    const row = {
      id: 9,
      bindingMode: 'SHARED',
      groupId: 'cid_row_delete',
      agentId: 7,
      version: 1,
      status: 'ENABLED',
      appKey: 'dingkey',
      robotCode: 'robot_A',
      transportMode: 'STREAM',
      streamEnv: 'ONLINE',
      callbackUrl: null,
    };
    server.use(
      http.get('/api/integrations/dingtalk/bindings', () => HttpResponse.json(ok(gone ? [] : [row]))),
      http.get('/api/agents', () => HttpResponse.json(agentsBody)),
      http.delete('/api/integrations/dingtalk/bindings/9', () => {
        deleted += 1;
        gone = true;
        return HttpResponse.json(ok(null));
      }),
    );
    renderPanel();

    await screen.findByText('cid_row_delete');
    const target = screen.getByText('cid_row_delete').closest('tr') as HTMLElement;
    await user.click(within(target).getByRole('button', { name: /删\s*除/ }));
    const popup = within(await screen.findByRole('tooltip'));
    expect(popup.getByText('确认删除该绑定？')).toBeInTheDocument();
    expect(popup.getByText('删除该绑定会立即停止其群聊服务，且不可恢复。')).toBeInTheDocument();
    await user.click(popup.getByRole('button', { name: '删除绑定' }));

    await vi.waitFor(() => expect(deleted).toBe(1));
    await vi.waitFor(() => expect(screen.queryByText('cid_row_delete')).not.toBeInTheDocument());
  });

  it('keeps the row when the action-column deletion is cancelled', async () => {
    const user = userEvent.setup();
    let deleted = 0;
    server.use(
      http.get('/api/integrations/dingtalk/bindings', () => HttpResponse.json(ok([
        {
          id: 9,
          bindingMode: 'SHARED',
          groupId: 'cid_keep',
          agentId: 7,
          version: 1,
          status: 'ENABLED',
          appKey: 'dingkey',
          robotCode: 'robot_A',
          transportMode: 'STREAM',
          streamEnv: 'ONLINE',
          callbackUrl: null,
        },
      ]))),
      http.get('/api/agents', () => HttpResponse.json(agentsBody)),
      http.delete('/api/integrations/dingtalk/bindings/9', () => {
        deleted += 1;
        return HttpResponse.json(ok(null));
      }),
    );
    renderPanel();

    await screen.findByText('cid_keep');
    const target = screen.getByText('cid_keep').closest('tr') as HTMLElement;
    await user.click(within(target).getByRole('button', { name: /删\s*除/ }));
    const popup = within(await screen.findByRole('tooltip'));
    await user.click(popup.getByRole('button', { name: /取\s*消/ }));

    expect(deleted).toBe(0);
    expect(screen.getByText('cid_keep')).toBeInTheDocument();
  });

  it('still deletes only from read-only details after a second confirmation', async () => {
    const user = userEvent.setup();
    let deleted = 0;
    server.use(
      http.get('/api/integrations/dingtalk/bindings', () => HttpResponse.json(ok([
        {
          id: 9,
          bindingMode: 'SHARED',
          groupId: 'cid_delete',
          agentId: 7,
          version: 1,
          status: 'ENABLED',
          appKey: 'dingkey',
          robotCode: 'robot_A',
          transportMode: 'STREAM',
          streamEnv: 'ONLINE',
          callbackUrl: null,
        },
      ]))),
      http.get('/api/agents', () => HttpResponse.json(agentsBody)),
      http.delete('/api/integrations/dingtalk/bindings/9', () => {
        deleted += 1;
        return HttpResponse.json(ok(null));
      }),
    );
    renderPanel();

    await screen.findByText('cid_delete');
    await user.click(screen.getByRole('button', { name: '详情' }));
    const detail = within(await screen.findByRole('dialog', { name: '绑定详情' }));
    expect(detail.getByText('cid_delete')).toBeInTheDocument();
    await user.click(detail.getByRole('button', { name: '删除绑定' }));
    const confirm = await screen.findByRole('tooltip');
    await user.click(within(confirm).getByRole('button', { name: '删除绑定' }));

    await vi.waitFor(() => expect(deleted).toBe(1));
  });

  it('blocks row deletion for read-only members before any request', async () => {
    const user = userEvent.setup();
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_ONLY');
    let deleted = 0;
    server.use(
      http.get('/api/integrations/dingtalk/bindings', () => HttpResponse.json(ok([
        {
          id: 9,
          bindingMode: 'SHARED',
          groupId: 'cid_read_only',
          agentId: 7,
          version: 1,
          status: 'ENABLED',
          appKey: 'dingkey',
          robotCode: 'robot_A',
          transportMode: 'STREAM',
          streamEnv: 'ONLINE',
          callbackUrl: null,
        },
      ]))),
      http.get('/api/agents', () => HttpResponse.json(agentsBody)),
      http.delete('/api/integrations/dingtalk/bindings/9', () => {
        deleted += 1;
        return HttpResponse.json(ok(null));
      }),
    );
    renderPanel();

    await screen.findByText('cid_read_only');
    const target = screen.getByText('cid_read_only').closest('tr') as HTMLElement;
    await user.click(within(target).getByRole('button', { name: /删\s*除/ }));
    const popup = within(await screen.findByRole('tooltip'));
    await user.click(popup.getByRole('button', { name: '删除绑定' }));

    expect(await screen.findByText('当前为只读权限，删除绑定需要管理员权限')).toBeInTheDocument();
    expect(deleted).toBe(0);
  });

  it('uses the application identity instead of inventing a group id for legacy rows', async () => {
    const user = userEvent.setup();
    server.use(
      http.get('/api/integrations/dingtalk/bindings', () => HttpResponse.json(ok([
        {
          id: 5,
          bindingMode: 'LEGACY',
          groupId: null,
          appKey: 'legacy-app',
          robotCode: 'robot_A',
          agentId: 7,
          status: 'ENABLED',
          transportMode: 'STREAM',
          streamEnv: 'ONLINE',
          streamStatus: 'NOT_CONNECTED',
          callbackUrl: null,
        },
      ]))),
      http.get('/api/agents', () => HttpResponse.json(agentsBody)),
    );
    renderPanel();

    await screen.findByText('legacy-app');
    await user.click(screen.getByRole('button', { name: '详情' }));
    const detail = within(await screen.findByRole('dialog', { name: '绑定详情' }));
    expect(detail.getByText('无群 ID（按应用身份接入）')).toBeInTheDocument();
    expect(detail.queryByText('群确认')).not.toBeInTheDocument();
  });

  it('keeps create visible but blocks non-admin users before opening the drawer', async () => {
    const user = userEvent.setup();
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_ONLY');
    server.use(
      http.get('/api/integrations/dingtalk/bindings', () => HttpResponse.json(ok([]))),
      http.get('/api/agents', () => HttpResponse.json(agentsBody)),
    );
    renderPanel();

    const createButton = await screen.findByRole('button', { name: /新建绑定/ });
    expect(createButton).toBeEnabled();
    await user.click(createButton);

    expect(screen.queryByRole('dialog', { name: '新建绑定' })).not.toBeInTheDocument();
    expect(await screen.findByText('当前为只读权限，新建钉钉绑定需要管理员权限')).toBeInTheDocument();
  });
  it('opens the existing same-group binding after an uncertain create instead of creating again', async () => {
    const user = userEvent.setup();
    let posts = 0;
    const existing = {
      id: 11,
      bindingMode: 'SHARED',
      platformConfigId: 1,
      groupId: 'cid_group',
      routeVersion: 1,
      version: 1,
      appKey: 'dingkey',
      appSecretMasked: null,
      robotCode: 'robot_B',
      agentId: 7,
      transportMode: 'STREAM',
      streamEnv: 'ONLINE',
      streamStatus: 'NOT_CONNECTED',
      streamError: null,
      streamStatusUpdatedAt: null,
      baseUrl: null,
      regionId: null,
      status: 'ENABLED',
      lastSuccessAt: null,
      lastError: null,
      callbackUrl: null,
    };
    server.use(
      http.get('/api/integrations/dingtalk/bindings', () => HttpResponse.json(ok([existing]))),
      http.get('/api/agents', () => HttpResponse.json(agentsBody)),
      http.post('/api/integrations/dingtalk/bindings', () => {
        posts += 1;
        return HttpResponse.json({ success: false, code: '10000', message: 'network lost' });
      }),
    );
    renderPanel();
    await screen.findByText('cid_group');
    await user.click(screen.getByText('新建绑定'));
    await user.type(await screen.findByLabelText('群 ID'), 'cid_group');
    await user.click(screen.getByRole('combobox', { name: '关联数字员工' }));
    await user.click(await screen.findByText('Alpha（评审工程师）', { selector: '.ant-select-item-option-content' }));
    await user.click(screen.getByRole('button', { name: /保\s*存/ }));

    const detail = within(await screen.findByRole('dialog', { name: '绑定详情' }));
    expect(detail.getByText('cid_group')).toBeInTheDocument();
    expect(posts).toBe(1);
  });

});

it('refreshes memoized binding labels and open drawer role options while preserving names and agent ids', async () => {
  const { i18n } = await import('@/shared/i18n');
  const { act } = await import('@testing-library/react');
  useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'ADMIN');
  const agents = [{ ...agentsBody.data[0], name: '<em>员工</em>', roleName: '测试工程师', roleCode: 'QA' },
    { ...agentsBody.data[0], id: 8, name: '用户员工', roleName: '<b>用户角色</b>', roleCode: 'constructor' }];
  server.use(
    http.get('/api/agents', () => HttpResponse.json(ok(agents))),
    http.get('/api/integrations/dingtalk/bindings', () => HttpResponse.json(ok([{ id: 1, robotCode: 'robot_A', agentId: 7, status: 'ENABLED', transportMode: 'STREAM' }]))),
  );
  const { container } = renderPanel();
  await screen.findByText('<em>员工</em>（测试工程师）');
  await userEvent.click(screen.getByRole('button', { name: /新建绑定/ }));
  await userEvent.click(screen.getByRole('combobox', { name: '关联数字员工' }));
  await act(async () => { await i18n.changeLanguage('en-US'); });
  try {
    expect(screen.getAllByText('<em>员工</em> (QA engineer)').length).toBeGreaterThanOrEqual(2);
    expect(screen.getByText('用户员工 (<b>用户角色</b>)')).toBeInTheDocument();
    expect(screen.getByRole('combobox', { name: 'Linked Agent' })).toHaveAttribute('id', 'agentId');
    expect(container.querySelector('em')).toBeNull();
    await act(async () => { await i18n.changeLanguage('zh-CN'); });
    expect(screen.getAllByText('<em>员工</em>（测试工程师）').length).toBeGreaterThanOrEqual(2);
  } finally { await act(async () => { await i18n.changeLanguage('zh-CN'); }); }
});
