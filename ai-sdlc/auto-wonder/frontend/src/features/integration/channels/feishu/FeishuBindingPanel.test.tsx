import { beforeEach, afterEach, describe, it, expect, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { message } from 'antd';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { useAuthStore } from '@/shared/auth/store';
import { FeishuBindingPanel } from './FeishuBindingPanel';
import type { FeishuBinding } from './feishuApi';

const binding: FeishuBinding = { id: 1, bindingMode: 'LEGACY', platformConfigId: null, groupId: null, routeVersion: 0,
  appId: 'cli_test', agentId: 7, status: 'ENABLED', version: 0,
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
  it('creates a shared group binding once and closes the form without a confirmation step', async () => {
    const user = userEvent.setup(); let payload: Record<string, unknown> | undefined;
    server.use(
      http.get('/api/integrations/feishu/bindings', () => HttpResponse.json(ok([]))),
      http.post('/api/integrations/feishu/bindings', async ({ request }) => {
        payload = await request.json() as Record<string, unknown>;
        return HttpResponse.json(ok({
          ...binding, id: 2, bindingMode: 'SHARED', groupId: 'oc_group',
        }));
      }),
    );
    renderPanel();
    await user.click(await screen.findByRole('button', { name: '新建飞书绑定' }));
    expect(await screen.findByText('先获取群 ID')).toBeInTheDocument();
    expect(screen.getByText('群 ID 指 chat_id（以 oc_ 开头）。请先在飞书后台配置事件订阅与所需权限。')).toBeInTheDocument();
    await user.type(await screen.findByLabelText('群 ID'), 'oc_group');
    await user.click(screen.getByLabelText('关联数字员工'));
    await user.click(await screen.findByText('Alpha'));
    await user.click(screen.getByRole('button', { name: '保存' }));
    // One save closes the drawer; no second save and no group confirmation step.
    await waitFor(() => expect(screen.queryByRole('dialog', { name: '新建飞书绑定' })).not.toBeInTheDocument());
    expect(screen.queryByRole('dialog', { name: /等待群内确认/ })).not.toBeInTheDocument();
    expect(payload).toMatchObject({ bindingMode: 'SHARED', groupId: 'oc_group', agentId: 7, status: 'ENABLED' });
    expect(payload).not.toHaveProperty('appSecret');
  });

  it('shows shared rows without any confirmation column while legacy rows keep unavailable group fields', async () => {
    const shared: FeishuBinding = {
      ...binding, id: 3, bindingMode: 'SHARED', groupId: 'oc_shared',
    };
    server.use(http.get('/api/integrations/feishu/bindings', () => HttpResponse.json(ok([shared, binding]))));
    renderPanel();

    expect(await screen.findByText('oc_shared')).toBeInTheDocument();
    expect(screen.getByText('共享群聊')).toBeInTheDocument();
    expect(screen.getByText('旧独立机器人')).toBeInTheDocument();
    expect(screen.queryByText(/已确认：/)).not.toBeInTheDocument();
    expect(screen.queryByText('群确认')).not.toBeInTheDocument();
    // The legacy row is located by its application identity, never a fabricated group ID.
    expect(screen.getByText('cli_test')).toBeInTheDocument();
  });

  it('opens the existing same-group binding after an uncertain create instead of creating again', async () => {
    const user = userEvent.setup();
    let posts = 0;
    const existing: FeishuBinding = {
      ...binding, id: 10, bindingMode: 'SHARED', groupId: 'oc_group',
    };
    server.use(
      http.get('/api/integrations/feishu/bindings', () => HttpResponse.json(ok([existing]))),
      http.post('/api/integrations/feishu/bindings', () => {
        posts += 1;
        return HttpResponse.json({ success: false, code: '10000', message: 'network lost' });
      }),
    );
    renderPanel();
    await screen.findByText('oc_group');
    await user.click(screen.getByRole('button', { name: '新建飞书绑定' }));
    await user.type(await screen.findByLabelText('群 ID'), 'oc_group');
    await user.click(screen.getByLabelText('关联数字员工'));
    await user.click(await screen.findByText('Alpha', { selector: '.ant-select-item-option-content' }));
    await user.click(screen.getByRole('button', { name: '保存' }));

    const detail = within(await screen.findByRole('dialog', { name: '绑定详情' }));
    expect(detail.getByText('oc_group')).toBeInTheDocument();
    expect(posts).toBe(1);
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
    await user.click(screen.getByRole('button', { name: '保存' }));
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

  it('deletes a binding from the action column only after the confirmation is accepted', async () => {
    const user = userEvent.setup();
    const removed = vi.fn();
    let gone = false;
    server.use(
      http.get('/api/integrations/feishu/bindings', () => HttpResponse.json(ok(gone ? [] : [binding]))),
      http.delete('/api/integrations/feishu/bindings/1', () => { removed(); gone = true; return HttpResponse.json(ok(null)); }),
    );
    renderPanel();
    await screen.findByText('cli_test');

    const row = screen.getByText('cli_test').closest('tr') as HTMLElement;
    await user.click(within(row).getByRole('button', { name: /删\s*除/ }));
    const popup = within(await screen.findByRole('tooltip'));
    expect(popup.getByText('确认删除该绑定？')).toBeInTheDocument();
    expect(popup.getByText('删除该绑定会立即停止其群聊服务，且不可恢复。')).toBeInTheDocument();
    await user.click(popup.getByRole('button', { name: '删除绑定' }));
    await waitFor(() => expect(removed).toHaveBeenCalledOnce());
    await waitFor(() => expect(screen.queryByText('cli_test')).not.toBeInTheDocument());
  });

  it('keeps the row when the action-column deletion is cancelled', async () => {
    const user = userEvent.setup();
    const removed = vi.fn();
    server.use(
      http.get('/api/integrations/feishu/bindings', () => HttpResponse.json(ok([binding]))),
      http.delete('/api/integrations/feishu/bindings/1', () => { removed(); return HttpResponse.json(ok(null)); }),
    );
    renderPanel();
    await screen.findByText('cli_test');

    const row = screen.getByText('cli_test').closest('tr') as HTMLElement;
    await user.click(within(row).getByRole('button', { name: /删\s*除/ }));
    const popup = within(await screen.findByRole('tooltip'));
    await user.click(popup.getByRole('button', { name: /取\s*消/ }));

    expect(removed).not.toHaveBeenCalled();
    expect(screen.getByText('cli_test')).toBeInTheDocument();
  });

  it('still deletes from the details after a second confirmation', async () => {
    const removed = vi.fn();
    server.use(
      http.get('/api/integrations/feishu/bindings', () => HttpResponse.json(ok([binding]))),
      http.delete('/api/integrations/feishu/bindings/1', () => { removed(); return HttpResponse.json(ok(null)); }),
    );
    renderPanel();
    await screen.findByText('cli_test');
    await userEvent.click(screen.getByRole('button', { name: '详情' }));
    const detail = within(await screen.findByRole('dialog', { name: '绑定详情' }));
    await userEvent.click(detail.getByRole('button', { name: '删除绑定' }));
    const confirm = await screen.findByRole('tooltip');
    await userEvent.click(within(confirm).getByRole('button', { name: '删除绑定' }));
    await waitFor(() => expect(removed).toHaveBeenCalledOnce());
  });

  it('blocks row deletion for read-only members before any request', async () => {
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_ONLY');
    const removed = vi.fn();
    server.use(
      http.get('/api/integrations/feishu/bindings', () => HttpResponse.json(ok([binding]))),
      http.delete('/api/integrations/feishu/bindings/1', () => { removed(); return HttpResponse.json(ok(null)); }),
    );
    renderPanel();
    await screen.findByText('cli_test');

    const row = screen.getByText('cli_test').closest('tr') as HTMLElement;
    await userEvent.click(within(row).getByRole('button', { name: /删\s*除/ }));
    const popup = within(await screen.findByRole('tooltip'));
    await userEvent.click(popup.getByRole('button', { name: '删除绑定' }));

    expect(await screen.findByText('当前为只读权限，删除绑定需要管理员权限')).toBeInTheDocument();
    expect(removed).not.toHaveBeenCalled();
  });
});
