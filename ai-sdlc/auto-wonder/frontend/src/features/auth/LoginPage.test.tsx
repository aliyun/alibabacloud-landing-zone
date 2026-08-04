import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { LoginPage } from './LoginPage';
import { useAuthStore } from '@/shared/auth/store';

function renderLogin() {
  return render(
    <MemoryRouter>
      <LoginPage />
    </MemoryRouter>,
  );
}

describe('LoginPage', () => {
  beforeEach(() => {
    useAuthStore.getState().clear();
  });

  it('renders login form', () => {
    renderLogin();
    expect(screen.getByLabelText(/用户名/)).toBeInTheDocument();
    expect(screen.getByLabelText(/密码/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /登\s*录/ })).toBeInTheDocument();
  });

  it('renders the product command center landing narrative', () => {
    renderLogin();
    expect(screen.getByText('AutoWonder · Agent SDLC')).toBeInTheDocument();
    expect(screen.getByText('登录后，把工单交给数字员工小队')).toBeInTheDocument();
    expect(screen.getByText('工单系统、仓库、SDLC、执行器和组织记忆在入口第一屏形成完整认知。')).toBeInTheDocument();
    expect(screen.getByText('6')).toBeInTheDocument();
    expect(screen.getByText('上手步骤')).toBeInTheDocument();
    expect(screen.getByText('3')).toBeInTheDocument();
    expect(screen.getByText('Agent 角色')).toBeInTheDocument();
    expect(screen.getByText('∞')).toBeInTheDocument();
    expect(screen.getByText('记忆沉淀')).toBeInTheDocument();
  });

  it('calls login API and stores authenticated user on success', async () => {
    server.use(
      http.post('/api/auth/login', () => {
        return HttpResponse.json({
          success: true,
          code: '0',
          message: '',
          data: {
            userId: 1,
            accessToken: 'acc-1',
            refreshToken: 'ref-1',
            user: {
              id: 1,
              username: 'caihe',
              nickname: '蔡何',
              email: 'caihe@example.com',
            },
          },
          traceId: null,
        });
      }),
    );

    renderLogin();
    const user = userEvent.setup();
    await user.type(screen.getByLabelText(/用户名/), 'alice');
    await user.type(screen.getByLabelText(/密码/), 'pass123');
    await user.click(screen.getByRole('button', { name: /登\s*录/ }));

    await waitFor(() => {
      expect(useAuthStore.getState().accessToken).toBe('acc-1');
    });
    expect(useAuthStore.getState().user).toEqual({
      id: 1,
      username: 'caihe',
      nickname: '蔡何',
      email: 'caihe@example.com',
    });
  });

  it('shows error message on login failure', async () => {
    server.use(
      http.post('/api/auth/login', () => {
        return HttpResponse.json({
          success: false,
          code: '10401',
          message: '用户名或密码错误',
          data: null,
          traceId: 'trace-x',
        });
      }),
    );

    renderLogin();
    const user = userEvent.setup();
    await user.type(screen.getByLabelText(/用户名/), 'bob');
    await user.type(screen.getByLabelText(/密码/), 'wrong');
    await user.click(screen.getByRole('button', { name: /登\s*录/ }));

    await waitFor(() => {
      expect(screen.getByText(/用户名或密码错误/)).toBeInTheDocument();
    });
  });
});
