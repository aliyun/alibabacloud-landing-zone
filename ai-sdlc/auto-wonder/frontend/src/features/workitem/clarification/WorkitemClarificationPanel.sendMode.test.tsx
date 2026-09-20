import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
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
    const { textarea } = await openPanel();

    await waitFor(() => expect(textarea).toHaveFocus());
  });

  it('AC-02: 全屏模式进入后同样自动聚焦', async () => {
    mockBackend();
    const { textarea } = await openPanel({ fullscreen: true });

    await waitFor(() => expect(textarea).toHaveFocus());
  });

  it('AC-02: 由非全屏切入全屏时重新聚焦（RightPanel 复用同一实例，不会重新挂载）', async () => {
    mockBackend();
    const { textarea, rerenderWith } = await openPanel();
    await waitFor(() => expect(textarea).toHaveFocus());

    textarea.blur();
    expect(textarea).not.toHaveFocus();

    rerenderWith({ fullscreen: true });
    await waitFor(() => expect(textarea).toHaveFocus());
  });

  it('FR-003: 切换会话不会把焦点抢回输入框', async () => {
    mockBackend([conversationPayload(202, '最新会话消息'), conversationPayload(101, '历史会话消息')]);
    const { textarea } = await openPanel();
    await waitFor(() => expect(textarea).toHaveFocus());
    expect(await screen.findByText('最新会话消息')).toBeInTheDocument();

    // 用户已经把焦点移到别处（例如去点消息上的复制按钮）
    textarea.blur();

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
    expect(textarea).not.toHaveFocus();

    payload = { ...payload, processingStatus: null, processingTurnId: null };
    queryClient.invalidateQueries();

    await waitFor(() => expect(textarea).not.toBeDisabled());
    await waitFor(() => expect(textarea).toHaveFocus());
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

    fireEvent.change(textarea, { target: { value: '第一行' } });
    fireEvent.keyDown(textarea, { key: 'Enter', code: 'Enter' });
    await settle();
    expect(submittedContents).toEqual([]);
    expect(textarea).toHaveValue('第一行');

    fireEvent.keyDown(textarea, { key: 'Enter', code: 'Enter', shiftKey: true });
    await waitFor(() => expect(submittedContents).toEqual(['第一行']));
    // 发送即清空输入框，说明 Shift+回车没有被当成换行注入内容
    expect(textarea).toHaveValue('');
  });

  it('AC-05: 切换为回车发送后，回车发送、Shift+回车换行', async () => {
    mockBackend();
    const { textarea } = await openPanel();

    await chooseSendMode(SEND_MODE_LABELS.enter);
    await waitFor(() => expect(currentSendModeLabel()).toBe(SEND_MODE_LABELS.enter));

    fireEvent.change(textarea, { target: { value: '走回车' } });
    fireEvent.keyDown(textarea, { key: 'Enter', code: 'Enter', shiftKey: true });
    await settle();
    expect(submittedContents).toEqual([]);
    expect(textarea).toHaveValue('走回车');

    fireEvent.keyDown(textarea, { key: 'Enter', code: 'Enter' });
    await waitFor(() => expect(submittedContents).toEqual(['走回车']));
  });

  it('FR-007: 回车发送模式下输入法组合期的回车仍然不发送', async () => {
    seedUserSetting(CLARIFICATION_SEND_MODE_KEY, '"enter"');
    mockBackend();
    const { textarea } = await openPanel();
    await waitFor(() => expect(currentSendModeLabel()).toBe(SEND_MODE_LABELS.enter));

    fireEvent.change(textarea, { target: { value: '候选词' } });
    fireEvent.compositionStart(textarea);
    fireEvent.keyDown(textarea, { key: 'Enter', code: 'Enter' });
    await settle();
    expect(submittedContents).toEqual([]);
    expect(textarea).toHaveValue('候选词');

    fireEvent.compositionEnd(textarea);
    fireEvent.keyDown(textarea, { key: 'Enter', code: 'Enter' });
    await waitFor(() => expect(submittedContents).toEqual(['候选词']));
  });

  it('AC-06/AC-07: 切换写入用户级偏好，重新挂载面板后偏好保持', async () => {
    mockBackend();
    const first = await openPanel();
    await chooseSendMode(SEND_MODE_LABELS.enter);
    await waitFor(() => expect(currentSendModeLabel()).toBe(SEND_MODE_LABELS.enter));

    // 模拟刷新页面 / 换设备：全新 QueryClient，只能靠服务端存的偏好恢复
    first.view.unmount();
    const { textarea } = await openPanel();
    await waitFor(() => expect(currentSendModeLabel()).toBe(SEND_MODE_LABELS.enter));

    fireEvent.change(textarea, { target: { value: '刷新后' } });
    fireEvent.keyDown(textarea, { key: 'Enter', code: 'Enter' });
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

    const { textarea } = await openPanel();
    await waitFor(() => expect(currentSendModeLabel()).toBe(SEND_MODE_LABELS['shift-enter']));

    fireEvent.change(textarea, { target: { value: '兜底' } });
    fireEvent.keyDown(textarea, { key: 'Enter', code: 'Enter', shiftKey: true });
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
    fireEvent.change(textarea, { target: { value: '没保存上' } });
    fireEvent.keyDown(textarea, { key: 'Enter', code: 'Enter' });
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
