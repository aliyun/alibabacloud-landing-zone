import { describe, expect, it, beforeEach, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { message } from 'antd';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { BrandingConfigPage } from './BrandingConfigPage';
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
      <BrandingConfigPage />
    </QueryClientProvider>,
  );
}

describe('BrandingConfigPage', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
    vi.restoreAllMocks();
    useAuthStore.getState().clear();
    useAuthStore.getState().setCurrentOrg({ id: 1, name: 'O', description: '' }, 'ADMIN');
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

  it('loads dingtalk collaboration robot status', async () => {
    renderPage();

    expect(await screen.findByText('协作通知')).toBeInTheDocument();
    expect(screen.getByText('钉钉机器人')).toBeInTheDocument();
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
    useAuthStore.getState().setCurrentOrg({ id: 1, name: 'O', description: '' }, 'READ_WRITE');
    server.use(
      http.get('/api/platform/branding', () => HttpResponse.json(brandingPayload())),
      http.put('/api/platform/branding', () => {
        updateCalls += 1;
        return HttpResponse.json({
          success: false,
          code: '10403',
          message: '仅系统第一个用户可以管理品牌配置',
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
      expect(error).toHaveBeenCalledWith('仅系统第一个用户可以管理品牌配置');
    });
    await waitFor(() => expect(saveButton).toBeEnabled());
  });
});
