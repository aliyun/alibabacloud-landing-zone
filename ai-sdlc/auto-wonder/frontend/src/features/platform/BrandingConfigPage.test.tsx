import { describe, expect, it, beforeEach, vi } from 'vitest';
import { render, screen, waitFor, within, fireEvent } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { message } from 'antd';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { BrandingConfigPage } from './BrandingConfigPage';
import type { UpdateDingTalkImChannelParams } from './brandingApi';
import { useAuthStore } from '@/shared/auth/store';

function brandingPayload() {
  return {
    success: true,
    code: '0',
    message: '',
    traceId: null,
    data: {
      platformName: 'AutoWonder',
      logoUrl: '/logo.png',
      themeKey: 'aliyun-orange',
      primaryColor: '#f97316',
      domain: 'https://community.example',
      mcpBaseUrl: 'https://community.example/api/mcp',
      canManage: true,
    },
  };
}

function imChannelsPayload() {
  return {
    success: true,
    code: '0',
    message: '',
    traceId: null,
    data: [
      {
        provider: 'DINGTALK',
        enabled: true,
        appKey: 'ding-app',
        robotCode: 'ding-robot',
        secretConfigured: true,
        ready: true,
      },
    ],
  };
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/workspaces/branding']}>
        <BrandingConfigPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

function LocationProbe() {
  const location = useLocation();
  return <span data-testid="location-path">{location.pathname}</span>;
}

function renderPageWithLocation() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/workspaces/branding']}>
        <BrandingConfigPage />
        <LocationProbe />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

/** antd Tabs mounts a pane only once its tab has been activated. */
async function openNotificationTab() {
  await userEvent.click(await screen.findByRole('tab', { name: '协作通知' }));
}

async function openPlatformAdminTab() {
  await userEvent.click(await screen.findByRole('tab', { name: '平台管理员' }));
}

describe('BrandingConfigPage', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
    vi.restoreAllMocks();
    // antd's static message() portals its notices into document.body and auto-closes them after 3s.
    // A notice opened by an earlier test is therefore removed by React *after* the body wipe above
    // has already detached it, which throws "The node to be removed is not a child of this node".
    // Nothing here asserts on a rendered toast, so keeping the real holder out of the DOM removes
    // the race instead of papering over it with timing.
    vi.spyOn(message, 'success').mockImplementation(
      () => undefined as unknown as ReturnType<typeof message.success>,
    );
    vi.spyOn(message, 'error').mockImplementation(
      () => undefined as unknown as ReturnType<typeof message.error>,
    );
    useAuthStore.getState().clear();
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'ADMIN');
    server.use(
      http.get('/api/platform/branding', () => HttpResponse.json(brandingPayload())),
      http.get('/api/platform/im-channels', () => HttpResponse.json(imChannelsPayload())),
    );
  });

  it('loads existing branding and saves updates', async () => {
    let savedBody: Record<string, unknown> | null = null;
    server.use(
      http.get('/api/platform/branding', () => HttpResponse.json(brandingPayload())),
      http.put('/api/platform/branding', async ({ request }) => {
        savedBody = await request.json() as Record<string, unknown>;
        return HttpResponse.json({
          success: true,
          code: '0',
          message: '',
          traceId: null,
          data: {
            ...(savedBody as Record<string, unknown>),
            logoUrl: '/logo.png',
            canManage: true,
          },
        });
      }),
    );

    renderPage();

    const nameInput = await screen.findByPlaceholderText('AutoWonder');
    await waitFor(() => expect(nameInput).not.toBeDisabled());
    await userEvent.clear(nameInput);
    await userEvent.type(nameInput, 'WonderHub');
    const domainInput = screen.getByPlaceholderText('https://wonder.example.com');
    await userEvent.clear(domainInput);
    await userEvent.type(domainInput, 'https://wonder.example.com');
    await userEvent.click(screen.getByText('海洋蓝'));
    await userEvent.click(screen.getByRole('button', { name: /保存配置/ }));

    await waitFor(() => {
      expect(savedBody).toMatchObject({
        platformName: 'WonderHub',
        themeKey: 'ocean-blue',
        primaryColor: '#2563eb',
        domain: 'https://wonder.example.com',
      });
    });
  });

  it('explains that the saved domain drives the outbound platform addresses', async () => {
    renderPage();

    expect(await screen.findByText(
      '仅平台管理员可以修改平台配置。保存「部署域名」后，MCP 服务地址、执行器启动命令等对外地址会立即使用该域名；未配置时使用部署环境变量地址。',
    )).toBeInTheDocument();
    expect(screen.getByText(
      '填写私有化部署后用户访问平台的域名，例如 https://wonder.example.com；保存后 MCP 服务地址、执行器启动命令等对外地址立即使用该域名',
    )).toBeInTheDocument();
  });

  it('loads dingtalk collaboration robot status', async () => {
    renderPage();

    expect(await screen.findByRole('tab', { name: '协作通知' })).toBeInTheDocument();
    await openNotificationTab();

    expect(await screen.findByText('钉钉机器人')).toBeInTheDocument();
    expect(await screen.findByText('AppSecret 已配置')).toBeInTheDocument();
    expect(screen.getByText('配置完整')).toBeInTheDocument();
  });

  it('uses backend dingtalk ready state even when credentials are present', async () => {
    server.use(
      http.get('/api/platform/im-channels', () => HttpResponse.json({
        ...imChannelsPayload(),
        data: [
          {
            provider: 'DINGTALK',
            enabled: false,
            appKey: 'ding-app',
            robotCode: 'ding-robot',
            secretConfigured: true,
            ready: false,
          },
        ],
      })),
    );

    renderPage();
    await openNotificationTab();

    expect(await screen.findByText('AppSecret 已配置')).toBeInTheDocument();
    expect(screen.getByText('配置未完整')).toBeInTheDocument();
    expect(screen.queryByText('配置完整')).not.toBeInTheDocument();
  });

  it('preserves configured app secret when saving dingtalk robot without a new secret', async () => {
    let savedBody: Record<string, unknown> | null = null;
    server.use(
      http.put('/api/platform/im-channels/dingtalk', async ({ request }) => {
        savedBody = await request.json() as Record<string, unknown>;
        return HttpResponse.json({ ...imChannelsPayload(), data: imChannelsPayload().data[0] });
      }),
    );

    renderPage();
    await openNotificationTab();

    const appKeyInput = await screen.findByLabelText('AppKey');
    await waitFor(() => expect(appKeyInput).not.toBeDisabled());
    const appSecretInput = screen.getByLabelText('AppSecret');
    expect(appSecretInput).toHaveValue('');

    await userEvent.clear(appKeyInput);
    await userEvent.type(appKeyInput, 'ding-app-updated');
    await userEvent.click(screen.getByRole('button', { name: /保存协作通知/ }));

    await waitFor(() => {
      expect(savedBody).toEqual({
        enabled: true,
        appKey: 'ding-app-updated',
        appSecret: '',
        robotCode: 'ding-robot',
      });
    });
  });

  it('can save disabled dingtalk robot with empty app secret', async () => {
    let savedBody: Record<string, unknown> | null = null;
    server.use(
      http.put('/api/platform/im-channels/dingtalk', async ({ request }) => {
        savedBody = await request.json() as Record<string, unknown>;
        return HttpResponse.json({ ...imChannelsPayload(), data: { ...imChannelsPayload().data[0], enabled: false } });
      }),
    );

    renderPage();
    await openNotificationTab();

    const enabledSwitch = await screen.findByRole('switch', { name: '启用钉钉机器人' });
    await userEvent.click(enabledSwitch);
    await userEvent.click(screen.getByRole('button', { name: /保存协作通知/ }));

    await waitFor(() => {
      expect(savedBody).toMatchObject({
        enabled: false,
        appKey: 'ding-app',
        appSecret: '',
        robotCode: 'ding-robot',
      });
    });
  });

  it('does not send app secret when saving main branding config', async () => {
    let savedBrandingBody: Record<string, unknown> | null = null;
    let savedDingTalkBody: Record<string, unknown> | null = null;
    server.use(
      http.get('/api/platform/branding', () => HttpResponse.json(brandingPayload())),
      http.put('/api/platform/branding', async ({ request }) => {
        savedBrandingBody = await request.json() as Record<string, unknown>;
        return HttpResponse.json({
          ...brandingPayload(),
          data: {
            ...brandingPayload().data,
            ...(savedBrandingBody as Record<string, unknown>),
          },
        });
      }),
      http.put('/api/platform/im-channels/dingtalk', async ({ request }) => {
        savedDingTalkBody = await request.json() as Record<string, unknown>;
        return HttpResponse.json({ ...imChannelsPayload(), data: imChannelsPayload().data[0] });
      }),
    );

    renderPage();

    await screen.findByText('AppSecret 已配置');
    await userEvent.click(screen.getByRole('button', { name: /保存配置/ }));

    await waitFor(() => {
      expect(savedBrandingBody).not.toBeNull();
    });
    expect(savedBrandingBody).not.toHaveProperty('appSecret');
    expect(savedDingTalkBody).toBeNull();
  });

  it('keeps branding controls visible and shows backend permission errors', async () => {
    let updateCalls = 0;
    const error = vi.spyOn(message, 'error').mockImplementation(
      () => undefined as unknown as ReturnType<typeof message.error>,
    );
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_WRITE');
    server.use(
      http.get('/api/platform/branding', () => HttpResponse.json(brandingPayload())),
      http.put('/api/platform/branding', () => {
        updateCalls += 1;
        return HttpResponse.json({
          success: false,
          code: '10403',
          message: '仅平台管理员可以修改平台配置',
          traceId: null,
          data: null,
        });
      }),
    );

    renderPage();

    const saveButton = await screen.findByRole('button', { name: /保存配置/ });
    await waitFor(() => expect(saveButton).toBeEnabled());
    await userEvent.click(saveButton);

    await waitFor(() => {
      expect(updateCalls).toBe(1);
      expect(error).toHaveBeenCalledWith('仅平台管理员可以修改平台配置');
    });
    await waitFor(() => expect(saveButton).toBeEnabled());
  });

  it('falls back to default logo when custom logo fails to load', async () => {
    server.use(
      http.get('/api/platform/branding', () => HttpResponse.json({
        ...brandingPayload(),
        data: {
          ...brandingPayload().data,
          logoUrl: '/api/platform/branding/logo?v=5',
        },
      })),
    );

    renderPage();

    const nameInput = await screen.findByPlaceholderText('AutoWonder');
    await waitFor(() => expect(nameInput).not.toBeDisabled());

    const logoImg = screen.getByRole('img', { name: 'AutoWonder' });
    expect(logoImg.getAttribute('src')).toBe('/api/platform/branding/logo?v=5');

    fireEvent.error(logoImg);

    expect(logoImg.getAttribute('src')).toBe('/logo.png');
  });

  it('shows an enabled back-to-home entry beside the page title', async () => {
    renderPage();

    const backButton = await screen.findByRole('button', { name: /返回首页/ });
    await waitFor(() => expect(backButton).toBeEnabled());
    expect(screen.getByText('平台配置')).toBeInTheDocument();
  });

  it('navigates back to the platform home when the back entry is clicked', async () => {
    renderPageWithLocation();

    expect(await screen.findByTestId('location-path')).toHaveTextContent('/workspaces/branding');

    await userEvent.click(screen.getByRole('button', { name: /返回首页/ }));

    await waitFor(() => {
      expect(screen.getByTestId('location-path')).toHaveTextContent(/^\/$/);
    });
  });

  it('keeps the back entry above the collaboration notice section', async () => {
    renderPage();

    const backButton = await screen.findByRole('button', { name: /返回首页/ });
    const noticeSection = await screen.findByText('协作通知');

    expect(
      backButton.compareDocumentPosition(noticeSection) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
  });

  it('leaves directly with unsaved branding edits instead of prompting', async () => {
    renderPageWithLocation();

    const nameInput = await screen.findByPlaceholderText('AutoWonder');
    await waitFor(() => expect(nameInput).not.toBeDisabled());
    await userEvent.clear(nameInput);
    await userEvent.type(nameInput, 'WonderHub');

    await userEvent.click(screen.getByRole('button', { name: /返回首页/ }));

    await waitFor(() => {
      expect(screen.getByTestId('location-path')).toHaveTextContent(/^\/$/);
    });
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('groups branding, collaboration notice and platform admins into tabs', async () => {
    renderPage();

    expect(await screen.findByRole('tab', { name: '品牌与主题' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: '协作通知' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: '平台管理员' })).toBeInTheDocument();

    // Branding stays the landing pane so an operator who only came to rename the platform does not
    // have to hunt for the form.
    const saveButton = await screen.findByRole('button', { name: /保存配置/ });
    await waitFor(() => expect(saveButton).toBeEnabled());
  });

  it('does not fetch the platform admin roster until its tab is opened', async () => {
    let adminRequests = 0;
    server.use(http.get('/api/platform/admins', () => {
      adminRequests += 1;
      return HttpResponse.json({
        success: true,
        code: '0',
        message: '',
        traceId: null,
        data: { admins: [], canManage: false },
      });
    }));

    renderPage();

    await screen.findByRole('tab', { name: '平台管理员' });
    expect(adminRequests).toBe(0);

    await openPlatformAdminTab();

    await waitFor(() => expect(adminRequests).toBe(1));
  });

  it('manages platform admins from the third tab', async () => {
    server.use(http.get('/api/platform/admins', () => HttpResponse.json({
      success: true,
      code: '0',
      message: '',
      traceId: null,
      data: {
        admins: [
          {
            userId: 10000,
            username: 'alice',
            nickname: '爱丽丝',
            email: 'alice@example.com',
            active: true,
            self: true,
            removable: false,
            removeDisabledReason: '平台管理员不可移除自己',
          },
          {
            userId: 10001,
            username: 'bob',
            nickname: '鲍勃',
            email: 'bob@example.com',
            active: true,
            self: false,
            removable: true,
            removeDisabledReason: null,
          },
        ],
        canManage: true,
      },
    })));

    renderPage();
    await openPlatformAdminTab();

    const selfRow = (await screen.findByText('爱丽丝')).closest('tr');
    const otherRow = (await screen.findByText('鲍勃')).closest('tr');
    expect(selfRow).not.toBeNull();
    expect(otherRow).not.toBeNull();
    expect(within(selfRow!).getByRole('button', { name: '移除' })).toBeDisabled();
    expect(within(otherRow!).getByRole('button', { name: '移除' })).toBeEnabled();
    expect(screen.getByRole('button', { name: '添加管理员' })).toBeDisabled();
  });
});

it('switches to Feishu without reusing DingTalk credentials or requiring RobotCode', async () => {
  let saved: UpdateDingTalkImChannelParams | undefined;
  server.use(
    http.get('/api/platform/branding', () => HttpResponse.json(brandingPayload())),
    http.get('/api/platform/im-channels', () => HttpResponse.json(imChannelsPayload())),
    http.put('/api/platform/im-channels/feishu', async ({ request }) => {
      saved = await request.json() as UpdateDingTalkImChannelParams;
      return HttpResponse.json({ ...imChannelsPayload(), data: { provider: 'FEISHU', ...saved } });
    }),
  );
  renderPage(); await openNotificationTab();
  await userEvent.click(await screen.findByRole('radio', { name: '飞书' }));
  expect(screen.queryByLabelText('RobotCode')).not.toBeInTheDocument();
  expect(screen.getByLabelText('App ID')).toHaveValue('');
  expect(screen.getByLabelText('AppSecret')).toHaveValue('');
  await userEvent.click(screen.getByRole('switch', { name: '启用飞书机器人' }));
  await userEvent.type(screen.getByLabelText('App ID'), 'cli_test');
  await userEvent.type(screen.getByLabelText('AppSecret'), 'feishu-secret');
  await userEvent.click(screen.getByRole('button', { name: /保存协作通知/ }));
  await waitFor(() => expect(saved).toEqual({ enabled: true, appKey: 'cli_test', appSecret: 'feishu-secret', robotCode: '' }));
});
