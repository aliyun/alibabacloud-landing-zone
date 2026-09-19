import { beforeEach, describe, expect, it } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import type { Notification } from '@/shared/types/notification';
import { NotificationCenterPage } from './NotificationCenterPage';
import { NotificationBell } from '@/shared/ui/NotificationBell';

function ok(data: unknown) {
  return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data });
}

let store: Notification[] = [];
let listRequests: Array<{ status: string | null; page: number; size: number }> = [];
let unreadCountCalls = 0;
let readCalls: number[] = [];
let readAllCalls = 0;
let deleteCalls: number[] = [];

function notification(overrides: Partial<Notification> = {}): Notification {
  return {
    id: 1,
    type: 'DISPATCH_ALERT',
    title: '派单失败',
    content: '工单 42 的派单执行失败，请检查执行器状态',
    link: '/workitems/42',
    refType: 'WORKITEM',
    refId: 42,
    status: 'UNREAD',
    gmtCreate: '2026-09-01T10:00:00.000Z',
    ...overrides,
  };
}

// 后端按创建时间倒序返回，这里让编号小的更新，贴近真实排序
function manyNotifications(count: number, status: Notification['status'] = 'UNREAD'): Notification[] {
  return Array.from({ length: count }, (_, index) => notification({
    id: index + 1,
    title: `通知 ${index + 1}`,
    status,
    gmtCreate: new Date(1789000000000 - index * 3600000).toISOString(),
  }));
}

function lastListRequest() {
  return listRequests[listRequests.length - 1];
}

function rows(container: HTMLElement): HTMLElement[] {
  return Array.from(container.querySelectorAll<HTMLElement>('li.ant-list-item'));
}

// 一个会随写操作变化的假后端，这样断言的是「操作后 UI 与角标真的刷新」而不是只断言请求发出
function installApi() {
  server.use(
    http.get('/api/notifications', ({ request }) => {
      const url = new URL(request.url);
      const status = url.searchParams.get('status');
      const page = Number(url.searchParams.get('page') ?? '1');
      const size = Number(url.searchParams.get('size') ?? '10');
      listRequests.push({ status, page, size });
      const filtered = status ? store.filter((n) => n.status === status) : store;
      return ok({
        items: filtered.slice((page - 1) * size, page * size),
        total: filtered.length,
      });
    }),
    http.get('/api/notifications/unread-count', () => {
      unreadCountCalls += 1;
      return ok(store.filter((n) => n.status === 'UNREAD').length);
    }),
    http.post('/api/notifications/:id/read', ({ params }) => {
      const id = Number(params.id);
      readCalls.push(id);
      const target = store.find((n) => n.id === id);
      if (target) target.status = 'READ';
      return ok(null);
    }),
    http.post('/api/notifications/read-all', () => {
      readAllCalls += 1;
      store = store.map((n) => ({ ...n, status: 'READ' as const }));
      return ok(null);
    }),
    http.delete('/api/notifications/:id', ({ params }) => {
      const id = Number(params.id);
      deleteCalls.push(id);
      store = store.filter((n) => n.id !== id);
      return ok(null);
    }),
  );
}

function renderPage(options: { withBell?: boolean } = {}) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/notifications']}>
        <Routes>
          <Route
            path="/notifications"
            element={
              <>
                {options.withBell ? <NotificationBell /> : null}
                <NotificationCenterPage />
              </>
            }
          />
          <Route path="/workitems/:id" element={<div data-testid="workitem-page">workitem page</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  store = [];
  listRequests = [];
  unreadCountCalls = 0;
  readCalls = [];
  readAllCalls = 0;
  deleteCalls = [];
  installApi();
});

describe('NotificationCenterPage', () => {
  it('defaults to the 全部 tab and requests the list without a status filter', async () => {
    store = [
      notification({ id: 1, title: '未读一', status: 'UNREAD' }),
      notification({ id: 2, title: '已读一', status: 'READ' }),
    ];
    renderPage();

    // 默认「全部」意味着两种状态的行都要出现，且请求不带 status
    expect(await screen.findByText('未读一')).toBeInTheDocument();
    expect(screen.getByText('已读一')).toBeInTheDocument();
    expect(listRequests[0]).toEqual({ status: null, page: 1, size: 10 });
    expect(screen.getByRole('tab', { name: '全部' })).toHaveAttribute('aria-selected', 'true');
  });

  it('shows the unread marker, title, truncated summary and time on each row', async () => {
    const longContent = 'A'.repeat(80);
    store = [
      notification({ id: 1, title: '未读一', content: longContent, status: 'UNREAD' }),
      notification({ id: 2, title: '已读一', content: '短内容', status: 'READ' }),
    ];
    const { container } = renderPage();
    await screen.findByText('未读一');

    const [unreadRow, readRow] = rows(container);
    expect(within(unreadRow).getByText('未读')).toBeInTheDocument();
    expect(within(readRow).queryByText('未读')).not.toBeInTheDocument();
    // 列表只做 60 字摘要，完整正文留给抽屉
    expect(within(unreadRow).getByText(`${'A'.repeat(60)}...`)).toBeInTheDocument();
    expect(within(unreadRow).queryByText(longContent)).not.toBeInTheDocument();
    expect(within(readRow).getByText('短内容')).toBeInTheDocument();
    expect(unreadRow.textContent).toContain(new Date('2026-09-01T10:00:00.000Z').toLocaleString('zh-CN'));
  });

  it('filters by status when switching tabs and jumps back to the first page', async () => {
    store = [notification({ id: 1, title: '未读一', status: 'UNREAD' }), notification({ id: 2, title: '已读一', status: 'READ' })];
    renderPage();
    await screen.findByText('未读一');

    await userEvent.click(screen.getByRole('tab', { name: '未读' }));
    await waitFor(() => expect(screen.queryByText('已读一')).not.toBeInTheDocument());
    expect(lastListRequest()).toEqual({ status: 'UNREAD', page: 1, size: 10 });

    await userEvent.click(screen.getByRole('tab', { name: '已读' }));
    expect(await screen.findByText('已读一')).toBeInTheDocument();
    expect(lastListRequest()).toEqual({ status: 'READ', page: 1, size: 10 });
  });

  it('paginates ten per page using the total returned by the API', async () => {
    store = manyNotifications(25);
    renderPage();
    await screen.findByText('通知 1');

    expect(screen.getByText('共 25 条')).toBeInTheDocument();
    expect(listRequests[0]).toEqual({ status: null, page: 1, size: 10 });
    expect(screen.queryByText('通知 11')).not.toBeInTheDocument();

    await userEvent.click(screen.getByTitle('3'));

    expect(await screen.findByText('通知 21')).toBeInTheDocument();
    expect(lastListRequest()).toEqual({ status: null, page: 3, size: 10 });
  });

  it('opens the drawer with the full body, type, time and related object', async () => {
    const longContent = '完整正文'.repeat(30);
    store = [notification({
      id: 7,
      title: '派单失败',
      content: longContent,
      type: 'DISPATCH_ALERT',
      refType: 'WORKITEM',
      refId: 42,
      status: 'READ',
    })];
    const { container } = renderPage();
    await screen.findByText('派单失败');

    await userEvent.click(rows(container)[0]);

    expect(await screen.findByText('通知详情')).toBeInTheDocument();
    expect(screen.getByText(longContent)).toBeInTheDocument();
    expect(screen.getByText('DISPATCH_ALERT')).toBeInTheDocument();
    expect(screen.getByText('WORKITEM #42')).toBeInTheDocument();
  });

  it('shows a dash for the related object when the notification has none', async () => {
    store = [notification({ id: 11, refType: null, refId: null, status: 'READ' })];
    const { container } = renderPage();
    await screen.findByText('派单失败');

    await userEvent.click(rows(container)[0]);

    expect(await screen.findByText('关联对象')).toBeInTheDocument();
    expect(screen.getByText('—')).toBeInTheDocument();
  });

  it('auto-marks an unread notification as read when its drawer opens', async () => {
    store = [notification({ id: 7, title: '未读一', status: 'UNREAD' })];
    const { container } = renderPage();
    await screen.findByText('未读一');
    expect(within(rows(container)[0]).getByText('未读')).toBeInTheDocument();

    await userEvent.click(rows(container)[0]);

    await waitFor(() => expect(readCalls).toEqual([7]));
    // 抽屉保持打开并展示打开时的快照，列表里的未读标识消失
    expect(screen.getByText('通知详情')).toBeInTheDocument();
    await waitFor(() => expect(within(rows(container)[0]).queryByText('未读')).not.toBeInTheDocument());
  });

  it('does not call mark-read when the opened notification is already read', async () => {
    store = [notification({ id: 8, status: 'READ' })];
    const { container } = renderPage();
    await screen.findByText('派单失败');

    await userEvent.click(rows(container)[0]);

    expect(await screen.findByText('通知详情')).toBeInTheDocument();
    expect(readCalls).toEqual([]);
  });

  it('navigates from 前往处理 when the notification carries a link', async () => {
    store = [notification({ id: 9, status: 'READ', link: '/workitems/42' })];
    const { container } = renderPage();
    await screen.findByText('派单失败');

    await userEvent.click(rows(container)[0]);
    await userEvent.click(await screen.findByRole('button', { name: '前往处理' }));

    expect(await screen.findByTestId('workitem-page')).toBeInTheDocument();
  });

  it('hides 前往处理 when the notification has no link', async () => {
    store = [notification({ id: 10, status: 'READ', link: null })];
    const { container } = renderPage();
    await screen.findByText('派单失败');

    await userEvent.click(rows(container)[0]);

    expect(await screen.findByText('通知详情')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '前往处理' })).not.toBeInTheDocument();
  });

  it('keeps the current page after the drawer is closed', async () => {
    store = manyNotifications(25, 'READ');
    const { container, baseElement } = renderPage();
    await screen.findByText('通知 1');

    await userEvent.click(screen.getByTitle('2'));
    expect(await screen.findByText('通知 11')).toBeInTheDocument();

    await userEvent.click(rows(container)[0]);
    expect(await screen.findByText('通知详情')).toBeInTheDocument();
    expect(baseElement.querySelector('.ant-drawer-open')).not.toBeNull();

    await userEvent.click(baseElement.querySelector('.ant-drawer-close') as Element);
    await waitFor(() => expect(baseElement.querySelector('.ant-drawer-open')).toBeNull());

    // 抽屉关闭后其内容仍挂在 body 上的 portal 里，断言要限定在页面容器内
    expect(within(container).getByText('通知 11')).toBeInTheDocument();
    expect(within(container).getByTitle('2')).toHaveClass('ant-pagination-item-active');
    expect(readCalls).toEqual([]);
  });

  it('keeps the active status tab after the drawer is closed', async () => {
    store = [notification({ id: 1, title: '未读一', status: 'UNREAD' }), notification({ id: 2, title: '已读一', status: 'READ' })];
    const { container, baseElement } = renderPage();
    await screen.findByText('未读一');

    await userEvent.click(screen.getByRole('tab', { name: '未读' }));
    await waitFor(() => expect(screen.queryByText('已读一')).not.toBeInTheDocument());

    await userEvent.click(rows(container)[0]);
    expect(await screen.findByText('通知详情')).toBeInTheDocument();

    await userEvent.click(baseElement.querySelector('.ant-drawer-close') as Element);
    await waitFor(() => expect(baseElement.querySelector('.ant-drawer-open')).toBeNull());

    expect(screen.getByRole('tab', { name: '未读' })).toHaveAttribute('aria-selected', 'true');
    expect(lastListRequest()).toEqual({ status: 'UNREAD', page: 1, size: 10 });
  });

  it('shows 标记已读 only on unread rows and refreshes the row and the bell badge', async () => {
    store = [
      notification({ id: 1, title: '未读一', status: 'UNREAD' }),
      notification({ id: 2, title: '已读一', status: 'READ' }),
    ];
    const { container } = renderPage({ withBell: true });
    await screen.findByText('未读一');

    const [unreadRow, readRow] = rows(container);
    expect(within(readRow).queryByRole('button', { name: '标记已读' })).not.toBeInTheDocument();

    const callsBefore = unreadCountCalls;
    await userEvent.click(within(unreadRow).getByRole('button', { name: '标记已读' }));

    await waitFor(() => expect(readCalls).toEqual([1]));
    await waitFor(() => expect(within(rows(container)[0]).queryByText('未读')).not.toBeInTheDocument());
    await waitFor(() => expect(unreadCountCalls).toBeGreaterThan(callsBefore));
  });

  it('does not open the drawer when an action button is clicked', async () => {
    store = [notification({ id: 1, title: '未读一', status: 'UNREAD' })];
    const { container } = renderPage();
    await screen.findByText('未读一');

    await userEvent.click(within(rows(container)[0]).getByRole('button', { name: '标记已读' }));

    await waitFor(() => expect(readCalls).toEqual([1]));
    expect(screen.queryByText('通知详情')).not.toBeInTheDocument();
  });

  it('marks everything read and clears every unread marker', async () => {
    store = [
      notification({ id: 1, title: '未读一', status: 'UNREAD' }),
      notification({ id: 2, title: '未读二', status: 'UNREAD' }),
    ];
    const { container } = renderPage({ withBell: true });
    await screen.findByText('未读一');
    expect(screen.getByText('共 2 条')).toBeInTheDocument();

    const callsBefore = unreadCountCalls;
    await userEvent.click(screen.getByRole('button', { name: '全部已读' }));

    await waitFor(() => expect(readAllCalls).toBe(1));
    await waitFor(() => {
      expect(rows(container).some((row) => within(row).queryByText('未读') !== null)).toBe(false);
    });
    await waitFor(() => expect(unreadCountCalls).toBeGreaterThan(callsBefore));
  });

  it('asks for confirmation before deleting and keeps the row when cancelled', async () => {
    store = [notification({ id: 1, title: '未读一', status: 'UNREAD' })];
    const { container } = renderPage();
    await screen.findByText('未读一');

    await userEvent.click(within(rows(container)[0]).getByRole('button', { name: '删除' }));
    expect(await screen.findByText('确认删除这条通知？')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '确认删除' })).toBeInTheDocument();
    // 点「删除」只能弹二次确认，不能直接发出删除请求
    expect(deleteCalls).toEqual([]);

    // antd 会给两个汉字的普通按钮插入空格，可访问名是「取 消」
    await userEvent.click(screen.getByRole('button', { name: /取\s*消/ }));

    expect(deleteCalls).toEqual([]);
    expect(within(rows(container)[0]).getByText('未读一')).toBeInTheDocument();
  });

  it('deletes after confirmation and refreshes the list total and the bell badge', async () => {
    store = [
      notification({ id: 1, title: '未读一', status: 'UNREAD' }),
      notification({ id: 2, title: '已读一', status: 'READ' }),
    ];
    const { container } = renderPage({ withBell: true });
    await screen.findByText('未读一');
    expect(screen.getByText('共 2 条')).toBeInTheDocument();

    const callsBefore = unreadCountCalls;
    await userEvent.click(within(rows(container)[0]).getByRole('button', { name: '删除' }));
    await userEvent.click(await screen.findByRole('button', { name: '确认删除' }));

    await waitFor(() => expect(deleteCalls).toEqual([1]));
    expect(await screen.findByText('共 1 条')).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByText('未读一')).not.toBeInTheDocument());
    await waitFor(() => expect(unreadCountCalls).toBeGreaterThan(callsBefore));
  });

  it('shows the empty state when the current filter has no notification', async () => {
    store = [];
    renderPage();

    expect(await screen.findByText('暂无通知')).toBeInTheDocument();
  });

  it('shows a loading indicator while the list request is in flight', async () => {
    store = [notification({ id: 1, title: '未读一' })];
    let release: () => void = () => {};
    server.use(
      http.get('/api/notifications', async () => {
        await new Promise<void>((resolve) => { release = resolve; });
        return ok({ items: store, total: store.length });
      }),
    );
    const { container } = renderPage();

    await waitFor(() => expect(container.querySelector('.ant-spin-spinning')).not.toBeNull());

    release();
    expect(await screen.findByText('未读一')).toBeInTheDocument();
  });

  it('shows the failure reason with a retry action when the list request fails', async () => {
    server.use(
      http.get('/api/notifications', () => HttpResponse.json({
        success: false, code: '10500', message: '服务暂时不可用', traceId: null, data: null,
      })),
    );
    renderPage();

    expect(await screen.findByText('通知列表加载失败')).toBeInTheDocument();
    expect(screen.getByText('服务暂时不可用')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /重\s*试/ })).toBeInTheDocument();
  });

  // CR-1：末页唯一一行被删除后 total 收缩，受控 Pagination 不会自行回退，页码必须收敛到最后一页
  it('falls back to the previous page after the only row on the last page is deleted', async () => {
    store = manyNotifications(21);
    const { container } = renderPage();
    await screen.findByText('通知 1');

    await userEvent.click(screen.getByTitle('3'));
    expect(await screen.findByText('通知 21')).toBeInTheDocument();
    expect(rows(container)).toHaveLength(1);

    await userEvent.click(within(rows(container)[0]).getByRole('button', { name: '删除' }));
    await userEvent.click(await screen.findByRole('button', { name: '确认删除' }));

    await waitFor(() => expect(deleteCalls).toEqual([21]));
    expect(await screen.findByText('共 20 条')).toBeInTheDocument();
    await waitFor(() => expect(within(container).getByTitle('2')).toHaveClass('ant-pagination-item-active'));
    // 回落后必须真的把第 2 页的数据取回来，而不是留下「暂无通知」与「共 20 条」同屏的假空态
    expect(await screen.findByText('通知 11')).toBeInTheDocument();
    expect(within(container).queryByText('暂无通知')).not.toBeInTheDocument();
    expect(lastListRequest()).toEqual({ status: null, page: 2, size: 10 });
  });

  // CR-1：「未读」Tab 末页唯一一行被自动已读后离开筛选，total 同样收缩，页码要回落
  it('falls back to the first page when the last unread row on the current page is auto-read', async () => {
    store = manyNotifications(11, 'UNREAD');
    const { container, baseElement } = renderPage();
    await screen.findByText('通知 1');

    await userEvent.click(screen.getByRole('tab', { name: '未读' }));
    await waitFor(() => expect(lastListRequest()).toEqual({ status: 'UNREAD', page: 1, size: 10 }));
    await waitFor(() => expect(rows(container)).toHaveLength(10));

    await userEvent.click(screen.getByTitle('2'));
    expect(await screen.findByText('通知 11')).toBeInTheDocument();
    expect(lastListRequest()).toEqual({ status: 'UNREAD', page: 2, size: 10 });

    await userEvent.click(rows(container)[0]);

    await waitFor(() => expect(readCalls).toEqual([11]));
    expect(await screen.findByText('共 10 条')).toBeInTheDocument();
    await waitFor(() => expect(within(container).getByTitle('1')).toHaveClass('ant-pagination-item-active'));
    expect(within(container).getByText('通知 1')).toBeInTheDocument();
    expect(within(container).queryByText('暂无通知')).not.toBeInTheDocument();
    // 抽屉不因该行离开筛选而变空，仍展示打开时的快照
    expect(baseElement.querySelector('.ant-drawer-open')).not.toBeNull();
    expect(lastListRequest()).toEqual({ status: 'UNREAD', page: 1, size: 10 });
  });

  // CR-1 的钳制不得在请求在途（data 为空、total 暂为 0）时把页码打回第 1 页
  it('does not rewind the page while its own request is still in flight', async () => {
    store = manyNotifications(25);
    const { container } = renderPage();
    await screen.findByText('通知 1');

    let release: () => void = () => {};
    server.use(
      http.get('/api/notifications', async ({ request }) => {
        const url = new URL(request.url);
        const status = url.searchParams.get('status');
        const page = Number(url.searchParams.get('page') ?? '1');
        const size = Number(url.searchParams.get('size') ?? '10');
        listRequests.push({ status, page, size });
        if (page === 3) {
          await new Promise<void>((resolve) => { release = resolve; });
        }
        const filtered = status ? store.filter((n) => n.status === status) : store;
        return ok({ items: filtered.slice((page - 1) * size, page * size), total: filtered.length });
      }),
    );

    await userEvent.click(screen.getByTitle('3'));

    await waitFor(() => expect(lastListRequest()).toEqual({ status: null, page: 3, size: 10 }));
    await waitFor(() => expect(container.querySelector('.ant-spin-spinning')).not.toBeNull());
    expect(listRequests.filter((request) => request.page === 1)).toHaveLength(1);

    release();

    expect(await screen.findByText('通知 21')).toBeInTheDocument();
    expect(within(container).getByTitle('3')).toHaveClass('ant-pagination-item-active');
    // 全过程只允许出现首屏那一次第 1 页请求，钳制没有制造抖动
    expect(listRequests.filter((request) => request.page === 1)).toHaveLength(1);
    expect(lastListRequest()).toEqual({ status: null, page: 3, size: 10 });
  });

  // NB-1：抽屉宽度必须自适应，窄屏下不能沿用固定 520px 溢出视口
  it('sizes the detail drawer responsively instead of a fixed 520px', async () => {
    store = [notification({ id: 12, status: 'READ' })];
    const { container, baseElement } = renderPage();
    await screen.findByText('派单失败');

    await userEvent.click(rows(container)[0]);
    expect(await screen.findByText('通知详情')).toBeInTheDocument();

    const wrapper = baseElement.querySelector('.ant-drawer-content-wrapper') as HTMLElement;
    expect(wrapper).not.toBeNull();
    // jsdom 的 cssstyle 可能丢弃 min() 这类 CSS 数学函数，两种可观察结果都说明宽度已不是固定 520px
    expect(['min(520px, 92vw)', '']).toContain(wrapper.style.width);
  });

  // NB-2：删除请求在途时确认按钮必须禁用，否则第二次点击命中后端 0 行分支弹「删除失败」
  it('disables the delete confirmation while the delete request is in flight', async () => {
    store = [notification({ id: 1, title: '未读一', status: 'UNREAD' })];
    let release: () => void = () => {};
    server.use(
      http.delete('/api/notifications/:id', async ({ params }) => {
        const id = Number(params.id);
        deleteCalls.push(id);
        await new Promise<void>((resolve) => { release = resolve; });
        store = store.filter((n) => n.id !== id);
        return ok(null);
      }),
    );
    const { container } = renderPage();
    await screen.findByText('未读一');

    await userEvent.click(within(rows(container)[0]).getByRole('button', { name: '删除' }));
    await userEvent.click(await screen.findByRole('button', { name: /确认删除/ }));

    await waitFor(() => expect(deleteCalls).toEqual([1]));
    // onConfirm 返回 Promise 后气泡保持打开并进入 loading，禁用态才真的落到 DOM；已禁用的按钮无法再被激活，
    // 这本身就是防重复的护栏，因此这里不再去点击它（antd v5 的 loading 按钮 pointer-events:none 会让 userEvent 抛错）。
    // loading 图标带 aria-label="loading"，按钮可访问名变成「loading 确认删除」，只能按子串匹配。
    await waitFor(() => expect(screen.getByRole('button', { name: /确认删除/ })).toBeDisabled());
    expect(within(rows(container)[0]).getByRole('button', { name: '删除' })).toBeDisabled();

    release();
    await waitFor(() => expect(screen.queryByText('未读一')).not.toBeInTheDocument());
    // 全程只允许一次删除请求，也就不会有第二次命中 NOT_FOUND 后弹出的「删除失败」
    expect(deleteCalls).toEqual([1]);
    expect(screen.queryByText('删除失败')).not.toBeInTheDocument();
  });

  // NB-2：标记已读同样要在请求在途时禁用，避免重复点击发出重复请求
  it('disables 标记已读 while the mark-read request is in flight', async () => {
    store = [notification({ id: 1, title: '未读一', status: 'UNREAD' })];
    let release: () => void = () => {};
    server.use(
      http.post('/api/notifications/:id/read', async ({ params }) => {
        const id = Number(params.id);
        readCalls.push(id);
        await new Promise<void>((resolve) => { release = resolve; });
        const target = store.find((n) => n.id === id);
        if (target) target.status = 'READ';
        return ok(null);
      }),
    );
    const { container } = renderPage();
    await screen.findByText('未读一');

    const readButton = within(rows(container)[0]).getByRole('button', { name: '标记已读' });
    await userEvent.click(readButton);

    await waitFor(() => expect(readCalls).toEqual([1]));
    expect(readButton).toBeDisabled();

    await userEvent.click(readButton);
    expect(readCalls).toEqual([1]);

    release();
    await waitFor(() => expect(within(rows(container)[0]).queryByText('未读')).not.toBeInTheDocument());
    expect(readCalls).toEqual([1]);
  });
});
