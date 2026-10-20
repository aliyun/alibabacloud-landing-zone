import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { useAuthStore } from '@/shared/auth/store';
import { ProfileSettingsPage } from './ProfileSettingsPage';

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

function payload(data: unknown) {
  return { success: true, code: '0', message: '', traceId: null, data };
}

function fail(code: string, message: string) {
  return { success: false, code, message, data: null, traceId: null };
}

function identity(overrides: Record<string, unknown> = {}) {
  return {
    id: 11,
    providerId: 7,
    providerName: '企业 ADFS',
    protocol: 'SAML2',
    issuer: 'https://adfs.corp.example/adfs',
    subject: 'subject-001',
    displayName: 'Alice',
    email: 'alice@corp.example',
    lastLoginAt: '2026-10-16 09:30:00',
    gmtCreate: '2026-10-01 08:00:00',
    ...overrides,
  };
}

function provider(overrides: Record<string, unknown> = {}) {
  return { providerId: 7, name: '企业 ADFS', protocol: 'SAML2', idpName: 'adfs.corp.example', ...overrides };
}

function imIdentityPayload() {
  return payload([
    { provider: 'DINGTALK', externalUserId: 'staff-42', configured: true, platformReady: true, testAvailable: true },
  ]);
}

function renderProfileTab() {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={['/profile/settings?tab=federated']}>
        <ProfileSettingsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('FederatedIdentitiesPanel', () => {
  beforeEach(() => {
    successMock.mockClear();
    errorMock.mockClear();
    useAuthStore.getState().clear();
    server.use(
      http.get('/api/users/me/im-identities', () => HttpResponse.json(imIdentityPayload())),
    );
  });

  it('lists bound identities and only offers unbound providers for binding', async () => {
    server.use(
      http.get('/api/users/me/federated-identities', () => HttpResponse.json(payload([identity()]))),
      http.get('/api/auth/sso/providers', () => HttpResponse.json(payload([
        provider(),
        provider({ providerId: 9, name: 'Contoso', protocol: 'OIDC', idpName: 'entra.example' }),
      ]))),
    );

    renderProfileTab();

    expect(await screen.findByText('企业 ADFS')).toBeInTheDocument();
    expect(screen.getByText('alice@corp.example')).toBeInTheDocument();
    expect(screen.getByText('SAML2')).toBeInTheDocument();
    expect(screen.getByTestId('federated-identity-11')).toBeInTheDocument();
    expect(screen.queryByTestId('federated-empty')).not.toBeInTheDocument();
    expect(screen.queryByTestId('federated-bind-7')).not.toBeInTheDocument();
    expect(await screen.findByTestId('federated-bind-9')).toBeInTheDocument();
    expect(screen.getByText('绑定 Contoso')).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: '企业身份' })).toHaveAttribute('aria-selected', 'true');
  });

  it('shows the empty hint and still offers every enabled provider', async () => {
    server.use(
      http.get('/api/auth/sso/providers', () => HttpResponse.json(payload([provider()]))),
    );

    renderProfileTab();

    expect(await screen.findByTestId('federated-empty')).toBeInTheDocument();
    expect(screen.getByText('尚未绑定企业身份')).toBeInTheDocument();
    expect(await screen.findByTestId('federated-bind-7')).toBeInTheDocument();
  });

  it('surfaces the backend message when identities cannot be loaded', async () => {
    server.use(
      http.get('/api/users/me/federated-identities', () => {
        return HttpResponse.json(fail('10002', '读取失败，请稍后重试'), { status: 500 });
      }),
    );

    renderProfileTab();

    expect(await screen.findByText('企业身份加载失败')).toBeInTheDocument();
    expect(screen.getByText('读取失败，请稍后重试')).toBeInTheDocument();
    expect(screen.queryByTestId('federated-empty')).not.toBeInTheDocument();
  });

  it('starts a protected binding with the provider id and leaves the page on success', async () => {
    let bindBody: unknown = null;
    server.use(
      http.get('/api/auth/sso/providers', () => HttpResponse.json(payload([
        provider({ providerId: 9, name: 'Contoso', protocol: 'OIDC', idpName: 'entra.example' }),
      ]))),
      http.post('/api/users/me/federated-identities/bind-start', async ({ request }) => {
        bindBody = await request.json();
        return HttpResponse.json(payload({ authorizeUrl: 'https://entra.example/authorize?state=s-1' }));
      }),
    );

    renderProfileTab();

    await userEvent.click(await screen.findByTestId('federated-bind-9'));

    await waitFor(() => expect(bindBody).toEqual({ providerId: 9 }));
    expect(successMock).not.toHaveBeenCalled();
    expect(errorMock).not.toHaveBeenCalled();
  });

  it('reports a failed binding start with the backend message', async () => {
    server.use(
      http.get('/api/auth/sso/providers', () => HttpResponse.json(payload([
        provider({ providerId: 9, name: 'Contoso', protocol: 'OIDC', idpName: 'entra.example' }),
      ]))),
      http.post('/api/users/me/federated-identities/bind-start', () => {
        return HttpResponse.json(fail('36009', '企业登录方式不可用'), { status: 400 });
      }),
    );

    renderProfileTab();

    await userEvent.click(await screen.findByTestId('federated-bind-9'));

    await waitFor(() => expect(errorMock).toHaveBeenCalledWith('企业登录方式不可用'));
    expect(successMock).not.toHaveBeenCalled();
  });

  it('falls back to the localized hint when the binding start fails without a message', async () => {
    server.use(
      http.get('/api/auth/sso/providers', () => HttpResponse.json(payload([
        provider({ providerId: 9, name: 'Contoso', protocol: 'OIDC', idpName: 'entra.example' }),
      ]))),
      http.post('/api/users/me/federated-identities/bind-start', () => {
        return HttpResponse.json(fail('36009', ''), { status: 400 });
      }),
    );

    renderProfileTab();

    await userEvent.click(await screen.findByTestId('federated-bind-9'));

    await waitFor(() => expect(errorMock).toHaveBeenCalledWith('无法发起绑定，请稍后重试'));
  });

  it('unbinds an identity after confirmation and refreshes the list', async () => {
    let listCalls = 0;
    let deletedId: string | null = null;
    server.use(
      http.get('/api/users/me/federated-identities', () => {
        listCalls += 1;
        return HttpResponse.json(payload([identity()]));
      }),
      http.delete('/api/users/me/federated-identities/:id', ({ params }) => {
        deletedId = String(params.id);
        return HttpResponse.json(payload(null));
      }),
    );

    renderProfileTab();

    await screen.findByTestId('federated-identity-11');
    expect(listCalls).toBe(1);

    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: /^解\s*绑$/ }));
    await user.click(await screen.findByRole('button', { name: '确认解绑' }));

    await waitFor(() => expect(successMock).toHaveBeenCalledWith('企业身份已解绑'));
    expect(deletedId).toBe('11');
    await waitFor(() => expect(listCalls).toBe(2));
  });

  it('keeps the identity listed and reports the failure when unbinding is rejected', async () => {
    server.use(
      http.get('/api/users/me/federated-identities', () => HttpResponse.json(payload([identity()]))),
      http.delete('/api/users/me/federated-identities/:id', () => {
        return HttpResponse.json(fail('36010', '该身份正在被其他账号使用'), { status: 409 });
      }),
    );

    renderProfileTab();

    await screen.findByTestId('federated-identity-11');

    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: /^解\s*绑$/ }));
    await user.click(await screen.findByRole('button', { name: '确认解绑' }));

    await waitFor(() => expect(errorMock).toHaveBeenCalledWith('该身份正在被其他账号使用'));
    expect(successMock).not.toHaveBeenCalled();
    expect(screen.getByTestId('federated-identity-11')).toBeInTheDocument();
  });
});
