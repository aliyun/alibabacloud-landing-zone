import { i18n } from '@/shared/i18n';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { WorkitemClarificationPanel } from './WorkitemClarificationPanel';
import {
  clearClarificationPrefill,
  readClarificationPrefill,
  writeClarificationPrefill,
} from './prefill';
import {
  LATEST_CLARIFICATION_PATH,
  failLatestClarificationConversation,
  latestConversationResponse,
} from '@/test/mocks/clarificationLatest';
import type { ClarificationConversation } from './types';

type RealtimeCallback = (event: { type: string; payload: unknown }) => void;
const realtime = vi.hoisted(() => ({ callback: null as RealtimeCallback | null }));

vi.mock('@/shared/realtime/useRealtime', () => ({
  useRealtime: (_channel: unknown, opts: { onEvent: RealtimeCallback }) => {
    realtime.callback = opts.onEvent;
  },
}));

function ok(data: unknown) {
  return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data });
}

function conversation(
  id: number,
  agentId: number,
  content: string,
): ClarificationConversation {
  return {
    id,
    agentId,
    agentName: 'Agent-X',
    channelConversationId: `ch-${id}`,
    status: 'ACTIVE',
    executorOnline: true,
    streamingSupported: true,
    cancelSupported: true,
    cliSessionRef: null,
    processingStatus: null,
    processingTurnId: null,
    lastTurnAt: '2026-01-01T00:00:00',
    gmtCreate: '2026-01-01T00:00:00',
    turns: [{
      id: id * 10,
      direction: 'IN',
      content,
      status: 'SUCCESS',
      error: null,
      gmtCreate: '2026-01-01T00:00:00',
    }],
  };
}

interface MockOptions {
  /** 小队成员；空数组表示该数字人不属于任何小队。 */
  members?: Array<{ agentId: number; agentName: string }>;
  /** 指定数字人名下的会话列表。 */
  conversations?: ClarificationConversation[];
}

function mockSquadsAndConversations(options: MockOptions = {}) {
  const members = options.members ?? [{ agentId: 42, agentName: 'Agent-X' }];
  const list = options.conversations ?? [];
  server.use(
    http.get('/api/squads', () => ok({
      list: [{ id: 9, name: '交付小队', description: '', memberCount: members.length, gmtCreate: '' }],
      total: 1,
      pageNum: 1,
      pageSize: 100,
    })),
    http.get('/api/squads/:squadId/members', () =>
      ok(members.map((member) => ({ ...member, roleCode: 'AW_FS_DEV' }))),
    ),
    http.get('/api/workitems/:workitemId/clarification-conversations', () => ok(list)),
    http.get('/api/workitems/:workitemId/clarification-conversations/:conversationId', ({ params }) => {
      const found = list.find((item) => String(item.id) === String(params.conversationId));
      return ok(found ?? null);
    }),
    http.get('/api/workitems/:workitemId/clarification-conversations/:conversationId/events', () =>
      ok([]),
    ),
  );
}

/**
 * /latest 必须最后注册：`:conversationId` 详情 handler 也会匹配 `latest`，
 * 而 MSW 运行时 handler 后注册者优先。
 */
function mockLatest(data: Partial<ClarificationConversation> | null) {
  server.use(http.get(LATEST_CLARIFICATION_PATH, () => latestConversationResponse(data)));
}

function renderPanel() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <WorkitemClarificationPanel workitemId="100" />
    </QueryClientProvider>,
  );
}

describe('WorkitemClarificationPanel 默认打开最近一次会话（工单 57463）', () => {
  beforeEach(() => {
    window.localStorage.clear();
    realtime.callback = null;
  });

  it('AC-1/AC-8: opens the latest conversation without ever showing the selection screen', async () => {
    const latest = conversation(7, 42, '最近一次会话内容');
    mockSquadsAndConversations({ conversations: [latest] });
    let releaseLatest!: () => void;
    const gate = new Promise<void>((resolve) => { releaseLatest = resolve; });
    server.use(http.get(LATEST_CLARIFICATION_PATH, async () => {
      await gate;
      return latestConversationResponse(latest);
    }));

    renderPanel();
    // 解析窗口：只展示加载态，不渲染选人页。
    expect(screen.getByTestId('clarification-latest-loading')).toBeInTheDocument();
    expect(screen.getByText('正在打开最近一次会话…')).toBeInTheDocument();
    expect(screen.queryByText('选择小队和数字员工')).toBeNull();

    releaseLatest();

    expect(await screen.findByText('最近一次会话内容')).toBeInTheDocument();
    expect(screen.queryByText('选择小队和数字员工')).toBeNull();
    expect(screen.queryByTestId('clarification-latest-loading')).toBeNull();
  });

  it('AC-4: opens the conversation even when its agent no longer belongs to any squad', async () => {
    const latest = conversation(7, 99, '离职数字人的历史会话');
    let createCalls = 0;
    mockSquadsAndConversations({ members: [], conversations: [latest] });
    server.use(http.post('/api/workitems/:workitemId/clarification-conversations', () => {
      createCalls += 1;
      return ok(null);
    }));
    mockLatest(latest);

    renderPanel();

    expect(await screen.findByText('离职数字人的历史会话')).toBeInTheDocument();
    // 会话回来了就不该再自动创建；小队归属查不到只占位，不伪造预填。
    expect(createCalls).toBe(0);
    expect(readClarificationPrefill('100')).toBeNull();
    expect(screen.getByRole('button', { name: '切换数字员工' })).toBeInTheDocument();
  });

  it('AC-5: falls back to the local prefill and auto-creates when the workitem has no conversation', async () => {
    writeClarificationPrefill('100', { squadId: 9, agentId: 42 });
    const created = conversation(5, 42, '自动创建的会话');
    let createCalls = 0;
    mockSquadsAndConversations({ conversations: [] });
    server.use(
      http.post('/api/workitems/:workitemId/clarification-conversations', () => {
        createCalls += 1;
        return ok(created);
      }),
      http.get('/api/workitems/:workitemId/clarification-conversations/:conversationId', () =>
        ok(created),
      ),
    );
    mockLatest(null);

    renderPanel();

    expect(await screen.findByText('自动创建的会话')).toBeInTheDocument();
    expect(createCalls).toBe(1);
  });

  it('AC-7: degrades silently to the selection screen when the latest query fails', async () => {
    mockSquadsAndConversations();
    failLatestClarificationConversation(500);

    renderPanel();

    expect(await screen.findByText('选择小队和数字员工')).toBeInTheDocument();
    expect(screen.queryByTestId('clarification-latest-loading')).toBeNull();
    // 静默降级：不把查询失败暴露成错误提示。
    expect(screen.queryByText('latest failed')).toBeNull();
  });

  it('AC-10: a late result never overrides the user switching agents during resolution', async () => {
    const latest = conversation(7, 99, '迟到的历史会话');
    mockSquadsAndConversations({ conversations: [latest] });
    let releaseLatest!: () => void;
    const gate = new Promise<void>((resolve) => { releaseLatest = resolve; });
    server.use(http.get(LATEST_CLARIFICATION_PATH, async () => {
      await gate;
      return latestConversationResponse(latest);
    }));

    renderPanel();
    fireEvent.click(screen.getByRole('button', { name: '切换数字员工' }));
    expect(await screen.findByText('选择小队和数字员工')).toBeInTheDocument();

    releaseLatest();

    // 迟到的结果落在「用户已接管」之后：必须被忽略。
    await waitFor(() => expect(screen.queryByTestId('clarification-latest-loading')).toBeNull());
    expect(screen.queryByText('迟到的历史会话')).toBeNull();
    expect(screen.getByText('选择小队和数字员工')).toBeInTheDocument();
  });

  it('AC-3: falls back to the first list item when the resolved conversation is gone', async () => {
    // 服务端解析出 7，但列表里只剩 8（并发删除后重新拉取）：不能把用户钉在空会话上。
    mockSquadsAndConversations({ conversations: [conversation(8, 42, '列表首项会话')] });
    mockLatest(conversation(7, 42, '已消失的会话'));

    renderPanel();

    expect(await screen.findByText('列表首项会话')).toBeInTheDocument();
    expect(screen.queryByText('已消失的会话')).toBeNull();
  });

  it('AC-13: writes the resolved squad back to the prefill for delivery', async () => {
    const latest = conversation(7, 42, '最近一次会话内容');
    mockSquadsAndConversations({ conversations: [latest] });
    mockLatest(latest);

    renderPanel();

    expect(await screen.findByText('最近一次会话内容')).toBeInTheDocument();
    await waitFor(() => {
      expect(readClarificationPrefill('100')).toMatchObject({ squadId: 9, agentId: 42 });
    });
  });

  /** CR N-1：/latest 之后的重新取数（窗口聚焦、缓存失效）不能把用户下拉改选的会话顶回最新。 */
  it('N-1: a later latest refetch never pulls the user back off a picked conversation', async () => {
    const latest = conversation(7, 42, '最近一次会话内容');
    const picked = conversation(8, 42, '用户下拉改选的会话');
    mockSquadsAndConversations({ conversations: [latest, picked] });

    let payload: ClarificationConversation | null = latest;
    server.use(http.get(LATEST_CLARIFICATION_PATH, () => latestConversationResponse(payload)));

    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={queryClient}>
        <WorkitemClarificationPanel workitemId="100" />
      </QueryClientProvider>,
    );

    expect(await screen.findByText('最近一次会话内容')).toBeInTheDocument();

    const selector = screen.getByTestId('clarification-conversation-select');
    fireEvent.mouseDown(within(selector).getByRole('combobox'));
    fireEvent.click(await screen.findByText(/会话 #8/));
    expect(await screen.findByText('用户下拉改选的会话')).toBeInTheDocument();

    // 服务端记录变化 + 后台重新取数：落定只做一次，用户已选会话不得被顶回。
    payload = conversation(9, 42, '后台重新取数的最新会话');
    await act(async () => {
      await queryClient.refetchQueries({
        queryKey: ['workitem', '100', 'clarification-latest-conversation'],
      });
    });

    await waitFor(() => expect(screen.getByText('用户下拉改选的会话')).toBeInTheDocument());
    expect(screen.queryByText('后台重新取数的最新会话')).toBeNull();
    expect(screen.getByTestId('clarification-conversation-select')).toHaveTextContent('会话 #8');
  });

  it('AC-8: ships the loading copy in both locales', async () => {
    mockSquadsAndConversations();
    let releaseLatest!: () => void;
    const gate = new Promise<void>((resolve) => { releaseLatest = resolve; });
    server.use(http.get(LATEST_CLARIFICATION_PATH, async () => {
      await gate;
      return latestConversationResponse(null);
    }));

    const previous = i18n.language;
    try {
      await i18n.changeLanguage('zh-CN');
      const zh = renderPanel();
      expect(screen.getByText('正在打开最近一次会话…')).toBeInTheDocument();
      zh.unmount();

      await i18n.changeLanguage('en-US');
      renderPanel();
      expect(screen.getByText('Opening the most recent conversation…')).toBeInTheDocument();
    } finally {
      releaseLatest();
      await i18n.changeLanguage(previous);
      clearClarificationPrefill('100');
    }
  });
});
