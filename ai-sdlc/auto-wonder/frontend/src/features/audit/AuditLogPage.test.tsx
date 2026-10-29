import { beforeEach, describe, expect, it } from 'vitest';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import dayjs from 'dayjs';
import { server } from '@/test/mocks/server';
import { AuditLogPage } from './AuditLogPage';
import type { AuditLog } from './api';

const ok = (data: unknown) => HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data });

const baseLog = (overrides: Partial<AuditLog> = {}): AuditLog => ({
  id: 1,
  module: 'WORKITEM',
  action: 'DELETE',
  riskLevel: 'high',
  actorId: 7,
  actorType: 'HUMAN',
  actorName: '张三',
  targetType: 'workitem',
  targetId: 42,
  detail: null,
  detailJson: '{"triggerType":"EVENT","triggerSource":"USER_CLICK","eventType":"workitem.delete","message":"删除工单 aone#123","stepName":""}',
  gmtCreate: '2026-07-01T10:11:12',
  ...overrides,
});

let listRequests: URL[] = [];
let countRequests: URL[] = [];

function stubAudit(rows: AuditLog[], total: number) {
  server.use(
    http.get('/api/audit-logs', ({ request }) => {
      listRequests.push(new URL(request.url));
      return ok(rows);
    }),
    http.get('/api/audit-logs/count', ({ request }) => {
      countRequests.push(new URL(request.url));
      return ok(total);
    }),
  );
}

function renderPage(entries: string[] = ['/audit-logs']) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={entries}><AuditLogPage /></MemoryRouter>
    </QueryClientProvider>,
  );
}

const lastListParams = () => listRequests[listRequests.length - 1].searchParams;

// antd 已打开的 Select 下拉会被隐藏但保留在 DOM，同名选项可能多命中，取最后挂载的那个。
async function choose(user: ReturnType<typeof userEvent.setup>, comboboxName: string, option: string) {
  await user.click(screen.getByRole('combobox', { name: comboboxName }));
  const matches = await screen.findAllByText(option);
  await user.click(matches[matches.length - 1]);
}

describe('AuditLogPage v2', () => {
  beforeEach(() => {
    listRequests = [];
    countRequests = [];
    window.localStorage.clear();
  });

  it('renders the redesigned columns, risk tooltip and detail drawer', async () => {
    stubAudit([baseLog()], 23);
    const user = userEvent.setup();
    renderPage();

    expect(await screen.findByText('张三')).toBeInTheDocument();
    expect(screen.getByText('HUMAN')).toBeInTheDocument();
    expect(screen.getByText('工单')).toBeInTheDocument();
    expect(screen.getByText('DELETE')).toBeInTheDocument();
    expect(screen.getByText('高危')).toBeInTheDocument();
    expect(screen.getByText('工单 #42')).toBeInTheDocument();
    expect(screen.getByText('EVENT / USER_CLICK')).toBeInTheDocument();
    expect(screen.getByText('workitem.delete')).toBeInTheDocument();
    expect(screen.getByText('message: 删除工单 aone#123')).toBeInTheDocument();
    expect(screen.getByText('2026-07-01 10:11:12')).toBeInTheDocument();
    expect(screen.getByText('当前筛选 23 条')).toBeInTheDocument();
    expect(screen.getByText('共 23 条')).toBeInTheDocument();
    expect(lastListParams().get('page')).toBe('1');
    expect(lastListParams().get('size')).toBe('10');

    await user.hover(screen.getByRole('img', { name: '风险等级口径' }));
    expect(await screen.findByText(/DELETE \/ FORCE_PUBLISH \/ ROLE_CHANGE/)).toBeInTheDocument();

    await user.click(screen.getByText('张三'));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('审计日志详情')).toBeInTheDocument();
    const full = within(dialog).getByRole('region', { name: '完整记录' });
    expect(full.querySelector('code')?.textContent).toBe(JSON.stringify(baseLog(), null, 2));
    expect(within(dialog).getByText('workitem.delete')).toBeInTheDocument();
    expect(within(dialog).getByRole('region', { name: '事件详情' }).querySelector('code')?.textContent)
      .toBe(JSON.stringify(JSON.parse(baseLog().detailJson as string), null, 2));

    await user.click(within(dialog).getByRole('button', { name: '关闭' }));
    await waitFor(() => expect(screen.queryByRole('region', { name: '完整记录' })).toBeNull());
  });

  it('applies select filters immediately without a search button', async () => {
    server.use(http.get('/api/agents', () => ok([{ id: 7, name: '开发者' }])));
    stubAudit([], 23);
    const user = userEvent.setup();
    const { container } = renderPage();
    await screen.findByText('当前筛选 23 条');
    expect(screen.queryByRole('button', { name: /搜\s*索/ })).toBeNull();

    await choose(user, '模块', '技能');
    await waitFor(() => expect(lastListParams().get('module')).toBe('SKILL'));

    await choose(user, '操作', '删除');
    await waitFor(() => expect(lastListParams().get('action')).toBe('DELETE'));

    await choose(user, '风险', '高危');
    await waitFor(() => expect(lastListParams().get('riskLevel')).toBe('high'));

    await choose(user, '目标类型', '工单');
    await waitFor(() => expect(lastListParams().get('targetType')).toBe('workitem'));

    await choose(user, '操作人类型', '数字员工');
    await waitFor(() => expect(lastListParams().get('actorType')).toBe('AGENT'));

    await choose(user, '数字员工', '开发者');
    await waitFor(() => expect(lastListParams().get('actorId')).toBe('7'));

    expect(container.querySelectorAll('.audit-filter.is-active').length).toBeGreaterThan(0);

    await choose(user, '操作人类型', '系统');
    await waitFor(() => {
      expect(lastListParams().get('actorType')).toBe('SYSTEM');
      expect(lastListParams().get('actorId')).toBeNull();
      expect(countRequests[countRequests.length - 1].searchParams.get('actorType')).toBe('SYSTEM');
    });
    expect(screen.queryByRole('combobox', { name: '数字员工' })).toBeNull();

    await choose(user, '操作人类型', '全部');
    await waitFor(() => expect(lastListParams().get('actorType')).toBeNull());
  }, 20000);

  it('debounces and trims the keyword before requesting', async () => {
    stubAudit([], 0);
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findByText('当前筛选 0 条');

    await user.type(screen.getByPlaceholderText('搜索详情关键词'), ' 发布 ');

    await waitFor(() => expect(listRequests.some((url) => url.searchParams.get('keyword') === '发布')).toBe(true));
    expect(listRequests.filter((url) => url.searchParams.has('keyword'))).toHaveLength(1);
    expect(lastListParams().get('page')).toBe('1');
  });

  it('reads deep-linked filters and shows the selected agent', async () => {
    server.use(http.get('/api/agents', () => ok([{ id: 7, name: '开发者' }])));
    stubAudit([], 5);
    renderPage(['/audit-logs?actorType=AGENT&actorId=7&targetId=42&riskLevel=medium&module=SKILL&action=DELETE'
      + '&targetType=workitem&keyword=%E5%8F%91%E5%B8%83'
      + '&startTime=2026-06-30T16:00:00.000Z&endTime=2026-07-02T15:59:59.999Z']);

    expect(await screen.findByText('当前筛选 5 条')).toBeInTheDocument();
    const params = listRequests[0].searchParams;
    expect(params.get('actorType')).toBe('AGENT');
    expect(params.get('actorId')).toBe('7');
    expect(params.get('targetId')).toBe('42');
    expect(params.get('riskLevel')).toBe('medium');
    expect(params.get('module')).toBe('SKILL');
    expect(params.get('action')).toBe('DELETE');
    expect(params.get('targetType')).toBe('workitem');
    expect(params.get('keyword')).toBe('发布');
    expect(params.get('startTime')).toBe('2026-06-30T16:00:00.000Z');
    expect(params.get('endTime')).toBe('2026-07-02T15:59:59.999Z');

    expect(await screen.findByRole('combobox', { name: '数字员工' })).toBeInTheDocument();
    expect(screen.getAllByText('开发者').length).toBeGreaterThan(0);
  });

  it('maps a time preset to shanghai day bounds and clears it', async () => {
    stubAudit([], 0);
    const user = userEvent.setup();
    renderPage();
    await screen.findByText('当前筛选 0 条');

    await user.click(screen.getByPlaceholderText('开始日期'));
    await user.click(await screen.findByText('近 7 天'));

    const expectedStart = new Date(`${dayjs().subtract(6, 'day').format('YYYY-MM-DD')}T00:00:00+08:00`).toISOString();
    const expectedEnd = new Date(`${dayjs().format('YYYY-MM-DD')}T23:59:59.999+08:00`).toISOString();
    await waitFor(() => {
      expect(lastListParams().get('startTime')).toBe(expectedStart);
      expect(lastListParams().get('endTime')).toBe(expectedEnd);
    });

    const clear = document.querySelector('.ant-picker-clear');
    expect(clear).not.toBeNull();
    await user.click(clear as Element);
    await waitFor(() => expect(lastListParams().get('startTime')).toBeNull());
    expect(lastListParams().get('endTime')).toBeNull();
  });

  it('labels the range picker dialog, trigger state and clear control', async () => {
    stubAudit([], 0);
    const user = userEvent.setup();
    renderPage();
    await screen.findByText('当前筛选 0 条');

    const picker = document.querySelector('.audit-filter-bar .ant-picker') as HTMLElement;
    expect(picker).not.toBeNull();
    expect(picker.querySelectorAll('input[aria-expanded="false"]')).toHaveLength(2);

    await user.click(screen.getByPlaceholderText('开始日期'));
    await screen.findByText('近 7 天');
    await waitFor(() => expect(picker.querySelectorAll('input[aria-expanded="true"]')).toHaveLength(2));
    const dialog = document.querySelector('.ant-picker-dropdown [role="dialog"]');
    expect(dialog).not.toBeNull();
    expect(dialog?.getAttribute('aria-label')).toBe('选择时间范围');

    await user.click(screen.getByText('近 7 天'));
    await waitFor(() => expect(picker.querySelector('.ant-picker-clear')?.getAttribute('aria-label')).toBe('清除时间范围'));
    expect(picker.querySelector('.ant-picker-clear')?.getAttribute('role')).toBe('button');
  });

  it('falls back for unknown or missing field values', async () => {
    stubAudit([
      baseLog(),
      baseLog({
        id: 2,
        module: 'LEGACY',
        action: 'CUSTOM',
        riskLevel: 'low',
        actorId: 9,
        actorType: null,
        actorName: null,
        targetType: 'custom_target',
        targetId: null,
        detail: 'plain detail',
        detailJson: null,
        gmtCreate: 'bad-date',
      }),
      baseLog({
        id: 3,
        actorId: 3,
        actorName: '',
        riskLevel: null,
        targetType: null,
        targetId: null,
        detail: null,
        detailJson: null,
      }),
    ], 3);
    const { container } = renderPage();

    expect(await screen.findByText('#9')).toBeInTheDocument();
    expect(screen.getByText('LEGACY')).toBeInTheDocument();
    expect(screen.getByText('custom_target')).toBeInTheDocument();
    expect(screen.getByText('plain detail')).toBeInTheDocument();
    expect(screen.getByText('低危')).toBeInTheDocument();
    expect(screen.getByText('#3')).toBeInTheDocument();
    expect(screen.getAllByText('HUMAN')).toHaveLength(2);
    expect(screen.getAllByText('-').length).toBeGreaterThanOrEqual(3);
    expect(screen.getByText('message: 删除工单 aone#123')).toBeInTheDocument();
    expect(container.querySelectorAll('.audit-detail-link')).toHaveLength(0);

    const user = userEvent.setup();
    await user.click(screen.getByText('#9'));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getAllByText('-').length).toBeGreaterThanOrEqual(2);
  });

  it('keeps the current rows visible while a filtered refetch is pending', async () => {
    let calls = 0;
    let finish!: () => void;
    const pending = new Promise<void>((resolve) => { finish = resolve; });
    server.use(
      http.get('/api/audit-logs', async () => {
        calls += 1;
        if (calls > 1) await pending;
        return ok([baseLog()]);
      }),
      http.get('/api/audit-logs/count', () => ok(1)),
    );
    const user = userEvent.setup();
    renderPage();
    await screen.findByText('张三');

    await choose(user, '模块', '技能');
    await waitFor(() => expect(calls).toBe(2));
    expect(screen.getByText('张三')).toBeInTheDocument();
    expect(screen.queryByRole('status', { name: '加载审计日志' })).toBeNull();

    finish();
    await act(async () => { await Promise.resolve(); });
    await waitFor(() => expect(screen.getByText('张三')).toBeInTheDocument());
  });

  it('shows a skeleton while the first page is pending', async () => {
    let finish!: () => void;
    const pending = new Promise<void>((resolve) => { finish = resolve; });
    server.use(
      http.get('/api/audit-logs', async () => { await pending; return ok([]); }),
      http.get('/api/audit-logs/count', () => ok(0)),
    );
    renderPage();

    expect(await screen.findByRole('status', { name: '加载审计日志' })).toBeInTheDocument();
    finish();
    expect(await screen.findByText('当前筛选下暂无审计记录')).toBeInTheDocument();
    expect(screen.queryByRole('status', { name: '加载审计日志' })).toBeNull();
  });

  it('surfaces list failures and resets every filter', async () => {
    stubAudit([], 0);
    server.use(http.get('/api/audit-logs', () => HttpResponse.json({ success: false, code: '500', message: 'boom' }, { status: 500 })));
    const user = userEvent.setup();
    renderPage();

    expect(await screen.findByText('审计日志加载失败，请稍后重试')).toBeInTheDocument();

    server.use(http.get('/api/audit-logs', ({ request }) => {
      listRequests.push(new URL(request.url));
      return ok([]);
    }));
    await user.click(screen.getByRole('combobox', { name: '模块' }));
    await user.click(await screen.findByText('技能'));
    await user.type(screen.getByPlaceholderText('搜索详情关键词'), 'abc');
    await waitFor(() => expect(lastListParams().get('module')).toBe('SKILL'));

    await user.click(screen.getByRole('button', { name: '重置筛选' }));
    await waitFor(() => {
      expect(lastListParams().get('module')).toBeNull();
      expect(lastListParams().get('keyword')).toBeNull();
    });
    expect(screen.getByPlaceholderText('搜索详情关键词')).toHaveValue('');
    await waitFor(() => expect(screen.queryByText('审计日志加载失败，请稍后重试')).toBeNull());
  });

  it('paginates the list', async () => {
    stubAudit([baseLog()], 23);
    const user = userEvent.setup();
    renderPage();
    await screen.findByText('当前筛选 23 条');

    await user.click(screen.getByTitle('2'));
    await waitFor(() => expect(lastListParams().get('page')).toBe('2'));
  });

  it('falls back to the legacy page when the v2 flag is off', async () => {
    stubAudit([baseLog()], 1);
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={client}>
        <MemoryRouter><AuditLogPage pageV2Enabled={false} /></MemoryRouter>
      </QueryClientProvider>,
    );

    expect(await screen.findByRole('button', { name: /搜\s*索/ })).toBeInTheDocument();
    expect(screen.queryByText(/当前筛选/)).toBeNull();
    await waitFor(() => expect(lastListParams().get('actorType')).toBe('HUMAN'));
  });
});
