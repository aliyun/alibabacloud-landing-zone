import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { Badge, message } from 'antd';
import { server } from '@/test/mocks/server';
import { useAuthStore } from '@/shared/auth/store';
import { AgentsHubPage } from './AgentsHubPage';
import { PENDING_REVIEW_COUNT_QUERY_KEY, useAgentPendingReviewCount } from './hooks';

// 本文件用真实审核面板验证「角标 ↔ 列表」联动，只打桩与审核链路无关的相邻面板，
// 避免它们的请求噪声干扰调用次数断言。
vi.mock('./AgentListPage', () => ({ AgentListPage: () => <div data-testid="agent-list-pane" /> }));
vi.mock('@/features/squad/SquadListPage', () => ({ SquadListPage: () => <div data-testid="squad-list-pane" /> }));

function pendingAgent(id: number, name: string) {
  return {
    id, name, avatarUrl: null, status: 'PENDING_REVIEW', onlineVersionId: null,
    editingVersionId: 5, latestVersionNo: 2, version: 1, gmtCreate: '2026-07-01',
  };
}

function jsonOk(data: unknown) {
  return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data });
}

// App 全局默认是 staleTime 30s、refetchOnWindowFocus false：同步行为必须只靠 hook 自身的覆盖成立，
// 所以这里刻意使用与 App 一致的 QueryClient 默认值，而不是测试里惯用的「零缓存」配置。
function makeAppLikeClient() {
  return new QueryClient({
    defaultOptions: { queries: { retry: false, staleTime: 30_000, refetchOnWindowFocus: false } },
  });
}

// 侧边栏入口角标在 AppLayout 里始终挂载，这里用探针复现同一前提：
// 角标观察者先于审核面板存在，并在面板拉回列表后收到失效重取。
function SidebarBadgeProbe() {
  const { data: count = 0 } = useAgentPendingReviewCount();
  return (
    <span data-testid="sidebar-badge">
      <Badge count={count} overflowCount={99} />
    </span>
  );
}

function renderApp(initialUrl = '/agents') {
  const client = makeAppLikeClient();
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[initialUrl]}>
        <SidebarBadgeProbe />
        <AgentsHubPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return client;
}

// antd Badge 在 count 为 0 时不渲染角标节点，因此「角标消失」等价于这里读回 0。
function sidebarBadgeCount(): number {
  const node = screen.getByTestId('sidebar-badge').querySelector('.ant-badge-count');
  return node ? Number(node.textContent) : 0;
}

function reviewsTab() {
  return screen.getByRole('tab', { name: /版本审核/ });
}

describe('审核角标与待审核列表联动', () => {
  beforeEach(() => {
    useAuthStore.getState().clear();
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_WRITE');
  });

  afterEach(() => {
    message.destroy();
  });

  it('场景A：角标缓存在 0 时，后台提交的新版本在打开审核 Tab 后同时出现在列表与角标', async () => {
    let listCalls = 0;
    let countCalls = 0;
    server.use(
      http.get('/api/agents', () => { listCalls += 1; return jsonOk([]); }),
      http.get('/api/agents/reviews/count', () => { countCalls += 1; return jsonOk(0); }),
    );

    const user = userEvent.setup();
    const client = renderApp();
    await waitFor(() => expect(client.getQueryData(PENDING_REVIEW_COUNT_QUERY_KEY)).toBe(0));
    expect(listCalls).toBe(0);
    expect(sidebarBadgeCount()).toBe(0);

    // 后台（例如 MCP 工具）提交了新版本：服务端此刻已有 1 条待审核
    server.use(
      http.get('/api/agents', () => { listCalls += 1; return jsonOk([pendingAgent(1, 'PendingBot')]); }),
      http.get('/api/agents/reviews/count', () => { countCalls += 1; return jsonOk(1); }),
    );

    await user.click(reviewsTab());

    expect(await screen.findByText('PendingBot')).toBeInTheDocument();
    await waitFor(() => expect(sidebarBadgeCount()).toBe(1));
    expect(reviewsTab().textContent).toBe('版本审核1');
    // 列表只拉一次、角标只在列表与缓存不一致时补拉一次，没有互相失效的请求环
    await waitFor(() => expect(countCalls).toBe(2));
    expect(listCalls).toBe(1);
  });

  it('场景A续：看过空列表再离开 Tab，后台提交后切回审核 Tab 会重新拉取并同步角标', async () => {
    let listCalls = 0;
    let countCalls = 0;
    server.use(
      http.get('/api/agents', () => { listCalls += 1; return jsonOk([]); }),
      http.get('/api/agents/reviews/count', () => { countCalls += 1; return jsonOk(0); }),
    );

    const user = userEvent.setup();
    renderApp('/agents?tab=reviews');
    expect(await screen.findByText(/暂无待审核的数字员工/)).toBeInTheDocument();
    expect(listCalls).toBe(1);

    await user.click(screen.getByRole('tab', { name: '数字员工' }));
    expect(screen.queryByText(/暂无待审核的数字员工/)).not.toBeInTheDocument();

    // 后台提交了新版本
    server.use(
      http.get('/api/agents', () => { listCalls += 1; return jsonOk([pendingAgent(2, 'ReturnBot')]); }),
      http.get('/api/agents/reviews/count', () => { countCalls += 1; return jsonOk(1); }),
    );

    await user.click(reviewsTab());

    expect(await screen.findByText('ReturnBot')).toBeInTheDocument();
    await waitFor(() => expect(sidebarBadgeCount()).toBe(1));
    await waitFor(() => expect(countCalls).toBe(2));
    expect(listCalls).toBe(2);
  });

  it('场景B：角标缓存在 1 时后台通过审核，切回审核 Tab 后列表为空且角标消失', async () => {
    let listCalls = 0;
    let countCalls = 0;
    server.use(
      http.get('/api/agents', () => { listCalls += 1; return jsonOk([pendingAgent(3, 'StaleBot')]); }),
      http.get('/api/agents/reviews/count', () => { countCalls += 1; return jsonOk(1); }),
    );

    const user = userEvent.setup();
    renderApp('/agents?tab=reviews');
    expect(await screen.findByText('StaleBot')).toBeInTheDocument();
    await waitFor(() => expect(sidebarBadgeCount()).toBe(1));
    expect(listCalls).toBe(1);

    await user.click(screen.getByRole('tab', { name: '数字员工' }));

    // 后台通过审核：服务端此刻 0 条待审核，但角标缓存仍是 1
    server.use(
      http.get('/api/agents', () => { listCalls += 1; return jsonOk([]); }),
      http.get('/api/agents/reviews/count', () => { countCalls += 1; return jsonOk(0); }),
    );

    await user.click(reviewsTab());

    expect(await screen.findByText(/暂无待审核的数字员工/)).toBeInTheDocument();
    await waitFor(() => expect(sidebarBadgeCount()).toBe(0));
    expect(reviewsTab().textContent).toBe('版本审核');
    await waitFor(() => expect(countCalls).toBe(2));
    expect(listCalls).toBe(2);
  });

  it('场景3：页内通过审核后列表与角标一起收敛', async () => {
    let countCalls = 0;
    server.use(
      http.get('/api/agents', () => jsonOk([pendingAgent(4, 'InlineBot')])),
      http.get('/api/agents/reviews/count', () => { countCalls += 1; return jsonOk(countCalls === 1 ? 1 : 0); }),
    );

    const user = userEvent.setup();
    renderApp('/agents?tab=reviews');
    expect(await screen.findByText('InlineBot')).toBeInTheDocument();
    await waitFor(() => expect(sidebarBadgeCount()).toBe(1));

    server.use(
      http.get('/api/agents', () => jsonOk([])),
      http.post('/api/agents/4/approve', () => jsonOk({ ...pendingAgent(4, 'InlineBot'), status: 'ONLINE' })),
    );

    await user.click(screen.getByRole('button', { name: '通过' }));

    // 审核成功提示与空列表同时收敛；hint 文案只在常驻 Alert 里出现，避免命中同名 toast
    expect(await screen.findByText(/待审核列表会自动刷新/)).toBeInTheDocument();
    expect(await screen.findByText(/暂无待审核的数字员工/)).toBeInTheDocument();
    await waitFor(() => expect(sidebarBadgeCount()).toBe(0));
    expect(reviewsTab().textContent).toBe('版本审核');
    // 通过动作会同时失效多个 key，允许合并为少量重取，但不允许出现持续增长的请求环
    expect(countCalls).toBeLessThanOrEqual(6);
  });

  it('场景3续：页内驳回后列表与角标一起收敛', async () => {
    let countCalls = 0;
    server.use(
      http.get('/api/agents', () => jsonOk([pendingAgent(5, 'RejectBot')])),
      http.get('/api/agents/reviews/count', () => { countCalls += 1; return jsonOk(countCalls === 1 ? 1 : 0); }),
    );

    const user = userEvent.setup();
    renderApp('/agents?tab=reviews');
    expect(await screen.findByText('RejectBot')).toBeInTheDocument();
    await waitFor(() => expect(sidebarBadgeCount()).toBe(1));

    server.use(
      http.get('/api/agents', () => jsonOk([])),
      http.post('/api/agents/5/reject', () => jsonOk({ ...pendingAgent(5, 'RejectBot'), status: 'REJECTED' })),
    );

    await user.click(screen.getByRole('button', { name: '驳回' }));
    const modal = await screen.findByRole('dialog');
    await user.type(within(modal).getByLabelText(/请输入驳回原因/), '内容不完整');
    await user.click(within(modal).getByRole('button', { name: '确认驳回' }));

    expect(await screen.findByText(/可在修订后重新提交审核/)).toBeInTheDocument();
    expect(await screen.findByText(/暂无待审核的数字员工/)).toBeInTheDocument();
    await waitFor(() => expect(sidebarBadgeCount()).toBe(0));
    expect(reviewsTab().textContent).toBe('版本审核');
    expect(countCalls).toBeLessThanOrEqual(6);
  });

  it('场景A续2：审核页常驻时角标刷新发现后台新提交，联动列表自动展示', async () => {
    let listCalls = 0;
    let countCalls = 0;
    server.use(
      http.get('/api/agents', () => { listCalls += 1; return jsonOk([]); }),
      http.get('/api/agents/reviews/count', () => { countCalls += 1; return jsonOk(0); }),
    );

    const client = renderApp('/agents?tab=reviews');
    expect(await screen.findByText(/暂无待审核的数字员工/)).toBeInTheDocument();
    await waitFor(() => expect(sidebarBadgeCount()).toBe(0));
    expect(listCalls).toBe(1);

    // 用户停留在审核页不动，MCP 在后台提交了新版本
    server.use(
      http.get('/api/agents', () => { listCalls += 1; return jsonOk([pendingAgent(6, 'LiveBot')]); }),
      http.get('/api/agents/reviews/count', () => { countCalls += 1; return jsonOk(1); }),
    );

    // 只驱动角标刷新（等价于 60s 轮询/窗口聚焦），列表必须由角标联动自行拉取
    await act(async () => {
      await client.refetchQueries({ queryKey: PENDING_REVIEW_COUNT_QUERY_KEY });
    });

    expect(await screen.findByText('LiveBot')).toBeInTheDocument();
    await waitFor(() => expect(sidebarBadgeCount()).toBe(1));
    expect(reviewsTab().textContent).toBe('版本审核1');
    expect(listCalls).toBe(2);
    expect(countCalls).toBe(2);
  });

  it('场景B续：审核页常驻时角标刷新发现审核完成，联动列表自动清空', async () => {
    let listCalls = 0;
    let countCalls = 0;
    server.use(
      http.get('/api/agents', () => { listCalls += 1; return jsonOk([pendingAgent(7, 'LiveGoneBot')]); }),
      http.get('/api/agents/reviews/count', () => { countCalls += 1; return jsonOk(1); }),
    );

    const client = renderApp('/agents?tab=reviews');
    expect(await screen.findByText('LiveGoneBot')).toBeInTheDocument();
    await waitFor(() => expect(sidebarBadgeCount()).toBe(1));
    expect(listCalls).toBe(1);

    // 其他成员/MCP 通过审核后，服务端已无待审核
    server.use(
      http.get('/api/agents', () => { listCalls += 1; return jsonOk([]); }),
      http.get('/api/agents/reviews/count', () => { countCalls += 1; return jsonOk(0); }),
    );

    await act(async () => {
      await client.refetchQueries({ queryKey: PENDING_REVIEW_COUNT_QUERY_KEY });
    });

    expect(await screen.findByText(/暂无待审核的数字员工/)).toBeInTheDocument();
    await waitFor(() => expect(sidebarBadgeCount()).toBe(0));
    expect(reviewsTab().textContent).toBe('版本审核');
    expect(listCalls).toBe(2);
    expect(countCalls).toBe(2);
  });
});
