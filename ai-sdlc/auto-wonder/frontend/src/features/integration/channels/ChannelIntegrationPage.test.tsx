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

function mockChannelList(channels: unknown[]) {
  server.use(
    http.get('/api/platform/im-channels', () => HttpResponse.json({ ...emptyResult, data: channels })),
    http.get('/api/integrations/dingtalk/bindings', () => HttpResponse.json(emptyResult)),
    http.get('/api/integrations/feishu/bindings', () => HttpResponse.json(emptyResult)),
    http.get('/api/agents', () => HttpResponse.json(emptyResult)),
  );
}

describe('ChannelIntegrationPage', () => {
  it.each(['DINGTALK', 'FEISHU'])('only exposes the platform-selected %s channel', async (provider) => {
    mockChannelList([{ provider, selected: true, enabled: true }]);
    renderPage();
    const label = provider === 'FEISHU' ? '飞书' : '钉钉';
    const other = provider === 'FEISHU' ? '钉钉' : '飞书';
    expect(await screen.findByText(`${label}机器人绑定`)).toBeInTheDocument();
    expect(screen.queryByText(`${other}机器人绑定`)).not.toBeInTheDocument();
    expect(screen.queryByRole('tab')).not.toBeInTheDocument();
    expect(
      await screen.findByRole('button', { name: provider === 'FEISHU' ? /新建飞书绑定/ : /新建绑定/ }),
    ).toBeInTheDocument();
  });

  it('defaults to the dingtalk channel when no provider is selected', async () => {
    mockChannelList([]);
    renderPage();
    expect(await screen.findByText('钉钉机器人绑定')).toBeInTheDocument();
    expect(await screen.findByRole('button', { name: /新建绑定/ })).toBeInTheDocument();
  });

  it('shows the placeholder empty state for an unregistered channel', async () => {
    mockChannelList([{ provider: 'SLACK', selected: true, enabled: false }]);
    renderPage();
    expect(await screen.findByText('该渠道尚未接入')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '新建绑定' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '新建飞书绑定' })).not.toBeInTheDocument();
  });

  it('surfaces channel list loading failures', async () => {
    server.use(http.get('/api/platform/im-channels', () => HttpResponse.error()));
    renderPage();
    expect(await screen.findByText('协作通知渠道加载失败，请刷新重试')).toBeInTheDocument();
  });
});
