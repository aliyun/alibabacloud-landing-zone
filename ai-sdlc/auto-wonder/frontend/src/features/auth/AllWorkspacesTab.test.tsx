import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { message } from 'antd';
import { beforeEach, describe, expect, it, afterAll, afterEach, beforeAll } from 'vitest';
import { server } from '@/test/mocks/server';
import { useAuthStore } from '@/shared/auth/store';
import type { WorkspaceListItem } from '@/shared/types/common';
import { AllWorkspacesTab } from './AllWorkspacesTab';

interface ListRequest {
  keyword: string | null;
  page: string | null;
  size: string | null;
}

let listRequests: ListRequest[] = [];
// Populated from MSW's request:start lifecycle event, which fires for every request in
// the order they begin — the ordering primitive behind the switch-POST assertions.
let startedRequests: string[] = [];

function recordStartedRequest({ request }: { request: Request }) {
  startedRequests.push(request.method + ' ' + request.url);
}

// The keyword reaches the network only after the 300ms debounce plus an MSW round trip, which
// does not fit Testing Library's 1000ms default once vitest runs every file in parallel.
const DEBOUNCED_FIND_TIMEOUT = 4000;

const memberWorkspace: WorkspaceListItem = {
  id: 1,
  name: '星云工坊',
  description: '多 Agent 研发协作空间',
  membershipStatus: 'MEMBER',
  accessLevel: 'ADMIN',
};

const pendingWorkspace: WorkspaceListItem = {
  id: 2,
  name: '云效集成平台',
  description: '连接 Aone 工单与执行器集群',
  membershipStatus: 'PENDING',
  accessLevel: null,
  pendingRequestId: 77,
};

const notMemberWorkspace: WorkspaceListItem = {
  id: 3,
  name: '数据中台',
  description: '离线与实时数据资产',
  membershipStatus: 'NOT_MEMBER',
  accessLevel: null,
};

function pageEnvelope(list: WorkspaceListItem[], total = list.length, pageNum = 1, pageSize = 20) {
  return {
    success: true,
    code: '0',
    message: '',
    traceId: null,
    data: { list, total, pageNum, pageSize },
  };
}

function useListHandler(
  respond: (req: ListRequest) => ReturnType<typeof pageEnvelope>,
) {
  server.use(
    http.get('/api/workspaces/all', ({ request }) => {
      const params = new URL(request.url).searchParams;
      const captured: ListRequest = {
        keyword: params.get('keyword'),
        page: params.get('page'),
        size: params.get('size'),
      };
      listRequests.push(captured);
      return HttpResponse.json(respond(captured));
    }),
  );
}

function LocationProbe() {
  const location = useLocation();
  return <span data-testid="location-path">{location.pathname}</span>;
}

function renderTab() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return {
    queryClient,
    ...render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={['/workspaces']}>
          <AllWorkspacesTab />
          <LocationProbe />
        </MemoryRouter>
      </QueryClientProvider>,
    ),
  };
}

describe('AllWorkspacesTab', () => {
  beforeAll(() => {
    server.events.on('request:start', recordStartedRequest);
  });

  afterAll(() => {
    server.events.removeListener('request:start', recordStartedRequest);
  });

  beforeEach(() => {
    listRequests = [];
    startedRequests = [];
    useAuthStore.getState().clear();
  });

  afterEach(() => {
    // antd renders toasts into its own container outside the React tree, which
    // testing-library's cleanup does not touch. Two tests here surface the same
    // failure text, so a leftover toast would otherwise cross test boundaries.
    message.destroy();
  });

  it('renders a badge and affordance per membership status', async () => {
    useListHandler(() => pageEnvelope([memberWorkspace, pendingWorkspace, notMemberWorkspace]));

    renderTab();

    const memberCard = await screen.findByTestId('all-workspace-card-1');
    const pendingCard = screen.getByTestId('all-workspace-card-2');
    const notMemberCard = screen.getByTestId('all-workspace-card-3');

    expect(within(memberCard).getByText('星云工坊')).toBeInTheDocument();
    expect(within(memberCard).getByText(/进入工作空间/)).toBeInTheDocument();
    expect(within(memberCard).queryByRole('button', { name: /申请权限/ })).not.toBeInTheDocument();

    const memberEnter = within(memberCard).getByTestId('all-workspace-enter-1');
    // Membership must be in the accessibility tree, not carried only by badge absence
    // and opacity. The accessible name has to say the user is already a member.
    expect(memberEnter).toHaveAccessibleName('进入工作空间 星云工坊（已加入）');
    expect(screen.getByRole('button', { name: /已加入/ })).toBe(memberEnter);
    // The non-member rows must not be reachable under a membership-implying name.
    expect(screen.queryAllByRole('button', { name: /已加入/ })).toHaveLength(1);
    expect(memberEnter).not.toHaveAttribute('aria-busy', 'true');
    // A plain member holds no manage rights, so no edit/delete entries ride along.
    expect(within(memberCard).queryByTestId('all-workspace-manage-area-1')).not.toBeInTheDocument();

    expect(within(pendingCard).getByText('审批中')).toBeInTheDocument();
    expect(within(pendingCard).queryByRole('button', { name: /申请权限/ })).not.toBeInTheDocument();

    expect(within(notMemberCard).getByText('未加入')).toBeInTheDocument();
    expect(within(notMemberCard).getByRole('button', { name: /申请权限/ })).toBeInTheDocument();

    // Only enterable cards expose an activation control, and that control is a nested
    // <button>; the card wrapper itself stays a plain <div> for every membership status,
    // so non-members are never reachable as buttons at all.
    expect(screen.getAllByRole('button', { name: /申请权限/ })).toHaveLength(1);
    expect(memberEnter.tagName).toBe('BUTTON');
    expect(memberCard.tagName).toBe('DIV');
    expect(pendingCard.tagName).not.toBe('BUTTON');
    expect(notMemberCard.tagName).not.toBe('BUTTON');
  });

  it('reads total, pageNum and pageSize from the response envelope', async () => {
    useListHandler(() => pageEnvelope([memberWorkspace], 57, 1, 20));

    renderTab();

    expect(await screen.findByText(/共 57 个工作空间/)).toBeInTheDocument();
    await waitFor(() => {
      expect(listRequests[0]).toMatchObject({ page: '1', size: '20' });
    });
    expect(listRequests[0].keyword).toBeNull();
  });

  it('renders the empty state when no workspace matches', async () => {
    useListHandler(() => pageEnvelope([], 0, 1, 20));

    renderTab();

    expect(await screen.findByTestId('all-workspaces-empty')).toBeInTheDocument();
    expect(screen.queryByTestId('all-workspace-card-1')).not.toBeInTheDocument();
  });

  it('shows the platform-empty wording when there are no workspaces and no keyword', async () => {
    useListHandler(() => pageEnvelope([]));

    renderTab();

    expect(await screen.findByText('平台上还没有任何工作空间')).toBeInTheDocument();
  });

  it('shows the no-match wording when a keyword filters everything out', async () => {
    const user = userEvent.setup();
    useListHandler(({ keyword }) => pageEnvelope(keyword ? [] : [notMemberWorkspace]));

    renderTab();

    expect(await screen.findByTestId('all-workspace-card-3')).toBeInTheDocument();

    await user.type(screen.getByPlaceholderText('搜索工作空间名称或描述'), '不存在的关键字');
    expect(await screen.findByText(/没有匹配「不存在的关键字」的工作空间/, {},
      { timeout: DEBOUNCED_FIND_TIMEOUT })).toBeInTheDocument();
  });

  it('debounces the keyword into a request and resets to page 1', async () => {
    const user = userEvent.setup();
    const wide = Array.from({ length: 20 }, (_, index) => ({
      ...notMemberWorkspace,
      id: 100 + index,
      name: `工作空间 ${index}`,
    }));
    useListHandler((req) => {
      if (req.keyword === 'terra') {
        return pageEnvelope([{ ...memberWorkspace, id: 900, name: 'Terra 空间' }], 1, 1, 20);
      }
      return pageEnvelope(wide, 60, Number(req.page), 20);
    });

    renderTab();
    await screen.findByTestId('all-workspace-card-100');

    await user.click(screen.getByTitle('2'));
    await waitFor(() => {
      expect(listRequests.some((req) => req.page === '2')).toBe(true);
    });

    await user.type(screen.getByRole('textbox', { name: /搜索工作空间/ }), 'terra');

    expect(await screen.findByText('Terra 空间', {},
      { timeout: DEBOUNCED_FIND_TIMEOUT })).toBeInTheDocument();
    const keywordRequests = listRequests.filter((req) => req.keyword !== null);
    const terraRequests = keywordRequests.filter((req) => req.keyword === 'terra');
    expect(terraRequests).toHaveLength(1);
    expect(terraRequests[0].page).toBe('1');
    // The real debounce guarantee: no intermediate keystroke may reach the network. A
    // pass-through useDebouncedValue would fire 't', 'te', 'ter', 'terr' as well, so
    // asserting on prefixes has headroom that a bare count comparison does not.
    expect(keywordRequests.map((req) => req.keyword)).toEqual(['terra']);
    // End the test on a settled query rather than mid-fetch. (This does not remove the
    // residual act() warnings: those were measured to come from antd's own rc-input
    // internal state during user.type, reproducible with a bare <Input> and no
    // debounce, query, or MSW in play.)
    await waitFor(() => {
      expect(screen.queryByTestId('all-workspaces-refetching')).not.toBeInTheDocument();
    });
  });

  it('hides pagination when the total fits on one page', async () => {
    useListHandler(() => pageEnvelope([memberWorkspace], 20, 1, 20));

    renderTab();

    await screen.findByTestId('all-workspace-card-1');
    expect(screen.queryByRole('listitem', { name: '2' })).not.toBeInTheDocument();
    expect(screen.queryByTitle('2')).not.toBeInTheDocument();
  });

  it('shows pagination and requests page 2 when clicked', async () => {
    const user = userEvent.setup();
    useListHandler((req) => pageEnvelope(
      [{ ...memberWorkspace, id: Number(req.page) === 2 ? 77 : 1, name: Number(req.page) === 2 ? '第二页空间' : '星云工坊' }],
      44,
      Number(req.page),
      20,
    ));

    renderTab();

    await screen.findByTestId('all-workspace-card-1');
    await user.click(screen.getByTitle('2'));

    expect(await screen.findByText('第二页空间')).toBeInTheDocument();
    await waitFor(() => {
      expect(listRequests.some((req) => req.page === '2')).toBe(true);
    });
  });

  it('switches into the workspace and navigates when a MEMBER card is clicked', async () => {
    const user = userEvent.setup();
    let switchCalls = 0;
    useListHandler(() => pageEnvelope([memberWorkspace, notMemberWorkspace]));
    server.use(
      http.post('/api/workspaces/1/switch', () => {
        switchCalls += 1;
        return HttpResponse.json({
          success: true,
          code: '0',
          message: '',
          traceId: null,
          data: { accessToken: 'switched-token', accessLevel: 'ADMIN' },
        });
      }),
    );

    renderTab();

    await user.click(await screen.findByTestId('all-workspace-enter-1'));

    await waitFor(() => {
      expect(switchCalls).toBe(1);
      expect(useAuthStore.getState().accessToken).toBe('switched-token');
      expect(useAuthStore.getState().currentWorkspace?.id).toBe(1);
      expect(useAuthStore.getState().currentWorkspace?.name).toBe('星云工坊');
      expect(useAuthStore.getState().accessLevel).toBe('ADMIN');
    });
    await waitFor(() => {
      expect(screen.getByTestId('location-path')).toHaveTextContent('/');
    });
  });

  it('resets tenant-scoped caches as part of the switch', async () => {
    const user = userEvent.setup();
    useListHandler(() => pageEnvelope([memberWorkspace]));
    server.use(
      http.post('/api/workspaces/1/switch', () => HttpResponse.json({
        success: true,
        code: '0',
        message: '',
        traceId: null,
        data: { accessToken: 'switched-token', accessLevel: 'READ_ONLY' },
      })),
    );

    const { queryClient } = renderTab();
    queryClient.setQueryData(['workitems', { page: 1, size: 20 }], { content: [{ id: 5 }] });

    await user.click(await screen.findByTestId('all-workspace-enter-1'));

    await waitFor(() => {
      expect(queryClient.getQueryData(['workitems', { page: 1, size: 20 }])).toBeUndefined();
    });
  });

  it('issues only one switch POST while a switch is already in flight', async () => {
    let switchCalls = 0;
    let releaseSwitch: (() => void) | null = null;
    const switchGate = new Promise<void>((resolve) => { releaseSwitch = resolve; });
    const secondMember = { ...memberWorkspace, id: 2, name: '第二空间' };
    useListHandler(() => pageEnvelope([memberWorkspace, secondMember]));
    server.use(
      http.post('/api/workspaces/:id/switch', async () => {
        switchCalls += 1;
        await switchGate;
        return HttpResponse.json({
          success: true,
          code: '0',
          message: '',
          traceId: null,
          data: { accessToken: 'switched-token', accessLevel: 'ADMIN' },
        });
      }),
    );

    renderTab();

    const firstEnter = await screen.findByTestId('all-workspace-enter-1');
    const secondEnter = screen.getByTestId('all-workspace-enter-2');

    // Phase 1 — declarative suppression. React flushes the busy state before the next
    // event, so `disabled` is already on the DOM and further clicks are no-ops.
    fireEvent.click(firstEnter);

    await waitFor(() => {
      expect(switchCalls).toBeGreaterThanOrEqual(1);
    });
    expect(firstEnter).toHaveAttribute('aria-busy', 'true');
    expect(firstEnter).toHaveAccessibleName('正在进入工作空间 星云工坊（已加入）');
    expect(firstEnter).toBeDisabled();
    expect(secondEnter).toBeDisabled();
    expect(secondEnter).not.toHaveAttribute('aria-busy', 'true');

    // Repeated activation of both the busy enter control and a competing MEMBER card's
    // must never add a second switch: two racing successes would pair one workspace's
    // token with another workspace's currentWorkspace, the reviewer's [1,1,2].
    fireEvent.click(firstEnter);
    fireEvent.click(secondEnter);
    fireEvent.click(firstEnter);

    expect(switchCalls).toBe(1);
    expect(startedRequests.filter((url) => url.includes('/switch')).length)
      .toBeLessThanOrEqual(1);

    releaseSwitch!();
    await waitFor(() => {
      expect(useAuthStore.getState().accessToken).toBe('switched-token');
    });
    expect(switchCalls).toBe(1);
  });

  it('guards at the handler so a same-batch double activation cannot race', async () => {
    let switchCalls = 0;
    let releaseSwitch: (() => void) | null = null;
    const switchGate = new Promise<void>((resolve) => { releaseSwitch = resolve; });
    const secondMember = { ...memberWorkspace, id: 2, name: '第二空间' };
    useListHandler(() => pageEnvelope([memberWorkspace, secondMember]));
    server.use(
      http.post('/api/workspaces/:id/switch', async () => {
        switchCalls += 1;
        await switchGate;
        return HttpResponse.json({
          success: true,
          code: '0',
          message: '',
          traceId: null,
          data: { accessToken: 'switched-token', accessLevel: 'ADMIN' },
        });
      }),
    );

    renderTab();
    const firstEnter = await screen.findByTestId('all-workspace-enter-1');
    const secondEnter = screen.getByTestId('all-workspace-enter-2');

    // `disabled` cannot cover this: both clicks are delivered inside one act() batch, so
    // React has not re-rendered either card as disabled when the second handler runs.
    // Only the synchronously-written ref makes the second call a no-op. (Note that React
    // keeps its own view of `disabled`, so stripping the DOM attribute cannot reproduce
    // this — the batch is the only way to reach the handler twice.)
    await act(async () => {
      fireEvent.click(firstEnter);
      fireEvent.click(secondEnter);
    });

    await waitFor(() => {
      expect(switchCalls).toBeGreaterThanOrEqual(1);
    });
    expect(switchCalls).toBe(1);
    // Whichever card won, the token and currentWorkspace must come from the same one.
    releaseSwitch!();
    await waitFor(() => {
      expect(useAuthStore.getState().accessToken).toBe('switched-token');
    });
    expect(useAuthStore.getState().currentWorkspace?.id).toBe(1);
    expect(switchCalls).toBe(1);
  });

  it('clears the switch guard after a failure so the grid stays usable', async () => {
    const user = userEvent.setup();
    let switchCalls = 0;
    useListHandler(() => pageEnvelope([memberWorkspace]));
    server.use(
      http.post('/api/workspaces/1/switch', () => {
        switchCalls += 1;
        return HttpResponse.json(
          {
            success: false,
            code: '11001',
            message: '你已不是该工作空间成员',
            data: null,
            traceId: 'trace-11001',
          },
          { status: 403 },
        );
      }),
      http.get('/api/workspaces/current/membership', () => HttpResponse.json({
        success: true, code: '0', message: '', data: null, traceId: null,
      })),
    );

    renderTab();
    const enter = await screen.findByTestId('all-workspace-enter-1');
    await user.click(enter);

    expect(await screen.findByText('你已不是该工作空间成员')).toBeInTheDocument();
    // A failed switch must release the guard, otherwise one error wedges the grid.
    await waitFor(() => {
      expect(enter).toBeEnabled();
    });
    expect(enter).not.toHaveAttribute('aria-busy', 'true');

    await user.click(enter);
    await waitFor(() => {
      expect(switchCalls).toBe(2);
    });
  });

  it('opens the access request modal without switching workspace', async () => {
    const user = userEvent.setup();
    let switchCalls = 0;
    useListHandler(() => pageEnvelope([notMemberWorkspace]));
    server.use(
      // A real resolver, not a bare vi.fn(): an undefined return makes MSW treat the
      // request as unhandled, which would let an accidental switch slip through.
      http.post('/api/workspaces/3/switch', () => {
        switchCalls += 1;
        return HttpResponse.json({
          success: true,
          code: '0',
          message: '',
          traceId: null,
          data: { accessToken: 'should-not-happen', accessLevel: 'READ_ONLY' },
        });
      }),
      // The ordering sentinel needs a handler of its own: the suite runs MSW with
      // onUnhandledRequest: 'error'.
      http.get('/api/__ordering_sentinel__', () => new HttpResponse(null, { status: 204 })),
    );

    renderTab();

    const card = await screen.findByTestId('all-workspace-card-3');
    await user.click(within(card).getByRole('button', { name: /申请权限/ }));

    expect(await screen.findByText('申请加入「数据中台」')).toBeInTheDocument();

    // Ordering guarantee instead of a fixed sleep: MSW's request:start fires for every
    // request as it begins, in order. A switch POST triggered by the click would be
    // started before this sentinel request that we issue afterwards, so once the
    // sentinel has been observed, any erroneous POST is necessarily already recorded.
    const sentinel = fetch('/api/__ordering_sentinel__').catch(() => undefined);
    await waitFor(() => {
      expect(startedRequests.some((url) => url.includes('__ordering_sentinel__'))).toBe(true);
    });
    await sentinel;

    expect(startedRequests.filter((url) => url.includes('/switch'))).toEqual([]);
    expect(switchCalls).toBe(0);
    expect(useAuthStore.getState().accessToken).toBeNull();
    expect(screen.getByTestId('location-path')).toHaveTextContent('/workspaces');
  });

  it('surfaces the switch failure message instead of navigating', async () => {
    const user = userEvent.setup();
    useListHandler(() => pageEnvelope([memberWorkspace]));
    server.use(
      http.post('/api/workspaces/1/switch', () => HttpResponse.json(
        {
          success: false,
          code: '11001',
          message: '你已不是该工作空间成员',
          data: null,
          traceId: 'trace-11001',
        },
        { status: 403 },
      )),
      http.get('/api/workspaces/current/membership', () => HttpResponse.json({
        success: true, code: '0', message: '', data: null, traceId: null,
      })),
    );

    renderTab();
    await user.click(await screen.findByTestId('all-workspace-enter-1'));

    expect(await screen.findByText('你已不是该工作空间成员')).toBeInTheDocument();
    expect(screen.getByTestId('location-path')).toHaveTextContent('/workspaces');
  });

  it('closes the access request modal when a refetch flips the target out of NOT_MEMBER', async () => {
    const user = userEvent.setup();
    let status: WorkspaceListItem['membershipStatus'] = 'NOT_MEMBER';
    useListHandler(() => pageEnvelope([{ ...notMemberWorkspace, membershipStatus: status }]));

    const { queryClient } = renderTab();

    const card = await screen.findByTestId('all-workspace-card-3');
    await user.click(within(card).getByRole('button', { name: /申请权限/ }));
    expect(await screen.findByText('申请加入「数据中台」')).toBeInTheDocument();

    // Out-of-band change: an admin approved the request while the dialog sat open.
    // staleTime is 0, so this refetch window is realistic rather than contrived.
    status = 'PENDING';
    await queryClient.invalidateQueries({ queryKey: ['workspaces', 'all'] });

    // The modal must not stay open offering a submission the server will reject.
    await waitFor(() => {
      expect(screen.queryByText('申请加入「数据中台」')).not.toBeInTheDocument();
    });
    // Closing silently would read as a bug, so the reason is surfaced.
    expect(await screen.findByText(/加入状态已更新/)).toBeInTheDocument();
    expect(await screen.findByText('审批中')).toBeInTheDocument();
    expect(within(screen.getByTestId('all-workspace-card-3'))
      .queryByRole('button', { name: /申请权限/ })).not.toBeInTheDocument();
  });

  it('keeps the modal open when the target merely leaves the current result page', async () => {
    const user = userEvent.setup();
    let targetOnPage = true;
    useListHandler(() => pageEnvelope(
      targetOnPage
        ? [notMemberWorkspace]
        : [{ ...notMemberWorkspace, id: 9, name: '别的空间' }],
      1,
      1,
      20,
    ));

    const { queryClient } = renderTab();

    const card = await screen.findByTestId('all-workspace-card-3');
    await user.click(within(card).getByRole('button', { name: /申请权限/ }));
    expect(await screen.findByText('申请加入「数据中台」')).toBeInTheDocument();

    // A refreshed result page can omit this row without changing membership.
    // Drive the real query refresh instead of typing behind the modal: its focus
    // trap can redirect keystrokes under full-suite load. Search/debounce has its
    // own coverage; this case checks the open dialog's response to a missing row.
    targetOnPage = false;
    await act(async () => {
      await queryClient.invalidateQueries({ queryKey: ['workspaces', 'all'] });
    });
    expect(await screen.findByText('别的空间')).toBeInTheDocument();

    expect(screen.getByText('申请加入「数据中台」')).toBeInTheDocument();
    expect(screen.queryByText(/加入状态已更新/)).not.toBeInTheDocument();
  });

  it('keeps the previous list visible while a new keyword is being fetched', async () => {
    const user = userEvent.setup();
    let releaseSecond: (() => void) | null = null;
    const secondPending = new Promise<void>((resolve) => { releaseSecond = resolve; });
    useListHandler((req) => {
      if (req.keyword === 'z') {
        return pageEnvelope([{ ...memberWorkspace, id: 500, name: '延迟结果' }], 1, 1, 20);
      }
      return pageEnvelope([memberWorkspace], 1, 1, 20);
    });

    renderTab();
    await screen.findByText('星云工坊');

    server.use(
      http.get('/api/workspaces/all', async ({ request }) => {
        const params = new URL(request.url).searchParams;
        listRequests.push({
          keyword: params.get('keyword'),
          page: params.get('page'),
          size: params.get('size'),
        });
        await secondPending;
        return HttpResponse.json(pageEnvelope([{ ...memberWorkspace, id: 500, name: '延迟结果' }], 1, 1, 20));
      }),
    );

    await user.type(screen.getByRole('textbox', { name: /搜索工作空间/ }), 'z');

    await waitFor(() => {
      expect(listRequests.some((req) => req.keyword === 'z')).toBe(true);
    });
    // Placeholder data keeps the old row on screen instead of blanking the grid.
    expect(screen.getByText('星云工坊')).toBeInTheDocument();
    expect(screen.queryByTestId('all-workspaces-loading')).not.toBeInTheDocument();
    // The in-place refetch hint is the visible payoff of keeping the rows mounted:
    // without it the grid would look frozen rather than updating.
    await waitFor(() => {
      expect(screen.getByTestId('all-workspaces-refetching')).toBeInTheDocument();
    });

    releaseSecond!();
    expect(await screen.findByText('延迟结果')).toBeInTheDocument();
    // ...and it must clear once the response lands, or it would read as a stuck spinner.
    await waitFor(() => {
      expect(screen.queryByTestId('all-workspaces-refetching')).not.toBeInTheDocument();
    });
  });

  it('shows the cancel button only on the pending card', async () => {
    useListHandler(() => pageEnvelope([memberWorkspace, pendingWorkspace, notMemberWorkspace]));

    renderTab();

    await screen.findByTestId('all-workspace-card-2');
    expect(within(screen.getByTestId('all-workspace-card-2'))
      .getByRole('button', { name: /撤销申请/ })).toBeInTheDocument();
    expect(within(screen.getByTestId('all-workspace-card-1'))
      .queryByRole('button', { name: /撤销申请/ })).not.toBeInTheDocument();
    expect(within(screen.getByTestId('all-workspace-card-3'))
      .queryByRole('button', { name: /撤销申请/ })).not.toBeInTheDocument();
  });

  it('cancels the pending request only after confirmation and refetches the list', async () => {
    const user = userEvent.setup();
    useListHandler(() => pageEnvelope([pendingWorkspace]));
    let cancelCalls = 0;
    server.use(
      http.post('/api/workspaces/2/access-requests/77/cancel', () => {
        cancelCalls += 1;
        return HttpResponse.json({
          success: true, code: '0', message: '', data: null, traceId: null,
        });
      }),
    );

    renderTab();

    const card = await screen.findByTestId('all-workspace-card-2');
    await user.click(within(card).getByRole('button', { name: /撤销申请/ }));

    // FR-3: a confirmation dialog stands between the click and the destructive call.
    expect(await screen.findByText(/确定撤销加入「云效集成平台」的申请吗/)).toBeInTheDocument();
    expect(cancelCalls).toBe(0);

    const listCallsBefore = listRequests.length;
    await user.click(screen.getByRole('button', { name: '确认撤销' }));

    expect(await screen.findByText('申请已撤销，可随时再次申请')).toBeInTheDocument();
    expect(cancelCalls).toBe(1);
    // request:start fires asynchronously via MSW's emitter, so poll instead of
    // asserting the instant the success toast appears. The match is origin-agnostic
    // because jsdom's base URL (port included) is an environment detail.
    await waitFor(() => expect(
      startedRequests.filter((r) => r.startsWith('POST ')
        && r.endsWith('/api/workspaces/2/access-requests/77/cancel')),
    ).toHaveLength(1));
    // The success path must invalidate the discovery list so the card flips back to 可申请.
    await waitFor(() => expect(listRequests.length).toBeGreaterThan(listCallsBefore));
    await waitFor(() => {
      expect(screen.queryByText(/确定撤销加入「云效集成平台」的申请吗/)).not.toBeInTheDocument();
    });
  });

  it('surfaces the business error when the cancel loses the race with a review', async () => {
    const user = userEvent.setup();
    useListHandler(() => pageEnvelope([pendingWorkspace]));
    server.use(
      http.post('/api/workspaces/2/access-requests/77/cancel', () => HttpResponse.json(
        {
          success: false,
          code: '12014',
          message: '权限申请记录不存在',
          data: null,
          traceId: 'trace-12014',
        },
        { status: 400 },
      )),
    );

    renderTab();

    const card = await screen.findByTestId('all-workspace-card-2');
    await user.click(within(card).getByRole('button', { name: /撤销申请/ }));
    await user.click(await screen.findByRole('button', { name: '确认撤销' }));

    expect(await screen.findByText('权限申请记录不存在')).toBeInTheDocument();
    expect(screen.queryByText('申请已撤销，可随时再次申请')).not.toBeInTheDocument();
  });

  // F7.5. The fade is applied as an inline style on one wrapper, so read it back from the
  // inline style rather than through getComputedStyle, which jsdom fills with defaults that
  // would make an unfaded element indistinguishable from a faded one.
  function fadedBlocks(scope: HTMLElement): HTMLElement[] {
    return Array.from(scope.querySelectorAll<HTMLElement>('*'))
      .filter((element) => element.style.opacity === '0.62');
  }

  it('styles the apply control as an enabled brand button rather than a disabled one', async () => {
    useListHandler(() => pageEnvelope([memberWorkspace, pendingWorkspace, notMemberWorkspace]));

    renderTab();

    await screen.findByTestId('all-workspace-card-3');
    const apply = screen.getByTestId('apply-access-3');

    // The class is the only hook the stylesheet has: without it the four states and the
    // focus ring in workspaceLifecycle.css simply do not apply to this button.
    expect(apply).toHaveClass('aw-apply-access-button');
    // F7.4: an applicable state must not read as a disabled one.
    expect(apply).toBeEnabled();
    expect(apply).not.toHaveAttribute('aria-disabled');
    expect(apply).not.toHaveAttribute('disabled');
    expect(apply.tagName).toBe('BUTTON');
    expect(apply).toHaveAttribute('type', 'button');
    expect(apply).toHaveAccessibleName('申请权限');
  });

  it('fades only the descriptive half of a card, never the control that acts on it', async () => {
    useListHandler(() => pageEnvelope([memberWorkspace, pendingWorkspace, notMemberWorkspace]));

    renderTab();

    const notMemberCard = await screen.findByTestId('all-workspace-card-3');
    const faded = fadedBlocks(notMemberCard);
    expect(faded).toHaveLength(1);
    // The name, the description and the status badge are the informational half.
    expect(faded[0]).toContainElement(within(notMemberCard).getByText('数据中台'));
    expect(faded[0]).toContainElement(within(notMemberCard).getByText('离线与实时数据资产'));
    expect(faded[0]).toContainElement(within(notMemberCard).getByText('未加入'));
    // Fading the whole card used to drag 申请权限 down to 0.62 as well, which both drops its
    // contrast below what F7 requires and makes an actionable state read as a disabled one.
    expect(faded[0]).not.toContainElement(screen.getByTestId('apply-access-3'));
    expect(screen.getByTestId('apply-access-3').parentElement).toBe(notMemberCard);

    const pendingCard = screen.getByTestId('all-workspace-card-2');
    const pendingFaded = fadedBlocks(pendingCard);
    expect(pendingFaded).toHaveLength(1);
    expect(pendingFaded[0]).toContainElement(within(pendingCard).getByText('审批中'));
    expect(pendingFaded[0])
      .not.toContainElement(within(pendingCard).getByRole('button', { name: /撤销申请/ }));
  });

  it('does not fade a member card, whose whole surface is the enter control', async () => {
    useListHandler(() => pageEnvelope([memberWorkspace, notMemberWorkspace]));

    renderTab();

    const memberCard = await screen.findByTestId('all-workspace-card-1');
    expect(fadedBlocks(memberCard)).toEqual([]);
    expect(within(memberCard).getByTestId('all-workspace-enter-1')).toHaveStyle({ cursor: 'pointer' });

    // The non-member card has no enter handler at all, so it must not promise a click
    // that enters — the only thing to click inside it is 申请权限.
    const notMemberCard = screen.getByTestId('all-workspace-card-3');
    expect(fadedBlocks(notMemberCard)).toHaveLength(1);
    expect(within(notMemberCard).queryByTestId(/all-workspace-enter-/)).not.toBeInTheDocument();
    expect(notMemberCard.tagName).not.toBe('BUTTON');
  });

  it('gives a platform admin enter, edit and delete over a workspace they never joined', async () => {
    const user = userEvent.setup();
    useListHandler(() => pageEnvelope([{ ...notMemberWorkspace, canManage: true }]));

    renderTab();

    const card = await screen.findByTestId('all-workspace-card-3');
    // canManage is server-computed: a platform admin who never joined still gets the
    // enter affordance and the manage entries, not the join-application dead end.
    expect(within(card).queryByTestId(/apply-access-/)).not.toBeInTheDocument();
    const enter = within(card).getByTestId('all-workspace-enter-3');
    expect(enter).toBeEnabled();
    // Not a member, so the accessible name must not claim 已加入 either.
    expect(enter).toHaveAccessibleName('进入工作空间 数据中台');
    // F7.5: every action on this card is real, so nothing on it may fade.
    expect(fadedBlocks(card)).toEqual([]);
    expect(within(card).getByTestId('all-workspace-manage-area-3')).toBeInTheDocument();
    expect(within(card).getByTestId('all-workspace-edit-3'))
      .toHaveAccessibleName('编辑工作空间 数据中台');
    expect(within(card).getByTestId('all-workspace-delete-3'))
      .toHaveAccessibleName('删除工作空间 数据中台');

    // The manage entries open the shared lifecycle dialogs against this workspace.
    await user.click(within(card).getByTestId('all-workspace-edit-3'));
    expect(await screen.findByText('编辑「数据中台」')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /取\s*消/ }));
    await user.click(within(card).getByTestId('all-workspace-delete-3'));
    expect(await screen.findByText('删除「数据中台」')).toBeInTheDocument();
    expect(screen.getByTestId('workspace-delete-consequences')).toBeInTheDocument();
  });

  it('switches into the workspace from a platform-admin card without joining', async () => {
    const user = userEvent.setup();
    let switchCalls = 0;
    useListHandler(() => pageEnvelope([{ ...notMemberWorkspace, canManage: true }]));
    server.use(
      http.post('/api/workspaces/3/switch', () => {
        switchCalls += 1;
        return HttpResponse.json({
          success: true,
          code: '0',
          message: '',
          traceId: null,
          data: { accessToken: 'platform-admin-token', accessLevel: 'ADMIN' },
        });
      }),
    );

    renderTab();

    await user.click(await screen.findByTestId('all-workspace-enter-3'));

    await waitFor(() => {
      expect(switchCalls).toBe(1);
      expect(useAuthStore.getState().accessToken).toBe('platform-admin-token');
      expect(useAuthStore.getState().currentWorkspace?.id).toBe(3);
      expect(useAuthStore.getState().accessLevel).toBe('ADMIN');
    });
    await waitFor(() => {
      expect(screen.getByTestId('location-path')).toHaveTextContent('/');
    });
  });

  it('leaves the apply button reachable by keyboard', async () => {
    const user = userEvent.setup();
    useListHandler(() => pageEnvelope([memberWorkspace, notMemberWorkspace]));

    renderTab();
    await screen.findByTestId('all-workspace-card-3');

    await user.tab();
    expect(screen.getByRole('textbox', { name: /搜索工作空间/ })).toHaveFocus();
    await user.tab();
    expect(screen.getByTestId('all-workspace-enter-1')).toHaveFocus();
    // Every card wrapper is a plain <div>, so the enter control and 申请权限 are the
    // only stops — F7.3's visible focus ring has something to be visible on.
    await user.tab();
    expect(screen.getByTestId('apply-access-3')).toHaveFocus();
  });
});
