import { createElement } from 'react';
import { act, render, waitFor } from '@testing-library/react';
import { QueryClient, useQueryClient } from '@tanstack/react-query';
import indexHtml from '../../index.html?raw';
import { App } from './App';
import { compactFavicon } from './favicon';

vi.mock('./favicon', () => ({ compactFavicon: vi.fn(async (url: string) => url) }));
import { BRANDING_QUERY_KEY, DEFAULT_BRANDING } from '@/features/platform/brandingApi';

let queryClient: QueryClient;
vi.mock('./router', () => ({ router: {} }));
vi.mock('react-router-dom', () => ({
  RouterProvider: () => {
    queryClient = useQueryClient();
    return null;
  },
}));
vi.mock('@/shared/theme/AppearanceProvider', () => ({
  AppearanceProvider: ({ children }: { children: React.ReactNode }) => children,
}));

describe('favicon', () => {
  it('uses the platform logo and follows uploads without forcing a PNG MIME type', async () => {
    const head = new DOMParser().parseFromString(indexHtml, 'text/html').head;
    const icons = Array.from(head.querySelectorAll<HTMLLinkElement>('link[rel*="icon"]'));
    expect(icons).toHaveLength(3);
    icons.forEach((icon) => {
      expect(icon.getAttribute('href')).toBe(DEFAULT_BRANDING.logoUrl);
      document.head.appendChild(icon);
    });
    const view = render(createElement(App));
    try {
      await waitFor(() => expect(queryClient.getQueryData(BRANDING_QUERY_KEY)).toBeDefined());
      let finishOld: (url: string) => void = () => {};
      vi.mocked(compactFavicon).mockImplementationOnce(() => new Promise<string>((resolve) => { finishOld = resolve; }));
      for (const logoUrl of ['/api/platform/branding/logo?v=svg-1', '/api/platform/branding/logo?v=svg-2', '']) {
        act(() => queryClient.setQueryData(BRANDING_QUERY_KEY, { ...DEFAULT_BRANDING, logoUrl }));
        await waitFor(() => icons.forEach((icon) => {
          expect(icon.getAttribute('href')).toBe(logoUrl || DEFAULT_BRANDING.logoUrl);
          expect(icon.hasAttribute('type')).toBe(false);
        }));
      }
      await act(async () => finishOld('data:image/png;base64,old'));
      expect(icons[0].getAttribute('href')).toBe(DEFAULT_BRANDING.logoUrl);
    } finally {
      view.unmount();
      queryClient.clear();
      icons.forEach((icon) => icon.remove());
    }
  });
});
