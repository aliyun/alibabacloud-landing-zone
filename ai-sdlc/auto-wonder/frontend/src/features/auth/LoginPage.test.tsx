import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { LoginPage } from './LoginPage';
import { useAuthStore } from '@/shared/auth/store';

function renderLogin() {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><MemoryRouter>
      <LoginPage />
    </MemoryRouter></QueryClientProvider>,
  );
}

describe('LoginPage', () => {
  beforeEach(() => {
    useAuthStore.getState().clear();
  });

  it('renders login form', () => {
    renderLogin();
    expect(screen.getAllByRole('button', { name: '切换外观' })).toHaveLength(1);
    expect(screen.getByLabelText(/用户名/)).toBeInTheDocument();
    expect(screen.getByLabelText(/密码/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /登\s*录/ })).toBeInTheDocument();
  });

  it('shows the platform introduction and delivery diagram', () => {
    renderLogin();
    expect(screen.getByText('AI Native SDLC Platform')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: /软件产品\s*自动交付平台/ })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '登录' })).toBeInTheDocument();
    expect(screen.queryByText('继续推进你的自主交付工作台')).not.toBeInTheDocument();
    expect(screen.queryByText('上手步骤')).not.toBeInTheDocument();
    const diagram = screen.getByRole('figure', { name: /软件自动交付流程示意/ });
    expect(within(diagram).getAllByRole('listitem').map((item) => item.textContent)).toEqual([
      '需求澄清', '启动', '开发', '验证', '评审', '部署', '测试', '交付',
    ]);
    const accentFlags = within(diagram).getAllByRole('listitem')
      .map((item) => item.querySelector('.auth-delivery-label')!.className
        .includes('auth-delivery-label--accent'));
    expect(accentFlags).toEqual([false, false, true, true, true, true, true, true]);
    expect(screen.queryByRole('button', { name: '暂停流程动画' })).not.toBeInTheDocument();
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

  it('shows backend message when login fails with HTTP 401', async () => {
    server.use(
      http.post('/api/auth/login', () => {
        return HttpResponse.json({
          success: false,
          code: '10401',
          message: '用户名或密码错误',
          data: null,
          traceId: 'trace-y',
        }, { status: 401 });
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
    expect(screen.queryByText(/未登录或登录已失效/)).not.toBeInTheDocument();
  });
});
