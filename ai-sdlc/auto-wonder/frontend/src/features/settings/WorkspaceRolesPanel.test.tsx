import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { server } from '@/test/mocks/server';
import { mockRoles } from '@/test/mocks/workspaceRoles';
import { useAuthStore } from '@/shared/auth/store';
import { i18n } from '@/shared/i18n';
import { WorkspaceRolesPanel } from './WorkspaceRolesPanel';

const ok = (data: unknown) => HttpResponse.json({ success: true, code: '0', data });
beforeEach(() => {
  useAuthStore.getState().clear();
  useAuthStore.getState().setCurrentWorkspace({ id: 7, name: 'O', description: '' }, 'ADMIN');
  server.use(http.get('/api/workspaces/current/roles', () => ok(mockRoles)));
});
function mount(isAdmin = true) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  render(<QueryClientProvider client={client}><WorkspaceRolesPanel isAdmin={isAdmin} /></QueryClientProvider>);
}

describe('WorkspaceRolesPanel', () => {
  it('creates a custom role with grouped read-only features and a raw name', async () => {
    const user = userEvent.setup();
    const saved = vi.fn();
    server.use(http.post('/api/workspaces/current/roles', async ({ request }) => { saved(await request.json()); return ok(mockRoles[3]); }));
    mount();
    await screen.findByText('交付协作 / Delivery');
    await user.click(screen.getByRole('button', { name: '新建角色' }));
    const dialog = within(screen.getByRole('dialog'));
    await user.type(dialog.getByRole('textbox', { name: '角色名称' }), '<QA> 验收');
    expect(dialog.queryByText('访问等级')).not.toBeInTheDocument();
    expect(dialog.getByRole('button', { name: /^交付与工单协作/ })).toHaveAttribute('aria-expanded', 'false');
    await user.click(dialog.getByRole('button', { name: /^流程与工作空间配置/ }));
    await user.click(within(dialog.getByRole('group', { name: 'SDLC 流程' })).getByRole('radio', { name: '只读' }));
    await user.click(dialog.getByRole('button', { name: /保\s*存/ }));
    await waitFor(() => expect(saved).toHaveBeenCalledWith({ name: '<QA> 验收', readOnlyDomains: ['SDLC'] }));
  });

  it('preserves draft and version across language switches and submits selected permissions', async () => {
    const user = userEvent.setup();
    const saved = vi.fn();
    server.use(http.put('/api/workspaces/current/roles/14', async ({ request }) => { saved(await request.json()); return ok(mockRoles[3]); }));
    mount();
    const row = (await screen.findByText('交付协作 / Delivery')).closest('tr')!;
    await user.click(within(row).getByRole('button', { name: '编辑' }));
    await act(async () => { await i18n.changeLanguage('en-US'); });
    try {
      const dialog = within(screen.getByRole('dialog'));
      expect(dialog.getByRole('textbox', { name: 'Role name' })).toHaveValue('交付协作 / Delivery');
      expect(dialog.getByRole('button', { name: /^Workflow and workspace configuration/ })).toHaveTextContent('1 read only');
      await user.click(dialog.getByRole('button', { name: /^Workflow and workspace configuration/ }));
      await user.click(within(dialog.getByRole('group', { name: 'SDLC workflows' })).getByRole('radio', { name: 'Read/write' }));
      await user.click(dialog.getByRole('button', { name: 'Save' }));
      await waitFor(() => expect(saved).toHaveBeenCalledWith({ name: '交付协作 / Delivery', readOnlyDomains: [], version: 2 }));
    } finally { await act(async () => { await i18n.changeLanguage('zh-CN'); }); }
  });

  it('edits collaborator permissions without submitting its fixed localized name', async () => {
    const user = userEvent.setup();
    const saved = vi.fn();
    server.use(http.put('/api/workspaces/current/roles/12', async ({ request }) => {
      saved(await request.json());
      return ok({ ...mockRoles[1], readOnlyDomains: ['SDLC'], version: 1 });
    }));
    mount();
    const row = (await screen.findByText('读写者')).closest('tr')!;
    expect(within(row).queryByRole('button', { name: '删除角色' })).not.toBeInTheDocument();
    await user.click(within(row).getByRole('button', { name: '编辑' }));
    expect(within(screen.getByRole('dialog')).getByRole('textbox', { name: '角色名称' })).toBeDisabled();
    await act(async () => { await i18n.changeLanguage('en-US'); });
    try {
      const dialog = within(screen.getByRole('dialog'));
      expect(dialog.getByRole('textbox', { name: 'Role name' })).toHaveValue('Collaborator');
      expect(dialog.getByRole('textbox', { name: 'Role name' })).toBeDisabled();
      await user.click(dialog.getByRole('button', { name: /^Workflow and workspace configuration/ }));
      await user.click(within(dialog.getByRole('group', { name: 'SDLC workflows' })).getByRole('radio', { name: 'Read only' }));
      await user.click(dialog.getByRole('button', { name: 'Save' }));
      await waitFor(() => expect(saved).toHaveBeenCalledWith({ readOnlyDomains: ['SDLC'], version: 0 }));
    } finally { await act(async () => { await i18n.changeLanguage('zh-CN'); }); }
  });

  it.each(['只读者', '管理员'])('keeps the %s role fixed and undeletable for administrators', async (name) => {
    const user = userEvent.setup();
    mount();
    const row = (await screen.findByText(name)).closest('tr')!;
    expect(within(row).queryByRole('button', { name: '编辑' })).not.toBeInTheDocument();
    expect(within(row).queryByRole('button', { name: '删除角色' })).not.toBeInTheDocument();
    await user.click(within(row).getByRole('button', { name: '查看角色' }));
    const dialog = within(screen.getByRole('dialog'));
    expect(dialog.getByRole('textbox', { name: '角色名称' })).toBeDisabled();
    expect(dialog.queryByRole('button', { name: /保\s*存/ })).not.toBeInTheDocument();
  });

  it('disables all edits for non-admins, including collaborator permissions', async () => {
    const user = userEvent.setup();
    useAuthStore.getState().setAccessLevel('READ_WRITE');
    mount(false);
    const row = (await screen.findByText('只读者')).closest('tr')!;
    expect(screen.getByRole('button', { name: '新建角色' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '删除角色' })).toBeDisabled();
    await user.click(within(row).getByRole('button', { name: '查看角色' }));
    const dialog = within(screen.getByRole('dialog'));
    expect(dialog.getByRole('textbox', { name: '角色名称' })).toBeDisabled();
    expect(dialog.queryByRole('button', { name: /保\s*存/ })).not.toBeInTheDocument();
    await user.click(dialog.getByRole('button', { name: /^流程与工作空间配置/ }));
    expect(within(dialog.getByRole('group', { name: 'SDLC 流程' })).getByRole('radio', { name: '只读' })).toBeDisabled();
    await user.click(dialog.getByRole('button', { name: '关闭' }));
    const collaborator = screen.getByText('读写者').closest('tr')!;
    await user.click(within(collaborator).getByRole('button', { name: '查看角色' }));
    expect(within(screen.getByRole('dialog')).queryByRole('button', { name: /保\s*存/ })).not.toBeInTheDocument();
  });

  it('deletes an unused role with its version after confirmation', async () => {
    const user = userEvent.setup();
    const deleted = vi.fn();
    server.use(http.delete('/api/workspaces/current/roles/14', ({ request }) => { deleted(new URL(request.url).searchParams.get('version')); return ok(null); }));
    mount();
    await screen.findByText('交付协作 / Delivery');
    await user.click(screen.getByRole('button', { name: '删除角色' }));
    expect(deleted).not.toHaveBeenCalled();
    await user.click(screen.getByRole('button', { name: 'OK' }));
    await waitFor(() => expect(deleted).toHaveBeenCalledWith('2'));
  });
});
