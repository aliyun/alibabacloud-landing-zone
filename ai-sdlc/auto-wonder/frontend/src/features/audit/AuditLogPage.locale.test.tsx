import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { message } from 'antd';
import { server } from '@/test/mocks/server';
import { i18n } from '@/shared/i18n';
import { AuditLogPage } from './AuditLogPage';
const record = { id: 1, module: 'WORKITEM', action: 'CREATE', actorId: 7, actorType: 'HUMAN', actorName: '名字<img src=x>', targetType: 'workitem', targetId: 42, detailJson: '{"message":"原始<img src=x>","eventType":"自定义","triggerType":"EVENT","triggerSource":"RAW","checkpointSeq":1784292150410807000}', gmtCreate: '2026-07-01T10:00:00Z' };
const language = (locale: string) => act(async () => { await i18n.changeLanguage(locale); });
const ok = (data: unknown) => HttpResponse.json({ success: true, code: '0', data });
describe('audit locale boundary', () => {
  beforeEach(async () => { await i18n.changeLanguage('zh-CN'); });
  afterEach(async () => { vi.restoreAllMocks(); message.destroy(); await i18n.changeLanguage('zh-CN'); });
  it('keeps filters, pagination and raw drawer JSON in a cached route while translating finite codes', async () => {
    const requests: string[] = [];
    server.use(...['/api/audit-logs', '/api/audit-logs/count'].map(path => http.get(path, ({ request }) => {
      requests.push(request.url); return ok(path.endsWith('count') ? 23 : [record, { ...record, id: 2, action: 'constructor', module: '__proto__', targetType: 'toString', actorType: 'constructor' }]);
    })));
    const router = createMemoryRouter([{ path: '/audit-logs', element: <AuditLogPage /> }], { initialEntries: ['/audit-logs?keep=raw'] });
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const { container } = render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>);
    await screen.findByText('共 23 条'); const user = userEvent.setup();
    await user.type(screen.getByPlaceholderText('按操作人 ID 筛选'), '7'); await user.type(screen.getByPlaceholderText('按目标 ID 筛选'), '42'); await user.type(screen.getByPlaceholderText('搜索详情关键词'), '原始');
    await user.click(screen.getAllByRole('combobox')[3]); await user.click(await screen.findByText('近 7 天')); await user.click(screen.getByRole('button', { name: /搜\s*索/ }));
    await waitFor(() => expect(requests.some(url => new URL(url).searchParams.get('keyword') === '原始')).toBe(true));
    await user.click(screen.getByTitle('2')); await waitFor(() => expect(requests.some(url => new URL(url).searchParams.get('page') === '2')).toBe(true));
    await user.click(screen.getAllByRole('button', { name: '详情' })[0]);
    const full = screen.getByRole('region', { name: '完整记录' }), json = full.querySelector('code')?.textContent;
    expect(json).toBe(JSON.stringify(record, null, 2));
    const keyword = screen.getByPlaceholderText('搜索详情关键词'), frozen = [...requests];
    let finish!: () => void; const writeText = vi.fn(() => new Promise<void>(resolve => { finish = resolve; }));
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } }); const success = vi.spyOn(message, 'success');
    await user.click(within(full).getByRole('button', { name: '复制完整记录' }));
    for (const locale of ['en-US', 'zh-CN', 'en-US'] as const) {
      await language(locale); const en = locale === 'en-US';
      expect(screen.getByRole('button', { name: en ? 'Close' : '关闭' })).toBeInTheDocument();
      expect(screen.getByText(en ? 'Audit log details' : '审计日志详情')).toBeInTheDocument(); expect(screen.getByText(en ? '23 records' : '共 23 条')).toBeInTheDocument();
      expect(screen.getByPlaceholderText(en ? 'Search detail keywords' : '搜索详情关键词')).toBe(keyword); expect(keyword).toHaveValue('原始');
      for (const name of en ? ['Module', 'Action', 'Target type', 'Time range'] : ['模块', '操作', '目标类型', '时间范围']) expect(screen.getByRole('combobox', { name })).toBeInTheDocument();
      expect(screen.getByRole('textbox', { name: en ? 'Actor ID' : '操作人 ID' })).toHaveValue('7'); expect(screen.getByRole('textbox', { name: en ? 'Target ID' : '目标 ID' })).toHaveValue('42');
      expect(screen.getByRole('textbox', { name: en ? 'Detail keywords' : '详情关键词' })).toBe(keyword);
      expect(screen.getByText(en ? 'Create' : '创建')).toBeInTheDocument(); expect(screen.getByText(en ? '名字<img src=x> (Human)' : '名字<img src=x> (人工)')).toBeInTheDocument();
      expect(screen.getByText('__proto__')).toBeInTheDocument(); expect(screen.getByText('constructor')).toBeInTheDocument(); expect(screen.getByText('toString #42')).toBeInTheDocument();
      expect(screen.getAllByText('message: 原始<img src=x>', { selector: 'span' })).toHaveLength(2);
      expect(screen.getAllByText(new Intl.DateTimeFormat(locale, { year: 'numeric', month: 'numeric', day: 'numeric', hour: 'numeric', minute: 'numeric', second: 'numeric' }).format(new Date(record.gmtCreate)))).toHaveLength(2);
      expect(screen.getByRole('region', { name: en ? 'Full record' : '完整记录' })).toBe(full); expect(full.querySelector('code')?.textContent).toBe(json); expect(requests).toEqual(frozen); expect(container.querySelector('img')).toBeNull();
    }
    await act(async () => { finish(); }); await waitFor(() => expect(success).toHaveBeenCalledWith('Copied Full record'));
    expect(writeText).toHaveBeenCalledWith(JSON.stringify(record, null, 2)); expect(router.state.location.search).toBe('?keep=raw');
    const applied = new URL(frozen.find(url => new URL(url).searchParams.get('keyword') === '原始')!);
    expect(applied.searchParams.get('actorType')).toBe('HUMAN'); expect(applied.searchParams.get('actorId')).toBe('7'); expect(applied.searchParams.get('targetId')).toBe('42');
    const start = new Date(applied.searchParams.get('startTime')!), end = new Date(applied.searchParams.get('endTime')!), expected = new Date(end); expected.setDate(expected.getDate() - 7); expect(start.toISOString()).toBe(expected.toISOString());
  }, 20000);
  it('updates loading, search and reset names while a list request is pending', async () => {
    let finish!: () => void;
    const pending = new Promise<void>(resolve => { finish = resolve; });
    let calls = 0;
    server.use(http.get('/api/audit-logs', async () => { calls++; await pending; return ok([]); }), http.get('/api/audit-logs/count', () => ok(0)));
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<QueryClientProvider client={client}><AuditLogPage /></QueryClientProvider>);
    await screen.findByText('加载审计日志'); await language('en-US');
    expect(screen.getByText('Loading audit logs')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Search' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Reset' })).toBeInTheDocument();
    await act(async () => { finish(); }); await screen.findAllByText('No data'); expect(calls).toBe(1);
  });
  it.each([0, 1, 2])('renders numeric total %s in both languages', async count => {
    server.use(http.get('/api/audit-logs', () => ok([])), http.get('/api/audit-logs/count', () => ok(count)));
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } }); render(<QueryClientProvider client={client}><AuditLogPage /></QueryClientProvider>);
    if (count === 0) { await screen.findAllByText('No data'); await language('en-US'); expect(screen.queryByText(/records/)).not.toBeInTheDocument(); }
    else { await screen.findByText(`共 ${count} 条`); await language('en-US'); expect(screen.getByText(`${count} ${count === 1 ? 'record' : 'records'}`)).toBeInTheDocument(); }
  });
});
