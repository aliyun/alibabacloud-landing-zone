import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { message } from 'antd';
import { server } from '@/test/mocks/server';
import { useAuthStore } from '@/shared/auth/store';
import { createAppRoutes } from '@/app/router';
import { EnvironmentVariablesPage } from './EnvironmentVariablesPage';

const ok = (data: unknown) => HttpResponse.json({ success: true, code: '0', message: '', data });
const variables = [
  { id: 11, name: 'API_TOKEN', value: '**', description: '部署令牌', gmtCreate: '2026-09-16T10:00:00Z', gmtModified: '2026-09-17T10:00:00Z', version: 0, onlineMountCount: 0, draftMountCount: 0 },
  { id: 12, name: 'EMPTY_SECRET', value: '**', description: null, gmtCreate: '2026-09-16T10:00:00Z', gmtModified: '2026-09-17T11:00:00Z', version: 1, onlineMountCount: 0, draftMountCount: 0 },
];

function renderPage(client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })) {
  return {
    client,
    ...render(
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={['/settings/environment-variables']}>
          <Routes>
            <Route path="/settings/environment-variables" element={<EnvironmentVariablesPage />} />
            <Route path="/agents" element={<div>agents-route</div>} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    ),
  };
}

function expectNoSecretCache(client: QueryClient, secret: string) {
  expect(client.getMutationCache().getAll()).toHaveLength(0);
  const queryData = client.getQueryCache().findAll().map((query) => query.state.data);
  expect(JSON.stringify(queryData)).not.toContain(secret);
}

describe('EnvironmentVariablesPage', () => {
  beforeEach(() => {
    useAuthStore.getState().clear();
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'project', description: '' }, 'ADMIN');
    server.use(http.get('/api/environment-variables', () => ok(variables)));
  });

  afterEach(() => {
    message.destroy();
  });

  it('registers the settings route', () => {
    const paths = createAppRoutes().flatMap((route) => [route.path, ...(route.children ?? []).map((child) => child.path)]);
    expect(paths).toContain('/settings/environment-variables');
  });

  it('shows a loading state and a friendly empty state', async () => {
    let release: (() => void) | undefined;
    server.use(http.get('/api/environment-variables', async () => {
      await new Promise<void>((resolve) => { release = resolve; });
      return ok([]);
    }));

    const view = renderPage();
    expect(view.container.querySelector('.ant-spin-spinning')).toBeInTheDocument();
    await waitFor(() => expect(release).toBeTypeOf('function'));
    act(() => release?.());
    expect(await screen.findByText('暂无环境变量')).toBeInTheDocument();
  });

  it('masks every value and never requests plaintext', async () => {
    const valueEndpoint = vi.fn(() => ok({ value: 'must-not-fetch' }));
    server.use(http.get('/api/environment-variables/:id/value', valueEndpoint));

    renderPage();
    const tokenRow = await screen.findByRole('row', { name: /API_TOKEN/ });
    const emptyRow = screen.getByRole('row', { name: /EMPTY_SECRET/ });
    expect(within(tokenRow).getByText('**')).toBeInTheDocument();
    expect(within(emptyRow).getByText('**')).toBeInTheDocument();
    expect(within(tokenRow).queryByRole('button', { name: /API_TOKEN 的值/ })).not.toBeInTheDocument();
    expect(within(emptyRow).queryByRole('button', { name: /EMPTY_SECRET 的值/ })).not.toBeInTheDocument();
    expect(valueEndpoint).not.toHaveBeenCalled();
  });

  it('allows an admin to create a variable with an explicitly present empty value', async () => {
    let body: Record<string, unknown> | undefined;
    server.use(http.post('/api/environment-variables', async ({ request }) => {
      body = await request.json() as Record<string, unknown>;
      return ok({ ...variables[0], id: 13, name: body.name, description: body.description });
    }));
    const { client } = renderPage();
    await screen.findByText('API_TOKEN');
    await userEvent.click(screen.getByRole('button', { name: '新增环境变量' }));
    await userEvent.type(screen.getByLabelText('名称'), 'NEW_KEY');
    expect(screen.getByLabelText('值')).toHaveValue('');
    await userEvent.type(screen.getByLabelText('说明'), '新密钥');
    await userEvent.click(screen.getByRole('button', { name: '创建' }));
    await waitFor(() => expect(body).toEqual({ name: 'NEW_KEY', value: '', description: '新密钥' }));
    expect(await screen.findByText('环境变量已创建')).toBeInTheDocument();
    expect(screen.queryByText('去挂载到数字员工')).not.toBeInTheDocument();
    expectNoSecretCache(client, 'new-secret');
  });

  it('edits metadata without fetching plaintext and sends updateValue false', async () => {
    const valueEndpoint = vi.fn(() => ok({ value: 'must-not-fetch' }));
    let body: Record<string, unknown> | undefined;
    server.use(
      http.get('/api/environment-variables/:id/value', valueEndpoint),
      http.put('/api/environment-variables/11', async ({ request }) => {
        body = await request.json() as Record<string, unknown>;
        return ok(variables[0]);
      }),
    );
    renderPage();
    const row = await screen.findByRole('row', { name: /API_TOKEN/ });
    await userEvent.click(within(row).getByRole('button', { name: '编辑 API_TOKEN' }));
    expect(screen.getByLabelText('名称')).toHaveValue('API_TOKEN');
    expect(screen.getByLabelText('说明')).toHaveValue('部署令牌');
    expect(screen.queryByLabelText('新值')).not.toBeInTheDocument();
    await userEvent.clear(screen.getByLabelText('说明'));
    await userEvent.type(screen.getByLabelText('说明'), '只改说明');
    await userEvent.click(screen.getByRole('button', { name: '保存' }));
    await waitFor(() => expect(body).toEqual({ name: 'API_TOKEN', description: '只改说明', updateValue: false }));
    expect(valueEndpoint).not.toHaveBeenCalled();
  });

  it('sends updateValue true with the replacement only after explicit selection', async () => {
    let body: Record<string, unknown> | undefined;
    server.use(http.put('/api/environment-variables/11', async ({ request }) => {
      body = await request.json() as Record<string, unknown>;
      return ok(variables[0]);
    }));
    const { client } = renderPage();
    const row = await screen.findByRole('row', { name: /API_TOKEN/ });
    await userEvent.click(within(row).getByRole('button', { name: '编辑 API_TOKEN' }));
    await userEvent.click(await screen.findByRole('checkbox', { name: '替换当前值' }));
    expect(await screen.findByLabelText('新值')).toHaveValue('');
    await userEvent.type(screen.getByLabelText('新值'), 'replacement');
    await userEvent.click(screen.getByRole('button', { name: '保存' }));
    await waitFor(() => expect(body).toEqual({ name: 'API_TOKEN', description: '部署令牌', updateValue: true, value: 'replacement' }));
    expectNoSecretCache(client, 'replacement');
  });

  it('keeps create errors inside the dialog without caching the submitted value', async () => {
    server.use(http.post('/api/environment-variables', () => HttpResponse.json({
      success: false,
      code: '33003',
      message: '该名称为平台保留名称',
      data: null,
    })));
    const { client } = renderPage();
    await screen.findByText('API_TOKEN');
    await userEvent.click(screen.getByRole('button', { name: '新增环境变量' }));
    await userEvent.type(await screen.findByLabelText('名称'), 'AUTOWONDER_TOKEN');
    await userEvent.type(await screen.findByLabelText('值'), 'create-sensitive-input');
    await userEvent.click(screen.getByRole('button', { name: '创建' }));

    const dialog = await screen.findByRole('dialog', { name: '新增环境变量' });
    expect(await within(dialog).findByText('该名称为平台保留名称')).toBeInTheDocument();
    expect(screen.getByLabelText('值')).toHaveValue('create-sensitive-input');
    expectNoSecretCache(client, 'create-sensitive-input');
  }, 15_000);

  it('keeps update errors inside the dialog without caching the replacement value', async () => {
    server.use(http.put('/api/environment-variables/11', () => HttpResponse.json({
      success: false,
      code: '33006',
      message: '环境变量已被修改，请重试',
      data: null,
    })));
    const { client } = renderPage();
    const row = await screen.findByRole('row', { name: /API_TOKEN/ });
    await userEvent.click(within(row).getByRole('button', { name: '编辑 API_TOKEN' }));
    await userEvent.click(screen.getByRole('checkbox', { name: '替换当前值' }));
    await userEvent.type(screen.getByLabelText('新值'), 'replacement-sensitive-input');
    await userEvent.click(screen.getByRole('button', { name: '保存' }));

    const dialog = await screen.findByRole('dialog', { name: '编辑环境变量' });
    expect(await within(dialog).findByText('环境变量已被修改，请重试')).toBeInTheDocument();
    expect(screen.getByLabelText('新值')).toHaveValue('replacement-sensitive-input');
    expectNoSecretCache(client, 'replacement-sensitive-input');
  });

  it.each(['READ_WRITE', 'READ_ONLY'] as const)('keeps %s members read-only without exposing values', async (level) => {
    useAuthStore.getState().setAccessLevel(level);
    const valueEndpoint = vi.fn(() => ok({ value: 'must-not-load' }));
    server.use(http.get('/api/environment-variables/11/value', valueEndpoint));
    renderPage();
    const row = await screen.findByRole('row', { name: /API_TOKEN/ });
    expect(screen.queryByRole('button', { name: '新增环境变量' })).not.toBeInTheDocument();
    expect(within(row).queryByRole('button', { name: '编辑 API_TOKEN' })).not.toBeInTheDocument();
    expect(within(row).queryByRole('button', { name: '删除 API_TOKEN' })).not.toBeInTheDocument();
    expect(within(row).getByText('**')).toBeInTheDocument();
    expect(within(row).queryByRole('button', { name: /API_TOKEN 的值/ })).not.toBeInTheDocument();
    expect(valueEndpoint).not.toHaveBeenCalled();
  });

  it('confirms deletion and surfaces the backend reference detail', async () => {
    server.use(http.delete('/api/environment-variables/11', () => HttpResponse.json({
      success: false,
      code: '33005',
      message: '环境变量仍被数字员工引用,无法删除:发布助手(#9) 在线版本 v3。请先解除挂载。',
      data: null,
    })));
    renderPage();
    const row = await screen.findByRole('row', { name: /API_TOKEN/ });
    await userEvent.click(within(row).getByRole('button', { name: '删除 API_TOKEN' }));
    expect(screen.getByText('确认删除环境变量 API_TOKEN？')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: '确认删除' }));
    expect(await screen.findByText(/发布助手\(#9\) 在线版本 v3/)).toBeInTheDocument();
  });

  it.each(['create', 'edit'] as const)('closes and clears an open %s form when the workspace changes', async (mode) => {
    const create = vi.fn(() => ok(variables[0]));
    const update = vi.fn(() => ok(variables[0]));
    server.use(
      http.post('/api/environment-variables', create),
      http.put('/api/environment-variables/11', update),
    );
    renderPage();
    const row = await screen.findByRole('row', { name: /API_TOKEN/ });
    if (mode === 'create') {
      await userEvent.click(screen.getByRole('button', { name: '新增环境变量' }));
      await userEvent.type(screen.getByLabelText('名称'), 'WORKSPACE_A_KEY');
      await userEvent.type(screen.getByLabelText('值'), 'workspace-a-secret');
    } else {
      await userEvent.click(within(row).getByRole('button', { name: '编辑 API_TOKEN' }));
      await userEvent.click(screen.getByRole('checkbox', { name: '替换当前值' }));
      await userEvent.type(screen.getByLabelText('新值'), 'workspace-a-secret');
    }
    const staleSubmit = screen.getByRole('button', { name: mode === 'create' ? '创建' : '保存' });

    act(() => useAuthStore.getState().setCurrentWorkspace({ id: 2, name: 'other', description: '' }, 'ADMIN'));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(screen.queryByDisplayValue('workspace-a-secret')).not.toBeInTheDocument();
    staleSubmit.click();
    await waitFor(() => {
      expect(create).not.toHaveBeenCalled();
      expect(update).not.toHaveBeenCalled();
    });

    await userEvent.click(screen.getByRole('button', { name: '新增环境变量' }));
    expect(screen.getByLabelText('值')).toHaveValue('');
  });

  it.each(['READ_WRITE', 'READ_ONLY'] as const)('closes a sensitive form and blocks stale submission after access changes to %s', async (level) => {
    const update = vi.fn(() => ok(variables[0]));
    server.use(http.put('/api/environment-variables/11', update));
    renderPage();
    const row = await screen.findByRole('row', { name: /API_TOKEN/ });
    await userEvent.click(within(row).getByRole('button', { name: '编辑 API_TOKEN' }));
    await userEvent.click(screen.getByRole('checkbox', { name: '替换当前值' }));
    await userEvent.type(screen.getByLabelText('新值'), 'admin-only-secret');
    const staleSave = screen.getByRole('button', { name: '保存' });

    act(() => useAuthStore.getState().setAccessLevel(level));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(screen.queryByDisplayValue('admin-only-secret')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '新增环境变量' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /编辑 API_TOKEN/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /删除 API_TOKEN/ })).not.toBeInTheDocument();
    staleSave.click();
    await waitFor(() => expect(update).not.toHaveBeenCalled());
  });

  it('does not let late create success close or reset a form opened in the new workspace', async () => {
    let finish: ((response: Response) => void) | undefined;
    server.use(http.post('/api/environment-variables', async () =>
      new Promise<Response>((resolve) => { finish = resolve; })));
    renderPage();
    await screen.findByText('API_TOKEN');
    await userEvent.click(screen.getByRole('button', { name: '新增环境变量' }));
    await userEvent.type(screen.getByLabelText('名称'), 'WORKSPACE_A_KEY');
    await userEvent.type(screen.getByLabelText('值'), 'workspace-a-secret');
    await userEvent.click(screen.getByRole('button', { name: '创建' }));
    await waitFor(() => expect(finish).toBeTypeOf('function'));

    act(() => useAuthStore.getState().setCurrentWorkspace({ id: 2, name: 'other', description: '' }, 'ADMIN'));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    await userEvent.click(screen.getByRole('button', { name: '新增环境变量' }));
    await userEvent.type(screen.getByLabelText('名称'), 'WORKSPACE_B_KEY');
    await userEvent.type(screen.getByLabelText('值'), 'workspace-b-secret');
    expect(screen.getByRole('button', { name: '创建' })).toBeEnabled();
    finish?.(ok(variables[0]));

    await waitFor(() => expect(screen.getByRole('dialog', { name: '新增环境变量' })).toBeInTheDocument());
    expect(screen.getByLabelText('名称')).toHaveValue('WORKSPACE_B_KEY');
    expect(screen.getByLabelText('值')).toHaveValue('workspace-b-secret');
    expect(screen.getByRole('button', { name: '创建' })).toBeEnabled();
    expect(screen.queryByText('环境变量已创建')).not.toBeInTheDocument();
  });

  it('does not let a late edit failure affect a form opened after an access boundary', async () => {
    let finish: ((response: Response) => void) | undefined;
    server.use(http.put('/api/environment-variables/11', async () =>
      new Promise<Response>((resolve) => { finish = resolve; })));
    renderPage();
    const row = await screen.findByRole('row', { name: /API_TOKEN/ });
    await userEvent.click(within(row).getByRole('button', { name: '编辑 API_TOKEN' }));
    await userEvent.click(screen.getByRole('checkbox', { name: '替换当前值' }));
    await userEvent.type(screen.getByLabelText('新值'), 'old-admin-secret');
    await userEvent.click(screen.getByRole('button', { name: '保存' }));
    await waitFor(() => expect(finish).toBeTypeOf('function'));

    act(() => useAuthStore.getState().setAccessLevel('READ_WRITE'));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    act(() => useAuthStore.getState().setAccessLevel('ADMIN'));
    await userEvent.click(screen.getByRole('button', { name: '新增环境变量' }));
    await userEvent.type(screen.getByLabelText('名称'), 'NEW_ADMIN_KEY');
    expect(screen.getByRole('button', { name: '创建' })).toBeEnabled();
    finish?.(HttpResponse.json({ success: false, code: '33006', message: '旧请求版本冲突', data: null }));

    await waitFor(() => expect(screen.getByRole('dialog', { name: '新增环境变量' })).toBeInTheDocument());
    expect(screen.getByLabelText('名称')).toHaveValue('NEW_ADMIN_KEY');
    expect(screen.queryByText('旧请求版本冲突')).not.toBeInTheDocument();
  }, 15_000);

  it('isolates late delete success from the new workspace state', async () => {
    let finish: ((response: Response) => void) | undefined;
    server.use(http.delete('/api/environment-variables/11', async () =>
      new Promise<Response>((resolve) => { finish = resolve; })));
    renderPage();
    let row = await screen.findByRole('row', { name: /API_TOKEN/ });
    await userEvent.click(within(row).getByRole('button', { name: '删除 API_TOKEN' }));
    await userEvent.click(screen.getByRole('button', { name: '确认删除' }));
    await waitFor(() => expect(finish).toBeTypeOf('function'));

    act(() => useAuthStore.getState().setCurrentWorkspace({ id: 2, name: 'other', description: '' }, 'ADMIN'));
    row = await screen.findByRole('row', { name: /API_TOKEN/ });
    expect(within(row).getByText('**')).toBeInTheDocument();
    await userEvent.click(within(row).getByRole('button', { name: '删除 API_TOKEN' }));
    expect(screen.getByRole('button', { name: '确认删除' })).toBeEnabled();
    await act(async () => { finish?.(ok(null)); });

    await waitFor(() => expect(within(row).getByText('**')).toBeInTheDocument());
    expect(screen.getByRole('button', { name: '确认删除' })).toBeEnabled();
    expect(screen.queryByText('环境变量已删除')).not.toBeInTheDocument();
  }, 15_000);

  it('isolates late delete failure after an access downgrade', async () => {
    let finish: ((response: Response) => void) | undefined;
    server.use(http.delete('/api/environment-variables/11', async () =>
      new Promise<Response>((resolve) => { finish = resolve; })));
    renderPage();
    let row = await screen.findByRole('row', { name: /API_TOKEN/ });
    await userEvent.click(within(row).getByRole('button', { name: '删除 API_TOKEN' }));
    await userEvent.click(screen.getByRole('button', { name: '确认删除' }));
    await waitFor(() => expect(finish).toBeTypeOf('function'));

    act(() => useAuthStore.getState().setAccessLevel('READ_ONLY'));
    await waitFor(() => expect(screen.queryByRole('button', { name: '删除 API_TOKEN' })).not.toBeInTheDocument());
    act(() => useAuthStore.getState().setAccessLevel('ADMIN'));
    row = await screen.findByRole('row', { name: /API_TOKEN/ });
    await userEvent.click(within(row).getByRole('button', { name: '删除 API_TOKEN' }));
    expect(screen.getByRole('button', { name: '确认删除' })).toBeEnabled();
    finish?.(HttpResponse.json({ success: false, code: '33005', message: '旧空间仍有引用', data: null }));

    await waitFor(() => expect(screen.getByRole('button', { name: '确认删除' })).toBeEnabled());
    expect(screen.queryByText('旧空间仍有引用')).not.toBeInTheDocument();
  }, 15_000);

  it('applies the global Qoder PAT preset with a locked name and guide', async () => {
    renderPage();
    await screen.findByText('API_TOKEN');
    await userEvent.click(screen.getByRole('button', { name: '新增环境变量' }));
    await userEvent.click(screen.getByRole('button', { name: 'Qoder 密钥 PAT' }));

    expect(screen.getByLabelText('名称')).toHaveValue('QODER_PERSONAL_ACCESS_TOKEN');
    expect(screen.getByLabelText('名称')).toBeDisabled();
    expect(screen.getByText('预设变量名固定，不能修改')).toBeInTheDocument();
    expect(screen.getByLabelText('说明')).toHaveValue('Qoder 密钥 PAT，用于执行器启动时直接认证，无需在机器上打开浏览器登录');
    const link = screen.getByRole('link', { name: '去 Qoder 平台获取 PAT' });
    expect(link).toHaveAttribute('href', 'https://qoder.com/account/integrations');
    expect(link).toHaveAttribute('target', '_blank');
    expect(link).toHaveAttribute('rel', 'noopener noreferrer');
    expect(screen.getByText('在对应 Qoder 平台生成或复制 Personal Access Token；')).toBeInTheDocument();
    expect(screen.getByText('重启执行器，使新密钥生效。')).toBeInTheDocument();
    expect(screen.getByText('密钥无效或过期会导致执行器启动失败，且不会回退到浏览器登录，请确认密钥可用后再保存。')).toBeInTheDocument();
  });

  it('applies the Qoder CN preset with its own name and guide link', async () => {
    renderPage();
    await screen.findByText('API_TOKEN');
    await userEvent.click(screen.getByRole('button', { name: '新增环境变量' }));
    await userEvent.click(screen.getByRole('button', { name: 'Qoder 国内版密钥 PAT' }));

    expect(screen.getByLabelText('名称')).toHaveValue('QODERCN_PERSONAL_ACCESS_TOKEN');
    expect(screen.getByLabelText('名称')).toBeDisabled();
    expect(screen.getByRole('link', { name: '去 Qoder 平台获取 PAT' }))
      .toHaveAttribute('href', 'https://qoder.cn/account/integrations');
  });

  it('switches back to a custom editable name while keeping the typed value', async () => {
    renderPage();
    await screen.findByText('API_TOKEN');
    await userEvent.click(screen.getByRole('button', { name: '新增环境变量' }));
    await userEvent.click(screen.getByRole('button', { name: 'Qoder 密钥 PAT' }));
    await userEvent.type(screen.getByLabelText('值'), 'typed-pat');
    await userEvent.click(screen.getByRole('button', { name: '切换回自定义' }));

    expect(screen.getByLabelText('名称')).toBeEnabled();
    expect(screen.getByLabelText('名称')).toHaveValue('QODER_PERSONAL_ACCESS_TOKEN');
    expect(screen.getByLabelText('值')).toHaveValue('typed-pat');
    expect(screen.queryByText('预设变量名固定，不能修改')).not.toBeInTheDocument();
  });

  it('rejects an empty PAT without sending a request', async () => {
    const create = vi.fn(() => ok(variables[0]));
    server.use(http.post('/api/environment-variables', create));
    renderPage();
    await screen.findByText('API_TOKEN');
    await userEvent.click(screen.getByRole('button', { name: '新增环境变量' }));
    await userEvent.click(screen.getByRole('button', { name: 'Qoder 密钥 PAT' }));
    await userEvent.click(screen.getByRole('button', { name: '创建' }));

    expect(await screen.findByText('请输入 PAT 密钥')).toBeInTheDocument();
    expect(create).not.toHaveBeenCalled();
  });

  it('rejects a PAT containing inner whitespace without sending a request', async () => {
    const create = vi.fn(() => ok(variables[0]));
    server.use(http.post('/api/environment-variables', create));
    renderPage();
    await screen.findByText('API_TOKEN');
    await userEvent.click(screen.getByRole('button', { name: '新增环境变量' }));
    await userEvent.click(screen.getByRole('button', { name: 'Qoder 密钥 PAT' }));
    await userEvent.type(screen.getByLabelText('值'), 'abc def');
    await userEvent.click(screen.getByRole('button', { name: '创建' }));

    expect(await screen.findByText('PAT 中不能包含空格、换行或制表符')).toBeInTheDocument();
    expect(create).not.toHaveBeenCalled();
  });

  it('trims surrounding whitespace before creating a PAT variable', async () => {
    let body: Record<string, unknown> | undefined;
    server.use(http.post('/api/environment-variables', async ({ request }) => {
      body = await request.json() as Record<string, unknown>;
      return ok({ ...variables[0], id: 13, name: body.name });
    }));
    renderPage();
    await screen.findByText('API_TOKEN');
    await userEvent.click(screen.getByRole('button', { name: '新增环境变量' }));
    await userEvent.click(screen.getByRole('button', { name: 'Qoder 密钥 PAT' }));
    await userEvent.type(screen.getByLabelText('值'), '  pat-value  ');
    await userEvent.click(screen.getByRole('button', { name: '创建' }));

    await waitFor(() => expect(body).toMatchObject({ name: 'QODER_PERSONAL_ACCESS_TOKEN', value: 'pat-value' }));
  });

  it('warns when a case-insensitive duplicate of the preset name already exists', async () => {
    server.use(http.get('/api/environment-variables', () => ok([
      ...variables,
      { id: 13, name: 'qoder_personal_access_token', value: '**', description: 'existing', gmtCreate: '2026-09-16T10:00:00Z', gmtModified: '2026-09-17T10:00:00Z', version: 0, onlineMountCount: 0, draftMountCount: 0 },
    ])));
    renderPage();
    await screen.findByText('qoder_personal_access_token');
    await userEvent.click(screen.getByRole('button', { name: '新增环境变量' }));
    await userEvent.click(screen.getByRole('button', { name: 'Qoder 密钥 PAT' }));

    expect(await screen.findByText('已存在同名变量 QODER_PERSONAL_ACCESS_TOKEN，请直接编辑该变量并替换当前值')).toBeInTheDocument();
  });

  it('guides PAT creators to mount the variable and navigates to agents', async () => {
    server.use(http.post('/api/environment-variables', () => ok({ ...variables[0], id: 13, name: 'QODER_PERSONAL_ACCESS_TOKEN' })));
    renderPage();
    await screen.findByText('API_TOKEN');
    await userEvent.click(screen.getByRole('button', { name: '新增环境变量' }));
    await userEvent.click(screen.getByRole('button', { name: 'Qoder 密钥 PAT' }));
    await userEvent.type(screen.getByLabelText('值'), 'pat-value');
    await userEvent.click(screen.getByRole('button', { name: '创建' }));

    expect(await screen.findByText('环境变量已创建，下一步将它挂载到数字员工。')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: '去挂载到数字员工' }));
    expect(await screen.findByText('agents-route')).toBeInTheDocument();
  });

  it('renders the mount status column for every state and shows the standing hint', async () => {
    server.use(http.get('/api/environment-variables', () => ok([
      { ...variables[0], id: 21, name: 'LIVE', onlineMountCount: 2, draftMountCount: 0 },
      { ...variables[0], id: 22, name: 'DRAFT_ONLY', onlineMountCount: 0, draftMountCount: 1 },
      { ...variables[0], id: 23, name: 'NEVER', onlineMountCount: 0, draftMountCount: 0 },
      { ...variables[0], id: 24, name: 'LEGACY', onlineMountCount: undefined, draftMountCount: undefined },
    ])));
    renderPage();

    const live = await screen.findByRole('row', { name: /LIVE/ });
    expect(within(live).getByText('已生效 · 2 个数字员工')).toBeInTheDocument();
    expect(within(screen.getByRole('row', { name: /DRAFT_ONLY/ })).getByText('仅草稿 · 未生效')).toBeInTheDocument();
    expect(within(screen.getByRole('row', { name: /NEVER/ })).getByText('未挂载')).toBeInTheDocument();
    expect(within(screen.getByRole('row', { name: /LEGACY/ })).getByText('—')).toBeInTheDocument();
    expect(screen.getByRole('columnheader', { name: '挂载状态' })).toBeInTheDocument();
    expect(screen.getByText(/执行器启动认证只读取数字员工/)).toBeInTheDocument();
  });
});
