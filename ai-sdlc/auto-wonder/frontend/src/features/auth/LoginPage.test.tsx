import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import { i18n } from '@/shared/i18n';
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
  afterEach(async () => { vi.restoreAllMocks(); await act(async () => { await i18n.changeLanguage('zh-CN'); }); });
  beforeEach(() => {
    useAuthStore.getState().clear();
  });

  it('renders login form', () => {
    renderLogin();
    expect(screen.getAllByRole('button', { name: '切换外观' })).toHaveLength(1);
    expect(screen.getByLabelText(/用户名/)).toBeInTheDocument();
    expect(screen.getByLabelText(/密码/, { selector: 'input' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /登\s*录/ })).toBeInTheDocument();
  });

  it('keeps the pending submit action localized and raw failure readable through an in-flight language change', async () => {
    let release!: () => void; let started = false;
    const pending = new Promise<void>(resolve => { release = resolve; });
    server.use(http.post('/api/auth/login', async () => { started = true; await pending; return HttpResponse.json({ success: false, code: '10401', message: '<raw 登录错误>', data: null }); }));
    renderLogin(); const user = userEvent.setup();
    await user.type(screen.getByLabelText('用户名'), 'raw user');
    await user.type(screen.getByLabelText('密码'), 'raw secret');
    await user.click(screen.getByRole('button', { name: /登\s*录/ }));
    await waitFor(() => expect(started).toBe(true));
    // Finish the fake request even when a pending-state assertion fails.
    try {
      expect(screen.getByRole('button', { name: '登录' })).toBeInTheDocument();
      expect(screen.queryByRole('img', { name: 'loading' })).not.toBeInTheDocument();
      await act(async () => { await i18n.changeLanguage('en-US'); });
      expect(screen.getByRole('button', { name: 'Sign in' })).toBeInTheDocument();
      expect(screen.getByLabelText('Password')).toHaveValue('raw secret');
    } finally { await act(async () => { release(); }); }
    expect(await screen.findByText('<raw 登录错误>')).toBeInTheDocument();
  });

  it.each(['zh-CN', 'en-US'])('keeps the raw password and keyboard toggle through live locale changes from %s', async locale => {
    const errors = vi.spyOn(console, 'error');
    await act(async () => { await i18n.changeLanguage(locale); });
    renderLogin(); const user = userEvent.setup();
    const password = screen.getByLabelText(locale === 'zh-CN' ? '密码' : 'Password');
    await act(async () => { await user.type(password, '<raw secret>'); });
    await act(async () => { await user.tab(); });
    const show = screen.getByRole('button', { name: locale === 'zh-CN' ? '显示密码' : 'Show password' });
    expect(show).toHaveFocus();
    await act(async () => { await user.keyboard('{Enter}'); });
    expect(password).toHaveAttribute('type', 'text');
    expect(password).toHaveValue('<raw secret>');
    expect(password).toHaveFocus();
    await act(async () => { await i18n.changeLanguage(locale === 'zh-CN' ? 'en-US' : 'zh-CN'); });
    expect(screen.getByLabelText(locale === 'zh-CN' ? 'Password' : '密码')).toBe(password);
    const hide = screen.getByRole('button', { name: locale === 'zh-CN' ? 'Hide password' : '隐藏密码' });
    expect(password).toHaveFocus();
    await act(async () => { await user.tab(); });
    expect(hide).toHaveFocus();
    await act(async () => { await user.keyboard('{Enter}'); });
    expect(password).toHaveAttribute('type', 'password');
    expect(password).toHaveValue('<raw secret>');
    expect(screen.queryByRole('img', { name: /eye|user|lock/ })).not.toBeInTheDocument();
    expect(errors.mock.calls.filter(args => args.some(value => typeof value === 'string' && (value.includes('not wrapped in act') || value.includes('not configured to support act'))))).toHaveLength(0);
  });

  it('shows the platform introduction and delivery diagram', () => {
    renderLogin();
    expect(screen.getByText('AI Native SDLC Platform')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: /软件产品\s*自动交付平台/ })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '登录' })).toBeInTheDocument();
    expect(screen.queryByText('继续推进你的自主交付工作台')).not.toBeInTheDocument();
    expect(screen.queryByText('上手步骤')).not.toBeInTheDocument();
    const diagram = screen.getByRole('figure', { name: /软件自动交付流程示意/ });
    expect(within(diagram).queryAllByRole('img')).toHaveLength(0);
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
    await user.type(screen.getByLabelText(/密码/, { selector: 'input' }), 'pass123');
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
    await user.type(screen.getByLabelText(/密码/, { selector: 'input' }), 'wrong');
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
    await user.type(screen.getByLabelText(/密码/, { selector: 'input' }), 'wrong');
    await user.click(screen.getByRole('button', { name: /登\s*录/ }));

    await waitFor(() => {
      expect(screen.getByText(/用户名或密码错误/)).toBeInTheDocument();
    });
    expect(screen.queryByText(/未登录或登录已失效/)).not.toBeInTheDocument();
  });

  it('lists enabled enterprise connections as link entries above the local form', async () => {
    server.use(
      http.get('/api/auth/sso/providers', () => {
        return HttpResponse.json({
          success: true,
          code: '0',
          message: '',
          traceId: null,
          data: [
            { providerId: 3, name: '企业 ADFS', protocol: 'SAML2', idpName: 'adfs.corp.example' },
            { providerId: 5, name: 'Contoso', protocol: 'OIDC', idpName: 'entra.example' },
          ],
        });
      }),
    );

    renderLogin();

    const first = await screen.findByRole('link', { name: '使用 企业 ADFS 登录' });
    expect(first).toHaveAttribute('href', '/api/auth/sso/3/start');
    expect(screen.getByRole('link', { name: '使用 Contoso 登录' })).toHaveAttribute('href', '/api/auth/sso/5/start');
    expect(screen.getByText('选择公司的身份系统登录 AutoWonder')).toBeInTheDocument();
    expect(screen.getByText('或')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '使用 AutoWonder 账号登录' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '登录' })).toBeInTheDocument();
    expect(screen.getByLabelText(/用户名/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /登\s*录/ })).toBeInTheDocument();
  });

  it('hides the enterprise section and keeps the local form when no connection is enabled', async () => {
    let providersFetched = false;
    let loginCalled = false;
    server.use(
      http.get('/api/auth/sso/providers', () => {
        providersFetched = true;
        return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: [] });
      }),
      http.post('/api/auth/login', () => {
        loginCalled = true;
        return HttpResponse.json({
          success: true,
          code: '0',
          message: '',
          traceId: null,
          data: {
            userId: 1,
            accessToken: 'acc-2',
            refreshToken: 'ref-2',
            user: { id: 1, username: 'bob', nickname: '鲍勃', email: 'bob@example.com' },
          },
        });
      }),
    );

    renderLogin();

    expect(await screen.findByLabelText(/用户名/)).toBeInTheDocument();
    await waitFor(() => expect(providersFetched).toBe(true));
    expect(screen.queryByText('选择公司的身份系统登录 AutoWonder')).not.toBeInTheDocument();
    expect(screen.queryByText('使用 AutoWonder 账号登录')).not.toBeInTheDocument();
    expect(screen.queryByText('或')).not.toBeInTheDocument();

    const user = userEvent.setup();
    await user.type(screen.getByLabelText(/用户名/), 'bob');
    await user.type(screen.getByLabelText(/密码/, { selector: 'input' }), 'pass123');
    await user.click(screen.getByRole('button', { name: /登\s*录/ }));

    await waitFor(() => expect(loginCalled).toBe(true));
    expect(useAuthStore.getState().accessToken).toBe('acc-2');
  });

  it('keeps the local login usable when the enabled connections cannot be loaded', async () => {
    let providersFetched = false;
    let loginCalled = false;
    server.use(
      http.get('/api/auth/sso/providers', () => {
        providersFetched = true;
        return HttpResponse.json(
          { success: false, code: '10002', message: '服务暂时不可用', data: null, traceId: null },
          { status: 500 },
        );
      }),
      http.post('/api/auth/login', () => {
        loginCalled = true;
        return HttpResponse.json({
          success: true,
          code: '0',
          message: '',
          traceId: null,
          data: {
            userId: 1,
            accessToken: 'acc-3',
            refreshToken: 'ref-3',
            user: { id: 1, username: 'bob', nickname: '鲍勃', email: 'bob@example.com' },
          },
        });
      }),
    );

    renderLogin();

    await waitFor(() => expect(providersFetched).toBe(true));
    expect(screen.queryByText('选择公司的身份系统登录 AutoWonder')).not.toBeInTheDocument();
    expect(screen.queryByText('服务暂时不可用')).not.toBeInTheDocument();

    const user = userEvent.setup();
    await user.type(screen.getByLabelText(/用户名/), 'bob');
    await user.type(screen.getByLabelText(/密码/, { selector: 'input' }), 'pass123');
    await user.click(screen.getByRole('button', { name: /登\s*录/ }));

    await waitFor(() => expect(loginCalled).toBe(true));
    expect(useAuthStore.getState().accessToken).toBe('acc-3');
  });
});
