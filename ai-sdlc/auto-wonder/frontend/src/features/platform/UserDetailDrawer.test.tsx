import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { useAuthStore } from '@/shared/auth/store';
import { i18n } from '@/shared/i18n';
import { ApiError } from '@/shared/types/common';
import { UserDetailDrawer, type UserDetailInitialAction } from './UserDetailDrawer';
import * as userAdminApi from './userAdminApi';
import type {
  UserAdminImIdentity,
  UserAdminStatus,
  UserAdminUser,
  UserAdminWorkspace,
  WorkspaceAccessLevel,
} from './userAdminApi';

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

function profile(overrides: Partial<UserAdminUser> = {}): UserAdminUser {
  return {
    id: 8,
    username: 'bob',
    nickname: '鲍勃',
    email: 'bob@example.com',
    phone: '13800000000',
    status: 'NORMAL',
    isAdmin: false,
    createdAt: '2026-09-01T10:00:00',
    lastLoginAt: null,
    deletedAt: null,
    deletedById: null,
    deletedByName: null,
    editable: true,
    restorable: null,
    ...overrides,
  };
}

function workspace(overrides: Partial<UserAdminWorkspace> = {}): UserAdminWorkspace {
  return {
    workspaceId: 1,
    name: 'Alpha',
    accessLevel: 'READ_ONLY',
    isOwner: false,
    joinedAt: null,
    ...overrides,
  };
}

const OWNER_WORKSPACE = workspace({ name: 'Alpha', isOwner: true });
const MEMBER_WORKSPACE = workspace({ workspaceId: 2, name: 'Beta', accessLevel: 'READ_WRITE' });

const READY_IDENTITY: UserAdminImIdentity = {
  provider: 'dingtalk',
  externalUserId: 'staff-1',
  configured: true,
  platformReady: true,
  testAvailable: true,
};

function renderDrawer(initialAction?: UserDetailInitialAction) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  const onClose = vi.fn();
  const onChanged = vi.fn();
  const view = render(
    <QueryClientProvider client={queryClient}>
      <UserDetailDrawer userId={8} open initialAction={initialAction} onClose={onClose} onChanged={onChanged} />
    </QueryClientProvider>,
  );
  return { queryClient, onClose, onChanged, ...view };
}

async function drawerBody(): Promise<HTMLElement> {
  return waitFor(() => {
    const body = document.querySelector('.ant-drawer-body');
    expect(body).not.toBeNull();
    return body as HTMLElement;
  });
}

describe('UserDetailDrawer', () => {
  beforeEach(async () => {
    document.body.innerHTML = '';
    vi.restoreAllMocks();
    successMock.mockClear();
    errorMock.mockClear();
    await i18n.changeLanguage('zh-CN');
    useAuthStore.getState().clear();
    useAuthStore.getState().setTokens('test-access', 'test-refresh');
  });
  afterEach(async () => {
    await i18n.changeLanguage('zh-CN');
  });

  it('renders the profile, the workspace access and the IM binding of an editable user', async () => {
    vi.spyOn(userAdminApi, 'getUserAdminDetail').mockResolvedValue(profile());
    vi.spyOn(userAdminApi, 'listUserAdminWorkspaces').mockResolvedValue([OWNER_WORKSPACE, MEMBER_WORKSPACE]);
    vi.spyOn(userAdminApi, 'listUserAdminImIdentities').mockResolvedValue([READY_IDENTITY]);
    renderDrawer();

    const body = await drawerBody();
    await within(body).findByText('鲍勃');
    expect(within(body).getByText('bob@example.com')).toBeInTheDocument();
    expect(within(body).getByText('13800000000')).toBeInTheDocument();
    expect(within(body).getByText('正常')).toBeInTheDocument();
    expect(within(body).getByText('普通用户')).toBeInTheDocument();
    expect(within(body).queryByText('已删除或已注销用户只读，不可编辑、删除或重置密码')).not.toBeInTheDocument();

    expect(within(body).getByRole('button', { name: '编辑资料' })).toBeEnabled();
    expect(within(body).getByRole('button', { name: '重置密码' })).toBeEnabled();
    expect(within(body).getByRole('button', { name: /删\s*除/ })).toBeEnabled();
    expect(within(body).queryByRole('button', { name: /恢\s*复/ })).not.toBeInTheDocument();

    const ownerRow = (await within(body).findByText('Alpha')).closest('tr')!;
    expect(within(ownerRow).getByText('所有者')).toBeInTheDocument();
    expect(within(ownerRow).getByRole('combobox', { name: '权限级别' })).toBeDisabled();
    expect(within(ownerRow).getByRole('button', { name: '移除' })).toBeDisabled();

    const memberRow = within(body).getByText('Beta').closest('tr')!;
    expect(within(memberRow).getByRole('combobox', { name: '权限级别' })).toBeEnabled();
    expect(within(memberRow).getByText('—')).toBeInTheDocument();

    expect(within(body).getByRole('textbox', { name: '工号（externalUserId）' })).toHaveValue('staff-1');
  });

  it('validates the profile form and saves the trimmed values', async () => {
    vi.spyOn(userAdminApi, 'getUserAdminDetail').mockResolvedValue(profile());
    vi.spyOn(userAdminApi, 'listUserAdminWorkspaces').mockResolvedValue([]);
    vi.spyOn(userAdminApi, 'listUserAdminImIdentities').mockResolvedValue([]);
    const update = vi.spyOn(userAdminApi, 'updateUserAdminProfile').mockResolvedValue(profile({ nickname: '鲍勃二世' }));
    renderDrawer();

    const body = await drawerBody();
    await userEvent.click(await within(body).findByRole('button', { name: '编辑资料' }));

    const dialog = await screen.findByRole('dialog', { name: '编辑用户资料' });
    const nicknameInput = within(dialog).getByLabelText('昵称');
    expect(nicknameInput).toHaveValue('鲍勃');

    await userEvent.clear(nicknameInput);
    await userEvent.click(within(dialog).getByRole('button', { name: /保\s*存/ }));
    expect(await within(dialog).findByText('请输入昵称')).toBeInTheDocument();
    expect(update).not.toHaveBeenCalled();

    await userEvent.type(nicknameInput, '  鲍勃二世  ');
    await userEvent.click(within(dialog).getByRole('button', { name: /保\s*存/ }));

    await waitFor(() => expect(update).toHaveBeenCalledWith(8, {
      nickname: '鲍勃二世',
      email: 'bob@example.com',
      phone: '13800000000',
    }));
    expect(successMock).toHaveBeenCalledWith('用户资料已更新');
    await waitFor(() => expect(screen.queryByRole('dialog', { name: '编辑用户资料' })).not.toBeInTheDocument());
  });

  it('blocks mismatching passwords and resets the password once they match', async () => {
    vi.spyOn(userAdminApi, 'getUserAdminDetail').mockResolvedValue(profile());
    vi.spyOn(userAdminApi, 'listUserAdminWorkspaces').mockResolvedValue([]);
    vi.spyOn(userAdminApi, 'listUserAdminImIdentities').mockResolvedValue([]);
    const reset = vi.spyOn(userAdminApi, 'resetUserAdminPassword').mockResolvedValue();
    renderDrawer();

    const body = await drawerBody();
    await userEvent.click(await within(body).findByRole('button', { name: '重置密码' }));

    const dialog = await screen.findByRole('dialog', { name: '重置用户密码' });
    await userEvent.type(within(dialog).getByLabelText('新密码'), 'abcdef');
    await userEvent.type(within(dialog).getByLabelText('确认新密码'), 'abcdeg');
    await userEvent.click(within(dialog).getByRole('button', { name: /保\s*存/ }));

    expect(await within(dialog).findByText('两次输入的密码不一致')).toBeInTheDocument();
    expect(reset).not.toHaveBeenCalled();

    await userEvent.clear(within(dialog).getByLabelText('确认新密码'));
    await userEvent.type(within(dialog).getByLabelText('确认新密码'), 'abcdef');
    await userEvent.click(within(dialog).getByRole('button', { name: /保\s*存/ }));

    await waitFor(() => expect(reset).toHaveBeenCalledWith(8, {
      newPassword: 'abcdef',
      confirmPassword: 'abcdef',
    }));
    expect(successMock).toHaveBeenCalledWith('密码已重置，并已通知该用户');
  });

  it('gates the delete confirmation on the exact username and reports the deletion', async () => {
    vi.spyOn(userAdminApi, 'getUserAdminDetail').mockResolvedValue(profile());
    vi.spyOn(userAdminApi, 'listUserAdminWorkspaces').mockResolvedValue([]);
    vi.spyOn(userAdminApi, 'listUserAdminImIdentities').mockResolvedValue([]);
    const remove = vi.spyOn(userAdminApi, 'deleteUserAdmin').mockResolvedValue();
    const { onChanged } = renderDrawer();

    const body = await drawerBody();
    await userEvent.click(await within(body).findByRole('button', { name: /删\s*除/ }));

    const dialog = await screen.findByRole('dialog', { name: '删除用户' });
    expect(within(dialog).getByText('删除后该用户立即无法登录，已签发的登录凭证立即失效。')).toBeInTheDocument();
    const confirm = within(dialog).getByRole('button', { name: '确认删除' });
    expect(confirm).toBeDisabled();

    await userEvent.type(within(dialog).getByRole('textbox', { name: '用户名' }), 'bob!');
    expect(confirm).toBeDisabled();
    await userEvent.clear(within(dialog).getByRole('textbox', { name: '用户名' }));
    await userEvent.type(within(dialog).getByRole('textbox', { name: '用户名' }), 'bob');
    expect(confirm).toBeEnabled();

    await userEvent.click(confirm);
    await waitFor(() => expect(remove).toHaveBeenCalledWith(8, 'bob'));
    expect(successMock).toHaveBeenCalledWith('用户已删除');
    expect(onChanged).toHaveBeenCalled();
  });

  it('auto-opens the restore dialog for a deleted user and keeps the reason inline on rejection', async () => {
    vi.spyOn(userAdminApi, 'getUserAdminDetail').mockResolvedValue(profile({
      id: 9,
      username: '#deleted-9',
      nickname: '卡罗',
      status: 'DELETED',
      editable: false,
      restorable: true,
      deletedAt: '2026-10-01T09:00:00',
      deletedByName: 'alice',
    }));
    const workspaces = vi.spyOn(userAdminApi, 'listUserAdminWorkspaces');
    const identities = vi.spyOn(userAdminApi, 'listUserAdminImIdentities');
    vi.spyOn(userAdminApi, 'restoreUserAdmin')
      .mockRejectedValueOnce(new ApiError('36005', '该用户未处于删除状态', 'trace-1'))
      .mockResolvedValueOnce();
    const { onChanged } = renderDrawer('restore');

    const dialog = await screen.findByRole('dialog', { name: '恢复用户' });
    expect(within(dialog).getByText('确认恢复用户 #deleted-9？')).toBeInTheDocument();
    expect(within(dialog).getByText('恢复后资料与密码保留，但空间权限与 IM 工号需重新配置。')).toBeInTheDocument();

    const body = await drawerBody();
    await within(body).findByText('已删除或已注销用户只读，不可编辑、删除或重置密码');
    expect(within(body).getByRole('button', { name: '编辑资料' })).toBeDisabled();
    expect(within(body).getByRole('button', { name: '重置密码' })).toBeDisabled();
    expect(within(body).getByRole('button', { name: /删\s*除/ })).toBeDisabled();
    expect(within(body).getByRole('button', { name: /恢\s*复/ })).toBeEnabled();
    expect(within(body).getByText('用户已删除，恢复后需重新配置空间权限')).toBeInTheDocument();
    expect(within(body).getByText('用户已删除，恢复后需重新配置 IM 工号')).toBeInTheDocument();

    await userEvent.click(within(dialog).getByRole('button', { name: '确认恢复' }));
    expect(await within(dialog).findByText('该用户未处于删除状态')).toBeInTheDocument();
    // The rejection stays in the dialog (spec 4.7), so no toast is fired.
    expect(errorMock).not.toHaveBeenCalled();

    await userEvent.click(within(dialog).getByRole('button', { name: '确认恢复' }));
    await waitFor(() => expect(successMock).toHaveBeenCalledWith('用户已恢复，可使用原密码登录'));
    await waitFor(() => expect(screen.queryByRole('dialog', { name: '恢复用户' })).not.toBeInTheDocument());
    expect(onChanged).toHaveBeenCalled();

    // A deleted account has no readable membership, so neither related list may be fetched.
    expect(workspaces).not.toHaveBeenCalled();
    expect(identities).not.toHaveBeenCalled();
  });

  it('closes from the header and forgets transient dialog state when reopened', async () => {
    vi.spyOn(userAdminApi, 'getUserAdminDetail').mockResolvedValue(profile());
    vi.spyOn(userAdminApi, 'listUserAdminWorkspaces').mockResolvedValue([]);
    vi.spyOn(userAdminApi, 'listUserAdminImIdentities').mockResolvedValue([]);
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });
    const onClose = vi.fn();
    const drawer = (open: boolean) => (
      <QueryClientProvider client={queryClient}>
        <UserDetailDrawer userId={8} open={open} onClose={onClose} onChanged={vi.fn()} />
      </QueryClientProvider>
    );
    const { rerender } = render(drawer(true));

    const body = await drawerBody();
    await userEvent.click(await within(body).findByRole('button', { name: /删\s*除/ }));
    const dialog = await screen.findByRole('dialog', { name: '删除用户' });
    await userEvent.type(within(dialog).getByRole('textbox', { name: '用户名' }), 'bob');

    await userEvent.click(screen.getByRole('button', { name: '关闭' }));
    expect(onClose).toHaveBeenCalled();

    rerender(drawer(false));
    rerender(drawer(true));
    await waitFor(() => expect(screen.queryByRole('dialog', { name: '删除用户' })).not.toBeInTheDocument());

    await userEvent.click(within(await drawerBody()).getByRole('button', { name: /删\s*除/ }));
    expect(await screen.findByRole('dialog', { name: '删除用户' })).toBeInTheDocument();
    expect(screen.getByRole('textbox', { name: '用户名' })).toHaveValue('');
  });

  it('changes a member access level and removes the membership through the confirmation', async () => {
    vi.spyOn(userAdminApi, 'getUserAdminDetail').mockResolvedValue(profile());
    vi.spyOn(userAdminApi, 'listUserAdminWorkspaces').mockResolvedValue([OWNER_WORKSPACE, MEMBER_WORKSPACE]);
    vi.spyOn(userAdminApi, 'listUserAdminImIdentities').mockResolvedValue([]);
    const updated = [OWNER_WORKSPACE, { ...MEMBER_WORKSPACE, accessLevel: 'ADMIN' as WorkspaceAccessLevel }];
    const level = vi.spyOn(userAdminApi, 'updateUserAdminWorkspaceLevel').mockResolvedValue(updated);
    const removed = [OWNER_WORKSPACE];
    const remove = vi.spyOn(userAdminApi, 'removeUserAdminWorkspace').mockResolvedValue(removed);
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'ADMIN');
    const { queryClient } = renderDrawer();

    const body = await drawerBody();
    const memberRow = (await within(body).findByText('Beta')).closest('tr')!;
    await userEvent.click(within(memberRow).getByRole('combobox', { name: '权限级别' }));
    await userEvent.click(await screen.findByText('管理', { selector: '.ant-select-item-option-content' }));

    await waitFor(() => expect(level).toHaveBeenCalledWith(8, 2, 'ADMIN'));
    await waitFor(() => expect(queryClient.getQueryData(userAdminApi.userAdminWorkspacesQueryKey(8))).toEqual(updated));
    expect(successMock).toHaveBeenCalledWith('权限级别已更新');

    const refreshedRow = (await within(await drawerBody()).findByText('Beta')).closest('tr')!;
    await userEvent.click(within(refreshedRow).getByRole('button', { name: '移除' }));
    expect(await screen.findByText('确认移除该用户在此空间的权限？')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: '确认删除' }));

    await waitFor(() => expect(remove).toHaveBeenCalledWith(8, 2));
    expect(successMock).toHaveBeenCalledWith('已移除空间权限');
    await waitFor(() => expect(queryClient.getQueryData(userAdminApi.userAdminWorkspacesQueryKey(8))).toEqual(removed));
  });

  it('denies the workspace removal confirmation for members without admin access', async () => {
    vi.spyOn(userAdminApi, 'getUserAdminDetail').mockResolvedValue(profile());
    vi.spyOn(userAdminApi, 'listUserAdminWorkspaces').mockResolvedValue([OWNER_WORKSPACE, MEMBER_WORKSPACE]);
    vi.spyOn(userAdminApi, 'listUserAdminImIdentities').mockResolvedValue([]);
    const remove = vi.spyOn(userAdminApi, 'removeUserAdminWorkspace').mockResolvedValue([OWNER_WORKSPACE]);
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_ONLY');
    renderDrawer();

    const body = await drawerBody();
    const memberRow = (await within(body).findByText('Beta')).closest('tr')!;
    await userEvent.click(within(memberRow).getByRole('button', { name: '移除' }));
    expect(await screen.findByText('确认移除该用户在此空间的权限？')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: '确认删除' }));

    await waitFor(() => expect(errorMock).toHaveBeenCalledWith('当前为只读权限，移除用户的空间权限需要管理员权限'));
    expect(remove).not.toHaveBeenCalled();
  });

  it('adds a workspace from the candidate list, skipping existing memberships', async () => {
    vi.spyOn(userAdminApi, 'getUserAdminDetail').mockResolvedValue(profile());
    vi.spyOn(userAdminApi, 'listUserAdminWorkspaces').mockResolvedValue([OWNER_WORKSPACE, MEMBER_WORKSPACE]);
    vi.spyOn(userAdminApi, 'listUserAdminImIdentities').mockResolvedValue([]);
    server.use(http.get('/api/workspaces/all', () => HttpResponse.json({
      success: true,
      code: '0',
      message: '',
      traceId: null,
      data: {
        list: [
          { id: 2, name: 'Beta', description: '', membershipStatus: 'NOT_MEMBER', accessLevel: null },
          { id: 11, name: 'Gamma', description: '', membershipStatus: 'NOT_MEMBER', accessLevel: null },
        ],
        total: 2,
        pageNum: 1,
        pageSize: 8,
      },
    })));
    const added = [OWNER_WORKSPACE, MEMBER_WORKSPACE, workspace({ workspaceId: 11, name: 'Gamma' })];
    const add = vi.spyOn(userAdminApi, 'addUserAdminWorkspace').mockResolvedValue(added);
    const { queryClient } = renderDrawer();

    const body = await drawerBody();
    await userEvent.click(await within(body).findByRole('combobox', { name: '选择空间' }));

    expect(await screen.findByText('Gamma', { selector: '.ant-select-item-option-content' })).toBeInTheDocument();
    expect(screen.queryByText('Beta', { selector: '.ant-select-item-option-content' })).not.toBeInTheDocument();

    await userEvent.click(screen.getByText('Gamma', { selector: '.ant-select-item-option-content' }));
    const addButton = within(await drawerBody()).getByRole('button', { name: /添\s*加/ });
    await waitFor(() => expect(addButton).toBeEnabled());
    await userEvent.click(addButton);

    await waitFor(() => expect(add).toHaveBeenCalledWith(8, 11, 'READ_ONLY'));
    expect(successMock).toHaveBeenCalledWith('已添加空间权限');
    await waitFor(() => expect(queryClient.getQueryData(userAdminApi.userAdminWorkspacesQueryKey(8))).toEqual(added));
  });

  it('falls back to raw tags for unknown status and level values and hides unbound IM actions', async () => {
    vi.spyOn(userAdminApi, 'getUserAdminDetail').mockResolvedValue(profile({
      nickname: null,
      email: null,
      phone: null,
      status: 'SUSPENDED' as unknown as UserAdminStatus,
    }));
    vi.spyOn(userAdminApi, 'listUserAdminWorkspaces').mockResolvedValue([
      workspace({ workspaceId: 3, name: 'Legacy', accessLevel: 'OWNER_LIKE' as unknown as WorkspaceAccessLevel }),
    ]);
    vi.spyOn(userAdminApi, 'listUserAdminImIdentities').mockResolvedValue([]);
    renderDrawer();

    const body = await drawerBody();
    const legacyRow = (await within(body).findByText('Legacy')).closest('tr')!;
    expect(within(body).getByText('SUSPENDED')).toBeInTheDocument();
    expect(within(legacyRow).getByText('OWNER_LIKE')).toBeInTheDocument();
    expect(within(legacyRow).getByText('—')).toBeInTheDocument();

    expect(within(body).getByText('保存工号后可测试发送')).toBeInTheDocument();
    expect(within(body).getByRole('button', { name: /保\s*存/ })).toBeDisabled();
    expect(within(body).getByRole('button', { name: '测试发送' })).toBeDisabled();
  });

  it('offers a retry when the profile fails to load', async () => {
    const detail = vi.spyOn(userAdminApi, 'getUserAdminDetail')
      .mockRejectedValueOnce(new ApiError('10500', '查询超时', 'trace-1'))
      .mockResolvedValueOnce(profile());
    vi.spyOn(userAdminApi, 'listUserAdminWorkspaces').mockResolvedValue([]);
    vi.spyOn(userAdminApi, 'listUserAdminImIdentities').mockResolvedValue([]);
    renderDrawer();

    const body = await drawerBody();
    expect(await within(body).findByText('用户列表加载失败')).toBeInTheDocument();
    expect(within(body).getByText('查询超时')).toBeInTheDocument();

    await userEvent.click(within(body).getByRole('button', { name: /重\s*试/ }));

    expect(await within(await drawerBody()).findByText('鲍勃')).toBeInTheDocument();
    expect(detail).toHaveBeenCalledTimes(2);
  });
});
