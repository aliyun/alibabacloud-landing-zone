import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { message } from 'antd';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
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
    expect(screen.getByText('总工单数 6001 个')).toBeInTheDocument();
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

  it('shows 需人工（XXX）tag in kanban for a human-assigned workitem', async () => {
    server.use(
      http.get('/api/workitems', () => {
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: pageData([
            { id: 6, title: '人工处理工单', workType: 'REQ', statusName: '待处理', priority: 2, assigneeType: 'HUMAN', assigneeRef: 10000, assigneeName: 'caihe', assigneeDisplayName: '蔡何', version: 1, gmtCreate: '2026-07-05', gmtModified: '2026-07-05' },
          ]),
        });
      }),
    );

    renderPage();
    expect(await screen.findByText('人工处理工单')).toBeInTheDocument();
    expect(screen.getByText(/需人工（蔡何）/)).toBeInTheDocument();
  });

  it('shows 需人工（XXX）tag in table status column for a human-assigned workitem', async () => {
    server.use(
      http.get('/api/workitems', () => {
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: pageData([
            { id: 7, title: '表格人工工单', workType: 'REQ', statusName: '开发中', priority: 2, assigneeType: 'HUMAN', assigneeRef: 10000, assigneeName: 'caihe', assigneeDisplayName: '蔡何(10000)', version: 1, gmtCreate: '2026-07-05', gmtModified: '2026-07-05' },
          ]),
        });
      }),
    );

    renderPage();
    await userEvent.click(await screen.findByLabelText('表格视图'));
    expect(await screen.findByText('表格人工工单')).toBeInTheDocument();
    expect(screen.getByText(/需人工（蔡何）/)).toBeInTheDocument();
    expect(screen.queryByText(/需人工（蔡何\(10000\)）/)).not.toBeInTheDocument();
  });

  it('does not show 需人工 tag for agent-assigned or unassigned workitems', async () => {
    server.use(
      http.get('/api/workitems', () => {
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: pageData([
            { id: 8, title: '机器工单', workType: 'REQ', statusName: '开发中', priority: 2, assigneeType: 'AGENT', assigneeRef: 5, assigneeName: '代码助手', version: 1, gmtCreate: '2026-07-05', gmtModified: '2026-07-05' },
            { id: 9, title: '未指派工单', workType: 'REQ', statusName: '待处理', priority: 2, assigneeType: 'HUMAN', assigneeRef: null, assigneeName: null, version: 1, gmtCreate: '2026-07-05', gmtModified: '2026-07-05' },
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
            { id: 1, title: '我的决策', workType: 'REQ', statusName: '开发中', pendingDecision: true, priority: 2, assigneeType: 'HUMAN', assigneeRef: 42, assigneeName: '我', version: 1, gmtCreate: '2026-07-01', gmtModified: '2026-07-01' },
          ] : [
            { id: 1, title: '我的决策', workType: 'REQ', statusName: '开发中', pendingDecision: true, priority: 2, assigneeType: 'HUMAN', assigneeRef: 42, assigneeName: '我', version: 1, gmtCreate: '2026-07-01', gmtModified: '2026-07-01' },
            { id: 2, title: '别人的决策', workType: 'REQ', statusName: '开发中', pendingDecision: true, priority: 2, assigneeType: 'HUMAN', assigneeRef: 99, assigneeName: '同事', version: 1, gmtCreate: '2026-07-01', gmtModified: '2026-07-01' },
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
    expect(await screen.findByText('总工单数 42 个')).toBeInTheDocument();
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
    expect(await screen.findByText('总工单数 120 个')).toBeInTheDocument();

    const selectInput = document.querySelector('.ant-select-selector')!;
    await userEvent.click(selectInput);
    await userEvent.click(await screen.findByTitle('缺陷'));

    expect(await screen.findByText('总工单数 5 个')).toBeInTheDocument();
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
