import { describe, it, expect } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { TurnDetailToggle } from './TurnDetailToggle';

const TURN_EVENTS_URL =
  '/api/workitems/:workitemId/clarification-conversations/:conversationId/turns/:turnId/events';

function row(over: Record<string, unknown>) {
  return {
    id: 1, conversationId: 1, turnId: 8, dispatchAttempt: 1, eventSeq: 1,
    chunkIndex: 0, chunkCount: 1, eventType: 'text',
    payloadFragment: '{"type":"text","content":"历史回复"}',
    gmtCreate: '2026-01-01T00:00:00',
    ...over,
  };
}

function renderToggle() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      {/* 9 是 OUT 气泡（详情挂在这里），8 是事件真正落库的 IN 轮次 */}
      <TurnDetailToggle workitemId="100" conversationId={1} turnId={9} eventTurnId={8} />
    </QueryClientProvider>,
  );
}

describe('TurnDetailToggle', () => {
  // 事件按 IN 轮次落库（ConversationTurnEventService 用 findProcessingInbound 校验），
  // 拿 OUT 轮次 id 去查线上必定返回空。
  it('requests events for the inbound turn id, not the bubble turn id', async () => {
    const requestedPaths: string[] = [];
    server.use(
      http.get(TURN_EVENTS_URL, ({ request }) => {
        requestedPaths.push(new URL(request.url).pathname);
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null, data: [row({})],
        });
      }),
    );

    renderToggle();
    fireEvent.click(await screen.findByTestId('turn-detail-toggle-9'));

    await waitFor(() => expect(requestedPaths).toHaveLength(1));
    expect(requestedPaths[0]).toBe(
      '/api/workitems/100/clarification-conversations/1/turns/8/events',
    );
  });

  // F22：执行器无合并节流，一轮数百至数千行事件。默认全量回放会让页面
  // 初始化拉上万行，所以必须点开才请求。
  it('does not request turn events until the详情 is expanded', async () => {
    let calls = 0;
    server.use(
      http.get(TURN_EVENTS_URL, () => {
        calls += 1;
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: [row({}), row({ id: 2, eventSeq: 2, eventType: 'tool_use', payloadFragment: '{"type":"tool_use","tool":"Bash","callId":"c1"}' })],
        });
      }),
    );

    renderToggle();

    expect(await screen.findByTestId('turn-detail-toggle-9')).toBeInTheDocument();
    await new Promise((r) => setTimeout(r, 120));
    expect(calls).toBe(0);

    fireEvent.click(screen.getByTestId('turn-detail-toggle-9'));

    await waitFor(() => expect(calls).toBe(1));
    expect(await screen.findByText('Bash')).toBeInTheDocument();

    // 收起后不重复请求，展开态由缓存承接
    fireEvent.click(screen.getByTestId('turn-detail-toggle-9'));
    await waitFor(() => expect(screen.queryByText('Bash')).toBeNull());
    expect(calls).toBe(1);
  });

  it('reassembles chunked payload fragments before rendering', async () => {
    server.use(
      http.get(TURN_EVENTS_URL, () =>
        HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: [
            // 乱序 + 分片：服务端按 UTF-8 边界切过，前端必须按 chunkIndex 拼回。
            // 用 thinking 承载：text 节点不进详情回放（正文由 OUT 气泡渲染）。
            row({ id: 2, chunkIndex: 1, chunkCount: 2, eventType: 'thinking', payloadFragment: 'content":"分片重组成功"}' }),
            row({ id: 1, chunkIndex: 0, chunkCount: 2, eventType: 'thinking', payloadFragment: '{"type":"thinking","' }),
          ],
        }),
      ),
    );

    renderToggle();
    fireEvent.click(await screen.findByTestId('turn-detail-toggle-9'));

    expect(await screen.findByText('分片重组成功')).toBeInTheDocument();
  });

  it('reports an empty process instead of an empty panel', async () => {
    server.use(
      http.get(TURN_EVENTS_URL, () =>
        HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: [] }),
      ),
    );

    renderToggle();
    fireEvent.click(await screen.findByTestId('turn-detail-toggle-9'));

    expect(await screen.findByText('无执行详情')).toBeInTheDocument();
  });

  it('surfaces a load failure instead of silently showing nothing', async () => {
    server.use(
      http.get(TURN_EVENTS_URL, () =>
        HttpResponse.json(
          { success: false, code: 'BOOM', message: '炸了', traceId: null, data: null },
          { status: 500 },
        ),
      ),
    );

    renderToggle();
    fireEvent.click(await screen.findByTestId('turn-detail-toggle-9'));

    expect(await screen.findByText('执行详情加载失败')).toBeInTheDocument();
  });

  /**
   * 线上 bug：点开「查看执行详情」后回复正文出现两遍 —— 一遍在 OUT 气泡里，
   * 一遍在详情回放里。text 事件累加起来就是气泡显示的 turn.content
   * （见 hooks.ts 的 streamedText），所以详情必须只回放过程，不重复结论。
   */
  it('replays the process without repeating the reply text already in the bubble', async () => {
    const reply = '没在想什么特别的。当前无待办任务，工单 #52957 交付已启动。';
    server.use(
      http.get(TURN_EVENTS_URL, () =>
        HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: [
            row({
              id: 1, eventSeq: 1, eventType: 'thinking',
              payloadFragment: JSON.stringify({ type: 'thinking', content: '先看看有没有待办' }),
            }),
            row({
              id: 2, eventSeq: 2, eventType: 'text',
              payloadFragment: JSON.stringify({ type: 'text', content: reply }),
            }),
          ],
        }),
      ),
    );

    renderToggle();
    fireEvent.click(await screen.findByTestId('turn-detail-toggle-9'));

    // 过程要能看到
    expect(await screen.findByText('思考过程')).toBeInTheDocument();
    // 结论不能在详情里再来一遍
    expect(screen.queryByText(reply)).toBeNull();
  });

  // 一轮只有回复、没有任何过程时，详情里没东西可放，要如实说而不是回放一遍正文。
  it('reports an empty process for a turn that only produced reply text', async () => {
    server.use(
      http.get(TURN_EVENTS_URL, () =>
        HttpResponse.json({
          success: true, code: '0', message: '', traceId: null, data: [row({})],
        }),
      ),
    );

    renderToggle();
    fireEvent.click(await screen.findByTestId('turn-detail-toggle-9'));

    expect(await screen.findByText('无执行详情')).toBeInTheDocument();
    expect(screen.queryByText('历史回复')).toBeNull();
  });
});

it('suppresses only visible history identities and keeps event-only legacy questions and process', async () => {
  server.use(http.get(TURN_EVENTS_URL, () => HttpResponse.json({ success: true, data: [
    row({ id: 1, eventSeq: 1, eventType: 'acp_elicitation', payloadFragment: JSON.stringify({ data: { requestId: 'covered', message: 'Covered question' } }) }),
    row({ id: 2, eventSeq: 2, eventType: 'acp_elicitation_resolved', payloadFragment: JSON.stringify({ data: { requestId: 'covered', action: 'accept', content: { value: 'Covered answer' } } }) }),
    row({ id: 3, eventSeq: 3, eventType: 'acp_elicitation', payloadFragment: JSON.stringify({ data: { requestId: 'legacy', message: 'Legacy question' } }) }),
    row({ id: 4, eventSeq: 4, eventType: 'acp_elicitation_resolved', payloadFragment: JSON.stringify({ data: { requestId: 'legacy', action: 'accept', content: { value: 'Legacy answer' } } }) }),
    row({ id: 5, eventSeq: 5, eventType: 'tool_use', payloadFragment: '{"tool":"Bash","callId":"process"}' }),
  ] })));
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(<QueryClientProvider client={client}><TurnDetailToggle workitemId="100" conversationId={1} turnId={9} eventTurnId={8} coveredQuestionIds={new Set(['8:covered'])} /></QueryClientProvider>);
  fireEvent.click(await screen.findByTestId('turn-detail-toggle-9'));
  expect(await screen.findByText('Legacy answer')).toBeInTheDocument();
  expect(screen.getByText('Bash')).toBeInTheDocument();
  expect(screen.queryByText('Covered question')).not.toBeInTheDocument();
  expect(screen.queryByText('Covered answer')).not.toBeInTheDocument();
});
