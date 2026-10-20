import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { message } from 'antd';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { createAppRoutes } from '@/app/router';
import { useAuthStore } from '@/shared/auth/store';
import { i18n } from '@/shared/i18n';
import { BasicProfilePanel } from './BasicProfilePanel';
import { ChangePasswordPanel } from './ChangePasswordPanel';
import { DeactivationPanel } from './DeactivationPanel';
import { McpTokenSettingsPanel } from '@/features/open-platform/McpTokenSettingsPanel';
import * as profileApi from './profileApi';
import * as mcpApi from '@/features/open-platform/api';
import * as brandingApi from '@/features/platform/brandingApi';

const ok = (data: unknown) => HttpResponse.json({ success: true, code: '0', message: '', data });
const language = (locale: string) => act(async () => { await i18n.changeLanguage(locale); });
const username = 'alice<img src=x>';
const issued = { id: 21, name: 'MCP Token', tokenPrefix: 'raw_prefix', token: 'raw/+?密钥' };
const clipboardDescriptor = Object.getOwnPropertyDescriptor(navigator, 'clipboard');
function mount(panel?: React.ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const router = createMemoryRouter(panel ? [{ path: '/profile/settings', element: panel }, { path: '/login', element: <div>login</div> }] : createAppRoutes(), { initialEntries: ['/profile/settings?keep=raw'] });
  return { client, router, ...render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>) };
}
describe('personal account and MCP language boundary', () => {
  beforeEach(async () => {
    await i18n.changeLanguage('zh-CN');
    useAuthStore.getState().clear();
    useAuthStore.getState().setTokens('access', 'refresh');
    useAuthStore.getState().setUser({ id: 1, username, nickname: 'raw', email: 'raw@example.com', isAdmin: true });
    server.use(
      http.get('/api/users/me/profile', () => ok({ id: 1, username, nickname: 'raw', email: '', phone: '' })),
      http.get('/api/users/me/im-identities', () => ok([{ provider: 'FEISHU', externalUserId: 'raw-id', configured: true, platformReady: true, testAvailable: true }])),
      http.get('/api/workspaces/mine', () => ok([])),
      http.get('/api/notifications/unread-count', () => ok(0)),
      http.post('/api/auth/refresh', () => ok({ accessToken: 'access' })),
      http.get('/api/mcp/tokens', () => ok([])),
      http.get('/api/mcp/tokens/tools', () => ok([{ name: 'autowonder.get_workitem', description: '原文<img src=x>', inputSchema: { type: 'object', properties: { workspaceId: { type: 'integer', description: 'raw-schema' } }, required: ['workspaceId'] } }])),
      http.post('/api/mcp/tokens', () => ok(issued)),
    );
  });
  afterEach(async () => {
    vi.restoreAllMocks(); message.destroy(); await i18n.changeLanguage('zh-CN');
    if (clipboardDescriptor) Object.defineProperty(navigator, 'clipboard', clipboardDescriptor);
    else Reflect.deleteProperty(navigator, 'clipboard');
  });
  it('updates cached actual routes and all panels without replacing the IM draft or query state', async () => {
    const identities = vi.spyOn(profileApi, 'listMyImIdentities');
    const { router } = mount();
    await screen.findByDisplayValue('raw');
    await language('en-US');
    expect(screen.getByRole('heading', { name: 'Personal settings' })).toBeInTheDocument();
    expect(screen.getByLabelText('Nickname')).toHaveValue('raw');
    await userEvent.click(screen.getByRole('tab', { name: 'IM identity' }));
    const input = await screen.findByLabelText('Feishu user ID');
    await userEvent.clear(input); await userEvent.type(input, 'draft-id');
    const calls = identities.mock.calls.length;
    await language('zh-CN'); expect(screen.getByLabelText('飞书工号')).toBe(input); expect(input).toHaveValue('draft-id');
    expect(screen.getByRole('button', { name: '发送测试' })).toBeDisabled();
    await language('en-US'); expect(screen.getByText('Save your Feishu user ID before testing')).toBeInTheDocument();
    expect(identities).toHaveBeenCalledTimes(calls); expect(router.state.location.search).toBe('?keep=raw&tab=im');
    await userEvent.click(screen.getByRole('tab', { name: 'Change password' })); expect(await screen.findByLabelText('Current password')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('tab', { name: 'Delete account' })); expect(await screen.findByRole('button', { name: 'Delete account' })).toBeInTheDocument();
    await userEvent.click(screen.getByRole('tab', { name: 'MCP tokens' })); expect(await screen.findByRole('button', { name: 'New token' })).toBeInTheDocument();
  }, 20000);
  it('refreshes only visible profile errors while retaining drafts and pristine phone', async () => {
    mount(<BasicProfilePanel />);
    const nickname = await screen.findByLabelText('昵称'); await waitFor(() => expect(nickname).toHaveValue('raw'));
    const email = screen.getByLabelText('邮箱'), phone = screen.getByLabelText('联系方式');
    await userEvent.clear(nickname); await userEvent.type(email, 'bad-email');
    await userEvent.click(screen.getByRole('button', { name: '保存修改' })); await screen.findByText('邮箱格式不正确');
    await language('en-US'); await screen.findByText('Enter a nickname'); await screen.findByText('Invalid email address');
    expect(screen.getByLabelText('Nickname')).toBe(nickname); expect(email).toHaveValue('bad-email'); expect(phone).toHaveValue('');
    expect(phone).not.toHaveAttribute('aria-invalid', 'true');
    await language('zh-CN'); await screen.findByText('邮箱格式不正确'); expect(email).toHaveValue('bad-email');
  });
  it('keeps password values, keyboard visibility and mismatch errors through a switch and submits exact secrets', async () => {
    const save = vi.spyOn(profileApi, 'changePassword').mockResolvedValue();
    mount(<ChangePasswordPanel />);
    const old = screen.getByLabelText('当前密码'), next = screen.getByLabelText('新密码'), confirm = screen.getByLabelText('确认新密码');
    await userEvent.type(old, ' old-secret '); await userEvent.type(next, ' new-secret '); await userEvent.type(confirm, 'mismatch');
    await userEvent.click(screen.getByRole('button', { name: '修改密码' })); await screen.findByText('两次输入的密码不一致');
    screen.getAllByRole('button', { name: '显示密码' })[0].focus(); await userEvent.keyboard('{Enter}'); expect(old).toHaveAttribute('type', 'text');
    const focused = document.activeElement;
    await language('en-US'); await screen.findByText('Passwords do not match'); expect(old).toHaveValue(' old-secret '); expect(next).toHaveValue(' new-secret ');
    expect(screen.getByLabelText('Current password')).toBe(old); expect(screen.getByLabelText('New password')).toBe(next); expect(screen.getByLabelText('Confirm new password')).toBe(confirm);
    const hide = screen.getByRole('button', { name: 'Hide password' }); expect(document.activeElement).toBe(focused); hide.focus(); await userEvent.keyboard(' '); expect(old).toHaveAttribute('type', 'password');
    await userEvent.clear(confirm); await userEvent.type(confirm, ' new-secret '); await userEvent.click(screen.getByRole('button', { name: 'Change password' }));
    await waitFor(() => expect(save).toHaveBeenCalledWith({ oldPassword: ' old-secret ', newPassword: ' new-secret ' }));
    await waitFor(() => expect(screen.getByLabelText('Current password')).toHaveValue(''));
    expect(screen.getByLabelText('New password')).toHaveValue(''); expect(screen.getByLabelText('Confirm new password')).toHaveValue('');
  });
  it('keeps raw deactivation confirmation and actual modal close accessible names', async () => {
    const remove = vi.spyOn(profileApi, 'deleteMyAccount').mockResolvedValue();
    const { container } = mount(<DeactivationPanel />);
    await userEvent.click(await screen.findByRole('button', { name: '注销账号' }));
    expect(screen.getByRole('button', { name: '关闭' })).toBeInTheDocument();
    const input = screen.getByRole('textbox', { name: '输入用户名' }); await userEvent.type(input, username);
    await language('en-US'); expect(screen.getByRole('dialog', { name: 'Confirm account deletion' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Close' })).toBeInTheDocument(); expect(screen.getByRole('textbox', { name: 'Enter username' })).toBe(input);
    expect(screen.getByText(username)).toBeInTheDocument(); expect(container.querySelector('img')).toBeNull(); expect(input).toHaveValue(username);
    expect(remove).not.toHaveBeenCalled(); await userEvent.click(screen.getByRole('button', { name: 'Confirm deletion' }));
    await waitFor(() => expect(remove).toHaveBeenCalledWith(username));
  });
  it('signs the session out and returns to login right after the account is deleted', async () => {
    const remove = vi.spyOn(profileApi, 'deleteMyAccount').mockResolvedValue();
    const { router } = mount(<DeactivationPanel />);
    await userEvent.click(await screen.findByRole('button', { name: '注销账号' }));
    await userEvent.type(screen.getByRole('textbox', { name: '输入用户名' }), username);
    await userEvent.click(screen.getByRole('button', { name: '确认注销' }));
    await waitFor(() => expect(remove).toHaveBeenCalledWith(username));
    // Deletion invalidates every issued credential server-side, so the client must drop its own
    // session instead of staying on a page whose every request would now 401.
    await waitFor(() => expect(useAuthStore.getState().accessToken).toBeNull());
    expect(useAuthStore.getState().refreshToken).toBeNull();
    expect(useAuthStore.getState().user).toBeNull();
    await waitFor(() => expect(router.state.location.pathname).toBe('/login'));
  });
  it('retains expanded raw schemas while capability labels and plural counts change', async () => {
    const tools = vi.spyOn(mcpApi, 'listMcpTools');
    const { container } = mount(<McpTokenSettingsPanel />); await userEvent.click(screen.getByRole('tab', { name: '工具' }));
    const schema = await screen.findByText('workspaceId'); const calls = tools.mock.calls.length;
    await language('en-US'); expect(screen.getByText('Work item management')).toBeInTheDocument(); expect(screen.getByText('1 tool')).toBeInTheDocument();
    expect(screen.getByText('workspaceId')).toBe(schema); expect(screen.getAllByText('原文<img src=x>')).toHaveLength(2); expect(screen.getByText('raw-schema')).toBeInTheDocument(); expect(container.querySelector('img')).toBeNull();
    await language('zh-CN'); expect(screen.getByText('workspaceId')).toBe(schema); expect(tools).toHaveBeenCalledTimes(calls);
  });
  it('keeps token draft errors, one-time secrets, encoded URLs and exact copied bytes across switches', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined); Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } });
    const create = vi.spyOn(mcpApi, 'createMcpToken');
    mount(<McpTokenSettingsPanel />); const newButton = screen.getByRole('button', { name: '新建令牌' }); await waitFor(() => expect(newButton).toBeEnabled()); await userEvent.click(newButton);
    const name = screen.getByLabelText('名称'); expect(name).toHaveValue('MCP Token'); await userEvent.clear(name); await userEvent.click(screen.getByRole('button', { name: '创建' })); await screen.findByText('请输入名称');
    await language('en-US'); await screen.findByText('Enter a name'); expect(screen.getByLabelText('Name')).toBe(name); expect(name).toHaveValue(''); expect(screen.getByRole('button', { name: 'Close' })).toBeInTheDocument();
    await userEvent.type(name, 'MCP Token'); await userEvent.click(screen.getByRole('button', { name: 'Create' })); const token = await screen.findByDisplayValue(issued.token);
    const url = 'https://community.example/api/mcp/raw%2F%2B%3F%E5%AF%86%E9%92%A5/';
    await language('zh-CN'); expect(screen.getByDisplayValue(issued.token)).toBe(token); expect(screen.getAllByDisplayValue(url).length).toBe(2);
    await userEvent.click(screen.getByRole('button', { name: '复制新令牌' })); expect(writeText).toHaveBeenLastCalledWith(issued.token);
    await language('en-US'); await userEvent.click(screen.getByRole('button', { name: 'Copy MCP URL' })); expect(writeText).toHaveBeenLastCalledWith(url);
    const qoder = screen.getByText('Qoder configuration').closest('.ant-collapse-item')!;
    await userEvent.click(within(qoder as HTMLElement).getByRole('button', { name: 'Copy' }));
    expect(writeText).toHaveBeenLastCalledWith('{\n  "mcpServers": {\n    "autowonder": {\n      "url": "https://community.example/api/mcp/raw%2F%2B%3F%E5%AF%86%E9%92%A5/"\n    }\n  }\n}'); expect(create).toHaveBeenCalledOnce(); expect(create.mock.calls[0][0]).toEqual({ name: 'MCP Token' });
  }, 15000);
  it('uses the completion language for delayed clipboard and revoke feedback', async () => {
    let finish!: () => void; const pending = new Promise<void>(resolve => { finish = resolve; });
    const writeText = vi.fn(() => pending); Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } });
    const revoke = vi.spyOn(mcpApi, 'revokeMcpToken').mockImplementation(() => pending); const success = vi.spyOn(message, 'success');
    server.use(http.get('/api/mcp/tokens', () => ok([{ id: 21, name: '原始名字', tokenPrefix: 'raw_prefix' }])));
    mount(<McpTokenSettingsPanel />); await userEvent.click(await screen.findByRole('button', { name: '撤销 原始名字' }));
    const copy = screen.getByRole('button', { name: '复制 MCP 服务地址' }); await waitFor(() => expect(copy).toBeEnabled()); await userEvent.click(copy);
    await language('en-US'); await act(async () => { finish(); }); await waitFor(() => expect(success).toHaveBeenCalledWith('Copied')); expect(success).toHaveBeenCalledWith('Token revoked');
    expect(revoke.mock.calls[0][0]).toBe(21); expect(writeText).toHaveBeenCalledWith('https://community.example/api/mcp/<MCP_TOKEN>/');
  });
  it('localizes actual schema expansion buttons and keeps a manually expanded second tool', async () => {
    server.use(http.get('/api/mcp/tokens/tools', () => ok([
      { name: 'autowonder.get_workitem', description: 'first', inputSchema: { type: 'object', properties: {} } },
      { name: 'autowonder.other_workitem', description: 'second', inputSchema: { type: 'object', properties: { 'raw.field': { type: 'array', items: { type: 'object', properties: { nested: { type: 'string', description: 'raw nested' } } } } } } },
      { name: 'unknown.raw', description: 'unknown raw', inputSchema: { type: 'object', properties: {} } },
    ])));
    mount(<McpTokenSettingsPanel />); await userEvent.click(screen.getByRole('tab', { name: '工具' }));
    const row = await screen.findByRole('row', { name: /autowonder.other_workitem/ });
    await userEvent.click(within(row).getByRole('button', { name: '展开行' })); const field = await screen.findByText('raw.field[].nested');
    await language('en-US'); expect(within(row).getByRole('button', { name: 'Collapse row' })).toBeInTheDocument();
    expect(screen.getByText('raw.field[].nested')).toBe(field); expect(screen.getByText('2 tools')).toBeInTheDocument(); expect(screen.getByText('Other capabilities')).toBeInTheDocument();
    await language('zh-CN'); expect(within(row).getByRole('button', { name: '收起行' })).toBeInTheDocument(); expect(screen.getByText('raw.field[].nested')).toBe(field);
  });
  it('formats token timestamps in the current locale while keeping their instant', async () => {
    const date = '2026-09-30T10:00:00Z';
    server.use(http.get('/api/mcp/tokens', () => ok([{ id: 22, name: 'raw date', tokenPrefix: 'raw', gmtCreate: date, lastUsedAt: null }])));
    mount(<McpTokenSettingsPanel />); const row = await screen.findByRole('row', { name: /raw date/ });
    for (const locale of ['zh-CN', 'en-US']) {
      await language(locale);
      expect(within(row).getByText(new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(date)))).toBeInTheDocument();
      expect(within(row).getByText('-')).toBeInTheDocument();
    }
  });
  it('keeps endpoint loading and missing endpoint controls reactive without fetching on a switch', async () => {
    let finish!: (data: brandingApi.PlatformBranding) => void;
    const branding = vi.spyOn(brandingApi, 'getPublicBranding').mockImplementation(() => new Promise<brandingApi.PlatformBranding>(resolve => { finish = resolve; }));
    mount(<McpTokenSettingsPanel />); expect(screen.getByLabelText('MCP 服务地址')).toHaveValue('正在加载 MCP 服务地址');
    expect(screen.getByRole('button', { name: '新建令牌' })).toBeDisabled(); await language('en-US');
    expect(screen.getByLabelText('MCP endpoint')).toHaveValue('Loading MCP endpoint'); expect(screen.getByRole('button', { name: 'New token' })).toBeDisabled();
    await act(async () => { finish({ ...brandingApi.DEFAULT_BRANDING, mcpBaseUrl: '' }); });
    await waitFor(() => expect(screen.getByLabelText('MCP endpoint')).toHaveValue('Failed to load MCP endpoint'));
    expect(screen.getByRole('button', { name: 'Copy MCP endpoint' })).toBeDisabled(); expect(branding).toHaveBeenCalledOnce();
  });
  it('renders delayed issued secrets in the completion language and clears them only on close', async () => {
    let finish!: (data: mcpApi.IssuedMcpToken) => void;
    const create = vi.spyOn(mcpApi, 'createMcpToken').mockImplementation(() => new Promise<mcpApi.IssuedMcpToken>(resolve => { finish = resolve; }));
    mount(<McpTokenSettingsPanel />); const button = screen.getByRole('button', { name: '新建令牌' }); await waitFor(() => expect(button).toBeEnabled()); await userEvent.click(button);
    await userEvent.click(screen.getByRole('button', { name: '创建' })); await language('en-US');
    expect(screen.getByLabelText('Name')).toHaveValue('MCP Token'); expect(screen.getByRole('button', { name: 'Create' })).toBeInTheDocument();
    await act(async () => { finish(issued); }); await screen.findByRole('button', { name: 'Copy new token' });
    expect(screen.getByRole('textbox', { name: 'New token' })).toHaveValue(issued.token); expect(create).toHaveBeenCalledOnce();
    await userEvent.click(screen.getByRole('button', { name: 'Done' })); await userEvent.click(screen.getByRole('button', { name: 'New token' }));
    expect(screen.getByLabelText('Name')).toHaveValue('MCP Token'); expect(screen.queryByDisplayValue(issued.token)).not.toBeInTheDocument();
  });
  it('reports delayed failed clipboard fallback in the completion language and preserves attempted bytes', async () => {
    let fail!: (error: Error) => void;
    const writeText = vi.fn(() => new Promise<void>((_, reject) => { fail = reject; }));
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } });
    const execDescriptor = Object.getOwnPropertyDescriptor(document, 'execCommand');
    Object.defineProperty(document, 'execCommand', { configurable: true, value: () => false }); vi.spyOn(window, 'prompt').mockReturnValue(null);
    try {
      mount(<McpTokenSettingsPanel />); const button = screen.getByRole('button', { name: '复制 MCP 服务地址' }); await waitFor(() => expect(button).toBeEnabled()); await userEvent.click(button);
      await language('en-US'); await act(async () => { fail(new Error('denied')); });
      await screen.findByText('Automatic copy failed. Please copy manually'); expect(screen.queryByText('Copied')).not.toBeInTheDocument();
      expect(writeText).toHaveBeenCalledWith('https://community.example/api/mcp/<MCP_TOKEN>/');
    } finally {
      if (execDescriptor) Object.defineProperty(document, 'execCommand', execDescriptor); else Reflect.deleteProperty(document, 'execCommand');
    }
  });
  it('blocks Enter submission when the current username is unavailable', async () => {
    useAuthStore.getState().setUser({ id: 1, username: '', nickname: '', email: '' });
    const remove = vi.spyOn(profileApi, 'deleteMyAccount').mockResolvedValue(); mount(<DeactivationPanel />);
    await userEvent.click(await screen.findByRole('button', { name: '注销账号' }));
    const input = screen.getByRole('textbox', { name: '输入用户名' }); input.focus(); await userEvent.keyboard('{Enter}');
    expect(screen.getByRole('button', { name: '确认注销' })).toBeDisabled(); expect(remove).not.toHaveBeenCalled();
    await language('en-US'); expect(screen.getByText('(unavailable)')).toBeInTheDocument();
  });
  it('keeps unknown provider identities as raw labels instead of identifying them as DingTalk', async () => {
    server.use(http.get('/api/users/me/im-identities', () => ok([{ provider: 'UNKNOWN_RAW', externalUserId: 'raw-id', configured: true, platformReady: true, testAvailable: true }])));
    const { router } = mount(); await act(async () => { await router.navigate('/profile/settings?keep=raw&tab=im'); });
    expect(await screen.findByLabelText('UNKNOWN_RAW工号')).toHaveValue('raw-id');
    await language('en-US'); expect(screen.getByLabelText('UNKNOWN_RAW user ID')).toHaveValue('raw-id'); expect(screen.queryByText('DingTalk')).not.toBeInTheDocument();
  });
});
