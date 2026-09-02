import { describe, it, expect, beforeEach, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import {
  WorkitemClarificationPanel,
  buildDeliveryAgentOptions,
  computeInputHeightMax,
  clarificationInputAutoSize,
  turnBubbleStyle,
  CLARIFICATION_INPUT_DEFAULT_ROWS,
} from './WorkitemClarificationPanel';
import {
  clearClarificationPrefill,
  readClarificationPrefill,
  writeClarificationPrefill,
} from './prefill';

type RealtimeCallback = (event: { type: string; payload: unknown }) => void;
const realtime = vi.hoisted(() => ({ callback: null as RealtimeCallback | null }));

vi.mock('@/shared/realtime/useRealtime', () => ({
  useRealtime: (_channel: unknown, opts: { onEvent: RealtimeCallback }) => {
    realtime.callback = opts.onEvent;
  },
}));

function renderPanel(agents: Array<{ agentId: number; agentName: string; status: string }> = []) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <WorkitemClarificationPanel
        workitemId="100"
        agents={agents as never}
      />
    </QueryClientProvider>,
  );
}

function mockSquads() {
  server.use(
    http.get('/api/squads', () =>
      HttpResponse.json({
        success: true,
        code: '0',
        message: '',
        traceId: null,
        data: {
          list: [{ id: 9, name: '交付小队', description: '', memberCount: 1, gmtCreate: '' }],
          total: 1,
          pageNum: 1,
          pageSize: 100,
        },
      }),
    ),
    http.get('/api/squads/:squadId/members', () =>
      HttpResponse.json({
        success: true,
        code: '0',
        message: '',
        traceId: null,
        data: [{ agentId: 42, agentName: 'Agent-X', roleCode: 'AW_FS_DEV' }],
      }),
    ),
    http.get('/api/workitems/:workitemId/clarification-conversations', () =>
      HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: [] }),
    ),
    http.post('/api/workitems/:workitemId/clarification-conversations', async ({ request }) => {
      const body = await request.json() as { agentId?: number };
      return HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: {
          id: 5, agentId: body.agentId ?? 42, agentName: 'Agent-X', channelConversationId: 'ch-5',
          status: 'ACTIVE', executorOnline: true, streamingSupported: true,
          cliSessionRef: null, processingStatus: null, processingTurnId: null,
          lastTurnAt: null, gmtCreate: '2026-01-01T00:00:00', turns: [],
        },
      });
    }),
    http.get('/api/workitems/:workitemId/clarification-conversations/:conversationId', () =>
      HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: null }),
    ),
    http.get('/api/workitems/:workitemId/clarification-conversations/:conversationId/events', () =>
      HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: [] }),
    ),
  );
}

function mockConversationWithTurns(agentId: number = 42) {
  server.use(
    http.get('/api/workitems/:workitemId/clarification-conversations', () =>
      HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [{
          id: 1, agentId, agentName: 'Agent-X', channelConversationId: 'ch-1',
          status: 'ACTIVE', executorOnline: true, streamingSupported: true,
          cliSessionRef: null, processingStatus: null, processingTurnId: null,
          lastTurnAt: '2026-01-01T00:00:00', gmtCreate: '2026-01-01T00:00:00',
          turns: [
            { id: 1, direction: 'IN', content: '你好，我想讨论需求', status: 'SUCCESS', error: null, gmtCreate: '2026-01-01T00:00:00' },
            { id: 2, direction: 'OUT', content: '好的，请说说你的想法', status: 'SUCCESS', error: null, gmtCreate: '2026-01-01T00:00:01' },
          ],
        }],
      }),
    ),
    http.get('/api/workitems/:workitemId/clarification-conversations/:conversationId', () =>
      HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: {
          id: 1, agentId, agentName: 'Agent-X', channelConversationId: 'ch-1',
          status: 'ACTIVE', executorOnline: true, streamingSupported: true,
          cliSessionRef: null, processingStatus: null, processingTurnId: null,
          lastTurnAt: '2026-01-01T00:00:00', gmtCreate: '2026-01-01T00:00:00',
          turns: [
            { id: 1, direction: 'IN', content: '你好，我想讨论需求', status: 'SUCCESS', error: null, gmtCreate: '2026-01-01T00:00:00' },
            { id: 2, direction: 'OUT', content: '好的，请说说你的想法', status: 'SUCCESS', error: null, gmtCreate: '2026-01-01T00:00:01' },
          ],
        },
      }),
    ),
    http.get('/api/workitems/:workitemId/clarification-conversations/:conversationId/events', () =>
      HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: [] }),
    ),
  );
}

function mockAgentDirectory(entries: Array<{ id: number; executorOnlineCount?: number }>) {
  server.use(
    http.get('/api/agents', () =>
      HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: entries.map((e) => ({ id: e.id, name: `Agent-${e.id}`, executorOnlineCount: e.executorOnlineCount })),
      }),
    ),
  );
}

// jsdom 没有 PointerEvent 构造器，fireEvent.pointer* 会丢失坐标，用 MouseEvent 派生 pointer 事件
function firePointer(
  type: 'pointerdown' | 'pointermove' | 'pointerup' | 'pointercancel',
  target: HTMLElement,
  coords: { clientX?: number; clientY?: number } = {},
) {
  fireEvent(target, new MouseEvent(type, { bubbles: true, cancelable: true, ...coords }));
}

function mockElementHeight(el: Element, height: number) {
  vi.spyOn(el, 'getBoundingClientRect').mockReturnValue({
    x: 0, y: 0, top: 0, left: 0, right: 0, bottom: height,
    width: 0, height, toJSON: () => ({}),
  } as DOMRect);
}

async function renderActiveConversation() {
  writeClarificationPrefill('100', { squadId: 9, agentId: 42 });
  mockSquads();
  mockConversationWithTurns(42);
  const view = renderPanel([]);
  const textarea = await screen.findByPlaceholderText('输入消息...');
  return { view, textarea };
}

describe('buildDeliveryAgentOptions', () => {
  it('uses real executor online counts and falls back to online for unknown agents', () => {
    const online = new Map([[1, true], [2, false]]);
    expect(buildDeliveryAgentOptions(
      [
        { agentId: 1, agentName: 'Agent-A' },
        { agentId: 2, agentName: 'Agent-B' },
        { agentId: 3, agentName: 'Agent-C' },
      ],
      online,
    )).toEqual([
      { agentId: 1, agentName: 'Agent-A', executorOnline: true },
      { agentId: 2, agentName: 'Agent-B', executorOnline: false },
      { agentId: 3, agentName: 'Agent-C', executorOnline: true },
    ]);
  });

  it('returns an empty option list for empty delivery agents', () => {
    expect(buildDeliveryAgentOptions([], new Map([[1, true]]))).toEqual([]);
  });
});

describe('turnBubbleStyle', () => {
  it('gives agent bubbles a visible border shared by streaming and completed states', () => {
    expect(turnBubbleStyle(false)).toEqual({
      padding: '8px 12px',
      borderRadius: 8,
      backgroundColor: '#f5f5f5',
      border: '1px solid #d9d9d9',
      fontSize: 13,
      lineHeight: '1.6',
    });
  });

  it('uses the user palette for user bubbles and never sets white-space', () => {
    expect(turnBubbleStyle(true)).toMatchObject({
      backgroundColor: '#e6f7ff',
      border: '1px solid #91d5ff',
    });
    expect(turnBubbleStyle(true)).not.toHaveProperty('whiteSpace');
    expect(turnBubbleStyle(false)).not.toHaveProperty('whiteSpace');
  });
});

describe('WorkitemClarificationPanel', () => {
  beforeEach(() => {
    window.localStorage.clear();
    realtime.callback = null;
  });

  it('uses delivery agents when progress already provides them', () => {
    renderPanel([
      { agentId: 1, agentName: 'Agent-A', status: 'active' },
      { agentId: 2, agentName: 'Agent-B', status: 'pending' },
    ]);
    expect(screen.getByText('选择数字人')).toBeInTheDocument();
    expect(screen.getByText('Agent-A')).toBeInTheDocument();
  });

  it('shows delivery agent online status from real executor counts, not delivery status', async () => {
    mockAgentDirectory([
      { id: 1, executorOnlineCount: 2 },
      { id: 2, executorOnlineCount: 0 },
      { id: 4 }, // 无 executorOnlineCount 字段 → 视为离线
    ]);
    renderPanel([
      { agentId: 1, agentName: 'Agent-A', status: 'pending' },
      { agentId: 2, agentName: 'Agent-B', status: 'active' },
      { agentId: 3, agentName: 'Agent-C', status: 'finished' },
      { agentId: 4, agentName: 'Agent-D', status: 'active' },
    ]);

    const itemOf = (name: string) => screen.getByText(name).closest('.ant-list-item') as HTMLElement;
    // 初始渲染按在线兜底；等 Agent-B 翻转为离线，证明目录数据已生效
    // （交付状态 active 但执行器真实离线 → 离线）
    await waitFor(() => expect(itemOf('Agent-B')).toHaveTextContent('离线'));
    // 交付状态 pending 但执行器真实在线 → 在线
    expect(itemOf('Agent-A')).toHaveTextContent('在线');
    // 目录中不存在的数字人按在线兜底，保持可选
    expect(itemOf('Agent-C')).toHaveTextContent('在线');
    // 目录项缺少在线数 → 离线
    expect(itemOf('Agent-D')).toHaveTextContent('离线');
  });

  it('keeps truly online agents selectable to re-enter historical clarification conversations', async () => {
    mockAgentDirectory([
      { id: 1, executorOnlineCount: 1 },
      { id: 2, executorOnlineCount: 0 },
    ]);
    const conversation = {
      id: 3, agentId: 1, agentName: 'Agent-A', channelConversationId: 'ch-3',
      status: 'ACTIVE', executorOnline: true, streamingSupported: true,
      cliSessionRef: null, processingStatus: null, processingTurnId: null,
      lastTurnAt: '2026-01-01T00:00:00', gmtCreate: '2026-01-01T00:00:00',
      turns: [
        { id: 9, direction: 'IN', content: '历史澄清记录', status: 'COMPLETED', error: null, gmtCreate: '2026-01-01T00:00:00' },
      ],
    };
    server.use(
      http.get('/api/workitems/:workitemId/clarification-conversations', () =>
        HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: [conversation] }),
      ),
      http.get('/api/workitems/:workitemId/clarification-conversations/:conversationId', () =>
        HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: conversation }),
      ),
    );

    renderPanel([
      { agentId: 1, agentName: 'Agent-A', status: 'pending' },
      { agentId: 2, agentName: 'Agent-B', status: 'pending' },
    ]);

    // 等待在线状态按真实执行器数落定后再交互
    const itemOf = (name: string) => screen.getByText(name).closest('.ant-list-item') as HTMLElement;
    await waitFor(() => expect(itemOf('Agent-B')).toHaveTextContent('离线'));

    // 离线数字人点击后仍停留在选择页
    fireEvent.click(screen.getByText('Agent-B'));
    expect(screen.getByText('选择数字人')).toBeInTheDocument();

    // 之前被误标离线的数字人现可点选并重入历史澄清会话
    fireEvent.click(screen.getByText('Agent-A'));
    expect(await screen.findByText('历史澄清记录')).toBeInTheDocument();
  });

  it('falls back to squad selector when no delivery agents exist', async () => {
    mockSquads();
    renderPanel([]);
    expect(screen.getByText('选择小队和数字人')).toBeInTheDocument();
    // Antd Select renders its placeholder inside a span, not an input attribute;
    // wait for the squad input to become enabled (squads loaded).
    const comboboxes = await screen.findAllByRole('combobox');
    expect(comboboxes.length).toBeGreaterThanOrEqual(2);
    await waitFor(() => expect(comboboxes[0]).not.toBeDisabled());
  });

  it('persists the selection to localStorage for delivery prefill', async () => {
    mockSquads();
    renderPanel([]);
    const comboboxes = await screen.findAllByRole('combobox');
    await waitFor(() => expect(comboboxes[0]).not.toBeDisabled());
    fireEvent.mouseDown(comboboxes[0]);
    fireEvent.click(await screen.findByText('交付小队'));
    await waitFor(() => expect(comboboxes[1]).not.toBeDisabled());
    fireEvent.mouseDown(comboboxes[1]);
    fireEvent.click(await screen.findByText('Agent-X (AW_FS_DEV)'));
    await waitFor(() => {
      const prefill = readClarificationPrefill('100');
      expect(prefill).not.toBeNull();
      expect(prefill!.squadId).toBe(9);
      expect(prefill!.agentId).toBe(42);
    });
  });

  it('prefills from localStorage when re-opened', () => {
    writeClarificationPrefill('100', { squadId: 9, agentId: 42 });
    mockSquads();
    renderPanel([]);
    // The prefilled selection short-circuits the agent-selection screen.
    expect(screen.queryByText('选择小队和数字人')).not.toBeInTheDocument();
  });

  it('shows the selected conversation agent name when delivery has not started', async () => {
    writeClarificationPrefill('100', { squadId: 9, agentId: 42 });
    mockSquads();
    server.use(
      http.get('/api/workitems/:workitemId/clarification-conversations', () =>
        HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: [{ id: 7 }] }),
      ),
      http.get('/api/workitems/:workitemId/clarification-conversations/:conversationId', () =>
        HttpResponse.json({
          success: true,
          code: '0',
          message: '',
          traceId: null,
          data: {
            id: 7,
            agentId: 42,
            agentName: '已选择的开发',
            channelConversationId: 'clarification-7',
            status: 'ACTIVE',
            executorOnline: true,
            streamingSupported: true,
            cliSessionRef: null,
            processingStatus: null,
            processingTurnId: null,
            lastTurnAt: null,
            gmtCreate: '',
            turns: [],
          },
        }),
      ),
    );

    renderPanel([]);

    expect(await screen.findByText('已选择的开发')).toBeInTheDocument();
    expect(screen.queryByText('数字人')).not.toBeInTheDocument();
    expect(await screen.findByDisplayValue(
      '请通过 AutoWonder MCP 读取工单 #100，与我进行需求澄清。',
    )).toBeInTheDocument();
  });

  it('clears prefill for unrelated workitems', () => {
    writeClarificationPrefill('100', { squadId: 9, agentId: 42 });
    clearClarificationPrefill('100');
    expect(readClarificationPrefill('100')).toBeNull();
  });

  it('renders sender labels for user and AI messages', async () => {
    writeClarificationPrefill('100', { squadId: 9, agentId: 42 });
    mockSquads();
    mockConversationWithTurns(42);
    renderPanel([]);

    await waitFor(() => {
      expect(screen.getByText('你好，我想讨论需求')).toBeInTheDocument();
    });

    expect(screen.getByText('你')).toBeInTheDocument();
    expect(screen.getByText('好的，请说说你的想法')).toBeInTheDocument();
  });

  it('distinguishes user and AI messages with different styles', async () => {
    writeClarificationPrefill('100', { squadId: 9, agentId: 42 });
    mockSquads();
    mockConversationWithTurns(42);
    renderPanel([]);

    await waitFor(() => {
      expect(screen.getByText('你好，我想讨论需求')).toBeInTheDocument();
    });

    const userMsg = screen.getByText('你好，我想讨论需求').closest('div[style*="background-color"]');
    const aiMsg = screen.getByText('好的，请说说你的想法').closest('div[style*="background-color"]');
    expect(userMsg).not.toBeNull();
    expect(aiMsg).not.toBeNull();
    expect(userMsg).toHaveStyle({ backgroundColor: '#e6f7ff' });
    expect(aiMsg).toHaveStyle({ backgroundColor: '#f5f5f5' });
  });

  it('drops pre-wrap for agent markdown bubbles but keeps it for plain user text', async () => {
    writeClarificationPrefill('100', { squadId: 9, agentId: 42 });
    mockSquads();
    mockConversationWithTurns(42);
    renderPanel([]);

    await waitFor(() => {
      expect(screen.getByText('你好，我想讨论需求')).toBeInTheDocument();
    });

    const userBubble = screen.getByText('你好，我想讨论需求').closest('div[style*="background-color"]');
    const aiBubble = screen.getByText('好的，请说说你的想法').closest('div[style*="background-color"]');
    // 用户纯文本保留 pre-wrap（保留手输入换行）
    expect(userBubble).toHaveStyle({ whiteSpace: 'pre-wrap' });
    // agent markdown 气泡不能带 pre-wrap：继承它会把块级元素间的换行
    // 渲染成字面空行，产生完成后的大块行间距空白
    expect(aiBubble).not.toHaveStyle({ whiteSpace: 'pre-wrap' });
  });

  it('renders the streaming reply inside the same bordered bubble as completed replies', async () => {
    writeClarificationPrefill('100', { squadId: 9, agentId: 42 });
    mockSquads();
    const processingConversation = {
      id: 1, agentId: 42, agentName: 'Agent-X', channelConversationId: 'ch-1',
      status: 'ACTIVE', executorOnline: true, streamingSupported: true,
      cliSessionRef: null, processingStatus: 'PROCESSING', processingTurnId: 3,
      lastTurnAt: '2026-01-01T00:00:02', gmtCreate: '2026-01-01T00:00:00',
      turns: [
        { id: 3, direction: 'INBOUND', content: '请给方案', status: 'PROCESSING', error: null, gmtCreate: '2026-01-01T00:00:02' },
      ],
    };
    server.use(
      http.get('/api/workitems/:workitemId/clarification-conversations', () =>
        HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: [processingConversation] }),
      ),
      http.get('/api/workitems/:workitemId/clarification-conversations/:conversationId', () =>
        HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: processingConversation }),
      ),
    );

    renderPanel([]);
    await screen.findByText('请给方案');
    await waitFor(() => expect(realtime.callback).not.toBeNull());

    act(() => {
      realtime.callback!({
        type: 'CONVERSATION_TURN_EVENT',
        payload: { conversationId: 1, turnId: 3, eventSeq: 1, eventType: 'text', payload: { type: 'text', content: '流式回复中' } },
      });
    });

    // 回复过程中就有与完成态一致的边框气泡
    const bubble = await screen.findByTestId('clarification-streaming-bubble');
    expect(bubble).toHaveStyle({
      backgroundColor: '#f5f5f5',
      border: '1px solid #d9d9d9',
      borderRadius: '8px',
    });
    expect(bubble.textContent).toContain('流式回复中');
  });

  it('does not render a cross-turn concatenated ghost bubble after the reply is persisted (workitem 50720)', async () => {
    writeClarificationPrefill('100', { squadId: 9, agentId: 42 });
    mockSquads();

    // 可变会话状态：第二轮处理中；事件流里会同时累积第一轮与第二轮的事件
    let conversationState: {
      id: number; agentId: number; agentName: string; channelConversationId: string;
      status: string; executorOnline: boolean; streamingSupported: boolean;
      cliSessionRef: string | null; processingStatus: string | null; processingTurnId: number | null;
      lastTurnAt: string; gmtCreate: string;
      turns: Array<{ id: number; direction: string; content: string; status: string; error: string | null; gmtCreate: string }>;
    } = {
      id: 1, agentId: 42, agentName: 'Agent-X', channelConversationId: 'ch-1',
      status: 'ACTIVE', executorOnline: true, streamingSupported: true,
      cliSessionRef: null, processingStatus: 'PROCESSING', processingTurnId: 3,
      lastTurnAt: '2026-01-01T00:00:02', gmtCreate: '2026-01-01T00:00:00',
      turns: [
        { id: 1, direction: 'INBOUND', content: '你好呀', status: 'SUCCESS', error: null, gmtCreate: '2026-01-01T00:00:00' },
        { id: 2, direction: 'OUTBOUND', content: '第一轮回复', status: 'SUCCESS', error: null, gmtCreate: '2026-01-01T00:00:01' },
        { id: 3, direction: 'INBOUND', content: '第二轮提问', status: 'PROCESSING', error: null, gmtCreate: '2026-01-01T00:00:02' },
      ],
    };
    server.use(
      http.get('/api/workitems/:workitemId/clarification-conversations', () =>
        HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: [conversationState] }),
      ),
      http.get('/api/workitems/:workitemId/clarification-conversations/:conversationId', () =>
        HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: conversationState }),
      ),
    );

    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const view = render(
      <QueryClientProvider client={queryClient}>
        <WorkitemClarificationPanel workitemId="100" agents={[] as never} />
      </QueryClientProvider>,
    );
    await screen.findByText('第二轮提问');
    await waitFor(() => expect(realtime.callback).not.toBeNull());

    // 会话内事件持续累积：包含第一轮的迟到事件与第二轮的流式回复
    act(() => {
      realtime.callback!({
        type: 'CONVERSATION_TURN_EVENT',
        payload: { conversationId: 1, turnId: 1, eventSeq: 1, eventType: 'text', payload: { type: 'text', content: '第一轮回复' } },
      });
      realtime.callback!({
        type: 'CONVERSATION_TURN_EVENT',
        payload: { conversationId: 1, turnId: 1, eventSeq: 2, eventType: 'status', payload: { type: 'status', status: 'completed' } },
      });
      realtime.callback!({
        type: 'CONVERSATION_TURN_EVENT',
        payload: { conversationId: 1, turnId: 3, eventSeq: 1, eventType: 'text', payload: { type: 'text', content: '第二轮回复' } },
      });
      realtime.callback!({
        type: 'CONVERSATION_TURN_EVENT',
        payload: { conversationId: 1, turnId: 3, eventSeq: 2, eventType: 'status', payload: { type: 'status', status: 'completed' } },
      });
    });

    // 回复落库：会话查询翻到无处理中轮次（旧实现此刻会渲染
    // “第一轮回复 + 第二轮回复”拼接的第三气泡）
    conversationState = {
      ...conversationState,
      processingStatus: null,
      processingTurnId: null,
      turns: [
        ...conversationState.turns.map((t) => (t.id === 3 ? { ...t, status: 'COMPLETED' } : t)),
        { id: 4, direction: 'OUTBOUND', content: '第二轮回复', status: 'COMPLETED', error: null, gmtCreate: '2026-01-01T00:00:03' },
      ],
    };
    await act(async () => {
      await queryClient.invalidateQueries({
        queryKey: ['workitem', '100', 'clarification-conversation', 1],
      });
    });

    await waitFor(() => {
      expect(screen.getByText('第二轮回复')).toBeInTheDocument();
    });
    // 不得出现跨轮拼接的第三气泡
    expect(view.container.textContent).not.toContain('第一轮回复第二轮回复');
    // 流式气泡已随落库消失，持久化回复只渲染一次
    expect(screen.queryByTestId('clarification-streaming-bubble')).toBeNull();
    expect(screen.getAllByText('第二轮回复')).toHaveLength(1);
  });

  it('auto-follow scrolls instantly and programmatic scroll events do not break bottom following', async () => {
    writeClarificationPrefill('100', { squadId: 9, agentId: 42 });
    mockSquads();
    mockConversationWithTurns(42);
    renderPanel([]);

    // jsdom 没有 Element.prototype.scrollTo，按元素挂 mock（与 R4 用例同法）
    const scrollEl = await screen.findByTestId('clarification-message-scroll');
    const scrollToMock = vi.fn();
    (scrollEl as unknown as { scrollTo: unknown }).scrollTo = scrollToMock;
    Object.defineProperty(scrollEl, 'scrollHeight', { configurable: true, value: 1000 });
    Object.defineProperty(scrollEl, 'clientHeight', { configurable: true, value: 300 });

    // 跟随滚动用瞬时滚动，避免平滑动画中间态误判“不在底部”
    await waitFor(() => expect(scrollToMock).toHaveBeenCalledWith(
      expect.objectContaining({ behavior: 'auto' }),
    ));

    // 程序化滚动到底后收到的底部 scroll 事件不会把跟随关掉
    Object.defineProperty(scrollEl, 'scrollTop', { configurable: true, value: 700 });
    fireEvent.scroll(scrollEl);
    expect(screen.queryByLabelText('回到底部')).toBeNull();

    // 用户真实上滚离开底部仍然暂停跟随（回到底部按钮出现），
    // 且跟随暂停后不再触发新的自动滚动
    scrollToMock.mockClear();
    Object.defineProperty(scrollEl, 'scrollTop', { configurable: true, value: 100 });
    fireEvent.scroll(scrollEl);
    expect(await screen.findByLabelText('回到底部')).toBeInTheDocument();
    expect(scrollToMock).not.toHaveBeenCalled();

    // 跟随暂停期间即使有新流式内容，也不再自动滚动
    await waitFor(() => expect(realtime.callback).not.toBeNull());
    act(() => {
      realtime.callback!({
        type: 'CONVERSATION_TURN_EVENT',
        payload: { conversationId: 1, turnId: 2, eventSeq: 1, eventType: 'text', payload: { type: 'text', content: '跟随暂停后的新内容' } },
      });
    });
    await waitFor(() => expect(scrollToMock).not.toHaveBeenCalled());
  });

  it('turns completed streamed text into an AI bubble without waiting for the next user reply', async () => {
    writeClarificationPrefill('100', { squadId: 9, agentId: 42 });
    mockSquads();
    const processingConversation = {
      id: 1, agentId: 42, agentName: 'Agent-X', channelConversationId: 'ch-1',
      status: 'ACTIVE', executorOnline: true, streamingSupported: true,
      cliSessionRef: null, processingStatus: 'PROCESSING', processingTurnId: 3,
      lastTurnAt: '2026-01-01T00:00:02', gmtCreate: '2026-01-01T00:00:00',
      turns: [
        { id: 3, direction: 'INBOUND', content: '请给方案', status: 'PROCESSING', error: null, gmtCreate: '2026-01-01T00:00:02' },
      ],
    };
    server.use(
      http.get('/api/workitems/:workitemId/clarification-conversations', () =>
        HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: [processingConversation] }),
      ),
      http.get('/api/workitems/:workitemId/clarification-conversations/:conversationId', () =>
        HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: processingConversation }),
      ),
    );

    const view = renderPanel([]);
    await screen.findByText('请给方案');
    await waitFor(() => expect(realtime.callback).not.toBeNull());

    act(() => {
      realtime.callback!({
        type: 'CONVERSATION_TURN_EVENT',
        payload: { conversationId: 1, turnId: 3, eventSeq: 1, eventType: 'text', payload: { type: 'text', content: '最终方案' } },
      });
      realtime.callback!({
        type: 'CONVERSATION_TURN_EVENT',
        payload: { conversationId: 1, turnId: 3, eventSeq: 2, eventType: 'status', payload: { type: 'status', status: 'completed' } },
      });
    });

    expect(screen.getByText('最终方案')).toBeInTheDocument();
    expect(view.container.querySelector('.ant-spin')).toBeNull();
  });

  it('does not keep spinning after the AI reply is already persisted', async () => {
    writeClarificationPrefill('100', { squadId: 9, agentId: 42 });
    mockSquads();
    const processingConversation = {
      id: 1, agentId: 42, agentName: 'Agent-X', channelConversationId: 'ch-1',
      status: 'ACTIVE', executorOnline: true, streamingSupported: true,
      cliSessionRef: null, processingStatus: 'PROCESSING', processingTurnId: 3,
      lastTurnAt: '2026-01-01T00:00:03', gmtCreate: '2026-01-01T00:00:00',
      turns: [
        { id: 3, direction: 'INBOUND', content: '请给方案', status: 'COMPLETED', error: null, gmtCreate: '2026-01-01T00:00:02' },
        { id: 4, direction: 'OUTBOUND', content: '请选择 A、B 或 C', status: 'COMPLETED', error: null, gmtCreate: '2026-01-01T00:00:03' },
      ],
    };
    server.use(
      http.get('/api/workitems/:workitemId/clarification-conversations', () =>
        HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: [processingConversation] }),
      ),
      http.get('/api/workitems/:workitemId/clarification-conversations/:conversationId', () =>
        HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: processingConversation }),
      ),
    );

    const view = renderPanel([]);

    expect(await screen.findByText('请选择 A、B 或 C')).toBeInTheDocument();
    expect(view.container.querySelector('.ant-spin')).toBeNull();
  });

  it('sends message on Enter key press', async () => {
    writeClarificationPrefill('100', { squadId: 9, agentId: 42 });
    mockSquads();
    mockConversationWithTurns(42);
    let submitCalled = false;
    server.use(
      http.post('/api/workitems/:workitemId/clarification-conversations/:conversationId/turns', () => {
        submitCalled = true;
        return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: null });
      }),
    );
    renderPanel([]);
    const textarea = await screen.findByPlaceholderText('输入消息...');
    fireEvent.change(textarea, { target: { value: '你好' } });
    fireEvent.keyDown(textarea, { key: 'Enter', code: 'Enter' });
    await waitFor(() => expect(submitCalled).toBe(true));
  });

  it('restores the message and reports an error when sending fails', async () => {
    writeClarificationPrefill('100', { squadId: 9, agentId: 42 });
    mockSquads();
    mockConversationWithTurns(42);
    server.use(
      http.post('/api/workitems/:workitemId/clarification-conversations/:conversationId/turns', () =>
        HttpResponse.json(
          { success: false, code: 'SEND_FAILED', message: '发送失败', traceId: null, data: null },
          { status: 500 },
        ),
      ),
    );
    renderPanel([]);
    const textarea = await screen.findByPlaceholderText('输入消息...');
    fireEvent.change(textarea, { target: { value: '请保留这条消息' } });
    fireEvent.keyDown(textarea, { key: 'Enter', code: 'Enter' });

    await waitFor(() => expect(textarea).toHaveValue('请保留这条消息'));
    expect(await screen.findByText('消息发送失败，请重试')).toBeInTheDocument();
  });

  it('renders backend IN and INBOUND turns as user messages', async () => {
    writeClarificationPrefill('100', { squadId: 9, agentId: 42 });
    mockSquads();
    const conversation = {
      id: 1, agentId: 42, agentName: 'Agent-X', channelConversationId: 'ch-1',
      status: 'ACTIVE', executorOnline: true, streamingSupported: false,
      cliSessionRef: null, processingStatus: null, processingTurnId: null,
      lastTurnAt: '2026-01-01T00:00:01', gmtCreate: '2026-01-01T00:00:00',
      turns: [
        { id: 7, direction: 'IN', content: '我的需求', status: 'PROCESSING', error: null, gmtCreate: '2026-01-01T00:00:01' },
        { id: 8, direction: 'INBOUND', content: '历史需求', status: 'COMPLETED', error: null, gmtCreate: '2026-01-01T00:00:02' },
      ],
    };
    server.use(
      http.get('/api/workitems/:workitemId/clarification-conversations', () =>
        HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: [conversation] }),
      ),
      http.get('/api/workitems/:workitemId/clarification-conversations/:conversationId', () =>
        HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: conversation }),
      ),
    );

    renderPanel([]);

    expect(await screen.findByText('我的需求')).toBeInTheDocument();
    expect(screen.getByText('历史需求')).toBeInTheDocument();
    expect(screen.getAllByText('你')).toHaveLength(2);
  });

  it('renders persisted AI replies as markdown', async () => {
    writeClarificationPrefill('100', { squadId: 9, agentId: 42 });
    mockSquads();
    const conversation = {
      id: 1, agentId: 42, agentName: 'Agent-X', channelConversationId: 'ch-1',
      status: 'ACTIVE', executorOnline: true, streamingSupported: true,
      cliSessionRef: null, processingStatus: null, processingTurnId: null,
      lastTurnAt: '2026-01-01T00:00:03', gmtCreate: '2026-01-01T00:00:00',
      turns: [
        { id: 3, direction: 'IN', content: '请给方案', status: 'COMPLETED', error: null, gmtCreate: '2026-01-01T00:00:02' },
        { id: 4, direction: 'OUT', content: '**需求目标**：完成澄清', status: 'COMPLETED', error: null, gmtCreate: '2026-01-01T00:00:03' },
      ],
    };
    server.use(
      http.get('/api/workitems/:workitemId/clarification-conversations', () =>
        HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: [conversation] }),
      ),
      http.get('/api/workitems/:workitemId/clarification-conversations/:conversationId', () =>
        HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: conversation }),
      ),
    );

    renderPanel([]);

    expect((await screen.findByText('需求目标')).tagName).toBe('STRONG');
  });

  it('does not send on Shift+Enter and preserves input content', async () => {
    writeClarificationPrefill('100', { squadId: 9, agentId: 42 });
    mockSquads();
    mockConversationWithTurns(42);
    let submitCalled = false;
    server.use(
      http.post('/api/workitems/:workitemId/clarification-conversations/:conversationId/turns', () => {
        submitCalled = true;
        return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: null });
      }),
    );
    renderPanel([]);
    const textarea = await screen.findByPlaceholderText('输入消息...');
    fireEvent.change(textarea, { target: { value: '第一行' } });
    fireEvent.keyDown(textarea, { key: 'Enter', code: 'Enter', shiftKey: true });
    await new Promise((r) => setTimeout(r, 100));
    expect(submitCalled).toBe(false);
    expect(textarea).toHaveValue('第一行');
  });

  it('does not send when Enter is pressed during IME composition', async () => {
    writeClarificationPrefill('100', { squadId: 9, agentId: 42 });
    mockSquads();
    mockConversationWithTurns(42);
    let submitCalled = false;
    server.use(
      http.post('/api/workitems/:workitemId/clarification-conversations/:conversationId/turns', () => {
        submitCalled = true;
        return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: null });
      }),
    );
    renderPanel([]);
    const textarea = await screen.findByPlaceholderText('输入消息...');
    fireEvent.change(textarea, { target: { value: '候选' } });
    fireEvent.keyDown(textarea, { key: 'Enter', code: 'Enter', isComposing: true });
    await new Promise((r) => setTimeout(r, 100));
    expect(submitCalled).toBe(false);
    expect(textarea).toHaveValue('候选');
  });

  it('renders a placeholder and error for AI turns persisted with empty content', async () => {
    writeClarificationPrefill('100', { squadId: 9, agentId: 42 });
    mockSquads();
    const conversation = {
      id: 1, agentId: 42, agentName: 'Agent-X', channelConversationId: 'ch-1',
      status: 'ACTIVE', executorOnline: true, streamingSupported: true,
      cliSessionRef: null, processingStatus: null, processingTurnId: null,
      lastTurnAt: '2026-01-01T00:00:03', gmtCreate: '2026-01-01T00:00:00',
      turns: [
        { id: 3, direction: 'IN', content: '问题9的答案', status: 'COMPLETED', error: null, gmtCreate: '2026-01-01T00:00:02' },
        { id: 4, direction: 'OUT', content: '', status: 'FAILED', error: 'provider error', gmtCreate: '2026-01-01T00:00:03' },
      ],
    };
    server.use(
      http.get('/api/workitems/:workitemId/clarification-conversations', () =>
        HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: [conversation] }),
      ),
      http.get('/api/workitems/:workitemId/clarification-conversations/:conversationId', () =>
        HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: conversation }),
      ),
    );

    renderPanel([]);

    expect(await screen.findByText('问题9的答案')).toBeInTheDocument();
    expect(screen.getByText('（未返回内容）')).toBeInTheDocument();
    expect(screen.getByText('provider error')).toBeInTheDocument();
  });

  it('auto creates a conversation when switching to an agent without history', async () => {
    let createCalls = 0;
    const created = {
      id: 9, agentId: 2, agentName: 'Agent-B', channelConversationId: 'ch-9',
      status: 'ACTIVE', executorOnline: true, streamingSupported: true,
      cliSessionRef: null, processingStatus: null, processingTurnId: null,
      lastTurnAt: null, gmtCreate: '2026-01-01T00:00:00', turns: [],
    };
    server.use(
      http.get('/api/workitems/:workitemId/clarification-conversations', () =>
        HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: [] }),
      ),
      http.post('/api/workitems/:workitemId/clarification-conversations', async ({ request }) => {
        createCalls += 1;
        const body = await request.json() as { agentId?: number };
        expect(body.agentId).toBe(2);
        return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: created });
      }),
      http.get('/api/workitems/:workitemId/clarification-conversations/:conversationId', () =>
        HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: created }),
      ),
    );

    renderPanel([
      { agentId: 1, agentName: 'Agent-A', status: 'active' },
      { agentId: 2, agentName: 'Agent-B', status: 'active' },
    ]);

    fireEvent.click(await screen.findByText('Agent-B'));

    await waitFor(() => expect(createCalls).toBe(1));
    expect(await screen.findByPlaceholderText('输入消息...')).toBeInTheDocument();
    expect(screen.queryByText('暂无对话')).not.toBeInTheDocument();
  });

  it('does not auto-retry a failed conversation creation; manual retry via 新对话 still works', async () => {
    let createCalls = 0;
    server.use(
      http.get('/api/workitems/:workitemId/clarification-conversations', () =>
        HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: [] }),
      ),
      http.post('/api/workitems/:workitemId/clarification-conversations', () => {
        createCalls += 1;
        return HttpResponse.json(
          { success: false, code: 'CREATE_FAILED', message: '创建失败', traceId: null, data: null },
          { status: 500 },
        );
      }),
    );

    renderPanel([
      { agentId: 1, agentName: 'Agent-A', status: 'active' },
      { agentId: 2, agentName: 'Agent-B', status: 'active' },
    ]);

    fireEvent.click(await screen.findByText('Agent-B'));

    await waitFor(() => expect(createCalls).toBe(1));
    expect(await screen.findByText('自动创建会话失败，请点击「新对话」重试')).toBeInTheDocument();

    // Give effects time to re-run; a regression would fire more POSTs here.
    await new Promise((r) => setTimeout(r, 200));
    expect(createCalls).toBe(1);
    expect(screen.getByText('暂无对话')).toBeInTheDocument();

    fireEvent.click(screen.getByText('新对话'));
    await waitFor(() => expect(createCalls).toBe(2));
  });

  describe('input height resize', () => {
    beforeEach(() => {
      if (!Element.prototype.setPointerCapture) {
        Element.prototype.setPointerCapture = vi.fn();
      }
      document.body.style.userSelect = '';
    });

    it('computes the height upper limit as panel height × 60% with a 32px floor', () => {
      expect(computeInputHeightMax(400)).toBe(240);
      expect(computeInputHeightMax(280)).toBe(168);
      // floor(40 × 0.6) = 24 → 被 32px 下限兜底
      expect(computeInputHeightMax(40)).toBe(32);
      // 面板高度未知时按保底 280px 计算
      expect(computeInputHeightMax(0)).toBe(168);
      expect(computeInputHeightMax(Number.NaN)).toBe(168);
    });

    it('defaults the auto-mode input to 6 rows and disables autoSize in manual mode', () => {
      expect(CLARIFICATION_INPUT_DEFAULT_ROWS).toBe(6);
      expect(clarificationInputAutoSize(null)).toEqual({ minRows: 6, maxRows: 6 });
      // 手动固定高度模式下关闭 autoSize，由内联 height 接管
      expect(clarificationInputAutoSize(120)).toBe(false);
      expect(clarificationInputAutoSize(32)).toBe(false);
    });

    it('renders the default 6-row auto mode without a fixed height and exposes a vertical resize handle', async () => {
      const { textarea } = await renderActiveConversation();
      // 自动模式：不施加我们自己的固定高度/滚动样式（6 行默认高度由 antd autoSize 应用，
      // jsdom 下 antd 可能写入自身测量样式，故不做强断言）
      expect(textarea.style.overflowY).not.toBe('auto');
      const handle = screen.getByTestId('resize-handle-vertical');
      expect(handle).toHaveStyle({ cursor: 'row-resize' });
    });

    it('switches to a fixed height on drag and clamps to [32px, panel height × 60%]', async () => {
      const { view, textarea } = await renderActiveConversation();
      const panel = view.container.firstChild as HTMLElement;
      const wrapper = textarea.parentElement as HTMLElement;
      mockElementHeight(panel, 400);
      mockElementHeight(wrapper, 60);

      const handle = screen.getByTestId('resize-handle-vertical');
      firePointer('pointerdown', handle, { clientX: 0, clientY: 200 });
      // 向上拖 100px → 60 + 100 = 160，进入手动固定高度模式
      firePointer('pointermove', handle, { clientX: 0, clientY: 100 });
      expect(textarea).toHaveStyle({ height: '160px', overflowY: 'auto' });

      // 继续向上拖出上限：60 + 200 = 260 → 钳制到 400 × 0.6 = 240
      firePointer('pointermove', handle, { clientX: 0, clientY: 0 });
      expect(textarea).toHaveStyle({ height: '240px' });

      // 向下拖出下限 → 钳制到 32
      firePointer('pointermove', handle, { clientX: 0, clientY: 400 });
      expect(textarea).toHaveStyle({ height: '32px' });

      firePointer('pointerup', handle, { clientX: 0, clientY: 400 });
      // 拖拽结束后移动不再改变高度
      firePointer('pointermove', handle, { clientX: 0, clientY: 100 });
      expect(textarea).toHaveStyle({ height: '32px' });
    });

    it('restores auto sizing mode when the handle is double clicked', async () => {
      const { view, textarea } = await renderActiveConversation();
      mockElementHeight(view.container.firstChild as HTMLElement, 400);
      mockElementHeight(textarea.parentElement as HTMLElement, 60);

      const handle = screen.getByTestId('resize-handle-vertical');
      firePointer('pointerdown', handle, { clientX: 0, clientY: 200 });
      firePointer('pointermove', handle, { clientX: 0, clientY: 100 });
      expect(textarea).toHaveStyle({ height: '160px' });
      firePointer('pointerup', handle, { clientX: 0, clientY: 100 });

      fireEvent.doubleClick(handle);
      // 双击后回到自动模式：手动固定高度与滚动样式被移除
      expect(textarea.style.height).not.toBe('160px');
      expect(textarea.style.overflowY).not.toBe('auto');
    });

    it('re-clamps a stored manual height after the panel shrinks', async () => {
      const { view, textarea } = await renderActiveConversation();
      const panel = view.container.firstChild as HTMLElement;
      mockElementHeight(panel, 400);
      mockElementHeight(textarea.parentElement as HTMLElement, 60);

      const handle = screen.getByTestId('resize-handle-vertical');
      firePointer('pointerdown', handle, { clientX: 0, clientY: 200 });
      firePointer('pointermove', handle, { clientX: 0, clientY: 0 });
      expect(textarea).toHaveStyle({ height: '240px' });
      firePointer('pointerup', handle, { clientX: 0, clientY: 0 });

      // 面板被 50386 拖小后，已存的手动高度随新上限收敛
      mockElementHeight(panel, 200);
      fireEvent.change(textarea, { target: { value: '触发重渲染' } });
      expect(textarea).toHaveStyle({ height: '120px' });
    });
  });
});
