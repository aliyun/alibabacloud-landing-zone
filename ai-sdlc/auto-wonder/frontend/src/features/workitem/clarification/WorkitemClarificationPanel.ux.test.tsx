import { describe, it, expect, afterEach, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { typeComposer } from './composerTestUtils';
import { WorkitemClarificationPanel, isNearScrollBottom } from './WorkitemClarificationPanel';
import {
  clearClarificationPrefill,
  writeClarificationPrefill,
} from './prefill';

type RealtimeCallback = (event: { type: string; payload: unknown }) => void;
const realtime = vi.hoisted(() => ({ callback: null as RealtimeCallback | null }));

vi.mock('@/shared/realtime/useRealtime', () => ({
  useRealtime: (_channel: unknown, opts: { onEvent: RealtimeCallback }) => {
    realtime.callback = opts.onEvent;
  },
}));

function okJson(data: unknown) {
  return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data });
}

/** 会话轮次实时事件：与 acp 测试同口径，经 useRealtime 的 onEvent 注入（会话 1）。 */
function emitTurnEvent(eventSeq: number, eventType: string, payload: unknown, turnId = 3) {
  act(() => {
    realtime.callback!({
      type: 'CONVERSATION_TURN_EVENT',
      payload: { conversationId: 1, turnId, eventSeq, eventType, payload },
    });
  });
}

interface MockConversation {
  id?: number;
  agentId?: number;
  agentName?: string;
  status?: string;
  processingStatus?: string | null;
  processingTurnId?: number | null;
  cancelSupported?: boolean;
  turns?: Array<{
    id: number;
    direction: string;
    content: string;
    status: string;
    error?: string | null;
  }>;
}

function conversationPayload(conv: MockConversation) {
  return {
    id: conv.id ?? 1,
    agentId: conv.agentId ?? 42,
    agentName: conv.agentName ?? 'Agent-X',
    channelConversationId: 'ch-1',
    status: conv.status ?? 'ACTIVE',
    executorOnline: true,
    streamingSupported: true,
    cancelSupported: conv.cancelSupported ?? false,
    cliSessionRef: null,
    processingStatus: conv.processingStatus ?? null,
    processingTurnId: conv.processingTurnId ?? null,
    lastTurnAt: null,
    gmtCreate: '2026-01-01T00:00:00',
    turns: (conv.turns ?? []).map((t) => ({
      id: t.id,
      direction: t.direction,
      content: t.content,
      status: t.status,
      error: t.error ?? null,
      gmtCreate: '2026-01-01T00:00:00',
    })),
  };
}

function mockConversation(conv: MockConversation) {
  const payload = conversationPayload(conv);
  server.use(
    http.get('/api/squads', () =>
      HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: {
          list: [{ id: 9, name: '交付小队', description: '', memberCount: 1, gmtCreate: '' }],
          total: 1, pageNum: 1, pageSize: 100,
        },
      }),
    ),
    http.get('/api/squads/:squadId/members', () =>
      HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [{ agentId: 42, agentName: 'Agent-X', roleCode: 'AW_FS_DEV' }],
      }),
    ),
    http.get('/api/workitems/:workitemId/clarification-conversations', () =>
      HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: [payload] }),
    ),
    http.get('/api/workitems/:workitemId/clarification-conversations/:conversationId', () =>
      HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: payload }),
    ),
    http.get('/api/workitems/:workitemId/clarification-conversations/:conversationId/events', () =>
      HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: [] }),
    ),
  );
}

async function renderPanelWithConversation(conv: MockConversation) {
  writeClarificationPrefill('100', { squadId: 9, agentId: 42 });
  mockConversation(conv);
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const view = render(
    <QueryClientProvider client={queryClient}>
      <WorkitemClarificationPanel workitemId="100" />
    </QueryClientProvider>,
  );
  const textarea = await screen.findByPlaceholderText('输入消息...');
  return { view, textarea, queryClient };
}

describe('WorkitemClarificationPanel 回复体验（R1/R2/R3/R4）', () => {
  afterEach(() => {
    clearClarificationPrefill('100');
    vi.restoreAllMocks();
  });

  it('R1+R2: 回复中展示动态指示且输入框与发送均禁用', async () => {
    await renderPanelWithConversation({
      processingStatus: 'PROCESSING',
      processingTurnId: 7,
      turns: [{ id: 1, direction: 'IN', content: '帮我澄清需求', status: 'SUCCESS' }],
    });

    const indicator = await screen.findByTestId('clarification-replying-indicator');
    expect(indicator.textContent).toContain('Agent-X');

    const textarea = screen.getByPlaceholderText('输入消息...');
    expect(textarea).toBeDisabled();
    // 回复中不展示发送按钮，也没有可用的发送入口
    expect(screen.queryByText('终止响应')).toBeNull();
  });

  it('R1: 排队中（QUEUED）同样展示回复指示', async () => {
    await renderPanelWithConversation({
      processingStatus: 'QUEUED',
      processingTurnId: null,
      turns: [{ id: 1, direction: 'IN', content: '你好', status: 'SUCCESS' }],
    });

    expect(await screen.findByTestId('clarification-replying-indicator')).toBeInTheDocument();
    expect(screen.getByPlaceholderText('输入消息...')).toBeDisabled();
  });

  it('R3: runtime 支持取消时展示「终止响应」按钮，不支持时隐藏', async () => {
    const { view } = await renderPanelWithConversation({
      processingStatus: 'PROCESSING',
      processingTurnId: 7,
      cancelSupported: true,
      turns: [{ id: 1, direction: 'IN', content: '帮我澄清需求', status: 'SUCCESS' }],
    });
    expect(await view.findByText('终止响应')).toBeInTheDocument();
    view.unmount();

    await renderPanelWithConversation({
      processingStatus: 'PROCESSING',
      processingTurnId: 7,
      cancelSupported: false,
      turns: [{ id: 1, direction: 'IN', content: '帮我澄清需求', status: 'SUCCESS' }],
    });
    // 等待会话详情加载完成（回复指示出现）后仍不应出现终止按钮
    await screen.findByTestId('clarification-replying-indicator');
    expect(screen.queryByText('终止响应')).toBeNull();
  });

  it('R3: 点击终止后调用取消接口，恢复输入并给部分输出打「已终止」标签', async () => {
    writeClarificationPrefill('100', { squadId: 9, agentId: 42 });
    let canceled = false;
    const cancelSpy = vi.fn();

    const processing = conversationPayload({
      processingStatus: 'PROCESSING',
      processingTurnId: 7,
      cancelSupported: true,
      turns: [{ id: 1, direction: 'IN', content: '帮我澄清需求', status: 'SUCCESS' }],
    });
    const afterCancel = conversationPayload({
      processingStatus: null,
      processingTurnId: null,
      cancelSupported: true,
      turns: [
        { id: 1, direction: 'IN', content: '帮我澄清需求', status: 'SUCCESS' },
        { id: 2, direction: 'OUT', content: '部分内容…', status: 'CANCELED' },
      ],
    });

    server.use(
      http.get('/api/squads', () =>
        HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: {
            list: [{ id: 9, name: '交付小队', description: '', memberCount: 1, gmtCreate: '' }],
            total: 1, pageNum: 1, pageSize: 100,
          },
        }),
      ),
      http.get('/api/squads/:squadId/members', () =>
        HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: [{ agentId: 42, agentName: 'Agent-X', roleCode: 'AW_FS_DEV' }],
        }),
      ),
      http.get('/api/workitems/:workitemId/clarification-conversations', () =>
        HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: [canceled ? afterCancel : processing],
        }),
      ),
      http.get('/api/workitems/:workitemId/clarification-conversations/:conversationId', () =>
        HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: canceled ? afterCancel : processing,
        }),
      ),
      http.get('/api/workitems/:workitemId/clarification-conversations/:conversationId/events', () =>
        HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: [] }),
      ),
      http.post('/api/workitems/:workitemId/clarification-conversations/:conversationId/turns/:turnId/cancel',
        ({ params }) => {
          cancelSpy(params.turnId);
          canceled = true;
          return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: null });
        }),
    );

    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={queryClient}>
        <WorkitemClarificationPanel workitemId="100" />
      </QueryClientProvider>,
    );

    await screen.findByPlaceholderText('输入消息...');
    // 等待会话详情加载完成（回复指示出现），此时输入框才进入禁用态
    await screen.findByTestId('clarification-replying-indicator');
    expect(screen.getByPlaceholderText('输入消息...')).toBeDisabled();

    fireEvent.click(await screen.findByText('终止响应'));

    await waitFor(() => expect(cancelSpy).toHaveBeenCalledWith('7'));
    // 取消成功并重新拉取后：输入恢复、指示消失、部分输出带「已终止」标签、焦点回到输入框
    await waitFor(() => expect(screen.getByPlaceholderText('输入消息...')).toBeEnabled());
    await waitFor(() => expect(screen.queryByTestId('clarification-replying-indicator')).toBeNull());
    expect(screen.getByText('已终止')).toBeInTheDocument();
    expect(screen.getByText(/部分内容/)).toBeInTheDocument();
    await waitFor(() => expect(document.activeElement).toBe(document.querySelector('.ProseMirror')));
  });

  it('R4: 向上滚动离开底部暂停跟随并出现「回到底部」，点击后平滑滚回并恢复跟随', async () => {
    await renderPanelWithConversation({
      turns: [
        { id: 1, direction: 'IN', content: '第一条', status: 'SUCCESS' },
        { id: 2, direction: 'OUT', content: '第二条', status: 'SUCCESS' },
      ],
    });

    const scrollEl = await screen.findByTestId('clarification-message-scroll');
    const scrollToMock = vi.fn();
    (scrollEl as unknown as { scrollTo: unknown }).scrollTo = scrollToMock;
    Object.defineProperty(scrollEl, 'scrollHeight', { configurable: true, value: 1000 });
    Object.defineProperty(scrollEl, 'clientHeight', { configurable: true, value: 300 });
    Object.defineProperty(scrollEl, 'scrollTop', { configurable: true, value: 100 });

    fireEvent.scroll(scrollEl);
    const backToBottom = await screen.findByLabelText('回到底部');

    fireEvent.click(backToBottom);
    expect(scrollToMock).toHaveBeenCalledWith(
      expect.objectContaining({ top: 1000, behavior: 'smooth' }),
    );

    // 滚回底部后按钮消失，自动跟随恢复
    Object.defineProperty(scrollEl, 'scrollTop', { configurable: true, value: 700 });
    fireEvent.scroll(scrollEl);
    await waitFor(() => expect(screen.queryByLabelText('回到底部')).toBeNull());
  });

  it('R4: 渲染 CANCELED 终态时不再有回复指示', async () => {
    await renderPanelWithConversation({
      processingStatus: null,
      turns: [
        { id: 1, direction: 'IN', content: '问题', status: 'SUCCESS' },
        { id: 2, direction: 'OUT', content: '部分回答', status: 'CANCELED' },
      ],
    });

    expect(await screen.findByText('已终止')).toBeInTheDocument();
    expect(screen.queryByTestId('clarification-replying-indicator')).toBeNull();
    expect(screen.getByPlaceholderText('输入消息...')).toBeEnabled();
  });
});

describe('WorkitemClarificationPanel 回复结束后的输入焦点（工单 53305）', () => {
  afterEach(() => {
    clearClarificationPrefill('100');
    vi.restoreAllMocks();
  });

  it('Agent 响应结束后自动聚焦输入框，用户可以直接接着打字', async () => {
    const { queryClient } = await renderPanelWithConversation({
      processingStatus: 'PROCESSING',
      processingTurnId: 7,
      turns: [{ id: 1, direction: 'IN', content: '帮我澄清需求', status: 'SUCCESS' }],
    });

    await screen.findByTestId('clarification-replying-indicator');
    expect(screen.getByPlaceholderText('输入消息...')).toBeDisabled();
    expect(document.activeElement).not.toBe(document.querySelector('.ProseMirror'));

    // 回复完成落库：等价于终态实时事件触发的会话失效重拉
    mockConversation({
      processingStatus: null,
      processingTurnId: null,
      turns: [
        { id: 1, direction: 'IN', content: '帮我澄清需求', status: 'SUCCESS' },
        { id: 2, direction: 'OUT', content: '澄清完成', status: 'COMPLETED' },
      ],
    });
    act(() => {
      queryClient.invalidateQueries({
        queryKey: ['workitem', '100', 'clarification-conversation', 1],
      });
    });

    await waitFor(() => expect(screen.getByPlaceholderText('输入消息...')).toBeEnabled());
    await waitFor(() => expect(screen.queryByTestId('clarification-replying-indicator')).toBeNull());
    await waitFor(() =>
      expect(document.activeElement).toBe(document.querySelector('.ProseMirror')));
  });

  // 焦点只在「本轮回复结束」时归还：面板首次拉取会话时输入框同样经历一次
  // 禁用→可用，回复结束的归还效应不能把这次跃迁误判为回复结束（工单 53305 原始诉求）。
  // 72ca85586 的「进入面板自动聚焦」（FR-001）有意在挂载、输入行可用后经
  // requestAnimationFrame 聚焦一次，所以本场景的终点是焦点落在输入框上；
  // 等待 rAF 落定后再断言，避免与断言赛跑（全量并发下 rAF 可能先于断言执行）。
  it('打开回复早已结束的历史会话时由进入面板的自动聚焦接管焦点', async () => {
    const { textarea } = await renderPanelWithConversation({
      processingStatus: null,
      turns: [
        { id: 1, direction: 'IN', content: '帮我澄清需求', status: 'SUCCESS' },
        { id: 2, direction: 'OUT', content: '澄清完成', status: 'COMPLETED' },
      ],
    });

    await waitFor(() => expect(textarea).toBeEnabled());
    expect(screen.queryByTestId('clarification-replying-indicator')).toBeNull();
    await waitFor(() => expect(document.activeElement).toBe(document.querySelector('.ProseMirror')));
  });
});

describe('WorkitemClarificationPanel 终态先于结算与跨会话隔离（工单 55851）', () => {
  afterEach(() => {
    clearClarificationPrefill('100');
    vi.restoreAllMocks();
  });

  // 现场形态：卡片结束、AI 改文字澄清后本轮已结束（流终态已到），详情却还停在
  // PROCESSING/QUEUED。回复指示走的是流终态（已隐藏），输入禁用却依赖详情——不能
  // 在无解释的禁用上卡死，也不能为了恢复输入而放开服务端未结算的并发门禁。
  it('流终态先于详情结算时给出明确状态与重试入口，结算后输入恢复', async () => {
    let detailCalls = 0;
    let settled = false;
    const submitPaths: string[] = [];
    const processingPayload = conversationPayload({
      processingStatus: 'PROCESSING',
      processingTurnId: 3,
      turns: [{ id: 3, direction: 'IN', content: '请给方案', status: 'PROCESSING' }],
    });
    const settledPayload = conversationPayload({
      turns: [
        { id: 3, direction: 'IN', content: '请给方案', status: 'SUCCESS' },
        { id: 4, direction: 'OUT', content: '这是方案', status: 'COMPLETED' },
      ],
    });
    await renderPanelWithConversation({
      processingStatus: 'PROCESSING',
      processingTurnId: 3,
      turns: [{ id: 3, direction: 'IN', content: '请给方案', status: 'PROCESSING' }],
    });
    server.use(
      http.get('/api/workitems/:workitemId/clarification-conversations/:conversationId', () => {
        detailCalls += 1;
        return okJson(settled ? settledPayload : processingPayload);
      }),
      http.post(
        '/api/workitems/:workitemId/clarification-conversations/:conversationId/turns',
        ({ request }) => {
          submitPaths.push(new URL(request.url).pathname);
          return okJson(null);
        },
      ),
    );
    const textarea = await screen.findByPlaceholderText('输入消息...');
    await waitFor(() => expect(textarea).toBeDisabled());

    emitTurnEvent(1, 'text', { type: 'text', content: '这是方案' }, 3);
    emitTurnEvent(2, 'status', { type: 'status', status: 'completed' }, 3);

    // 指示已隐藏（流终态），输入仍禁用（详情未结算）：给出明确「结算中」状态与
    // 重试入口，而不是静默禁用；此时不放开发送。
    await waitFor(() => expect(screen.queryByTestId('clarification-replying-indicator')).toBeNull());
    const notice = await screen.findByTestId('clarification-settling-notice');
    expect(notice).toHaveTextContent('回复已结束，正在同步会话状态，输入暂不可用。');
    expect(textarea).toBeDisabled();
    expect(submitPaths).toHaveLength(0);

    // 重试拉到结算后的详情：输入恢复、提示消失，无需退出重进
    settled = true;
    const before = detailCalls;
    fireEvent.click(screen.getByTestId('clarification-settling-retry'));
    await waitFor(() => expect(detailCalls).toBeGreaterThan(before));
    await waitFor(() => expect(screen.queryByTestId('clarification-settling-notice')).toBeNull());
    await waitFor(() => expect(screen.getByPlaceholderText('输入消息...')).toBeEnabled());
    expect(await screen.findByText('这是方案')).toBeInTheDocument();
  });

  // 现场形态：旧会话 PROCESSING、旧提交的 POST 一直不返回时，成功新建的空会话必须
  // 可编辑、可发送；旧请求稍后落地只影响它自己，不改变目标会话状态与输入内容。
  it('旧提交未返回时新建会话可编辑可发送，旧请求落地不污染目标会话', async () => {
    writeClarificationPrefill('100', { squadId: 9, agentId: 42 });
    const existing = conversationPayload({
      id: 1,
      turns: [{ id: 1, direction: 'IN', content: '老会话消息', status: 'SUCCESS' }],
    });
    const created = conversationPayload({ id: 2, turns: [] });
    let releaseSubmit: (() => void) | undefined;
    const submitGate = new Promise<void>((resolve) => { releaseSubmit = resolve; });
    const submitPaths: string[] = [];
    server.use(
      http.get('/api/squads', () => okJson({
        list: [{ id: 9, name: '交付小队', description: '', memberCount: 1, gmtCreate: '' }],
        total: 1, pageNum: 1, pageSize: 100,
      })),
      http.get('/api/squads/:squadId/members', () =>
        okJson([{ agentId: 42, agentName: 'Agent-X', roleCode: 'AW_FS_DEV' }])),
      http.get('/api/workitems/:workitemId/clarification-conversations', () =>
        okJson([existing, created])),
      http.get('/api/workitems/:workitemId/clarification-conversations/:conversationId',
        ({ params }) => okJson(Number(params.conversationId) === 2 ? created : existing)),
      http.post('/api/workitems/:workitemId/clarification-conversations', () => okJson(created)),
      http.post(
        '/api/workitems/:workitemId/clarification-conversations/:conversationId/turns',
        async ({ request }) => {
          submitPaths.push(new URL(request.url).pathname);
          // 只有旧会话的提交一直扣着不返回：模拟「旧 HTTP 请求未返回」
          if (request.url.includes('/clarification-conversations/1/')) await submitGate;
          return okJson(null);
        },
      ),
    );
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={queryClient}>
        <WorkitemClarificationPanel workitemId="100" />
      </QueryClientProvider>,
    );
    const textarea = await screen.findByPlaceholderText('输入消息...');
    await screen.findByText('老会话消息');

    // 旧会话发出提交且 POST 未返回：旧会话自己的输入保持禁用（同会话并发保护不变）
    await typeComposer('旧会话的待发送内容');
    fireEvent.keyDown(textarea, { key: 'Enter', code: 'Enter', shiftKey: true });
    await waitFor(() => expect(submitPaths).toHaveLength(1));
    expect(submitPaths[0]).toContain('/clarification-conversations/1/turns');
    await waitFor(() => expect(textarea).toBeDisabled());

    // 新建会话：目标空会话必须可编辑，不被旧会话的在飞请求阻塞
    fireEvent.click(screen.getByRole('button', { name: /新对话/ }));
    await waitFor(() =>
      expect(screen.getByTestId('clarification-conversation-select')).toHaveTextContent('会话 #2'));
    await waitFor(() => expect(textarea).toBeEnabled());

    // 目标会话可正常发送，与旧请求互不影响
    await typeComposer('新会话留言');
    fireEvent.keyDown(textarea, { key: 'Enter', code: 'Enter', shiftKey: true });
    await waitFor(() => expect(submitPaths).toHaveLength(2));
    expect(submitPaths[1]).toContain('/clarification-conversations/2/turns');

    // 旧请求落地：目标会话不受影响——输入仍可用、不被旧草稿回填、无错误提示
    releaseSubmit!();
    await waitFor(() => expect(textarea).toBeEnabled());
    expect(textarea).toHaveValue('');
    expect(screen.queryByText('消息发送失败，请重试')).toBeNull();
  });
});

describe('isNearScrollBottom', () => {
  it('treats the bottom threshold inclusively', () => {
    expect(isNearScrollBottom({ scrollTop: 692, clientHeight: 300, scrollHeight: 1000 })).toBe(true);
    expect(isNearScrollBottom({ scrollTop: 700, clientHeight: 300, scrollHeight: 1000 })).toBe(true);
    expect(isNearScrollBottom({ scrollTop: 100, clientHeight: 300, scrollHeight: 1000 })).toBe(false);
    expect(isNearScrollBottom({ scrollTop: 0, clientHeight: 0, scrollHeight: 0 })).toBe(true);
  });
});
