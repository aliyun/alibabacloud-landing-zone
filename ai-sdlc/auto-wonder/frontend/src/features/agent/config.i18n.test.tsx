import { beforeEach, afterEach, describe, it, expect, vi } from 'vitest';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { message } from 'antd';
import { server } from '@/test/mocks/server';
import { useAuthStore } from '@/shared/auth/store';
import { i18n } from '@/shared/i18n';
import { AgentCreatePage } from './AgentCreatePage';
import { AgentEditPage } from './AgentEditPage';

const agent = { id: 1, name: '<em>员工</em>', avatarUrl: null, status: 'DRAFT', onlineVersionId: null,
  editingVersionId: 10, latestVersionNo: 1, version: 1, gmtCreate: '2026-07-01' };
const version = { id: 10, agentId: 1, versionNo: 1, status: 'DRAFT', roleName: '测试工程师', roleCode: 'QA',
  businessBackground: '<strong>业务原文</strong>', responsibilities: '用户职责', sdlcId: null,
  identityJson: null, evolutionMode: 'ASSISTED', reviewerId: null, reviewComment: null, reviewedAt: null,
  version: 1, gmtCreate: '2026-07-01', repoPerms: [], skills: [], environmentVariables: [] };
const ok = (data: unknown) => HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data });
function renderRoute(path: string) {
  const router = createMemoryRouter([
    { path: '/agents/new', element: <AgentCreatePage /> },
    { path: '/agents/:id/edit', element: <AgentEditPage /> },
    { path: '/agents/:id', element: <div>Saved agent</div> },
  ], { initialEntries: [path] });
  return render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
    <RouterProvider router={router} />
  </QueryClientProvider>);
}
beforeEach(async () => {
  await i18n.changeLanguage('zh-CN');
  useAuthStore.getState().clear();
  useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_WRITE');
  server.use(http.get('/api/agents/1', () => ok(agent)), http.get('/api/agents/1/versions/1', () => ok(version)),
    http.get('/api/repos', () => ok([])), http.get('/api/skills', () => ok([])),
    http.get('/api/sdlcs', () => ok([])), http.get('/api/environment-variables', () => ok([])));
});
afterEach(async () => { vi.restoreAllMocks(); await i18n.changeLanguage('zh-CN'); });

describe('routed agent configuration languages', () => {
  it('switches create labels and visible errors, preserves drafts and submits canonical selected presets', async () => {
    const user = userEvent.setup();
    let payload: Record<string, unknown> | undefined;
    server.use(http.post('/api/agents', async ({ request }) => { payload = await request.json() as Record<string, unknown>; return ok(agent); }));
    const { container } = renderRoute('/agents/new');
    const nameInput = screen.getByLabelText('名称');
    await user.type(nameInput, '<em>新员工</em>');
    await act(async () => { await i18n.changeLanguage('en-US'); });
    expect(screen.getByText('Create agent')).toBeInTheDocument();
    expect(screen.getByLabelText('Name')).toBe(nameInput);
    expect(nameInput).toHaveValue('<em>新员工</em>');
    expect(container.querySelectorAll('.ant-form-item-explain-error')).toHaveLength(0);
    await user.click(screen.getByRole('button', { name: /^Create$/ }));
    expect(await screen.findByText('Enter a role name')).toBeInTheDocument();
    expect(screen.getByText('Enter a role code')).toBeInTheDocument();
    await act(async () => { await i18n.changeLanguage('zh-CN'); });
    expect(await screen.findByText('请输入角色名称')).toBeInTheDocument();
    expect(screen.queryByText('Enter a role name')).not.toBeInTheDocument();
    await act(async () => { await i18n.changeLanguage('en-US'); });
    await user.click(screen.getByLabelText('Role code'));
    await user.click(await screen.findByText('QA (QA engineer)'));
    expect(screen.getByLabelText('Role name')).toHaveValue('测试工程师');
    expect(screen.getByLabelText('Role code')).toHaveValue('QA');
    await user.type(screen.getByLabelText('SOUL.md'), '<strong>原文</strong>');
    await user.click(screen.getByRole('button', { name: /^Create$/ }));
    expect(await screen.findByText('Saved agent')).toBeInTheDocument();
    expect(payload).toMatchObject({ name: '<em>新员工</em>', roleName: '测试工程师', roleCode: 'QA', businessBackground: '<strong>原文</strong>' });
  });

  it('localizes alias options live while keeping the chosen alias and its role code canonical', async () => {
    const user = userEvent.setup();
    renderRoute('/agents/new');
    await act(async () => { await i18n.changeLanguage('en-US'); });
    await user.click(screen.getByLabelText('Role name'));
    await user.click(await screen.findByText('Frontend development'));
    expect(screen.getByLabelText('Role name')).toHaveValue('前端开发');
    expect(screen.getByLabelText('Role code')).toHaveValue('FRONTEND_DEV');
    await act(async () => { await i18n.changeLanguage('zh-CN'); });
    expect(screen.getByLabelText('角色名称')).toHaveValue('前端开发');
    expect(screen.getByLabelText('角色码')).toHaveValue('FRONTEND_DEV');
  });

  it('refreshes edit options, relation dialogs and existing errors without replacing authored configuration', async () => {
    const user = userEvent.setup();
    const { container } = renderRoute('/agents/1/edit');
    const nameInput = await screen.findByLabelText('员工名称');
    await screen.findByDisplayValue('测试工程师');
    const soul = screen.getByLabelText('SOUL.md');
    await user.type(soul, ' 草稿');
    await act(async () => { await i18n.changeLanguage('en-US'); });
    expect(screen.getByText('Edit configuration — <em>员工</em>')).toBeInTheDocument();
    expect(screen.getByLabelText('Agent name')).toBe(nameInput);
    expect(screen.getByRole('button', { name: 'Save draft' })).toBeInTheDocument();
    expect(soul).toHaveValue('<strong>业务原文</strong> 草稿');
    expect(container.querySelectorAll('.ant-form-item-explain-error')).toHaveLength(0);
    await user.click(screen.getByRole('combobox', { name: 'Evolution mode' }));
    expect(await screen.findByText('Assisted review (recommended)', { selector: '.ant-select-item-option-content span' })).toBeInTheDocument();
    await act(async () => { await i18n.changeLanguage('zh-CN'); });
    expect(await screen.findByText('辅助审核（推荐）', { selector: '.ant-select-item-option-content span' })).toBeInTheDocument();
    await user.keyboard('{Escape}');
    await user.clear(nameInput);
    await user.click(screen.getByRole('button', { name: /保存草稿/ }));
    expect(await screen.findByText('请输入员工名称')).toBeInTheDocument();
    await act(async () => { await i18n.changeLanguage('en-US'); });
    expect(await screen.findByText('Enter an agent name')).toBeInTheDocument();
    expect(screen.queryByText('请输入员工名称')).not.toBeInTheDocument();
    expect(screen.queryByText('Enter a role name')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /Add repository/ }));
    const dialog = await screen.findByRole('dialog', { name: 'Add repository permission' });
    expect(within(dialog).getByRole('combobox', { name: 'Allowed commit branches' })).toBeInTheDocument();
    expect(soul).toHaveValue('<strong>业务原文</strong> 草稿');
  });

  it('localizes fixed relation enums and template status while preserving authored names', async () => {
    const user = userEvent.setup();
    server.use(
      http.get('/api/agents/1/versions/1', () => ok({ ...version,
        repoPerms: [{ repoId: 2, permLevel: 'READ', allowedBranchPatterns: [] }, { repoId: 5, permLevel: 'constructor', allowedBranchPatterns: [] }], skills: [{ skillId: 3 }, { skillId: 6 }] })),
      http.get('/api/repos', () => ok([{ id: 2, name: '<em>仓库</em>' }, { id: 5, name: '未知权限仓库' }])),
      http.get('/api/skills', () => ok([{ id: 3, name: '用户技能', type: 'SKILL', version: 1 }, { id: 6, name: '未知类型能力', type: 'toString', version: 1 }])),
      http.get('/api/sdlcs', () => ok([{ id: 4, name: '用户模版', status: 'ENABLED' }])),
    );
    renderRoute('/agents/1/edit');
    await screen.findByText('<em>仓库</em>');
    await act(async () => { await i18n.changeLanguage('en-US'); });
    expect(screen.getByText('Read only')).toBeInTheDocument();
    expect(screen.getByText('Skill', { selector: '.ant-tag' })).toBeInTheDocument();
    expect(screen.getByText('用户技能')).toBeInTheDocument();
    expect(screen.getByText('constructor', { selector: '.ant-tag' })).toBeInTheDocument();
    expect(screen.getByText('toString', { selector: '.ant-tag' })).toBeInTheDocument();
    await user.click(screen.getByLabelText('SDLC template'));
    expect(await screen.findByText('用户模版 (Enabled)')).toBeInTheDocument();
  });

  it('uses completion locale for a pending save and keeps feedback reactive with an unchanged canonical payload', async () => {
    const user = userEvent.setup();
    let release!: () => void;
    let payload: Record<string, unknown> | undefined;
    const pending = new Promise<void>(resolve => { release = resolve; });
    server.use(http.put('/api/agents/1/config', async ({ request }) => {
      payload = await request.json() as Record<string, unknown>; await pending; return ok(version);
    }));
    const success = vi.spyOn(message, 'success').mockImplementation(() => undefined as unknown as ReturnType<typeof message.success>);
    renderRoute('/agents/1/edit');
    await screen.findByDisplayValue('测试工程师');
    await user.click(screen.getByRole('button', { name: /保存草稿/ }));
    await waitFor(() => expect(payload).toBeDefined());
    await act(async () => { await i18n.changeLanguage('en-US'); });
    await act(async () => { release(); });
    expect(await screen.findByText('Draft saved')).toBeInTheDocument();
    expect(success).toHaveBeenCalledWith('Configuration saved as a draft');
    expect(payload).toMatchObject({ roleName: '测试工程师', roleCode: 'QA', businessBackground: '<strong>业务原文</strong>', responsibilities: '用户职责', evolutionMode: 'ASSISTED' });
    await act(async () => { await i18n.changeLanguage('zh-CN'); });
    expect(await screen.findByText('草稿已保存')).toBeInTheDocument();
    expect(screen.queryByText('Draft saved')).not.toBeInTheDocument();
  });
});
