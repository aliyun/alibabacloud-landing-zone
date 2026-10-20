import { createElement } from 'react';
import { act, render, waitFor } from '@testing-library/react';
import { QueryClient } from '@tanstack/react-query';
import indexHtml from '../../index.html?raw';
import { App } from './App';
import { compactFavicon } from './favicon';

const runtime = vi.hoisted(() => ({ queryClient: null as QueryClient | null }));

vi.mock('./favicon', () => ({ compactFavicon: vi.fn(async (url: string) => url) }));
vi.mock('./router', () => ({ router: {} }));
vi.mock('react-router-dom', () => ({ RouterProvider: () => null }));
vi.mock('@/shared/theme/AppearanceProvider', () => ({
  AppearanceProvider: ({ children }: { children: React.ReactNode }) => children,
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
import { BRANDING_QUERY_KEY, DEFAULT_BRANDING } from '@/features/platform/brandingApi';

function client(): QueryClient {
  if (!runtime.queryClient) throw new Error('QueryClientProvider has not mounted yet');
  return runtime.queryClient;
}

function parsedHeadIcons(): HTMLLinkElement[] {
  const head = new DOMParser().parseFromString(indexHtml, 'text/html').head;
  return Array.from(head.querySelectorAll<HTMLLinkElement>('link[rel*="icon"]'));
}

beforeEach(() => {
  runtime.queryClient?.clear();
  document.title = '';
});

describe('favicon', () => {
  it('keeps the raw HTML entry free of brand defaults', () => {
    const head = new DOMParser().parseFromString(indexHtml, 'text/html').head;
    expect(head.querySelector('title')?.textContent).toBe('');
    const icons = parsedHeadIcons();
    expect(icons).toHaveLength(3);
    icons.forEach((icon) => {
      expect(icon.getAttribute('href')).toMatch(/^data:image\/svg\+xml/);
      expect(icon.getAttribute('href')).not.toContain('/logo.svg');
    });
  });

  it('uses the platform logo and follows uploads without forcing a PNG MIME type', async () => {
    const icons = parsedHeadIcons();
    icons.forEach((icon) => document.head.appendChild(icon));
    const view = render(createElement(App));
    try {
      await waitFor(() => expect(client().getQueryData(BRANDING_QUERY_KEY)).toBeDefined());
      await waitFor(() => expect(document.title).toBe(DEFAULT_BRANDING.platformName));
      await waitFor(() => icons.forEach((icon) => {
        expect(icon.getAttribute('href')).toBe(DEFAULT_BRANDING.logoUrl);
        expect(icon.hasAttribute('type')).toBe(false);
      }));
      let finishOld: (url: string) => void = () => {};
      vi.mocked(compactFavicon).mockImplementationOnce(() => new Promise<string>((resolve) => { finishOld = resolve; }));
      vi.mocked(compactFavicon).mockImplementationOnce(() => Promise.reject(new Error('favicon conversion failed')));
      for (const logoUrl of ['/api/platform/branding/logo?v=svg-1', '/api/platform/branding/logo?v=svg-2', '']) {
        act(() => client().setQueryData(BRANDING_QUERY_KEY, { ...DEFAULT_BRANDING, logoUrl }));
        await waitFor(() => icons.forEach((icon) => {
          expect(icon.getAttribute('href')).toBe(logoUrl || DEFAULT_BRANDING.logoUrl);
          expect(icon.hasAttribute('type')).toBe(false);
        }));
      }
      await act(async () => finishOld('data:image/png;base64,old'));
      expect(icons[0].getAttribute('href')).toBe(DEFAULT_BRANDING.logoUrl);
    } finally {
      view.unmount();
      icons.forEach((icon) => icon.remove());
    }
  });
});
