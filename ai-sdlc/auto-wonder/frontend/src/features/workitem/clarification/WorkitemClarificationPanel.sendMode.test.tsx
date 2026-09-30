import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within, act } from '@testing-library/react';
import { typeComposer, pressComposerKey, waitForComposerFocus } from './composerTestUtils';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { message } from 'antd';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { resetUserSettingStore, seedUserSetting } from '@/test/mocks/handlers';
import { WorkitemClarificationPanel } from './WorkitemClarificationPanel';
import { clearClarificationPrefill, writeClarificationPrefill } from './prefill';
import { CLARIFICATION_SEND_MODE_KEY, SEND_MODE_LABELS } from './sendMode';

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

function conversationPayload(id = 1, content = '历史提问') {
  return {
    id,
    agentId: 42,
    agentName: 'Agent-X',
    channelConversationId: `ch-${id}`,
    status: 'ACTIVE',
    executorOnline: true,
    streamingSupported: true,
    cancelSupported: false,
    cliSessionRef: null,
    processingStatus: null as string | null,
    processingTurnId: null as number | null,
    lastTurnAt: '2026-01-01T00:00:01',
    gmtCreate: '2026-01-01T00:00:00',
    turns: [{
      id: id * 10,
      direction: 'IN',
      content,
      status: 'SUCCESS',
      error: null,
      gmtCreate: '2026-01-01T00:00:01',
    }],
  };
}

let submittedContents: string[] = [];

function mockBackend(conversations = [conversationPayload()]) {
  submittedContents = [];
  server.use(
    http.get('/api/squads', () => ok({
      list: [{ id: 9, name: '交付小队', description: '', memberCount: 1, gmtCreate: '' }],
      total: 1, pageNum: 1, pageSize: 100,
    })),
    http.get('/api/squads/:squadId/members', () =>
      ok([{ agentId: 42, agentName: 'Agent-X', roleCode: 'AW_FS_DEV' }])),
    http.get('/api/workitems/:workitemId/clarification-conversations', () => ok(conversations)),
    http.get('/api/workitems/:workitemId/clarification-conversations/:conversationId', ({ params }) =>
      ok(conversations.find((item) => item.id === Number(params.conversationId)) ?? null)),
    http.get('/api/workitems/:workitemId/clarification-conversations/:conversationId/events', () => ok([])),
    http.post('/api/workitems/:workitemId/clarification-conversations/:conversationId/turns',
      async ({ request }) => {
        const body = await request.json() as { content?: string } | null;
        submittedContents.push(body?.content ?? '');
        return ok(null);
      }),
  );
}

interface PanelProps {
  fullscreen?: boolean;
}

function renderPanel(props: PanelProps = {}) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const element = (override: PanelProps = {}) => (
    <QueryClientProvider client={queryClient}>
      <WorkitemClarificationPanel workitemId="100" agents={[] as never} {...props} {...override} />
    </QueryClientProvider>
  );
  const view = render(element());
  return {
    view,
    queryClient,
    // RightPanel 切全屏走的是同一个实例的 prop 变化，用 rerender 复现
    rerenderWith: (override: PanelProps) => view.rerender(element(override)),
  };
}

function sendModeEntry() {
  return screen.getByTestId('clarification-send-mode-select');
}

/** 入口自身要「标明当前模式」（AC-03），所以断言精确相等：
 *  '回车发送' 是 'Shift+回车发送' 的子串，用 toContain 会让默认态误判成已切换。 */
function currentSendModeLabel() {
  return sendModeEntry().textContent?.trim() ?? '';
}

async function chooseSendMode(label: string) {
  fireEvent.mouseDown(within(sendModeEntry()).getByRole('combobox'));
  fireEvent.click(await screen.findByText(label));
}

async function openPanel(props: PanelProps = {}) {
  const panel = renderPanel(props);
  const textarea = await screen.findByPlaceholderText('输入消息...');
  return { ...panel, textarea };
}

/** 聚焦发生在 requestAnimationFrame 里，等一拍再断言。 */
async function settle() {
  await new Promise((resolve) => { setTimeout(resolve, 50); });
}

describe('需求澄清面板自动聚焦（FR-001~FR-004）', () => {
  beforeEach(() => {
    resetUserSettingStore();
    writeClarificationPrefill('100', { squadId: 9, agentId: 42 });
  });

  afterEach(() => {
    clearClarificationPrefill('100');
    vi.restoreAllMocks();
  });

  it('AC-01: 进入非全屏面板后输入框自动获得焦点，不需要用户再点一次', async () => {
    mockBackend();
    await openPanel();

    await waitForComposerFocus({ focused: true });
  });

  it('AC-02: 全屏模式进入后同样自动聚焦', async () => {
    mockBackend();
    await openPanel({ fullscreen: true });

    await waitForComposerFocus({ focused: true });
  });

  it('AC-02: 由非全屏切入全屏时重新聚焦（RightPanel 复用同一实例，不会重新挂载）', async () => {
    mockBackend();
    const { rerenderWith } = await openPanel();
    await waitForComposerFocus({ focused: true });

    await act(async () => { (document.activeElement as HTMLElement | null)?.blur(); });
    await waitForComposerFocus({ focused: false });

    rerenderWith({ fullscreen: true });
    await waitForComposerFocus({ focused: true });
  });

  it('FR-003: 切换会话不会把焦点抢回输入框', async () => {
    mockBackend([conversationPayload(202, '最新会话消息'), conversationPayload(101, '历史会话消息')]);
    await openPanel();
    await waitForComposerFocus({ focused: true });
    expect(await screen.findByText('最新会话消息')).toBeInTheDocument();

    // 用户已经把焦点移到别处（例如去点消息上的复制按钮）
    await act(async () => { (document.activeElement as HTMLElement | null)?.blur(); });

    const selector = screen.getByTestId('clarification-conversation-select');
    fireEvent.mouseDown(within(selector).getByRole('combobox'));
    fireEvent.click(await screen.findByText(/会话 #101/));
    expect(await screen.findByText('历史会话消息')).toBeInTheDocument();

    await settle();
    expect(screen.getByPlaceholderText('输入消息...')).not.toHaveFocus();
  });

  it('FR-004: 输入行还在禁用态时不聚焦，等它真正可用后才把光标交过去', async () => {
    mockBackend();
    let payload = {
      ...conversationPayload(1, '正在回复'),
      processingStatus: 'PROCESSING' as string | null,
      processingTurnId: 11 as number | null,
    };
    server.use(
      http.get('/api/workitems/:workitemId/clarification-conversations', () => ok([payload])),
      http.get('/api/workitems/:workitemId/clarification-conversations/:conversationId', () => ok(payload)),
    );

    const { textarea, queryClient } = await openPanel();
    expect(textarea).toBeDisabled();
    await settle();
    expect(document.activeElement).not.toBe(document.querySelector('.ProseMirror'));

    payload = { ...payload, processingStatus: null, processingTurnId: null };
    queryClient.invalidateQueries();

    await waitFor(() => expect(textarea).not.toBeDisabled());
    await waitFor(() => expect(document.activeElement).toBe(document.querySelector('.ProseMirror')));
  });
});

describe('需求澄清面板发送方式（FR-005~FR-010 / AC-03~AC-07）', () => {
  beforeEach(() => {
    resetUserSettingStore();
    writeClarificationPrefill('100', { squadId: 9, agentId: 42 });
  });

  afterEach(() => {
    clearClarificationPrefill('100');
    vi.restoreAllMocks();
  });

  it('AC-03+FR-006: 输入框旁的入口标明当前模式，默认是 Shift+回车发送', async () => {
    mockBackend();
    await openPanel();

    expect(currentSendModeLabel()).toBe(SEND_MODE_LABELS['shift-enter']);
  });

  it('AC-04: 默认模式下回车只换行不发送，Shift+回车发送且不注入换行', async () => {
    mockBackend();
    const { textarea } = await openPanel();

    await typeComposer('第一行');
    pressComposerKey({ key: 'Enter' });
    await settle();
    expect(submittedContents).toEqual([]);
    expect(textarea).toHaveValue('第一行');

    pressComposerKey({ key: 'Enter', shiftKey: true });
    await waitFor(() => expect(submittedContents).toEqual(['第一行']));
    // 发送即清空输入框，说明 Shift+回车没有被当成换行注入内容
    expect(textarea).toHaveValue('');
  });

  it('AC-05: 切换为回车发送后，回车发送、Shift+回车换行', async () => {
    mockBackend();
    const { textarea } = await openPanel();

    await chooseSendMode(SEND_MODE_LABELS.enter);
    await waitFor(() => expect(currentSendModeLabel()).toBe(SEND_MODE_LABELS.enter));

    await typeComposer('走回车');
    pressComposerKey({ key: 'Enter', shiftKey: true });
    await settle();
    expect(submittedContents).toEqual([]);
    expect(textarea).toHaveValue('走回车');

    pressComposerKey({ key: 'Enter' });
    await waitFor(() => expect(submittedContents).toEqual(['走回车']));
  });

  it('FR-007: 回车发送模式下输入法组合期的回车仍然不发送', async () => {
    seedUserSetting(CLARIFICATION_SEND_MODE_KEY, '"enter"');
    mockBackend();
    const { textarea } = await openPanel();
    await waitFor(() => expect(currentSendModeLabel()).toBe(SEND_MODE_LABELS.enter));

    await typeComposer('候选词');
    const pm = () => document.querySelector('.ProseMirror') as HTMLElement;
    fireEvent.compositionStart(pm());
    // jsdom 的 KeyboardEvent 不带 isComposing，构造后手工补上（等价于 IME 组合期的回车）
    const composingEnter = new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true });
    Object.defineProperty(composingEnter, 'isComposing', { value: true });
    pm().dispatchEvent(composingEnter);
    await settle();
    expect(submittedContents).toEqual([]);
    expect(textarea).toHaveValue('候选词');

    fireEvent.compositionEnd(pm());
    pressComposerKey({ key: 'Enter' });
    await waitFor(() => expect(submittedContents).toEqual(['候选词']));
  });

  it('AC-06/AC-07: 切换写入用户级偏好，重新挂载面板后偏好保持', async () => {
    mockBackend();
    const first = await openPanel();
    await chooseSendMode(SEND_MODE_LABELS.enter);
    await waitFor(() => expect(currentSendModeLabel()).toBe(SEND_MODE_LABELS.enter));

    // 模拟刷新页面 / 换设备：全新 QueryClient，只能靠服务端存的偏好恢复
    first.view.unmount();
    await openPanel();
    await waitFor(() => expect(currentSendModeLabel()).toBe(SEND_MODE_LABELS.enter));

    await typeComposer('刷新后');
    pressComposerKey({ key: 'Enter' });
    await waitFor(() => expect(submittedContents).toEqual(['刷新后']));
    // 这条用例要挂载两次完整面板（模拟刷新 / 换设备），请求与渲染量是同文件其他
    // 用例的两倍，全量套件并行时 5s 默认超时不够。只放宽这一条，不动全局 testTimeout。
  }, 20000);

  it('偏好读取失败时静默回落到默认发送方式，面板照常可用', async () => {
    mockBackend();
    server.use(
      http.get('/api/users/me/settings/:key', () => HttpResponse.json(
        { success: false, code: '10000', message: 'boom', traceId: null, data: null },
      )),
    );

    await openPanel();
    await waitFor(() => expect(currentSendModeLabel()).toBe(SEND_MODE_LABELS['shift-enter']));

    await typeComposer('兜底');
    pressComposerKey({ key: 'Enter', shiftKey: true });
    await waitFor(() => expect(submittedContents).toEqual(['兜底']));
  });

  it('偏好写入失败时回滚模式，界面标注与实际按键行为保持一致', async () => {
    mockBackend();
    server.use(
      http.put('/api/users/me/settings/:key', () => HttpResponse.json(
        { success: false, code: '10000', message: 'boom', traceId: null, data: null },
        { status: 500 },
      )),
    );

    const { textarea } = await openPanel();
    await waitFor(() => expect(currentSendModeLabel()).toBe(SEND_MODE_LABELS['shift-enter']));

    await chooseSendMode(SEND_MODE_LABELS.enter);
    await waitFor(() => expect(currentSendModeLabel()).toBe(SEND_MODE_LABELS['shift-enter']));

    // 回滚之后裸回车依旧只换行，不会出现「标着回车发送却发不出去」
    await typeComposer('没保存上');
    pressComposerKey({ key: 'Enter' });
    await settle();
    expect(submittedContents).toEqual([]);
    expect(textarea).toHaveValue('没保存上');
  });

  it('AC-03: 回复进行中只剩「终止响应」按钮时，发送方式入口依然在', async () => {
    mockBackend();
    const processing = {
      ...conversationPayload(1, '正在回复'),
      processingStatus: 'PROCESSING' as string | null,
      processingTurnId: 11 as number | null,
      cancelSupported: true,
    };
    server.use(
      http.get('/api/workitems/:workitemId/clarification-conversations', () => ok([processing])),
      http.get('/api/workitems/:workitemId/clarification-conversations/:conversationId', () => ok(processing)),
    );

    await openPanel();

    expect(await screen.findByText('终止响应')).toBeInTheDocument();
    expect(currentSendModeLabel()).toBe(SEND_MODE_LABELS['shift-enter']);
  });
});

describe('发送方式快速连续切换的落库一致性（工单 55511）', () => {
  beforeEach(() => {
    resetUserSettingStore();
    // 上一组用例的 message.error toast 会挂在 document.body 上跨用例残留，
    // RTL 清理不掉 antd 的全局 holder，不 destroy 会让后面的文本断言命中多个节点。
    message.destroy();
    writeClarificationPrefill('100', { squadId: 9, agentId: 42 });
  });

  afterEach(() => {
    clearClarificationPrefill('100');
    vi.restoreAllMocks();
    message.destroy();
  });

  function failureBody() {
    return HttpResponse.json(
      { success: false, code: '10000', message: 'boom', traceId: null, data: null },
      { status: 500 },
    );
  }

  it('旧保存失败不覆盖新选择：失败时已切走则静默放弃，最终选择与落库一致', async () => {
    mockBackend();
    let releaseFirstPut: (() => void) | null = null;
    const firstPutHeld = new Promise<void>((resolve) => { releaseFirstPut = resolve; });
    let putCalls = 0;
    server.use(
      http.put('/api/users/me/settings/:key', async ({ request }) => {
        putCalls += 1;
        if (putCalls === 1) {
          await firstPutHeld;
          return failureBody();
        }
        const body = await request.json() as { valueJson?: string | null } | null;
        seedUserSetting(CLARIFICATION_SEND_MODE_KEY, body?.valueJson ?? null);
        return ok({ key: CLARIFICATION_SEND_MODE_KEY, valueJson: body?.valueJson ?? null });
      }),
    );

    const first = await openPanel();
    await waitFor(() => expect(currentSendModeLabel()).toBe(SEND_MODE_LABELS['shift-enter']));

    // 第一次切换的 PUT 悬停在飞，第二次切换已经发生
    await chooseSendMode(SEND_MODE_LABELS.enter);
    await chooseSendMode(SEND_MODE_LABELS['shift-enter']);
    await settle();
    // 保存串行：第二个 PUT 必须排在第一个后面，不能并发抢跑
    expect(putCalls).toBe(1);

    releaseFirstPut!();
    await waitFor(() => expect(putCalls).toBe(2));

    // 旧失败不回滚新选择，也不弹「保存失败」
    expect(currentSendModeLabel()).toBe(SEND_MODE_LABELS['shift-enter']);
    expect(screen.queryByText('发送方式保存失败，请重试')).not.toBeInTheDocument();

    // 最终落库值 = 最终界面选择：重新挂载（模拟刷新/换设备）从服务端恢复
    first.view.unmount();
    await openPanel();
    await waitFor(() => expect(currentSendModeLabel()).toBe(SEND_MODE_LABELS['shift-enter']));
  });

  it('连续切换全部保存失败时回滚到最后落库值，且只提示一次', async () => {
    mockBackend();
    seedUserSetting(CLARIFICATION_SEND_MODE_KEY, '"enter"');
    let releaseFirstPut: (() => void) | null = null;
    const firstPutHeld = new Promise<void>((resolve) => { releaseFirstPut = resolve; });
    let putCalls = 0;
    server.use(
      http.put('/api/users/me/settings/:key', async () => {
        putCalls += 1;
        if (putCalls === 1) {
          await firstPutHeld;
        }
        return failureBody();
      }),
    );

    await openPanel();
    await waitFor(() => expect(currentSendModeLabel()).toBe(SEND_MODE_LABELS.enter));

    await chooseSendMode(SEND_MODE_LABELS['shift-enter']);
    await chooseSendMode(SEND_MODE_LABELS.enter);
    await settle();
    expect(putCalls).toBe(1);

    releaseFirstPut!();
    // 第二个 PUT 也失败：界面回到最后落库值（enter），第一条的失败已被新选择取代、静默
    await waitFor(() => expect(putCalls).toBe(2));
    await waitFor(() => expect(currentSendModeLabel()).toBe(SEND_MODE_LABELS.enter));
    expect(await screen.findAllByText('发送方式保存失败，请重试')).toHaveLength(1);
  });

  it('偏好落定前用户已切换：本地选择不被服务端旧值盖回', async () => {
    mockBackend();
    let releaseGet: (() => void) | null = null;
    const heldGet = new Promise<void>((resolve) => { releaseGet = resolve; });
    server.use(
      http.get('/api/users/me/settings/:key', async () => {
        await heldGet;
        return ok({ key: CLARIFICATION_SEND_MODE_KEY, valueJson: '"shift-enter"' });
      }),
    );

    await openPanel();
    await chooseSendMode(SEND_MODE_LABELS.enter);

    releaseGet!();
    await settle();
    expect(currentSendModeLabel()).toBe(SEND_MODE_LABELS.enter);
  });
});

describe('发送区底部操作行布局', () => {
  beforeEach(() => {
    resetUserSettingStore();
    message.destroy();
    writeClarificationPrefill('100', { squadId: 9, agentId: 42 });
  });

  afterEach(() => {
    clearClarificationPrefill('100');
    vi.restoreAllMocks();
    message.destroy();
  });

  function assertBottomActionsLayout() {
    const composer = screen.getByTestId('clarification-composer');
    const actions = within(composer).getByTestId('clarification-input-actions');
    expect(composer.style.flexDirection).toBe('column');
    expect(actions).toHaveStyle({
      width: '100%',
      flexDirection: 'row',
      justifyContent: 'space-between',
    });

    const textarea = within(composer).getByPlaceholderText('输入消息...');
    const select = within(actions).getByTestId('clarification-send-mode-select');
    const sendButton = within(actions).getByRole('button', { name: '发送消息' });
    expect(textarea.compareDocumentPosition(actions) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(select.compareDocumentPosition(sendButton) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  }

  it('将发送方式和发送按钮放在输入框下方同一行的左右两侧', async () => {
    mockBackend();
    await openPanel();

    assertBottomActionsLayout();
  });

  it('全屏和非全屏模式保持相同的底部操作行布局', async () => {
    mockBackend();
    const { rerenderWith } = await openPanel({ fullscreen: true });
    assertBottomActionsLayout();

    rerenderWith({ fullscreen: false });
    assertBottomActionsLayout();
  });

  it('终止响应替换右侧发送按钮时保留左侧发送方式', async () => {
    mockBackend();
    const processing = {
      ...conversationPayload(1, '正在回复'),
      processingStatus: 'PROCESSING' as string | null,
      processingTurnId: 11 as number | null,
      cancelSupported: true,
    };
    server.use(
      http.get('/api/workitems/:workitemId/clarification-conversations', () => ok([processing])),
      http.get('/api/workitems/:workitemId/clarification-conversations/:conversationId', () => ok(processing)),
    );

    await openPanel();
    const actions = screen.getByTestId('clarification-input-actions');

    const stopButton = await within(actions).findByRole('button', { name: /终止响应/ });
    expect(actions.lastElementChild).toBe(stopButton);
    expect(within(actions).queryByRole('button', { name: '发送消息' })).not.toBeInTheDocument();
    expect(within(actions).getByTestId('clarification-send-mode-select')).toBeInTheDocument();
  });
});
