import { act, cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { message } from 'antd';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { i18n } from '@/shared/i18n';
import { formatDateTime } from '@/shared/i18n/format';
import { useAuthStore } from '@/shared/auth/store';
import { IntegrationsHubPage } from './IntegrationsHubPage';
import { WorkitemIntegrationPage } from './WorkitemIntegrationPage';
import { ChannelIntegrationPage } from './channels/ChannelIntegrationPage';
import { CHANNEL_REGISTRY } from './channels/channelRegistry';
import { DingTalkBindingDrawer } from './channels/dingtalk/DingTalkBindingDrawer';
import type { DingTalkBinding, DingTalkBindingRequest } from './channels/dingtalk/dingtalkApi';
import { DingTalkBindingPanel } from './channels/dingtalk/DingTalkBindingPanel';
import type { FeishuBindingRequest } from './channels/feishu/feishuApi';
import type { AoneBindingRequest } from './aoneApi';
import { FeishuBindingPanel } from './channels/feishu/FeishuBindingPanel';

const ok = (data: unknown) => ({ success: true, code: '0', message: '', data });
function localeUser() {
  const user = userEvent.setup();
  return {
    click: (element: Element) => act(async () => { await user.click(element); }),
    type: (element: Element, text: string) => act(async () => { await user.type(element, text); }),
    clear: (element: Element) => act(async () => { await user.clear(element); }),
    keyboard: (text: string) => act(async () => { await user.keyboard(text); }),
    hover: (element: Element) => act(async () => { await user.hover(element); }),
  };
}
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>(done => { resolve = done; });
  return { promise, resolve };
}
async function locale(value: 'zh-CN' | 'en-US') { await act(async () => { await i18n.changeLanguage(value); }); }
function mount(element: React.ReactElement, url = '/integrations?keep=raw') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity }, mutations: { retry: false } } });
  const router = createMemoryRouter([{ path: '/integrations', element }], { initialEntries: [url] });
  return { ...render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>), router, client };
}
beforeEach(async () => {
  await locale('zh-CN'); useAuthStore.getState().clear();
  useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'ADMIN');
  server.use(http.get('/api/integrations/capabilities', () => HttpResponse.json(ok({ aoneEnabled: true }))), http.get('/api/agents', () => HttpResponse.json(ok([
    { id: 7, name: '<em>员工</em>', roleCode: 'QA', roleName: '测试工程师' },
    { id: 8, name: '新建绑定', roleCode: 'constructor', roleName: '<b>用户角色</b>' },
  ]))));
});
afterEach(async () => { cleanup(); message.destroy(); vi.restoreAllMocks(); await locale('zh-CN'); });

it('updates the cached hub and Aone form, held results and delayed feedback without changing URL, drafts or raw data', async () => {
  const user = localeUser(); const search = deferred<unknown>(); const test = deferred<unknown>();
  const success = vi.spyOn(message, 'success');
  let listCalls = 0; let searchBody: unknown; let testBody: unknown; let syncBody: unknown;
  const date = '2026-08-06T14:43:07Z';
  server.use(
    http.get('/api/integrations/aone/bindings', () => { listCalls++; return HttpResponse.json(ok([{ id: '17', provider: 'AONE', externalProjectId: 'p1', externalProjectName: '<b>项目</b>', enabled: true, lastSuccessAt: date, lastError: '连接失败', clientKey: 'raw-client' }])); }),
    http.post('/api/integrations/aone/projects/search', async ({ request }) => { searchBody = await request.json(); return HttpResponse.json(ok(await search.promise)); }),
    http.post('/api/integrations/aone/bindings/test', async ({ request }) => { testBody = await request.json(); return HttpResponse.json(ok(await test.promise)); }),
    http.post('/api/integrations/aone/bindings/17/sync-now', async ({ request }) => { syncBody = await request.json(); return HttpResponse.json(ok({ imported: 1, updated: 2, commentsImported: 0, workitemIds: ['raw-工单'] })); }),
  );
  const { router, container } = mount(<IntegrationsHubPage />); await screen.findByText('<b>项目</b>');
  const secret = screen.getByLabelText('Access Secret'); const staff = screen.getByLabelText('写回身份 StaffId');
  const keyword = screen.getByPlaceholderText('项目名或关键字');
  const ids = screen.getByRole('textbox', { name: '外部工单 ID' });
  await user.type(screen.getByLabelText('Base URL'), 'https://aone.example.test'); await user.type(secret, 'synthetic-secret'); await user.type(keyword, '原始关键词');
  await user.click(screen.getByRole('button', { name: '搜索可托管项目' })); await screen.findByText('请输入写回身份 StaffId');
  await locale('en-US');
  expect(screen.getByRole('heading', { name: 'Integrations' })).toBeInTheDocument();
  expect(screen.getByRole('tab', { name: 'Work item platforms' })).toBeInTheDocument();
  expect(await screen.findByText('Enter the writeback StaffId')).toBeInTheDocument();
  expect(screen.queryByText('Select at least one managed project')).not.toBeInTheDocument();
  expect(screen.getByLabelText('Writeback StaffId')).toBe(staff);
  expect(secret).toHaveValue('synthetic-secret'); expect(keyword).toHaveValue('原始关键词');
  expect(screen.getByText(formatDateTime(date, { dateStyle: 'medium', timeStyle: 'medium' }))).toBeInTheDocument();
  expect(listCalls).toBe(1); expect(router.state.location.search).toBe('?keep=raw');
  await user.type(staff, 'staff-raw');
  await waitFor(() => expect(screen.getByRole('button', { name: 'Search manageable projects' })).not.toHaveClass('ant-btn-loading'), { timeout: 3000 });
  await user.click(screen.getByRole('button', { name: 'Search manageable projects' }));
  await waitFor(() => expect(searchBody).toMatchObject({ accessSecret: 'synthetic-secret', writebackStaffId: 'staff-raw' }));
  await locale('zh-CN'); await act(async () => { search.resolve({ items: [{ externalId: 'p1', name: '<b>项目</b>', rawJson: '{"原始":true}' }] }); });
  await waitFor(() => expect(success).toHaveBeenCalledWith('找到 1 个项目'));
  await user.click(screen.getByRole('combobox', { name: '托管项目' })); await user.click(await screen.findByText('<b>项目</b> (p1)'));
  await user.click(screen.getByRole('button', { name: /测试连接/ }));
  await waitFor(() => expect(testBody).toMatchObject({ externalProjectId: 'p1', externalProjectName: '<b>项目</b>', enabled: true }));
  await locale('en-US'); await act(async () => { test.resolve({ success: true, message: 'Aone 连接成功', checks: ['project:<b>项目</b>', 'members:1', 'writeback-staff:staff-raw'] }); });
  await waitFor(() => expect(success).toHaveBeenCalledWith('Aone connection succeeded'));
  expect(await within(container).findByText('Aone connection succeeded')).toBeInTheDocument();
  expect(screen.getByText('project:<b>项目</b> / members:1 / writeback-staff:staff-raw')).toBeInTheDocument();
  expect(screen.getByRole('textbox', { name: 'External work item IDs' })).toBe(ids);
  await user.type(ids, ' raw1 , 中文2 ');
  await user.click(screen.getByRole('button', { name: 'Sync selected IDs' }));
  await waitFor(() => expect(syncBody).toEqual({ issueIds: ['raw1', '中文2'] })); await screen.findByText('Sync results: 1 added, 2 updated, 0 comments');
  const calls = listCalls; await locale('zh-CN');
  expect(within(container).getByText('Aone 连接成功')).toBeInTheDocument(); expect(screen.getByText('同步结果：新增 1，更新 2，评论 0')).toBeInTheDocument();
  expect(screen.getByRole('textbox', { name: '外部工单 ID' })).toBe(ids);
  expect(ids).toHaveValue(' raw1 , 中文2 '); expect(secret).toHaveValue('synthetic-secret');
  await locale('en-US'); expect(screen.getByRole('textbox', { name: 'External work item IDs' })).toBe(ids);
  expect(ids).toHaveValue(' raw1 , 中文2 '); expect(listCalls).toBe(calls); expect(container.querySelector('b')).toBeNull();
});

it.each([true, false])('ends writeback mutation loading before the %s-result toast is dismissed', async succeeded => {
  const toast = deferred<boolean>();
  const handle = Object.assign(() => {}, { then: toast.promise.then.bind(toast.promise), promise: toast.promise });
  const feedback = vi.spyOn(message, succeeded ? 'success' : 'error').mockReturnValue(handle);
  server.use(
    http.get('/api/integrations/aone/bindings', () => HttpResponse.json(ok([]))),
    http.post('/api/integrations/aone/outbox/dispatch-now', () => HttpResponse.json(succeeded ? ok(2) : { success: false, code: '10000', message: 'raw writeback failure' })),
  );
  mount(<WorkitemIntegrationPage />);
  const button = await screen.findByRole('button', { name: '处理写回队列' });
  try {
    await localeUser().click(button);
    await waitFor(() => expect(feedback).toHaveBeenCalled());
    await waitFor(() => expect(button).not.toHaveClass('ant-btn-loading'));
    expect(feedback).toHaveBeenCalledWith(succeeded ? '已处理 2 条写回任务' : 'raw writeback failure');
  } finally {
    await act(async () => { toast.resolve(true); });
  }
});

it('keeps a false Aone result and protocol checks raw even when they match owned wording', async () => {
  server.use(http.get('/api/integrations/aone/bindings', () => HttpResponse.json(ok([]))),
    http.post('/api/integrations/aone/bindings/test', () => HttpResponse.json(ok({ success: false, message: 'Aone 连接成功', checks: ['workitem-search:ok'] }))),
    http.post('/api/integrations/aone/projects/search', () => HttpResponse.json(ok({ items: [{ externalId: 'p', name: '平台配置' }] }))));
  mount(<WorkitemIntegrationPage />); const user = localeUser();
  await user.type(await screen.findByLabelText('Base URL'), 'https://aone.example.test'); await user.type(screen.getByLabelText('Access Secret'), 'fake'); await user.type(screen.getByLabelText('写回身份 StaffId'), 'staff');
  await user.click(screen.getByRole('button', { name: '搜索可托管项目' }));
  await user.click(screen.getByRole('combobox', { name: '托管项目' })); await user.click(await screen.findByText('平台配置 (p)'));
  await user.click(screen.getByRole('button', { name: /测试连接/ })); await screen.findAllByText('Aone 连接成功'); await locale('en-US');
  expect(screen.getAllByText('Aone 连接成功').length).toBeGreaterThan(0); expect(screen.getByText('workitem-search:ok')).toBeInTheDocument();
});

it.each(['FEISHU', 'SLACK', 'constructor'])('updates independently mounted channel %s with stable selection and request count', async provider => {
  let calls = 0;
  server.use(http.get('/api/platform/im-channels', () => { calls++; return HttpResponse.json(ok([{ provider, selected: true, enabled: false }])); }),
    http.get('/api/integrations/feishu/bindings', () => HttpResponse.json(ok([]))));
  const { router } = mount(provider === 'FEISHU' ? <IntegrationsHubPage /> : <ChannelIntegrationPage />, '/integrations?tab=channels&keep=raw');
  await screen.findByText(provider === 'FEISHU' ? '飞书机器人绑定' : '该渠道尚未接入'); await locale('en-US');
  expect(screen.getByText(provider === 'FEISHU' ? 'Feishu bot bindings' : 'This channel is not available yet')).toBeInTheDocument();
  expect(CHANNEL_REGISTRY.find(c => c.key === 'SLACK')?.label).toBe('Slack (coming soon)');
  await locale('zh-CN'); expect(calls).toBe(1); expect(router.state.location.search).toBe('?tab=channels&keep=raw');
});

const ding = { id: 3, appKey: 'dingraw', robotCode: 'robot_raw', agentId: 7, transportMode: 'STREAM', streamEnv: 'ONLINE', status: 'ENABLED', callbackUrl: 'https://example.test/raw?token=fake', baseUrl: 'https://gateway.test', regionId: '9' } as DingTalkBinding;
it('keeps an independently mounted DingTalk edit drawer, errors and advanced/secret drafts across switching and sends canonical values', async () => {
  const user = localeUser(); const save = deferred<unknown>(); const success = vi.spyOn(message, 'success'); let body: unknown;
  server.use(http.put('/api/integrations/dingtalk/bindings/3', async ({ request }) => { body = await request.json(); return HttpResponse.json(ok(await save.promise)); }));
  mount(<DingTalkBindingDrawer open mode="edit" record={ding} onClose={() => {}} onSaved={() => {}} />);
  const key = await screen.findByLabelText('appKey'); const secret = screen.getByLabelText('appSecret（留空=不修改）');
  await user.clear(key); await user.click(screen.getByRole('button', { name: /保\s*存/ })); await screen.findByText('请填写 appKey');
  await user.type(secret, 'replacement'); await user.click(screen.getByText('高级设置（传输方式 / 网关地址 / regionId）'));
  const callback = screen.getByLabelText('回调 token callbackToken'); await user.type(callback, 'callback-draft'); await locale('en-US');
  expect(screen.getByRole('dialog', { name: 'Edit binding' })).toBeInTheDocument(); expect(screen.getByRole('button', { name: 'Close' })).toBeInTheDocument();
  expect(await screen.findByText('Enter appKey')).toBeInTheDocument(); expect(screen.getByLabelText('appKey')).toBe(key);
  expect(secret).toHaveValue('replacement'); expect(callback).toHaveValue('callback-draft'); expect(screen.queryByText('Enter robotCode')).not.toBeInTheDocument();
  const eye = screen.getByRole('button', { name: 'Show secret' }); eye.focus(); await user.keyboard('{Enter}'); expect(secret).toHaveAttribute('type', 'text');
  await locale('zh-CN'); expect(screen.getByRole('button', { name: '隐藏密钥' })).toBeInTheDocument(); expect(secret).toHaveValue('replacement');
  await user.type(key, 'dingnew'); await user.click(screen.getByRole('button', { name: /保\s*存/ }));
  await waitFor(() => expect(body).toMatchObject({ appKey: 'dingnew', appSecret: 'replacement', transportMode: 'STREAM', streamEnv: 'ONLINE', callbackToken: 'callback-draft', baseUrl: 'https://gateway.test', regionId: '9', agentId: 7 }));
  await locale('en-US'); await act(async () => { save.resolve(ding); }); await waitFor(() => expect(success).toHaveBeenCalledWith('Binding saved'));
  expect(await screen.findByRole('button', { name: 'Copy callback URL' })).toBeInTheDocument();
});

it('preserves Feishu edit encryption choice, blank secrets, disabled app ID and version across locale changes', async () => {
  const user = localeUser(); let body!: FeishuBindingRequest; const saved = deferred<unknown>(); const success = vi.spyOn(message, 'success');
  const binding = { id: 4, appId: 'cli_raw', agentId: 7, version: 5, status: 'ENABLED', encryptKeyConfigured: true, callbackUrl: '/callback?raw=1', lastError: '<em>原始错误</em>', lastSuccessAt: '2026-08-06T14:43:07Z' };
  server.use(http.get('/api/integrations/feishu/bindings', () => HttpResponse.json(ok([binding]))),
    http.put('/api/integrations/feishu/bindings/4', async ({ request }) => { body = await request.json() as FeishuBindingRequest; return HttpResponse.json(ok(await saved.promise)); }));
  mount(<FeishuBindingPanel />); await user.click(await screen.findByRole('button', { name: /编\s*辑/ }));
  const appId = screen.getByLabelText('App ID'); const secret = screen.getByLabelText('App Secret');
  await user.click(screen.getByRole('checkbox', { name: /清除 Encrypt Key/ })); await locale('en-US');
  expect(screen.getByRole('dialog', { name: 'Edit Feishu binding' })).toBeInTheDocument(); expect(screen.getByRole('button', { name: 'Close' })).toBeInTheDocument();
  expect(screen.getByLabelText('App ID')).toBe(appId); expect(appId).toBeDisabled(); expect(secret).toHaveValue('');
  expect(screen.getByRole('checkbox', { name: /Clear Encrypt Key/ })).toBeChecked();
  expect(await screen.findByRole('button', { name: 'Copy callback URL' })).toBeInTheDocument();
  await locale('zh-CN'); await user.click(screen.getByRole('button', { name: /保\s*存/ }));
  await waitFor(() => expect(body).toMatchObject({ version: 5, appId: 'cli_raw', agentId: 7, status: 'ENABLED', clearEncryptKey: true }));
  expect(body.appSecret).toBeUndefined(); expect(body.verificationToken).toBeUndefined(); await locale('en-US'); await act(async () => { saved.resolve(binding); });
  await waitFor(() => expect(success).toHaveBeenCalledWith('Feishu binding saved. Configure the callback URL and publish the Feishu app.'));
});

it('refreshes visible Feishu create validation while keeping password visibility and pristine optional fields', async () => {
  const user = localeUser(); server.use(http.get('/api/integrations/feishu/bindings', () => HttpResponse.json(ok([]))));
  mount(<FeishuBindingPanel />); await user.click(await screen.findByRole('button', { name: '新建飞书绑定' }));
  await user.type(screen.getByLabelText('App ID'), 'invalid'); await user.click(screen.getByRole('button', { name: /保\s*存/ })); await screen.findByText('请输入有效的 App ID（cli_ 开头）');
  const secret = screen.getByLabelText('App Secret'); await user.type(secret, 'secret-draft'); await locale('en-US');
  expect(await screen.findByText('Enter a valid App ID starting with cli_')).toBeInTheDocument(); expect(screen.getByLabelText('App Secret')).toBe(secret); expect(secret).toHaveValue('secret-draft');
  const drawer = within(screen.getByRole('dialog', { name: 'New Feishu binding' })); const toggle = drawer.getAllByRole('button', { name: 'Show secret' })[0];
  toggle.focus(); await user.keyboard(' '); expect(secret).toHaveAttribute('type', 'text'); await locale('zh-CN'); expect(secret).toHaveAttribute('type', 'text');
  expect(drawer.getByRole('button', { name: '隐藏密钥' })).toBeInTheDocument();
});

it('preserves unknown binding/transport codes instead of guessing localized meanings', async () => {
  server.use(http.get('/api/integrations/dingtalk/bindings', () => HttpResponse.json(ok([{ ...ding, transportMode: 'constructor', agentId: 8 }, { ...ding, id: 9, robotCode: 'health_raw', streamStatus: 'toString' }]))));
  const view = mount(<DingTalkBindingPanel />); await screen.findByText('robot_raw'); await locale('en-US');
  expect(screen.getByText('constructor')).toBeInTheDocument(); expect(screen.getByText('toString')).toBeInTheDocument(); expect(screen.getByText('新建绑定 (<b>用户角色</b>)')).toBeInTheDocument();
  view.unmount();
  server.use(http.get('/api/integrations/feishu/bindings', () => HttpResponse.json(ok([{ id: 2, appId: 'cli_raw', agentId: 8, status: 'constructor' }]))));
  mount(<FeishuBindingPanel />); await screen.findByText('cli_raw'); expect(screen.getByText('constructor')).toBeInTheDocument();
});

it('keeps Aone polling keyboard stepping, bounds and the exact value submitted without untranslated pointer controls', async () => {
  const user = localeUser(); let body: unknown;
  server.use(http.get('/api/integrations/aone/bindings', () => HttpResponse.json(ok([]))),
    http.post('/api/integrations/aone/projects/search', () => HttpResponse.json(ok({ items: [{ externalId: 'p', name: '平台配置' }] }))),
    http.post('/api/integrations/aone/bindings', async ({ request }) => { body = await request.json(); return HttpResponse.json(ok({ id: '1' })); }));
  mount(<WorkitemIntegrationPage />); const polling = await screen.findByRole('spinbutton', { name: '轮询秒数' });
  expect(screen.queryByRole('button', { name: 'Increase Value' })).not.toBeInTheDocument();
  polling.focus(); await user.keyboard('{ArrowDown}'); expect(polling).toHaveValue('3');
  await user.keyboard('{ArrowUp}'); expect(polling).toHaveValue('4');
  await locale('en-US'); expect(screen.getByRole('spinbutton', { name: 'Polling interval (seconds)' })).toBe(polling);
  await user.clear(polling); await user.type(polling, '3600'); await user.keyboard('{ArrowUp}'); expect(polling).toHaveValue('3600');
  await user.clear(polling); await user.type(polling, '15');
  await user.type(await screen.findByLabelText('Base URL'), 'https://aone.example.test'); await user.type(screen.getByLabelText('Access Secret'), 'synthetic'); await user.type(screen.getByLabelText('Writeback StaffId'), 'staff');
  await user.click(screen.getByRole('button', { name: 'Search manageable projects' }));
  await user.click(screen.getByRole('combobox', { name: 'Managed projects' })); await user.click(await screen.findByText('平台配置 (p)'));
  await user.click(screen.getByRole('button', { name: 'Save managed projects' }));
  await waitFor(() => expect(body).toMatchObject({ pollIntervalSeconds: 15, enabled: true, provider: 'AONE', regionId: '1', externalProjectId: 'p', externalProjectName: '平台配置' }));
});

it('uses current locale for Aone zero/one/many counts, mixed reused saves and deferred writeback errors without altering raw requests', async () => {
  const user = localeUser(); const success = vi.spyOn(message, 'success'); const error = vi.spyOn(message, 'error');
  const saveGate = deferred<void>(); let projectCount = 0; let outboxCount = 0; let failOutbox = false;
  const outboxGate = deferred<void>(); const saves: AoneBindingRequest[] = []; let outboxStarted = false;
  server.use(http.get('/api/integrations/aone/bindings', () => HttpResponse.json(ok([]))),
    http.post('/api/integrations/aone/projects/search', () => HttpResponse.json(ok({ items: Array.from({ length: projectCount }, (_, id) => ({ externalId: `p${id}`, name: `原始项目${id}`, rawJson: '原始JSON' })) }))),
    http.post('/api/integrations/aone/bindings', async ({ request }) => { const body = await request.json() as AoneBindingRequest; saves.push(body); await saveGate.promise; return HttpResponse.json(ok({ id: body.externalProjectId, reusedExistingBinding: body.externalProjectId === 'p1' })); }),
    http.post('/api/integrations/aone/outbox/dispatch-now', async () => { if (failOutbox) { outboxStarted = true; await outboxGate.promise; return HttpResponse.json({ success: false, code: '10000', message: '<b>原始 SDK 错误</b>' }); } return HttpResponse.json(ok(outboxCount)); }));
  mount(<WorkitemIntegrationPage />); await locale('en-US');
  await user.type(await screen.findByLabelText('Base URL'), 'https://aone.example.test'); await user.type(screen.getByLabelText('Access Secret'), 'synthetic'); await user.type(screen.getByLabelText('Writeback StaffId'), 'staff');
  for (const count of [0, 1, 2]) {
    projectCount = count;
    await waitFor(() => expect(screen.getByRole('button', { name: 'Search manageable projects' })).not.toHaveClass('ant-btn-loading'));
    await user.click(screen.getByRole('button', { name: 'Search manageable projects' }));
    await waitFor(() => expect(success).toHaveBeenCalledWith(`Found ${count} ${count === 1 ? 'project' : 'projects'}`));
    outboxCount = count;
    await waitFor(() => expect(screen.getByRole('button', { name: 'Process writeback queue' })).not.toHaveClass('ant-btn-loading'), { timeout: 5000 });
    await user.click(screen.getByRole('button', { name: 'Process writeback queue' }));
    await waitFor(() => expect(success).toHaveBeenCalledWith(`Processed ${count} writeback ${count === 1 ? 'task' : 'tasks'}`));
  }
  await user.click(screen.getByRole('combobox', { name: 'Managed projects' }));
  await user.click(await screen.findByText('原始项目0 (p0)')); await user.click(await screen.findByText('原始项目1 (p1)'));
  await user.click(screen.getByRole('button', { name: 'Save managed projects' })); await waitFor(() => expect(saves).toHaveLength(1));
  await locale('zh-CN'); await act(async () => { saveGate.resolve(); });
  await waitFor(() => expect(success).toHaveBeenCalledWith('已处理 2 个托管项目：新建 1 个，复用已有 1 个。'));
  expect(saves.map(body => [body.externalProjectId, body.externalProjectName])).toEqual([['p0', '原始项目0'], ['p1', '原始项目1']]);
  failOutbox = true;
  await waitFor(() => expect(screen.getByRole('button', { name: '处理写回队列' })).not.toHaveClass('ant-btn-loading'), { timeout: 5000 });
  await user.click(screen.getByRole('button', { name: '处理写回队列' })); await waitFor(() => expect(outboxStarted).toBe(true));
  await locale('en-US'); await act(async () => { outboxGate.resolve(); });
  await waitFor(() => expect(error).toHaveBeenCalledWith('<b>原始 SDK 错误</b>'));
}, 20000);

it('keeps DingTalk toggle/edit/delete secrets omitted and uses completion locale while health details stay raw', async () => {
  const user = localeUser(); const success = vi.spyOn(message, 'success'); const toggle = deferred<unknown>(); const remove = deferred<void>();
  const bodies: DingTalkBindingRequest[] = []; let deletes = 0;
  const binding = { ...ding, streamStatus: 'FAILED', streamError: '<b>原始钉钉错误</b>' };
  server.use(http.get('/api/integrations/dingtalk/bindings', () => HttpResponse.json(ok([binding]))),
    http.put('/api/integrations/dingtalk/bindings/3', async ({ request }) => { const body = await request.json() as DingTalkBindingRequest; bodies.push(body); return HttpResponse.json(ok(bodies.length === 1 ? await toggle.promise : binding)); }),
    http.delete('/api/integrations/dingtalk/bindings/3', async () => { deletes++; await remove.promise; return HttpResponse.json(ok(null)); }));
  mount(<DingTalkBindingPanel />); await screen.findByText('连接失败');
  await user.hover(screen.getByText('连接失败')); expect(await screen.findByText('<b>原始钉钉错误</b>')).toBeInTheDocument();
  await user.click(screen.getByRole('switch', { name: '切换钉钉绑定状态' })); await waitFor(() => expect(bodies).toHaveLength(1));
  await locale('en-US'); await act(async () => { toggle.resolve(binding); });
  await waitFor(() => expect(success).toHaveBeenCalledWith('Status updated'));
  expect(bodies[0]).toMatchObject({ status: 'DISABLED', transportMode: 'STREAM', streamEnv: 'ONLINE' }); expect(bodies[0].appSecret).toBeUndefined();
  await user.click(screen.getByRole('button', { name: 'Edit' })); const secret = screen.getByLabelText('appSecret (leave blank to keep existing)'); expect(secret).toHaveValue('');
  await locale('zh-CN'); await user.click(screen.getByRole('button', { name: /保\s*存/ })); await waitFor(() => expect(bodies).toHaveLength(2)); expect(bodies[1].appSecret).toBeUndefined();
  await user.click(screen.getByRole('button', { name: '关闭' })); await user.click(screen.getByRole('button', { name: /删\s*除/ }));
  await locale('en-US'); expect(screen.getByText('Delete this binding?')).toBeInTheDocument(); expect(deletes).toBe(0);
  await user.click(screen.getAllByRole('button', { name: 'Delete' })[1]); await waitFor(() => expect(deletes).toBe(1));
  await locale('zh-CN'); await act(async () => { remove.resolve(); }); await waitFor(() => expect(success).toHaveBeenCalledWith('绑定已删除'));
});

it('copies the exact raw Feishu callback URL and updates the actual copy and copied buttons in both locales', async () => {
  const user = localeUser(); let copied: string | undefined;
  const original = Object.getOwnPropertyDescriptor(document, 'execCommand');
  Object.defineProperty(document, 'execCommand', { configurable: true, value: vi.fn(() => { copied = window.getSelection()?.toString(); return true; }) });
  try {
    server.use(http.get('/api/integrations/feishu/bindings', () => HttpResponse.json(ok([{ id: 4, appId: 'cli_raw', agentId: 7, status: 'ENABLED', callbackUrl: '/callback?原始=1', version: 3 }]))));
    mount(<FeishuBindingPanel />); await user.click(await screen.findByRole('button', { name: /编\s*辑/ }));
    expect(screen.getByRole('button', { name: '复制回调地址' })).toBeInTheDocument(); await locale('en-US');
    await user.click(screen.getByRole('button', { name: 'Copy callback URL' }));
    expect(copied).toBe(`${window.location.origin}/callback?原始=1`);
    expect(await screen.findByRole('button', { name: 'Copied' })).toBeInTheDocument();
    await locale('zh-CN'); expect(screen.getByRole('button', { name: '已复制' })).toBeInTheDocument();
  } finally { if (original) Object.defineProperty(document, 'execCommand', original); else Reflect.deleteProperty(document, 'execCommand'); }
});

it.each([['', 'Delete failed'], ['保存失败', '保存失败']])('keeps deferred Feishu deletion detail %j raw or uses a current-locale empty-detail fallback', async (detail, expected) => {
  const user = localeUser(); const error = vi.spyOn(message, 'error'); const remove = deferred<void>(); let started = false;
  server.use(http.get('/api/integrations/feishu/bindings', () => HttpResponse.json(ok([{ id: 4, appId: 'cli_raw', agentId: 7, status: 'ENABLED' }]))),
    http.delete('/api/integrations/feishu/bindings/4', async () => { started = true; await remove.promise; return HttpResponse.json({ success: false, code: '10000', message: detail }); }));
  mount(<FeishuBindingPanel />); await user.click(await screen.findByRole('button', { name: /删\s*除/ }));
  await user.click(screen.getAllByRole('button', { name: /删\s*除/ })[1]); await waitFor(() => expect(started).toBe(true));
  await locale('en-US'); await act(async () => { remove.resolve(); }); await waitFor(() => expect(error).toHaveBeenCalledWith(expected));
});

it.each([0, 1, 2])('renders a held Aone sync count %i in natural English and Chinese without another request', async count => {
  const user = localeUser(); const success = vi.spyOn(message, 'success'); const sync = deferred<void>(); let syncCalls = 0;
  server.use(http.get('/api/integrations/aone/bindings', () => HttpResponse.json(ok([{ id: '17', enabled: true }]))),
    http.post('/api/integrations/aone/bindings/17/sync-now', async () => { syncCalls++; await sync.promise; return HttpResponse.json(ok({ imported: 1, updated: 0, commentsImported: count, workitemIds: ['原始ID'] })); }));
  const { container } = mount(<WorkitemIntegrationPage />);
  await waitFor(() => expect(screen.getByRole('button', { name: '同步选中 ID' })).toBeEnabled());
  await user.click(screen.getByRole('button', { name: '同步选中 ID' }));
  await waitFor(() => expect(syncCalls).toBe(1)); await locale('en-US'); await act(async () => { sync.resolve(); });
  const noun = count === 1 ? 'comment' : 'comments';
  expect(await within(container).findByText(`Sync results: 1 added, 0 updated, ${count} ${noun}`)).toBeInTheDocument();
  expect(success).toHaveBeenCalledWith(`Sync complete: 1 added, 0 updated, ${count} ${noun}`);
  await locale('zh-CN'); expect(within(container).getByText(`同步结果：新增 1，更新 0，评论 ${count}`)).toBeInTheDocument();
  await locale('en-US'); expect(syncCalls).toBe(1); expect(within(container).getByText('Local work item IDs: 原始ID')).toBeInTheDocument();
});
