import { beforeEach, afterEach, describe, it, expect, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { message } from 'antd';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { useAuthStore } from '@/shared/auth/store';
import { FeishuBindingPanel } from './FeishuBindingPanel';
import type { FeishuBinding } from './feishuApi';

const binding: FeishuBinding = { id: 1, appId: 'cli_test', agentId: 7, status: 'ENABLED', version: 0,
  encryptKeyConfigured: true, callbackUrl: '/api/integrations/feishu/callback?bindingId=1', lastSuccessAt: null, lastError: null };
const ok = (data: unknown) => ({ success: true, code: '0', message: '', data });
function renderPanel() {
  return render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><MemoryRouter><FeishuBindingPanel /></MemoryRouter></QueryClientProvider>);
}
beforeEach(() => {
  useAuthStore.getState().clear();
  useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'ADMIN');
  server.use(http.get('/api/agents', () => HttpResponse.json(ok([{ id: 7, name: 'Alpha' }]))));
});
afterEach(() => message.destroy());

describe('FeishuBindingPanel', () => {
  it('creates a binding and displays its callback URL without exposing saved secrets', async () => {
    const user = userEvent.setup(); let payload: Record<string, unknown> | undefined;
    server.use(
      http.get('/api/integrations/feishu/bindings', () => HttpResponse.json(ok([]))),
      http.post('/api/integrations/feishu/bindings', async ({ request }) => {
        payload = await request.json() as Record<string, unknown>; return HttpResponse.json(ok(binding));
      }),
    );
    renderPanel();
    await user.click(await screen.findByRole('button', { name: '新建飞书绑定' }));
    await user.type(screen.getByLabelText('App ID'), 'cli_test');
    await user.type(screen.getByLabelText('App Secret'), 'new-secret');
    await user.type(screen.getByLabelText('Verification Token'), 'verify');
    await user.click(screen.getByLabelText('关联数字人'));
    await user.click(await screen.findByText('Alpha'));
    await user.click(screen.getByRole('button', { name: '保 存' }));
    expect(await screen.findByText('回调地址（复制到飞书事件订阅）')).toBeInTheDocument();
    expect(payload).toMatchObject({ appId: 'cli_test', appSecret: 'new-secret', verificationToken: 'verify', agentId: 7, status: 'ENABLED' });
    expect(screen.getByLabelText('App Secret')).toHaveValue('');
    expect(screen.getByText(/\/api\/integrations\/feishu\/callback\?bindingId=1/)).toBeInTheDocument();
  });
  it('editing preserves blank secrets and sends the version', async () => {
    const user = userEvent.setup(); let payload: Record<string, unknown> | undefined;
    server.use(
      http.get('/api/integrations/feishu/bindings', () => HttpResponse.json(ok([binding]))),
      http.put('/api/integrations/feishu/bindings/1', async ({ request }) => {
        payload = await request.json() as Record<string, unknown>; return HttpResponse.json(ok({ ...binding, version: 1 }));
      }),
    );
    renderPanel(); await user.click(await screen.findByRole('button', { name: /编\s*辑/ }));
    expect(screen.getByLabelText('App ID')).toBeDisabled();
    expect(screen.getByLabelText('App Secret')).toHaveValue('');
    await user.click(screen.getByRole('button', { name: '保 存' }));
    await waitFor(() => expect(payload).toMatchObject({ version: 0, appId: 'cli_test', agentId: 7 }));
    expect(payload?.appSecret).toBeUndefined(); expect(payload?.verificationToken).toBeUndefined();
  });
  it('denies creation for read-only members', async () => {
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_ONLY');
    server.use(http.get('/api/integrations/feishu/bindings', () => HttpResponse.json(ok([]))));
    renderPanel(); await userEvent.click(await screen.findByRole('button', { name: '新建飞书绑定' }));
    expect(await screen.findByText('当前为只读权限，新建飞书绑定需要管理员权限')).toBeInTheDocument();
    expect(screen.queryByLabelText('App ID')).not.toBeInTheDocument();
  });
  it('deletes only after confirmation', async () => {
    const removed = vi.fn();
    server.use(
      http.get('/api/integrations/feishu/bindings', () => HttpResponse.json(ok([binding]))),
      http.delete('/api/integrations/feishu/bindings/1', () => { removed(); return HttpResponse.json(ok(null)); }),
    );
    renderPanel(); await userEvent.click(await screen.findByRole('button', { name: /删\s*除/ }));
    expect(removed).not.toHaveBeenCalled();
    await userEvent.click(await screen.findByRole('button', { name: /OK|确 定/ }));
    await waitFor(() => expect(removed).toHaveBeenCalledOnce());
  });
});
