import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { act, render, screen, waitFor } from '@testing-library/react';
import { i18n } from '@/shared/i18n';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { LoginPage } from './LoginPage';
import { beginSsoLogin } from './ssoApi';
import { useAuthStore } from '@/shared/auth/store';

const { beginSsoLoginMock } = vi.hoisted(() => ({ beginSsoLoginMock: vi.fn() }));

vi.mock('./ssoApi', async (importOriginal) => {
  const actual = await importOriginal<typeof import('./ssoApi')>();
  return { ...actual, beginSsoLogin: beginSsoLoginMock };
});

function ok<T>(data: T) {
  return { success: true, code: '0', message: '', traceId: null, data };
}

function LocationProbe() {
  const location = useLocation();
  return <span data-testid="login-target">{location.pathname}{location.search}</span>;
}

function renderLogin(initialEntry: string | { pathname: string; state?: unknown } = '/login') {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={[initialEntry]}>
        <LoginPage />
        <LocationProbe />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

function serveSso(defaultProviderId: number | null, registrationEnabled = true) {
  server.use(
    http.get('/api/auth/sso/providers', () => HttpResponse.json(ok([
      { providerId: 3, name: '统一认证', protocol: 'SAML2', idpName: 'SANY' },
      { providerId: 5, name: '备用认证', protocol: 'SAML2', idpName: 'SANY' },
    ]))),
    http.get('/api/auth/sso/config', () => HttpResponse.json(ok({
      registrationEnabled,
      defaultProviderId,
    }))),
  );
}

describe('LoginPage SSO auto start', () => {
  beforeEach(() => {
    useAuthStore.getState().clear();
    window.sessionStorage.clear();
    beginSsoLoginMock.mockClear();
  });

  afterEach(async () => {
    vi.restoreAllMocks();
    await act(async () => { await i18n.changeLanguage('zh-CN'); });
  });

  it('starts the configured default connection with the remembered return target', async () => {
    serveSso(3);

    renderLogin({
      pathname: '/login',
      state: { from: { pathname: '/workitems/42', search: '?tab=discussion', hash: '' } },
    });

    await waitFor(() => expect(beginSsoLoginMock).toHaveBeenCalledWith(
      '/api/auth/sso/3/start?next=%2Fworkitems%2F42%3Ftab%3Ddiscussion',
    ));
    expect(await screen.findByText(/正在跳转到企业登录/)).toBeInTheDocument();
    expect(beginSsoLogin).toBe(beginSsoLoginMock);
  });

  it('does not auto start when the default connection is not in the enabled list', async () => {
    serveSso(99);

    renderLogin();

    await screen.findByRole('link', { name: '使用 统一认证 登录' });
    await act(async () => { await Promise.resolve(); });
    expect(beginSsoLoginMock).not.toHaveBeenCalled();
  });

  it('leaves the local entry available and skips auto start when local=1 is requested', async () => {
    serveSso(3);

    renderLogin('/login?local=1');

    expect(await screen.findByLabelText(/用户名/)).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('link', { name: '使用 统一认证 登录' })).toBeInTheDocument());
    expect(beginSsoLoginMock).not.toHaveBeenCalled();
  });

  it('still auto starts when the local marker is not exactly 1', async () => {
    serveSso(3);

    renderLogin('/login?local=0');

    await waitFor(() => expect(beginSsoLoginMock).toHaveBeenCalledWith('/api/auth/sso/3/start'));
  });

  it('hides the registration entry when the platform closed self-registration', async () => {
    serveSso(null, false);

    renderLogin();

    await screen.findByRole('link', { name: '使用 统一认证 登录' });
    expect(screen.queryByRole('link', { name: '没有账号？去注册' })).not.toBeInTheDocument();
  });

  it('keeps the registration entry when the platform config cannot be loaded', async () => {
    server.use(
      http.get('/api/auth/sso/providers', () => HttpResponse.json(ok([
        { providerId: 3, name: '统一认证', protocol: 'SAML2', idpName: 'SANY' },
      ]))),
      http.get('/api/auth/sso/config', () => HttpResponse.json(
        { success: false, code: '10000', message: 'boom', data: null, traceId: null },
        { status: 500 },
      )),
    );

    renderLogin();

    expect(await screen.findByRole('link', { name: '没有账号？去注册' })).toBeInTheDocument();
    expect(beginSsoLoginMock).not.toHaveBeenCalled();
  });
});
