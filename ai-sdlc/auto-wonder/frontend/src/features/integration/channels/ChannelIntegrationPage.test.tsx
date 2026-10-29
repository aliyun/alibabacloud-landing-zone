import { describe, it, expect } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
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

function mockSelection(provider: string) {
  server.use(http.get('/api/platform/im-channels/selection', () => HttpResponse.json({
    ...emptyResult,
    data: { provider, generation: 3, activatedAt: '2026-10-20T10:00:00Z' },
  })));
}

function mockChannelList(channels: unknown[]) {
  const provider = (channels[0] as { provider?: string } | undefined)?.provider ?? 'DINGTALK';
  server.use(
    http.get('/api/platform/im-channels', () => HttpResponse.json({ ...emptyResult, data: channels })),
    http.get('/api/integrations/dingtalk/bindings', () => HttpResponse.json(emptyResult)),
    http.get('/api/integrations/feishu/bindings', () => HttpResponse.json(emptyResult)),
    http.get('/api/agents', () => HttpResponse.json(emptyResult)),
    http.get('/api/integrations/channels/group-config', () => HttpResponse.json({
      ...emptyResult,
      data: {
        provider, configured: true, ready: true, desiredEnabled: true,
        appKey: 'platform-app', robotCode: 'platform-robot', selectionGeneration: 3,
        status: 'ACTIVE', drainingCount: 0, failedCount: 0, unknownCount: 0,
      },
    })),
  );
}

function mockGroupConfig(overrides: Record<string, unknown>) {
  server.use(http.get('/api/integrations/channels/group-config', () => HttpResponse.json({
    ...emptyResult,
    data: {
      provider: 'DINGTALK', configured: true, ready: true, desiredEnabled: true,
      appKey: 'platform-app', robotCode: 'platform-robot', selectionGeneration: 3,
      status: 'ACTIVE', drainingCount: 0, failedCount: 0, unknownCount: 0,
      ...overrides,
    },
  })));
}

describe('ChannelIntegrationPage', () => {
  it.each(['DINGTALK', 'FEISHU'])('only exposes the platform-selected %s channel', async (provider) => {
    mockChannelList([{ provider, selected: true, enabled: true }]);
    mockSelection(provider);
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

  it('prefers the persisted selection over a stale channel list', async () => {
    // The channel list still carries a selected flag for DingTalk, but the persisted
    // platform selection says Feishu; the page must follow the selection.
    mockChannelList([{ provider: 'DINGTALK', selected: true, enabled: true }]);
    mockSelection('FEISHU');
    renderPage();

    expect(await screen.findByText('飞书机器人绑定')).toBeInTheDocument();
    expect(screen.queryByText('钉钉机器人绑定')).not.toBeInTheDocument();
  });

  it('falls back to the channel list while the selection request is unavailable', async () => {
    mockChannelList([{ provider: 'FEISHU', selected: true, enabled: true }]);
    server.use(http.get('/api/platform/im-channels/selection', () => HttpResponse.error()));
    renderPage();

    expect(await screen.findByText('飞书机器人绑定')).toBeInTheDocument();
  });

  it('defaults to the dingtalk channel when no provider is selected', async () => {
    mockChannelList([]);
    mockSelection('DINGTALK');
    renderPage();
    expect(await screen.findByText('钉钉机器人绑定')).toBeInTheDocument();
    expect(await screen.findByRole('button', { name: /新建绑定/ })).toBeInTheDocument();
  });

  it('shows the placeholder empty state for an unregistered channel', async () => {
    mockChannelList([{ provider: 'SLACK', selected: true, enabled: false }]);
    mockSelection('SLACK');
    renderPage();
    expect(await screen.findByText('该渠道尚未接入')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '新建绑定' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '新建飞书绑定' })).not.toBeInTheDocument();
  });

  it('shows that receiving is paused after the platform switched channels', async () => {
    mockChannelList([{ provider: 'DINGTALK', selected: true, enabled: true }]);
    mockSelection('DINGTALK');
    mockGroupConfig({ status: 'PAUSED', drainingCount: 2 });
    renderPage();

    expect(await screen.findByText('已暂停接收')).toBeInTheDocument();
    expect(screen.getByText('待收尾 2 条')).toBeInTheDocument();
    expect(screen.getByText('平台群聊接入')).toBeInTheDocument();
    expect(await screen.findByText('钉钉机器人绑定')).toBeInTheDocument();
  });

  it('shows draining, failed and unknown delivery counts for the shared bot', async () => {
    mockChannelList([{ provider: 'DINGTALK', selected: true, enabled: true }]);
    mockSelection('DINGTALK');
    mockGroupConfig({ status: 'DRAINING', drainingCount: 4, failedCount: 2, unknownCount: 1 });
    renderPage();

    expect(await screen.findByText('收尾中')).toBeInTheDocument();
    expect(screen.getByText('待收尾 4 条')).toBeInTheDocument();
    expect(screen.getByText('投递失败 2 条')).toBeInTheDocument();
    expect(screen.getByText('投递结果未知 1 条')).toBeInTheDocument();
  });

  it('warns when the shared bot configuration is not ready', async () => {
    mockChannelList([{ provider: 'DINGTALK', selected: true, enabled: true }]);
    mockSelection('DINGTALK');
    mockGroupConfig({ status: 'ERROR', ready: false });
    renderPage();

    expect(await screen.findByText('群聊机器人配置异常')).toBeInTheDocument();
  });

  it('shows an unknown platform access state instead of green when the status is unrecognized', async () => {
    mockChannelList([{ provider: 'DINGTALK', selected: true, enabled: true }]);
    mockSelection('DINGTALK');
    mockGroupConfig({ status: 'QUARANTINED' });
    renderPage();

    expect(await screen.findByText('未知')).toBeInTheDocument();
    expect(screen.queryByText('运行中')).not.toBeInTheDocument();
  });

  it('shows a load-failure state for platform access instead of a healthy tag', async () => {
    mockChannelList([{ provider: 'DINGTALK', selected: true, enabled: true }]);
    mockSelection('DINGTALK');
    server.use(http.get('/api/integrations/channels/group-config', () => HttpResponse.error()));
    renderPage();

    expect(await screen.findByText('加载失败')).toBeInTheDocument();
  });

  it('shows the platform access explanation on keyboard focus', async () => {
    mockChannelList([{ provider: 'DINGTALK', selected: true, enabled: true }]);
    mockSelection('DINGTALK');
    mockGroupConfig({ status: 'ACTIVE' });
    renderPage();

    const tag = await screen.findByText('运行中');
    expect(tag).toHaveAttribute('tabindex', '0');
    fireEvent.focus(tag);
    expect(await screen.findByText('这是平台配置允许接入的状态，不代表每个群都已确认，也不保证每条消息处理或投递成功。')).toBeInTheDocument();
  });

  it('surfaces channel list loading failures', async () => {
    server.use(http.get('/api/platform/im-channels', () => HttpResponse.error()));
    mockSelection('DINGTALK');
    mockGroupConfig({});
    renderPage();
    expect(await screen.findByText('协作通知渠道加载失败，请刷新重试')).toBeInTheDocument();
  });
});
