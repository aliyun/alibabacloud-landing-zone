import { act, cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { i18n } from '@/shared/i18n';
import { MemberDeliveryTab } from './components/MemberDeliveryTab';
import { InsightsPage } from './InsightsPage';
import { MetricCards } from './components/MetricCards';
import { KeyInsights } from './components/KeyInsights';
import { Recommendations } from './components/Recommendations';
import { SdlcLinkIssues } from './components/SdlcLinkIssues';
const metrics = { cost: { totalTokens: 100000, avgTokensPerTask: 5000, dailyAvg: 14000, trend: [10], totalCredits: 82.61, avgCreditsPerTask: 12.5, dailyAvgCredits: 2.75, creditsTrend: [1] }, efficiency: { completionRate: 80, totalTasks: 20, completedTasks: 16, avgDurationMinutes: 30, trend: [80] }, stability: { successRate: 90, retryCount: 2, blockedCount: 1, trend: [90] }, security: { highRiskOps: 1, complianceRate: 98, auditBlocks: 3, trend: [1] } };
const ok = (data: unknown) => HttpResponse.json({ success: true, data });
function routed(element: React.ReactNode, client = new QueryClient({ defaultOptions: { queries: { retry: false } } })) {
 const router = createMemoryRouter([{ path: '/insights', element }], { initialEntries: ['/insights'] });
 return { ...render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>), client };
}
beforeEach(async () => { localStorage.clear(); await i18n.changeLanguage('zh-CN'); });
afterEach(async () => { cleanup(); vi.useRealTimers(); await i18n.changeLanguage('zh-CN'); });
it('translates the four insight tabs without retired execution audit and requirement delivery entries', async () => {
 server.use(http.get('/api/dashboard/realtime', () => ok(null)), http.get('/api/dashboard/overview', () => ok(null)), http.get('/api/dashboard/pipeline-health', () => ok(null)), http.get('/api/insights/metrics', () => ok(metrics)), http.get('/api/insights/workers', () => ok([])), http.get('/api/insights/audit', () => ok({ items: [], total: 0 })));
 routed(<InsightsPage />);
 const zh = ['实时看板', '人机协作', '工单交付', '阶段耗时'];
 await screen.findByRole('radio', { name: zh[0] });
 for (const label of zh) expect(screen.getByRole('radio', { name: label })).toBeInTheDocument();
 expect(screen.queryByRole('radio', { name: '执行审计' })).toBeNull();
 expect(screen.queryByRole('radio', { name: '需求交付' })).toBeNull();
 await act(async () => { await i18n.changeLanguage('en-US'); });
 for (const label of ['Realtime dashboard', 'Human–Agent participation', 'Work item delivery', 'Stage duration']) expect(screen.getByRole('radio', { name: label })).toBeInTheDocument();
 expect(screen.queryByRole('radio', { name: 'Execution audit' })).toBeNull();
 expect(screen.queryByRole('radio', { name: 'Requirement delivery' })).toBeNull();
});
it('keeps member search and dates on a cached independent consumer', async () => {
 const requests: string[] = [];
 server.use(http.get('/api/insights/member-delivery', ({ request }) => { requests.push(new URL(request.url).search); return ok({ summary: { created: 4, total: 8, completed: 5, inProgress: 3, notStarted: 0, pendingDecision: 0, requirements: 4 }, members: [{ memberId: 1, memberName: '<em>成员甲</em>', created: 4, total: 8, completed: 5, inProgress: 3, notStarted: 0, pendingDecision: 0, requirements: 4 }, { memberId: 2, memberName: '成员乙', created: 0, total: 0, completed: 0, inProgress: 0, notStarted: 0, pendingDecision: 0, requirements: 0 }] }); }));
 const { container, client } = routed(<MemberDeliveryTab />); await screen.findByText('<em>成员甲</em>'); const user = userEvent.setup();
 await user.type(screen.getByPlaceholderText('搜索项目成员'), '成员甲');
 const input = screen.getByPlaceholderText('搜索项目成员'); const table = container.querySelector('table'); const dates = container.querySelectorAll('.ant-picker-input input'); const values = [...dates].map(n => (n as HTMLInputElement).value);
 const keys = client.getQueryCache().getAll().map(q => q.queryKey);
 await act(async () => { await i18n.changeLanguage('en-US'); });
 expect(screen.getByPlaceholderText('Search project members')).toBe(input); expect(input).toHaveValue('成员甲');
 expect(screen.getByText('1 member')).toBeInTheDocument(); expect(screen.getByRole('img', { name: 'Completed: 5 tasks; current maximum: 5 tasks' })).toBeInTheDocument();
 expect(container.querySelector('table')).toBe(table); expect([...dates].map(n => (n as HTMLInputElement).value)).toEqual(values); expect(requests).toHaveLength(1); expect(client.getQueryCache().getAll().map(q => q.queryKey)).toEqual(keys);
 expect(container.querySelector('em')).toBeNull(); await user.clear(input); await user.type(input, 'missing'); expect(screen.getByText('No matching members. Adjust your search.')).toBeInTheDocument();
});
it('translates independent metric and analytical panel labels and hides decorative icons', async () => {
 const { container } = routed(<><MetricCards metrics={metrics} /><KeyInsights cards={[]} workerLabel="raw" /><Recommendations items={[]} /><SdlcLinkIssues findings={[{ worker: '<em>原名</em>', link: 'raw stage', issue: 'issue', impact: 'impact', signal: '1', severity: 'low' }]} /></>);
 await act(async () => { await i18n.changeLanguage('en-US'); });
 for (const text of ['Efficiency', 'Stability', 'Security', 'Key insights', 'Recommended actions', 'Updates with filters', 'SDLC workflow issues', 'issue; impact', '30min']) expect(screen.getByText(text)).toBeInTheDocument();
 expect(container.querySelectorAll('[role="img"]:not([aria-hidden="true"])')).toHaveLength(0); expect(container.querySelector('em')).toBeNull();
});
it('uses singular units for measured one and keeps the last thirty days across an English locale', async () => {
 vi.useFakeTimers({ toFake: ['Date'] }); vi.setSystemTime(new Date('2026-09-27T17:00:00Z'));
 const requests: string[] = [];
 server.use(http.get('/api/insights/member-delivery', ({ request }) => { requests.push(new URL(request.url).search); return ok({ summary: { created: 1, total: 3, completed: 1, inProgress: 2, notStarted: 0, pendingDecision: 0, requirements: 1 }, members: [{ memberId: 1, memberName: 'raw', created: 1, total: 3, completed: 1, inProgress: 2, notStarted: 0, pendingDecision: 0, requirements: 1 }] }); }));
 const { container } = routed(<MemberDeliveryTab />);
 await screen.findByText('所选周期 · 已完成');
 await waitFor(() => expect(container.querySelectorAll('.ant-statistic-content-suffix')).toHaveLength(4));
 expect([...container.querySelectorAll('.ant-statistic-content-suffix')].map(n => n.textContent)).toEqual(['项', '项', '项', '项']);
 await act(async () => { await i18n.changeLanguage('en-US'); });
 expect(requests).toEqual(['?start_date=2026-08-30&end_date=2026-09-28']);
 const suffixes = [...container.querySelectorAll('.ant-statistic-content-suffix')].map(n => n.textContent);
 expect(suffixes).toEqual(['task', 'task', 'tasks', 'tasks']);
 expect(screen.getByRole('img', { name: 'Completed: 1 task; current maximum: 1 task' })).toBeInTheDocument();
 vi.useRealTimers();
});

it('uses the current locale for member loading, error and retry empty state', async () => {
 let resolve!: () => void; let calls = 0;
 server.use(http.get('/api/insights/member-delivery', async () => { calls++; if (calls === 1) { await new Promise<void>(r => { resolve = r; }); return HttpResponse.json({ success: false, message: 'raw backend failure' }, { status: 500 }); } return ok({ summary: { created: 0, total: 0, completed: 0, inProgress: 0, notStarted: 0, pendingDecision: 0, requirements: 0 }, members: [] }); }));
 const { container } = routed(<MemberDeliveryTab />); await waitFor(() => expect(resolve).toBeTypeOf('function'));
 await act(async () => { await i18n.changeLanguage('en-US'); }); expect(screen.getByText('Loading member data…')).toBeInTheDocument();
 await act(async () => { resolve(); }); expect(await screen.findByText('Failed to load member statistics. Retry; the date range may span at most 180 days.')).toBeInTheDocument();
 const suffixes = () => [...container.querySelectorAll('.ant-statistic-content-suffix')].map(n => n.textContent);
 const values = () => [...container.querySelectorAll('.ant-statistic-content-value')].map(n => n.textContent);
 expect(values()).toEqual(['—', '—', '—', '—']);
 expect(suffixes()).toEqual(['tasks', 'tasks', 'tasks', 'tasks']);
 await act(async () => { await i18n.changeLanguage('zh-CN'); });
 expect(screen.getByText('成员统计加载失败，请重试；日期范围最多 180 天。')).toBeInTheDocument();
 expect(suffixes()).toEqual(['项', '项', '项', '项']); expect(values()).toEqual(['—', '—', '—', '—']);
 await act(async () => { await i18n.changeLanguage('en-US'); });
 await userEvent.click(screen.getByRole('button', { name: 'Retry' })); expect(await screen.findByText('No work item delivery data in the selected period')).toBeInTheDocument(); expect(calls).toBe(2);
 expect(values()).toEqual(['0', '0', '0', '0']); expect(suffixes()).toEqual(['tasks', 'tasks', 'tasks', 'tasks']);
 await act(async () => { await i18n.changeLanguage('zh-CN'); });
 expect(values()).toEqual(['0', '0', '0', '0']); expect(suffixes()).toEqual(['项', '项', '项', '项']);
});

it('projects only actual member origins on cached zh-en-zh data and searches displayed names without another query', async () => {
 const members = [
  { memberId: '123456789', memberName: '成员 #123456789', memberNameKind: 'MEMBER_ID_FALLBACK' },
  { memberId: 8, memberName: '历史成员 #8', memberNameKind: 'HISTORICAL_MEMBER_FALLBACK' },
  { memberId: null, memberName: '未归属成员' },
  { memberId: 2, memberName: '成员 #123456789' },
  { memberId: 3, memberName: '<b>历史成员 #8</b>', memberNameKind: 'FUTURE' },
  { memberId: 4, memberName: '未归属成员' },
 ].map(m => ({ ...m, created: 1, total: 1, completed: 1, inProgress: 0, notStarted: 0, pendingDecision: 0, requirements: 0 }));
 let calls = 0;
 server.use(http.get('/api/insights/member-delivery', () => { calls++; return ok({ summary: { created: 6, total: 6, completed: 6, inProgress: 0, notStarted: 0, pendingDecision: 0, requirements: 0 }, members }); }));
 const { client, container } = routed(<MemberDeliveryTab />);
 await screen.findByText('<b>历史成员 #8</b>');
 const cache = client.getQueryCache().getAll().map(q => q.queryKey); const table = container.querySelector('table');
 await act(async () => { await i18n.changeLanguage('en-US'); });
 expect(screen.getByText('Member #123456789')).toBeInTheDocument();
 expect(screen.getByText('Historical member #8')).toBeInTheDocument();
 expect(screen.getByText('Unassigned member')).toBeInTheDocument();
 expect(screen.getByText('成员 #123456789')).toBeInTheDocument(); expect(screen.getByText('未归属成员')).toBeInTheDocument();
 expect(container.querySelector('b')).toBeNull(); expect(container.querySelector('table')).toBe(table);
 const input = screen.getByPlaceholderText('Search project members');
 await userEvent.type(input, 'Historical member');
 expect(screen.getByText('Historical member #8')).toBeInTheDocument(); expect(screen.queryByText('<b>历史成员 #8</b>')).not.toBeInTheDocument();
 await act(async () => { await i18n.changeLanguage('zh-CN'); });
 expect(input).toHaveValue('Historical member'); expect(screen.getByText('没有匹配的成员，请调整搜索条件')).toBeInTheDocument();
 await userEvent.clear(input); expect(screen.getAllByText('历史成员 #8')).toHaveLength(1); expect(screen.getAllByText('未归属成员')).toHaveLength(2);
 expect(calls).toBe(1); expect(client.getQueryCache().getAll().map(q => q.queryKey)).toEqual(cache);
 expect(members[0].memberName).toBe('成员 #123456789');
});

it.each(['zh-CN', 'en-US'])('hides the installed member error glyph in %s without losing retry', async locale => {
 server.use(http.get('/api/insights/member-delivery', () => HttpResponse.json({ success: false, message: 'raw' }, { status: 500 })));
 await i18n.changeLanguage(locale); const { container } = routed(<MemberDeliveryTab />);
 await screen.findByRole('button', { name: locale === 'en-US' ? 'Retry' : /^重\s*试$/ });
 expect(screen.queryByRole('img', { name: 'close-circle' })).not.toBeInTheDocument();
 expect(container.querySelector('.anticon-close-circle')).not.toBeNull();
});

it.each(['zh-CN', 'en-US'])('final widget member search and nonempty clear preserve live raw filtering in %s', async locale => {
 let calls = 0;
 server.use(http.get('/api/insights/member-delivery', () => { calls++; return ok({ summary: { created: 1, total: 1, completed: 1, inProgress: 0, notStarted: 0, pendingDecision: 0, requirements: 0 }, members: [{ memberId: 1, memberName: '<raw>成员', created: 1, total: 1, completed: 1, inProgress: 0, notStarted: 0, pendingDecision: 0, requirements: 0 }] }); }));
 await i18n.changeLanguage(locale); routed(<MemberDeliveryTab />);
 await screen.findByText('<raw>成员');
 const label = locale === 'en-US' ? 'Search project members' : '搜索项目成员';
 const input = screen.getByRole('searchbox', { name: label });
 expect(screen.getByRole('button', { name: label })).toBeEnabled();
 await userEvent.type(input, 'missing');
 expect(screen.queryByText('<raw>成员')).not.toBeInTheDocument();
 const clear = screen.getByRole('button', { name: locale === 'en-US' ? 'Clear member search' : '清空成员搜索' });
 expect(screen.queryByRole('img', { name: 'close-circle' })).not.toBeInTheDocument();
 await userEvent.click(clear);
 expect(input).toHaveValue(''); expect(screen.getByText('<raw>成员')).toBeInTheDocument(); expect(calls).toBe(1);
});
it.each(['zh-CN', 'en-US'])('final widget pending member refresh keeps its own current name in %s', async locale => {
 let finish!: () => void;
 server.use(http.get('/api/insights/member-delivery', async () => { await new Promise<void>(resolve => { finish = resolve; }); return ok({ summary: { created: 0, total: 0, completed: 0, inProgress: 0, notStarted: 0, pendingDecision: 0, requirements: 0 }, members: [] }); }));
 await i18n.changeLanguage(locale); routed(<MemberDeliveryTab />);
 await waitFor(() => expect(finish).toBeTypeOf('function'));
 try {
  const button = screen.getByRole('button', { name: locale === 'en-US' ? /Refresh/ : /刷\s*新/ });
  expect(button).toHaveClass('ant-btn-loading');
  expect(button).toHaveAccessibleName(locale === 'en-US' ? 'Refresh' : '刷新');
  expect(button.querySelector('.anticon-loading')).toHaveAttribute('aria-hidden', 'true');
  await act(() => i18n.changeLanguage(locale === 'en-US' ? 'zh-CN' : 'en-US'));
  expect(screen.getByRole('button', { name: locale === 'en-US' ? '刷新' : 'Refresh' })).toBe(button);
  expect(button).toHaveClass('ant-btn-loading');
 } finally { await act(async () => { finish(); }); }
});
