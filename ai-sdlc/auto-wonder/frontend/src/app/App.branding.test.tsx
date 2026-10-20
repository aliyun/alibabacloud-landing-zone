import { createElement } from 'react';
import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { QueryClient } from '@tanstack/react-query';
import { server } from '@/test/mocks/server';
import { DEFAULT_BRANDING, type PlatformBranding } from '@/features/platform/brandingApi';
import '@/shared/i18n';
import { App } from './App';

const runtime = vi.hoisted(() => ({ queryClient: null as QueryClient | null }));

vi.mock('./router', () => ({ router: {} }));
vi.mock('./favicon', () => ({ compactFavicon: vi.fn(async (url: string) => url) }));
vi.mock('react-router-dom', () => ({
  RouterProvider: () => createElement('div', { 'data-testid': 'app-router' }),
}));
vi.mock('@/shared/theme/AppearanceProvider', () => ({
  AppearanceProvider: ({ accent, children }: { accent: string; children?: React.ReactNode }) =>
    createElement('div', { 'data-testid': 'appearance-provider', 'data-accent': accent }, children),
}));
vi.mock('@tanstack/react-query', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@tanstack/react-query')>();
  return {
    ...actual,
    // The module-level client in App.tsx outlives each test; capture it so the cache can be reset.
    QueryClientProvider: ({ client, children }: { client: QueryClient; children?: React.ReactNode }) => {
      runtime.queryClient = client;
      return createElement(actual.QueryClientProvider, { client }, children);
    },
  };
});

const CUSTOM_BRANDING: PlatformBranding = {
  platformName: 'The Legion · AI 工程工厂',
  logoUrl: '/api/platform/branding/logo?v=legion',
  themeKey: 'teal',
  primaryColor: '#0f766e',
  domain: 'https://legion.example.com',
  mcpBaseUrl: 'https://legion.example.com/api/mcp',
  recommendedRuntimeVersion: '0.3.8',
  deploymentVersion: '1.0.0',
  communityEdition: false,
  canManage: false,
};

function brandingEnvelope(data: PlatformBranding) {
  return { success: true, code: '0', message: '', data, traceId: 'trace-branding' };
}

let releaseBranding: (() => void) | null = null;

describe('App branding gate', () => {
  beforeEach(() => {
    runtime.queryClient?.clear();
    document.title = '';
    releaseBranding = null;
  });

  it('holds a neutral bootstrap screen until the branding request resolves', async () => {
    server.use(http.get('/api/platform/branding/public', () => new Promise<Response>((resolve) => {
      releaseBranding = () => resolve(HttpResponse.json(brandingEnvelope(CUSTOM_BRANDING)));
    })));

    render(createElement(App));

    expect(screen.getByRole('status')).toHaveTextContent('正在加载平台配置');
    expect(screen.queryByTestId('app-router')).not.toBeInTheDocument();
    expect(screen.queryByText('AutoWonder')).not.toBeInTheDocument();
    expect(document.querySelector('img')).toBeNull();
    expect(document.documentElement.style.getPropertyValue('--aw-accent')).toBe('#64748b');
    expect(document.title).not.toBe('AutoWonder');

    await waitFor(() => expect(releaseBranding).not.toBeNull());
    await act(async () => { releaseBranding?.(); });

    expect(await screen.findByTestId('app-router')).toBeInTheDocument();
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
    expect(screen.getByTestId('appearance-provider')).toHaveAttribute('data-accent', CUSTOM_BRANDING.primaryColor);
    await waitFor(() => expect(document.title).toBe(CUSTOM_BRANDING.platformName));
  });

  it('shows the failure screen and recovers through retry', async () => {
    let fail = true;
    server.use(http.get('/api/platform/branding/public', () => {
      if (fail) {
        return HttpResponse.json(
          { success: false, code: '500', message: 'branding unavailable', data: null, traceId: null },
          { status: 500 },
        );
      }
      return HttpResponse.json(brandingEnvelope(CUSTOM_BRANDING));
    }));

    render(createElement(App));

    const alert = await screen.findByRole('alert', undefined, { timeout: 4000 });
    expect(alert).toHaveTextContent('平台配置加载失败');
    expect(alert).toHaveTextContent('请检查网络连接后重试');
    expect(screen.queryByTestId('app-router')).not.toBeInTheDocument();
    expect(screen.queryByText('AutoWonder')).not.toBeInTheDocument();
    expect(document.querySelector('img')).toBeNull();

    fail = false;
    await userEvent.click(screen.getByRole('button', { name: '重试' }));

    expect(await screen.findByTestId('app-router', undefined, { timeout: 4000 })).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.getByTestId('appearance-provider')).toHaveAttribute('data-accent', CUSTOM_BRANDING.primaryColor);
    await waitFor(() => expect(document.title).toBe(CUSTOM_BRANDING.platformName));
  });

  it('paints the platform branding once the request resolves on the default platform', async () => {
    render(createElement(App));

    expect(screen.getByRole('status')).toBeInTheDocument();
    expect(await screen.findByTestId('app-router')).toBeInTheDocument();
    expect(screen.getByTestId('appearance-provider')).toHaveAttribute('data-accent', DEFAULT_BRANDING.primaryColor);
    await waitFor(() => expect(document.title).toBe(DEFAULT_BRANDING.platformName));
  });

  it('falls back to the built-in name, logo and accent when the platform returns empty values', async () => {
    server.use(http.get('/api/platform/branding/public', () => HttpResponse.json(brandingEnvelope({
      ...CUSTOM_BRANDING, platformName: '', logoUrl: '', primaryColor: '',
    }))));

    render(createElement(App));

    expect(await screen.findByTestId('app-router')).toBeInTheDocument();
    expect(screen.getByTestId('appearance-provider')).toHaveAttribute('data-accent', DEFAULT_BRANDING.primaryColor);
    await waitFor(() => expect(document.title).toBe(DEFAULT_BRANDING.platformName));
  });
});
