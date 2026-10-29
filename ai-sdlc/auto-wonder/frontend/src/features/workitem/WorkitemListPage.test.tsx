import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { act, render, screen, waitFor, within, fireEvent } from '@testing-library/react';
import { i18n } from '@/shared/i18n';
import userEvent from '@testing-library/user-event';
import dayjs from 'dayjs';
import { message } from 'antd';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse, delay } from 'msw';
import { server } from '@/test/mocks/server';
import { useAuthStore } from '@/shared/auth/store';
import { WorkitemListPage } from './WorkitemListPage';

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <WorkitemListPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

function pageData(list: unknown[], total = list.length) {
  return { list, total, pageNum: 1, pageSize: 100 };
}

describe('WorkitemListPage', () => {
  beforeEach(() => {
    useAuthStore.getState().clear();
    window.localStorage.clear();
    vi.restoreAllMocks();
  });

  it('shows only the current user and server pending total under All, and hides the summary under Pending', async () => {
    useAuthStore.getState().setUser({ id: 42, username: 'zhangsan', nickname: '张三', email: '' });
    server.use(http.get('/api/workitems', ({ request }) => {
      const query = new URL(request.url).searchParams;
      const pending = query.get('statusCategory') === 'PENDING_DECISION';
      return HttpResponse.json({ success: true, code: '0', data: pageData(pending ? [{
        id: 1, title: '别人的工单', workType: 'REQ', statusCategory: 'PENDING_DECISION',
        statusName: '待决策', assigneeType: 'HUMAN', assigneeRef: 99, assigneeDisplayName: '李四', priority: 2,
      }] : [], query.get('pendingDecisionOnly') === 'true' ? 73 : 100) });
    }));
    renderPage();
    const summary = await screen.findByLabelText('决策人汇总');
    expect(within(summary).getByText('张三')).toBeInTheDocument();
    expect(within(summary).getByTitle('73')).toBeInTheDocument();
    expect(within(summary).queryByText('李四')).not.toBeInTheDocument();
    await userEvent.click(screen.getByText('待我决策'));
    expect(screen.queryByLabelText('决策人汇总')).not.toBeInTheDocument();
    await userEvent.click(screen.getByText('全部'));
    expect(await screen.findByLabelText('决策人汇总')).toBeInTheDocument();
  });

  it('filters watched workitems in every kanban column and table, and remembers the scope', async () => {
    const requests: URLSearchParams[] = [];
    server.use(http.get('/api/workitems', ({ request }) => {
      requests.push(new URL(request.url).searchParams);
      return HttpResponse.json({ success: true, code: '0', data: pageData([]) });
    }));
    const view = renderPage();
    await userEvent.click(screen.getByText('我关注的'));
    await waitFor(() => {
      for (const category of ['NEW', 'IN_PROGRESS', 'PENDING_DECISION', 'DONE']) {
        expect(requests.some(q => q.get('mineScope') === 'WATCHED' && q.get('statusCategory') === category)).toBe(true);
      }
    });
    expect(window.localStorage.getItem('autowonder.workitems.scope')).toBe('WATCHED');
    await userEvent.click(screen.getByLabelText('表格视图'));
    await waitFor(() => expect(requests.some(q => q.get('mineScope') === 'WATCHED' && q.get('size') === '10')).toBe(true));
    view.unmount();
    requests.length = 0;
    renderPage();
    await waitFor(() => expect(requests.length).toBeGreaterThan(0));
    expect(requests.every(q => q.get('mineScope') === 'WATCHED')).toBe(true);
    await userEvent.click(screen.getByText('全部'));
    await waitFor(() => expect(requests[requests.length - 1].has('mineScope')).toBe(false));
  });

  it('renders kanban view by default with workitems', async () => {
    server.use(
      http.get('/api/workitems', () => {
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: pageData([
            { id: 1, title: '实现登录功能', workType: 'REQ', statusName: '待处理', priority: 2, assigneeType: 'HUMAN', assigneeRef: null, assigneeName: null, version: 1, gmtCreate: '2026-07-01', gmtModified: '2026-07-01' },
            { id: 2, title: '修复搜索Bug', workType: 'BUG', statusName: '开发中', priority: 1, assigneeType: 'AGENT', assigneeRef: 5, assigneeName: '代码助手', version: 2, gmtCreate: '2026-07-02', gmtModified: '2026-07-02' },
          ], 6001),
        });
      }),
    );

    renderPage();
    expect(await screen.findByText('实现登录功能')).toBeInTheDocument();
    expect(screen.getByText('修复搜索Bug')).toBeInTheDocument();
    expect(screen.getAllByText('待处理').length).toBeGreaterThan(0);
    expect(screen.getAllByText('执行中').length).toBeGreaterThan(0);
    expect(screen.getByText((_, element) => element?.textContent === '总工单数 6001 个' && !Array.from(element.children).some(child => child.textContent === element.textContent))).toBeInTheDocument();
  });

  it('shows the 定时执行 icon for scheduled workitems in kanban view', async () => {
    server.use(
      http.get('/api/workitems', () => {
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: pageData([
            { id: 21, title: '定时工单', workType: 'REQ', statusName: '待处理', priority: 2, assigneeType: 'AGENT', assigneeRef: 5, assigneeName: '代码助手', scheduledStartAt: '2026-09-01T02:00:00Z', version: 1, gmtCreate: '2026-07-01', gmtModified: '2026-07-01' },
            { id: 22, title: '普通工单', workType: 'REQ', statusName: '待处理', priority: 2, assigneeType: 'HUMAN', assigneeRef: null, assigneeName: null, version: 1, gmtCreate: '2026-07-01', gmtModified: '2026-07-01' },
          ]),
        });
      }),
    );

    renderPage();
    expect(await screen.findByText('定时工单')).toBeInTheDocument();
    expect(screen.getByLabelText('定时执行')).toBeInTheDocument();
  });

  it('shows the 定时执行 icon in table view title column for scheduled workitems', async () => {
    server.use(
      http.get('/api/workitems', () => {
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: pageData([
            { id: 23, title: '表格定时工单', workType: 'REQ', statusName: '待处理', priority: 2, assigneeType: 'AGENT', assigneeRef: 5, assigneeName: '代码助手', scheduledStartAt: '2026-09-01T02:00:00Z', version: 1, gmtCreate: '2026-07-01', gmtModified: '2026-07-01' },
          ]),
        });
      }),
    );

    renderPage();
    await userEvent.click(await screen.findByLabelText('表格视图'));
    expect(await screen.findByText('表格定时工单')).toBeInTheDocument();
    expect(screen.getByLabelText('定时执行')).toBeInTheDocument();
  });

  it('keeps the scheduled badge after the planned start has already fired', async () => {
    server.use(
      http.get('/api/workitems', () => {
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: pageData([
            { id: 24, title: '已触发定时工单', workType: 'REQ', statusName: '开发中', priority: 2, assigneeType: 'AGENT', assigneeRef: 5, assigneeName: '代码助手', scheduledStartTriggeredAt: '2026-08-26T10:00:00Z', version: 2, gmtCreate: '2026-07-01', gmtModified: '2026-08-26' },
          ]),
        });
      }),
    );

    renderPage();
    expect(await screen.findByText('已触发定时工单')).toBeInTheDocument();
    expect(screen.getByLabelText('定时执行已触发')).toBeInTheDocument();
  });

  it('marks scheduled-task derived workitems in the list', async () => {
    server.use(
      http.get('/api/workitems', () => {
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: pageData([
            { id: 25, title: '定时任务派生工单', workType: 'TASK', statusName: '待处理', priority: 2, assigneeType: 'AGENT', assigneeRef: 5, assigneeName: '代码助手', origin: { type: 'SCHEDULED_TASK_RUN', id: 9, scheduledTaskId: 77, scheduledTaskName: '每日巡检' }, version: 1, gmtCreate: '2026-08-26T02:00:00Z', gmtModified: '2026-08-26T02:00:00Z' },
          ]),
        });
      }),
    );

    renderPage();
    expect(await screen.findByText('定时任务派生工单')).toBeInTheDocument();
    expect(screen.getByLabelText('定时任务执行')).toBeInTheDocument();
  });

  it('shows the 异常 tag for a stuck workitem in kanban', async () => {
    server.use(
      http.get('/api/workitems', () => {
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: pageData([
            { id: 3, title: '卡住的任务', workType: 'TASK', statusName: '开发中', priority: 2, assigneeType: 'AGENT', assigneeRef: 5, assigneeName: '代码助手', version: 1, gmtCreate: '2026-07-03', gmtModified: '2026-07-03', health: 'STUCK', healthReason: '最近一次执行超时，流程已停止且无自动恢复，请人工介入' },
          ]),
        });
      }),
    );

    renderPage();
    expect(await screen.findByText('卡住的任务')).toBeInTheDocument();
    expect(screen.getByText('异常')).toBeInTheDocument();
  });

  it('shows the assignee once without a duplicate intervention tag in kanban', async () => {
    server.use(
      http.get('/api/workitems', () => {
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: pageData([
            { id: 6, title: '人工处理工单', workType: 'REQ', statusName: '待处理', statusCategory: 'PENDING_DECISION', priority: 2, assigneeType: 'HUMAN', assigneeRef: 10000, assigneeName: 'caihe', assigneeDisplayName: '蔡何', version: 1, gmtCreate: '2026-07-05', gmtModified: '2026-07-05' },
          ]),
        });
      }),
    );

    renderPage();
    expect(await screen.findByText('人工处理工单')).toBeInTheDocument();
    expect(screen.queryByText('需要人工')).not.toBeInTheDocument();
    expect(screen.getByText('当前处理人: 蔡何')).toBeInTheDocument();
  });

  it('shows a nameless human-action tag in table status column for a pending-decision workitem', async () => {
    server.use(
      http.get('/api/workitems', () => {
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: pageData([
            { id: 7, title: '表格人工工单', workType: 'REQ', statusName: '开发中', statusCategory: 'PENDING_DECISION', priority: 2, assigneeType: 'HUMAN', assigneeRef: 10000, assigneeName: 'caihe', assigneeDisplayName: '蔡何(10000)', version: 1, gmtCreate: '2026-07-05', gmtModified: '2026-07-05' },
          ]),
        });
      }),
    );

    renderPage();
    await userEvent.click(await screen.findByLabelText('表格视图'));
    expect(await screen.findByText('表格人工工单')).toBeInTheDocument();
    expect(screen.getByText('需要人工')).toBeInTheDocument();
    expect(screen.queryByText(/需人工（蔡何\(10000\)）/)).not.toBeInTheDocument();
  });

  it('does not show 需人工 tag for agent-assigned or unassigned workitems', async () => {
    server.use(
      http.get('/api/workitems', () => {
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: pageData([
            { id: 8, title: '机器工单', workType: 'REQ', statusName: '开发中', statusCategory: 'PENDING_DECISION', priority: 2, assigneeType: 'AGENT', assigneeRef: 5, assigneeName: '代码助手', version: 1, gmtCreate: '2026-07-05', gmtModified: '2026-07-05' },
            { id: 9, title: '未指派工单', workType: 'REQ', statusName: '待处理', statusCategory: 'PENDING_DECISION', priority: 2, assigneeType: 'HUMAN', assigneeRef: null, assigneeName: null, version: 1, gmtCreate: '2026-07-05', gmtModified: '2026-07-05' },
          ]),
        });
      }),
    );

    renderPage();
    expect(await screen.findByText('机器工单')).toBeInTheDocument();
    expect(screen.getByText('未指派工单')).toBeInTheDocument();
    expect(screen.queryByText(/需人工/)).not.toBeInTheDocument();
  });

  it('renders create button', async () => {
    server.use(
      http.get('/api/workitems', () => {
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: pageData([]),
        });
      }),
    );

    renderPage();
    expect(await screen.findByRole('button', { name: /新建/ })).toBeInTheDocument();
  });

  it('retains exact bilingual New semantics and navigation without decorative glyph names', async () => {
    useAuthStore.setState({ accessLevel: 'READ_WRITE' });
    server.use(http.get('/api/workitems', () => HttpResponse.json({ success: true, data: pageData([]) })));
    renderPageWithProbe();
    const button = await screen.findByRole('button', { name: '新建工单' });
    expect(within(button).queryByRole('img')).not.toBeInTheDocument();
    await act(async () => { await i18n.changeLanguage('en-US'); });
    try {
      expect(screen.getByRole('button', { name: 'New work item' })).toBe(button);
      await userEvent.click(button);
      expect(screen.getByTestId('location-probe')).toHaveTextContent(/^\/workitems\/new$/);
    } finally { await act(async () => { await i18n.changeLanguage('zh-CN'); }); }
  });

  it('names the actual search clear button and offers a keyboard clear for hidden Select clear controls', async () => {
    const requests: URLSearchParams[] = [];
    server.use(http.get('/api/workitems', ({ request }) => {
      requests.push(new URL(request.url).searchParams);
      return HttpResponse.json({ success: true, data: pageData([]) });
    }));
    renderPage();
    const input = await screen.findByPlaceholderText('搜索工单ID或标题');
    await userEvent.type(input, '<raw 用户>{enter}');
    await waitFor(() => expect(requests.at(-1)?.get('keyword')).toBe('<raw 用户>'));
    const clear = screen.getByRole('button', { name: '清空工单搜索' });
    await userEvent.click(document.querySelectorAll('.ant-select-selector')[0]!);
    await userEvent.click(await screen.findByTitle('缺陷'));
    await waitFor(() => expect(requests.at(-1)?.get('workType')).toBe('BUG'));
    const clearType = screen.getByRole('button', { name: '清空类型筛选' });
    await act(async () => { await i18n.changeLanguage('en-US'); });
    try {
      expect(screen.getByRole('button', { name: 'Clear work item search' })).toBe(clear);
      expect(screen.getByRole('button', { name: 'Clear type filter' })).toBe(clearType);
      clearType.focus(); await userEvent.keyboard('{Enter}');
      await waitFor(() => expect(requests.at(-1)?.has('workType')).toBe(false));
      expect(requests.at(-1)?.get('keyword')).toBe('<raw 用户>');
      expect(input).toHaveValue('<raw 用户>');
      await userEvent.click(clear);
      expect(input).toHaveValue('');
      await waitFor(() => expect(requests.at(-1)?.has('keyword')).toBe(false));
      expect(requests.at(-1)?.get('page')).toBe('1');
    } finally { await act(async () => { await i18n.changeLanguage('zh-CN'); }); }
  });

  it('keeps create visible but blocks navigation for a read-only member', async () => {
    const error = vi.spyOn(message, 'error').mockImplementation(
      () => undefined as unknown as ReturnType<typeof message.error>,
    );
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_ONLY');
    server.use(
      http.get('/api/workitems', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: pageData([]),
      })),
    );
    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: /新建工单/ }));

    expect(error).toHaveBeenCalledWith('当前为只读权限，新建工单需要读写权限');
    expect(screen.getByText('工单')).toBeInTheDocument();
  });

  it('disables delete button for non-deletable workitems in table view', async () => {
    server.use(
      http.get('/api/workitems', () => {
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: pageData([
            {
              id: 4,
              title: '执行中的任务',
              workType: 'TASK',
              statusName: '开发中',
              priority: 2,
              assigneeType: 'AGENT',
              assigneeRef: 5,
              assigneeName: '代码助手',
              version: 1,
              gmtCreate: '2026-07-04',
              gmtModified: '2026-07-04',
              deletable: false,
              deletableReason: '工单正在执行中，请等待完成或结束后再删除',
            },
          ]),
        });
      }),
    );

    renderPage();
    await userEvent.click(await screen.findByLabelText('表格视图'));

    expect(await screen.findByRole('button', { name: /删除工单/ })).toBeDisabled();
  });

  it('keeps the opened delete confirmation bilingual and decorative while preserving the delete request', async () => {
    useAuthStore.setState({ accessLevel: 'READ_WRITE' });
    window.localStorage.setItem('autowonder.workitems.view', 'table');
    const rawTitle = '<b>raw</b> 删除工单';
    const deleted: Array<{ method: string; path: string; body: string }> = [];
    let reads = 0;
    server.use(
      http.get('/api/workitems', () => {
        reads += 1;
        return HttpResponse.json({ success: true, code: '0', data: pageData([{
          id: 71, title: rawTitle, workType: 'TASK', statusName: '待处理', priority: 2,
          assigneeType: 'HUMAN', assigneeRef: null, version: 1, deletable: true,
        }]) });
      }),
      http.delete('/api/workitems/:id', async ({ request }) => {
        deleted.push({ method: request.method, path: new URL(request.url).pathname, body: await request.text() });
        return HttpResponse.json({ success: true, code: '0', data: null });
      }),
    );
    await act(async () => { await i18n.changeLanguage('zh-CN'); });
    try {
      renderPageWithProbe();
      expect(await screen.findByText(rawTitle)).toBeInTheDocument();
      await userEvent.click(screen.getByRole('button', { name: '删除工单' }));
      const popup = await screen.findByRole('tooltip');
      expect(within(popup).getByText('删除工单')).toBeInTheDocument();
      expect(within(popup).getByText('正在执行或外部平台集成的工单不可删除')).toBeInTheDocument();
      expect(within(popup).getByRole('button', { name: /^删\s*除$/ })).toBeInTheDocument();
      expect(within(popup).getByRole('button', { name: /^取\s*消$/ })).toBeInTheDocument();
      expect(within(popup).queryByRole('img', { name: 'exclamation-circle' })).not.toBeInTheDocument();
      const glyph = within(popup).getAllByRole('img', { hidden: true })
        .find(icon => icon.querySelector('svg')?.getAttribute('data-icon') === 'exclamation-circle');
      expect(glyph).toHaveAttribute('aria-hidden', 'true');
      expect(glyph?.querySelector('svg')).toHaveAttribute('data-icon', 'exclamation-circle');
      const initialReads = reads;
      await act(async () => { await i18n.changeLanguage('en-US'); });
      expect(within(popup).getByText('Delete work item')).toBeInTheDocument();
      expect(within(popup).getByText('Work items that are running or integrated with an external platform cannot be deleted.')).toBeInTheDocument();
      expect(within(popup).getByRole('button', { name: 'Delete' })).toBeInTheDocument();
      expect(within(popup).getByRole('button', { name: 'Cancel' })).toBeInTheDocument();
      expect(within(popup).queryByRole('img', { name: 'exclamation-circle' })).not.toBeInTheDocument();
      expect(screen.getByText(rawTitle)).toBeInTheDocument();
      expect(reads).toBe(initialReads);
      expect(deleted).toEqual([]);
      await userEvent.click(within(popup).getByRole('button', { name: 'Delete' }));
      await waitFor(() => expect(deleted).toEqual([{ method: 'DELETE', path: '/api/workitems/71', body: '' }]));
      expect(screen.getByTestId('location-probe')).toHaveTextContent(/^\/workitems$/);
    } finally {
      await act(async () => { await i18n.changeLanguage('zh-CN'); });
    }
  });

  it('requests only my pending decisions through the server filter and remembers it', async () => {
    const requests: string[] = [];
    server.use(
      http.get('/api/workitems', ({ request }) => {
        const url = new URL(request.url);
        requests.push(url.searchParams.toString());
        const pendingOnly = url.searchParams.get('pendingDecisionOnly') === 'true';
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: pageData(pendingOnly ? [
            { id: 1, title: '我的决策', workType: 'REQ', statusName: '开发中', statusCategory: 'PENDING_DECISION', pendingDecision: true, priority: 2, assigneeType: 'HUMAN', assigneeRef: 42, assigneeName: '我', version: 1, gmtCreate: '2026-07-01', gmtModified: '2026-07-01' },
          ] : [
            { id: 1, title: '我的决策', workType: 'REQ', statusName: '开发中', statusCategory: 'PENDING_DECISION', pendingDecision: true, priority: 2, assigneeType: 'HUMAN', assigneeRef: 42, assigneeName: '我', version: 1, gmtCreate: '2026-07-01', gmtModified: '2026-07-01' },
            { id: 2, title: '别人的决策', workType: 'REQ', statusName: '开发中', statusCategory: 'PENDING_DECISION', pendingDecision: true, priority: 2, assigneeType: 'HUMAN', assigneeRef: 99, assigneeName: '同事', version: 1, gmtCreate: '2026-07-01', gmtModified: '2026-07-01' },
          ]),
        });
      }),
    );

    renderPage();
    expect(await screen.findByText('我的决策')).toBeInTheDocument();
    expect(screen.getByText('别人的决策')).toBeInTheDocument();

    await userEvent.click(screen.getByText('待我决策'));

    await waitFor(() => expect(screen.queryByText('别人的决策')).not.toBeInTheDocument());
    expect(screen.getByText('我的决策')).toBeInTheDocument();
    expect(window.localStorage.getItem('autowonder.workitems.scope')).toBe('PENDING');
    expect(requests.some((query) => query.includes('pendingDecisionOnly=true'))).toBe(true);
    expect(requests.some((query) => query.includes('mineScope'))).toBe(false);
  });

  it('displays total workitem count at the top of the page', async () => {
    server.use(
      http.get('/api/workitems', () => {
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: pageData([
            { id: 1, title: '任务A', workType: 'REQ', statusName: '待处理', priority: 2, assigneeType: 'HUMAN', assigneeRef: null, assigneeName: null, version: 1, gmtCreate: '2026-07-01', gmtModified: '2026-07-01' },
          ], 42),
        });
      }),
    );

    renderPage();
    expect(await screen.findByText((_, element) => element?.textContent === '总工单数 42 个' && !Array.from(element.children).some(child => child.textContent === element.textContent))).toBeInTheDocument();
  });

  it('updates total count when workType filter changes', async () => {
    server.use(
      http.get('/api/workitems', ({ request }) => {
        const url = new URL(request.url);
        const workType = url.searchParams.get('workType');
        const total = workType === 'BUG' ? 5 : 120;
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: pageData([], total),
        });
      }),
    );

    renderPage();
    expect(await screen.findByText((_, element) => element?.textContent === '总工单数 120 个' && !Array.from(element.children).some(child => child.textContent === element.textContent))).toBeInTheDocument();

    const selectInput = document.querySelector('.ant-select-selector')!;
    await userEvent.click(selectInput);
    await userEvent.click(await screen.findByTitle('缺陷'));

    expect(await screen.findByText((_, element) => element?.textContent === '总工单数 5 个' && !Array.from(element.children).some(child => child.textContent === element.textContent))).toBeInTheDocument();
  });

  it('restores the scope preference from localStorage', async () => {
    const requests: string[] = [];
    window.localStorage.setItem('autowonder.workitems.scope', 'PENDING');
    server.use(
      http.get('/api/workitems', ({ request }) => {
        requests.push(new URL(request.url).searchParams.toString());
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: pageData([]),
        });
      }),
    );

    renderPage();

    await waitFor(() => expect(requests.some((query) => query.includes('pendingDecisionOnly=true'))).toBe(true));
  });

  it('migrates legacy pending decision key to scope preference', async () => {
    const requests: string[] = [];
    window.localStorage.setItem('autowonder.workitems.onlyMyPendingDecision', 'true');
    server.use(
      http.get('/api/workitems', ({ request }) => {
        requests.push(new URL(request.url).searchParams.toString());
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: pageData([]),
        });
      }),
    );

    renderPage();

    await waitFor(() => expect(requests.some((query) => query.includes('pendingDecisionOnly=true'))).toBe(true));
    expect(window.localStorage.getItem('autowonder.workitems.scope')).toBe('PENDING');
  });

  it('sends mineScope=CREATED when selecting 我创建的', async () => {
    const requests: string[] = [];
    server.use(
      http.get('/api/workitems', ({ request }) => {
        requests.push(new URL(request.url).searchParams.toString());
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: pageData([
            { id: 5, title: '我创建的工单', workType: 'REQ', statusName: '待处理', priority: 2, assigneeType: 'HUMAN', assigneeRef: null, assigneeName: null, version: 1, gmtCreate: '2026-07-01', gmtModified: '2026-07-01' },
          ]),
        });
      }),
    );

    renderPage();
    expect(await screen.findByText('我创建的工单')).toBeInTheDocument();

    await userEvent.click(screen.getByText('我创建的'));

    await waitFor(() => {
      const lastReq = requests[requests.length - 1];
      expect(lastReq).toContain('mineScope=CREATED');
      expect(lastReq).toContain('page=1');
      expect(lastReq).not.toContain('pendingDecisionOnly=true');
    });
    expect(window.localStorage.getItem('autowonder.workitems.scope')).toBe('CREATED');
  });

  it('sends mineScope=ASSIGNED when selecting 指派给我的', async () => {
    const requests: string[] = [];
    server.use(
      http.get('/api/workitems', ({ request }) => {
        requests.push(new URL(request.url).searchParams.toString());
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: pageData([]),
        });
      }),
    );

    renderPage();
    await screen.findByText('工单');

    await userEvent.click(screen.getByText('指派给我的'));

    await waitFor(() => {
      const lastReq = requests[requests.length - 1];
      expect(lastReq).toContain('mineScope=ASSIGNED');
      expect(lastReq).not.toContain('pendingDecisionOnly=true');
    });
    expect(window.localStorage.getItem('autowonder.workitems.scope')).toBe('ASSIGNED');
  });

  it('sends mineScope=COMMENTED when selecting 我参与的', async () => {
    const requests: string[] = [];
    server.use(
      http.get('/api/workitems', ({ request }) => {
        requests.push(new URL(request.url).searchParams.toString());
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: pageData([
            { id: 6, title: '我参与的工单', workType: 'REQ', statusName: '待处理', priority: 2, assigneeType: 'HUMAN', assigneeRef: null, assigneeName: null, version: 1, gmtCreate: '2026-07-01', gmtModified: '2026-07-01', myLastCommentAt: '2026-07-02T10:20:30Z' },
          ]),
        });
      }),
    );

    renderPage();
    expect(await screen.findByText('我参与的工单')).toBeInTheDocument();

    await userEvent.click(screen.getByText('我参与的'));

    await waitFor(() => {
      const lastReq = requests[requests.length - 1];
      expect(lastReq).toContain('mineScope=COMMENTED');
      expect(lastReq).toContain('page=1');
      expect(lastReq).not.toContain('pendingDecisionOnly=true');
    });
    expect(window.localStorage.getItem('autowonder.workitems.scope')).toBe('COMMENTED');
  });

  it('restores the remembered 我参与的 scope on remount', async () => {
    window.localStorage.setItem('autowonder.workitems.scope', 'COMMENTED');
    const requests: string[] = [];
    server.use(
      http.get('/api/workitems', ({ request }) => {
        requests.push(new URL(request.url).searchParams.toString());
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: pageData([]),
        });
      }),
    );

    renderPage();

    await waitFor(() => expect(requests.some(q => q.includes('mineScope=COMMENTED'))).toBe(true));
  });

  it('sends no scope params when selecting 全部', async () => {
    const requests: string[] = [];
    window.localStorage.setItem('autowonder.workitems.scope', 'PENDING');
    server.use(
      http.get('/api/workitems', ({ request }) => {
        requests.push(new URL(request.url).searchParams.toString());
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: pageData([]),
        });
      }),
    );

    renderPage();
    await screen.findByText('工单');

    await waitFor(() => {
      expect(requests.some((q) => q.includes('pendingDecisionOnly=true'))).toBe(true);
    });

    await userEvent.click(screen.getByText('全部'));

    await waitFor(() => {
      const lastReq = requests[requests.length - 1];
      expect(lastReq).not.toContain('pendingDecisionOnly=true');
      expect(lastReq).not.toContain('mineScope');
    });
    expect(window.localStorage.getItem('autowonder.workitems.scope')).toBe('ALL');
  });

  it('sends statusCategory param and resets page when status filter changes', async () => {
    const requests: string[] = [];
    server.use(
      http.get('/api/workitems', ({ request }) => {
        requests.push(new URL(request.url).searchParams.toString());
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: pageData([]),
        });
      }),
    );

    renderPage();
    await screen.findByText('工单');

    await waitFor(() => expect(requests.length).toBeGreaterThan(0));
    expect(requests[0]).not.toContain('statusCategory');

    const statusSelect = document.querySelectorAll('.ant-select-selector')[1]!;
    await userEvent.click(statusSelect);
    await userEvent.click(await screen.findByTitle('执行中'));

    await waitFor(() => {
      const lastReq = requests[requests.length - 1];
      expect(lastReq).toContain('statusCategory=IN_PROGRESS');
      expect(lastReq).toContain('page=1');
    });
  });

  it('offers 已取消 in the status filter and shows cancelled workitems', async () => {
    const requests: URLSearchParams[] = [];
    server.use(
      http.get('/api/workitems', ({ request }) => {
        const query = new URL(request.url).searchParams;
        requests.push(query);
        const canceled = query.get('statusCategory') === 'CANCELED';
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: pageData(canceled ? [
            { id: 88, title: '已取消工单', workType: 'REQ', statusName: '已取消', statusCategory: 'CANCELED', priority: 2, assigneeType: 'HUMAN', assigneeRef: null, assigneeName: null, version: 1, gmtCreate: '2026-07-01', gmtModified: '2026-07-01' },
          ] : []),
        });
      }),
    );

    renderPage();
    await screen.findByText('工单');

    const statusSelect = document.querySelectorAll('.ant-select-selector')[1]!;
    await userEvent.click(statusSelect);
    await userEvent.click(await screen.findByTitle('已取消'));

    await waitFor(() => {
      expect(requests.some(q => q.get('statusCategory') === 'CANCELED')).toBe(true);
    });
    expect(await screen.findByTestId('kanban-column-CANCELED')).toBeInTheDocument();
    expect(await screen.findByText('已取消工单')).toBeInTheDocument();

    await userEvent.click(screen.getByLabelText('表格视图'));
    expect(await screen.findByText('已取消工单')).toBeInTheDocument();
  });

  it('sends keyword param and resets page on search', async () => {
    const requests: string[] = [];
    server.use(
      http.get('/api/workitems', ({ request }) => {
        requests.push(new URL(request.url).searchParams.toString());
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: pageData([
            { id: 10, title: '搜索结果工单', workType: 'REQ', statusName: '待处理', priority: 2, assigneeType: 'HUMAN', assigneeRef: null, assigneeName: null, version: 1, gmtCreate: '2026-07-01', gmtModified: '2026-07-01' },
          ]),
        });
      }),
    );

    renderPage();
    expect(await screen.findByText('搜索结果工单')).toBeInTheDocument();

    const searchInput = screen.getByPlaceholderText('搜索工单ID或标题');
    await userEvent.type(searchInput, '登录{enter}');

    await waitFor(() => {
      const lastReq = requests[requests.length - 1];
      expect(lastReq).toContain('keyword=');
      expect(lastReq).toContain('page=1');
    });
  });

  it('removes keyword param when search is cleared', async () => {
    const requests: string[] = [];
    server.use(
      http.get('/api/workitems', ({ request }) => {
        requests.push(new URL(request.url).searchParams.toString());
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: pageData([]),
        });
      }),
    );

    renderPage();
    await screen.findByText('工单');

    const searchInput = screen.getByPlaceholderText('搜索工单ID或标题');
    await userEvent.type(searchInput, '测试{enter}');
    await waitFor(() => {
      expect(requests.some((q) => q.includes('keyword='))).toBe(true);
    });

    const clearBtn = document.querySelector('.ant-input-clear-icon');
    if (clearBtn) {
      await userEvent.click(clearBtn);
      await waitFor(() => {
        const lastReq = requests[requests.length - 1];
        expect(lastReq).not.toContain('keyword=');
      });
    }
  });
});

// 看板列必须按状态各自向服务端查询：否则服务端先分页、前端再按状态分类，
// 「执行中」列的内容会随全量列表的页码变化，用户只能一直翻页找执行中的工单。
describe('WorkitemListPage 看板按状态列查询', () => {
  const CARDS = [
    { id: 1, title: '待处理-A', statusName: '待处理', cat: 'NEW' },
    { id: 2, title: '待处理-B', statusName: '待处理', cat: 'NEW' },
    { id: 3, title: '执行中-A', statusName: '开发中', cat: 'IN_PROGRESS' },
    { id: 4, title: '执行中-B', statusName: '开发中', cat: 'IN_PROGRESS' },
    { id: 5, title: '已完成-A', statusName: '已完成', cat: 'DONE' },
  ].map(({ cat, ...card }) => ({
    card: {
      ...card, workType: 'REQ', priority: 2, assigneeType: 'AGENT', assigneeRef: 5,
      assigneeName: '代码助手', version: 1, gmtCreate: '2026-07-01', gmtModified: '2026-07-01',
      statusCategory: cat,
    },
    cat,
  }));

  /** 与真实服务端一致：带 statusCategory 时只返回该状态，且 total 是该状态的真实总数 */
  function useStatusAwareServer(requests: string[], totalOverride?: Record<string, number>) {
    server.use(
      http.get('/api/workitems', ({ request }) => {
        const url = new URL(request.url);
        requests.push(url.searchParams.toString());
        const statusCategory = url.searchParams.get('statusCategory');
        const size = Number(url.searchParams.get('size') ?? '50');
        const pool = statusCategory ? CARDS.filter(x => x.cat === statusCategory) : CARDS;
        const list = pool.slice(0, size).map(x => x.card);
        const total = (statusCategory && totalOverride?.[statusCategory]) ?? pool.length;
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: { list, total, pageNum: 1, pageSize: size },
        });
      }),
    );
  }

  beforeEach(() => {
    useAuthStore.getState().clear();
    window.localStorage.clear();
    vi.restoreAllMocks();
  });

  it('每个状态列各发一次带 statusCategory 的请求，且看板不再有全局分页', async () => {
    const requests: string[] = [];
    useStatusAwareServer(requests);

    renderPage();
    expect(await screen.findByText('执行中-A')).toBeInTheDocument();

    await waitFor(() => {
      for (const key of ['NEW', 'IN_PROGRESS', 'PENDING_DECISION', 'DONE']) {
        expect(requests.some(q => q.includes(`statusCategory=${key}`))).toBe(true);
      }
    });
    // 看板不再暴露全局分页，避免用户靠翻页找某个状态的工单
    expect(document.querySelector('.ant-pagination')).toBeNull();
  });

  it('执行中列一次展示该状态的工单，不受其他状态工单和页码影响', async () => {
    const requests: string[] = [];
    useStatusAwareServer(requests);

    renderPage();
    const inProgress = await screen.findByTestId('kanban-column-IN_PROGRESS');

    // 两条执行中工单同时出现在执行中列里（旧实现下第二条可能落到下一页）
    await waitFor(() => {
      expect(within(inProgress).getByText('执行中-A')).toBeInTheDocument();
      expect(within(inProgress).getByText('执行中-B')).toBeInTheDocument();
    });
    expect(within(inProgress).queryByText('待处理-A')).not.toBeInTheDocument();
    expect(within(screen.getByTestId('kanban-column-NEW')).getByText('待处理-A')).toBeInTheDocument();
  });

  it('列徽标用服务端总数，超出已加载条数时可加载更多', async () => {
    const requests: string[] = [];
    useStatusAwareServer(requests, { IN_PROGRESS: 137 });

    renderPage();
    const inProgress = await screen.findByTestId('kanban-column-IN_PROGRESS');
    await waitFor(() => expect(within(inProgress).getByText('执行中-A')).toBeInTheDocument());

    // 徽标显示该状态真实总数 137，而不是当前页命中的 2 条
    expect(within(inProgress).getByTitle('137')).toBeInTheDocument();

    await userEvent.click(within(inProgress).getByRole('button', { name: /加载更多/ }));

    await waitFor(() => {
      const inProgressRequests = requests.filter(q => q.includes('statusCategory=IN_PROGRESS'));
      expect(inProgressRequests[inProgressRequests.length - 1]).toContain('size=100');
    });
  });

  it('选择状态筛选后只展示该状态列', async () => {
    const requests: string[] = [];
    useStatusAwareServer(requests);

    renderPage();
    await screen.findByTestId('kanban-column-NEW');

    const statusSelect = document.querySelectorAll('.ant-select-selector')[1]!;
    await userEvent.click(statusSelect);
    await userEvent.click(await screen.findByTitle('执行中'));

    await waitFor(() => expect(screen.queryByTestId('kanban-column-NEW')).not.toBeInTheDocument());
    expect(screen.getByTestId('kanban-column-IN_PROGRESS')).toBeInTheDocument();
    expect(screen.queryByTestId('kanban-column-DONE')).not.toBeInTheDocument();
  });
});

function LocationProbe() {
  const location = useLocation();
  return <div data-testid="location-probe">{location.pathname}</div>;
}

function renderPageWithProbe() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/workitems']}>
        <WorkitemListPage />
        <LocationProbe />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

// 列表页表格「操作」列的关注入口此前完全没有测试覆盖：
// 这里验证它的可访问名/图标随关注态变化、点击按当前态分流 POST/DELETE、
// 点击不会误触发行跳转（stopPropagation），以及请求进行中展示 loading。
describe('WorkitemListPage 关注入口', () => {
  function watchRow(id: number, title: string, watched: boolean) {
    return {
      id, title, workType: 'REQ', statusName: '待处理', priority: 2,
      assigneeType: 'HUMAN', assigneeRef: null, assigneeName: null,
      watched, version: 1, gmtCreate: '2026-07-01', gmtModified: '2026-07-01',
    };
  }

  function listResponse(rows: unknown[]) {
    return HttpResponse.json({
      success: true, code: '0', message: '', traceId: null, data: pageData(rows),
    });
  }

  beforeEach(() => {
    useAuthStore.getState().clear();
    window.localStorage.clear();
    vi.restoreAllMocks();
  });

  it('关注入口按当前关注态展示可访问名与图标颜色', async () => {
    server.use(
      http.get('/api/workitems', () => listResponse([
        watchRow(31, '未关注行', false),
        watchRow(32, '已关注行', true),
      ])),
    );

    renderPage();
    await userEvent.click(await screen.findByLabelText('表格视图'));
    await screen.findByText('未关注行');

    const toggles = screen.getAllByTestId('workitem-watch-toggle');
    expect(toggles).toHaveLength(2);
    expect(toggles[0]).toHaveAccessibleName('关注工单');
    expect(toggles[1]).toHaveAccessibleName('取消关注工单');

    const unwatchedIcon = toggles[0].querySelector('.anticon-star')!;
    const watchedIcon = toggles[1].querySelector('.anticon-star')!;
    expect(unwatchedIcon).not.toHaveClass('aw-watch-active');
    expect(watchedIcon).toHaveClass('aw-watch-active');
  });

  it('点击未关注行的关注入口发起 POST /watch', async () => {
    const requests: Array<{ method: string; url: string }> = [];
    server.use(
      http.get('/api/workitems', () => listResponse([watchRow(11, '未关注工单', false)])),
      http.post('/api/workitems/:id/watch', ({ request }) => {
        requests.push({ method: request.method, url: new URL(request.url).pathname });
        return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: { workitemId: 11, watched: true, watcherCount: 1 } });
      }),
      http.delete('/api/workitems/:id/watch', ({ request }) => {
        requests.push({ method: request.method, url: new URL(request.url).pathname });
        return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: { workitemId: 11, watched: false, watcherCount: 0 } });
      }),
    );

    renderPage();
    await userEvent.click(await screen.findByLabelText('表格视图'));
    await userEvent.click(await screen.findByTestId('workitem-watch-toggle'));

    await waitFor(() => expect(requests).toEqual([{ method: 'POST', url: '/api/workitems/11/watch' }]));
  });

  it('点击已关注行的关注入口发起 DELETE /watch', async () => {
    const requests: Array<{ method: string; url: string }> = [];
    server.use(
      http.get('/api/workitems', () => listResponse([watchRow(12, '已关注工单', true)])),
      http.post('/api/workitems/:id/watch', ({ request }) => {
        requests.push({ method: request.method, url: new URL(request.url).pathname });
        return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: { workitemId: 12, watched: true, watcherCount: 2 } });
      }),
      http.delete('/api/workitems/:id/watch', ({ request }) => {
        requests.push({ method: request.method, url: new URL(request.url).pathname });
        return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: { workitemId: 12, watched: false, watcherCount: 1 } });
      }),
    );

    renderPage();
    await userEvent.click(await screen.findByLabelText('表格视图'));
    await userEvent.click(await screen.findByTestId('workitem-watch-toggle'));

    await waitFor(() => expect(requests).toEqual([{ method: 'DELETE', url: '/api/workitems/12/watch' }]));
  });

  it('点击关注入口不触发行跳转，而点击标题会跳转', async () => {
    const requests: Array<{ method: string; url: string }> = [];
    server.use(
      http.get('/api/workitems', () => listResponse([watchRow(13, '跳转校验工单', false)])),
      http.post('/api/workitems/:id/watch', ({ request }) => {
        requests.push({ method: request.method, url: new URL(request.url).pathname });
        return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: { workitemId: 13, watched: true, watcherCount: 1 } });
      }),
    );

    renderPageWithProbe();
    await userEvent.click(await screen.findByLabelText('表格视图'));
    expect(screen.getByTestId('location-probe')).toHaveTextContent(/^\/workitems$/);

    await userEvent.click(await screen.findByTestId('workitem-watch-toggle'));
    await waitFor(() => expect(requests).toEqual([{ method: 'POST', url: '/api/workitems/13/watch' }]));
    // 关注点击被 stopPropagation 拦截，停留在列表路由
    expect(screen.getByTestId('location-probe')).toHaveTextContent(/^\/workitems$/);

    // 正对照：标题链接确实会跳转到详情，证明探针能捕捉导航
    await userEvent.click(screen.getByText('跳转校验工单'));
    await waitFor(() => expect(screen.getByTestId('location-probe')).toHaveTextContent(/^\/workitems\/13$/));
  });

  it('关注请求进行中时入口展示 loading', async () => {
    server.use(
      http.get('/api/workitems', () => listResponse([watchRow(14, '加载态工单', false)])),
      http.post('/api/workitems/:id/watch', async () => {
        await delay('infinite');
        return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: { workitemId: 14, watched: true, watcherCount: 1 } });
      }),
    );

    renderPage();
    await userEvent.click(await screen.findByLabelText('表格视图'));
    const toggle = await screen.findByTestId('workitem-watch-toggle');
    await userEvent.click(toggle);

    await waitFor(() => expect(toggle.querySelector('.ant-btn-loading-icon')).not.toBeNull());
  });
});


describe('WorkitemListPage 拖拽流转集成', () => {
  it('checks workflow rules, submits the guarded transition and refreshes columns', async () => {
    window.localStorage.clear();
    useAuthStore.setState({ accessLevel: 'READ_WRITE' });
    let current = { id: 91, title: '拖拽集成工单', workType: 'REQ', statusName: '待处理', statusCategory: 'NEW',
      statusNodeId: 20, templateId: 10, version: 3, priority: 3, assigneeType: 'HUMAN' };
    const submitted = vi.fn();
    server.use(
      http.get('/api/workitems', ({ request }) => {
        const category = new URL(request.url).searchParams.get('statusCategory');
        const visible = !category || category === (current.statusNodeId === 20 ? 'NEW' : 'IN_PROGRESS');
        return HttpResponse.json({ success: true, data: pageData(visible ? [current] : []) });
      }),
      http.get('/api/workitems/91', () => HttpResponse.json({ success: true, data: current })),
      http.get('/api/status-templates/10', () => HttpResponse.json({ success: true, data: {
        nodes: [{ id: 21, name: '开发中', category: 'IN_PROGRESS' }], transitions: [{ fromNodeId: 20, toNodeId: 21 }],
      } })),
      http.post('/api/workitems/91/transition', async ({ request }) => {
        submitted(await request.json());
        current = { ...current, statusNodeId: 21, statusName: '开发中', statusCategory: 'IN_PROGRESS', version: 4 };
        return HttpResponse.json({ success: true, data: current });
      }),
    );
    renderPage();
    await screen.findByText('拖拽集成工单');
    const transfer = { setData: vi.fn(), effectAllowed: '', dropEffect: '' };
    fireEvent.dragStart(screen.getByTestId('workitem-card-91'), { dataTransfer: transfer });
    fireEvent.drop(screen.getByTestId('kanban-column-IN_PROGRESS'), { dataTransfer: transfer });
    await waitFor(() => expect(submitted).toHaveBeenCalledWith({ toNodeId: 21, fromNodeId: 20, expectedVersion: 3 }));
    await waitFor(() => expect(screen.getByTestId('kanban-column-IN_PROGRESS'))
      .toContainElement(screen.getByTestId('workitem-card-91')));
  });
});

// 工单列表收敛为 9 列、字段统一展示（当前处理人/中文优先级/去编号姓名/两行创建时间）：
// 这里锁定表头顺序、移除的列（SDLC、来源）以及各单元格的展示语义。
describe('WorkitemListPage 表格列与字段展示', () => {
  function fieldRow(overrides: Record<string, unknown>) {
    return {
      id: 41,
      title: '字段工单',
      workType: 'REQ',
      statusName: '开发中',
      priority: 0,
      assigneeType: 'HUMAN',
      assigneeRef: 10000,
      assigneeName: 'caihe',
      assigneeDisplayName: '蔡何（10000）',
      creatorDisplayName: '淘飞(10018)',
      version: 1,
      gmtCreate: '2026-07-01T10:20:30Z',
      gmtModified: '2026-07-01',
      ...overrides,
    };
  }

  beforeEach(() => {
    useAuthStore.getState().clear();
    window.localStorage.clear();
    vi.restoreAllMocks();
    server.use(
      http.get('/api/workitems', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: pageData([fieldRow({})]),
      })),
    );
  });

  async function renderTableView(expectedTitle = '字段工单') {
    renderPage();
    await userEvent.click(await screen.findByLabelText('表格视图'));
    await screen.findByText(expectedTitle);
  }

  it('shows exactly the ten required columns in order', async () => {
    await renderTableView();
    const headers = (await screen.findAllByRole('columnheader'))
      .map(header => header.textContent?.trim());
    expect(headers).toEqual([
      'ID', '标题', '类型', '状态', '优先级', '标签', '当前处理人', '创建者', '创建时间', '操作',
    ]);
  });

  it('keeps the SDLC and 来源 columns out of the table', async () => {
    await renderTableView();
    expect(screen.queryByRole('columnheader', { name: 'SDLC' })).not.toBeInTheDocument();
    expect(screen.queryByRole('columnheader', { name: '来源' })).not.toBeInTheDocument();
  });

  it('strips id suffixes from the assignee and creator names', async () => {
    await renderTableView();
    expect(screen.getByText('蔡何')).toBeInTheDocument();
    expect(screen.getByText('淘飞')).toBeInTheDocument();
    expect(screen.queryByText(/蔡何（10000）/)).not.toBeInTheDocument();
    expect(screen.queryByText(/淘飞\(10018\)/)).not.toBeInTheDocument();
  });

  it('keeps the AI marker for agent assignees and shows 未指派 when unassigned', async () => {
    server.use(
      http.get('/api/workitems', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: pageData([
          fieldRow({ id: 42, title: '数字员工工单', assigneeType: 'AGENT', assigneeRef: 40013, assigneeName: 'agent-40013', assigneeDisplayName: 'AW全栈开发(40013)' }),
          fieldRow({ id: 43, title: '未指派工单', assigneeRef: null, assigneeName: null, assigneeDisplayName: null }),
        ]),
      })),
    );

    await renderTableView('数字员工工单');
    expect(screen.getByText('AI')).toBeInTheDocument();
    expect(screen.getByText('AW全栈开发')).toBeInTheDocument();
    expect(screen.getByText('未指派')).toBeInTheDocument();
  });

  it('renders Chinese priority labels and falls back to 未知优先级', async () => {
    server.use(
      http.get('/api/workitems', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: pageData([
          fieldRow({ id: 44, title: '紧急工单', priority: 0 }),
          fieldRow({ id: 45, title: '低优工单', priority: 3 }),
          fieldRow({ id: 46, title: '未知工单', priority: 9 }),
        ]),
      })),
    );

    await renderTableView('紧急工单');
    expect(screen.getByText('紧急')).toBeInTheDocument();
    expect(screen.getByText('低')).toBeInTheDocument();
    expect(screen.getByText('未知优先级')).toBeInTheDocument();
  });

  it('shows the external source creator without the subject-id suffix', async () => {
    server.use(
      http.get('/api/workitems', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: pageData([
          fieldRow({
            id: 47,
            title: '外部创建工单',
            sourceType: 'EXTERNAL',
            sourceCreator: { id: 20001, provider: 'AONE', subjectId: '440501', subjectType: 'USER', displayName: '煊童', mappedUserId: null },
          }),
          fieldRow({
            id: 48,
            title: '本地兜底工单',
            creatorDisplayName: null,
            creatorName: '后备(1)',
            gmtCreate: undefined,
          }),
        ]),
      })),
    );

    await renderTableView('外部创建工单');
    expect(screen.getByText('煊童')).toBeInTheDocument();
    expect(screen.queryByText(/440501/)).not.toBeInTheDocument();
    const fallbackRow = screen.getByText('后备').closest('tr')!;
    expect(within(fallbackRow).getByText('-', { selector: 'td.aw-column-numeric' })).toBeInTheDocument();
  });

  it('renders the create time as a two-line date and time', async () => {
    await renderTableView();
    const dateLine = screen.getByText('2026/7/1');
    const timeBlock = dateLine.parentElement!;
    expect(timeBlock.children).toHaveLength(2);
    expect(dateLine.nextElementSibling?.textContent).toMatch(/^\d{1,2}:\d{2}:\d{2}$/);
  });

  it('shows my latest comment time only under 我参与的', async () => {
    server.use(
      http.get('/api/workitems', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: pageData([fieldRow({ id: 49, title: '参与工单', myLastCommentAt: '2026-07-02T10:20:30Z' })]),
      })),
    );

    await renderTableView('参与工单');
    expect(screen.queryByRole('columnheader', { name: '我最近参与时间' })).not.toBeInTheDocument();

    await userEvent.click(screen.getByText('我参与的'));

    expect(await screen.findByRole('columnheader', { name: '我最近参与时间' })).toBeInTheDocument();
    const dateLine = screen.getByText('2026/7/2');
    const timeBlock = dateLine.parentElement!;
    expect(timeBlock.children).toHaveLength(2);
    expect(dateLine.nextElementSibling?.textContent).toMatch(/^\d{1,2}:\d{2}:\d{2}$/);

    await userEvent.click(screen.getByText('全部'));
    await waitFor(() => {
      expect(screen.queryByRole('columnheader', { name: '我最近参与时间' })).not.toBeInTheDocument();
    });
  });
});

// 视图切换（看板/表格）需要立即写入 localStorage 并在重新进入时恢复；
// 无效或缺失的记录回退默认看板，且不影响现有切换行为。
describe('WorkitemListPage 视图偏好记忆', () => {
  beforeEach(() => {
    useAuthStore.getState().clear();
    window.localStorage.clear();
    vi.restoreAllMocks();
    server.use(
      http.get('/api/workitems', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: pageData([]),
      })),
    );
  });

  it('saves the view immediately when switching and restores it after remount', async () => {
    const first = renderPage();
    await screen.findByText('工单');
    await userEvent.click(screen.getByLabelText('表格视图'));
    expect(window.localStorage.getItem('autowonder.workitems.view')).toBe('table');

    // 模拟刷新：卸载后重新渲染，无需再点击即停留在表格视图
    first.unmount();
    const second = renderPage();
    expect(await screen.findByRole('columnheader', { name: '当前处理人' })).toBeInTheDocument();
    second.unmount();

    // 切回看板同样被记住
    const third = renderPage();
    await userEvent.click(await screen.findByLabelText('看板视图'));
    expect(window.localStorage.getItem('autowonder.workitems.view')).toBe('kanban');
    third.unmount();
  });

  it('falls back to the kanban view for an invalid stored preference', async () => {
    window.localStorage.setItem('autowonder.workitems.view', 'gallery');
    renderPage();
    await screen.findByText('工单');
    expect(screen.queryByRole('columnheader', { name: '当前处理人' })).not.toBeInTheDocument();
    expect(screen.getByTestId('kanban-column-NEW')).toBeInTheDocument();
  });
});

it.each(['zh-CN', 'en-US'])('final widget both workitem search buttons preserve raw keyword and trimmed tag in %s', async locale => {
 useAuthStore.getState().clear(); localStorage.clear(); localStorage.setItem('autowonder.workitems.view', 'table');
 const requests: URLSearchParams[] = [];
 server.use(http.get('/api/workitems', ({ request }) => { requests.push(new URL(request.url).searchParams); return HttpResponse.json({ success: true, data: pageData([]) }); }));
 await i18n.changeLanguage(locale); renderPage();
 try {
  const searchLabel = locale === 'en-US' ? 'Search work item ID or title' : '搜索工单ID或标题';
  const tagLabel = locale === 'en-US' ? 'Filter by tag' : '按标签筛选';
  const input = screen.getByPlaceholderText(searchLabel); const tag = screen.getByPlaceholderText(tagLabel);
  const search = screen.getByRole('button', { name: searchLabel }); const tagSearch = screen.getByRole('button', { name: tagLabel });
  await userEvent.type(input, '<raw>中文'); await userEvent.click(search);
  await waitFor(() => expect(requests.at(-1)?.get('keyword')).toBe('<raw>中文'));
  await userEvent.type(tag, '  原标签  '); await userEvent.click(tagSearch);
  await waitFor(() => expect(requests.at(-1)?.get('tag')).toBe('原标签'));
  expect(tag).toHaveValue('  原标签  ');
  await userEvent.click(screen.getByRole('button', { name: locale === 'en-US' ? 'Clear tag filter' : '清空标签筛选' }));
  await waitFor(() => expect(requests.at(-1)?.has('tag')).toBe(false)); expect(requests.at(-1)?.get('page')).toBe('1');
 } finally { await act(() => i18n.changeLanguage('zh-CN')); }
});

// 创建时间高级筛选（工单 #55696）：入口在筛选栏，条件与既有关键词等筛选以 AND 叠加，
// 表格与各看板状态列都必须携带同一份时间边界；应用/清除一律回到第 1 页，非法区间不提交。
describe('WorkitemListPage 创建时间高级筛选', () => {
  const FROM = dayjs('2026-10-01').startOf('day').toDate().toISOString();
  const TO = dayjs('2026-10-10').startOf('day').add(1, 'day').toDate().toISOString();

  const ROWS = [
    { id: 61, title: '范围工单一', workType: 'REQ', statusName: '待处理', statusCategory: 'NEW', priority: 2, assigneeType: 'HUMAN', assigneeRef: null, assigneeName: null, version: 1, gmtCreate: '2026-10-03T02:00:00Z', gmtModified: '2026-10-03T02:00:00Z' },
    { id: 62, title: '范围工单二', workType: 'REQ', statusName: '开发中', statusCategory: 'IN_PROGRESS', priority: 2, assigneeType: 'AGENT', assigneeRef: 5, assigneeName: '代码助手', version: 1, gmtCreate: '2026-10-05T02:00:00Z', gmtModified: '2026-10-05T02:00:00Z' },
  ];

  function useRecordingServer(requests: URLSearchParams[]) {
    server.use(http.get('/api/workitems', ({ request }) => {
      requests.push(new URL(request.url).searchParams);
      return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: pageData(ROWS, 25) });
    }));
  }

  async function openAdvancedFilter() {
    await userEvent.click(screen.getByRole('button', { name: /高级筛选/ }));
    return screen.findByRole('dialog');
  }

  /** 日期录入按 antd 自身的用例写法：先打开面板，改输入值后回车提交，避免依赖日历单元格。 */
  function typeDate(dialog: HTMLElement, placeholder: string, value: string) {
    const input = within(dialog).getByPlaceholderText(placeholder);
    fireEvent.mouseDown(input);
    fireEvent.focus(input);
    fireEvent.change(input, { target: { value } });
    fireEvent.keyDown(input, { key: 'Enter', code: 'Enter', keyCode: 13 });
  }

  beforeEach(() => {
    useAuthStore.getState().clear();
    window.localStorage.clear();
    vi.restoreAllMocks();
  });

  it('applies the created range to the table query and returns to the first page', async () => {
    window.localStorage.setItem('autowonder.workitems.view', 'table');
    const requests: URLSearchParams[] = [];
    useRecordingServer(requests);
    renderPage();
    await screen.findByText('范围工单一');

    await userEvent.click(await screen.findByTitle('2'));
    await waitFor(() => expect(requests.at(-1)?.get('page')).toBe('2'));

    const dialog = await openAdvancedFilter();
    typeDate(dialog, '起始日期', '2026-10-01');
    typeDate(dialog, '结束日期', '2026-10-10');
    await userEvent.click(within(dialog).getByRole('button', { name: /应\s*用/ }));

    await waitFor(() => {
      const last = requests.at(-1)!;
      expect(last.get('page')).toBe('1');
      expect(last.get('createdFrom')).toBe(FROM);
      expect(last.get('createdTo')).toBe(TO);
    });
    expect(await screen.findByTestId('workitem-created-filter-tag')).toBeInTheDocument();
  });

  it('combines the created range with the keyword filter and clears a single-sided condition', async () => {
    window.localStorage.setItem('autowonder.workitems.view', 'table');
    const requests: URLSearchParams[] = [];
    useRecordingServer(requests);
    renderPage();
    await screen.findByText('范围工单一');

    await userEvent.type(screen.getByPlaceholderText('搜索工单ID或标题'), '巡检{enter}');
    await waitFor(() => expect(requests.at(-1)?.get('keyword')).toBe('巡检'));

    const dialog = await openAdvancedFilter();
    typeDate(dialog, '起始日期', '2026-10-01');
    await userEvent.click(within(dialog).getByRole('button', { name: /应\s*用/ }));

    await waitFor(() => {
      const last = requests.at(-1)!;
      expect(last.get('createdFrom')).toBe(FROM);
      expect(last.has('createdTo')).toBe(false);
      expect(last.get('keyword')).toBe('巡检');
      expect(last.get('page')).toBe('1');
    });

    const tag = screen.getByTestId('workitem-created-filter-tag');
    expect(tag.textContent).toMatch(/^创建时间：.+ 起$/);
    await userEvent.click(screen.getByTestId('workitem-created-filter-clear'));

    await waitFor(() => {
      const last = requests.at(-1)!;
      expect(last.has('createdFrom')).toBe(false);
      expect(last.has('createdTo')).toBe(false);
      expect(last.get('keyword')).toBe('巡检');
    });
    expect(screen.queryByTestId('workitem-created-filter-tag')).not.toBeInTheDocument();
  });

  it('sends the created range to every kanban status column and the pending summary', async () => {
    useAuthStore.getState().setUser({ id: 42, username: 'zhangsan', nickname: '张三', email: '' });
    const requests: URLSearchParams[] = [];
    useRecordingServer(requests);
    renderPage();
    await screen.findByText('范围工单一');

    const dialog = await openAdvancedFilter();
    typeDate(dialog, '起始日期', '2026-10-01');
    typeDate(dialog, '结束日期', '2026-10-10');
    await userEvent.click(within(dialog).getByRole('button', { name: /应\s*用/ }));

    await waitFor(() => {
      for (const category of ['NEW', 'IN_PROGRESS', 'PENDING_DECISION', 'DONE']) {
        expect(requests.some(q => q.get('statusCategory') === category
          && q.get('createdFrom') === FROM && q.get('createdTo') === TO)).toBe(true);
      }
      // 决策人汇总是看板列的一部分，时间条件同样生效，避免汇总数与列内卡片数不一致
      expect(requests.some(q => q.get('pendingDecisionOnly') === 'true'
        && q.get('createdFrom') === FROM && q.get('createdTo') === TO)).toBe(true);
    });
  });

  it('warns instead of submitting when the start day is later than the end day', async () => {
    window.localStorage.setItem('autowonder.workitems.view', 'table');
    const requests: URLSearchParams[] = [];
    useRecordingServer(requests);
    renderPage();
    await screen.findByText('范围工单一');

    const dialog = await openAdvancedFilter();
    typeDate(dialog, '起始日期', '2026-10-10');
    typeDate(dialog, '结束日期', '2026-10-01');

    expect(within(dialog).getByRole('alert')).toHaveTextContent('起始日期不能晚于结束日期');
    expect(within(dialog).getByRole('button', { name: /应\s*用/ })).toBeDisabled();

    await userEvent.click(within(dialog).getByRole('button', { name: /取\s*消/ }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(requests.length).toBeGreaterThan(0);
    expect(requests.every(q => !q.has('createdFrom') && !q.has('createdTo'))).toBe(true);
  });
});

// 创建者高级筛选（工单 #56513）：候选来自工作空间成员，多选按 OR 生效，
// 与创建时间等条件按 AND 叠加；表格、看板各列与「待我决策」汇总列共用同一份 creatorIds。
describe('WorkitemListPage 创建者高级筛选', () => {
  const FROM = dayjs('2026-10-01').startOf('day').toDate().toISOString();
  const TO = dayjs('2026-10-10').startOf('day').add(1, 'day').toDate().toISOString();

  const ROWS = [
    { id: 61, title: '范围工单一', workType: 'REQ', statusName: '待处理', statusCategory: 'NEW', priority: 2, assigneeType: 'HUMAN', assigneeRef: null, assigneeName: null, version: 1, gmtCreate: '2026-10-03T02:00:00Z', gmtModified: '2026-10-03T02:00:00Z' },
    { id: 62, title: '范围工单二', workType: 'REQ', statusName: '开发中', statusCategory: 'IN_PROGRESS', priority: 2, assigneeType: 'AGENT', assigneeRef: 5, assigneeName: '代码助手', version: 1, gmtCreate: '2026-10-05T02:00:00Z', gmtModified: '2026-10-05T02:00:00Z' },
  ];

  const MEMBERS = [
    { userId: 12, username: 'zhangsan', email: 'zhangsan@example.com', nickname: '张三', joinedAt: '2026-01-01', owner: false, accessLevel: 'READ_WRITE', identityTags: [], roleId: 12, roleCode: 'READ_WRITE', roleName: null, readOnlyDomains: [] },
    { userId: 34, username: 'lisi', email: 'lisi@example.com', nickname: '李四', joinedAt: '2026-01-02', owner: false, accessLevel: 'READ_WRITE', identityTags: [], roleId: 12, roleCode: 'READ_WRITE', roleName: null, readOnlyDomains: [] },
  ];

  function useRecordingServer(requests: URLSearchParams[]) {
    server.use(http.get('/api/workitems', ({ request }) => {
      requests.push(new URL(request.url).searchParams);
      return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: pageData(ROWS, 25) });
    }));
  }

  function useMembersServer() {
    server.use(http.get('/api/workspaces/current/members', () => HttpResponse.json({
      success: true, code: '0', message: '', data: MEMBERS, traceId: null,
    })));
  }

  async function openAdvancedFilter() {
    await userEvent.click(screen.getByRole('button', { name: /高级筛选/ }));
    return screen.findByRole('dialog');
  }

  /** 日期录入按 antd 自身的用例写法：先打开面板，改输入值后回车提交，避免依赖日历单元格。 */
  function typeDate(dialog: HTMLElement, placeholder: string, value: string) {
    const input = within(dialog).getByPlaceholderText(placeholder);
    fireEvent.mouseDown(input);
    fireEvent.focus(input);
    fireEvent.change(input, { target: { value } });
    fireEvent.keyDown(input, { key: 'Enter', code: 'Enter', keyCode: 13 });
  }

  /** 下拉选项在隐藏无障碍列表里以 value 作为文本，这里点可见的选项内容节点。 */
  async function selectCreators(dialog: HTMLElement, labels: string[]) {
    await userEvent.click(within(dialog).getByRole('combobox', { name: '创建者' }));
    for (const label of labels) {
      const option = (await screen.findAllByText(label))
        .find((element) => element.classList.contains('ant-select-item-option-content'));
      expect(option).toBeTruthy();
      await userEvent.click(option!);
    }
  }

  beforeEach(() => {
    useAuthStore.getState().clear();
    window.localStorage.clear();
    vi.restoreAllMocks();
  });

  it('sends creatorIds to the table and to every kanban status column', async () => {
    window.localStorage.setItem('autowonder.workitems.view', 'table');
    const requests: URLSearchParams[] = [];
    useRecordingServer(requests);
    useMembersServer();
    renderPage();
    await screen.findByText('范围工单一');

    const dialog = await openAdvancedFilter();
    await selectCreators(dialog, ['张三', '李四']);
    await userEvent.click(within(dialog).getByRole('button', { name: /应\s*用/ }));

    await waitFor(() => {
      expect(requests.some(q => q.get('size') === '10' && q.get('creatorIds') === '12,34')).toBe(true);
      expect(requests.at(-1)?.get('page')).toBe('1');
    });

    await userEvent.click(screen.getByLabelText('看板视图'));
    await waitFor(() => {
      for (const category of ['NEW', 'IN_PROGRESS', 'PENDING_DECISION', 'DONE']) {
        expect(requests.some(q => q.get('statusCategory') === category
          && q.get('creatorIds') === '12,34')).toBe(true);
      }
    });
  });

  it('sends creatorIds to the pending-decision summary column', async () => {
    useAuthStore.getState().setUser({ id: 42, username: 'zhangsan', nickname: '张三', email: '' });
    const requests: URLSearchParams[] = [];
    useRecordingServer(requests);
    useMembersServer();
    renderPage();
    await screen.findByText('范围工单一');

    const dialog = await openAdvancedFilter();
    await selectCreators(dialog, ['张三']);
    await userEvent.click(within(dialog).getByRole('button', { name: /应\s*用/ }));

    await waitFor(() => {
      expect(requests.some(q => q.get('pendingDecisionOnly') === 'true'
        && q.get('creatorIds') === '12')).toBe(true);
    });
  });

  it('switches the scope back to all when creators are applied under a personal scope', async () => {
    window.localStorage.setItem('autowonder.workitems.view', 'table');
    const requests: URLSearchParams[] = [];
    useRecordingServer(requests);
    useMembersServer();
    renderPage();
    await screen.findByText('范围工单一');

    await userEvent.click(screen.getByText('我创建的'));
    await waitFor(() => expect(requests.at(-1)?.get('mineScope')).toBe('CREATED'));

    const dialog = await openAdvancedFilter();
    await selectCreators(dialog, ['张三']);
    await userEvent.click(within(dialog).getByRole('button', { name: /应\s*用/ }));

    await waitFor(() => {
      const last = requests.at(-1)!;
      expect(last.get('creatorIds')).toBe('12');
      expect(last.has('mineScope')).toBe(false);
    });
    expect(window.localStorage.getItem('autowonder.workitems.scope')).toBe('ALL');
  });

  it('combines creator and created-time conditions in one query', async () => {
    window.localStorage.setItem('autowonder.workitems.view', 'table');
    const requests: URLSearchParams[] = [];
    useRecordingServer(requests);
    useMembersServer();
    renderPage();
    await screen.findByText('范围工单一');

    const dialog = await openAdvancedFilter();
    typeDate(dialog, '起始日期', '2026-10-01');
    typeDate(dialog, '结束日期', '2026-10-10');
    await selectCreators(dialog, ['张三']);
    await userEvent.click(within(dialog).getByRole('button', { name: /应\s*用/ }));

    await waitFor(() => {
      const last = requests.at(-1)!;
      expect(last.get('creatorIds')).toBe('12');
      expect(last.get('createdFrom')).toBe(FROM);
      expect(last.get('createdTo')).toBe(TO);
    });
  });

  it('drops creatorIds from subsequent queries when the creator tag is cleared', async () => {
    window.localStorage.setItem('autowonder.workitems.view', 'table');
    const requests: URLSearchParams[] = [];
    useRecordingServer(requests);
    useMembersServer();
    renderPage();
    await screen.findByText('范围工单一');

    const dialog = await openAdvancedFilter();
    await selectCreators(dialog, ['张三']);
    await userEvent.click(within(dialog).getByRole('button', { name: /应\s*用/ }));
    await waitFor(() => expect(requests.at(-1)?.get('creatorIds')).toBe('12'));

    await userEvent.click(screen.getByTestId('workitem-creator-filter-clear'));

    await waitFor(() => expect(requests.at(-1)?.has('creatorIds')).toBe(false));
    expect(screen.queryByTestId('workitem-creator-filter-tag')).not.toBeInTheDocument();
  });
});

// 标签逻辑条件（工单 #56796）：条件序列化为 tagConditions 参数，与顶部单值 tag 及其他筛选整体 AND；
// 表格、看板各列与「待我决策」列共用同一份条件，关闭单条摘要后请求同步收窄。
describe('WorkitemListPage 标签逻辑条件高级筛选', () => {
  const FROM = dayjs('2026-10-01').startOf('day').toDate().toISOString();
  const TO = dayjs('2026-10-10').startOf('day').add(1, 'day').toDate().toISOString();

  const ROWS = [
    { id: 61, title: '范围工单一', workType: 'REQ', statusName: '待处理', statusCategory: 'NEW', priority: 2, assigneeType: 'HUMAN', assigneeRef: null, assigneeName: null, version: 1, gmtCreate: '2026-10-03T02:00:00Z', gmtModified: '2026-10-03T02:00:00Z' },
    { id: 62, title: '范围工单二', workType: 'REQ', statusName: '开发中', statusCategory: 'IN_PROGRESS', priority: 2, assigneeType: 'AGENT', assigneeRef: 5, assigneeName: '代码助手', version: 1, gmtCreate: '2026-10-05T02:00:00Z', gmtModified: '2026-10-05T02:00:00Z' },
  ];

  const TAG_OPTIONS = ['前端', '后端', 'P1'];

  const MEMBERS = [
    { userId: 12, username: 'zhangsan', email: 'zhangsan@example.com', nickname: '张三', joinedAt: '2026-01-01', owner: false, accessLevel: 'READ_WRITE', identityTags: [], roleId: 12, roleCode: 'READ_WRITE', roleName: null, readOnlyDomains: [] },
  ];

  function useRecordingServer(requests: URLSearchParams[]) {
    server.use(http.get('/api/workitems', ({ request }) => {
      requests.push(new URL(request.url).searchParams);
      return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: pageData(ROWS, 25) });
    }));
  }

  function useTagOptionsServer() {
    server.use(http.get('/api/workitems/tag-options', () => HttpResponse.json({
      success: true, code: '0', message: '', data: TAG_OPTIONS, traceId: null,
    })));
  }

  function useMembersServer() {
    server.use(http.get('/api/workspaces/current/members', () => HttpResponse.json({
      success: true, code: '0', message: '', data: MEMBERS, traceId: null,
    })));
  }

  async function openAdvancedFilter() {
    await userEvent.click(screen.getByRole('button', { name: /高级筛选/ }));
    return screen.findByRole('dialog');
  }

  /** 日期录入按 antd 自身的用例写法：先打开面板，改输入值后回车提交，避免依赖日历单元格。 */
  function typeDate(dialog: HTMLElement, placeholder: string, value: string) {
    const input = within(dialog).getByPlaceholderText(placeholder);
    fireEvent.mouseDown(input);
    fireEvent.focus(input);
    fireEvent.change(input, { target: { value } });
    fireEvent.keyDown(input, { key: 'Enter', code: 'Enter', keyCode: 13 });
  }

  /** 只点当前可见下拉里的选项，避免多个条件的选择器残留隐藏选项互相干扰。 */
  async function pickOption(label: string) {
    const dropdown = document.querySelector<HTMLElement>('.ant-select-dropdown:not(.ant-select-dropdown-hidden)');
    expect(dropdown).toBeTruthy();
    const option = (await within(dropdown!).findAllByText(label))
      .find((element) => element.classList.contains('ant-select-item-option-content'));
    expect(option).toBeTruthy();
    await userEvent.click(option!);
  }

  async function addTagCondition(dialog: HTMLElement, rowId: number, tags: string[]) {
    await userEvent.click(within(dialog).getByRole('button', { name: /添加条件/ }));
    const row = await within(dialog).findByTestId(`workitem-tag-condition-row-${rowId}`);
    await userEvent.click(within(row).getAllByRole('combobox')[1]);
    for (const tag of tags) await pickOption(tag);
  }

  async function selectCreators(dialog: HTMLElement, labels: string[]) {
    await userEvent.click(within(dialog).getByRole('combobox', { name: '创建者' }));
    for (const label of labels) await pickOption(label);
  }

  function tagConditionsOf(query: URLSearchParams) {
    const raw = query.get('tagConditions');
    return raw ? JSON.parse(raw) : null;
  }

  beforeEach(() => {
    useAuthStore.getState().clear();
    window.localStorage.clear();
    vi.restoreAllMocks();
  });

  it('sends the serialized tag conditions to the table and every kanban status column', async () => {
    window.localStorage.setItem('autowonder.workitems.view', 'table');
    const requests: URLSearchParams[] = [];
    useRecordingServer(requests);
    useTagOptionsServer();
    renderPage();
    await screen.findByText('范围工单一');

    const dialog = await openAdvancedFilter();
    await addTagCondition(dialog, 1, ['前端', 'P1']);
    await userEvent.click(within(dialog).getByRole('button', { name: /应\s*用/ }));

    await waitFor(() => {
      const last = requests.at(-1)!;
      expect(last.get('page')).toBe('1');
      expect(tagConditionsOf(last)).toEqual([{ op: 'AND', tags: ['前端', 'P1'] }]);
    });

    await userEvent.click(screen.getByLabelText('看板视图'));
    await waitFor(() => {
      for (const category of ['NEW', 'IN_PROGRESS', 'PENDING_DECISION', 'DONE']) {
        expect(requests.some(q => q.get('statusCategory') === category
          && JSON.stringify(tagConditionsOf(q)) === JSON.stringify([{ op: 'AND', tags: ['前端', 'P1'] }]))).toBe(true);
      }
    });
  });

  it('sends the same tag conditions to the pending-decision summary column', async () => {
    useAuthStore.getState().setUser({ id: 42, username: 'zhangsan', nickname: '张三', email: '' });
    const requests: URLSearchParams[] = [];
    useRecordingServer(requests);
    useTagOptionsServer();
    renderPage();
    await screen.findByText('范围工单一');

    const dialog = await openAdvancedFilter();
    await addTagCondition(dialog, 1, ['前端']);
    await userEvent.click(within(dialog).getByRole('button', { name: /应\s*用/ }));

    await waitFor(() => {
      expect(requests.some(q => q.get('pendingDecisionOnly') === 'true'
        && JSON.stringify(tagConditionsOf(q)) === JSON.stringify([{ op: 'AND', tags: ['前端'] }]))).toBe(true);
    });
  });

  it('combines tag conditions with the top-level tag, created time and creators', async () => {
    window.localStorage.setItem('autowonder.workitems.view', 'table');
    const requests: URLSearchParams[] = [];
    useRecordingServer(requests);
    useTagOptionsServer();
    useMembersServer();
    renderPage();
    await screen.findByText('范围工单一');

    const tagInput = screen.getByPlaceholderText('按标签筛选');
    await userEvent.type(tagInput, '自由标签');
    await userEvent.keyboard('{Enter}');
    await waitFor(() => expect(requests.some(q => q.get('tag') === '自由标签')).toBe(true));

    const dialog = await openAdvancedFilter();
    typeDate(dialog, '起始日期', '2026-10-01');
    typeDate(dialog, '结束日期', '2026-10-10');
    await selectCreators(dialog, ['张三']);
    await addTagCondition(dialog, 1, ['前端']);
    await userEvent.click(within(dialog).getByRole('button', { name: /应\s*用/ }));

    await waitFor(() => {
      const last = requests.at(-1)!;
      expect(last.get('tag')).toBe('自由标签');
      expect(last.get('createdFrom')).toBe(FROM);
      expect(last.get('createdTo')).toBe(TO);
      expect(last.get('creatorIds')).toBe('12');
      expect(tagConditionsOf(last)).toEqual([{ op: 'AND', tags: ['前端'] }]);
    });
  });

  it('drops only the cleared tag condition from subsequent queries', async () => {
    window.localStorage.setItem('autowonder.workitems.view', 'table');
    const requests: URLSearchParams[] = [];
    useRecordingServer(requests);
    useTagOptionsServer();
    renderPage();
    await screen.findByText('范围工单一');

    const dialog = await openAdvancedFilter();
    await addTagCondition(dialog, 1, ['前端']);
    await addTagCondition(dialog, 2, ['后端']);
    await userEvent.click(within(dialog).getByRole('button', { name: /应\s*用/ }));

    await waitFor(() => expect(tagConditionsOf(requests.at(-1)!)).toEqual([
      { op: 'AND', tags: ['前端'] },
      { op: 'AND', tags: ['后端'] },
    ]));

    await userEvent.click(screen.getByTestId('workitem-tag-condition-clear-1'));

    await waitFor(() => expect(tagConditionsOf(requests.at(-1)!)).toEqual([{ op: 'AND', tags: ['后端'] }]));
    expect(screen.getAllByTestId('workitem-tag-condition-tag')).toHaveLength(1);
  });
});


describe('WorkitemListPage 工单标签', () => {
  beforeEach(() => {
    useAuthStore.getState().clear();
    window.localStorage.clear();
    vi.restoreAllMocks();
  });

  afterEach(() => {
    message.destroy();
    useAuthStore.getState().clear();
  });

  it('shows tags in the table and filters by a suggested tag', async () => {
    const requests: URLSearchParams[] = [];
    window.localStorage.setItem('autowonder.workitems.view', 'table');
    server.use(
      http.get('/api/workitems/tag-options', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: ['发布', '线上'],
      })),
      http.get('/api/workitems', ({ request }) => {
        requests.push(new URL(request.url).searchParams);
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: pageData([{
            id: 900, title: '带标签工单', workType: 'REQ', statusCategory: 'IN_PROGRESS', statusName: '开发中',
            priority: 2, assigneeType: 'HUMAN', assigneeRef: null, assigneeName: null, tags: ['发布', '后端'],
            version: 1, gmtCreate: '2026-07-01', gmtModified: '2026-07-01',
          }]),
        });
      }),
    );

    renderPage();
    await screen.findByText('带标签工单');
    const cell = screen.getByTestId('workitem-tags-cell-900');
    expect(within(cell).getByText('发布')).toBeInTheDocument();
    expect(within(cell).getByText('后端')).toBeInTheDocument();

    const tagInput = screen.getByPlaceholderText('按标签筛选');
    await userEvent.click(tagInput);
    await userEvent.type(tagInput, '线');
    await userEvent.click(await screen.findByText('线上', { selector: '.ant-select-item-option-content' }));

    await waitFor(() => expect(requests.some((query) => query.get('tag') === '线上')).toBe(true));
    expect(tagInput).toHaveValue('线上');
  });

  it('shows tags on kanban cards', async () => {
    server.use(http.get('/api/workitems', ({ request }) => {
      const category = new URL(request.url).searchParams.get('statusCategory');
      const list = category === 'IN_PROGRESS' ? [{
        id: 901, title: '看板标签工单', workType: 'REQ', statusCategory: 'IN_PROGRESS', statusName: '开发中',
        priority: 2, assigneeType: 'HUMAN', assigneeRef: null, assigneeName: null, tags: ['发布', '线上', '后端'],
        version: 1, gmtCreate: '2026-07-01', gmtModified: '2026-07-01',
      }] : [];
      return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: pageData(list) });
    }));

    renderPage();
    const card = await screen.findByTestId('workitem-card-901');
    expect(within(card).getByText('发布')).toBeInTheDocument();
    expect(within(card).getByText('线上')).toBeInTheDocument();
    expect(within(card).getByTestId('workitem-tags-overflow')).toHaveTextContent('+1');
  });

  it('shows three tags plus an overflow counter in the table column', async () => {
    useAuthStore.setState({ accessLevel: 'READ_WRITE', readOnlyDomains: [] });
    window.localStorage.setItem('autowonder.workitems.view', 'table');
    server.use(
      http.get('/api/workitems/tag-options', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: ['标签一'],
      })),
      http.get('/api/workitems', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: pageData([{
          id: 910, title: '多标签工单', workType: 'REQ', statusCategory: 'IN_PROGRESS', statusName: '开发中',
          priority: 2, assigneeType: 'HUMAN', assigneeRef: null, assigneeName: null,
          tags: ['标签一', '标签二', '标签三', '标签四'],
          version: 1, gmtCreate: '2026-07-01', gmtModified: '2026-07-01',
        }]),
      })),
    );

    renderPage();
    await screen.findByText('多标签工单');
    const cell = screen.getByTestId('workitem-tags-cell-910');
    expect(within(cell).getByText('标签一')).toBeInTheDocument();
    expect(within(cell).getByText('标签二')).toBeInTheDocument();
    expect(within(cell).getByText('标签三')).toBeInTheDocument();
    expect(within(cell).queryByText('标签四')).not.toBeInTheDocument();
    expect(within(cell).getByTestId('workitem-tags-overflow')).toHaveTextContent('+1');
  });

  it('prompts read-only members instead of opening the writable tag editor', async () => {
    useAuthStore.setState({ accessLevel: 'READ_ONLY', readOnlyDomains: [] });
    window.localStorage.setItem('autowonder.workitems.view', 'table');
    server.use(
      http.get('/api/workitems/tag-options', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: ['发布'],
      })),
      http.get('/api/workitems', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: pageData([{
          id: 911, title: '只读标签工单', workType: 'REQ', statusCategory: 'IN_PROGRESS', statusName: '开发中',
          priority: 2, assigneeType: 'HUMAN', assigneeRef: null, assigneeName: null, tags: ['发布'],
          version: 1, gmtCreate: '2026-07-01', gmtModified: '2026-07-01',
        }]),
      })),
    );

    renderPage();
    await screen.findByText('只读标签工单');
    const cell = screen.getByTestId('workitem-tags-cell-911');
    expect(within(cell).queryByRole('button', { name: '编辑标签' })).not.toBeInTheDocument();

    await userEvent.click(within(cell).getByText('发布'));

    expect(await screen.findByText(/编辑标签需要读写权限/)).toBeInTheDocument();
    expect(screen.queryByTestId('workitem-tags-editor')).not.toBeInTheDocument();
  });

  it('keeps free-text tag filtering usable when suggestions fail to load', async () => {
    const requests: URLSearchParams[] = [];
    window.localStorage.setItem('autowonder.workitems.view', 'table');
    server.use(
      http.get('/api/workitems/tag-options', () => HttpResponse.json(
        { success: false, code: '50000', message: 'boom', traceId: null, data: null },
        { status: 500 },
      )),
      http.get('/api/workitems', ({ request }) => {
        requests.push(new URL(request.url).searchParams);
        return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: pageData([]) });
      }),
    );

    renderPage();
    expect(await screen.findByTestId('workitem-tag-options-error')).toHaveTextContent('标签联想加载失败');

    const tagInput = screen.getByPlaceholderText('按标签筛选');
    await userEvent.type(tagInput, '自由标签');
    await userEvent.keyboard('{Enter}');

    await waitFor(() => expect(requests.some((query) => query.get('tag') === '自由标签')).toBe(true));
  });
});
