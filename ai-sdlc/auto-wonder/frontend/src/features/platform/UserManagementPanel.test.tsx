import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { useAuthStore } from '@/shared/auth/store';
import { i18n } from '@/shared/i18n';
import { ApiError } from '@/shared/types/common';
import { UserManagementPanel } from './UserManagementPanel';
import * as userAdminApi from './userAdminApi';
import type { UserAdminList, UserAdminUser } from './userAdminApi';

function user(overrides: Partial<UserAdminUser> = {}): UserAdminUser {
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

function payload(list: UserAdminUser[], overrides: Partial<UserAdminList> = {}): UserAdminList {
  return {
    totalAll: 9,
    total: list.length,
    pageNum: 1,
    pageSize: 20,
    list,
    ...overrides,
  };
}

const ROSTER: UserAdminUser[] = [
  user(),
  user({
    id: 9,
    username: '#deleted-9',
    nickname: '卡罗',
    status: 'DELETED',
    editable: false,
    restorable: true,
    deletedAt: '2026-10-01T09:00:00',
    deletedByName: 'alice',
  }),
  user({
    id: 7,
    username: 'dave',
    nickname: '戴夫',
    status: 'DEACTIVATED',
    isAdmin: true,
    editable: false,
    restorable: false,
  }),
];

function renderPanel() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  const utils = render(
    <QueryClientProvider client={queryClient}>
      <UserManagementPanel />
    </QueryClientProvider>,
  );
  return { queryClient, ...utils };
}

function stubRelatedReads() {
  vi.spyOn(userAdminApi, 'getUserAdminDetail').mockResolvedValue(user());
  vi.spyOn(userAdminApi, 'listUserAdminWorkspaces').mockResolvedValue([]);
  vi.spyOn(userAdminApi, 'listUserAdminImIdentities').mockResolvedValue([]);
}

describe('UserManagementPanel', () => {
  beforeEach(async () => {
    document.body.innerHTML = '';
    vi.restoreAllMocks();
    await i18n.changeLanguage('zh-CN');
    useAuthStore.getState().clear();
    useAuthStore.getState().setTokens('test-access', 'test-refresh');
    useAuthStore.getState().setUser({ id: 1, username: 'alice', nickname: '爱丽丝', email: 'alice@example.com', isAdmin: true });
  });
  afterEach(async () => {
    await i18n.changeLanguage('zh-CN');
  });

  it('lists every user with its status, type and only the actions its state allows', async () => {
    const list = vi.spyOn(userAdminApi, 'getUserAdminList').mockResolvedValue(payload(ROSTER));
    renderPanel();

    expect(await screen.findByText('鲍勃')).toBeInTheDocument();
    expect(list.mock.calls[0][0]).toEqual({ keyword: '', status: 'ALL', type: 'ALL', page: 1, size: 20 });
    expect(screen.getByText('全部用户 9 人')).toBeInTheDocument();
    expect(screen.getByText('筛选结果 3 条')).toBeInTheDocument();

    const normalRow = screen.getByText('鲍勃').closest('tr')!;
    expect(within(normalRow).getByText('正常')).toBeInTheDocument();
    expect(within(normalRow).getByText('普通用户')).toBeInTheDocument();
    expect(within(normalRow).getByRole('button', { name: '删除' })).toBeEnabled();
    expect(within(normalRow).queryByRole('button', { name: '恢复' })).not.toBeInTheDocument();

    // A deleted row keeps its pre-deletion nickname, offers only restore, and never delete.
    const deletedRow = screen.getByText('卡罗').closest('tr')!;
    expect(within(deletedRow).getByText('已删除')).toBeInTheDocument();
    expect(within(deletedRow).getByRole('button', { name: '恢复' })).toBeEnabled();
    expect(within(deletedRow).queryByRole('button', { name: '删除' })).not.toBeInTheDocument();

    const deactivatedRow = screen.getByText('戴夫').closest('tr')!;
    expect(within(deactivatedRow).getByText('已注销')).toBeInTheDocument();
    expect(within(deactivatedRow).getByText('平台管理员')).toBeInTheDocument();
    expect(within(deactivatedRow).queryByRole('button', { name: '删除' })).not.toBeInTheDocument();
    expect(within(deactivatedRow).queryByRole('button', { name: '恢复' })).not.toBeInTheDocument();
  });

  it('drives the list query from the search box, both filters and the reset button', async () => {
    const list = vi.spyOn(userAdminApi, 'getUserAdminList').mockResolvedValue(payload(ROSTER));
    renderPanel();
    await screen.findByText('鲍勃');

    const search = screen.getByLabelText('搜索');
    await userEvent.type(search, '  car  {enter}');
    await waitFor(() => expect(list.mock.calls.at(-1)![0]).toMatchObject({ keyword: 'car', page: 1 }));

    await userEvent.click(screen.getByRole('combobox', { name: '状态' }));
    await userEvent.click(await screen.findByText('已删除', { selector: '.ant-select-item-option-content' }));
    await waitFor(() => expect(list.mock.calls.at(-1)![0]).toMatchObject({ keyword: 'car', status: 'DELETED', page: 1 }));

    await userEvent.click(screen.getByRole('combobox', { name: '类型' }));
    await userEvent.click(await screen.findByText('平台管理员', { selector: '.ant-select-item-option-content' }));
    await waitFor(() => expect(list.mock.calls.at(-1)![0]).toMatchObject({ status: 'DELETED', type: 'ADMIN', page: 1 }));

    await userEvent.click(screen.getByRole('button', { name: /重置筛选/ }));
    await waitFor(() => expect(list.mock.calls.at(-1)![0]).toEqual({ keyword: '', status: 'ALL', type: 'ALL', page: 1, size: 20 }));
    expect(search).toHaveValue('');
  });

  it('opens the read-only detail drawer from the row action', async () => {
    vi.spyOn(userAdminApi, 'getUserAdminList').mockResolvedValue(payload(ROSTER));
    const detail = vi.spyOn(userAdminApi, 'getUserAdminDetail').mockResolvedValue(user({ id: 9, username: '#deleted-9', nickname: '卡罗', status: 'DELETED', editable: false, restorable: true }));
    const workspaces = vi.spyOn(userAdminApi, 'listUserAdminWorkspaces');
    renderPanel();

    const deletedRow = (await screen.findByText('卡罗')).closest('tr')!;
    await userEvent.click(within(deletedRow).getByRole('button', { name: '详情' }));

    expect(await screen.findByText('用户详情')).toBeInTheDocument();
    await waitFor(() => expect(detail).toHaveBeenCalledWith(9));

    const drawerBody = await waitFor(() => {
      const body = document.querySelector('.ant-drawer-body');
      expect(body).not.toBeNull();
      return body as HTMLElement;
    });
    // The drawer is the only place that renders the read-only hint and the deleted-state
    // placeholder sections, so they scope the assertions away from the list's own buttons.
    expect(await within(drawerBody).findByText('已删除或已注销用户只读，不可编辑、删除或重置密码')).toBeInTheDocument();
    expect(within(drawerBody).getByText('用户已删除，恢复后需重新配置空间权限')).toBeInTheDocument();
    expect(within(drawerBody).getByRole('button', { name: /恢\s*复/ })).toBeEnabled();
    expect(within(drawerBody).getByRole('button', { name: /删\s*除/ })).toBeDisabled();
    // Deleted accounts have no readable membership, so the drawer must not fetch the workspace list.
    expect(workspaces).not.toHaveBeenCalled();
  });

  it('opens the delete confirmation straight from the row action for an editable user', async () => {
    vi.spyOn(userAdminApi, 'getUserAdminList').mockResolvedValue(payload(ROSTER));
    stubRelatedReads();
    renderPanel();

    const normalRow = (await screen.findByText('鲍勃')).closest('tr')!;
    await userEvent.click(within(normalRow).getByRole('button', { name: '删除' }));

    expect(await screen.findByText('删除用户')).toBeInTheDocument();
    const confirm = screen.getByRole('button', { name: '确认删除' });
    expect(confirm).toBeDisabled();
    await userEvent.type(screen.getByRole('textbox', { name: '用户名' }), 'bob');
    expect(confirm).toBeEnabled();
  });

  it('shows the failure alert with the server reason and recovers on retry', async () => {
    const list = vi.spyOn(userAdminApi, 'getUserAdminList')
      .mockRejectedValueOnce(new ApiError('10500', '服务器开小差了', 'trace-1'))
      .mockResolvedValue(payload(ROSTER));
    renderPanel();

    expect(await screen.findByText('用户列表加载失败')).toBeInTheDocument();
    expect(screen.getByText('服务器开小差了')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: /重\s*试/ }));

    expect(await screen.findByText('鲍勃')).toBeInTheDocument();
    expect(list).toHaveBeenCalledTimes(2);
  });

  it('renders the dedicated empty copy when no user matches the filters', async () => {
    vi.spyOn(userAdminApi, 'getUserAdminList').mockResolvedValue(payload([], { total: 0 }));
    renderPanel();

    // The table shows its empty copy while the query is pending; wait for the settled totals first.
    expect(await screen.findByText('全部用户 9 人')).toBeInTheDocument();
    expect(screen.getByText('暂无用户')).toBeInTheDocument();
  });
});
