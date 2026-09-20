import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { ChannelIntegrationPage } from './ChannelIntegrationPage';

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter><ChannelIntegrationPage /></MemoryRouter>
    </QueryClientProvider>,
  );
}

const emptyResult = { success: true, code: '0', message: '', traceId: null, data: [] };

describe('ChannelIntegrationPage', () => {
  it.each(['DINGTALK', 'FEISHU'])('only exposes the platform-selected %s channel, even when disabled', async (provider) => {
    server.use(
      http.get('/api/platform/im-channels', () => HttpResponse.json({ ...emptyResult, data: [{ provider, selected: true, enabled: false }] })),
      http.get('/api/integrations/dingtalk/bindings', () => HttpResponse.json(emptyResult)),
      http.get('/api/integrations/feishu/bindings', () => HttpResponse.json(emptyResult)),
      http.get('/api/agents', () => HttpResponse.json(emptyResult)),
    );
    renderPage();
    expect(await screen.findByText('消息渠道集成')).toBeInTheDocument();
    expect(screen.getAllByRole('tab')).toHaveLength(1);
    expect(screen.getByRole('tab', { name: provider === 'FEISHU' ? '飞书' : '钉钉' })).toBeInTheDocument();
    expect(screen.queryByRole('tab', { name: provider === 'FEISHU' ? '钉钉' : '飞书' })).not.toBeInTheDocument();
  });
});
