import { beforeEach, afterEach, it, expect, vi } from 'vitest';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { message } from 'antd';
import { server } from '@/test/mocks/server';
import { useAuthStore } from '@/shared/auth/store';
import { i18n } from '@/shared/i18n';
import { formatDateTime } from '@/shared/i18n/format';
import { AgentListPage } from './AgentListPage';
import { AgentDetailPage } from './AgentDetailPage';
import { AgentReviewPage } from './AgentReviewPage';
import { AgentsHubPage } from './AgentsHubPage';
import { AgentStatCards } from './components/AgentStatCards';

const agent = { id: 1, name: '<em>员工</em>', avatarUrl: null, status: 'ONLINE', onlineVersionId: 10,
  editingVersionId: 11, latestVersionNo: 2, version: 1, gmtCreate: '2026-07-01T08:00:00Z', roleName: '测试工程师', roleCode: 'QA',
  evolutionMode: 'ASSISTED', hasDraft: true, draftVersionNo: 3, executorOnlineCount: 1, executorTotalCount: 2,
  environmentVariables: [{ id: 4, name: '<em>变量</em>', description: null }] };
const version = { id: 10, agentId: 1, versionNo: 2, status: 'PENDING_REVIEW', roleName: '测试工程师', roleCode: 'QA',
  businessBackground: '<strong>业务原文</strong>', responsibilities: '用户职责', sdlcId: null,
  identityJson: null, evolutionMode: 'ASSISTED', reviewerId: null, reviewComment: null, reviewedAt: null,
  version: 1, gmtCreate: '2026-07-01', repoPerms: [{ repoId: 3, repoName: '<em>仓库</em>', permLevel: 'READ', allowedBranchPatterns: [] }],
  skills: [{ skillId: 4, skillName: '<em>能力</em>' }] };
const historicalVersions = [
  { id: 20, versionNo: 1, status: 'APPROVED', roleName: '后端开发工程师', roleCode: 'BACKEND_DEV', gmtCreate: '2026-07-01T08:00:00Z' },
  { id: 21, versionNo: 2, status: 'REJECTED', roleName: '<em>旧角色</em>', gmtCreate: 'bad-date' },
  { id: 22, versionNo: 3, status: 'constructor', roleName: '<b>自定义</b>', roleCode: 'constructor', gmtCreate: '2026-07-01' },
  { id: 23, versionNo: 4, status: 'REJECTED', roleName: '后端开发工程师', roleCode: null, gmtCreate: '2026-07-01' },
];
const ok = (data: unknown) => HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data });
function renderRoute(path: string) {
  const router = createMemoryRouter([
    { path: '/agents', element: <AgentsHubPage /> },
    { path: '/list', element: <AgentListPage /> },
    { path: '/reviews', element: <AgentReviewPage /> },
    { path: '/agents/:id', element: <AgentDetailPage /> },
  ], { initialEntries: [path] });
  return render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
    <RouterProvider router={router} />
  </QueryClientProvider>);
}
async function language(locale: 'en-US' | 'zh-CN') { await act(async () => { await i18n.changeLanguage(locale); }); }
beforeEach(async () => {
  await i18n.changeLanguage('zh-CN');
  useAuthStore.getState().clear();
  useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_WRITE');
  window.localStorage.removeItem('autowonder.agents.view');
  server.use(http.get('/api/agents', () => ok([agent])), http.get('/api/agents/reviews/count', () => ok(1)),
    http.get('/api/agents/1', () => ok(agent)), http.get('/api/agents/1/versions/2', () => ok(version)),
    http.get('/api/agents/1/versions/3', () => ok({ ...version, roleName: '后端开发工程师', roleCode: 'BACKEND_DEV', evolutionMode: 'MANUAL' })),
    http.get('/api/agents/1/versions', () => ok(historicalVersions)),
    http.get('/api/agents/1/memories', () => ok([])),
    http.get('/api/workitems', () => ok({ list: [], total: 0, pageNum: 1, pageSize: 100 })),
    http.get('/api/squads', () => ok({ list: [], total: 0, pageNum: 1, pageSize: 100 })));
});
afterEach(async () => { vi.restoreAllMocks(); await i18n.changeLanguage('zh-CN'); });

it('switches cached hub and independent cards, keeps authored names literal and list filters stable', async () => {
  const user = userEvent.setup();
  const { container } = renderRoute('/agents');
  await screen.findByText('<em>员工</em>');
  await language('en-US');
  expect(screen.getByRole('heading', { name: 'Agent management' })).toBeInTheDocument();
  expect(screen.getByRole('tab', { name: 'Agents' })).toBeInTheDocument();
  expect(screen.getByRole('tab', { name: /Version reviews/ })).toBeInTheDocument();
  expect(screen.getByRole('tab', { name: 'Squads' })).toBeInTheDocument();
  expect(screen.getByText('QA engineer')).toBeInTheDocument();
  expect(screen.getByText('1/2 online')).toBeInTheDocument();
  expect(screen.getByText(formatDateTime(agent.gmtCreate, { dateStyle: 'medium' }))).toBeInTheDocument();
  expect(screen.getByRole('link', { name: 'View details and configuration for <em>员工</em>' })).toHaveAttribute('href', '/agents/1');
  expect(container.querySelector('em')).toBeNull();
  await language('zh-CN');
  expect(screen.getByRole('heading', { name: '数字员工管理' })).toBeInTheDocument();
  expect(screen.getByText('测试工程师')).toBeInTheDocument();
  await user.click(screen.getByText('列表'));
  await language('en-US');
  expect(screen.getByRole('columnheader', { name: 'Role' })).toBeInTheDocument();
  expect(screen.getByRole('combobox', { name: 'Filter by status' })).toBeInTheDocument();
  expect(screen.getByText('QA engineer')).toBeInTheDocument();
  await user.click(screen.getByText('Platform agents'));
  expect(screen.queryByRole('button', { name: /Create/ })).not.toBeInTheDocument();
});

it('switches detail configuration, draft roles and historical row codes with missing/unknown raw fallbacks', async () => {
  const user = userEvent.setup();
  let historyRequests = 0;
  server.use(http.get('/api/agents/1/versions', () => { historyRequests += 1; return ok(historicalVersions); }));
  const { container } = renderRoute('/agents/1');
  await screen.findByText('<em>员工</em>');
  await language('en-US');
  expect(screen.getByRole('button', { name: 'Edit configuration' })).toBeInTheDocument();
  expect(screen.getByText('QA engineer')).toBeInTheDocument();
  expect(screen.getByText('Evolution mode: Assisted review (recommended)')).toBeInTheDocument();
  expect(screen.getAllByText('Not configured')).toHaveLength(2);
  await user.click(screen.getByRole('button', { name: 'View draft differences' }));
  expect(await screen.findByText('Backend developer')).toBeInTheDocument();
  expect(screen.getByText('Manual')).toBeInTheDocument();
  await user.click(screen.getByRole('tab', { name: 'Repository permissions (1)' }));
  expect(await screen.findByText('Read only')).toBeInTheDocument();
  expect(screen.getByText('Unrestricted')).toBeInTheDocument();
  expect(screen.getByText('<em>仓库</em>')).toBeInTheDocument();
  await user.click(screen.getByRole('tab', { name: 'Capabilities (1)' }));
  expect(await screen.findByText('<em>能力</em>')).toBeInTheDocument();
  await user.click(screen.getByRole('tab', { name: 'Environment variables (1)' }));
  expect(await screen.findByText('<em>变量</em>')).toBeInTheDocument();
  expect(screen.getByText('No description')).toBeInTheDocument();
  await user.click(screen.getByRole('tab', { name: 'Version history' }));
  const table = screen.getAllByRole('table').at(-1)!;
  expect(within(table).getByText('Backend developer')).toBeInTheDocument();
  expect(within(table).getByText('后端开发工程师')).toBeInTheDocument();
  expect(within(table).getByText('<em>旧角色</em>')).toBeInTheDocument();
  expect(within(table).getByText('<b>自定义</b>')).toBeInTheDocument();
  expect(within(table).getAllByText('Rejected')).toHaveLength(2);
  expect(within(table).getByText('constructor')).toBeInTheDocument();
  expect(within(table).getByText('—')).toBeInTheDocument();
  expect(container.querySelector('em')).toBeNull();
  await language('zh-CN');
  expect(within(table).getAllByText('后端开发工程师')).toHaveLength(2);
  expect(screen.getByText('自进化模式：辅助审核（推荐）')).toBeInTheDocument();
  await language('en-US');
  expect(within(table).getByText('Backend developer')).toBeInTheDocument();
  expect(within(table).getByText('后端开发工程师')).toBeInTheDocument();
  expect(within(table).getByText('<em>旧角色</em>')).toBeInTheDocument();
  expect(within(table).getByText('<b>自定义</b>')).toBeInTheDocument();
  expect(historyRequests).toBe(1);
});

it('uses the completion locale for rollback and retains reactive feedback', async () => {
  const user = userEvent.setup();
  let release!: () => void;
  let submitted: unknown;
  const pending = new Promise<void>(resolve => { release = resolve; });
  server.use(http.post('/api/agents/1/rollback', async ({ request }) => { submitted = await request.json(); await pending; return ok(agent); }));
  const success = vi.spyOn(message, 'success').mockImplementation(() => undefined as never);
  renderRoute('/agents/1');
  await screen.findByText('<em>员工</em>');
  await user.click(screen.getByRole('tab', { name: '版本记录' }));
  await user.click(screen.getByRole('button', { name: /回退$/ }));
  await user.click(await screen.findByRole('button', { name: /确定回退/ }));
  await waitFor(() => expect(submitted).toEqual({ versionNo: 1 }));
  await language('en-US');
  await act(async () => { release(); });
  expect(await screen.findByText('Rolled back to v1')).toBeInTheDocument();
  expect(success).toHaveBeenCalledWith('Rolled back');
  await language('zh-CN');
  expect(screen.getByText('已回退到 v1')).toBeInTheDocument();
});

it('switches preview children and rejection drafts, uses completion locale and literal authored text', async () => {
  const user = userEvent.setup();
  let release!: () => void;
  let submitted: unknown;
  const pending = new Promise<void>(resolve => { release = resolve; });
  server.use(http.post('/api/agents/1/reject', async ({ request }) => { submitted = await request.json(); await pending; return ok(agent); }));
  const success = vi.spyOn(message, 'success').mockImplementation(() => undefined as never);
  const { container } = renderRoute('/reviews');
  await screen.findByText('<em>员工</em>');
  await user.click(screen.getByRole('button', { name: /预览/ }));
  await screen.findByText('测试工程师');
  await language('en-US');
  const preview = screen.getByRole('dialog', { name: 'Configuration preview — <em>员工</em>' });
  expect(within(preview).getByText('QA engineer')).toBeInTheDocument();
  expect(within(preview).getByText('Pending review')).toBeInTheDocument();
  expect(within(preview).getByText('<strong>业务原文</strong>')).toBeInTheDocument();
  await language('zh-CN');
  expect(within(preview).getByRole('button', { name: '关闭' })).toBeInTheDocument();
  await language('en-US');
  await user.click(within(preview).getByRole('button', { name: 'Close' }));
  await user.click(screen.getByRole('button', { name: 'Reject' }));
  const input = screen.getByPlaceholderText('Enter a reason for rejection...');
  await user.type(input, '<b>驳回原文</b>');
  await language('zh-CN');
  expect(screen.getByPlaceholderText('请输入驳回原因...')).toBe(input);
  expect(input).toHaveValue('<b>驳回原文</b>');
  await user.click(screen.getByRole('button', { name: /确认驳回/ }));
  await waitFor(() => expect(submitted).toEqual({ comment: '<b>驳回原文</b>' }));
  await language('en-US');
  await act(async () => { release(); });
  expect(await screen.findByText('<em>员工</em> was rejected')).toBeInTheDocument();
  expect(success).toHaveBeenCalledWith('<em>员工</em> was rejected');
  expect(container.querySelector('em')).toBeNull();
  await language('zh-CN');
  expect(screen.getByText('<em>员工</em> 已驳回')).toBeInTheDocument();
});

it('updates stat cards independently when only the singleton locale changes', async () => {
  render(<AgentStatCards workitems={[]} memoryCount={5} />);
  expect(screen.getByText('记忆数')).toBeInTheDocument();
  await language('en-US');
  expect(screen.getByText('Memories')).toBeInTheDocument();
  expect(screen.getByText('In progress')).toBeInTheDocument();
  expect(screen.getByText('Pending decision')).toBeInTheDocument();
  expect(screen.getByText('Done')).toBeInTheDocument();
});

it('uses completion locale for approval and errors, refreshes retained approval feedback', async () => {
  const user = userEvent.setup();
  let release!: () => void;
  let requested = false;
  const pending = new Promise<void>(resolve => { release = resolve; });
  server.use(http.post('/api/agents/1/approve', async () => { requested = true; await pending; return ok(agent); }));
  const success = vi.spyOn(message, 'success').mockImplementation(() => undefined as never);
  renderRoute('/reviews');
  await screen.findByText('<em>员工</em>');
  await user.click(screen.getByRole('button', { name: '通过' }));
  await waitFor(() => expect(requested).toBe(true));
  await language('en-US');
  await act(async () => { release(); });
  expect(await screen.findByText('<em>员工</em> was approved')).toBeInTheDocument();
  expect(success).toHaveBeenCalledWith('<em>员工</em> was approved');
  await language('zh-CN');
  expect(screen.getByText('<em>员工</em> 已通过审核')).toBeInTheDocument();
  let fail!: () => void;
  requested = false;
  const failing = new Promise<void>(resolve => { fail = resolve; });
  server.use(http.post('/api/agents/1/approve', async () => {
    requested = true; await failing;
    return HttpResponse.json({ success: false, code: '14005', message: '', data: null }, { status: 400 });
  }));
  const error = vi.spyOn(message, 'error').mockImplementation(() => undefined as never);
  await user.click(screen.getByRole('button', { name: '通过' }));
  await waitFor(() => expect(requested).toBe(true));
  await language('en-US');
  await act(async () => { fail(); });
  await waitFor(() => expect(error).toHaveBeenCalledWith('Could not approve the version. Refresh and try again.'));
});

it('switches open online/delete confirmations without changing authored data', async () => {
  const user = userEvent.setup();
  server.use(http.get('/api/agents/1', () => ok({ ...agent, status: 'OFFLINE', hasDraft: false })));
  renderRoute('/agents/1');
  await screen.findByText('<em>员工</em>');
  await user.click(screen.getByRole('button', { name: '上线' }));
  await screen.findByText('确定重新上线该数字员工？');
  await language('en-US');
  expect(screen.getByText('Bring this agent online again?')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Confirm online' })).toBeInTheDocument();
  await user.click(screen.getByRole('button', { name: 'Cancel' }));
  await user.click(screen.getByRole('button', { name: 'Delete' }));
  expect(await screen.findByText('Delete this agent? This cannot be undone.')).toBeInTheDocument();
  await language('zh-CN');
  expect(screen.getByRole('button', { name: '确定删除' })).toBeInTheDocument();
  expect(screen.getByText('确定删除该数字员工？删除后不可恢复。')).toBeInTheDocument();
});

it('switches invalid-id and missing-error fallback UI on cached routes', async () => {
  renderRoute('/agents/abc');
  expect(screen.getByText('无效的 ID')).toBeInTheDocument();
  await language('en-US');
  expect(screen.getByText('Invalid ID')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Back' })).toBeInTheDocument();
});

it('retains canonical status filters and translates fresh options while preserving unknown roles', async () => {
  const user = userEvent.setup();
  const statuses: (string | null)[] = [];
  window.localStorage.setItem('autowonder.agents.view', 'list');
  server.use(http.get('/api/agents', ({ request }) => {
    statuses.push(new URL(request.url).searchParams.get('status'));
    return ok([{ ...agent, roleCode: 'constructor', roleName: '<b>用户角色</b>' }]);
  }));
  const { container } = renderRoute('/list');
  await screen.findByText('<em>员工</em>');
  const filter = screen.getByRole('combobox', { name: '按状态筛选' });
  await user.click(filter);
  await user.click(screen.getByText('未启用', { selector: '.ant-select-item-option-content' }));
  await waitFor(() => expect(statuses).toContain('OFFLINE'));
  await language('en-US');
  expect(screen.getByRole('combobox', { name: 'Filter by status' })).toBe(filter);
  expect(screen.getByText('Not enabled', { selector: '.ant-select-selection-item' })).toBeInTheDocument();
  expect(screen.getByText('<b>用户角色</b>')).toBeInTheDocument();
  expect(container.querySelector('b')).toBeNull();
  await user.click(filter);
  expect(screen.getByText('Pending review', { selector: '.ant-select-item-option-content' })).toBeInTheDocument();
  await language('zh-CN');
  expect(screen.getByText('未启用', { selector: '.ant-select-selection-item' })).toBeInTheDocument();
  expect(statuses.every(code => code === null || code === 'OFFLINE')).toBe(true);
});

it.each([['/list', 'No agents'], ['/reviews', 'No agents awaiting review']])('switches empty route %s', async (path, empty) => {
  server.use(http.get('/api/agents', () => ok([])));
  renderRoute(path);
  await screen.findByText(path === '/list' ? '暂无数字员工' : '暂无待审核的数字员工');
  await language('en-US');
  expect(screen.getByText(empty)).toBeInTheDocument();
  if (path === '/list') {
    await userEvent.click(screen.getByText('Platform agents'));
    expect(await screen.findByText('No platform agents')).toBeInTheDocument();
  }
});

it('reactively translates missing-message load errors while preserving the route', async () => {
  server.use(http.get('/api/agents/1', () => HttpResponse.json({ success: false, code: '10001', message: '' }, { status: 500 })));
  renderRoute('/agents/1');
  await screen.findByText('加载失败');
  await language('en-US');
  expect(screen.getByText('Could not load')).toBeInTheDocument();
  expect(screen.getByText('Please try again later.')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Back' })).toBeInTheDocument();
});
