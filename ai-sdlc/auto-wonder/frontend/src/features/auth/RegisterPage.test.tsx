import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, describe, expect, it } from 'vitest';
import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { i18n } from '@/shared/i18n';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { RegisterPage } from './RegisterPage';

function ok<T>(data: T) {
  return { success: true, code: '0', message: '', traceId: null, data };
}

function LocationProbe() {
  const location = useLocation();
  return <span data-testid="register-target">{location.pathname}</span>;
}

function renderRegister() {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><MemoryRouter>
      <RegisterPage />
      <LocationProbe />
    </MemoryRouter></QueryClientProvider>,
  );
}

describe('RegisterPage', () => {
  afterEach(async () => { await act(async () => { await i18n.changeLanguage('zh-CN'); }); });
  it('renders registration form inside the product command center landing', () => {
    renderRegister();
    expect(screen.getAllByRole('button', { name: '切换外观' })).toHaveLength(1);
    expect(screen.getByText('AI Native SDLC Platform')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: /软件产品\s*自动交付平台/ })).toBeInTheDocument();
    expect(screen.getByText('创建平台账号')).toBeInTheDocument();
    expect(screen.getByLabelText(/用户名/)).toBeInTheDocument();
    expect(screen.getByLabelText(/昵称/)).toBeInTheDocument();
    expect(screen.getByLabelText(/邮箱/)).toBeInTheDocument();
    expect(screen.getByLabelText(/密码/, { selector: 'input' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /注\s*册/ })).toBeInTheDocument();
  });

  it('registers normally and opens the login page when review is disabled', async () => {
    server.use(http.post('/api/auth/register', () => HttpResponse.json(ok({
      id: 13,
      username: 'carol',
      nickname: '卡罗',
      email: 'carol@example.com',
      reviewRequired: false,
      administratorContacts: null,
    }))));
    renderRegister();
    const user = userEvent.setup();
    await user.type(screen.getByLabelText(/用户名/), 'carol');
    await user.type(screen.getByLabelText(/昵称/), '卡罗');
    await user.type(screen.getByLabelText(/邮箱/), 'carol@example.com');
    await user.type(screen.getByLabelText(/密码/, { selector: 'input' }), 'secret123');

    await user.click(screen.getByRole('button', { name: /注\s*册/ }));

    await waitFor(() => expect(screen.getByTestId('register-target')).toHaveTextContent('/login'));
    expect(screen.queryByText('注册成功，等待审核')).not.toBeInTheDocument();
  });

  it('falls back to the generic contact hint when a pending response has no contacts', async () => {
    server.use(http.post('/api/auth/register', () => HttpResponse.json(ok({
      id: 14,
      username: 'carol',
      nickname: '卡罗',
      email: 'carol@example.com',
      reviewRequired: true,
      administratorContacts: null,
    }))));
    renderRegister();
    const user = userEvent.setup();
    await user.type(screen.getByLabelText(/用户名/), 'carol');
    await user.type(screen.getByLabelText(/昵称/), '卡罗');
    await user.type(screen.getByLabelText(/邮箱/), 'carol@example.com');
    await user.type(screen.getByLabelText(/密码/, { selector: 'input' }), 'secret123');
    await user.click(screen.getByRole('button', { name: /注\s*册/ }));

    expect(await screen.findByText('注册成功，等待审核')).toBeInTheDocument();
    expect(screen.getByText('暂无可用平台管理员，请联系平台管理员处理。')).toBeInTheDocument();
  });

  it('shows administrator contacts for a pending registration and then opens the login page', async () => {
    server.use(http.post('/api/auth/register', () => HttpResponse.json(ok({
      id: 12,
      username: 'carol',
      nickname: '卡罗',
      email: 'carol@example.com',
      reviewRequired: true,
      administratorContacts: [{ nickname: '周宁', email: 'zhou@example.com' }],
    }))));
    renderRegister();
    const user = userEvent.setup();
    await user.type(screen.getByLabelText(/用户名/), 'carol');
    await user.type(screen.getByLabelText(/昵称/), '卡罗');
    await user.type(screen.getByLabelText(/邮箱/), 'carol@example.com');
    await user.type(screen.getByLabelText(/密码/, { selector: 'input' }), 'secret123');

    await user.click(screen.getByRole('button', { name: /注\s*册/ }));

    expect(await screen.findByText('注册成功，等待审核')).toBeInTheDocument();
    expect(screen.getAllByText('新用户注册请联系管理员审核后登录').length).toBeGreaterThan(0);
    expect(screen.getByText('zhou@example.com')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /确\s*定/ }));
    await waitFor(() => expect(screen.getByTestId('register-target')).toHaveTextContent('/login'));
  });

  it('replaces the form with a closed notice when self-registration is disabled', async () => {
    server.use(http.get('/api/auth/sso/config', () => HttpResponse.json(ok({
      registrationEnabled: false,
      defaultProviderId: null,
    }))));

    renderRegister();

    expect(await screen.findByText('平台已关闭自行注册')).toBeInTheDocument();
    expect(screen.getByText('如需开通账号，请联系平台管理员。')).toBeInTheDocument();
    expect(screen.queryByLabelText(/用户名/)).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: '返回登录' })).toBeInTheDocument();
  });

  it.each(['zh-CN', 'en-US'])('keeps the registration password raw with a keyboard toggle from %s', async locale => {
    await act(async () => { await i18n.changeLanguage(locale); });
    renderRegister(); const user = userEvent.setup();
    const password = screen.getByLabelText(locale === 'zh-CN' ? '密码' : 'Password');
    await user.type(password, '<raw secret>'); await user.tab();
    expect(screen.getByRole('button', { name: locale === 'zh-CN' ? '显示密码' : 'Show password' })).toHaveFocus();
    await user.keyboard('{Enter}');
    expect(password).toHaveAttribute('type', 'text');
    expect(password).toHaveFocus();
    await act(async () => { await i18n.changeLanguage(locale === 'zh-CN' ? 'en-US' : 'zh-CN'); });
    expect(password).toHaveFocus();
    await user.tab();
    expect(screen.getByRole('button', { name: locale === 'zh-CN' ? 'Hide password' : '隐藏密码' })).toHaveFocus();
    await user.keyboard('{Enter}');
    expect(password).toHaveAttribute('type', 'password');
    expect(password).toHaveValue('<raw secret>');
    expect(screen.queryByRole('img', { name: /eye/ })).not.toBeInTheDocument();
  });
});
