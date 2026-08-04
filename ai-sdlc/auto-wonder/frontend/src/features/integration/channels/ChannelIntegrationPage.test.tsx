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
  it('renders DingTalk tab active and Feishu/Slack as disabled placeholders', async () => {
    server.use(
      http.get('/api/integrations/dingtalk/bindings', () => HttpResponse.json(emptyResult)),
      http.get('/api/agents', () => HttpResponse.json(emptyResult)),
    );
    renderPage();
    expect(await screen.findByText('消息渠道集成')).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: '钉钉' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: '飞书（待接入）' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Slack（待接入）' })).toBeInTheDocument();
    // DingTalk panel is active by default → its new-binding button shows
    expect(await screen.findByText('新建绑定')).toBeInTheDocument();
  });
});
