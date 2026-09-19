import { describe, it, expect, beforeEach, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import {
  WorkitemClarificationPanel,
  buildDeliveryAgentOptions,
  computeInputHeightMax,
  clarificationInputAutoSize,
  CLARIFICATION_INPUT_DEFAULT_ROWS,
} from './WorkitemClarificationPanel';
import {
  clearClarificationPrefill,
  readClarificationPrefill,
  writeClarificationPrefill,
} from './prefill';
import { CLARIFICATION_THEME } from './theme';
import { readClarifyView, writeClarifyView } from '../clarifyView';
import { copyTextToClipboard } from '@/shared/lib/clipboard';

type RealtimeCallback = (event: { type: string; payload: unknown }) => void;
const realtime = vi.hoisted(() => ({ callback: null as RealtimeCallback | null }));

vi.mock('@/shared/realtime/useRealtime', () => ({
  useRealtime: (_channel: unknown, opts: { onEvent: RealtimeCallback }) => {
    realtime.callback = opts.onEvent;
  },
}));

// follow 仓库既有模式（shared/ui/CopyContentMenu.test.tsx）：拦库函数而不是 stub
// navigator.clipboard
vi.mock('@/shared/lib/clipboard', () => ({
  copyTextToClipboard: vi.fn().mockResolvedValue(true),
}));

/** 面板上报的上下文：只带「已经落定」的字段（CR53035-001）。 */
type ReportedClarifyContext = {
  agentId?: number | null;
  conversationId?: number | null;
};

type PanelTestProps = {
  onAgentConfirmed?: () => void;
  fullscreen?: boolean;
  initialAgentId?: number | null;
  initialConversationId?: number | null;
  onContextChange?: (context: ReportedClarifyContext) => void;
};

function renderPanel(
  agents: Array<{ agentId: number; agentName: string; status: string }> = [],
  props: PanelTestProps = {},
) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <WorkitemClarificationPanel
        workitemId="100"
        agents={agents as never}
        {...props}
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

function makeConversation(
  id: number,
  message: string,
  options: { processing?: boolean; gmtCreate?: string; lastTurnAt?: string | null } = {},
) {
  const processing = options.processing ?? false;
  const gmtCreate = options.gmtCreate ?? '2026-01-01T00:00:00';
  return {
    id, agentId: 42, agentName: 'Agent-X', channelConversationId: `ch-${id}`,
    status: 'ACTIVE', executorOnline: true, streamingSupported: true,
    cancelSupported: true, cliSessionRef: null,
    processingStatus: processing ? 'PROCESSING' : null,
    processingTurnId: processing ? id * 10 : null,
    lastTurnAt: options.lastTurnAt ?? gmtCreate,
    gmtCreate,
    turns: [{
      id: id * 10,
      direction: 'IN',
      content: message,
      status: processing ? 'PROCESSING' : 'SUCCESS',
      error: null,
      gmtCreate,
    }],
  };
}

/** 处理中会话：一条已落库的用户提问 + 正在流式回复的 turn 3。
 *  D4 的两个流式用例共用它。 */
const PROCESSING_CONVERSATION = {
  id: 1, agentId: 42, agentName: 'Agent-X', channelConversationId: 'ch-1',
  status: 'ACTIVE', executorOnline: true, streamingSupported: true,
  cliSessionRef: null, processingStatus: 'PROCESSING', processingTurnId: 3,
  lastTurnAt: '2026-01-01T00:00:02', gmtCreate: '2026-01-01T00:00:00',
  turns: [
    { id: 3, direction: 'INBOUND', content: '请给方案', status: 'PROCESSING', error: null, gmtCreate: '2026-01-01T00:00:02' },
  ],
};

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

async function renderActiveConversation(props: PanelTestProps = {}) {
  writeClarificationPrefill('100', { squadId: 9, agentId: 42 });
  mockSquads();
  mockConversationWithTurns(42);
  const view = renderPanel([], props);
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
    expect(screen.getByPlaceholderText('输入消息...')).toHaveValue('');
  });

  it('prefills the bootstrap prompt only for an automatically created first session', async () => {
    writeClarificationPrefill('100', { squadId: 9, agentId: 42 });
    mockSquads();
    const created = {
      ...makeConversation(5, 'unused'),
      turns: [],
    };
    server.use(
      http.get('/api/workitems/:workitemId/clarification-conversations', () =>
        HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: [] }),
      ),
      http.post('/api/workitems/:workitemId/clarification-conversations', () =>
        HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: created }),
      ),
      http.get('/api/workitems/:workitemId/clarification-conversations/:conversationId', () =>
        HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: created }),
      ),
    );

    renderPanel([]);

    expect(await screen.findByDisplayValue(
      '请通过 AutoWonder MCP 读取工单 #100，与我进行需求澄清。',
    )).toBeInTheDocument();
  });

  it('clears prefill for unrelated workitems', () => {
    writeClarificationPrefill('100', { squadId: 9, agentId: 42 });
    clearClarificationPrefill('100');
    expect(readClarificationPrefill('100')).toBeNull();
  });

  it('labels AI messages with the agent name and leaves user messages unlabeled', async () => {
    writeClarificationPrefill('100', { squadId: 9, agentId: 42 });
    mockSquads();
    mockConversationWithTurns(42);
    renderPanel([]);

    await waitFor(() => {
      expect(screen.getByText('你好，我想讨论需求')).toBeInTheDocument();
    });

    // AI 侧带数字人名字；用户侧不再有「你」标签（Codex 风格）
    expect(screen.getAllByText('Agent-X').length).toBeGreaterThan(0);
    expect(screen.queryByText('你')).not.toBeInTheDocument();
    expect(screen.getByText('好的，请说说你的想法')).toBeInTheDocument();
  });

  it('distinguishes user and AI messages: bubble for user, flat for AI', async () => {
    writeClarificationPrefill('100', { squadId: 9, agentId: 42 });
    mockSquads();
    mockConversationWithTurns(42);
    renderPanel([]);

    await waitFor(() => {
      expect(screen.getByText('你好，我想讨论需求')).toBeInTheDocument();
    });

    // 用户侧仍是有底色的气泡
    const userMsg = screen.getByText('你好，我想讨论需求').closest('div[style*="background-color"]');
    expect(userMsg).not.toBeNull();
    expect(userMsg).toHaveStyle({ backgroundColor: '#f4f4f5' });

    // AI 侧平铺：先确认 AI 文本真的渲染出来了（否则下面的 toBeNull 会空洞地通过），
    // 再证明从文本往上找不到任何带 background-color 的祖先气泡
    const aiText = screen.getByText('好的，请说说你的想法');
    expect(aiText).toBeInTheDocument();
    const aiBlock = screen.getByTestId('clarification-agent-block');
    expect(aiBlock).toContainElement(aiText);
    expect(aiText.closest('div[style*="background-color"]')).toBeNull();

    // 与流式态同款保护（clarification-streaming-bubble 已有）：光排除祖先底色不够，
    // 直接往这一层内联加 background/border 也必须红，否则平铺设计能被悄悄改回气泡
    expect(aiBlock.style.backgroundColor).toBe('');
    expect(aiBlock.style.border).toBe('');
  });

  it('keeps pre-wrap for plain user text and never applies it to agent markdown', async () => {
    writeClarificationPrefill('100', { squadId: 9, agentId: 42 });
    mockSquads();
    mockConversationWithTurns(42);
    renderPanel([]);

    await waitFor(() => {
      expect(screen.getByText('你好，我想讨论需求')).toBeInTheDocument();
    });

    // 用户纯文本保留 pre-wrap（保留手输入换行）
    const userBubble = screen.getByText('你好，我想讨论需求').closest('div[style*="background-color"]');
    expect(userBubble).toHaveStyle({ whiteSpace: 'pre-wrap' });

    // agent markdown 平铺容器不能带 pre-wrap：继承它会把块级元素间的换行
    // 渲染成字面空行，产生完成后的大块行间距空白
    const aiBlock = screen.getByTestId('clarification-agent-block');
    expect(aiBlock).not.toHaveStyle({ whiteSpace: 'pre-wrap' });
  });

  it('scopes the completed AI reply markdown with the aw-clarify-md class', async () => {
    writeClarificationPrefill('100', { squadId: 9, agentId: 42 });
    mockSquads();
    mockConversationWithTurns(42);
    renderPanel([]);

    // 代码块/表格/引用的浅色样式全挂在 .aw-clarify-md 作用域下（clarification.css）。
    // 这个类此前只在 ConversationEventView 的用例里被断言过，主路径（TurnBubble）
    // 掉了它，全部 markdown 样式会静默失效而测试全绿。
    const aiBlock = await screen.findByTestId('clarification-agent-block');
    const markdown = aiBlock.querySelector('.aw-clarify-md');
    expect(markdown).not.toBeNull();
    expect(markdown).toContainElement(screen.getByText('好的，请说说你的想法'));
  });

  it('renders the streaming reply with the same flat style as completed replies', async () => {
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

    // 回复过程中与完成态取同一个 agentBlockStyle()，保证前后渲染一致
    const bubble = await screen.findByTestId('clarification-streaming-bubble');
    expect(bubble).toHaveStyle({
      fontSize: '13px',
      lineHeight: '1.75',
    });
    expect(bubble.style.backgroundColor).toBe('');
    expect(bubble.style.border).toBe('');
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

  it('sends message on Shift+Enter in the default send mode', async () => {
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
    await screen.findByText('你好，我想讨论需求');
    fireEvent.change(textarea, { target: { value: '你好' } });
    fireEvent.keyDown(textarea, { key: 'Enter', code: 'Enter', shiftKey: true });
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
    fireEvent.keyDown(textarea, { key: 'Enter', code: 'Enter', shiftKey: true });

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
    // 「你」标签已按方案 A 移除，用户侧改以「右对齐 + 用户气泡底色」为证据
    const userRowOf = (text: string) => {
      const bubble = screen.getByText(text).closest('div[style*="background-color"]');
      expect(bubble).toHaveStyle({ backgroundColor: '#f4f4f5' });
      return bubble!.closest('div[style*="justify-content"]');
    };
    expect(userRowOf('我的需求')).toHaveStyle({ justifyContent: 'flex-end' });
    expect(userRowOf('历史需求')).toHaveStyle({ justifyContent: 'flex-end' });
    // 两条都不该走 AI 平铺分支
    expect(screen.queryByTestId('clarification-agent-block')).toBeNull();
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

  it('does not send on plain Enter in the default send mode and preserves input content', async () => {
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
    fireEvent.keyDown(textarea, { key: 'Enter', code: 'Enter' });
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
    fireEvent.keyDown(textarea, { key: 'Enter', code: 'Enter', shiftKey: true, isComposing: true });
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

  it('lists current-agent sessions and loads messages from the selected historical session', async () => {
    writeClarificationPrefill('100', { squadId: 9, agentId: 42 });
    mockSquads();
    const latest = makeConversation(202, '最新会话消息', {
      gmtCreate: '2026-01-02T00:00:00', lastTurnAt: '2026-01-02T01:02:03',
    });
    const historical = makeConversation(101, '历史会话消息', {
      gmtCreate: '2026-01-01T00:00:00', lastTurnAt: '2026-01-01T01:02:03',
    });
    let listedAgentId: string | null = null;
    server.use(
      http.get('/api/workitems/:workitemId/clarification-conversations', ({ request }) => {
        listedAgentId = new URL(request.url).searchParams.get('agentId');
        return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: [latest, historical] });
      }),
      http.get('/api/workitems/:workitemId/clarification-conversations/:conversationId', ({ params }) => {
        const conversation = [latest, historical].find((item) => item.id === Number(params.conversationId));
        return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: conversation });
      }),
    );

    renderPanel([]);

    expect(await screen.findByText('最新会话消息')).toBeInTheDocument();
    await waitFor(() => expect(listedAgentId).toBe('42'));
    expect(screen.getByPlaceholderText('输入消息...')).toHaveValue('');

    const selector = screen.getByTestId('clarification-conversation-select');
    fireEvent.mouseDown(within(selector).getByRole('combobox'));
    expect(await screen.findByText(/会话 #101/)).toBeInTheDocument();
    expect(screen.getAllByText(/会话 #202/)).not.toHaveLength(0);
    expect(screen.getByText(`最近 ${new Date('2026-01-01T01:02:03').toLocaleString('zh-CN')}`)).toBeInTheDocument();
    expect(screen.getByText(`创建 ${new Date('2026-01-01T00:00:00').toLocaleString('zh-CN')}`)).toBeInTheDocument();
    expect(screen.getAllByText(`最近 ${new Date('2026-01-02T01:02:03').toLocaleString('zh-CN')}`)).not.toHaveLength(0);
    expect(screen.getAllByText(`创建 ${new Date('2026-01-02T00:00:00').toLocaleString('zh-CN')}`)).not.toHaveLength(0);

    fireEvent.click(screen.getByText(/会话 #101/));

    expect(await screen.findByText('历史会话消息')).toBeInTheDocument();
    expect(screen.queryByText('最新会话消息')).toBeNull();
  });

  it('does not inject a bootstrap prompt when an existing session detail fails to load', async () => {
    writeClarificationPrefill('100', { squadId: 9, agentId: 42 });
    mockSquads();
    const latest = makeConversation(202, '最新会话消息');
    server.use(
      http.get('/api/workitems/:workitemId/clarification-conversations', () =>
        HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: [latest] }),
      ),
      http.get('/api/workitems/:workitemId/clarification-conversations/:conversationId', () =>
        HttpResponse.json(
          { success: false, code: 'BOOM', message: '加载失败', traceId: null, data: null },
          { status: 500 },
        ),
      ),
    );

    renderPanel([]);
    const textarea = await screen.findByPlaceholderText('输入消息...');

    // 历史未知（缺陷一）：显式报错而不是空白，输入受限，也不塞引导提示词
    expect(await screen.findByTestId('clarification-history-error')).toBeInTheDocument();
    expect(textarea).toHaveValue('');
    expect(textarea).toBeDisabled();
  });

  it('does not render old realtime thinking after selecting a historical conversation', async () => {
    writeClarificationPrefill('100', { squadId: 9, agentId: 42 });
    mockSquads();
    const latest = makeConversation(202, '最新会话消息', { processing: true });
    const historical = makeConversation(101, '历史会话消息');
    server.use(
      http.get('/api/workitems/:workitemId/clarification-conversations', () =>
        HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: [latest, historical] }),
      ),
      http.get('/api/workitems/:workitemId/clarification-conversations/:conversationId', ({ params }) => {
        const conversation = Number(params.conversationId) === historical.id ? historical : latest;
        return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: conversation });
      }),
    );

    renderPanel([]);
    await screen.findByText('最新会话消息');
    await waitFor(() => expect(realtime.callback).not.toBeNull());
    act(() => {
      realtime.callback!({
        type: 'CONVERSATION_TURN_EVENT',
        payload: {
          conversationId: latest.id,
          turnId: latest.processingTurnId,
          eventSeq: 1,
          eventType: 'thinking',
          payload: { type: 'thinking', content: '旧会话流式思考' },
        },
      });
    });
    expect(await screen.findByText('旧会话流式思考')).toBeInTheDocument();

    const selector = screen.getByTestId('clarification-conversation-select');
    fireEvent.mouseDown(within(selector).getByRole('combobox'));
    fireEvent.click(await screen.findByText(/会话 #101/));

    expect(await screen.findByText('历史会话消息')).toBeInTheDocument();
    expect(screen.queryByText('旧会话流式思考')).toBeNull();
  });

  it('clears a draft before switching to another delivery agent', async () => {
    const agentAConversation = {
      ...makeConversation(201, '数字人 A 的会话消息'),
      agentId: 1,
      agentName: 'Agent-A',
    };
    const agentBConversation = {
      ...makeConversation(202, '数字人 B 的会话消息'),
      agentId: 2,
      agentName: 'Agent-B',
    };
    server.use(
      http.get('/api/workitems/:workitemId/clarification-conversations', ({ request }) => {
        const agentId = new URL(request.url).searchParams.get('agentId');
        const conversations = agentId === '1' ? [agentAConversation] : [agentBConversation];
        return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: conversations });
      }),
      http.get('/api/workitems/:workitemId/clarification-conversations/:conversationId', ({ params }) => {
        const conversation = Number(params.conversationId) === 201 ? agentAConversation : agentBConversation;
        return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: conversation });
      }),
    );

    renderPanel([
      { agentId: 1, agentName: 'Agent-A', status: 'active' },
      { agentId: 2, agentName: 'Agent-B', status: 'active' },
    ]);
    fireEvent.click(await screen.findByText('Agent-A'));

    const textarea = await screen.findByPlaceholderText('输入消息...');
    await screen.findByText('数字人 A 的会话消息');
    fireEvent.change(textarea, { target: { value: '只属于数字人 A 的草稿' } });

    fireEvent.click(screen.getByRole('button', { name: '切换数字人' }));
    fireEvent.click(await screen.findByText('Agent-B'));

    expect(await screen.findByText('数字人 B 的会话消息')).toBeInTheDocument();
    expect(screen.getByPlaceholderText('输入消息...')).toHaveValue('');
  });

  it('clears a draft before creating a new conversation', async () => {
    writeClarificationPrefill('100', { squadId: 9, agentId: 42 });
    mockSquads();
    const existing = makeConversation(202, '当前会话消息');
    const created = { ...makeConversation(303, '新会话消息'), turns: [] };
    server.use(
      http.get('/api/workitems/:workitemId/clarification-conversations', () =>
        HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: [existing, created] }),
      ),
      http.post('/api/workitems/:workitemId/clarification-conversations', () =>
        HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: created }),
      ),
      http.get('/api/workitems/:workitemId/clarification-conversations/:conversationId', ({ params }) => {
        const conversation = Number(params.conversationId) === created.id ? created : existing;
        return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: conversation });
      }),
    );

    renderPanel([]);
    const textarea = await screen.findByPlaceholderText('输入消息...');
    await screen.findByText('当前会话消息');
    fireEvent.change(textarea, { target: { value: '不应带入新会话的草稿' } });

    fireEvent.click(screen.getByRole('button', { name: /新对话/ }));

    await waitFor(() => expect(screen.getByTestId('clarification-conversation-select')).toHaveTextContent('会话 #303'));
    expect(textarea).toHaveValue('');
  });

  it('submits to the historical conversation after switching back to it', async () => {
    writeClarificationPrefill('100', { squadId: 9, agentId: 42 });
    mockSquads();
    const latest = makeConversation(202, '最新会话消息', { gmtCreate: '2026-01-02T00:00:00' });
    const historical = makeConversation(101, '历史会话消息');
    let submittedUrl = '';
    server.use(
      http.get('/api/workitems/:workitemId/clarification-conversations', () =>
        HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: [latest, historical] }),
      ),
      http.get('/api/workitems/:workitemId/clarification-conversations/:conversationId', ({ params }) => {
        const conversation = [latest, historical].find((item) => item.id === Number(params.conversationId));
        return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: conversation });
      }),
      http.post('/api/workitems/:workitemId/clarification-conversations/:conversationId/turns', ({ request }) => {
        submittedUrl = request.url;
        return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: null });
      }),
    );

    renderPanel([]);
    await screen.findByText('最新会话消息');

    const selector = screen.getByTestId('clarification-conversation-select');
    fireEvent.mouseDown(within(selector).getByRole('combobox'));
    fireEvent.click(await screen.findByText(/会话 #101/));
    expect(await screen.findByText('历史会话消息')).toBeInTheDocument();

    const textarea = screen.getByPlaceholderText('输入消息...');
    fireEvent.change(textarea, { target: { value: '继续历史会话' } });
    fireEvent.keyDown(textarea, { key: 'Enter', code: 'Enter', shiftKey: true });

    await waitFor(() => expect(submittedUrl).toContain('/clarification-conversations/101/turns'));
  });

  it('keeps existing sessions selectable after creating a new conversation', async () => {
    writeClarificationPrefill('100', { squadId: 9, agentId: 42 });
    mockSquads();
    const historical = makeConversation(101, '历史会话消息');
    const created = makeConversation(303, '新会话消息', { gmtCreate: '2026-01-03T00:00:00' });
    const conversations = [historical];
    let createCalls = 0;
    server.use(
      http.get('/api/workitems/:workitemId/clarification-conversations', () =>
        HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: conversations }),
      ),
      http.post('/api/workitems/:workitemId/clarification-conversations', () => {
        createCalls += 1;
        conversations.unshift(created);
        return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: created });
      }),
      http.get('/api/workitems/:workitemId/clarification-conversations/:conversationId', ({ params }) => {
        const conversation = conversations.find((item) => item.id === Number(params.conversationId));
        return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: conversation });
      }),
    );

    renderPanel([]);
    await screen.findByText('历史会话消息');

    fireEvent.click(screen.getByRole('button', { name: /新对话/ }));

    await waitFor(() => expect(createCalls).toBe(1));
    await waitFor(() => expect(screen.getByTestId('clarification-conversation-select')).toHaveTextContent('会话 #303'));

    const selector = screen.getByTestId('clarification-conversation-select');
    fireEvent.mouseDown(within(selector).getByRole('combobox'));
    expect((await screen.findAllByText(/会话 #303/)).length).toBeGreaterThan(1);
    expect(screen.getAllByText(/会话 #101/)).not.toHaveLength(0);
    fireEvent.click(screen.getByText(/会话 #101/));

    expect(await screen.findByText('历史会话消息')).toBeInTheDocument();
  });

  it('disables historical-session selection until a new conversation request completes', async () => {
    writeClarificationPrefill('100', { squadId: 9, agentId: 42 });
    mockSquads();
    const latest = makeConversation(202, '当前会话消息');
    const historical = makeConversation(101, '历史会话消息');
    const created = makeConversation(303, '新会话消息');
    const conversations = [latest, historical];
    let resolveCreate: (() => void) | undefined;
    const createGate = new Promise<void>((resolve) => {
      resolveCreate = resolve;
    });
    let createStarted = false;
    server.use(
      http.get('/api/workitems/:workitemId/clarification-conversations', () =>
        HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: conversations }),
      ),
      http.post('/api/workitems/:workitemId/clarification-conversations', async () => {
        createStarted = true;
        await createGate;
        conversations.unshift(created);
        return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: created });
      }),
      http.get('/api/workitems/:workitemId/clarification-conversations/:conversationId', ({ params }) => {
        const conversation = conversations.find((item) => item.id === Number(params.conversationId));
        return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: conversation });
      }),
    );

    renderPanel([]);
    await screen.findByText('当前会话消息');
    fireEvent.click(screen.getByRole('button', { name: /新对话/ }));
    await waitFor(() => expect(createStarted).toBe(true));

    const selector = screen.getByTestId('clarification-conversation-select');
    expect(within(selector).getByRole('combobox')).toBeDisabled();
    expect(screen.getByRole('button', { name: '切换数字人' })).toBeDisabled();
    expect(screen.getByPlaceholderText('输入消息...')).toBeDisabled();
    expect(screen.getByRole('button', { name: '发送消息' })).toBeDisabled();

    resolveCreate!();
    await waitFor(() => expect(selector).toHaveTextContent('会话 #303'));
  });

  it('clears the draft on session change and keeps a processing session composer disabled', async () => {
    writeClarificationPrefill('100', { squadId: 9, agentId: 42 });
    mockSquads();
    const processing = makeConversation(202, '正在处理的会话消息', {
      processing: true, gmtCreate: '2026-01-02T00:00:00',
    });
    const historical = makeConversation(101, '可继续的历史会话消息');
    server.use(
      http.get('/api/workitems/:workitemId/clarification-conversations', () =>
        HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: [processing, historical] }),
      ),
      http.get('/api/workitems/:workitemId/clarification-conversations/:conversationId', ({ params }) => {
        const conversation = [processing, historical].find((item) => item.id === Number(params.conversationId));
        return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: conversation });
      }),
    );

    renderPanel([]);
    const textarea = await screen.findByPlaceholderText('输入消息...');
    expect(await screen.findByText('正在处理的会话消息')).toBeInTheDocument();
    expect(textarea).toBeDisabled();

    let selector = screen.getByTestId('clarification-conversation-select');
    fireEvent.mouseDown(within(selector).getByRole('combobox'));
    fireEvent.click(await screen.findByText(/会话 #101/));
    expect(await screen.findByText('可继续的历史会话消息')).toBeInTheDocument();
    await waitFor(() => expect(textarea).not.toBeDisabled());
    fireEvent.change(textarea, { target: { value: '不应带到处理中会话的草稿' } });

    selector = screen.getByTestId('clarification-conversation-select');
    fireEvent.mouseDown(within(selector).getByRole('combobox'));
    fireEvent.click(await screen.findByText(/会话 #202/));

    await waitFor(() => {
      expect(textarea).toBeDisabled();
      expect(textarea).toHaveValue('');
    });
  });

  it('keeps the composer empty after selecting an empty historical session', async () => {
    writeClarificationPrefill('100', { squadId: 9, agentId: 42 });
    mockSquads();
    const latest = makeConversation(202, '当前会话消息');
    const historical = {
      ...makeConversation(101, '不会显示的消息'),
      agentName: '空历史会话数字人',
      turns: [],
    };
    server.use(
      http.get('/api/workitems/:workitemId/clarification-conversations', () =>
        HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: [latest, historical] }),
      ),
      http.get('/api/workitems/:workitemId/clarification-conversations/:conversationId', ({ params }) => {
        const conversation = [latest, historical].find((item) => item.id === Number(params.conversationId));
        return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: conversation });
      }),
    );

    renderPanel([]);
    const textarea = await screen.findByPlaceholderText('输入消息...');
    await screen.findByText('当前会话消息');

    const selector = screen.getByTestId('clarification-conversation-select');
    fireEvent.mouseDown(within(selector).getByRole('combobox'));
    fireEvent.click(await screen.findByText(/会话 #101/));

    await screen.findByText('空历史会话数字人');
    expect(textarea).toHaveValue('');
  });

  it('does not restore a failed send draft into a different selected session', async () => {
    writeClarificationPrefill('100', { squadId: 9, agentId: 42 });
    mockSquads();
    const latest = makeConversation(202, '当前会话消息');
    const historical = makeConversation(101, '历史会话消息');
    let resolveFailedSubmit: (() => void) | undefined;
    const failedSubmit = new Promise<void>((resolve) => {
      resolveFailedSubmit = resolve;
    });
    let submitStarted = false;
    server.use(
      http.get('/api/workitems/:workitemId/clarification-conversations', () =>
        HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: [latest, historical] }),
      ),
      http.get('/api/workitems/:workitemId/clarification-conversations/:conversationId', ({ params }) => {
        const conversation = [latest, historical].find((item) => item.id === Number(params.conversationId));
        return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: conversation });
      }),
      http.post('/api/workitems/:workitemId/clarification-conversations/:conversationId/turns', async () => {
        submitStarted = true;
        await failedSubmit;
        return HttpResponse.json(
          { success: false, code: 'SEND_FAILED', message: '发送失败', traceId: null, data: null },
          { status: 500 },
        );
      }),
    );

    renderPanel([]);
    const textarea = await screen.findByPlaceholderText('输入消息...');
    await screen.findByText('当前会话消息');
    fireEvent.change(textarea, { target: { value: '只属于当前会话的草稿' } });
    fireEvent.keyDown(textarea, { key: 'Enter', code: 'Enter', shiftKey: true });
    await waitFor(() => expect(submitStarted).toBe(true));

    const selector = screen.getByTestId('clarification-conversation-select');
    fireEvent.mouseDown(within(selector).getByRole('combobox'));
    fireEvent.click(await screen.findByText(/会话 #101/));
    await screen.findByText('历史会话消息');

    resolveFailedSubmit!();
    await screen.findByText('消息发送失败，请重试');
    expect(textarea).toHaveValue('');
  });

  it('keeps the composer disabled until a processing session detail has loaded', async () => {
    writeClarificationPrefill('100', { squadId: 9, agentId: 42 });
    mockSquads();
    const processing = makeConversation(202, '正在处理的会话消息', { processing: true });
    let resolveDetail: (() => void) | undefined;
    const detailGate = new Promise<void>((resolve) => {
      resolveDetail = resolve;
    });
    server.use(
      http.get('/api/workitems/:workitemId/clarification-conversations', () =>
        HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: [processing] }),
      ),
      http.get('/api/workitems/:workitemId/clarification-conversations/:conversationId', async () => {
        await detailGate;
        return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: processing });
      }),
    );

    renderPanel([]);
    const textarea = await screen.findByPlaceholderText('输入消息...');
    await waitFor(() => expect(resolveDetail).toBeTypeOf('function'));
    expect(textarea).toBeDisabled();

    resolveDetail!();
    expect(await screen.findByText('正在处理的会话消息')).toBeInTheDocument();
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

    it('borders the composer with the controlBorder token in both height modes', async () => {
      // controlBorder 曾经是个零消费方的死令牌，输入框留着 antd 默认灰 #d9d9d9
      // ——正是面板别处已全部移除的那个灰。两个分支各有一份内联样式对象，都要断言。
      const { view, textarea } = await renderActiveConversation();
      expect(CLARIFICATION_THEME.controlBorder).toBe('rgba(0,0,0,0.10)');
      expect(textarea).toHaveStyle({ borderColor: CLARIFICATION_THEME.controlBorder });

      const panel = view.container.firstChild as HTMLElement;
      mockElementHeight(panel, 400);
      mockElementHeight(textarea.parentElement as HTMLElement, 60);
      const handle = screen.getByTestId('resize-handle-vertical');
      firePointer('pointerdown', handle, { clientX: 0, clientY: 200 });
      firePointer('pointermove', handle, { clientX: 0, clientY: 100 });
      // 进入手动固定高度分支后同样取令牌
      expect(textarea).toHaveStyle({ height: '160px' });
      expect(textarea).toHaveStyle({ borderColor: CLARIFICATION_THEME.controlBorder });
    });
  });

  describe('fullscreen reading column', () => {
    /** 输入行 = inputWrapRef 的父节点。铺满样式是合进这个 flex 行的，
     *  不是新插一层节点——多一层会打断上面 6 个输入框高度测量用例。 */
    function inputRowOf(textarea: HTMLElement): HTMLElement {
      return textarea.parentElement!.parentElement!;
    }

    /** 工单 53035：全屏后正文/选人/输入行共用一条跟着视口铺满的列，
     *  只由外层容器内距留小幅左右边距。曾经这里的 760px 限宽是超宽屏
     *  横向空出大半空间的唯一来源，三处节点都要钉住。 */
    function expectFluidColumn(column: HTMLElement) {
      expect(column.style.width).toBe('100%');
      expect(column).toHaveStyle({ minWidth: 0, overflowWrap: 'break-word' });
      expect(column.style.maxWidth).toBe('');
      expect(column.style.margin).toBe('');
    }

    it('fills the viewport with the body column inside the scroll container when fullscreen', async () => {
      const { textarea } = await renderActiveConversation({ fullscreen: true });

      const scroll = screen.getByTestId('clarification-message-scroll');
      const column = screen.getByTestId('clarification-content-column');
      // 滚动元素仍必须是 scrollRef 那层：铺满列在它内部
      expect(scroll).toContainElement(column);
      expect(column).toContainElement(await screen.findByText('你好，我想讨论需求'));
      expectFluidColumn(column);

      const inputRow = inputRowOf(textarea);
      expectFluidColumn(inputRow);
      // 铺满只是叠加，flex 行本身的排布不能被覆盖掉
      expect(inputRow.style.display).toBe('flex');
    });

    it('leaves the docked panel unconstrained so the narrow column keeps full width', async () => {
      const { textarea } = await renderActiveConversation();

      const column = screen.getByTestId('clarification-content-column');
      expect(column).toContainElement(await screen.findByText('你好，我想讨论需求'));
      expect(column.style.maxWidth).toBe('');
      expect(column.style.margin).toBe('');
      expect(column.style.width).toBe('');
      expect(column.style.minWidth).toBe('');

      const inputRow = inputRowOf(textarea);
      expect(inputRow.style.maxWidth).toBe('');
      expect(inputRow.style.display).toBe('flex');
    });

    it('widens the panel header padding when fullscreen', async () => {
      await renderActiveConversation({ fullscreen: true });

      // 全屏态整屏内距放宽，面板头部要跟着走，否则和外层 clarify 头部（RightPanel）错半档
      const header = screen.getByRole('button', { name: '切换数字人' }).parentElement!;
      expect(header.style.padding).toBe('12px 20px');
    });

    it('keeps the compact header padding when docked', async () => {
      await renderActiveConversation();

      const header = screen.getByRole('button', { name: '切换数字人' }).parentElement!;
      expect(header.style.padding).toBe('10px 14px');
    });

    it('fills the viewport with the agent selection screen when fullscreen', async () => {
      // 选人页在全屏态下真实可达：全屏中点「切换数字人」会回到这一屏，
      // 轮询把 hasDeliveryAgents 翻转也会落到这里。早返回若绕过铺满列，
      // 它就成了唯一与对话屏宽度不一致的全屏面。
      mockSquads();
      renderPanel([], { fullscreen: true });

      const column = screen.getByTestId('clarification-selection-column');
      expect(column).toContainElement(screen.getByText('选择小队和数字人'));
      expectFluidColumn(column);

      // 等小队加载落定，避免 act 警告
      const comboboxes = await screen.findAllByRole('combobox');
      await waitFor(() => expect(comboboxes[0]).not.toBeDisabled());
    });

    it('leaves the docked agent selection screen unconstrained', async () => {
      mockSquads();
      renderPanel([]);

      const column = screen.getByTestId('clarification-selection-column');
      expect(column).toContainElement(screen.getByText('选择小队和数字人'));
      expect(column.style.maxWidth).toBe('');
      expect(column.style.margin).toBe('');
      expect(column.style.width).toBe('');
      expect(column.style.minWidth).toBe('');

      const comboboxes = await screen.findAllByRole('combobox');
      await waitFor(() => expect(comboboxes[0]).not.toBeDisabled());
    });
  });

  describe('URL 恢复上下文（工单 53035）', () => {
    function mockConversationList(conversations: Array<ReturnType<typeof makeConversation>>) {
      server.use(
        http.get('/api/workitems/:workitemId/clarification-conversations', () =>
          HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: conversations }),
        ),
        http.get('/api/workitems/:workitemId/clarification-conversations/:conversationId', ({ params }) => {
          const found = conversations.find((item) => item.id === Number(params.conversationId));
          return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: found ?? null });
        }),
        http.get('/api/workitems/:workitemId/clarification-conversations/:conversationId/events', () =>
          HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: [] }),
        ),
      );
    }

    /** 交付数字人来自轮询查询，挂载当帧往往还是空数组——
     *  恢复逻辑必须能等到列表落地后再补认，所以要能中途换 agents。
     *  同理，外层会把面板上报的上下文写回 URL 再作为 prop 回流，props 也要能中途换。 */
    function renderRestorable(
      agents: Array<{ agentId: number; agentName: string; status: string }>,
      props: PanelTestProps = {},
    ) {
      const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
      const element = (nextAgents: typeof agents, nextProps: PanelTestProps) => (
        <QueryClientProvider client={queryClient}>
          <WorkitemClarificationPanel workitemId="100" agents={nextAgents as never} {...nextProps} />
        </QueryClientProvider>
      );
      const view = render(element(agents, props));
      // 两个助手各自记住最新值：交付列表落地与 URL 回流是会叠加发生的两件事，
      // 后一次 rerender 不能把前一次的结果退回初值。
      let latestAgents = agents;
      let latestProps = props;
      return {
        ...view,
        rerenderAgents: (nextAgents: typeof agents) => {
          latestAgents = nextAgents;
          view.rerender(element(latestAgents, latestProps));
        },
        rerenderProps: (nextProps: PanelTestProps) => {
          latestProps = nextProps;
          view.rerender(element(latestAgents, latestProps));
        },
      };
    }

    it('lands on the conversation recorded in the URL instead of the most recent one', async () => {
      writeClarificationPrefill('100', { squadId: 9, agentId: 42 });
      mockSquads();
      const latest = makeConversation(202, '最新会话消息');
      const historical = makeConversation(101, '历史会话消息');
      mockConversationList([latest, historical]);

      renderPanel([], { initialConversationId: 101 });

      // 自动选择挑的是列表首条 202；URL 记着 101 时必须落在 101 上
      expect(await screen.findByText('历史会话消息')).toBeInTheDocument();
      expect(screen.queryByText('最新会话消息')).toBeNull();
    });

    it('drops a stale conversation id from the URL and falls back to auto-select', async () => {
      // 书签/旧链接里的会话可能已删；不校验就会卡在一个永远拉不到内容的空会话上
      writeClarificationPrefill('100', { squadId: 9, agentId: 42 });
      mockSquads();
      mockConversationList([makeConversation(202, '最新会话消息')]);

      renderPanel([], { initialConversationId: 999 });

      expect(await screen.findByText('最新会话消息')).toBeInTheDocument();
    });

    it('does not clear a freshly created conversation when the URL echoes it back', async () => {
      writeClarificationPrefill('100', { squadId: 9, agentId: 42 });
      mockSquads();
      const onContextChange = vi.fn();
      const view = renderRestorable([], { onContextChange });

      // 没有历史会话 → 自动创建 5 号会话并上报给外层写回 URL
      await waitFor(() =>
        expect(onContextChange).toHaveBeenLastCalledWith({ agentId: 42, conversationId: 5 }),
      );

      // 外层把 5 回灌成 initialConversationId，而会话列表查询此刻仍是那份空数据。
      // 校验若跟着活的 prop 走，这条刚创建的会话会被判成“不存在”并清空。
      view.rerenderProps({ onContextChange, initialConversationId: 5 });

      await waitFor(() =>
        expect(onContextChange).toHaveBeenLastCalledWith({ agentId: 42, conversationId: 5 }),
      );
      expect(screen.queryByText('暂无对话')).toBeNull();
    });

    it('adopts the restored agent once the delivery agent list lands', async () => {
      mockSquads();
      mockAgentDirectory([{ id: 1 }, { id: 2 }]);
      // 会话归属交付数字人 2：header 的 displayName 优先取会话详情里的 agentName，
      // 沿用 makeConversation 默认的 Agent-X 会让「认领的是 2 号」这条断言失去指向
      mockConversationList([
        { ...makeConversation(101, '历史会话消息'), agentId: 2, agentName: 'Agent-B' },
      ]);
      const onContextChange = vi.fn();
      const view = renderRestorable([], {
        initialAgentId: 2, initialConversationId: 101, onContextChange,
      });

      // 交付数字人还没落地：先停在小队选人屏（会话内容要等选人屏让位后才渲染）
      expect(await screen.findByText('选择小队和数字人')).toBeInTheDocument();

      // 交付列表是轮询来的，挂载当帧必然为空。这一窗口里上报 agentId: null，
      // 页面就会把 URL 里的 agent 删掉，回流后恢复源自己没了（CR53035-001）。
      const reported = onContextChange.mock.calls.map(
        (call) => call[0] as ReportedClarifyContext,
      );
      expect(reported.length).toBeGreaterThan(0);
      for (const context of reported) {
        expect(context).not.toHaveProperty('agentId');
      }

      // 回流走真实编解码器：把每一次上报都写回 URL，再读回来当 props。
      // 少一环都复现不了缺陷——手写成固定的 initialAgentId: 2 等于替旧代码兜了底。
      const echoedSearch = reported.reduce(
        (search, context) => writeClarifyView(search, context),
        new URLSearchParams('panel=clarify&fullscreen=1&agent=2&conversation=101'),
      );
      const echoed = readClarifyView(echoedSearch);
      expect(echoed.agentId).toBe(2);

      view.rerenderAgents([
        { agentId: 1, agentName: 'Agent-A', status: 'active' },
        { agentId: 2, agentName: 'Agent-B', status: 'active' },
      ]);
      view.rerenderProps({
        initialAgentId: echoed.agentId,
        initialConversationId: echoed.conversationId,
        onContextChange,
      });

      // 列表落地后补认 URL 里的数字人 2，直接进对话页而不是再让人选一次
      await waitFor(() => expect(screen.queryByText('选择数字人')).toBeNull());
      expect(await screen.findByRole('button', { name: '切换数字人' })).toBeInTheDocument();
      await waitFor(() => expect(screen.getByText('Agent-B')).toBeInTheDocument());
      // 恢复落定后把 agent 补回 URL，会话仍是原来那条
      await waitFor(() => expect(onContextChange).toHaveBeenLastCalledWith({
        agentId: 2, conversationId: 101,
      }));
      // 且真的停在那条会话的内容上，不是重新挑过的另一条
      expect(await screen.findByText('历史会话消息')).toBeInTheDocument();
    });

    it('ignores a restored agent that is no longer in the delivery list', async () => {
      // 数字人被换掉后，URL 里的旧 id 不该把面板钉死在选不出人的状态
      mockAgentDirectory([{ id: 1, executorOnlineCount: 1 }]);
      renderPanel([{ agentId: 1, agentName: 'Agent-A', status: 'pending' }], { initialAgentId: 7 });

      expect(await screen.findByText('选择数字人')).toBeInTheDocument();
      expect(screen.getByText('Agent-A')).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: '切换数字人' })).toBeNull();
    });

    it('reports the restored context upward so the page can keep the URL in sync', async () => {
      writeClarificationPrefill('100', { squadId: 9, agentId: 42 });
      mockSquads();
      mockConversationList([makeConversation(202, '最新会话消息'), makeConversation(101, '历史会话消息')]);
      const onContextChange = vi.fn();

      renderPanel([], { initialConversationId: 101, onContextChange });

      await waitFor(() => expect(onContextChange).toHaveBeenCalledWith({ agentId: 42, conversationId: 101 }));
    });

    it('does not report an empty context on the first frame', async () => {
      // 首次上报若带上 null/null，外层会把刚从 URL 读出来的恢复源自己抹掉
      mockSquads();
      const onContextChange = vi.fn();
      renderPanel([], { onContextChange });

      const comboboxes = await screen.findAllByRole('combobox');
      await waitFor(() => expect(comboboxes[0]).not.toBeDisabled());
      expect(onContextChange).not.toHaveBeenCalled();
    });

    it('reports the newly selected conversation when the user switches sessions', async () => {
      writeClarificationPrefill('100', { squadId: 9, agentId: 42 });
      mockSquads();
      mockConversationList([makeConversation(202, '最新会话消息'), makeConversation(101, '历史会话消息')]);
      const onContextChange = vi.fn();

      renderPanel([], { onContextChange });
      await screen.findByText('最新会话消息');

      const selector = screen.getByTestId('clarification-conversation-select');
      fireEvent.mouseDown(within(selector).getByRole('combobox'));
      fireEvent.click(await screen.findByText(/会话 #101/));

      await waitFor(() =>
        expect(onContextChange).toHaveBeenLastCalledWith({ agentId: 42, conversationId: 101 }),
      );
    });

    /** 缺陷二 A/B 竞态：localStorage 预填 A=42，URL 记录 B=2/会话101。恢复窗口内
     *  先落地的是 A 的会话列表，旧代码拿它校验恢复的 101 会判「不存在」→ 清掉 →
     *  自动选上 A 的 202，刷新后永远回不到 B 的会话。校验必须等 B 的身份与它自己
     *  的列表就绪。 */
    it('validates the restored conversation only after the restored agent identity settles', async () => {
      writeClarificationPrefill('100', { squadId: 9, agentId: 42 });
      mockSquads();
      mockAgentDirectory([{ id: 1 }, { id: 2 }]);
      const aConversation = makeConversation(202, '预填数字人的会话');
      const bConversation = {
        ...makeConversation(101, '恢复目标的历史会话'), agentId: 2, agentName: 'Agent-B',
      };
      server.use(
        http.get('/api/workitems/:workitemId/clarification-conversations', ({ request }) => {
          const agentId = new URL(request.url).searchParams.get('agentId');
          return HttpResponse.json({ success: true, code: '0', message: '', traceId: null,
            data: agentId === '2' ? [bConversation] : [aConversation] });
        }),
        http.get('/api/workitems/:workitemId/clarification-conversations/:conversationId', ({ params }) => {
          const found = [aConversation, bConversation]
            .find((item) => item.id === Number(params.conversationId));
          return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: found ?? null });
        }),
        http.get('/api/workitems/:workitemId/clarification-conversations/:conversationId/events', () =>
          HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: [] }),
        ),
      );
      const onContextChange = vi.fn();
      const view = renderRestorable([], {
        initialAgentId: 2, initialConversationId: 101, onContextChange,
      });

      // 窗口期内：恢复会话的历史正常展示，但身份未落定前发送必须受限
      const textarea = await screen.findByPlaceholderText('正在恢复会话，请稍候…');
      expect(await screen.findByText('恢复目标的历史会话')).toBeInTheDocument();
      expect(textarea).toBeDisabled();

      // 交付列表落地，URL 里的数字人 2 被认领
      view.rerenderAgents([
        { agentId: 1, agentName: 'Agent-A', status: 'active' },
        { agentId: 2, agentName: 'Agent-B', status: 'active' },
      ]);

      // 最终停在 URL 记录的 B/101：预填数字人的 202 从头到尾不该出现，
      // 也没有把恢复会话误判成不存在而清空
      await waitFor(() =>
        expect(screen.getByPlaceholderText('输入消息...')).not.toBeDisabled());
      expect(screen.getByText('恢复目标的历史会话')).toBeInTheDocument();
      expect(screen.queryByText('预填数字人的会话')).toBeNull();
      await waitFor(() => expect(onContextChange).toHaveBeenLastCalledWith({
        agentId: 2, conversationId: 101,
      }));
    });

    /** 缺陷二优先级：用户手动选择 > URL 恢复。恢复窗口内用户已通过小队选择器
     *  自己选了数字人，迟到的 URL 恢复不得再把面板拽到别的数字人上。 */
    it('lets a manual agent selection win over the URL restore arriving late', async () => {
      mockSquads();
      mockAgentDirectory([{ id: 1 }, { id: 2 }]);
      const bConversation = {
        ...makeConversation(101, '迟到恢复的会话'), agentId: 2, agentName: 'Agent-B',
      };
      server.use(
        http.get('/api/workitems/:workitemId/clarification-conversations', ({ request }) => {
          const agentId = new URL(request.url).searchParams.get('agentId');
          return HttpResponse.json({ success: true, code: '0', message: '', traceId: null,
            data: agentId === '2' ? [bConversation] : [] });
        }),
        http.get('/api/workitems/:workitemId/clarification-conversations/:conversationId', ({ params }) => {
          const found = Number(params.conversationId) === 101 ? bConversation : null;
          return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: found });
        }),
        http.get('/api/workitems/:workitemId/clarification-conversations/:conversationId/events', () =>
          HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: [] }),
        ),
      );
      const view = renderRestorable([], { initialAgentId: 2 });

      // 恢复窗口内：用户自己先在小队选择器里选了 42
      const comboboxes = await screen.findAllByRole('combobox');
      await waitFor(() => expect(comboboxes[0]).not.toBeDisabled());
      fireEvent.mouseDown(comboboxes[0]);
      fireEvent.click(await screen.findByText('交付小队'));
      await waitFor(() => expect(comboboxes[1]).not.toBeDisabled());
      fireEvent.mouseDown(comboboxes[1]);
      fireEvent.click(await screen.findByText('Agent-X (AW_FS_DEV)'));

      // 交付列表随后落地且包含 URL 里的 2：恢复必须让位（没有自动认领），
      // 面板停在选人页而不是直接进入 2 号的会话
      view.rerenderAgents([
        { agentId: 1, agentName: 'Agent-A', status: 'active' },
        { agentId: 2, agentName: 'Agent-B', status: 'active' },
      ]);

      expect(await screen.findByText('选择数字人')).toBeInTheDocument();
      expect(screen.queryByText('迟到恢复的会话')).toBeNull();
    });
  });

  describe('历史加载状态与错误反馈（工单 55411 缺陷一）', () => {
    it('renders an explicit error and blocks sending instead of a blank history when the detail request fails', async () => {
      writeClarificationPrefill('100', { squadId: 9, agentId: 42 });
      mockSquads();
      let detailCalls = 0;
      server.use(
        http.get('/api/workitems/:workitemId/clarification-conversations', () =>
          HttpResponse.json({ success: true, code: '0', message: '', traceId: null,
            data: [makeConversation(202, '不该出现的消息')] }),
        ),
        http.get('/api/workitems/:workitemId/clarification-conversations/:conversationId', () => {
          detailCalls += 1;
          return HttpResponse.json(
            { success: false, code: 'BOOM', message: '加载失败', traceId: null, data: null },
            { status: 500 },
          );
        }),
      );

      renderPanel([]);
      const textarea = await screen.findByPlaceholderText('输入消息...');

      expect(await screen.findByTestId('clarification-history-error')).toBeInTheDocument();
      // 失败不能被渲染成「没有历史」的空白，也不是真的空会话
      expect(screen.queryByText('暂无历史消息')).not.toBeInTheDocument();
      expect(screen.queryByText('暂无对话')).not.toBeInTheDocument();
      expect(textarea).toBeDisabled();
      // 有界重试：初次请求 + 2 次自动重试，之后交给显式错误态
      expect(detailCalls).toBe(3);

      // 显式重试入口仍然可用（会再次经历失败）。antd 会给两字按钮插入空格，按容器内按钮查询。
      fireEvent.click(within(screen.getByTestId('clarification-history-error')).getByRole('button'));
      await waitFor(() => expect(detailCalls).toBeGreaterThan(3));
      // 手动重试会先经历 loading 再回到错误态，等待其回归
      expect(await screen.findByTestId('clarification-history-error')).toBeInTheDocument();
    });

    it('recovers the history via retry without creating a new conversation to mask the failure', async () => {
      writeClarificationPrefill('100', { squadId: 9, agentId: 42 });
      mockSquads();
      const recovered = makeConversation(202, '恢复后的历史消息');
      let createCalls = 0;
      let detailCalls = 0;
      server.use(
        http.get('/api/workitems/:workitemId/clarification-conversations', () =>
          HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: [recovered] }),
        ),
        http.post('/api/workitems/:workitemId/clarification-conversations', () => {
          createCalls += 1;
          return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: recovered });
        }),
        http.get('/api/workitems/:workitemId/clarification-conversations/:conversationId', () => {
          detailCalls += 1;
          if (detailCalls <= 3) {
            return HttpResponse.json(
              { success: false, code: 'BOOM', message: '加载失败', traceId: null, data: null },
              { status: 500 },
            );
          }
          return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: recovered });
        }),
      );

      renderPanel([]);

      expect(await screen.findByTestId('clarification-history-error')).toBeInTheDocument();
      fireEvent.click(within(screen.getByTestId('clarification-history-error')).getByRole('button'));

      // 服务恢复后重试取回原会话历史；不允许靠自动新建会话掩盖失败
      expect(await screen.findByText('恢复后的历史消息')).toBeInTheDocument();
      expect(screen.queryByTestId('clarification-history-error')).toBeNull();
      expect(createCalls).toBe(0);
      await waitFor(() =>
        expect(screen.getByPlaceholderText('输入消息...')).not.toBeDisabled(),
      );
    });

    it('keeps previously loaded history and shows a degraded notice when a later refresh fails', async () => {
      writeClarificationPrefill('100', { squadId: 9, agentId: 42 });
      mockSquads();
      const first = makeConversation(202, '第一次加载的消息');
      const other = makeConversation(303, '另一条会话的消息');
      let detail202Calls = 0;
      server.use(
        http.get('/api/workitems/:workitemId/clarification-conversations', () =>
          HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: [first, other] }),
        ),
        http.get('/api/workitems/:workitemId/clarification-conversations/:conversationId', ({ params }) => {
          if (Number(params.conversationId) === 202) {
            detail202Calls += 1;
            if (detail202Calls === 1) {
              return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: first });
            }
            return HttpResponse.json(
              { success: false, code: 'BOOM', message: '刷新失败', traceId: null, data: null },
              { status: 500 },
            );
          }
          return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: other });
        }),
      );

      renderPanel([]);
      expect(await screen.findByText('第一次加载的消息')).toBeInTheDocument();

      // 切走再切回：切回触发后台刷新失败，已有内容必须保留并给出降级提示
      const selector = screen.getByTestId('clarification-conversation-select');
      fireEvent.mouseDown(within(selector).getByRole('combobox'));
      fireEvent.click(await screen.findByText(/会话 #303/));
      expect(await screen.findByText('另一条会话的消息')).toBeInTheDocument();

      fireEvent.mouseDown(within(selector).getByRole('combobox'));
      fireEvent.click(await screen.findByText(/会话 #202/));

      expect(await screen.findByTestId('clarification-history-stale-notice')).toBeInTheDocument();
      expect(screen.getByText('第一次加载的消息')).toBeInTheDocument();
      // 有内容时是降级提示而非整页错误，且仍可继续对话
      expect(screen.queryByTestId('clarification-history-error')).toBeNull();
      expect(screen.getByPlaceholderText('输入消息...')).not.toBeDisabled();
    });

    it('shows a list-level error with retry instead of an empty-session dead end when the list request fails', async () => {
      writeClarificationPrefill('100', { squadId: 9, agentId: 42 });
      mockSquads();
      const recovered = makeConversation(202, '列表恢复后的消息');
      let listCalls = 0;
      let createCalls = 0;
      server.use(
        http.get('/api/workitems/:workitemId/clarification-conversations', () => {
          listCalls += 1;
          if (listCalls <= 3) {
            return HttpResponse.json(
              { success: false, code: 'BOOM', message: '列表加载失败', traceId: null, data: null },
              { status: 500 },
            );
          }
          return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: [recovered] });
        }),
        http.post('/api/workitems/:workitemId/clarification-conversations', () => {
          createCalls += 1;
          return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: recovered });
        }),
        http.get('/api/workitems/:workitemId/clarification-conversations/:conversationId', () =>
          HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: recovered }),
        ),
      );

      renderPanel([]);

      expect(await screen.findByTestId('clarification-list-error')).toBeInTheDocument();
      // 列表失败不是「暂无对话」，也不能靠自动建新会话掩盖
      expect(screen.queryByText('暂无对话')).not.toBeInTheDocument();
      expect(createCalls).toBe(0);

      fireEvent.click(within(screen.getByTestId('clarification-list-error')).getByRole('button'));
      expect(await screen.findByText('列表恢复后的消息')).toBeInTheDocument();
      expect(createCalls).toBe(0);
    });

    it('fails fast on a 404 conversation without auto-retrying and keeps the failure explicit', async () => {
      writeClarificationPrefill('100', { squadId: 9, agentId: 42 });
      mockSquads();
      let detailCalls = 0;
      server.use(
        http.get('/api/workitems/:workitemId/clarification-conversations', () =>
          HttpResponse.json({ success: true, code: '0', message: '', traceId: null,
            data: [makeConversation(202, '列表里的会话')] }),
        ),
        http.get('/api/workitems/:workitemId/clarification-conversations/:conversationId', () => {
          detailCalls += 1;
          return HttpResponse.json(
            { success: false, code: '10404', message: '会话不存在', traceId: null, data: null },
            { status: 404 },
          );
        }),
      );

      renderPanel([]);

      expect(await screen.findByTestId('clarification-history-error')).toBeInTheDocument();
      // 404 重试无意义：一次请求后立即失败，按真实原因呈现
      expect(detailCalls).toBe(1);
      expect(screen.getByPlaceholderText('输入消息...')).toBeDisabled();
    });
  });

  describe('onAgentConfirmed', () => {
    it('fires when a delivery agent is picked from the list', async () => {
      mockAgentDirectory([{ id: 1, executorOnlineCount: 1 }]);
      server.use(
        http.get('/api/workitems/:workitemId/clarification-conversations', () =>
          HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: [] }),
        ),
      );
      const onAgentConfirmed = vi.fn();
      renderPanel([{ agentId: 1, agentName: 'Agent-A', status: 'pending' }], { onAgentConfirmed });

      expect(onAgentConfirmed).not.toHaveBeenCalled();

      fireEvent.click(screen.getByText('Agent-A'));

      await waitFor(() => expect(onAgentConfirmed).toHaveBeenCalledTimes(1));
    });

    it('fires when a squad agent is picked from the dropdowns', async () => {
      mockSquads();
      const onAgentConfirmed = vi.fn();
      renderPanel([], { onAgentConfirmed });

      const comboboxes = await screen.findAllByRole('combobox');
      await waitFor(() => expect(comboboxes[0]).not.toBeDisabled());
      expect(onAgentConfirmed).not.toHaveBeenCalled();

      fireEvent.mouseDown(comboboxes[0]);
      fireEvent.click(await screen.findByText('交付小队'));
      await waitFor(() => expect(comboboxes[1]).not.toBeDisabled());
      fireEvent.mouseDown(comboboxes[1]);
      fireEvent.click(await screen.findByText('Agent-X (AW_FS_DEV)'));

      await waitFor(() => expect(onAgentConfirmed).toHaveBeenCalledTimes(1));
    });

    it('fires once on mount when localStorage prefill already resolves an agent', async () => {
      writeClarificationPrefill('100', { squadId: 9, agentId: 42 });
      mockSquads();
      mockConversationWithTurns(42);
      const onAgentConfirmed = vi.fn();
      renderPanel([], { onAgentConfirmed });

      await waitFor(() => expect(onAgentConfirmed).toHaveBeenCalledTimes(1));
    });

    // D4：真实消费方（RightPanel）传的是内联箭头 `() => setClarifyFullscreen(true)`，
    // 每次父组件渲染都是新的函数身份，而它就在 effect 的依赖数组里；面板本身又会因为
    // 会话轮询 / 流式事件 / 输入框打字持续重渲染。少了 agentConfirmedRef 守卫，
    // 用户按 Esc 退出全屏后，下一个流式事件就会把他重新拽回全屏。
    it('does not re-fire when the parent passes a new callback identity on every render', async () => {
      writeClarificationPrefill('100', { squadId: 9, agentId: 42 });
      mockSquads();
      mockConversationWithTurns(42);
      const onAgentConfirmed = vi.fn();
      const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
      // 每次调用都产出一个新的箭头函数身份，稳定的 spy 只用来计数
      const tree = () => (
        <QueryClientProvider client={queryClient}>
          <WorkitemClarificationPanel
            workitemId="100"
            agents={[] as never}
            onAgentConfirmed={() => onAgentConfirmed()}
          />
        </QueryClientProvider>
      );
      const { rerender } = render(tree());

      await waitFor(() => expect(onAgentConfirmed).toHaveBeenCalledTimes(1));

      // 模拟数字人确定之后父组件的连续重渲染
      rerender(tree());
      rerender(tree());
      rerender(tree());
      await screen.findByText('好的，请说说你的想法');
      rerender(tree());

      expect(onAgentConfirmed).toHaveBeenCalledTimes(1);
    });

    // 守卫必须在派生值回到 null 时复位：「切换数字人」正是把选择清回 null，
    // 少了复位分支，用户切换后重新选人就再也不会重新进全屏。
    it('fires again after 「切换数字人」 resets the selection and a new agent is picked', async () => {
      mockAgentDirectory([{ id: 1, executorOnlineCount: 1 }]);
      server.use(
        http.get('/api/workitems/:workitemId/clarification-conversations', () =>
          HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: [] }),
        ),
        // 选中后面板会自动建会话，这条用例活得比隔壁更久，不兜住会话相关请求
        // 会让 msw 抛未处理请求
        http.post('/api/workitems/:workitemId/clarification-conversations', () =>
          HttpResponse.json({
            success: true, code: '0', message: '', traceId: null,
            data: {
              id: 5, agentId: 1, agentName: 'Agent-A', channelConversationId: 'ch-5',
              status: 'ACTIVE', executorOnline: true, streamingSupported: true,
              cliSessionRef: null, processingStatus: null, processingTurnId: null,
              lastTurnAt: null, gmtCreate: '2026-01-01T00:00:00', turns: [],
            },
          }),
        ),
        http.get('/api/workitems/:workitemId/clarification-conversations/:conversationId', () =>
          HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: null }),
        ),
        http.get('/api/workitems/:workitemId/clarification-conversations/:conversationId/events', () =>
          HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: [] }),
        ),
      );
      const onAgentConfirmed = vi.fn();
      renderPanel([{ agentId: 1, agentName: 'Agent-A', status: 'pending' }], { onAgentConfirmed });

      fireEvent.click(screen.getByText('Agent-A'));
      await waitFor(() => expect(onAgentConfirmed).toHaveBeenCalledTimes(1));

      // 回到选择页：派生值被清回 null，选人列表重新挂载，元素需重新查询
      fireEvent.click(screen.getByText('切换数字人'));
      expect(await screen.findByText('选择数字人')).toBeInTheDocument();
      expect(onAgentConfirmed).toHaveBeenCalledTimes(1);

      fireEvent.click(screen.getByText('Agent-A'));

      await waitFor(() => expect(onAgentConfirmed).toHaveBeenCalledTimes(2));
    });
  });

  describe('per-message copy', () => {
    beforeEach(() => {
      vi.mocked(copyTextToClipboard).mockClear();
    });

    it('copies the AI reply as raw untrimmed markdown, not the rendered text', async () => {
      // D2「一律取原始文本」：首尾刻意留空白/空行，实现只要偷偷 .trim() 就必挂
      const md = '\n\n  结论如下：\n\n```ts\nconst a = 1;\n```\n\n  \n';
      // 守住 fixture 本身：若哪天有人把空白抹平，这条测试就退回成假绿
      expect(md).not.toBe(md.trim());
      writeClarificationPrefill('100', { squadId: 9, agentId: 42 });
      mockSquads();
      server.use(
        http.get('/api/workitems/:workitemId/clarification-conversations', () =>
          HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: [{ id: 1 }] }),
        ),
        http.get('/api/workitems/:workitemId/clarification-conversations/:conversationId', () =>
          HttpResponse.json({
            success: true, code: '0', message: '', traceId: null,
            data: {
              id: 1, agentId: 42, agentName: 'Agent-X', channelConversationId: 'ch-1',
              status: 'ACTIVE', executorOnline: true, streamingSupported: true,
              cliSessionRef: null, processingStatus: null, processingTurnId: null,
              lastTurnAt: '2026-01-01T00:00:00', gmtCreate: '2026-01-01T00:00:00',
              turns: [
                { id: 1, direction: 'IN', content: '第一行\n第二行', status: 'SUCCESS', error: null, gmtCreate: '2026-01-01T00:00:00' },
                { id: 2, direction: 'OUT', content: md, status: 'SUCCESS', error: null, gmtCreate: '2026-01-01T00:00:01' },
              ],
            },
          }),
        ),
        http.get('/api/workitems/:workitemId/clarification-conversations/:conversationId/events', () =>
          HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: [] }),
        ),
      );
      renderPanel([]);

      await screen.findByText('结论如下：');
      const buttons = screen.getAllByTestId('copy-message-button');
      // 两条消息各一个按钮：用户在前、AI 在后
      expect(buttons).toHaveLength(2);

      fireEvent.click(buttons[1]);
      await waitFor(() => expect(vi.mocked(copyTextToClipboard)).toHaveBeenCalledWith(md));
    });

    it('copies the user message verbatim including typed newlines', async () => {
      writeClarificationPrefill('100', { squadId: 9, agentId: 42 });
      mockSquads();
      mockConversationWithTurns(42);
      renderPanel([]);

      await screen.findByText('你好，我想讨论需求');
      const buttons = screen.getAllByTestId('copy-message-button');
      fireEvent.click(buttons[0]);

      await waitFor(() =>
        expect(vi.mocked(copyTextToClipboard)).toHaveBeenCalledWith('你好，我想讨论需求'));
    });

    it('marks each message and its action slot so CSS can reveal on hover', async () => {
      writeClarificationPrefill('100', { squadId: 9, agentId: 42 });
      mockSquads();
      mockConversationWithTurns(42);
      const view = renderPanel([]);

      await screen.findByText('你好，我想讨论需求');
      // jsdom 不加载外部 CSS，只能验证钩子类名挂上了；真实显隐靠浏览器核对
      expect(view.container.querySelectorAll('.aw-clarify-msg').length).toBeGreaterThanOrEqual(2);
      expect(view.container.querySelectorAll('.aw-clarify-msg-actions').length).toBeGreaterThanOrEqual(2);
    });

    it('keeps the AI copy button on the agent-name row, never inside the content block', async () => {
      writeClarificationPrefill('100', { squadId: 9, agentId: 42 });
      mockSquads();
      mockConversationWithTurns(42);
      renderPanel([]);

      await screen.findByText('好的，请说说你的想法');
      const aiBlock = screen.getByTestId('clarification-agent-block');
      const aiBubble = aiBlock.closest('.aw-clarify-msg') as HTMLElement;
      const aiCopy = within(aiBubble).getByTestId('copy-message-button');

      // 正向：按钮和数字人名字同属那一行。它待在这行 11px 文字里，才是
      // clarification.css 把按钮压到 18×18 的全部理由。
      expect(aiCopy.closest('div')).toHaveTextContent('Agent-X');
      // 反向：挪进内容块就没有「别撑高名字行」这个约束了，18px 的理由随之作废，
      // 所以这次搬迁必须红。
      expect(within(aiBlock).queryByTestId('copy-message-button')).toBeNull();
    });

    it('names the two sides distinctly so screen readers can tell the buttons apart', async () => {
      writeClarificationPrefill('100', { squadId: 9, agentId: 42 });
      mockSquads();
      mockConversationWithTurns(42);
      renderPanel([]);

      await screen.findByText('好的，请说说你的想法');
      const aiBubble = screen.getByTestId('clarification-agent-block')
        .closest('.aw-clarify-msg') as HTMLElement;
      const userBubble = screen.getByText('你好，我想讨论需求')
        .closest('.aw-clarify-msg') as HTMLElement;

      // 每条消息都叫「复制」的话，读屏用户按按钮名导航时分不出哪个是哪条
      expect(within(aiBubble).getByTestId('copy-message-button'))
        .toHaveAccessibleName('复制回复');
      expect(within(userBubble).getByTestId('copy-message-button'))
        .toHaveAccessibleName('复制我的消息');
    });

    it('omits the copy button for an AI turn that returned no content', async () => {
      writeClarificationPrefill('100', { squadId: 9, agentId: 42 });
      mockSquads();
      server.use(
        http.get('/api/workitems/:workitemId/clarification-conversations', () =>
          HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: [{ id: 1 }] }),
        ),
        http.get('/api/workitems/:workitemId/clarification-conversations/:conversationId', () =>
          HttpResponse.json({
            success: true, code: '0', message: '', traceId: null,
            data: {
              id: 1, agentId: 42, agentName: 'Agent-X', channelConversationId: 'ch-1',
              status: 'ACTIVE', executorOnline: true, streamingSupported: true,
              cliSessionRef: null, processingStatus: null, processingTurnId: null,
              lastTurnAt: '2026-01-01T00:00:00', gmtCreate: '2026-01-01T00:00:00',
              turns: [
                { id: 2, direction: 'OUT', content: '', status: 'SUCCESS', error: null, gmtCreate: '2026-01-01T00:00:01' },
              ],
            },
          }),
        ),
        http.get('/api/workitems/:workitemId/clarification-conversations/:conversationId/events', () =>
          HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: [] }),
        ),
      );
      renderPanel([]);

      await screen.findByText('（未返回内容）');
      expect(screen.queryByTestId('copy-message-button')).toBeNull();
    });

    // D4 的两侧行为分别钉住：进行中的流式区不走 TurnBubble，天然没有按钮；
    // 流终止后的幽灵气泡走 TurnBubble，内容已定型，按钮必须在。
    it('shows no copy button inside the in-progress streaming region', async () => {
      writeClarificationPrefill('100', { squadId: 9, agentId: 42 });
      mockSquads();
      server.use(
        http.get('/api/workitems/:workitemId/clarification-conversations', () =>
          HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: [PROCESSING_CONVERSATION] }),
        ),
        http.get('/api/workitems/:workitemId/clarification-conversations/:conversationId', () =>
          HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: PROCESSING_CONVERSATION }),
        ),
      );

      renderPanel([]);
      await screen.findByText('请给方案');
      await waitFor(() => expect(realtime.callback).not.toBeNull());

      // 只发 text、不发终态：流仍在进行中
      act(() => {
        realtime.callback!({
          type: 'CONVERSATION_TURN_EVENT',
          payload: { conversationId: 1, turnId: 3, eventSeq: 1, eventType: 'text', payload: { type: 'text', content: '方案第一段' } },
        });
      });

      const bubble = await screen.findByTestId('clarification-streaming-bubble');
      // 先证明流式区真的在渲染增长中的内容，否则下面的「没有按钮」是空洞的
      expect(bubble.textContent).toContain('方案第一段');
      expect(within(bubble).queryByTestId('copy-message-button')).toBeNull();
      // 全局只剩已落库的用户提问那一个按钮
      expect(screen.getAllByTestId('copy-message-button')).toHaveLength(1);
    });

    it('copies the streamed text from the terminated ghost bubble before it is persisted', async () => {
      writeClarificationPrefill('100', { squadId: 9, agentId: 42 });
      mockSquads();
      server.use(
        http.get('/api/workitems/:workitemId/clarification-conversations', () =>
          HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: [PROCESSING_CONVERSATION] }),
        ),
        http.get('/api/workitems/:workitemId/clarification-conversations/:conversationId', () =>
          HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: PROCESSING_CONVERSATION }),
        ),
      );

      renderPanel([]);
      await screen.findByText('请给方案');
      await waitFor(() => expect(realtime.callback).not.toBeNull());

      act(() => {
        realtime.callback!({
          type: 'CONVERSATION_TURN_EVENT',
          payload: { conversationId: 1, turnId: 3, eventSeq: 1, eventType: 'text', payload: { type: 'text', content: '最终方案' } },
        });
        // 终态事件：流式区收起，幽灵气泡（合成 turn id=-1）接手
        realtime.callback!({
          type: 'CONVERSATION_TURN_EVENT',
          payload: { conversationId: 1, turnId: 3, eventSeq: 2, eventType: 'status', payload: { type: 'status', status: 'completed' } },
        });
      });

      expect(screen.queryByTestId('clarification-streaming-bubble')).toBeNull();
      const ghost = screen.getByText('最终方案').closest('.aw-clarify-msg');
      expect(ghost).not.toBeNull();

      fireEvent.click(within(ghost as HTMLElement).getByTestId('copy-message-button'));
      await waitFor(() =>
        expect(vi.mocked(copyTextToClipboard)).toHaveBeenCalledWith('最终方案'));
    });
  });
});
