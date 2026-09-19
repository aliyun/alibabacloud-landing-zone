import { beforeEach, describe, expect, it, vi } from 'vitest';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { useAuthStore } from '@/shared/auth/store';
import { createAppRoutes } from '@/app/router';
import { EnvironmentVariablesPage } from './EnvironmentVariablesPage';

const ok = (data: unknown) => HttpResponse.json({ success: true, code: '0', message: '', data });
const variables = [
  { id: 11, name: 'API_TOKEN', value: '**', description: '部署令牌', gmtCreate: '2026-09-16T10:00:00Z', gmtModified: '2026-09-17T10:00:00Z', version: 0 },
  { id: 12, name: 'EMPTY_SECRET', value: '**', description: null, gmtCreate: '2026-09-16T10:00:00Z', gmtModified: '2026-09-17T11:00:00Z', version: 1 },
];

function renderPage(client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })) {
  return {
    client,
    ...render(
      <QueryClientProvider client={client}>
        <EnvironmentVariablesPage />
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

  it('always starts masked and reveals only the requested row on demand', async () => {
    const reveal = vi.fn(({ params }: { params: { id: string } }) => ok({ value: params.id === '11' ? 'top-secret-value' : 'other' }));
    server.use(http.get('/api/environment-variables/:id/value', reveal));

    renderPage();
    const tokenRow = await screen.findByRole('row', { name: /API_TOKEN/ });
    const emptyRow = screen.getByRole('row', { name: /EMPTY_SECRET/ });
    expect(within(tokenRow).getByText('**')).toBeInTheDocument();
    expect(within(emptyRow).getByText('**')).toBeInTheDocument();
    expect(screen.queryByText('top-secret-value')).not.toBeInTheDocument();
    expect(reveal).not.toHaveBeenCalled();

    await userEvent.click(within(tokenRow).getByRole('button', { name: '显示 API_TOKEN 的值' }));
    expect(await within(tokenRow).findByText('top-secret-value')).toBeInTheDocument();
    expect(within(emptyRow).getByText('**')).toBeInTheDocument();
    expect(reveal).toHaveBeenCalledOnce();
  });

  it('copies only a revealed value and hiding clears it from component memory', async () => {
    server.use(http.get('/api/environment-variables/11/value', () => ok({ value: 'copy-me' })));
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } });

    renderPage();
    const row = await screen.findByRole('row', { name: /API_TOKEN/ });
    expect(within(row).queryByRole('button', { name: '复制 API_TOKEN 的值' })).not.toBeInTheDocument();
    await userEvent.click(within(row).getByRole('button', { name: '显示 API_TOKEN 的值' }));
    await userEvent.click(await within(row).findByRole('button', { name: '复制 API_TOKEN 的值' }));
    await waitFor(() => expect(writeText).toHaveBeenCalledWith('copy-me'));
    expect(await screen.findByText('已复制')).toBeInTheDocument();

    await userEvent.click(within(row).getByRole('button', { name: '隐藏 API_TOKEN 的值' }));
    expect(within(row).getByText('**')).toBeInTheDocument();
    expect(screen.queryByText('copy-me')).not.toBeInTheDocument();
  });

  it('clears revealed values on refresh, unmount, and remount without query caching', async () => {
    const reveal = vi.fn(() => ok({ value: 'memory-only' }));
    server.use(http.get('/api/environment-variables/11/value', reveal));
    const { client, unmount } = renderPage();
    let row = await screen.findByRole('row', { name: /API_TOKEN/ });
    await userEvent.click(within(row).getByRole('button', { name: '显示 API_TOKEN 的值' }));
    expect(await within(row).findByText('memory-only')).toBeInTheDocument();
    expect(client.getQueryCache().findAll().some((query) => JSON.stringify(query.state.data).includes('memory-only'))).toBe(false);

    await userEvent.click(screen.getByRole('button', { name: '刷新' }));
    await waitFor(() => expect(screen.queryByText('memory-only')).not.toBeInTheDocument());
    unmount();
    renderPage(client);
    row = await screen.findByRole('row', { name: /API_TOKEN/ });
    expect(within(row).getByText('**')).toBeInTheDocument();
    expect(reveal).toHaveBeenCalledOnce();
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
    expectNoSecretCache(client, 'new-secret');
  });

  it('edits metadata without fetching plaintext and sends updateValue false', async () => {
    const reveal = vi.fn(() => ok({ value: 'must-not-fetch' }));
    let body: Record<string, unknown> | undefined;
    server.use(
      http.get('/api/environment-variables/:id/value', reveal),
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
    expect(reveal).not.toHaveBeenCalled();
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

  it.each(['READ_WRITE', 'READ_ONLY'] as const)('keeps %s members read-only while allowing reveal and copy', async (level) => {
    useAuthStore.getState().setAccessLevel(level);
    server.use(http.get('/api/environment-variables/11/value', () => ok({ value: 'readable' })));
    renderPage();
    const row = await screen.findByRole('row', { name: /API_TOKEN/ });
    expect(screen.queryByRole('button', { name: '新增环境变量' })).not.toBeInTheDocument();
    expect(within(row).queryByRole('button', { name: '编辑 API_TOKEN' })).not.toBeInTheDocument();
    expect(within(row).queryByRole('button', { name: '删除 API_TOKEN' })).not.toBeInTheDocument();
    await userEvent.click(within(row).getByRole('button', { name: '显示 API_TOKEN 的值' }));
    expect(await within(row).findByText('readable')).toBeInTheDocument();
    expect(within(row).getByRole('button', { name: '复制 API_TOKEN 的值' })).toBeInTheDocument();
  });

  it('render-gates a revealed value and stale copy control immediately after an access downgrade', async () => {
    server.use(http.get('/api/environment-variables/11/value', () => ok({ value: 'admin-boundary-secret' })));
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } });
    renderPage();

    const row = await screen.findByRole('row', { name: /API_TOKEN/ });
    await userEvent.click(within(row).getByRole('button', { name: '显示 API_TOKEN 的值' }));
    expect(await within(row).findByText('admin-boundary-secret')).toBeInTheDocument();
    const staleCopy = within(row).getByRole('button', { name: '复制 API_TOKEN 的值' });

    act(() => {
      useAuthStore.getState().setAccessLevel('READ_ONLY');
      // A control retained by the browser/event queue must not copy after the
      // authorization boundary changes, even before React flushes effects.
      staleCopy.click();
      expect(writeText).not.toHaveBeenCalled();
    });

    expect(screen.queryByText('admin-boundary-secret')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '复制 API_TOKEN 的值' })).not.toBeInTheDocument();
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

  it.each([
    { outcome: 'success', response: ok({ value: 'workspace-a-value' }) },
    { outcome: 'failure', response: HttpResponse.json({ success: false, code: '33001', message: '旧空间变量不存在', data: null }) },
  ])('ignores a late reveal $outcome after switching workspaces', async ({ response }) => {
    let finish: ((response: Response) => void) | undefined;
    server.use(http.get('/api/environment-variables/11/value', async () =>
      new Promise<Response>((resolve) => { finish = resolve; })));
    renderPage();
    const row = await screen.findByRole('row', { name: /API_TOKEN/ });
    await userEvent.click(within(row).getByRole('button', { name: '显示 API_TOKEN 的值' }));
    await waitFor(() => expect(finish).toBeTypeOf('function'));

    act(() => useAuthStore.getState().setCurrentWorkspace({ id: 2, name: 'other', description: '' }, 'ADMIN'));
    finish?.(response);

    await waitFor(() => expect(screen.getByRole('row', { name: /API_TOKEN/ })).toBeInTheDocument());
    expect(screen.queryByText('workspace-a-value')).not.toBeInTheDocument();
    expect(screen.queryByText('旧空间变量不存在')).not.toBeInTheDocument();
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

  it('isolates late delete success from the new workspace reveal and loading state', async () => {
    let finish: ((response: Response) => void) | undefined;
    server.use(
      http.delete('/api/environment-variables/11', async () =>
        new Promise<Response>((resolve) => { finish = resolve; })),
      http.get('/api/environment-variables/11/value', () => ok({ value: 'workspace-b-visible' })),
    );
    renderPage();
    let row = await screen.findByRole('row', { name: /API_TOKEN/ });
    await userEvent.click(within(row).getByRole('button', { name: '删除 API_TOKEN' }));
    await userEvent.click(screen.getByRole('button', { name: '确认删除' }));
    await waitFor(() => expect(finish).toBeTypeOf('function'));

    act(() => useAuthStore.getState().setCurrentWorkspace({ id: 2, name: 'other', description: '' }, 'ADMIN'));
    row = await screen.findByRole('row', { name: /API_TOKEN/ });
    await userEvent.click(within(row).getByRole('button', { name: '显示 API_TOKEN 的值' }));
    expect(await within(row).findByText('workspace-b-visible')).toBeInTheDocument();
    await userEvent.click(within(row).getByRole('button', { name: '删除 API_TOKEN' }));
    expect(screen.getByRole('button', { name: '确认删除' })).toBeEnabled();
    await act(async () => { finish?.(ok(null)); });

    await waitFor(() => expect(within(row).getByText('workspace-b-visible')).toBeInTheDocument());
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
});
