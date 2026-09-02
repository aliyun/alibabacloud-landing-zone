import { describe, it, expect, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
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
      <WorkitemClarificationPanel workitemId="100" agents={[] as never} />
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
        <WorkitemClarificationPanel workitemId="100" agents={[] as never} />
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
    await waitFor(() => expect(document.activeElement).toBe(screen.getByPlaceholderText('输入消息...')));
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

describe('isNearScrollBottom', () => {
  it('treats the bottom threshold inclusively', () => {
    expect(isNearScrollBottom({ scrollTop: 692, clientHeight: 300, scrollHeight: 1000 })).toBe(true);
    expect(isNearScrollBottom({ scrollTop: 700, clientHeight: 300, scrollHeight: 1000 })).toBe(true);
    expect(isNearScrollBottom({ scrollTop: 100, clientHeight: 300, scrollHeight: 1000 })).toBe(false);
    expect(isNearScrollBottom({ scrollTop: 0, clientHeight: 0, scrollHeight: 0 })).toBe(true);
  });
});
