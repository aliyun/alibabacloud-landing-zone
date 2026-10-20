import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { message } from 'antd';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { createAppRoutes } from '@/app/router';
import { useAuthStore } from '@/shared/auth/store';
import { i18n } from '@/shared/i18n';
import { BrandingConfigPage } from './BrandingConfigPage';
import { PlatformAdminPanel } from './PlatformAdminPanel';
import { AboutAutoWonderPage } from '@/features/about/AboutAutoWonderPage';
import { DEFAULT_BRANDING, THEME_PRESETS } from './brandingApi';

const ok = (data: unknown) => HttpResponse.json({ success: true, code: '0', message: '', data });
const language = (locale: string) => act(async () => { await i18n.changeLanguage(locale); });
const rawName = '名字<img src=x>';
const self = { userId: 1, username: 'self', nickname: rawName, email: 'raw@example.com', active: true, self: true, removable: false, removeDisabledReason: '平台管理员不可移除自己' };
const other = { ...self, userId: 2, username: 'other', nickname: 'raw other', self: false, removable: true, removeDisabledReason: null };
function mount(element?: React.ReactNode, path = '/workspaces/branding') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
  const router = createMemoryRouter(element ? [{ path, element }] : createAppRoutes(), { initialEntries: [path] });
  return { client, router, ...render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>) };
}
beforeEach(async () => {
  await i18n.changeLanguage('zh-CN');
  window.localStorage.clear();
  useAuthStore.getState().clear();
  useAuthStore.getState().setTokens('access', 'refresh');
  useAuthStore.getState().setUser({ id: 1, username: 'self', nickname: rawName, email: '', isAdmin: true });
  server.use(
    http.get('/api/platform/branding', () => ok({ ...DEFAULT_BRANDING, canManage: true })),
    http.get('/api/platform/branding/public', () => ok({ ...DEFAULT_BRANDING, deploymentVersion: 'raw+version' })),
    http.get('/api/platform/im-channels', () => ok([{ provider: 'DINGTALK', enabled: true, appKey: 'ding-app', robotCode: 'robot', secretConfigured: true, ready: true }])),
    http.get('/api/platform/admins', () => ok({ admins: [self, other], canManage: true })),
    http.get('/api/platform/admins/candidates', () => ok([])),
  );
});
afterEach(async () => { vi.restoreAllMocks(); message.destroy(); await i18n.changeLanguage('zh-CN'); });

describe('platform and about language boundaries', () => {
  it('updates the actual cached no-workspace route and all tabs while keeping raw drafts, secret visibility and preset identity', async () => {
    let reads = 0;
    server.use(http.get('/api/platform/branding', () => { reads++; return ok({ ...DEFAULT_BRANDING, canManage: true }); }));
    const { container } = mount();
    const name = await screen.findByLabelText('平台名称');
    await waitFor(() => expect(name).toHaveValue('AutoWonder'));
    await userEvent.clear(name); await userEvent.type(name, rawName);
    const domain = screen.getByLabelText('部署域名'); await userEvent.clear(domain); await userEvent.type(domain, 'https://raw.example/+?a=1');
    await waitFor(() => expect(screen.getByRole('radio', { name: '海洋蓝' })).toBeEnabled()); await userEvent.click(screen.getByText('海洋蓝'));
    const color = screen.getByLabelText('选择主色'); expect(color).toHaveValue('#2563eb');
    await userEvent.click(screen.getByRole('tab', { name: '协作通知' }));
    const secret = screen.getByLabelText('AppSecret'); await userEvent.type(secret, ' raw/+秘密 ');
    const show = screen.getByRole('button', { name: '显示密钥' }); show.focus(); await userEvent.keyboard('{Enter}'); expect(secret).toHaveAttribute('type', 'text');
    await language('en-US');
    expect(screen.getByRole('heading', { name: 'Platform configuration' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Collaboration notifications' })).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByLabelText('AppSecret')).toBe(secret); expect(secret).toHaveValue(' raw/+秘密 ');
    screen.getByRole('button', { name: 'Hide secret' }).focus(); await userEvent.keyboard(' '); expect(secret).toHaveAttribute('type', 'password');
    await userEvent.click(screen.getByRole('tab', { name: 'Brand and theme' }));
    expect(screen.getByLabelText('Platform name')).toBe(name); expect(name).toHaveValue(rawName); expect(domain).toHaveValue('https://raw.example/+?a=1');
    expect(screen.getByRole('radio', { name: 'Ocean blue' })).toBeChecked(); expect(color).toHaveValue('#2563eb');
    expect(THEME_PRESETS.map(p => [p.key, p.primaryColor])).toEqual([
      ['aliyun-orange', '#f97316'], ['ocean-blue', '#2563eb'], ['jade-green', '#059669'], ['indigo', '#4f46e5'], ['rose', '#e11d48'], ['cyan', '#0891b2'], ['amber', '#d97706'], ['violet', '#7c3aed'], ['graphite', '#374151'], ['teal', '#0f766e'],
    ]);
    for (const label of ['Aliyun orange', 'Ocean blue', 'Jade green', 'Indigo', 'Rose', 'Cyan', 'Amber', 'Violet', 'Graphite', 'Teal']) expect(screen.getByRole('radio', { name: label })).toBeInTheDocument();
    await userEvent.click(screen.getByRole('tab', { name: 'Platform administrators' }));
    expect(await screen.findByRole('columnheader', { name: 'Administrator' })).toBeInTheDocument();
    await language('zh-CN'); expect(screen.getByRole('columnheader', { name: '管理员' })).toBeInTheDocument();
    expect(localStorage.getItem('autowonder.platform-config.tab')).toBe('platform-admin'); expect(reads).toBe(1);
    expect(container.querySelector('img[src="x"]')).toBeNull();
  }, 20000);
  it.each(['DINGTALK', 'FEISHU'])('retains visible conditional %s errors and draft bytes without validating pristine siblings', async provider => {
    server.use(http.get('/api/platform/im-channels', () => ok([{ provider, selected: true, enabled: true, appKey: '', robotCode: '', secretConfigured: false, ready: false }])));
    mount(<BrandingConfigPage />); await userEvent.click(screen.getByRole('tab', { name: '协作通知' }));
    const key = await screen.findByLabelText(provider === 'FEISHU' ? 'App ID' : 'AppKey'); await waitFor(() => expect(key).toBeEnabled());
    const secret = screen.getByLabelText('AppSecret'); await userEvent.type(secret, ' raw secret ');
    await userEvent.click(screen.getByRole('button', { name: /保存协作通知/ })); await screen.findByText('请输入 AppKey');
    await language('en-US'); await screen.findByText('Enter AppKey');
    if (provider === 'DINGTALK') expect(screen.getByText('Enter RobotCode')).toBeInTheDocument();
    else expect(screen.queryByText('Enter RobotCode')).not.toBeInTheDocument();
    expect(secret).toHaveValue(' raw secret '); expect(secret).not.toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByLabelText('Platform name')).not.toHaveAttribute('aria-invalid', 'true');
    await language('zh-CN'); await screen.findByText('请输入 AppKey'); expect(secret).toHaveValue(' raw secret ');
  });
  it('translates required unconfigured secrets and allows disabled empty credentials with the exact payload', async () => {
    server.use(http.get('/api/platform/im-channels', () => ok([{ provider: 'FEISHU', enabled: true, appKey: 'cli_raw', robotCode: '', secretConfigured: false, ready: false }])));
    let payload: unknown;
    server.use(http.put('/api/platform/im-channels/feishu', async ({ request }) => { payload = await request.json(); return ok({ provider: 'FEISHU', enabled: false, appKey: '', robotCode: '', secretConfigured: false, ready: false }); }));
    mount(<BrandingConfigPage />); await userEvent.click(screen.getByRole('tab', { name: '协作通知' }));
    await waitFor(() => expect(screen.getByLabelText('App ID')).toHaveValue('cli_raw'));
    await userEvent.click(screen.getByRole('button', { name: /保存协作通知/ })); await screen.findByText('请输入 AppSecret');
    await language('en-US'); await screen.findByText('Enter AppSecret');
    await userEvent.click(screen.getByRole('switch', { name: 'Enable Feishu robot' })); await userEvent.clear(screen.getByLabelText('App ID'));
    await userEvent.click(screen.getByRole('button', { name: 'Save collaboration notifications' }));
    await waitFor(() => expect(payload).toEqual({ enabled: false, appKey: '', robotCode: '', appSecret: '' }));
  });
  it.each([
    [[self], 'You cannot remove yourself as a platform administrator'],
    [[{ ...other, removable: false, removeDisabledReason: '平台管理员至少保留一名，无法移除最后一名' }], 'At least one platform administrator must remain'],
    [[self, other], 'You cannot remove yourself as a platform administrator'],
    [[{ ...other, removable: false, removeDisabledReason: '平台管理员不可移除自己' }, { ...other, userId: 3 }], '平台管理员不可移除自己'],
    [[{ ...other, removable: false, removeDisabledReason: 'future raw reason' }, { ...other, userId: 3 }], 'future raw reason'],
  ])('derives current finite disabled reasons from cached roster facts and preserves nonmatching raw reasons', async (admins, expected) => {
    let reads = 0; server.use(http.get('/api/platform/admins', () => { reads++; return ok({ admins, canManage: true }); }));
    mount(<PlatformAdminPanel />); const row = (await screen.findAllByText(admins[0].nickname!))[0].closest('tr')!;
    const remove = within(row).getByRole('button', { name: '移除' }); await userEvent.hover(remove.parentElement!);
    await language('en-US'); expect(await screen.findByRole('tooltip')).toHaveTextContent(expected);
    await language('zh-CN'); expect(await screen.findByRole('tooltip')).toHaveTextContent(admins[0].removeDisabledReason!); expect(reads).toBe(1);
  });
  it('keeps readonly permission hints reactive and never searches candidates', async () => {
    let requests = 0; server.use(http.get('/api/platform/admins', () => ok({ admins: [self, other], canManage: false })), http.get('/api/platform/admins/candidates', () => { requests++; return ok([]); }));
    mount(<PlatformAdminPanel />); const button = await screen.findByRole('button', { name: '添加管理员' });
    await userEvent.hover(button.parentElement!); await screen.findByRole('tooltip'); await language('en-US');
    expect(screen.getByRole('tooltip')).toHaveTextContent('Only platform administrators can perform this action');
    expect(screen.getByRole('combobox', { name: 'Search users to add' })).toBeDisabled(); expect(requests).toBe(0);
  });
  it('keeps candidate search, selected numeric user and raw open confirmation through switching, and exposes keyboard clear', async () => {
    let requests = 0; let added: unknown;
    server.use(http.get('/api/platform/admins', () => ok({ admins: [self, { ...other, nickname: 'other<img src=x>' }], canManage: true })), http.get('/api/platform/admins/candidates', () => { requests++; return ok([{ userId: 3, username: 'candidate', nickname: 'raw candidate', email: null }]); }), http.post('/api/platform/admins', async ({ request }) => { added = await request.json(); return ok({ admins: [self, { ...other, nickname: 'other<img src=x>' }], canManage: true }); }));
    const { container } = mount(<PlatformAdminPanel />);
    await screen.findByText('other<img src=x>'); const input = screen.getByRole('combobox', { name: '搜索可添加的用户' }); expect(input).toBeEnabled();
    await userEvent.type(input, '原文'); await userEvent.click(await screen.findByText('raw candidate', { selector: '.ant-select-item-option-content' })); expect(screen.getByRole('button', { name: '添加管理员' })).toBeEnabled(); const calls = requests;
    await language('en-US'); expect(screen.getByRole('combobox', { name: 'Search users to add' })).toBe(input); expect(screen.getAllByText('raw candidate').length).toBeGreaterThan(0); expect(requests).toBe(calls);
    await userEvent.click(screen.getByRole('button', { name: 'Add administrator' })); await waitFor(() => expect(added).toEqual({ userId: 3 }));
    await userEvent.type(input, 'raw search'); const clear = screen.getByRole('button', { name: 'Clear user selection and search' }); clear.focus(); await userEvent.keyboard('{Enter}'); expect(input).toHaveValue('');
    const row = (await screen.findByText('other<img src=x>')).closest('tr')!; await userEvent.click(within(row).getByRole('button', { name: 'Remove' }));
    expect(await screen.findByText('Remove platform administrator access from other<img src=x>?')).toBeInTheDocument();
    await language('zh-CN'); expect(screen.getByText('确定移除 other<img src=x> 的平台管理员权限？')).toBeInTheDocument(); expect(container.querySelector('img')).toBeNull();
  });
  it('uses completion language for delayed branding save without rewriting raw data', async () => {
    let release!: () => void; let payload: unknown;
    server.use(http.put('/api/platform/branding', async ({ request }) => { payload = await request.json(); await new Promise<void>(resolve => { release = resolve; }); return ok({ ...DEFAULT_BRANDING, ...(payload as object) }); }));
    const success = vi.spyOn(message, 'success').mockImplementation(() => undefined as unknown as ReturnType<typeof message.success>);
    mount(<BrandingConfigPage />); const name = await screen.findByLabelText('平台名称'); await waitFor(() => expect(name).toHaveValue('AutoWonder'));
    await userEvent.clear(name); await userEvent.type(name, rawName); await userEvent.click(screen.getByRole('button', { name: /保存配置/ })); await waitFor(() => expect(release).toBeDefined());
    await language('en-US'); expect(screen.getByRole('button', { name: 'Save configuration' })).toBeInTheDocument(); await act(async () => { release(); });
    await waitFor(() => expect(success).toHaveBeenCalledWith('Platform configuration saved')); expect(payload).toMatchObject({ platformName: rawName, themeKey: 'aliyun-orange', primaryColor: '#f97316' }); expect(payload).not.toHaveProperty('appSecret');
  });
  it('updates every about step while retaining nodes, raw version, and decorative accessibility', async () => {
    mount(<AboutAutoWonderPage />, '/about'); await screen.findByText('raw+version'); const nodes = screen.getAllByRole('listitem');
    await language('en-US'); expect(screen.getByRole('region', { name: 'About the platform' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Automated software product delivery platform' })).toBeInTheDocument();
    expect(screen.getAllByRole('listitem')).toEqual(nodes);
    expect(nodes.map(item => item.textContent)).toEqual(['Requirement clarification', 'Start', 'Development', 'Verification', 'Review', 'Deployment', 'Testing', 'Delivery']);
    expect(screen.getByText('Continuous iteration')).toBeInTheDocument(); expect(screen.getByText('raw+version')).toBeInTheDocument(); expect(screen.queryByRole('img')).not.toBeInTheDocument();
    await language('zh-CN'); expect(screen.getAllByRole('listitem')).toEqual(nodes); expect(nodes[0]).toHaveTextContent('需求澄清');
  });
});

// Regression: AntD's default informational/error/confirmation icons expose English role names.
describe('platform decorative alert and confirmation icons', () => {
  it('keeps branding and administrator information icons decorative in both languages', async () => {
    mount(<BrandingConfigPage />);
    await waitFor(() => expect(screen.getByLabelText('平台名称')).toHaveValue('AutoWonder'));
    for (const locale of ['zh-CN', 'en-US']) {
      await language(locale);
      expect.soft(within(screen.getByRole('alert')).queryByRole('img')).not.toBeInTheDocument();
      await userEvent.click(screen.getByRole('tab', { name: locale === 'zh-CN' ? '平台管理员' : 'Platform administrators' }));
      await screen.findByText('raw other');
      expect.soft(within(screen.getByRole('alert')).queryByRole('img')).not.toBeInTheDocument();
      await userEvent.click(screen.getByRole('tab', { name: locale === 'zh-CN' ? '品牌与主题' : 'Brand and theme' }));
    }
  });
  it.each(['im', 'admin'])('keeps %s error details raw and its icon decorative in both languages', async kind => {
    const rawError = 'raw failure <b>保留</b>';
    server.use(http.get(kind === 'im' ? '/api/platform/im-channels' : '/api/platform/admins', () => HttpResponse.json({ success: false, code: '10500', message: rawError, data: null })));
    mount(kind === 'im' ? <BrandingConfigPage /> : <PlatformAdminPanel />);
    if (kind === 'im') await userEvent.click(screen.getByRole('tab', { name: '协作通知' }));
    await screen.findByText(rawError);
    for (const locale of ['zh-CN', 'en-US']) {
      await language(locale);
      const alert = screen.getByRole('alert');
      expect(alert).toHaveTextContent(rawError);
      expect(alert).toHaveTextContent(kind === 'im'
        ? locale === 'zh-CN' ? '协作通知配置加载失败' : 'Failed to load collaboration notification configuration'
        : locale === 'zh-CN' ? '平台管理员加载失败' : 'Failed to load platform administrators');
      expect.soft(within(alert).queryByRole('img')).not.toBeInTheDocument();
    }
  });
  it('keeps the open administrator confirmation decorative and cancellable in both languages', async () => {
    mount(<PlatformAdminPanel />);
    const row = (await screen.findByText('raw other')).closest('tr')!;
    await userEvent.click(within(row).getByRole('button', { name: '移除' }));
    const popup = await screen.findByRole('tooltip');
    for (const locale of ['zh-CN', 'en-US']) {
      await language(locale);
      expect(popup).toHaveTextContent(locale === 'zh-CN' ? '确定移除 raw other 的平台管理员权限？' : 'Remove platform administrator access from raw other?');
      expect(within(popup).getByRole('button', { name: locale === 'zh-CN' ? '确定移除' : 'Confirm removal' })).toBeEnabled();
      expect.soft(within(popup).queryByRole('img')).not.toBeInTheDocument();
    }
    await userEvent.click(within(popup).getByRole('button', { name: 'Cancel' }));
    expect(screen.getByText('raw other')).toBeInTheDocument();
  });
});
