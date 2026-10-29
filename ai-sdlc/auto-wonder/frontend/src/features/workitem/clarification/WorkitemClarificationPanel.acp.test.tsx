import { describe, it, expect, afterEach, beforeEach, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { typeComposer } from './composerTestUtils';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { WorkitemClarificationPanel } from './WorkitemClarificationPanel';
import { useAuthStore } from '@/shared/auth/store';
import { clearClarificationPrefill, writeClarificationPrefill } from './prefill';

type RealtimeCallback = (event: { type: string; payload: unknown }) => void;
const realtime = vi.hoisted(() => ({ callback: null as RealtimeCallback | null }));

vi.mock('@/shared/realtime/useRealtime', () => ({
  useRealtime: (_channel: unknown, opts: { onEvent: RealtimeCallback }) => {
    realtime.callback = opts.onEvent;
  },
}));

const SELECT_SCHEMA = JSON.stringify({
  type: 'object',
  properties: {
    q0: {
      type: 'string',
      title: '首选技术栈',
      oneOf: [{ const: 'nextjs', title: 'Next.js 全栈' }, { const: 'vite', title: 'Vite + Spring' }],
    },
  },
  required: ['q0'],
});

interface PanelConversation {
  acpInteractionSupported?: boolean;
  processingStatus?: string | null;
  processingTurnId?: number | null;
  cancelSupported?: boolean;
  pendingElicitations?: Array<Record<string, unknown>>;
  answerDeliveries?: Array<Record<string, unknown>>;
  questionHistory?: Array<Record<string, unknown>>;
  turns?: Array<{ id: number; direction: string; content: string; status: string }>;
  /** 详情接口带回的命令快照种子（打开会话即秒显 `/` 候选） */
  availableCommands?: Array<Record<string, unknown>> | null;
}

function payloadOf(conv: PanelConversation) {
  return {
    id: 1,
    agentId: 42,
    agentName: 'Agent-X',
    channelConversationId: 'ch-1',
    status: 'ACTIVE',
    executorOnline: true,
    streamingSupported: true,
    cancelSupported: conv.cancelSupported ?? false,
    acpInteractionSupported: conv.acpInteractionSupported ?? false,
    cliSessionRef: null,
    processingStatus: conv.processingStatus ?? null,
    processingTurnId: conv.processingTurnId ?? null,
    pendingElicitations: conv.pendingElicitations ?? [],
    answerDeliveries: conv.answerDeliveries ?? [],
    questionHistory: conv.questionHistory,
    lastTurnAt: null,
    gmtCreate: '2026-01-01T00:00:00',
    turns: (conv.turns ?? []).map((t) => ({ ...t, error: null, gmtCreate: '2026-01-01T00:00:00' })),
  };
}

function ok(data: unknown) {
  return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data });
}

let detailCalls = 0;
let refreshCalls = 0;

function mockPanel(conv: PanelConversation) {
  const payload = payloadOf(conv);
  // 命令快照只有详情接口填充（与服务端一致），列表接口不带
  const detail = { ...payload, availableCommands: conv.availableCommands ?? null };
  server.use(
    http.get('/api/squads', () => ok({
      list: [{ id: 9, name: '交付小队', description: '', memberCount: 1, gmtCreate: '' }],
      total: 1, pageNum: 1, pageSize: 100,
    })),
    http.get('/api/squads/:squadId/members', () =>
      ok([{ agentId: 42, agentName: 'Agent-X', roleCode: 'AW_FS_DEV' }])),
    http.get('/api/workitems/:workitemId/clarification-conversations', () => ok([payload])),
    http.get('/api/workitems/:workitemId/clarification-conversations/:conversationId', () => {
      detailCalls += 1;
      return ok(detail);
    }),
    // 打开会话即触发带外命令探针：所有用例统一兜底，避免未 mock 落入 unhandled
    http.post(
      '/api/workitems/:workitemId/clarification-conversations/:conversationId/commands/refresh',
      () => {
        refreshCalls += 1;
        return ok(null);
      },
    ),
  );
  return payload;
}

async function renderPanel(conv: PanelConversation) {
  writeClarificationPrefill('100', { squadId: 9, agentId: 42 });
  mockPanel(conv);
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const view = render(
    <QueryClientProvider client={queryClient}>
      <WorkitemClarificationPanel workitemId="100" />
    </QueryClientProvider>,
  );
  await waitFor(() => expect(realtime.callback).not.toBeNull());
  return { view, queryClient };
}

/** 会话详情重拉一次：等价于终态实时事件触发的失效，用来推进 processingStatus。 */
function refetchConversation(queryClient: QueryClient) {
  act(() => {
    queryClient.invalidateQueries({
      queryKey: ['workitem', '100', 'clarification-conversation', 1],
    });
  });
}

function emit(eventSeq: number, eventType: string, payload: unknown, turnId = 3) {
  act(() => {
    realtime.callback!({
      type: 'CONVERSATION_TURN_EVENT',
      payload: { conversationId: 1, turnId, eventSeq, eventType, payload },
    });
  });
}

describe('WorkitemClarificationPanel ACP 交互接线', () => {
  beforeEach(() => {
    detailCalls = 0;
    refreshCalls = 0;
  });

  afterEach(() => {
    clearClarificationPrefill('100');
    realtime.callback = null;
    vi.restoreAllMocks();
  });

  // F16：浏览器刷新后 WS 流已断，未解决卡片只能靠会话详情里的 pendingElicitations 恢复，
  // 否则用户被卡在一个既没有问题也没有输入框的空白处。
  it('restores unresolved cards from the conversation detail after a refresh', async () => {
    await renderPanel({
      acpInteractionSupported: true,
      processingStatus: 'PROCESSING',
      processingTurnId: 3,
      pendingElicitations: [{
        requestId: 'req-restored', turnId: 3, mode: 'form', message: '刷新后仍要回答',
        requestedSchema: SELECT_SCHEMA, status: 'PENDING', gmtCreate: '2026-01-01T00:00:00',
      }],
      turns: [{ id: 3, direction: 'INBOUND', content: '请给方案', status: 'PROCESSING' }],
    });

    expect(await screen.findByTestId('elicitation-wizard-req-restored')).toBeInTheDocument();
    expect(screen.getByText('刷新后仍要回答')).toBeInTheDocument();
    expect(screen.getByText('Next.js 全栈')).toBeInTheDocument();
  });

  // v2：有问题时输入框被停靠的问题卡片替换，不再是「显示带提示语的禁用输入框」。
  it('有问题时隐藏输入框、改由停靠卡片承载作答', async () => {
    await renderPanel({
      acpInteractionSupported: true,
      processingStatus: 'PROCESSING',
      processingTurnId: 3,
      pendingElicitations: [{
        requestId: 'req-1', turnId: 3, mode: 'form', message: '选一个',
        requestedSchema: SELECT_SCHEMA, status: 'PENDING', gmtCreate: '',
      }],
      turns: [{ id: 3, direction: 'INBOUND', content: '请给方案', status: 'PROCESSING' }],
    });

    expect(await screen.findByTestId('elicitation-wizard-req-1')).toBeInTheDocument();
    expect(screen.queryByPlaceholderText('输入消息...')).toBeNull();
    expect(screen.queryByPlaceholderText('请先回答上方问题或点击跳过')).toBeNull();
  });

  it('问题解决后输入框恢复', async () => {
    await renderPanel({
      acpInteractionSupported: true,
      processingStatus: 'PROCESSING',
      processingTurnId: 3,
      turns: [{ id: 3, direction: 'INBOUND', content: '请给方案', status: 'PROCESSING' }],
    });
    await screen.findByText('请给方案');

    emit(1, 'acp_elicitation', {
      type: 'acp_elicitation',
      data: { requestId: 'req-s', mode: 'form', message: '选一个', requestedSchema: JSON.parse(SELECT_SCHEMA) },
    });
    expect(await screen.findByTestId('elicitation-wizard-req-s')).toBeInTheDocument();
    expect(screen.queryByPlaceholderText('输入消息...')).toBeNull();

    emit(2, 'acp_elicitation_resolved', {
      type: 'acp_elicitation_resolved',
      data: { requestId: 'req-s', action: 'accept', content: { q0: 'nextjs' } },
    });

    expect(await screen.findByPlaceholderText('输入消息...')).toBeInTheDocument();
    expect(screen.getByText('已回答')).toBeInTheDocument();
  });

  // F26：老执行器不声明 CONVERSATION_ACP_INTERACTION_V1 时，答案没人接收。
  it('renders no card interaction entry when the runtime does not support it', async () => {
    await renderPanel({
      acpInteractionSupported: false,
      processingStatus: 'PROCESSING',
      processingTurnId: 3,
      pendingElicitations: [{
        requestId: 'req-1', turnId: 3, mode: 'form', message: '不该出现的问题',
        requestedSchema: SELECT_SCHEMA, status: 'PENDING', gmtCreate: '',
      }],
      turns: [{ id: 3, direction: 'INBOUND', content: '请给方案', status: 'PROCESSING' }],
    });

    await screen.findByText('请给方案');
    expect(screen.queryByTestId('elicitation-wizard-req-1')).toBeNull();
    expect(screen.queryByText('不该出现的问题')).toBeNull();
    expect(await screen.findByPlaceholderText('输入消息...')).toBeInTheDocument();
  });

  it('restores saved answers without reopening the stale question wizard', async () => {
    const card = { requestId: 'req-saved', turnId: 3, mode: 'form', message: 'saved question', requestedSchema: SELECT_SCHEMA, status: 'ANSWERED', gmtCreate: '', answerId: 'answer-1', answerJson: '{"q0":"nextjs"}', deliveryStatus: 'SAVED' };
    await renderPanel({ acpInteractionSupported: true, processingStatus: 'PROCESSING', processingTurnId: 3,
      pendingElicitations: [{ ...card, status: 'PENDING' }], answerDeliveries: [card],
      turns: [{ id: 3, direction: 'INBOUND', content: '请给方案', status: 'PROCESSING' }] });
    expect(await screen.findByText('回答已保存，等待送达执行器')).toBeInTheDocument();
    expect(screen.queryByTestId('elicitation-wizard-req-saved')).toBeNull();
    expect(screen.getByText(/nextjs/)).toBeInTheDocument();
  });

  // F13：提交走 POST .../elicitations/{requestId}/reply，answer 以字符串原样透传。
  it('submits a card answer to the reply endpoint and refreshes the conversation', async () => {
    let replyBody: unknown = null;
    await renderPanel({
      acpInteractionSupported: true,
      processingStatus: 'PROCESSING',
      processingTurnId: 3,
      pendingElicitations: [{
        requestId: 'req-1', turnId: 3, mode: 'form', message: '选一个',
        requestedSchema: SELECT_SCHEMA, status: 'PENDING', gmtCreate: '',
      }],
      turns: [{ id: 3, direction: 'INBOUND', content: '请给方案', status: 'PROCESSING' }],
    });
    server.use(
      http.post(
        '/api/workitems/:workitemId/clarification-conversations/:conversationId/elicitations/:requestId/reply',
        async ({ request }) => {
          replyBody = await request.json();
          return ok(null);
        },
      ),
    );

    await screen.findByTestId('elicitation-wizard-req-1');
    const callsBeforeReply = detailCalls;
    fireEvent.click(screen.getByTestId('elicitation-option-q0-0'));
    fireEvent.click(screen.getByTestId('elicitation-submit-req-1'));

    await waitFor(() => expect(replyBody).toEqual({ action: 'accept', content: '{"q0":"nextjs"}' }));
    // 回答会唤醒挂起的 Agent 继续本轮，必须重新拉会话状态
    await waitFor(() => expect(detailCalls).toBeGreaterThan(callsBeforeReply));
  });

  // F14：跳过 = decline，Qoder 收到后 end_turn 正常收尾。
  it('declines the card when the user skips it', async () => {
    let replyBody: unknown = null;
    await renderPanel({
      acpInteractionSupported: true,
      processingStatus: 'PROCESSING',
      processingTurnId: 3,
      pendingElicitations: [{
        requestId: 'req-1', turnId: 3, mode: 'form', message: '选一个',
        requestedSchema: SELECT_SCHEMA, status: 'PENDING', gmtCreate: '',
      }],
      turns: [{ id: 3, direction: 'INBOUND', content: '请给方案', status: 'PROCESSING' }],
    });
    server.use(
      http.post(
        '/api/workitems/:workitemId/clarification-conversations/:conversationId/elicitations/:requestId/reply',
        async ({ request }) => {
          replyBody = await request.json();
          return ok(null);
        },
      ),
    );

    fireEvent.click(await screen.findByTestId('elicitation-skip-req-1'));

    await waitFor(() => expect(replyBody).toEqual({ action: 'decline', content: null }));
  });

  it('reports a transient reply failure and leaves the card answerable', async () => {
    await renderPanel({
      acpInteractionSupported: true,
      processingStatus: 'PROCESSING',
      processingTurnId: 3,
      pendingElicitations: [{
        requestId: 'req-1', turnId: 3, mode: 'form', message: '选一个',
        requestedSchema: SELECT_SCHEMA, status: 'PENDING', gmtCreate: '',
      }],
      turns: [{ id: 3, direction: 'INBOUND', content: '请给方案', status: 'PROCESSING' }],
    });
    server.use(
      http.post(
        '/api/workitems/:workitemId/clarification-conversations/:conversationId/elicitations/:requestId/reply',
        () => HttpResponse.json(
          { success: false, code: '10000', message: '服务暂时不可用', traceId: null, data: null },
          { status: 500 },
        ),
      ),
    );

    fireEvent.click(await screen.findByTestId('elicitation-skip-req-1'));

    expect(await screen.findByText('回答提交失败，请重试')).toBeInTheDocument();
    expect(screen.getByTestId('elicitation-skip-req-1')).toBeEnabled();
  });

  // 线上 bug：轮次取消把卡片结算成 CANCELED，但该 resolved 事件不落库，前端时间线仍当作 pending，
  // 提交/跳过都吃 409「already settled」，卡片关不掉、对话卡死。修复：CONFLICT 即撤下卡片。
  it('对已结算（409 CONFLICT）的问题：撤下卡片并提示已失效，解除卡死', async () => {
    await renderPanel({
      acpInteractionSupported: true,
      processingStatus: 'PROCESSING',
      processingTurnId: 3,
      pendingElicitations: [{
        requestId: 'req-1', turnId: 3, mode: 'form', message: '选一个',
        requestedSchema: SELECT_SCHEMA, status: 'PENDING', gmtCreate: '',
      }],
      turns: [{ id: 3, direction: 'INBOUND', content: '请给方案', status: 'PROCESSING' }],
    });
    server.use(
      http.post(
        '/api/workitems/:workitemId/clarification-conversations/:conversationId/elicitations/:requestId/reply',
        () => HttpResponse.json(
          { success: false, code: '10409', message: 'elicitation already settled: CANCELED', traceId: null, data: null },
          { status: 409 },
        ),
      ),
    );

    fireEvent.click(await screen.findByTestId('elicitation-skip-req-1'));

    expect(await screen.findByText('该问题已失效，已为你关闭')).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByTestId('elicitation-wizard-req-1')).toBeNull());
    // 卡片撤下后输入框恢复正常占位，不再卡在「请先回答上方问题」
    expect(await screen.findByPlaceholderText('输入消息...')).toBeInTheDocument();
  });

  // 复现线上真实路径：卡片来自流式时间线节点（非 restored），且走提交（accept）路径吃 409。
  it('流式时间线里的 pending 卡片提交 409 后也撤下', async () => {
    await renderPanel({
      acpInteractionSupported: true,
      processingStatus: 'PROCESSING',
      processingTurnId: 3,
      turns: [{ id: 3, direction: 'INBOUND', content: '请给方案', status: 'PROCESSING' }],
    });
    await screen.findByText('请给方案');
    emit(1, 'acp_elicitation', {
      type: 'acp_elicitation',
      data: { requestId: 'req-s', mode: 'form', message: '选一个', requestedSchema: JSON.parse(SELECT_SCHEMA) },
    });
    await screen.findByTestId('elicitation-wizard-req-s');
    server.use(
      http.post(
        '/api/workitems/:workitemId/clarification-conversations/:conversationId/elicitations/:requestId/reply',
        () => HttpResponse.json(
          { success: false, code: '10409', message: 'elicitation already settled: CANCELED', traceId: null, data: null },
          { status: 409 },
        ),
      ),
    );

    fireEvent.click(screen.getByTestId('elicitation-option-q0-0'));
    fireEvent.click(screen.getByTestId('elicitation-submit-req-s'));

    expect(await screen.findByText('该问题已失效，已为你关闭')).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByTestId('elicitation-wizard-req-s')).toBeNull());
  });

  it('falls back to a JSON textarea when the restored schema is not valid JSON', async () => {
    await renderPanel({
      acpInteractionSupported: true,
      processingStatus: 'PROCESSING',
      processingTurnId: 3,
      pendingElicitations: [{
        requestId: 'req-bad', turnId: 3, mode: 'form', message: '坏 schema',
        requestedSchema: '{not json', status: 'PENDING', gmtCreate: '',
      }],
      turns: [{ id: 3, direction: 'INBOUND', content: '请给方案', status: 'PROCESSING' }],
    });

    expect(await screen.findByTestId('elicitation-field-_raw')).toBeInTheDocument();
  });

  it('does not render a restored card twice once the stream carries the same requestId', async () => {
    await renderPanel({
      acpInteractionSupported: true,
      processingStatus: 'PROCESSING',
      processingTurnId: 3,
      pendingElicitations: [{
        requestId: 'req-dup', turnId: 3, mode: 'form', message: '只能出现一次',
        requestedSchema: SELECT_SCHEMA, status: 'PENDING', gmtCreate: '',
      }],
      turns: [{ id: 3, direction: 'INBOUND', content: '请给方案', status: 'PROCESSING' }],
    });
    await screen.findByTestId('elicitation-wizard-req-dup');

    emit(1, 'acp_elicitation', {
      type: 'acp_elicitation',
      data: { requestId: 'req-dup', mode: 'form', message: '只能出现一次', requestedSchema: JSON.parse(SELECT_SCHEMA) },
    });

    await waitFor(() => expect(screen.getAllByTestId('elicitation-wizard-req-dup')).toHaveLength(1));
  });

  // 回归：activeElicitation 从 A 直接切到 B（无 null 间隙）时，向导必须按 requestId remount。
  // 否则旧 stepIndex 越界 B 的字段数 → 向导渲染为空 → 问题不可见且输入框禁用 = 卡死本轮。
  it('切到下一个 pending 问题时向导重置到第一题', async () => {
    await renderPanel({
      acpInteractionSupported: true,
      processingStatus: 'PROCESSING',
      processingTurnId: 3,
      turns: [{ id: 3, direction: 'INBOUND', content: '请给方案', status: 'PROCESSING' }],
    });
    await screen.findByText('请给方案');

    const twoQ = {
      type: 'object',
      properties: {
        q0: { type: 'string', title: '题一', oneOf: [{ const: 'A', title: 'A' }] },
        q1: { type: 'string', title: '题二' },
      },
      required: ['q0'],
    };
    const oneQ = {
      type: 'object',
      properties: { z0: { type: 'string', title: '单问题', oneOf: [{ const: 'X', title: 'X' }] } },
      required: ['z0'],
    };
    // 两个问题都 pending：浮最早的 A，B 排队
    emit(1, 'acp_elicitation', { type: 'acp_elicitation', data: { requestId: 'req-A', mode: 'form', message: '两个问题', requestedSchema: twoQ } });
    emit(2, 'acp_elicitation', { type: 'acp_elicitation', data: { requestId: 'req-B', mode: 'form', message: '一个问题', requestedSchema: oneQ } });
    expect(await screen.findByTestId('elicitation-wizard-req-A')).toBeInTheDocument();

    // 把 A 推进到末题（stepIndex=1）
    fireEvent.click(screen.getByTestId('elicitation-option-q0-0'));
    await waitFor(() => expect(screen.getByTestId('elicitation-count-req-A')).toHaveTextContent('第 2/2 题'));

    // A 解决 → activeElicitation 直接切到仍 pending 的 B（无 null 间隙）
    emit(3, 'acp_elicitation_resolved', { type: 'acp_elicitation_resolved', data: { requestId: 'req-A', action: 'accept', content: { q0: 'A' } } });

    expect(await screen.findByTestId('elicitation-wizard-req-B')).toBeInTheDocument();
    expect(screen.getByTestId('elicitation-count-req-B')).toHaveTextContent('第 1/1 题');
  });

  // F18/F19：ACP 没有 invoke 方法，选中命令就是把 `/name ` 当普通文本填进输入框。
  it('completes slash commands announced by acp_commands', async () => {
    await renderPanel({
      acpInteractionSupported: true,
      turns: [{ id: 1, direction: 'IN', content: '你好', status: 'COMPLETED' }],
    });
    const textarea = await screen.findByPlaceholderText('输入消息...');

    emit(1, 'acp_commands', {
      type: 'acp_commands',
      data: {
        availableCommands: [
          { name: 'quest', description: '生成需求问卷', input: { hint: '<主题>' } },
          { name: 'commit', description: '提交改动' },
        ],
      },
    }, 1);

    await typeComposer('/qu');

    expect(await screen.findByTestId('slash-command-picker')).toBeInTheDocument();
    expect(screen.getByText('生成需求问卷')).toBeInTheDocument();
    expect(screen.getByText('<主题>')).toBeInTheDocument();
    expect(screen.queryByTestId('slash-command-commit')).toBeNull();

    fireEvent.click(screen.getByTestId('slash-command-quest'));

    expect(textarea).toHaveValue('/quest ');
    // 已选定命令后浮层收起，后面输入的是参数
    expect(screen.queryByTestId('slash-command-picker')).toBeNull();
  });

  // 打开会话即刻用详情里的命令快照种子（不等 acp_commands 实时事件），
  // 同时后台补一次 commands/refresh 探针去刷新耐久命令。
  it('seeds slash commands from the conversation detail and probes commands/refresh on open', async () => {
    const seed = [{ name: 'quest', description: '两阶段问卷' }];
    const turns = [{ id: 1, direction: 'IN', content: '你好', status: 'COMPLETED' }];
    await renderPanel({ acpInteractionSupported: true, availableCommands: seed, turns });

    // 探针在打开会话时后台发出（fire-and-forget），且不阻塞输入
    await waitFor(() => expect(refreshCalls).toBe(1));

    // 快照种子先于任何 acp_commands 实时事件生效：敲 `/` 立刻有候选
    await screen.findByPlaceholderText('输入消息...');
    await typeComposer('/');
    expect(await screen.findByTestId('slash-command-quest')).toBeInTheDocument();
    expect(screen.getByText('两阶段问卷')).toBeInTheDocument();

    // 实时命令到达后优先于快照种子
    emit(1, 'acp_commands', {
      type: 'acp_commands',
      data: { availableCommands: [{ name: 'commit', description: '提交改动' }] },
    }, 1);
    await waitFor(() => expect(screen.queryByTestId('slash-command-quest')).toBeNull());
    expect(screen.getByTestId('slash-command-commit')).toBeInTheDocument();

    // 去重：详情重拉、重渲染、能力位抖动（false→true 会让 effect 重跑）都不能重复探针
    const detailBase = { ...payloadOf({ acpInteractionSupported: true, turns }), availableCommands: seed };
    let supported = true;
    server.use(
      http.get('/api/workitems/:workitemId/clarification-conversations/:conversationId', () => {
        detailCalls += 1;
        supported = !supported;
        return ok({ ...detailBase, acpInteractionSupported: supported });
      }),
    );
    const detailsBefore = detailCalls;
    act(() => { window.dispatchEvent(new Event('visibilitychange')); });
    await waitFor(() => expect(detailCalls).toBeGreaterThan(detailsBefore));
    act(() => { window.dispatchEvent(new Event('visibilitychange')); });
    await waitFor(() => expect(supported).toBe(true));
    await typeComposer('/c');

    await act(async () => { await new Promise((r) => setTimeout(r, 60)); });
    expect(refreshCalls).toBe(1);
    expect(screen.getByTestId('slash-command-commit')).toBeInTheDocument();
  });

  // Fix #3：执行器权威上报「就是没有命令」时不能被快照种子盖回去，
  // 否则 `/` 会一直浮出早已失效的候选（空列表 ≠ 尚未上报）。
  it('keeps an executor-reported empty command set over the seed', async () => {
    const seed = [{ name: 'quest', description: '两阶段问卷' }];
    const turns = [{ id: 1, direction: 'IN', content: '你好', status: 'COMPLETED' }];
    await renderPanel({ acpInteractionSupported: true, availableCommands: seed, turns });

    await screen.findByPlaceholderText('输入消息...');
    await typeComposer('/');
    expect(await screen.findByTestId('slash-command-quest')).toBeInTheDocument();

    // 探针结果：命令集已空（服务端直推带 turnId:0）
    emit(2, 'acp_commands', { type: 'acp_commands', data: { availableCommands: [] } }, 0);

    await waitFor(() => expect(screen.queryByTestId('slash-command-picker')).toBeNull());
    await typeComposer('/q');
    await new Promise((r) => setTimeout(r, 60));
    expect(screen.queryByTestId('slash-command-quest')).toBeNull();
  });

  // F20：执行器没吐 acp_commands 就没有候选，/ 不能弹出空浮层。
  it('does not open the picker without any commands event', async () => {
    await renderPanel({
      acpInteractionSupported: true,
      turns: [{ id: 1, direction: 'IN', content: '你好', status: 'COMPLETED' }],
    });
    await screen.findByPlaceholderText('输入消息...');

    await typeComposer('/');

    await new Promise((r) => setTimeout(r, 60));
    expect(screen.queryByTestId('slash-command-picker')).toBeNull();
  });

  // F22：一轮数百至数千行事件，历史轮次默认不能拉。
  it('loads a historical turn detail only when asked', async () => {
    const requestedPaths: string[] = [];
    await renderPanel({
      acpInteractionSupported: true,
      turns: [
        { id: 1, direction: 'IN', content: '请给方案', status: 'COMPLETED' },
        { id: 2, direction: 'OUT', content: '这是方案', status: 'COMPLETED' },
      ],
    });
    server.use(
      http.get(
        '/api/workitems/:workitemId/clarification-conversations/:conversationId/turns/:turnId/events',
        ({ request }) => {
          requestedPaths.push(new URL(request.url).pathname);
          return ok([{
            id: 1, conversationId: 1, turnId: 1, dispatchAttempt: 1, eventSeq: 1,
            chunkIndex: 0, chunkCount: 1, eventType: 'tool_use',
            payloadFragment: JSON.stringify({
              type: 'tool_use', tool: 'Bash', callId: 'c1',
              data: { kind: 'execute', title: '当时跑了测试' },
            }),
            gmtCreate: '',
          }]);
        },
      ),
    );

    await screen.findByText('这是方案');
    // 用户自己的输入没有执行详情可看
    expect(screen.queryByTestId('turn-detail-toggle-1')).toBeNull();
    await new Promise((r) => setTimeout(r, 120));
    expect(requestedPaths).toHaveLength(0);

    fireEvent.click(screen.getByTestId('turn-detail-toggle-2'));

    // 事件按 IN 轮次落库，详情必须查 turn 1 而不是气泡自己的 turn 2
    await waitFor(() => expect(requestedPaths).toHaveLength(1));
    expect(requestedPaths[0]).toBe(
      '/api/workitems/100/clarification-conversations/1/turns/1/events',
    );
    expect(await screen.findByText('当时跑了测试')).toBeInTheDocument();
  });

  // 首轮就是 OUT（历史数据异常）时没有可查的 IN 轮次，挂个必然返回空的入口只会骗用户。
  it('omits the detail toggle for an outbound turn with no preceding inbound turn', async () => {
    await renderPanel({
      acpInteractionSupported: true,
      turns: [{ id: 2, direction: 'OUT', content: '这是方案', status: 'COMPLETED' }],
    });

    await screen.findByText('这是方案');
    expect(screen.queryByTestId('turn-detail-toggle-2')).toBeNull();
  });

  /**
   * 排队中再输入然后点停止：被取消的那一轮从未执行，它带出的「已取消」气泡没有
   * 执行详情；真正在跑的那一轮产出 OUT 时，详情必须查回它自己而不是被取消的轮次。
   */
  it('pairs the running turn past a canceled queued turn and drops the cancel notice toggle', async () => {
    const requestedPaths: string[] = [];
    await renderPanel({
      acpInteractionSupported: true,
      turns: [
        { id: 1, direction: 'IN', content: '请给方案', status: 'PROCESSING' },
        { id: 2, direction: 'IN', content: '再补一句', status: 'CANCELED' },
        { id: 3, direction: 'OUT', content: '已取消', status: 'CANCELED' },
        { id: 4, direction: 'OUT', content: '这是方案', status: 'SUCCESS' },
      ],
    });
    server.use(
      http.get(
        '/api/workitems/:workitemId/clarification-conversations/:conversationId/turns/:turnId/events',
        ({ request }) => {
          requestedPaths.push(new URL(request.url).pathname);
          return ok([]);
        },
      ),
    );

    await screen.findByText('这是方案');
    expect(screen.queryByTestId('turn-detail-toggle-3')).toBeNull();

    fireEvent.click(screen.getByTestId('turn-detail-toggle-4'));

    await waitFor(() => expect(requestedPaths).toHaveLength(1));
    expect(requestedPaths[0]).toBe(
      '/api/workitems/100/clarification-conversations/1/turns/1/events',
    );
  });

  // F25 回归：旧判定用 turn.content === streamedText。引入非文本事件后落库正文
  // 与流式文本不再逐字相同，会多出一个重复气泡。
  it('does not duplicate the reply bubble when non-text events change the streamed text', async () => {
    await renderPanel({
      acpInteractionSupported: true,
      processingStatus: 'PROCESSING',
      processingTurnId: 3,
      turns: [
        { id: 3, direction: 'INBOUND', content: '请给方案', status: 'COMPLETED' },
        { id: 4, direction: 'OUTBOUND', content: '最终方案（已补充工具产出）', status: 'COMPLETED' },
      ],
    });
    await screen.findByText('最终方案（已补充工具产出）');

    emit(1, 'acp_plan', {
      type: 'acp_plan',
      data: { entries: [{ content: '确认选型', priority: 'high', status: 'completed' }] },
    });
    emit(2, 'text', { type: 'text', content: '最终方案' });
    emit(3, 'status', { type: 'status', status: 'completed' });

    await waitFor(() =>
      expect(screen.getByText('最终方案（已补充工具产出）')).toBeInTheDocument());
    // 落库正文已在气泡里，流式文本不能再单独渲染一个气泡
    expect(screen.queryByText('最终方案')).toBeNull();
  });

  it('still shows the streamed reply as a bubble while nothing is persisted yet', async () => {
    await renderPanel({
      acpInteractionSupported: true,
      processingStatus: 'PROCESSING',
      processingTurnId: 3,
      turns: [{ id: 3, direction: 'INBOUND', content: '请给方案', status: 'PROCESSING' }],
    });
    await screen.findByText('请给方案');

    emit(1, 'text', { type: 'text', content: '还没落库的回复' });
    emit(2, 'status', { type: 'status', status: 'completed' });

    expect(await screen.findByText('还没落库的回复')).toBeInTheDocument();
  });

  // 工单 53305：回复状态结束但问题卡片还没答时，输入行仍由卡片承载，
  // 「回复结束就聚焦输入框」不能把焦点抢到卡片上、也不能提前放出输入框。
  it('keeps the card in place and focus off the input when the reply ends unresolved', async () => {
    const pendingCard = {
      requestId: 'req-focus', turnId: 3, mode: 'form', message: '选一个',
      requestedSchema: SELECT_SCHEMA, status: 'PENDING', gmtCreate: '',
    };
    const { queryClient } = await renderPanel({
      acpInteractionSupported: true,
      processingStatus: 'PROCESSING',
      processingTurnId: 3,
      pendingElicitations: [pendingCard],
      turns: [{ id: 3, direction: 'INBOUND', content: '请给方案', status: 'PROCESSING' }],
    });
    await screen.findByTestId('elicitation-wizard-req-focus');

    mockPanel({
      acpInteractionSupported: true,
      processingStatus: null,
      processingTurnId: null,
      pendingElicitations: [pendingCard],
      turns: [{ id: 3, direction: 'INBOUND', content: '请给方案', status: 'COMPLETED' }],
    });
    refetchConversation(queryClient);

    await waitFor(() =>
      expect(screen.queryByTestId('clarification-replying-indicator')).toBeNull());
    expect(screen.getByTestId('elicitation-wizard-req-focus')).toBeInTheDocument();
    expect(screen.queryByPlaceholderText('输入消息...')).toBeNull();
  });

  // 工单 53305：卡片结算、会话回到静默后，重新出现的输入框要直接拿到焦点。
  it('focuses the input once the card is settled and the conversation goes idle', async () => {
    await renderPanel({ acpInteractionSupported: true });
    await screen.findByPlaceholderText('输入消息...');
    expect(document.activeElement).not.toBe(document.querySelector('.ProseMirror'));

    emit(1, 'acp_elicitation', {
      type: 'acp_elicitation',
      data: {
        requestId: 'req-focus', mode: 'form', message: '选一个',
        requestedSchema: JSON.parse(SELECT_SCHEMA),
      },
    });
    expect(await screen.findByTestId('elicitation-wizard-req-focus')).toBeInTheDocument();
    expect(screen.queryByPlaceholderText('输入消息...')).toBeNull();

    emit(2, 'acp_elicitation_resolved', {
      type: 'acp_elicitation_resolved',
      data: { requestId: 'req-focus', action: 'accept', content: { q0: 'nextjs' } },
    });

    await screen.findByPlaceholderText('输入消息...');
    await waitFor(() => expect(document.activeElement).toBe(document.querySelector('.ProseMirror')));
  });
});

// 工单 55851：卡片被系统取消 / 轮次异常结束后的输入恢复与滚动无关性。
describe('WorkitemClarificationPanel 卡片异常结束与输入恢复（工单 55851）', () => {
  beforeEach(() => {
    detailCalls = 0;
    refreshCalls = 0;
  });

  afterEach(() => {
    clearClarificationPrefill('100');
    realtime.callback = null;
    vi.restoreAllMocks();
  });

  // 验收：仅上下滚动不会发 reply/cancel/decline 请求，问题卡片不被滚动结束。
  it('滚动不触发任何卡片提交，卡片保持可作答', async () => {
    const cardPosts: string[] = [];
    await renderPanel({
      acpInteractionSupported: true,
      processingStatus: 'PROCESSING',
      processingTurnId: 3,
      cancelSupported: true,
      pendingElicitations: [{
        requestId: 'req-scroll', turnId: 3, mode: 'form', message: '滚动无关的卡片',
        requestedSchema: SELECT_SCHEMA, status: 'PENDING', gmtCreate: '',
      }],
      turns: [{ id: 3, direction: 'INBOUND', content: '请给方案', status: 'PROCESSING' }],
    });
    expect(await screen.findByTestId('elicitation-wizard-req-scroll')).toBeInTheDocument();
    server.use(
      http.post(
        '/api/workitems/:workitemId/clarification-conversations/:conversationId/turns',
        ({ request }) => { cardPosts.push(new URL(request.url).pathname); return ok(null); },
      ),
      http.post(
        '/api/workitems/:workitemId/clarification-conversations/:conversationId/turns/:turnId/cancel',
        ({ request }) => { cardPosts.push(new URL(request.url).pathname); return ok(null); },
      ),
      http.post(
        '/api/workitems/:workitemId/clarification-conversations/:conversationId/elicitations/:requestId/reply',
        ({ request }) => { cardPosts.push(new URL(request.url).pathname); return ok(null); },
      ),
    );

    const scrollEl = screen.getByTestId('clarification-message-scroll');
    Object.defineProperty(scrollEl, 'scrollHeight', { configurable: true, value: 1000 });
    Object.defineProperty(scrollEl, 'clientHeight', { configurable: true, value: 300 });
    Object.defineProperty(scrollEl, 'scrollTop', { configurable: true, value: 100 });
    fireEvent.scroll(scrollEl);
    fireEvent.wheel(scrollEl, { deltaY: -240 });
    fireEvent.scroll(scrollEl);
    await act(async () => { await Promise.resolve(); });

    expect(cardPosts).toHaveLength(0);
    expect(screen.getByTestId('elicitation-wizard-req-scroll')).toBeInTheDocument();
    expect(screen.getByText('滚动无关的卡片')).toBeInTheDocument();
  });

  // 现场形态：系统取消卡片、AI 改文字澄清并结束本轮（流终态已到），详情滞后仍报
  // PROCESSING。输入不再是无提示禁用：给出「结算中」状态与重试入口；后端结算后
  // 无需退出重进，输入即可用。
  it('卡片被取消且详情滞后时给出结算中状态，结算后输入恢复', async () => {
    const { queryClient } = await renderPanel({
      acpInteractionSupported: true,
      processingStatus: 'PROCESSING',
      processingTurnId: 3,
      pendingElicitations: [{
        requestId: 'req-canceled', turnId: 3, mode: 'form', message: '选一个',
        requestedSchema: SELECT_SCHEMA, status: 'PENDING', gmtCreate: '',
      }],
      turns: [{ id: 3, direction: 'INBOUND', content: '请给方案', status: 'PROCESSING' }],
    });
    expect(await screen.findByTestId('elicitation-wizard-req-canceled')).toBeInTheDocument();

    // 系统取消卡片 → AI 改用文字澄清并结束本轮
    emit(1, 'acp_elicitation_resolved', {
      type: 'acp_elicitation_resolved',
      data: { requestId: 'req-canceled', action: 'cancel' },
    });
    emit(2, 'text', { type: 'text', content: '既然问题卡片被取消，我用文字汇总' });
    emit(3, 'status', { type: 'status', status: 'completed' });

    // 卡片撤下、回复指示隐藏（流终态），详情未结算：输入必须有明确状态，不能静默禁用
    await waitFor(() =>
      expect(screen.queryByTestId('elicitation-wizard-req-canceled')).toBeNull());
    await waitFor(() =>
      expect(screen.queryByTestId('clarification-replying-indicator')).toBeNull());
    expect(await screen.findByTestId('clarification-settling-notice')).toBeInTheDocument();
    expect(screen.getByPlaceholderText('输入消息...')).toBeDisabled();

    // 后端结算：提示消失、输入可用（无需退出澄清再进入）
    mockPanel({
      processingStatus: null,
      processingTurnId: null,
      turns: [
        { id: 3, direction: 'INBOUND', content: '请给方案', status: 'SUCCESS' },
        { id: 4, direction: 'OUTBOUND', content: '既然问题卡片被取消，我用文字汇总', status: 'COMPLETED' },
      ],
    });
    refetchConversation(queryClient);

    await waitFor(() => expect(screen.queryByTestId('clarification-settling-notice')).toBeNull());
    await waitFor(() => expect(screen.getByPlaceholderText('输入消息...')).toBeEnabled());
  });
});

// 工单 56330：澄清面板的问题卡片支持一键收起/展开，结算后先淡出再卸载。
describe('WorkitemClarificationPanel 问题卡片收起/展开（工单 56330）', () => {
  beforeEach(() => {
    detailCalls = 0;
    refreshCalls = 0;
  });

  afterEach(() => {
    clearClarificationPrefill('100');
    realtime.callback = null;
    vi.restoreAllMocks();
  });

  it('澄清面板的卡片带收起入口，收起后仅保留标题行且不恢复自由输入', async () => {
    await renderPanel({
      acpInteractionSupported: true,
      processingStatus: 'PROCESSING',
      processingTurnId: 3,
      pendingElicitations: [{
        requestId: 'req-collapse', turnId: 3, mode: 'form', message: '选一个',
        requestedSchema: SELECT_SCHEMA, status: 'PENDING', gmtCreate: '',
      }],
      turns: [{ id: 3, direction: 'INBOUND', content: '请给方案', status: 'PROCESSING' }],
    });
    await screen.findByTestId('elicitation-wizard-req-collapse');

    fireEvent.click(screen.getByTestId('elicitation-toggle-req-collapse'));
    expect(screen.getByTestId('elicitation-collapsed-req-collapse')).toBeInTheDocument();
    expect(screen.getByTestId('elicitation-count-req-collapse')).toHaveTextContent('第 1/1 题');
    expect(screen.queryByTestId('elicitation-skip-req-collapse')).toBeNull();
    expect(screen.queryByTestId('elicitation-submit-req-collapse')).toBeNull();
    expect(screen.queryByPlaceholderText('输入消息...')).toBeNull();

    fireEvent.click(screen.getByTestId('elicitation-toggle-req-collapse'));
    expect(screen.getByTestId('elicitation-skip-req-collapse')).toBeInTheDocument();
    expect(screen.queryByTestId('elicitation-collapsed-req-collapse')).toBeNull();
  });

  it('结算后卡片先淡出再卸载，期间输入框不提前出现', async () => {
    await renderPanel({ acpInteractionSupported: true });
    await screen.findByPlaceholderText('输入消息...');
    emit(1, 'acp_elicitation', {
      type: 'acp_elicitation',
      data: {
        requestId: 'req-fade', mode: 'form', message: '选一个',
        requestedSchema: JSON.parse(SELECT_SCHEMA),
      },
    });
    const wizard = await screen.findByTestId('elicitation-wizard-req-fade');
    expect(wizard).not.toHaveClass('is-dismissing');

    emit(2, 'acp_elicitation_resolved', {
      type: 'acp_elicitation_resolved',
      data: { requestId: 'req-fade', action: 'accept', content: { q0: 'nextjs' } },
    });

    // 淡出窗口内：卡片仍在原位并标记 is-dismissing，输入框不得同时出现
    expect(screen.getByTestId('elicitation-wizard-req-fade')).toHaveClass('is-dismissing');
    expect(screen.queryByPlaceholderText('输入消息...')).toBeNull();

    // 窗口结束：卡片卸载、输入框恢复
    await screen.findByPlaceholderText('输入消息...');
    await waitFor(() => expect(screen.queryByTestId('elicitation-wizard-req-fade')).toBeNull());
  });

  it('下一组问题（新 requestId）默认展开，不继承上一组的收起状态', async () => {
    await renderPanel({
      acpInteractionSupported: true,
      processingStatus: 'PROCESSING',
      processingTurnId: 3,
      turns: [{ id: 3, direction: 'INBOUND', content: '请给方案', status: 'PROCESSING' }],
    });
    await screen.findByText('请给方案');
    const oneQ = {
      type: 'object',
      properties: { z0: { type: 'string', title: '单问题', oneOf: [{ const: 'X', title: 'X' }] } },
      required: ['z0'],
    };

    emit(1, 'acp_elicitation', { type: 'acp_elicitation', data: { requestId: 'req-A', mode: 'form', message: '第一组', requestedSchema: oneQ } });
    emit(2, 'acp_elicitation', { type: 'acp_elicitation', data: { requestId: 'req-B', mode: 'form', message: '第二组', requestedSchema: oneQ } });
    await screen.findByTestId('elicitation-wizard-req-A');

    fireEvent.click(screen.getByTestId('elicitation-toggle-req-A'));
    expect(screen.getByTestId('elicitation-collapsed-req-A')).toBeInTheDocument();

    emit(3, 'acp_elicitation_resolved', { type: 'acp_elicitation_resolved', data: { requestId: 'req-A', action: 'accept', content: { z0: 'X' } } });

    const nextToggle = await screen.findByTestId('elicitation-toggle-req-B');
    expect(nextToggle).toHaveAttribute('aria-expanded', 'true');
    expect(screen.queryByTestId('elicitation-collapsed-req-B')).toBeNull();
  });
});


describe('durable clarification history', () => {
  beforeEach(() => useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'Test', description: '' }, 'ADMIN'));
  afterEach(() => { clearClarificationPrefill('100'); realtime.callback = null; useAuthStore.getState().clear(); });
  const rows = (count: number, turnId: number) => Array.from({ length: count }, (_, i) => ({
    requestId: `q-${turnId}-${i}`, turnId, mode: 'form', message: `Question ${turnId}/${i}`,
    requestedSchema: SELECT_SCHEMA, status: 'ANSWERED', gmtCreate: `2026-01-01T00:00:0${i}`,
    answerJson: JSON.stringify({ q0: `Exact answer ${turnId}/${i}` }), deliveryStatus: 'WRITTEN',
  }));
  it('keeps seven and two answers once beside their original IN across live snapshots and reload', async () => {
    const cards = [...rows(7, 3), ...rows(2, 5)];
    const conv = { acpInteractionSupported: true, questionHistory: cards, answerDeliveries: cards,
      turns: [{ id: 3, direction: 'IN', content: 'First IN', status: 'SUCCESS' },
        { id: 4, direction: 'OUT', content: 'First result', status: 'SUCCESS' },
        { id: 5, direction: 'IN', content: 'Second IN', status: 'SUCCESS' }] };
    const { view, queryClient } = await renderPanel(conv);
    expect(await screen.findByText('Exact answer 3/0')).toBeInTheDocument();
    emit(1, 'acp_elicitation_resolved', { data: { requestId: cards[0].requestId, action: 'accept', content: { q0: 'old live answer' } } });
    emit(2, 'acp_elicitation', { data: { requestId: cards[0].requestId, message: cards[0].message, requestedSchema: JSON.parse(SELECT_SCHEMA) } });
    for (const card of cards) expect(screen.getAllByText(JSON.parse(card.answerJson).q0)).toHaveLength(1);
    expect(screen.queryByText(/回答已写入执行器，等待后续进展/)).not.toBeInTheDocument();
    expect(screen.queryByTestId(`elicitation-wizard-${cards[0].requestId}`)).not.toBeInTheDocument();
    view.unmount(); queryClient.clear();
    await renderPanel(conv);
    for (const card of cards) expect(await screen.findAllByText(JSON.parse(card.answerJson).q0)).toHaveLength(1);
  });
  it('links uncertain delivery to its preserved answer and restores expired context only into the draft', async () => {
    let sends = 0; let replies = 0;
    server.use(http.post('/api/workitems/:workitemId/clarification-conversations/:conversationId/turns', () => { sends++; return ok(null); }),
      http.post('/api/workitems/:workitemId/clarification-conversations/:conversationId/elicitations/:requestId/reply', () => { replies++; return ok(null); }));
    const uncertain = { ...rows(1, 3)[0], deliveryStatus: 'UNKNOWN' };
    const expired = { ...rows(1, 5)[0], status: 'EXPIRED', deliveryStatus: null, answerJson: null };
    await renderPanel({ acpInteractionSupported: true, questionHistory: [uncertain, expired],
      pendingElicitations: [{ ...expired, status: 'PENDING' }],
      turns: [{ id: 3, direction: 'IN', content: 'Earlier request', status: 'SUCCESS' },
        { id: 5, direction: 'IN', content: 'Original request for fresh session', status: 'CANCELED' }] });
    const history = await screen.findByTestId('question-history-3-q-3-0');
    expect(screen.queryByTestId('elicitation-wizard-q-5-0')).not.toBeInTheDocument();
    expect(history).toHaveTextContent('Exact answer 3/0');
    expect(history).toHaveTextContent('回答的处理状态无法确认，已保留答案，请查看本轮结果');
    await typeComposer('Keep my draft');
    fireEvent.click(screen.getByRole('button', { name: '继续回答' }));
    fireEvent.click(screen.getByRole('button', { name: '继续回答' }));
    const input = screen.getByPlaceholderText('输入消息...') as HTMLTextAreaElement;
    expect(input.value).toContain('Keep my draft');
    expect(input.value).toContain('Original request for fresh session');
    expect(input.value).toContain('Question 5/0');
    expect(input.value).toContain('首选技术栈');
    expect(input.value.match(/Original request for fresh session/g)).toHaveLength(1);
    expect(sends).toBe(0); expect(replies).toBe(0);
    fireEvent.click(screen.getByRole('button', { name: '发送消息' }));
    await waitFor(() => expect(sends).toBe(1));
    expect(replies).toBe(0);
  });
});


it('keeps expired draft restoration disabled during processing and for read-only workitems', async () => {
  useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'Test', description: '' }, 'READ_ONLY');
  const conv = { acpInteractionSupported: true, questionHistory: [{ requestId: 'expired-idle', turnId: 3, mode: 'form', message: 'Expired question context', requestedSchema: SELECT_SCHEMA, status: 'EXPIRED', gmtCreate: '' }],
    turns: [{ id: 3, direction: 'IN', content: 'Original request', status: 'CANCELED' }] };
  const { view, queryClient } = await renderPanel(conv);
  const button = await screen.findByRole('button', { name: '继续回答' });
  expect(button).toBeDisabled();
  act(() => useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'Test', description: '' }, 'ADMIN'));
  expect(button).toBeEnabled();
  mockPanel({ ...conv, processingStatus: 'PROCESSING', processingTurnId: 4 });
  refetchConversation(queryClient);
  await waitFor(() => expect(button).toBeDisabled());
  view.unmount(); queryClient.clear(); useAuthStore.getState().clear(); clearClarificationPrefill('100'); realtime.callback = null;
});
