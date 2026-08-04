import { beforeEach, describe, it, expect } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { SettingsPage } from './SettingsPage';
import { useAuthStore } from '@/shared/auth/store';

function apiOk<T>(data: T) {
  return { success: true, code: '0', message: '', traceId: null, data };
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter><SettingsPage /></MemoryRouter>
    </QueryClientProvider>,
  );
}

function useCommonSettingsHandlers(overrides: Parameters<typeof server.use>) {
  server.use(
    http.get('/api/ai-usage/quota', () => HttpResponse.json(apiOk({
      periodType: 'MONTH',
      maxCalls: 20000,
      maxTokens: 10000000,
      concurrencyLimit: 8,
    }))),
    http.get('/api/ai-usage', () => HttpResponse.json(apiOk([
      { period: '2026-07', scene: '仓库扫描', callCount: 12, inputTokens: 1000, outputTokens: 500 },
      { period: '2026-07', scene: 'SDLC生成', callCount: 3, inputTokens: 300, outputTokens: 120 },
    ]))),
    http.get('/api/settings/AI', () => HttpResponse.json(apiOk([
      { key: 'default_model', valueJson: 'qwen-max', secret: false },
      { key: 'request_timeout_seconds', valueJson: '60', secret: false },
      { key: 'api_key', valueJson: '********', secret: true },
    ]))),
    http.get('/api/notifications/prefs', () => HttpResponse.json(apiOk([
      { type: 'WORKITEM_ASSIGNED', inApp: true, dingtalk: false },
      { type: 'DELIVERY_BLOCKED', inApp: true, dingtalk: true },
    ]))),
    http.get('/api/settings/NOTIFY', () => HttpResponse.json(apiOk([
      { key: 'dingtalk_enabled', valueJson: 'true', secret: false },
      { key: 'dingtalk_webhook', valueJson: '********', secret: true },
    ]))),
    http.get('/api/settings/SYSTEM', () => HttpResponse.json(apiOk([
      { key: 'default_sdlc_id', valueJson: '334208147726012416', secret: false },
      { key: 'default_status_template_id', valueJson: 'default-coding', secret: false },
      { key: 'artifact_bucket', valueJson: 'auto-wonder-artifacts', secret: false },
    ]))),
    ...overrides,
  );
}

describe('SettingsPage', () => {
  beforeEach(() => {
    useAuthStore.getState().clear();
    useAuthStore.getState().setCurrentOrg({ id: 1, name: 'O', description: '' }, 'ADMIN');
  });

  it('renders AI quota and usage from dedicated APIs', async () => {
    useCommonSettingsHandlers([]);

    renderPage();

    expect(await screen.findByDisplayValue('20000', {}, { timeout: 5000 })).toBeInTheDocument();
    expect(screen.getByDisplayValue('10000000')).toBeInTheDocument();
    expect(screen.getByDisplayValue('qwen-max')).toBeInTheDocument();
    expect(screen.getByText('仓库扫描')).toBeInTheDocument();
    expect(screen.getByText('15')).toBeInTheDocument();
  });

  it('saves AI quota and model defaults', async () => {
    const saved: { quota?: unknown; aiSettings?: unknown } = {};
    useCommonSettingsHandlers([
      http.put('/api/ai-usage/quota', async ({ request }) => {
        saved.quota = await request.json();
        return HttpResponse.json(apiOk(null));
      }),
      http.put('/api/settings/AI', async ({ request }) => {
        saved.aiSettings = await request.json();
        return HttpResponse.json(apiOk(null));
      }),
    ]);

    renderPage();
    const callsInput = await screen.findByDisplayValue('20000');
    await userEvent.clear(callsInput);
    await userEvent.type(callsInput, '30000');
    await userEvent.click(screen.getByRole('button', { name: /保存AI配置/ }));

    await waitFor(() => {
      expect(saved.quota).toMatchObject({ maxCalls: 30000, maxTokens: 10000000, concurrencyLimit: 8 });
      expect(saved.aiSettings).toMatchObject({
        items: expect.arrayContaining([
          expect.objectContaining({ key: 'default_model', valueJson: 'qwen-max', secret: false }),
        ]),
      });
      expect((saved.aiSettings as { items: Array<{ key: string }> }).items.some((item) => item.key === 'api_key')).toBe(false);
    });
  });

  it('renders and saves notification preferences', async () => {
    const saved: { prefs?: unknown; notifySettings?: unknown } = {};
    useCommonSettingsHandlers([
      http.put('/api/notifications/prefs', async ({ request }) => {
        saved.prefs = await request.json();
        return HttpResponse.json(apiOk(null));
      }),
      http.put('/api/settings/NOTIFY', async ({ request }) => {
        saved.notifySettings = await request.json();
        return HttpResponse.json(apiOk(null));
      }),
    ]);

    renderPage();
    await userEvent.click(await screen.findByRole('tab', { name: '通知配置' }));

    expect(await screen.findByText('工单指派')).toBeInTheDocument();
    expect(screen.getByText('交付阻塞')).toBeInTheDocument();
    const notifyPanel = screen.getByTestId('notify-settings-panel');
    expect(within(notifyPanel).getByDisplayValue('********')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: /保存通知配置/ }));

    await waitFor(() => {
      expect(saved.prefs).toMatchObject({
        items: expect.arrayContaining([
          expect.objectContaining({ type: 'WORKITEM_ASSIGNED', inApp: true, dingtalk: false }),
        ]),
      });
      expect(saved.notifySettings).toMatchObject({
        items: expect.arrayContaining([
          expect.objectContaining({ key: 'dingtalk_enabled', valueJson: 'true', secret: false }),
        ]),
      });
      expect((saved.notifySettings as { items: Array<{ key: string }> }).items.some((item) => item.key === 'dingtalk_webhook')).toBe(false);
    });
  });

  it('uses SYSTEM group for system defaults', async () => {
    const saved: { systemSettings?: unknown } = {};
    useCommonSettingsHandlers([
      http.put('/api/settings/SYSTEM', async ({ request }) => {
        saved.systemSettings = await request.json();
        return HttpResponse.json(apiOk(null));
      }),
    ]);

    renderPage();
    await userEvent.click(await screen.findByRole('tab', { name: '系统配置' }));

    expect(await screen.findByDisplayValue('334208147726012416')).toBeInTheDocument();
    expect(screen.getByDisplayValue('auto-wonder-artifacts')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: /保存系统配置/ }));

    await waitFor(() => {
      expect(saved.systemSettings).toMatchObject({
        items: expect.arrayContaining([
          expect.objectContaining({ key: 'default_sdlc_id', valueJson: '334208147726012416' }),
          expect.objectContaining({ key: 'artifact_bucket', valueJson: 'auto-wonder-artifacts' }),
        ]),
      });
    });
  });

  it('keeps save commands visible but blocks read-only users before mutation', async () => {
    let quotaUpdates = 0;
    useAuthStore.getState().setCurrentOrg({ id: 1, name: 'O', description: '' }, 'READ_ONLY');
    useCommonSettingsHandlers([
      http.put('/api/ai-usage/quota', () => {
        quotaUpdates += 1;
        return HttpResponse.json(apiOk(null));
      }),
    ]);

    renderPage();

    const saveButton = await screen.findByRole('button', { name: /保存AI配置/ });
    expect(saveButton).toBeEnabled();
    await userEvent.click(saveButton);

    expect(quotaUpdates).toBe(0);
    expect(await screen.findByText('当前为只读权限，保存AI配置需要管理员权限')).toBeInTheDocument();
  });
});
