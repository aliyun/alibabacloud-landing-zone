import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { useAuthStore } from '@/shared/auth/store';
import { SsoCompletePage } from './SsoCompletePage';

function apiOk(data: unknown) {
  return { success: true, code: '0', message: '', traceId: null, data };
}

function apiFail(code: string, message: string) {
  return { success: false, code, message, data: null, traceId: null };
}

function loginPayload(accessToken: string) {
  return {
    userId: 1,
    accessToken,
    refreshToken: `${accessToken}-refresh`,
    user: { id: 1, username: 'alice', nickname: '爱丽丝', email: 'alice@example.com' },
  };
}

function LocationProbe() {
  const location = useLocation();
  return <div data-testid="route-probe">{`${location.pathname}${location.search}`}</div>;
}

function renderPage(entry: string) {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={[entry]}>
        <Routes>
          <Route path="/sso/complete" element={<SsoCompletePage />} />
          <Route path="*" element={<LocationProbe />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('SsoCompletePage', () => {
  beforeEach(() => {
    useAuthStore.getState().clear();
  });

  it('exchanges a one-time ticket for an AW session and enters the workspace list', async () => {
    let exchangedBody: unknown = null;
    server.use(
      http.post('/api/auth/sso/session', async ({ request }) => {
        exchangedBody = await request.json();
        return HttpResponse.json(apiOk(loginPayload('sso-access')));
      }),
    );

    renderPage('/sso/complete?ticket=t-1');

    expect(screen.getByTestId('sso-completing')).toBeInTheDocument();
    expect(screen.getByText('正在完成登录，请稍候…')).toBeInTheDocument();

    await waitFor(() => expect(screen.getByTestId('route-probe')).toHaveTextContent('/workspaces'));
    expect(exchangedBody).toEqual({ ticket: 't-1' });
    expect(useAuthStore.getState().accessToken).toBe('sso-access');
    expect(useAuthStore.getState().user?.username).toBe('alice');
  });

  it('keeps the pending state until the exchange settles', async () => {
    let release!: () => void;
    const gate = new Promise<void>((resolve) => { release = resolve; });
    server.use(
      http.post('/api/auth/sso/session', async () => {
        await gate;
        return HttpResponse.json(apiOk(loginPayload('sso-late')));
      }),
    );

    renderPage('/sso/complete?ticket=t-late');

    // Finish the gated request even when a pending-state assertion fails.
    try {
      expect(await screen.findByTestId('sso-completing')).toBeInTheDocument();
      expect(screen.queryByTestId('route-probe')).not.toBeInTheDocument();
      expect(useAuthStore.getState().accessToken).toBeNull();
    } finally {
      release();
    }

    await waitFor(() => expect(useAuthStore.getState().accessToken).toBe('sso-late'));
    expect(screen.getByTestId('route-probe')).toHaveTextContent('/workspaces');
  });

  it('surfaces a ticket exchange rejection without navigating', async () => {
    server.use(
      http.post('/api/auth/sso/session', () => {
        return HttpResponse.json(apiFail('36012', '绑定票据无效或已过期'), { status: 400 });
      }),
    );

    renderPage('/sso/complete?ticket=used-ticket');

    expect(await screen.findByTestId('sso-error')).toBeInTheDocument();
    expect(screen.getByText('暂时无法进入 AutoWonder')).toBeInTheDocument();
    expect(screen.getByText('绑定票据无效或已过期')).toBeInTheDocument();
    expect(screen.queryByTestId('route-probe')).not.toBeInTheDocument();
    expect(useAuthStore.getState().accessToken).toBeNull();

    await userEvent.click(screen.getByRole('button', { name: '返回登录页' }));
    await waitFor(() => expect(screen.getByTestId('route-probe')).toHaveTextContent('/login'));
  });

  it('keeps the existing session when the exchange rejects with 10401', async () => {
    useAuthStore.getState().setTokens('keep-token', 'keep-refresh');
    useAuthStore.getState().setUser({ id: 1, username: 'alice', nickname: '爱丽丝', email: 'alice@example.com' });
    server.use(
      http.post('/api/auth/sso/session', () => {
        return HttpResponse.json(apiFail('10401', '登录凭据已过期，请重新登录'), { status: 401 });
      }),
    );

    renderPage('/sso/complete?ticket=stale');

    expect(await screen.findByText('登录凭据已过期，请重新登录')).toBeInTheDocument();
    expect(useAuthStore.getState().accessToken).toBe('keep-token');
    expect(useAuthStore.getState().refreshToken).toBe('keep-refresh');
  });

  it('binds with trimmed credentials when the enterprise identity is not linked yet', async () => {
    let bindBody: unknown = null;
    server.use(
      http.post('/api/auth/sso/bind', async ({ request }) => {
        bindBody = await request.json();
        return HttpResponse.json(apiOk(loginPayload('bound-access')));
      }),
    );

    renderPage('/sso/complete?bind=bt-2');

    expect(screen.getByTestId('sso-bind')).toBeInTheDocument();
    expect(screen.getByText(/不根据邮箱自动合并/)).toBeInTheDocument();
    expect(screen.getByText('登录成功也不会自动授予工作空间成员资格。')).toBeInTheDocument();

    const user = userEvent.setup();
    await user.type(screen.getByLabelText('用户名'), ' alice ');
    await user.type(screen.getByLabelText('密码'), 'pass123');
    await user.click(screen.getByRole('button', { name: '绑定并登录' }));

    await waitFor(() => expect(screen.getByTestId('route-probe')).toHaveTextContent('/workspaces'));
    expect(bindBody).toEqual({ ticket: 'bt-2', username: 'alice', password: 'pass123' });
    expect(useAuthStore.getState().accessToken).toBe('bound-access');
  });

  it('blocks the bind request until credentials are provided', async () => {
    let bindCalls = 0;
    server.use(
      http.post('/api/auth/sso/bind', () => {
        bindCalls += 1;
        return HttpResponse.json(apiOk(loginPayload('never')));
      }),
    );

    renderPage('/sso/complete?bind=bt-3');

    await userEvent.click(screen.getByRole('button', { name: '绑定并登录' }));

    expect(await screen.findByText('请输入用户名')).toBeInTheDocument();
    expect(await screen.findByText('请输入密码')).toBeInTheDocument();
    expect(bindCalls).toBe(0);
    expect(screen.getByTestId('sso-bind')).toBeInTheDocument();
    expect(screen.queryByTestId('route-probe')).not.toBeInTheDocument();
  });

  it('shows the backend message when the credentials do not match an AW account', async () => {
    server.use(
      http.post('/api/auth/sso/bind', () => {
        return HttpResponse.json(apiFail('10401', '用户名或密码错误'), { status: 401 });
      }),
    );

    renderPage('/sso/complete?bind=bt-4');

    const user = userEvent.setup();
    await user.type(screen.getByLabelText('用户名'), 'alice');
    await user.type(screen.getByLabelText('密码'), 'wrong');
    await user.click(screen.getByRole('button', { name: '绑定并登录' }));

    expect(await screen.findByText('用户名或密码错误')).toBeInTheDocument();
    expect(screen.getByTestId('sso-bind')).toBeInTheDocument();
    expect(useAuthStore.getState().accessToken).toBeNull();
  });

  it('claims the verified identity for the signed-in account', async () => {
    useAuthStore.getState().setTokens('keep-token', 'keep-refresh');
    let claimBody: unknown = null;
    server.use(
      http.post('/api/users/me/federated-identities/claim', async ({ request }) => {
        claimBody = await request.json();
        return HttpResponse.json(apiOk({
          id: 21,
          providerId: 3,
          providerName: '企业 ADFS',
          protocol: 'SAML2',
          issuer: 'https://adfs.corp.example/adfs',
          subject: 'subject-001',
          displayName: 'Alice',
          email: 'alice@corp.example',
          lastLoginAt: null,
          gmtCreate: '2026-10-16 10:00:00',
        }));
      }),
    );

    renderPage('/sso/complete?bind=bt-5');

    expect(screen.getByTestId('sso-claim')).toBeInTheDocument();
    expect(screen.getByText(/将当前企业身份绑定到正在使用的账号/)).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: '绑定到当前账号' }));

    await waitFor(() => expect(screen.getByTestId('sso-bound')).toBeInTheDocument());
    expect(claimBody).toEqual({ ticket: 'bt-5' });
    expect(screen.getByText('企业身份已绑定到当前账号，可在个人设置中查看。')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: '返回个人设置' }));
    await waitFor(() => expect(screen.getByTestId('route-probe')).toHaveTextContent('/profile/settings?tab=federated'));
  });

  it('reports a claim rejection while keeping the claimed ticket usable', async () => {
    useAuthStore.getState().setTokens('keep-token', 'keep-refresh');
    let claimCalls = 0;
    server.use(
      http.post('/api/users/me/federated-identities/claim', () => {
        claimCalls += 1;
        return HttpResponse.json(apiFail('36006', '该企业身份已绑定到其他账号'), { status: 409 });
      }),
    );

    renderPage('/sso/complete?bind=bt-6');

    await userEvent.click(screen.getByRole('button', { name: '绑定到当前账号' }));

    expect(await screen.findByText('该企业身份已绑定到其他账号')).toBeInTheDocument();
    expect(claimCalls).toBe(1);
    expect(screen.getByTestId('sso-claim')).toBeInTheDocument();
    expect(screen.queryByTestId('sso-bound')).not.toBeInTheDocument();
  });

  it('returns to the profile tab after a successful bound callback while signed in', async () => {
    useAuthStore.getState().setTokens('keep-token', 'keep-refresh');

    renderPage('/sso/complete?bound=1');

    expect(screen.getByTestId('sso-bound')).toBeInTheDocument();
    expect(screen.getByText('绑定成功，之后可使用该企业身份登录 AutoWonder。')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '返回个人设置' })).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: '返回个人设置' }));
    await waitFor(() => expect(screen.getByTestId('route-probe')).toHaveTextContent('/profile/settings?tab=federated'));
  });

  it('returns to the login page after a bound callback without a session', async () => {
    renderPage('/sso/complete?bound=1');

    expect(screen.getByTestId('sso-bound')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '返回登录页' })).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: '返回登录页' }));
    await waitFor(() => expect(screen.getByTestId('route-probe')).toHaveTextContent('/login'));
  });

  it.each([
    ['36001', '该企业登录方式当前不可用，请联系管理员。'],
    ['36002', '企业身份验证失败，请重新发起登录。'],
    ['36003', '企业身份验证失败，请重新发起登录。'],
    ['36004', '该登录请求已失效或被重复使用，请重新发起登录。'],
    ['36005', '该登录请求已失效或被重复使用，请重新发起登录。'],
    ['36006', '该企业身份已绑定到其他 AutoWonder 账号，如需合并请联系管理员。'],
    ['36007', '该账号已被禁用，请联系工作空间管理员。'],
    ['36009', '该企业登录方式当前不可用，请联系管理员。'],
    ['36012', '登录凭据已失效，请重新发起登录。'],
    ['99999', '登录未完成，请重新发起登录。'],
  ])('maps callback error code %s to its message', (code, message) => {
    renderPage(`/sso/complete?error=${code}`);

    expect(screen.getByTestId('sso-error')).toBeInTheDocument();
    expect(screen.getByText('暂时无法进入 AutoWonder')).toBeInTheDocument();
    expect(screen.getByText(message)).toBeInTheDocument();
  });

  it('falls back to the generic failure when the callback carries no parameters', () => {
    renderPage('/sso/complete');

    expect(screen.getByTestId('sso-error')).toBeInTheDocument();
    expect(screen.getByText('登录未完成，请重新发起登录。')).toBeInTheDocument();
  });
});
