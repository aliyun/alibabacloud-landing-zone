import { describe, expect, it, beforeEach, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { message } from 'antd';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { SsoConfigPanel } from './SsoConfigPanel';
import { DEFAULT_BRANDING, type PlatformBranding } from './brandingApi';
import type { FederationConnection, FederationIdp } from './ssoApi';

function ok<T>(data: T) {
  return { success: true, code: '0', message: '', traceId: null, data };
}

function fail(messageText: string, code = '10000') {
  return { success: false, code, message: messageText, traceId: null, data: null };
}

function makeIdp(overrides: Partial<FederationIdp> = {}): FederationIdp {
  return { id: 1, name: '集团 AD FS', description: '主 IdP', enabled: 1, ...overrides };
}

function makeConnection(overrides: Partial<FederationConnection> = {}): FederationConnection {
  return {
    id: 11,
    idpId: 1,
    idpName: '集团 AD FS',
    name: '员工 SSO',
    protocol: 'SAML2',
    enabled: 1,
    issuer: 'https://adfs.corp.example.com',
    samlSsoUrl: 'https://adfs.corp.example.com/adfs/ls',
    samlCert: 'CERT',
    oidcAuthorizeUrl: null,
    oidcTokenUrl: null,
    oidcJwksUrl: null,
    clientId: null,
    clientSecretMasked: null,
    clientSecretConfigured: false,
    scopes: null,
    subjectClaim: null,
    nameClaim: null,
    emailClaim: null,
    allowedTenants: null,
    sortOrder: 0,
    ...overrides,
  };
}

function serveLists(idps: FederationIdp[], connections: FederationConnection[]) {
  server.use(
    http.get('/api/platform/federation/idps', () => HttpResponse.json(ok(idps))),
    http.get('/api/platform/federation/connections', () => HttpResponse.json(ok(connections))),
  );
}

function branding(overrides: Partial<PlatformBranding> = {}): PlatformBranding {
  return { ...DEFAULT_BRANDING, ...overrides };
}

function renderPanel(brandingConfig?: PlatformBranding) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return {
    queryClient,
    ...render(
      <QueryClientProvider client={queryClient}>
        <SsoConfigPanel branding={brandingConfig} />
      </QueryClientProvider>,
    ),
  };
}

async function chooseOption(label: string, optionText: string) {
  await userEvent.click(screen.getByRole('combobox', { name: label }));
  const dropdown = await waitFor(() => {
    const open = Array.from(document.querySelectorAll('.ant-select-dropdown')).filter(
      (node) => !node.classList.contains('ant-select-dropdown-hidden'),
    );
    if (open.length === 0) throw new Error('select dropdown is not open');
    return open[open.length - 1] as HTMLElement;
  });
  await userEvent.click(
    await within(dropdown).findByText(optionText, { selector: '.ant-select-item-option-content' }),
  );
}

describe('SsoConfigPanel', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
    vi.restoreAllMocks();
    // antd static message portals into body; keep the holder out of the torn-down DOM.
    vi.spyOn(message, 'success').mockImplementation(
      () => undefined as unknown as ReturnType<typeof message.success>,
    );
    vi.spyOn(message, 'error').mockImplementation(
      () => undefined as unknown as ReturnType<typeof message.error>,
    );
    vi.spyOn(message, 'info').mockImplementation(
      () => undefined as unknown as ReturnType<typeof message.info>,
    );
    window.localStorage.clear();
  });

  it('renders idps and connections and previews only enabled connections of enabled idps in server order', async () => {
    serveLists(
      [makeIdp(), makeIdp({ id: 2, name: '备用 IdP', description: null, enabled: 0 })],
      [
        makeConnection(),
        makeConnection({ id: 12, idpId: 2, idpName: '备用 IdP', name: '备用 SSO' }),
        makeConnection({ id: 13, protocol: 'OIDC', name: '旧 SSO', enabled: 0 }),
        makeConnection({ id: 14, name: '访客 SSO' }),
      ],
    );

    renderPanel();

    expect((await screen.findAllByText('集团 AD FS')).length).toBeGreaterThan(0);
    expect(screen.getAllByText('备用 IdP').length).toBeGreaterThan(0);
    expect(screen.getByText('—')).toBeInTheDocument();
    expect(screen.getAllByText('SAML 2.0')).toHaveLength(3);
    expect(screen.getByText('OIDC')).toBeInTheDocument();

    const previewButtons = await screen.findAllByRole('button', { name: /使用 .* 登录/ });
    expect(previewButtons.map((button) => button.textContent)).toEqual([
      '使用 员工 SSO 登录',
      '使用 访客 SSO 登录',
    ]);
    expect(screen.queryByRole('button', { name: '使用 备用 SSO 登录' })).toBeNull();
    expect(screen.queryByRole('button', { name: '使用 旧 SSO 登录' })).toBeNull();
  });

  it('creates a disabled SAML connection with trimmed values and refreshes both lists before reporting success', async () => {
    const idps = [makeIdp()];
    let createdBody: Record<string, unknown> | null = null;
    let idpGets = 0;
    let connectionGets = 0;
    server.use(
      http.get('/api/platform/federation/idps', () => {
        idpGets += 1;
        return HttpResponse.json(ok(idps));
      }),
      http.get('/api/platform/federation/connections', () => {
        connectionGets += 1;
        return HttpResponse.json(ok([]));
      }),
      http.post('/api/platform/federation/connections', async ({ request }) => {
        createdBody = await request.json() as Record<string, unknown>;
        return HttpResponse.json(ok(makeConnection({ id: 21, name: String(createdBody.name) })));
      }),
    );

    renderPanel();

    await userEvent.click(await screen.findByRole('button', { name: '新增连接' }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByRole('combobox', { name: '所属 IdP' })).not.toBeDisabled();
    expect(within(dialog).getByRole('switch', { name: '启用该登录方式' })).not.toBeChecked();

    await userEvent.type(within(dialog).getByLabelText('连接名称'), '  新建 SSO  ');
    await userEvent.type(within(dialog).getByLabelText('Issuer'), ' https://idp.example.com ');
    await userEvent.type(within(dialog).getByLabelText('SAML SSO 地址'), ' https://idp.example.com/sso ');
    await userEvent.type(within(dialog).getByLabelText('签名证书'), 'CERT');
    await userEvent.type(within(dialog).getByLabelText('用户标识声明'), 'sub');
    await userEvent.click(within(dialog).getByRole('button', { name: '保存连接' }));

    await waitFor(() => expect(createdBody).not.toBeNull());
    expect(createdBody).toMatchObject({
      idpId: 1,
      protocol: 'SAML2',
      name: '新建 SSO',
      enabled: 0,
      issuer: 'https://idp.example.com',
      samlSsoUrl: 'https://idp.example.com/sso',
      samlCert: 'CERT',
      subjectClaim: 'sub',
      nameClaim: '',
      emailClaim: '',
    });
    expect(createdBody).not.toHaveProperty('oidcAuthorizeUrl');
    expect(createdBody).not.toHaveProperty('clientSecret');
    await waitFor(() => expect(message.success).toHaveBeenCalledWith('登录连接已创建'));
    expect(idpGets).toBeGreaterThanOrEqual(2);
    expect(connectionGets).toBeGreaterThanOrEqual(2);
  });

  it('requires a client secret for OIDC and sends only OIDC fields', async () => {
    let createdBody: Record<string, unknown> | null = null;
    server.use(
      http.get('/api/platform/federation/idps', () => HttpResponse.json(ok([makeIdp()]))),
      http.get('/api/platform/federation/connections', () => HttpResponse.json(ok([]))),
      http.post('/api/platform/federation/connections', async ({ request }) => {
        createdBody = await request.json() as Record<string, unknown>;
        return HttpResponse.json(ok(makeConnection({
          id: 22,
          protocol: 'OIDC',
          clientId: String(createdBody.clientId),
          clientSecretConfigured: true,
        })));
      }),
    );

    renderPanel();
    await userEvent.click(await screen.findByRole('button', { name: '新增连接' }));
    const dialog = await screen.findByRole('dialog');

    await chooseOption('协议', 'OIDC');
    await userEvent.type(within(dialog).getByLabelText('连接名称'), 'OIDC SSO');
    await userEvent.type(within(dialog).getByLabelText('Issuer'), 'https://idp.example.com');
    await userEvent.type(within(dialog).getByLabelText('授权端点'), 'https://idp.example.com/authorize');
    await userEvent.type(within(dialog).getByLabelText('令牌端点'), 'https://idp.example.com/token');
    await userEvent.type(within(dialog).getByLabelText('JWKS 端点'), 'https://idp.example.com/jwks');
    await userEvent.type(within(dialog).getByLabelText('Client ID'), 'client-1');
    await userEvent.click(within(dialog).getByRole('button', { name: '保存连接' }));

    expect(await within(dialog).findByText('请输入 Client Secret')).toBeInTheDocument();
    expect(createdBody).toBeNull();

    await userEvent.type(within(dialog).getByLabelText('Client Secret'), 'secret-1');
    await userEvent.click(within(dialog).getByRole('button', { name: '保存连接' }));

    await waitFor(() => expect(createdBody).not.toBeNull());
    expect(createdBody).toMatchObject({
      idpId: 1,
      protocol: 'OIDC',
      name: 'OIDC SSO',
      enabled: 0,
      oidcAuthorizeUrl: 'https://idp.example.com/authorize',
      oidcTokenUrl: 'https://idp.example.com/token',
      oidcJwksUrl: 'https://idp.example.com/jwks',
      clientId: 'client-1',
      clientSecret: 'secret-1',
    });
    expect(createdBody).not.toHaveProperty('samlSsoUrl');
    expect(createdBody).not.toHaveProperty('samlCert');
  });

  it('locks idp and protocol when editing and keeps the stored secret when the secret field is blank', async () => {
    const connection = makeConnection({
      id: 31,
      name: 'OIDC SSO',
      protocol: 'OIDC',
      oidcAuthorizeUrl: 'https://idp.example.com/authorize',
      oidcTokenUrl: 'https://idp.example.com/token',
      oidcJwksUrl: 'https://idp.example.com/jwks',
      clientId: 'client-1',
      clientSecretMasked: '******',
      clientSecretConfigured: true,
      scopes: 'openid profile',
      allowedTenants: 'tenant-a',
    });
    let updatedBody: Record<string, unknown> | null = null;
    server.use(
      http.get('/api/platform/federation/idps', () => HttpResponse.json(ok([makeIdp()]))),
      http.get('/api/platform/federation/connections', () => HttpResponse.json(ok([connection]))),
      http.put('/api/platform/federation/connections/31', async ({ request }) => {
        updatedBody = await request.json() as Record<string, unknown>;
        return HttpResponse.json(ok(connection));
      }),
    );

    renderPanel();
    await userEvent.click(await screen.findByRole('button', { name: /配\s*置/ }));
    const dialog = await screen.findByRole('dialog');

    expect(within(dialog).getByRole('combobox', { name: '所属 IdP' })).toBeDisabled();
    expect(within(dialog).getByRole('combobox', { name: '协议' })).toBeDisabled();
    expect(within(dialog).getByLabelText('Client Secret')).toHaveValue('');
    expect(within(dialog).getByText('已配置密钥；留空保存将沿用现有密钥。')).toBeInTheDocument();

    const nameInput = within(dialog).getByLabelText('连接名称');
    await userEvent.clear(nameInput);
    await userEvent.type(nameInput, 'OIDC SSO 更新');
    const scopesInput = within(dialog).getByLabelText('Scopes');
    await userEvent.clear(scopesInput);
    await userEvent.clear(within(dialog).getByLabelText('允许的租户'));
    await userEvent.click(within(dialog).getByRole('button', { name: '保存连接' }));

    await waitFor(() => expect(updatedBody).not.toBeNull());
    expect(updatedBody).toMatchObject({
      name: 'OIDC SSO 更新',
      enabled: 1,
      issuer: 'https://adfs.corp.example.com',
      oidcAuthorizeUrl: 'https://idp.example.com/authorize',
      clientId: 'client-1',
      scopes: '',
      allowedTenants: '',
      subjectClaim: '',
      nameClaim: '',
      emailClaim: '',
    });
    expect(updatedBody).not.toHaveProperty('idpId');
    expect(updatedBody).not.toHaveProperty('protocol');
    expect(updatedBody).not.toHaveProperty('clientSecret');
    await waitFor(() => expect(message.success).toHaveBeenCalledWith('登录连接已更新'));
  });

  it('toggles a connection through a partial update and reports readback refresh', async () => {
    let updatedBody: Record<string, unknown> | null = null;
    server.use(
      http.get('/api/platform/federation/idps', () => HttpResponse.json(ok([makeIdp()]))),
      http.get('/api/platform/federation/connections', () => HttpResponse.json(ok([makeConnection()]))),
      http.put('/api/platform/federation/connections/11', async ({ request }) => {
        updatedBody = await request.json() as Record<string, unknown>;
        return HttpResponse.json(ok(makeConnection({ enabled: 0 })));
      }),
    );

    renderPanel();
    await userEvent.click(await screen.findByRole('switch', { name: '切换 员工 SSO 的启用状态' }));

    await waitFor(() => expect(updatedBody).toEqual({ enabled: 0 }));
    await waitFor(() => expect(message.success).toHaveBeenCalledWith('启用状态已更新'));
  });

  it('keeps the connection switch state and falls back to the localized error when a toggle fails without a message', async () => {
    server.use(
      http.get('/api/platform/federation/idps', () => HttpResponse.json(ok([makeIdp()]))),
      http.get('/api/platform/federation/connections', () => HttpResponse.json(ok([makeConnection()]))),
      http.put('/api/platform/federation/connections/11', () => HttpResponse.json(fail(''))),
    );

    renderPanel();
    const toggle = await screen.findByRole('switch', { name: '切换 员工 SSO 的启用状态' });
    await userEvent.click(toggle);

    await waitFor(() => expect(message.error).toHaveBeenCalledWith('启用状态更新失败'));
    expect(toggle).toBeChecked();
  });

  it('asks for confirmation before disabling an idp that still has connections', async () => {
    const idps = [
      makeIdp(),
      makeIdp({ id: 2, name: '备用 IdP', description: null }),
    ];
    const updated: Array<Record<string, unknown>> = [];
    server.use(
      http.get('/api/platform/federation/idps', () => HttpResponse.json(ok(idps))),
      http.get('/api/platform/federation/connections', () => HttpResponse.json(ok([
        makeConnection(),
        makeConnection({ id: 12, name: '访客 SSO' }),
      ]))),
      http.put('/api/platform/federation/idps/1', async ({ request }) => {
        updated.push(await request.json() as Record<string, unknown>);
        return HttpResponse.json(ok(makeIdp({ enabled: 0 })));
      }),
      http.put('/api/platform/federation/idps/2', async ({ request }) => {
        updated.push(await request.json() as Record<string, unknown>);
        return HttpResponse.json(ok(makeIdp({ id: 2, name: '备用 IdP', enabled: 0 })));
      }),
    );

    renderPanel();
    await userEvent.click(await screen.findByRole('switch', { name: '切换 集团 AD FS 的启用状态' }));

    const title = await screen.findByText('停用 集团 AD FS？');
    expect(screen.getByText('该 IdP 下的 2 个登录连接将同时从登录页隐藏，已配置的内容不会被删除。'))
      .toBeInTheDocument();
    const popup = title.closest('.ant-popconfirm');
    expect(popup).not.toBeNull();
    const buttons = popup!.querySelectorAll('.ant-popconfirm-buttons button');
    await userEvent.click(buttons[buttons.length - 1] as HTMLButtonElement);

    await waitFor(() => expect(updated).toHaveLength(1));
    expect(updated[0]).toEqual({ enabled: 0 });

    await userEvent.click(screen.getByRole('switch', { name: '切换 备用 IdP 的启用状态' }));
    await waitFor(() => expect(updated).toHaveLength(2));
    expect(updated[1]).toEqual({ enabled: 0 });
    expect(screen.queryByText('停用 备用 IdP？')).toBeNull();
    expect(message.success).toHaveBeenCalledWith('启用状态已更新');
  });

  it('reports a failed save when the post-write readback fails', async () => {
    let connectionGets = 0;
    server.use(
      http.get('/api/platform/federation/idps', () => HttpResponse.json(ok([makeIdp()]))),
      http.get('/api/platform/federation/connections', () => {
        connectionGets += 1;
        return connectionGets === 1
          ? HttpResponse.json(ok([]))
          : HttpResponse.json(fail('回读失败'));
      }),
      http.post('/api/platform/federation/connections', () => HttpResponse.json(ok(makeConnection({ id: 41 })))),
    );

    renderPanel();
    await userEvent.click(await screen.findByRole('button', { name: '新增连接' }));
    const dialog = await screen.findByRole('dialog');
    await userEvent.type(within(dialog).getByLabelText('连接名称'), '读回失败 SSO');
    await userEvent.type(within(dialog).getByLabelText('Issuer'), 'https://idp.example.com');
    await userEvent.type(within(dialog).getByLabelText('SAML SSO 地址'), 'https://idp.example.com/sso');
    await userEvent.type(within(dialog).getByLabelText('签名证书'), 'CERT');
    await userEvent.click(within(dialog).getByRole('button', { name: '保存连接' }));

    await waitFor(() => expect(message.error).toHaveBeenCalledWith('回读失败'));
    expect(message.success).not.toHaveBeenCalled();
    expect(await screen.findByRole('dialog')).toBeInTheDocument();
  });

  it('shows load errors, retries both lists and then renders the idp table', async () => {
    let idpAttempts = 0;
    server.use(
      http.get('/api/platform/federation/idps', () => {
        idpAttempts += 1;
        return idpAttempts === 1
          ? HttpResponse.json(fail('加载失败'))
          : HttpResponse.json(ok([makeIdp()]));
      }),
      http.get('/api/platform/federation/connections', () => HttpResponse.json(ok([]))),
    );

    renderPanel();

    expect(await screen.findByText('企业 SSO 配置加载失败')).toBeInTheDocument();
    expect(screen.getByText('加载失败')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: /重\s*试/ }));

    expect((await screen.findAllByText('集团 AD FS')).length).toBeGreaterThan(0);
  });

  it('shows empty states and blocks adding a connection before any idp exists', async () => {
    serveLists([], []);

    renderPanel();

    expect(await screen.findByText('尚未配置身份提供方')).toBeInTheDocument();
    expect(screen.getByText('尚未配置登录连接')).toBeInTheDocument();
    expect(screen.getByText('没有可展示的登录方式：请启用连接及其所属 IdP。')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '新增连接' })).toBeDisabled();
  });

  it('derives the SAML callback address from the configured deployment domain', async () => {
    serveLists([makeIdp()], [makeConnection()]);

    renderPanel(branding({
      domain: 'https://sso.example.com',
      mcpBaseUrl: 'https://internal.example.com/api/mcp',
    }));
    await userEvent.click(await screen.findByRole('button', { name: '回调信息' }));

    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('https://sso.example.com/api/auth/sso/11/acs')).toBeInTheDocument();
    expect(within(dialog).getByText(
      '请将该 ACS 地址配置为 IdP 的断言消费服务（ACS）地址，并在 IdP 侧将 RelayState 原样回传。',
    )).toBeInTheDocument();
    expect(within(dialog).getByText(
      '地址按部署域名生成；未配置部署域名时使用平台公网地址或当前访问域名。更换域名后请同步更新 IdP 侧配置。',
    )).toBeInTheDocument();
  });

  it('falls back to the current origin for the OIDC callback when no deployment domain is configured', async () => {
    serveLists([makeIdp()], [makeConnection({ id: 12, protocol: 'OIDC', name: 'OIDC SSO' })]);

    renderPanel(branding({ domain: null, mcpBaseUrl: '' }));
    await userEvent.click(await screen.findByRole('button', { name: '回调信息' }));

    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText(`${window.location.origin}/api/auth/sso/12/callback`)).toBeInTheDocument();
    expect(within(dialog).getByText(
      '请将该地址配置为 OIDC 应用的回调（Redirect URI）地址。',
    )).toBeInTheDocument();
  });

  it('previews the login button without starting a login', async () => {
    serveLists([makeIdp()], [makeConnection()]);

    renderPanel();
    await userEvent.click(await screen.findByRole('button', { name: '使用 员工 SSO 登录' }));

    expect(message.info).toHaveBeenCalledWith('预览仅为展示效果，请从登录页发起登录。');
  });

  it('creates and renames an idp', async () => {
    let createdBody: Record<string, unknown> | null = null;
    let updatedBody: Record<string, unknown> | null = null;
    server.use(
      http.get('/api/platform/federation/idps', () => HttpResponse.json(ok([makeIdp()]))),
      http.get('/api/platform/federation/connections', () => HttpResponse.json(ok([]))),
      http.post('/api/platform/federation/idps', async ({ request }) => {
        createdBody = await request.json() as Record<string, unknown>;
        return HttpResponse.json(ok(makeIdp({ id: 51, name: String(createdBody.name) })));
      }),
      http.put('/api/platform/federation/idps/1', async ({ request }) => {
        updatedBody = await request.json() as Record<string, unknown>;
        return HttpResponse.json(ok(makeIdp({ name: String(updatedBody.name), description: null })));
      }),
    );

    renderPanel();

    await userEvent.click(await screen.findByRole('button', { name: '新增 IdP' }));
    let dialog = await screen.findByRole('dialog');
    await userEvent.type(within(dialog).getByLabelText('名称'), '  新 IdP  ');
    await userEvent.type(within(dialog).getByLabelText('描述'), '第二 IdP');
    await userEvent.click(within(dialog).getByRole('button', { name: '保存 IdP' }));

    await waitFor(() => expect(createdBody).toEqual({ name: '新 IdP', description: '第二 IdP' }));
    await waitFor(() => expect(message.success).toHaveBeenCalledWith('IdP 已创建'));

    await userEvent.click((await screen.findAllByRole('button', { name: '编辑 IdP' }))[0]);
    dialog = await screen.findByRole('dialog');
    const nameInput = within(dialog).getByLabelText('名称');
    await userEvent.clear(nameInput);
    await userEvent.type(nameInput, '集团 AD FS 更新');
    await userEvent.clear(within(dialog).getByLabelText('描述'));
    await userEvent.click(within(dialog).getByRole('button', { name: '保存 IdP' }));

    await waitFor(() => expect(updatedBody).toEqual({ name: '集团 AD FS 更新', description: null }));
    await waitFor(() => expect(message.success).toHaveBeenCalledWith('IdP 已更新'));
  });

  it('falls back to the localized save error when an idp update returns no message', async () => {
    server.use(
      http.get('/api/platform/federation/idps', () => HttpResponse.json(ok([makeIdp()]))),
      http.get('/api/platform/federation/connections', () => HttpResponse.json(ok([]))),
      http.put('/api/platform/federation/idps/1', () => HttpResponse.json(fail(''))),
    );

    renderPanel();
    await userEvent.click(await screen.findByRole('button', { name: '编辑 IdP' }));
    const dialog = await screen.findByRole('dialog');
    const nameInput = within(dialog).getByLabelText('名称');
    await userEvent.clear(nameInput);
    await userEvent.type(nameInput, '改名 IdP');
    await userEvent.click(within(dialog).getByRole('button', { name: '保存 IdP' }));

    await waitFor(() => expect(message.error).toHaveBeenCalledWith('IdP 保存失败'));
    expect(await screen.findByRole('dialog')).toBeInTheDocument();
  });

  it('keeps the connection modal open and surfaces the backend error when saving fails', async () => {
    server.use(
      http.get('/api/platform/federation/idps', () => HttpResponse.json(ok([makeIdp()]))),
      http.get('/api/platform/federation/connections', () => HttpResponse.json(ok([]))),
      http.post('/api/platform/federation/connections', () => HttpResponse.json(fail('连接名称已存在'))),
    );

    renderPanel();
    await userEvent.click(await screen.findByRole('button', { name: '新增连接' }));
    const dialog = await screen.findByRole('dialog');
    await userEvent.type(within(dialog).getByLabelText('连接名称'), '员工 SSO');
    await userEvent.type(within(dialog).getByLabelText('Issuer'), 'https://idp.example.com');
    await userEvent.type(within(dialog).getByLabelText('SAML SSO 地址'), 'https://idp.example.com/sso');
    await userEvent.type(within(dialog).getByLabelText('签名证书'), 'CERT');
    await userEvent.click(within(dialog).getByRole('button', { name: '保存连接' }));

    await waitFor(() => expect(message.error).toHaveBeenCalledWith('连接名称已存在'));
    expect(message.success).not.toHaveBeenCalled();
    expect(await screen.findByRole('dialog')).toBeInTheDocument();
  });
});
