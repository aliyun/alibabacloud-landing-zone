import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { useAuthStore } from '@/shared/auth/store';
import { i18n } from '@/shared/i18n';
import { ApiError } from '@/shared/types/common';
import { DeactivationPanel } from './DeactivationPanel';
import * as profileApi from './profileApi';

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

const username = 'alice';

function renderPanel() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const router = createMemoryRouter(
    [
      { path: '/profile/settings', element: <DeactivationPanel /> },
      { path: '/login', element: <div>login</div> },
    ],
    { initialEntries: ['/profile/settings'] },
  );
  const utils = render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  return { queryClient, router, ...utils };
}

async function openConfirmModal() {
  await userEvent.click(await screen.findByRole('button', { name: '注销账号' }));
  return screen.getByRole('textbox', { name: '输入用户名' });
}

describe('DeactivationPanel', () => {
  beforeEach(async () => {
    document.body.innerHTML = '';
    successMock.mockClear();
    errorMock.mockClear();
    await i18n.changeLanguage('zh-CN');
    useAuthStore.getState().clear();
    useAuthStore.getState().setTokens('test-access', 'test-refresh');
    useAuthStore.getState().setUser({ id: 1, username, nickname: '爱丽丝', email: 'alice@example.com' });
  });
  afterEach(async () => {
    vi.restoreAllMocks();
    await i18n.changeLanguage('zh-CN');
  });

  it('states the immediate-effect rules and only enables the confirm once the exact username is typed', async () => {
    const remove = vi.spyOn(profileApi, 'deleteMyAccount').mockResolvedValue();
    renderPanel();

    const input = await openConfirmModal();
    // The warning copy also renders on the panel; pin the copy that belongs to the dialog.
    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByText('此操作将立即删除您的账号（数据保留）')).toBeInTheDocument();
    expect(within(dialog).getByText('2. 删除立即生效，无法在个人中心自行恢复')).toBeInTheDocument();

    const confirm = screen.getByRole('button', { name: '确认注销' });
    expect(confirm).toBeDisabled();
    await userEvent.type(input, 'alice-other');
    expect(confirm).toBeDisabled();
    await userEvent.clear(input);
    await userEvent.type(input, username);
    expect(confirm).toBeEnabled();

    await userEvent.click(screen.getByRole('button', { name: '取消' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(remove).not.toHaveBeenCalled();
    const reopened = await openConfirmModal();
    expect(reopened).toHaveValue('');
  });

  it('submits the typed confirmation and reports the deletion with the completion-language copy', async () => {
    const remove = vi.spyOn(profileApi, 'deleteMyAccount').mockResolvedValue();
    const { router } = renderPanel();

    const input = await openConfirmModal();
    await userEvent.type(input, username);
    await i18n.changeLanguage('en-US');
    await userEvent.click(screen.getByRole('button', { name: 'Confirm deletion' }));

    await waitFor(() => expect(remove).toHaveBeenCalledWith(username));
    expect(successMock).toHaveBeenCalledWith('Account deleted');
    expect(useAuthStore.getState().accessToken).toBeNull();
    await waitFor(() => expect(router.state.location.pathname).toBe('/login'));
  });

  it('keeps the session and surfaces the server reason when deletion is blocked', async () => {
    const blocked = new ApiError('29002', '存在未完结工单，无法注销', 'trace-1');
    vi.spyOn(profileApi, 'deleteMyAccount').mockRejectedValue(blocked);
    const { router } = renderPanel();

    const input = await openConfirmModal();
    await userEvent.type(input, username);
    await userEvent.click(screen.getByRole('button', { name: '确认注销' }));

    await waitFor(() => expect(errorMock).toHaveBeenCalledWith('存在未完结工单，无法注销'));
    expect(useAuthStore.getState().accessToken).toBe('test-access');
    expect(router.state.location.pathname).toBe('/profile/settings');
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });

  it('falls back to the localized failure copy for rejections that carry no message', async () => {
    vi.spyOn(profileApi, 'deleteMyAccount').mockRejectedValue('gateway down');
    renderPanel();

    const input = await openConfirmModal();
    await userEvent.type(input, username);
    await userEvent.click(screen.getByRole('button', { name: '确认注销' }));

    await waitFor(() => expect(errorMock).toHaveBeenCalledWith('注销失败'));
  });
});
