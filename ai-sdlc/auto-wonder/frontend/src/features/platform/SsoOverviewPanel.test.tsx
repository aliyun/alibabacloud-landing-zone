import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { act, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { i18n } from '@/shared/i18n';
import { SsoOverviewPanel } from './SsoOverviewPanel';

function ok<T>(data: T) {
  return { success: true, code: '0', message: '', traceId: null, data };
}

// 概览端点之外的字段（端点、证书、密钥掩码）即使被误返回，界面也不应渲染。
const overviewPayload = {
  idps: [
    { id: 1, name: '集团 AD FS', enabled: 1 },
    { id: 2, name: '备用 IdP', enabled: 0 },
  ],
  connections: [
    {
      id: 11,
      idpId: 1,
      idpName: '集团 AD FS',
      name: '员工 SSO',
      protocol: 'OIDC',
      enabled: 1,
      clientSecretConfigured: true,
      issuer: 'https://hidden.example.com',
      oidcAuthorizeUrl: 'https://hidden.example.com/authorize',
      clientSecretMasked: 'kc:v1:***',
    },
    {
      id: 12,
      idpId: 2,
      idpName: null,
      name: '遗留 SAML',
      protocol: 'SAML2',
      enabled: 0,
      clientSecretConfigured: false,
      samlCert: 'HIDDEN-CERT',
    },
    {
      id: 13,
      idpId: 1,
      idpName: '集团 AD FS',
      name: '待启用 OIDC',
      protocol: 'OIDC',
      enabled: 0,
      clientSecretConfigured: false,
      oidcTokenUrl: 'https://hidden.example.com/token',
    },
  ],
};

function renderPanel() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <SsoOverviewPanel />
    </QueryClientProvider>,
  );
}

async function language(locale: string) {
  await act(async () => { await i18n.changeLanguage(locale); });
}

describe('SsoOverviewPanel', () => {
  beforeEach(async () => {
    await i18n.changeLanguage('zh-CN');
    server.use(
      http.get('/api/platform/federation/overview', () => HttpResponse.json(ok(overviewPayload))),
    );
  });

  afterEach(async () => {
    await i18n.changeLanguage('zh-CN');
  });

  it('renders only the whitelisted read-only overview without management controls', async () => {
    let adminRequests = 0;
    server.use(
      http.get('/api/platform/federation/idps', () => {
        adminRequests += 1;
        return HttpResponse.json(ok([]));
      }),
      http.get('/api/platform/federation/connections', () => {
        adminRequests += 1;
        return HttpResponse.json(ok([]));
      }),
    );

    renderPanel();

    expect(await screen.findByText('员工 SSO')).toBeInTheDocument();
    expect(screen.getAllByText('集团 AD FS').length).toBeGreaterThan(0);
    expect(screen.getByText('备用 IdP')).toBeInTheDocument();
    expect(screen.getByText('遗留 SAML')).toBeInTheDocument();
    expect(screen.getByText('待启用 OIDC')).toBeInTheDocument();
    expect(screen.getByText('SAML 2.0')).toBeInTheDocument();
    expect(screen.getAllByText('OIDC')).toHaveLength(2);
    expect(screen.getAllByText('已启用')).toHaveLength(2);
    expect(screen.getAllByText('未启用')).toHaveLength(3);
    // OIDC 连接显示「是否已配置密钥」；SAML 不使用客户端密钥，显示占位符。
    expect(screen.getByText('密钥已配置')).toBeInTheDocument();
    expect(screen.getByText('密钥未配置')).toBeInTheDocument();
    expect(screen.getAllByText('—')).toHaveLength(2);
    expect(screen.getByText('SSO 登录方式由平台管理员维护，你只能查看概览，无法修改配置。')).toBeInTheDocument();
    expect(within(screen.getByRole('alert')).queryByRole('img')).not.toBeInTheDocument();

    for (const hidden of [
      'https://hidden.example.com',
      'https://hidden.example.com/authorize',
      'https://hidden.example.com/token',
      'kc:v1:***',
      'HIDDEN-CERT',
    ]) {
      expect(screen.queryByText(hidden)).toBeNull();
    }
    expect(screen.queryByRole('button', { name: '新增 IdP' })).toBeNull();
    expect(screen.queryByRole('button', { name: '新增连接' })).toBeNull();
    expect(screen.queryByRole('button', { name: '配置' })).toBeNull();
    expect(screen.queryByRole('button', { name: '回调信息' })).toBeNull();
    expect(screen.queryAllByRole('switch')).toHaveLength(0);
    // 只读概览只调用白名单端点，不触达管理面接口。
    expect(adminRequests).toBe(0);
  });

  it('translates the read-only overview in both languages', async () => {
    renderPanel();

    expect(await screen.findByText('员工 SSO')).toBeInTheDocument();
    expect(screen.getByRole('columnheader', { name: '密钥状态' })).toBeInTheDocument();
    expect(screen.getByText('密钥已配置')).toBeInTheDocument();

    await language('en-US');

    expect(screen.getByText(
      'SSO sign-in methods are maintained by platform administrators. You can view this overview only and cannot change the configuration.',
    )).toBeInTheDocument();
    expect(screen.getByRole('columnheader', { name: 'Secret status' })).toBeInTheDocument();
    expect(screen.getByRole('columnheader', { name: 'Connection name' })).toBeInTheDocument();
    expect(screen.getByText('Secret configured')).toBeInTheDocument();
    expect(screen.getByText('Secret not configured')).toBeInTheDocument();
    expect(screen.getAllByText('Enabled')).toHaveLength(2);
    expect(screen.getAllByText('Disabled')).toHaveLength(3);
    expect(screen.getByText('SAML 2.0')).toBeInTheDocument();

    await language('zh-CN');

    expect(screen.getByText('SSO 登录方式由平台管理员维护，你只能查看概览，无法修改配置。')).toBeInTheDocument();
    expect(screen.getByRole('columnheader', { name: '密钥状态' })).toBeInTheDocument();
  });

  it('recovers from a load failure through the retry action', async () => {
    let attempts = 0;
    server.use(
      http.get('/api/platform/federation/overview', () => {
        attempts += 1;
        return attempts === 1
          ? HttpResponse.json({ success: false, code: '10500', message: 'overview unavailable', traceId: null, data: null })
          : HttpResponse.json(ok({ idps: [], connections: [] }));
      }),
    );

    renderPanel();

    expect(await screen.findByText('overview unavailable')).toBeInTheDocument();
    expect(screen.getByText('企业 SSO 配置加载失败')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /重\s*试/ }));

    expect(await screen.findByText('尚未配置身份提供方')).toBeInTheDocument();
    expect(screen.getByText('尚未配置登录连接')).toBeInTheDocument();
    expect(attempts).toBe(2);
  });
});
