import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
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
    expect(screen.getByText('共 6001 条')).toBeInTheDocument();
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
    useAuthStore.getState().setCurrentOrg({ id: 1, name: 'O', description: '' }, 'READ_ONLY');
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
