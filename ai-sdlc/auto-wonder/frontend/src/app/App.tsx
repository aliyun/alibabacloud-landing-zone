import { useEffect } from 'react';
import { useLocale } from '@/shared/i18n/LocaleProvider';
import { RouterProvider } from 'react-router-dom';
import { QueryClient, QueryClientProvider, useQuery } from '@tanstack/react-query';
import { AppearanceProvider } from '@/shared/theme/AppearanceProvider';
import { router } from './router';
import { compactFavicon } from './favicon';
import { BrandBootstrap } from './BrandBootstrap';
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
  useLocale();
  return (
    <QueryClientProvider client={queryClient}>
      <BrandedApp />
    </QueryClientProvider>
  );
}

function BrandedApp() {
  const { data: branding, isError, isFetching, refetch } = useQuery({
    queryKey: BRANDING_QUERY_KEY,
    queryFn: getPublicBranding,
  });
  const platformName = branding ? branding.platformName || DEFAULT_BRANDING.platformName : null;
  const logoUrl = branding ? branding.logoUrl || DEFAULT_BRANDING.logoUrl : null;

  useEffect(() => {
    if (!platformName) return;
    document.title = platformName;
  }, [platformName]);

  useEffect(() => {
    if (!logoUrl) return;
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
  }, [logoUrl]);

  if (!branding) {
    return <BrandBootstrap failed={isError} retrying={isFetching} onRetry={() => { void refetch(); }} />;
  }

  return (
    <AppearanceProvider accent={branding.primaryColor || DEFAULT_BRANDING.primaryColor}>
      <RouterProvider router={router} />
    </AppearanceProvider>
  );
}
