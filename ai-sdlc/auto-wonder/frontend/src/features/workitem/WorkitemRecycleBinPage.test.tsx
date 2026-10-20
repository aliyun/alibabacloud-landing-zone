import { describe, it, expect, beforeEach, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { useAuthStore } from '@/shared/auth/store';
import { WorkitemRecycleBinPage } from './WorkitemRecycleBinPage';

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <WorkitemRecycleBinPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

function pageData(list: unknown[], total = list.length) {
  return { list, total, pageNum: 1, pageSize: 20 };
}

function recycledWorkitem(overrides: Record<string, unknown> = {}) {
  return {
    id: 12,
    title: '误删的需求',
    workType: 'REQ',
    statusName: '开发中',
    creatorDisplayName: '蔡何',
    deletedAt: '2026-08-20T10:00:00',
    priority: 2,
    assigneeType: 'HUMAN',
    assigneeRef: null,
    assigneeName: null,
    version: 4,
    gmtCreate: '2026-08-01',
    gmtModified: '2026-08-20',
    ...overrides,
  };
}

function okJson(data: unknown) {
  return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data });
}

describe('WorkitemRecycleBinPage', () => {
  beforeEach(() => {
    cleanup();
    useAuthStore.getState().clear();
    window.localStorage.clear();
    vi.restoreAllMocks();
  });

  it('lists recycled workitems with deletion time, pre-deletion status and creator fallbacks', async () => {
    server.use(
      http.get('/api/workitems/recycled', () => okJson(pageData([
        recycledWorkitem(),
        recycledWorkitem({ id: 13, title: '存量已删除工单', deletedAt: null, statusName: null, creatorDisplayName: null, creatorName: 'zhangsan' }),
        recycledWorkitem({ id: 14, title: '无归属工单', deletedAt: null, creatorDisplayName: null, creatorName: null }),
      ]))),
    );

    renderPage();

    expect(await screen.findByText('误删的需求')).toBeInTheDocument();
    expect(screen.getByText('存量已删除工单')).toBeInTheDocument();
    expect(screen.getByText('无归属工单')).toBeInTheDocument();
    expect(screen.getByText(/2026\/8\/20/)).toBeInTheDocument();
    expect(screen.getAllByText('未知删除时间')).toHaveLength(2);
    expect(screen.getAllByText('开发中').length).toBeGreaterThan(0);
    expect(screen.getByText('zhangsan')).toBeInTheDocument();
    expect(screen.getAllByText('-').length).toBeGreaterThan(0);
    expect(screen.getAllByText('需求').length).toBeGreaterThan(0);
  });

  it('queries only the recycled endpoint, not the regular workitem list', async () => {
    let regularListCalls = 0;
    server.use(
      http.get('/api/workitems/recycled', () => okJson(pageData([]))),
      http.get('/api/workitems', () => {
        regularListCalls += 1;
        return okJson(pageData([]));
      }),
    );

    renderPage();

    expect(await screen.findByTestId('recycle-bin-empty')).toBeInTheDocument();
    expect(regularListCalls).toBe(0);
  });

  it('shows the empty state and the keyword-aware no-match copy', async () => {
    const requests: URLSearchParams[] = [];
    server.use(
      http.get('/api/workitems/recycled', ({ request }) => {
        requests.push(new URL(request.url).searchParams);
        return okJson(pageData([]));
      }),
    );

    renderPage();

    expect(await screen.findByText('回收站为空')).toBeInTheDocument();
    await userEvent.type(screen.getByPlaceholderText('搜索工单ID或标题'), '巡检{enter}');
    expect(await screen.findByText('没有匹配「巡检」的已删除工单')).toBeInTheDocument();
    await waitFor(() => expect(requests.at(-1)?.get('keyword')).toBe('巡检'));
  });

  it('restores a workitem only after the second confirmation', async () => {
    let restoreCalls = 0;
    let restored = false;
    server.use(
      http.get('/api/workitems/recycled', () => okJson(pageData(restored ? [] : [recycledWorkitem()]))),
      http.post('/api/workitems/12/restore', () => {
        restoreCalls += 1;
        restored = true;
        return okJson(recycledWorkitem({ isDeleted: 0, deletedAt: null }));
      }),
    );
    useAuthStore.getState().setAccessLevel('READ_WRITE');

    renderPage();
    expect(await screen.findByText('误删的需求')).toBeInTheDocument();

    await userEvent.click(screen.getByTestId('restore-workitem-12'));
    expect(await screen.findByText('恢复工单「误删的需求」？')).toBeInTheDocument();
    expect(restoreCalls).toBe(0);

    const confirmButtons = screen.getAllByRole('button', { name: /恢\s*复/ });
    await userEvent.click(confirmButtons[confirmButtons.length - 1]);

    await waitFor(() => expect(restoreCalls).toBe(1));
    expect(await screen.findByText('回收站为空')).toBeInTheDocument();
  });

  it('explains that restoring only restores visibility and does not resume execution', async () => {
    server.use(
      http.get('/api/workitems/recycled', () => okJson(pageData([recycledWorkitem()]))),
    );
    useAuthStore.getState().setAccessLevel('READ_WRITE');

    renderPage();
    expect(await screen.findByText('误删的需求')).toBeInTheDocument();

    await userEvent.click(screen.getByTestId('restore-workitem-12'));

    expect(await screen.findByText('恢复后将回到删除前状态，历史产物自动可见；仅恢复可见性，不会自动恢复执行，需重新指派。')).toBeInTheDocument();
  });

  it('blocks restoring for read-only members without calling the endpoint', async () => {
    let restoreCalls = 0;
    server.use(
      http.get('/api/workitems/recycled', () => okJson(pageData([recycledWorkitem()]))),
      http.post('/api/workitems/12/restore', () => {
        restoreCalls += 1;
        return okJson(recycledWorkitem());
      }),
    );
    useAuthStore.getState().setAccessLevel('READ_ONLY');

    renderPage();
    expect(await screen.findByText('误删的需求')).toBeInTheDocument();

    await userEvent.click(screen.getByTestId('restore-workitem-12'));
    const confirmButtons = await screen.findAllByRole('button', { name: /恢\s*复/ });
    await userEvent.click(confirmButtons[confirmButtons.length - 1]);

    await waitFor(() => expect(screen.getByText(/无权限|只读/)).toBeInTheDocument());
    expect(restoreCalls).toBe(0);
  });

  it('passes the type and keyword filters to the recycled query', async () => {
    const requests: URLSearchParams[] = [];
    server.use(
      http.get('/api/workitems/recycled', ({ request }) => {
        requests.push(new URL(request.url).searchParams);
        return okJson(pageData([recycledWorkitem()]));
      }),
    );

    renderPage();
    await screen.findByText('误删的需求');

    const typeFilter = screen.getByText('类型筛选').closest('.ant-select') as HTMLElement;
    fireEvent.mouseDown(within(typeFilter).getByRole('combobox'));
    await userEvent.click(await screen.findByText('缺陷'));
    await waitFor(() => expect(requests.at(-1)?.get('workType')).toBe('BUG'));

    await userEvent.type(screen.getByPlaceholderText('搜索工单ID或标题'), '误删{enter}');
    await waitFor(() => {
      const last = requests.at(-1)!;
      expect(last.get('keyword')).toBe('误删');
      expect(last.get('workType')).toBe('BUG');
      expect(last.get('page')).toBe('1');
      expect(last.get('size')).toBe('20');
    });
  });

  it('resets to the first page when filters change and keeps the total label', async () => {
    const requests: URLSearchParams[] = [];
    server.use(
      http.get('/api/workitems/recycled', ({ request }) => {
        requests.push(new URL(request.url).searchParams);
        const page = Number(new URL(request.url).searchParams.get('page'));
        return okJson({ list: [recycledWorkitem({ id: page })], total: 45, pageNum: page, pageSize: 20 });
      }),
    );

    renderPage();
    await screen.findByText('误删的需求');

    await userEvent.click(screen.getByTitle('2'));
    await waitFor(() => expect(requests.at(-1)?.get('page')).toBe('2'));

    const search = screen.getByPlaceholderText('搜索工单ID或标题');
    await userEvent.type(search, '订{enter}');
    await waitFor(() => expect(requests.at(-1)?.get('page')).toBe('1'));
    expect(screen.getByText('共 45 条')).toBeInTheDocument();
  });

  it('renders the filter placeholder copy from the workitem locale', async () => {
    server.use(
      http.get('/api/workitems/recycled', () => okJson(pageData([recycledWorkitem()]))),
    );

    renderPage();
    await screen.findByText('误删的需求');
    expect(screen.getByText('类型筛选')).toBeInTheDocument();
    expect(within(screen.getByRole('table')).getByText('删除前状态')).toBeInTheDocument();
    expect(screen.getByText('这里只展示已删除的工单。恢复仅还原可见性，不会自动恢复执行。')).toBeInTheDocument();
  });
});
