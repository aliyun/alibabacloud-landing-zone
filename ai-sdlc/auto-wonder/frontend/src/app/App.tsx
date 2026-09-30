import { useEffect } from 'react';
import { RouterProvider } from 'react-router-dom';
import { QueryClient, QueryClientProvider, useQuery } from '@tanstack/react-query';
import { AppearanceProvider } from '@/shared/theme/AppearanceProvider';
import { router } from './router';
import { compactFavicon } from './favicon';
import { BRANDING_QUERY_KEY, DEFAULT_BRANDING, getPublicBranding } from '@/features/platform/brandingApi';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: 1,
      staleTime: 30_000,
      refetchOnWindowFocus: false,
    },
  },
});

export function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <BrandedApp />
    </QueryClientProvider>
  );
}

function BrandedApp() {
  const { data } = useQuery({
    queryKey: BRANDING_QUERY_KEY,
    queryFn: getPublicBranding,
  });
  const branding = data || DEFAULT_BRANDING;

  useEffect(() => {
    document.title = branding.platformName || DEFAULT_BRANDING.platformName;
  }, [branding.platformName]);

  useEffect(() => {
    const logoUrl = branding.logoUrl || DEFAULT_BRANDING.logoUrl;
    const icons = document.querySelectorAll<HTMLLinkElement>('link[rel="icon"], link[rel="shortcut icon"]');
    const updateIcons = (url: string) => icons.forEach((link) => {
      link.href = url;
      link.removeAttribute('type');
    });
    updateIcons(logoUrl);
    document.querySelector<HTMLLinkElement>('link[rel="apple-touch-icon"]')?.setAttribute('href', logoUrl);

    const controller = new AbortController();
    void compactFavicon(logoUrl, controller.signal).then((url) => {
      if (!controller.signal.aborted) updateIcons(url);
    }).catch(() => { /* Keep the original logo if loading or conversion fails. */ });
    return () => controller.abort();
  }, [branding.logoUrl]);

  return (
    <AppearanceProvider accent={branding.primaryColor || DEFAULT_BRANDING.primaryColor}>
      <RouterProvider router={router} />
    </AppearanceProvider>
  );
}
