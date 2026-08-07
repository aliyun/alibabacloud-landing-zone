import { describe, expect, it } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { ChangePasswordPanel } from './ChangePasswordPanel';

function renderPanel() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <ChangePasswordPanel />
    </QueryClientProvider>,
  );
}

describe('ChangePasswordPanel', () => {
  it('renders password form fields', async () => {
    renderPanel();
    expect(await screen.findByPlaceholderText('请输入当前密码')).toBeInTheDocument();
    expect(screen.getByPlaceholderText('请输入新密码')).toBeInTheDocument();
    expect(screen.getByPlaceholderText('请再次输入新密码')).toBeInTheDocument();
    expect(screen.getByText('修改密码')).toBeInTheDocument();
  });

  it('shows validation error when passwords do not match', async () => {
    const user = userEvent.setup();
    renderPanel();

    await user.type(screen.getByPlaceholderText('请输入当前密码'), 'oldPass1');
    await user.type(screen.getByPlaceholderText('请输入新密码'), 'newPass1');
    await user.type(screen.getByPlaceholderText('请再次输入新密码'), 'different');
    await user.click(screen.getByText('修改密码'));

    expect(await screen.findByText('两次输入的密码不一致')).toBeInTheDocument();
  });

  it('submits successfully with valid data', async () => {
    const user = userEvent.setup();
    server.use(
      http.put('/api/users/me/password', async ({ request }) => {
        const body = (await request.json()) as Record<string, string>;
        if (body.oldPassword === 'oldPass1' && body.newPassword === 'newPass123') {
          return HttpResponse.json({
            success: true, code: '0', message: '', traceId: null, data: null,
          });
        }
        return HttpResponse.json({
          success: false, code: '10401', message: '旧密码不正确', traceId: null, data: null,
        }, { status: 200 });
      }),
    );

    renderPanel();

    await user.type(screen.getByPlaceholderText('请输入当前密码'), 'oldPass1');
    await user.type(screen.getByPlaceholderText('请输入新密码'), 'newPass123');
    await user.type(screen.getByPlaceholderText('请再次输入新密码'), 'newPass123');
    await user.click(screen.getByText('修改密码'));

    expect(await screen.findByText('密码已成功修改，请使用新密码登录。')).toBeInTheDocument();
  });

  it('shows error when old password is wrong', async () => {
    const user = userEvent.setup();
    server.use(
      http.put('/api/users/me/password', () => HttpResponse.json({
        success: false, code: '10401', message: '旧密码不正确', traceId: null, data: null,
      })),
    );

    renderPanel();

    await user.type(screen.getByPlaceholderText('请输入当前密码'), 'wrongOld');
    await user.type(screen.getByPlaceholderText('请输入新密码'), 'newPass123');
    await user.type(screen.getByPlaceholderText('请再次输入新密码'), 'newPass123');
    await user.click(screen.getByText('修改密码'));

    await waitFor(() => {
      expect(document.querySelector('.ant-message-error')).toBeTruthy();
    }, { timeout: 3000 });
    expect((screen.getByPlaceholderText('请输入当前密码') as HTMLInputElement).value).toBe('wrongOld');
  });
});
