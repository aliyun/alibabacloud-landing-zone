import React from 'react';
import { createRoot } from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { LocaleProvider } from '../src/shared/i18n/LocaleProvider';
import { apiClient } from '../src/shared/api/client';
import type { TimelineItem } from '../src/shared/types/workitem';
import { UnifiedTimeline } from '../src/features/workitem/components/UnifiedTimeline';
declare global {
  interface Window {
    loadFixture: () => Promise<void>;
    result: { id: string | number; count: number; body: string } | null;
    currentItems: TimelineItem[] | null;
    previousItems: TimelineItem[] | null;
    cancelAndUnmountFixture: () => Promise<boolean>;
  }
}
const root = createRoot(document.getElementById('root')!);
const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
window.loadFixture = async () => {
  const { data } = await apiClient.get('/api/memory-fixture');
  window.previousItems = window.currentItems;
  window.currentItems = data;
  window.result = { id: data[0].id, count: data.length, body: data[0].content };
  root.render(<LocaleProvider><QueryClientProvider client={client}><MemoryRouter>
    <UnifiedTimeline items={data} />
  </MemoryRouter></QueryClientProvider></LocaleProvider>);
};
window.cancelAndUnmountFixture = async () => {
  const controller = new AbortController();
  const pending = apiClient.get('/api/memory-fixture?slow', { signal: controller.signal })
    .then(() => false, error => error.message === 'canceled');
  let observed = false;
  for (let attempt = 0; attempt < 20; attempt++) {
    const { data } = await apiClient.get('/api/memory-fixture-metrics');
    if (data.slowRequests > 0) { observed = true; break; }
    await new Promise(resolve => setTimeout(resolve, 25));
  }
  root.unmount();
  controller.abort();
  const cancelled = await pending;
  client.clear();
  window.currentItems = null;
  window.previousItems = null;
  window.result = null;
  return observed && cancelled;
};
void window.loadFixture();
