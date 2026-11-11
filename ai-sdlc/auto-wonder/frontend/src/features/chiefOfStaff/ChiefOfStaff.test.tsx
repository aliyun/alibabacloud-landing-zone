import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, cleanup, within, fireEvent, act } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, useNavigate } from 'react-router-dom';
import { useAuthStore } from '@/shared/auth/store';
import { i18n } from '@/shared/i18n';
import { ChiefOfStaff, wideResizeExit } from './ChiefOfStaff';
import * as api from './api';
import { buildExecutorLaunchCommand, type ExecutorLaunchCommandVO } from '@/features/executor/api';
import {
  closeChiefPanel,
  openChiefPanel,
  prepareGuideSend,
  publishGuideRuntime,
  resetWorkspaceGuideBridge,
  subscribeGuideEvents,
} from '@/features/workspaceGuide/bridge';
vi.mock('./api');
vi.mock('@/features/executor/api');
vi.mock('@/shared/realtime/useRealtime', () => ({ useRealtime: vi.fn() }));
const ready: api.SetupStatus = { agentId: 10, executorId: 20, agentConfigured: true, skillsConfigured: true, executorConfigured: true, launchConfigured: true, ready: true, missingSkills: [] };
const conversation: api.Conversation = { id: 100, agentId: 10, agentName: 'Chief of Staff', channelConversationId: 'private', title: 'My conversation', archivedAt: null, status: 'ACTIVE', executorOnline: true, streamingSupported: true, cancelSupported: true, cliSessionRef: null, processingStatus: null, processingTurnId: null, lastTurnAt: null, gmtCreate: '', turns: [] };
let clients: QueryClient[] = [];
class TestResizeObserver {
  static instances: TestResizeObserver[] = [];
  constructor(readonly callback: ResizeObserverCallback) { TestResizeObserver.instances.push(this); }
  observe() {}
  unobserve() {}
  disconnect() {}
}
function stubMatchMedia(matches: boolean) {
  Object.defineProperty(window, 'matchMedia', { configurable: true, writable: true, value: (query: string) => ({
    matches, media: query, onchange: null, addEventListener: vi.fn(), removeEventListener: vi.fn(),
    addListener: vi.fn(), removeListener: vi.fn(), dispatchEvent: vi.fn(),
  }) });
}
function wideButton(name: '宽屏模式' | '退出宽屏') { return screen.getByRole('button', { name }); }
class TestPointerEvent extends MouseEvent {
  readonly pointerId: number;
  constructor(type: string, init: PointerEventInit = {}) {
    super(type, init);
    this.pointerId = init.pointerId ?? 1;
  }
}
function NextPage() { const navigate = useNavigate(); return <button onClick={() => navigate('/workitems/129')}>Next workitem</button>; }
function mount() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } }); clients.push(client);
  return render(<QueryClientProvider client={client}><MemoryRouter initialEntries={['/workitems/128']}><NextPage /><ChiefOfStaff /></MemoryRouter></QueryClientProvider>);
}
function pointerEvent(type: string, pointerId: number, clientX: number, clientY: number, button = 0) {
  return new TestPointerEvent(type, { bubbles: true, cancelable: true, pointerId, clientX, clientY, button });
}
function drag(element: Element, dx: number, dy: number, options: { pointerId?: number; button?: number; cancel?: boolean } = {}) {
  const pointerId = options.pointerId ?? 1;
  const button = options.button ?? 0;
  fireEvent(element, pointerEvent('pointerdown', pointerId, 100, 100, button));
  fireEvent(element, pointerEvent('pointermove', pointerId, 100 + dx, 100 + dy, button));
  fireEvent(element, pointerEvent(options.cancel ? 'pointercancel' : 'pointerup', pointerId, 100 + dx, 100 + dy, button));
}
function chief() { return document.querySelector('.aw-chief') as HTMLElement; }
function storedPrefs() { return JSON.parse(localStorage.getItem('chief-of-staff:7:1') || '{}') as { x?: number; y?: number; docked?: boolean }; }
beforeEach(() => {
  localStorage.clear(); vi.resetAllMocks(); useAuthStore.getState().clear();
  resetWorkspaceGuideBridge();
  Object.defineProperty(window, 'innerWidth', { configurable: true, value: 1280 });
  Object.defineProperty(window, 'innerHeight', { configurable: true, value: 800 });
  Element.prototype.setPointerCapture = vi.fn();
  Element.prototype.releasePointerCapture = vi.fn();
  Element.prototype.scrollIntoView = vi.fn();
  TestResizeObserver.instances = [];
  Object.defineProperty(globalThis, 'ResizeObserver', { configurable: true, writable: true, value: TestResizeObserver });
  stubMatchMedia(false);
  useAuthStore.getState().setUser({ id: 7, username: 'owner', nickname: 'Owner', email: '' });
  useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'Project', description: '' }, 'ADMIN');
  vi.mocked(api.setupStatus).mockResolvedValue(ready);
  vi.mocked(api.createConversation).mockResolvedValue(conversation);
  vi.mocked(api.getConversation).mockResolvedValue(conversation);
  vi.mocked(api.listConversations).mockResolvedValue([]);
  vi.mocked(api.getPlans).mockResolvedValue([]);
  vi.mocked(api.getTurnEventPage).mockResolvedValue({ rows: [], nextAfterId: 0, hasMore: false });
  vi.mocked(api.sendMessage).mockResolvedValue(undefined);
});
afterEach(() => { cleanup(); clients.forEach(client => client.clear()); clients = []; vi.useRealTimers(); });
describe('Chief of Staff', () => {
  it.each([
    ['zh-CN', '初始化工作空间', '打开 Chief of Staff'],
    ['en-US', 'Initialize workspace', 'Open Chief of Staff'],
  ])('sends the shared initialization prompt once in %s', async (locale, title, openLabel) => {
    await i18n.changeLanguage(locale);
    const user = userEvent.setup(); mount();
    await user.click(screen.getByRole('button', { name: openLabel }));
    const shortcut = await screen.findByRole('button', { name: title });
    act(() => { fireEvent.click(shortcut); fireEvent.click(shortcut); });
    await waitFor(() => expect(api.sendMessage).toHaveBeenCalledTimes(1));
    expect(api.createConversation).toHaveBeenCalledTimes(1);
    expect(api.sendMessage).toHaveBeenCalledWith(100, i18n.t('prompt.initial', { ns: 'workspaceGuide' }), expect.any(String), '/workitems/128');
    await i18n.changeLanguage('zh-CN');
  });

  it('sends the optimization scenario once on rapid clicks without composing a message', async () => {
    const user = userEvent.setup(); mount();
    await user.click(screen.getByRole('button', { name: '打开 Chief of Staff' }));
    const shortcut = await screen.findByRole('button', { name: '优化小队效率与成本' });
    act(() => { fireEvent.click(shortcut); fireEvent.click(shortcut); });
    await waitFor(() => expect(api.sendMessage).toHaveBeenCalledTimes(1));
    expect(api.createConversation).toHaveBeenCalledTimes(1);
    expect(api.sendMessage).toHaveBeenCalledWith(100, expect.stringContaining('最近 3 天'), expect.any(String), '/workitems/128');
  });

  it('preserves an existing draft instead of sending a scenario over it', async () => {
    const user = userEvent.setup(); mount();
    await user.click(screen.getByRole('button', { name: '打开 Chief of Staff' }));
    await user.type(screen.getByRole('textbox', { name: '输入消息' }), 'my draft');
    const shortcut = await screen.findByRole('button', { name: '优化小队效率与成本' });
    expect(shortcut).toBeDisabled();
    expect(screen.getByRole('button', { name: '初始化工作空间' })).toBeDisabled();
    expect(api.sendMessage).not.toHaveBeenCalled();
    expect(screen.getByRole('textbox', { name: '输入消息' })).toHaveValue('my draft');
  });

  it('retains a failed scenario as a draft and retries with the same message id', async () => {
    vi.mocked(api.sendMessage).mockRejectedValueOnce(new Error('send failed'));
    const user = userEvent.setup(); mount();
    await user.click(screen.getByRole('button', { name: '打开 Chief of Staff' }));
    await user.click(await screen.findByRole('button', { name: '优化小队效率与成本' }));
    await waitFor(() => expect(api.sendMessage).toHaveBeenCalledTimes(1));
    const first = vi.mocked(api.sendMessage).mock.calls[0];
    await waitFor(() => expect(screen.getByRole('textbox', { name: '输入消息' })).toHaveValue(first[1]));
    await waitFor(() => expect(screen.getByRole('button', { name: '发送' })).toBeEnabled());
    await user.click(screen.getByRole('button', { name: '发送' }));
    await waitFor(() => expect(api.sendMessage).toHaveBeenCalledTimes(2));
    expect(vi.mocked(api.sendMessage).mock.calls[1]).toEqual(first);
    expect(api.createConversation).toHaveBeenCalledTimes(1);
  });

  it('does not send the scenario for a read-only workspace member', async () => {
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'Project', description: '' }, 'READ_ONLY');
    const user = userEvent.setup(); mount();
    await user.click(screen.getByRole('button', { name: '打开 Chief of Staff' }));
    expect(await screen.findByRole('button', { name: '优化小队效率与成本' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '初始化工作空间' })).toBeDisabled();
    expect(api.sendMessage).not.toHaveBeenCalled();
  });

  async function mountInitialization(status = 'COMPLETED', turnStatus?: string) {
    const actual = await vi.importActual<typeof api>('./api');
    vi.mocked(api.initializationContinuation).mockImplementation(actual.initializationContinuation);
    const completed: api.ActionPlan = { id: 'workspace-plan', turnId: 200, actionsJson: '[]', payloadHash: 'hash', status: 'COMPLETED', resultJson: JSON.stringify([{ tool: 'autowonder.initialize_workspace', result: { runId: 42, status: 'SUCCEEDED' } }]), expiresAt: '2099-01-01' };
    const content = actual.initializationContinuation(completed)!;
    localStorage.setItem('chief-of-staff:7:1', JSON.stringify({ conversationId: 100 }));
    vi.mocked(api.getPlans).mockResolvedValue([{ ...completed, status }]);
    vi.mocked(api.resolvePlan).mockImplementation(async () => {
      vi.mocked(api.getPlans).mockResolvedValue([completed]);
      return completed;
    });
    if (turnStatus) vi.mocked(api.getConversation).mockResolvedValue({ ...conversation, turns: [{ id: 201, direction: 'IN', content, status: turnStatus, error: null, gmtCreate: '' }] });
    const user = userEvent.setup(); mount();
    await user.click(screen.getByRole('button', { name: '打开 Chief of Staff' }));
    return { user, content };
  }
  it('automatically sends one workspace continuation after confirmation', async () => {
    const { user, content } = await mountInitialization('PENDING');
    await user.click(await screen.findByRole('button', { name: '确认执行' }));
    await waitFor(() => expect(api.continueInitialization).toHaveBeenCalledWith(100, 'workspace-plan', content));
    expect(api.continueInitialization).toHaveBeenCalledTimes(1);
    expect(api.resolvePlan).toHaveBeenCalledWith(100, 'workspace-plan', 'hash', true);
    expect(api.resolvePlan).toHaveBeenCalledTimes(1);
  });
  it('offers manual continuation for a completed historical initialization', async () => {
    const { user, content } = await mountInitialization();
    await user.click(await screen.findByRole('button', { name: '继续初始化后续步骤' }));
    await waitFor(() => expect(api.continueInitialization).toHaveBeenCalledWith(100, 'workspace-plan', content, undefined));
    expect(api.continueInitialization).toHaveBeenCalledTimes(1);
    expect(api.resolvePlan).not.toHaveBeenCalled();
  });
  it('does not resend a completed initialization continuation', async () => {
    await mountInitialization('COMPLETED', 'SUCCESS');
    await screen.findByText('已完成');
    expect(screen.queryByRole('button', { name: '继续初始化后续步骤' })).not.toBeInTheDocument();
    expect(api.continueInitialization).not.toHaveBeenCalled();
  });
  it('retries a failed continuation with its failed turn identity', async () => {
    const { user, content } = await mountInitialization('COMPLETED', 'FAILED');
    await user.click(await screen.findByRole('button', { name: '继续初始化后续步骤' }));
    await waitFor(() => expect(api.continueInitialization).toHaveBeenCalledWith(100, 'workspace-plan', content, 201));
    expect(api.continueInitialization).toHaveBeenCalledTimes(1);
    expect(api.resolvePlan).not.toHaveBeenCalled();
  });
  it('changes the active overlay layer without replacing the assistant or losing its draft and preferences', async () => {
    localStorage.setItem('chief-of-staff:7:1', JSON.stringify({ conversationId: 100, x: 70, y: 80 }));
    const user = userEvent.setup(); mount();
    const root = chief();
    expect(root).not.toHaveClass('aw-chief-open');
    await user.click(screen.getByRole('button', { name: '打开 Chief of Staff' }));
    expect(root).toHaveClass('aw-chief-open');
    await user.type(await screen.findByRole('textbox', { name: '输入消息' }), 'Retained draft');
    const prefs = localStorage.getItem('chief-of-staff:7:1');
    await user.click(screen.getByRole('button', { name: '收起对话' }));
    expect(chief()).toBe(root);
    expect(root).not.toHaveClass('aw-chief-open');
    await user.click(screen.getByRole('button', { name: '打开 Chief of Staff' }));
    expect(chief()).toBe(root);
    expect(await screen.findByRole('textbox', { name: '输入消息' })).toHaveValue('Retained draft');
    expect(localStorage.getItem('chief-of-staff:7:1')).toBe(prefs);
    expect(api.createConversation).not.toHaveBeenCalled();
  });

  it('sends with Enter while Shift+Enter keeps a newline', async () => {
    const user = userEvent.setup(); mount();
    await user.click(screen.getByRole('button', { name: '打开 Chief of Staff' }));
    const input = await screen.findByRole('textbox', { name: '输入消息' });
    await screen.findByText('有什么可以帮你？');
    await user.type(input, 'First{Shift>}{Enter}{/Shift}Second');
    expect(api.sendMessage).not.toHaveBeenCalled();
    fireEvent.keyDown(input, { key: 'Enter', keyCode: 229, isComposing: true });
    expect(api.sendMessage).not.toHaveBeenCalled();
    await user.keyboard('{Enter}');
    await waitFor(() => expect(api.sendMessage).toHaveBeenCalledWith(100, 'First\nSecond', expect.any(String), '/workitems/128'));
  });
  it('sends a message with a generated id when randomUUID is unavailable', async () => {
    const realCrypto = globalThis.crypto;
    Object.defineProperty(globalThis, 'crypto', {
      configurable: true,
      value: { getRandomValues: realCrypto.getRandomValues.bind(realCrypto) },
    });
    try {
      const user = userEvent.setup(); mount();
      await user.click(screen.getByRole('button', { name: '打开 Chief of Staff' }));
      const input = await screen.findByRole('textbox', { name: '输入消息' });
      await screen.findByText('有什么可以帮你？');
      await user.type(input, 'HTTP context');
      await user.keyboard('{Enter}');
      await waitFor(() => expect(api.sendMessage).toHaveBeenCalledWith(
        100,
        'HTTP context',
        expect.stringMatching(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/),
        '/workitems/128',
      ));
    } finally {
      Object.defineProperty(globalThis, 'crypto', { configurable: true, value: realCrypto });
    }
  });
  it('follows streaming updates unless the user scrolls up, without duplicate spinners', async () => {
    localStorage.setItem('chief-of-staff:7:1', JSON.stringify({ conversationId: 100, x: 24, y: 24, width: 440, height: 620 }));
    const active = { ...conversation, processingStatus: 'PROCESSING', processingTurnId: 200, turns: [{ id: 200, direction: 'IN', content: 'Question', status: 'PROCESSING', gmtCreate: '' }] } as api.Conversation;
    vi.mocked(api.getConversation).mockResolvedValue(active);
    const user = userEvent.setup(); mount();
    await user.click(screen.getByRole('button', { name: '打开 Chief of Staff' }));
    await screen.findByText('Question');
    const body = screen.getByRole('dialog').querySelector('.aw-chief-body') as HTMLElement;
    Object.defineProperty(body, 'scrollHeight', { configurable: true, value: 1000 });
    Object.defineProperty(body, 'clientHeight', { configurable: true, value: 200 });
    const query = clients[0].getQueryCache().findAll().find(q => q.state.data === active)!;
    clients[0].setQueryData(query.queryKey, { ...active, title: 'Updated' });
    await waitFor(() => expect(body.scrollTop).toBe(1000));
    body.scrollTop = 100; fireEvent.scroll(body);
    clients[0].setQueryData(query.queryKey, { ...active, title: 'More updates' });
    await screen.findByText('More updates');
    expect(body.scrollTop).toBe(100);
    expect(body.querySelectorAll('.ant-spin')).toHaveLength(0);
    expect(screen.getByRole('textbox', { name: '输入消息' })).not.toBeDisabled();
  });
  it.each(['puck', 'header'])('reopens cached chat at the latest message via %s after reading older messages', async entry => {
    const height = vi.spyOn(HTMLElement.prototype, 'scrollHeight', 'get').mockImplementation(function (this: HTMLElement) {
      return this.classList.contains('aw-chief-body') ? 1600 : 0;
    });
    const viewport = vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get').mockImplementation(function (this: HTMLElement) {
      return this.classList.contains('aw-chief-body') ? 400 : 0;
    });
    try {
      localStorage.setItem('chief-of-staff:7:1', JSON.stringify({ conversationId: 100 }));
      vi.mocked(api.getConversation).mockResolvedValue({ ...conversation, turns: [
        { id: 200, direction: 'OUT', content: 'Latest reply', status: 'COMPLETED', gmtCreate: '' },
      ] } as api.Conversation);
      const user = userEvent.setup(); mount();
      await user.click(screen.getByRole('button', { name: '打开 Chief of Staff' }));
      await screen.findByText('Latest reply');
      const body = screen.getByRole('dialog').querySelector('.aw-chief-body') as HTMLElement;
      await waitFor(() => expect(body.scrollTop).toBe(1600));
      body.scrollTop = 100;
      fireEvent.scroll(body);
      await user.click(screen.getByRole('button', { name: '收起对话' }));
      // 重新打开先显示缓存，定位不能依赖下一次网络请求完成。
      vi.mocked(api.getConversation).mockReturnValue(new Promise(() => {}));
      if (entry === 'header') act(() => openChiefPanel({ expanded: false }));
      else await user.click(screen.getByRole('button', { name: '打开 Chief of Staff' }));
      const reopened = screen.getByRole('dialog').querySelector('.aw-chief-body') as HTMLElement;
      expect(reopened).not.toBe(body);
      expect(screen.getByText('Latest reply')).toBeInTheDocument();
      await waitFor(() => expect(reopened.scrollTop).toBe(1600));
    } finally {
      height.mockRestore();
      viewport.mockRestore();
    }
  });
  it.each(['zh-CN', 'en-US'])('hides the speaker label on user messages while keeping it on assistant messages (%s)', async locale => {
    await i18n.changeLanguage(locale);
    localStorage.setItem('chief-of-staff:7:1', JSON.stringify({ conversationId: 100 }));
    vi.mocked(api.getConversation).mockResolvedValue({ ...conversation, turns: [
      { id: 200, direction: 'IN', content: 'User question', status: 'COMPLETED', error: null, gmtCreate: '' },
      { id: 201, direction: 'OUT', content: 'Assistant answer', status: 'COMPLETED', gmtCreate: '' },
    ] } as api.Conversation);
    const user = userEvent.setup(); mount();
    await user.click(screen.getByRole('button', { name: i18n.t('chiefOfStaff:open') }));
    await screen.findByText('User question');
    const sections = Array.from(document.querySelectorAll('.aw-chief-message')) as HTMLElement[];
    expect(sections).toHaveLength(2);
    const [userSection, assistantSection] = sections;
    expect(within(userSection).queryByText('你')).not.toBeInTheDocument();
    expect(within(userSection).queryByText('You')).not.toBeInTheDocument();
    expect(userSection.querySelector('.aw-chief-message-heading .aw-chief-secondary')).toBeNull();
    expect(within(assistantSection).getByText('Chief of Staff')).toBeInTheDocument();
    const copyName = i18n.t('chiefOfStaff:copyMessage');
    const userCopies = within(userSection).getAllByRole('button', { name: copyName });
    expect(userCopies).toHaveLength(2);
    const heading = userSection.querySelector('.aw-chief-message-heading') as HTMLElement;
    expect(heading.contains(userCopies[0])).toBe(true);
    expect(heading.compareDocumentPosition(within(userSection).getByText('User question')) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(within(assistantSection).getAllByRole('button', { name: copyName })).toHaveLength(2);
    await i18n.changeLanguage('zh-CN');
  });
  it.each(['zh-CN', 'en-US'])('offers copy before a rich Markdown template and preserves its full content (%s)', async locale => {
    await i18n.changeLanguage(locale);
    const template = '请复制下面的模板，将示例替换为实际项目信息。\n\n#### 1. 代码仓库\n\n- `console`：`git@example.test:team/console.git`；默认分支 `main`。\n\n#### 2. 构建与测试\n\n构建：`npm run build`\n\n#### 3. 数据库脚本\n\n不适用。\n\n#### 4. 数据库配置\n\n通过环境变量读取。\n\n#### 5. 部署\n\n部署目标分支：`dev`。';
    localStorage.setItem('chief-of-staff:7:1', JSON.stringify({ conversationId: 100, x: 24, y: 24, width: 440, height: 620 }));
    vi.mocked(api.getConversation).mockResolvedValue({ ...conversation, turns: [{ id: 200, direction: 'OUT', content: template, status: 'COMPLETED', gmtCreate: '' }] } as api.Conversation);
    const user = userEvent.setup(); mount();
    await user.click(screen.getByRole('button', { name: i18n.t('chiefOfStaff:open') }));
    const copyButtons = await screen.findAllByRole('button', { name: i18n.t('chiefOfStaff:copyMessage') });
    const firstSection = await screen.findByRole('heading', { name: '1. 代码仓库' });
    expect(copyButtons).toHaveLength(2);
    expect(copyButtons[0].compareDocumentPosition(firstSection) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    const lastSection = screen.getByRole('heading', { name: '5. 部署' });
    expect(lastSection.compareDocumentPosition(copyButtons[1]) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    // jsdom FileReader cannot read the Node Blob used by HTTP fixtures.
    // Assert the browser clipboard boundary instead of user-event's readback shim.
    // eslint-disable-next-line no-restricted-properties -- Verify the fallback-capable clipboard helper.
    const writeText = vi.spyOn(navigator.clipboard, 'writeText').mockResolvedValue(undefined);
    for (const copyButton of copyButtons) {
      writeText.mockClear();
      await user.click(copyButton);
      expect(writeText).toHaveBeenCalledWith(template);
    }
    expect(screen.queryByRole('button', { name: '回复这条消息' })).not.toBeInTheDocument();
    await i18n.changeLanguage('zh-CN');
  });
  it('uploads a screenshot and sends its server image reference even without text', async () => {
    const image = { id: 'image-1', name: 'screenshot.png', url: 'https://example.test/image.png', contentType: 'image/png', size: 8 };
    vi.mocked(api.uploadImage).mockResolvedValue(image);
    const user = userEvent.setup(); mount();
    await user.click(screen.getByRole('button', { name: '打开 Chief of Staff' }));
    await screen.findByText('有什么可以帮你？');
    fireEvent.paste(screen.getByRole('textbox', { name: '输入消息' }), { clipboardData: { items: [], files: [new File(['png'], 'screenshot.png', { type: 'image/png' })] } });
    expect(await screen.findByAltText('screenshot.png')).toBeInTheDocument();
    expect(api.uploadImage).toHaveBeenCalledWith(100, expect.any(File));
    await user.click(screen.getByRole('button', { name: '发送' }));
    await waitFor(() => expect(api.sendMessage).toHaveBeenCalledWith(100, '请查看并分析附上的图片。', expect.any(String), '/workitems/128', ['image-1']));
  });
  it('keeps setup errors visible and offers the draft configuration entry', async () => {
    vi.mocked(api.setupStatus).mockResolvedValue({ ...ready, ready: false, skillsConfigured: false });
    vi.mocked(api.completeSetup).mockRejectedValue(new Error('Draft requires review'));
    const user = userEvent.setup(); mount();
    await user.click(screen.getByRole('button', { name: '打开 Chief of Staff' }));
    await user.click(await screen.findByRole('button', { name: '一键补全配置' }));
    expect(await within(screen.getByRole('dialog')).findByRole('alert')).toHaveTextContent('Draft requires review');
    expect(screen.getByRole('button', { name: '查看数字人配置与草稿' })).toBeInTheDocument();
  });
  it('can always reopen a collapsed assistant and saves messages with the current route', async () => {
    const user = userEvent.setup(); mount();
    await user.click(screen.getByRole('button', { name: '打开 Chief of Staff' }));
    await screen.findByText('有什么可以帮你？');
    await user.type(screen.getByRole('textbox', { name: '输入消息' }), 'Inspect this workitem');
    await user.click(screen.getByRole('button', { name: 'Next workitem' }));
    await user.click(screen.getByRole('button', { name: '发送' }));
    await waitFor(() => expect(api.sendMessage).toHaveBeenCalledWith(100, 'Inspect this workitem', expect.any(String), '/workitems/129'));
    await user.click(screen.getByRole('button', { name: '收起对话' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '打开 Chief of Staff' }));
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });
  it('renders a compact icon-only launcher without the title text', () => {
    mount();
    const launcher = screen.getByRole('button', { name: '打开 Chief of Staff' });
    expect(launcher).toHaveClass('aw-chief-puck');
    expect(launcher.textContent).toBe('');
    expect(launcher.querySelector('.aw-chief-icon')).not.toBeNull();
    expect(launcher.querySelector('.anticon')).toBeNull();
    expect(screen.queryByText('Chief of Staff')).not.toBeInTheDocument();
  });
  it('tucks the launcher against the edge and expands it again on demand', async () => {
    const user = userEvent.setup(); mount();
    const row = document.querySelector('.aw-chief-launch') as HTMLElement;
    expect(row).not.toHaveClass('aw-chief-tucked');
    expect(document.querySelector('.aw-chief-grip')).not.toBeNull();
    await user.click(screen.getByRole('button', { name: '贴边收起' }));
    expect(row).toHaveClass('aw-chief-tucked');
    expect(document.querySelector('.aw-chief')).toHaveStyle({ right: '0px' });
    expect(screen.getByRole('button', { name: '展开' })).toHaveAttribute('aria-expanded', 'false');
    expect(JSON.parse(localStorage.getItem('chief-of-staff:7:1') || '{}')).toMatchObject({ docked: true, x: 0 });
    await user.click(screen.getByRole('button', { name: '展开' }));
    expect(row).not.toHaveClass('aw-chief-tucked');
    expect(screen.getByRole('button', { name: '贴边收起' })).toHaveAttribute('aria-expanded', 'true');
    expect(document.querySelector('.aw-chief-grip')).not.toBeNull();
    expect(JSON.parse(localStorage.getItem('chief-of-staff:7:1') || '{}')).toMatchObject({ docked: false });
  });
  it('releases the dock button focus when tucking and keeps it when expanding', async () => {
    const user = userEvent.setup(); mount();
    const dock = screen.getByRole('button', { name: '贴边收起' });
    dock.focus();
    expect(document.activeElement).toBe(dock);
    await user.click(dock);
    expect(document.activeElement).not.toBe(dock);
    const undock = screen.getByRole('button', { name: '展开' });
    undock.focus();
    expect(document.activeElement).toBe(undock);
    await user.click(undock);
    expect(document.activeElement).toBe(undock);
  });
  it('restores the tucked launcher without a grip and keeps it visible while the conversation is open', async () => {
    localStorage.setItem('chief-of-staff:7:1', JSON.stringify({ conversationId: 100, x: 0, y: 24, width: 440, height: 620, docked: true }));
    const user = userEvent.setup(); mount();
    const row = document.querySelector('.aw-chief-launch') as HTMLElement;
    expect(row).toHaveClass('aw-chief-tucked');
    expect(document.querySelector('.aw-chief-grip')).toBeNull();
    await user.click(screen.getByRole('button', { name: '打开 Chief of Staff' }));
    expect(await screen.findByRole('dialog')).toBeInTheDocument();
    expect(row).not.toHaveClass('aw-chief-tucked');
  });
  it('allows disabling page context and starting a separate conversation', async () => {
    const user = userEvent.setup(); mount();
    await user.click(screen.getByRole('button', { name: '打开 Chief of Staff' }));
    await screen.findByText('有什么可以帮你？');
    await user.click(screen.getByRole('switch', { name: '关联当前页面' }));
    await user.type(screen.getByRole('textbox', { name: '输入消息' }), 'Hello');
    await user.click(screen.getByRole('button', { name: '发送' }));
    await waitFor(() => expect(api.sendMessage).toHaveBeenCalledWith(100, 'Hello', expect.any(String), null));
    await user.click(screen.getByRole('button', { name: '新会话' }));
    expect(screen.queryByText('My conversation')).not.toBeInTheDocument();
  });
  it('shows historical tool input, output and status, with private confirmation controls', async () => {
    localStorage.setItem('chief-of-staff:7:1', JSON.stringify({ conversationId: 100 }));
    vi.mocked(api.getConversation).mockResolvedValue({ ...conversation, turns: [{ id: 200, direction: 'IN', content: 'Inspect', status: 'SUCCESS', error: null, gmtCreate: '' }] });
    vi.mocked(api.getTurnEventPage).mockResolvedValue({ rows: [{ id: 1, conversationId: 100, turnId: 200, dispatchAttempt: 1, eventSeq: 1, chunkIndex: 0, chunkCount: 1, eventType: 'tool_result', payloadFragment: JSON.stringify({ tool: 'autowonder.get_workitem', callId: 'call-1', status: 'completed', output: 'Workitem details' }), gmtCreate: '' }], nextAfterId: 1, hasMore: false });
    vi.mocked(api.getPlans).mockResolvedValue([{ id: 'plan-1', turnId: 200, actionsJson: '[]', payloadHash: 'hash', status: 'PENDING', resultJson: null, expiresAt: '2099-01-01T00:00:00Z' }]);
    const user = userEvent.setup(); mount(); await user.click(screen.getByRole('button', { name: '打开 Chief of Staff' }));
    await screen.findByText('autowonder.get_workitem');
    expect(screen.getByText('Workitem details')).toBeInTheDocument();
    expect(screen.queryByText(/分享/)).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '确认执行' }));
    await waitFor(() => expect(api.resolvePlan).toHaveBeenCalledWith(100, 'plan-1', 'hash', true));
  });
  it('gates setup to administrators and never starts an AI call during bootstrap', async () => {
    vi.mocked(api.setupStatus).mockResolvedValue({ ...ready, executorConfigured: false, launchConfigured: false, ready: false, executorId: null });
    useAuthStore.getState().setAccessLevel('READ_ONLY');
    const user = userEvent.setup(); mount(); await user.click(screen.getByRole('button', { name: '打开 Chief of Staff' }));
    await screen.findByText('请联系项目管理员完成配置或启动执行器。');
    expect(screen.queryByRole('button', { name: '一键补全配置' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: '发送' })).toBeDisabled();
    expect(api.sendMessage).not.toHaveBeenCalled();
  });
  it('does not restore another account conversation preference', async () => {
    localStorage.setItem('chief-of-staff:99:1', JSON.stringify({ conversationId: 999 }));
    const user = userEvent.setup(); mount(); await user.click(screen.getByRole('button', { name: '打开 Chief of Staff' }));
    await screen.findByText('有什么可以帮你？');
    expect(api.getConversation).not.toHaveBeenCalled();
  });
});
describe('Chief of Staff dragging', () => {
  it('drags the collapsed puck in both axes, persists, and does not open the panel', () => {
    mount();
    const puck = screen.getByRole('button', { name: '打开 Chief of Staff' });
    drag(puck, -60, -120);
    expect(chief()).toHaveStyle({ right: '80px', bottom: '140px' });
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(storedPrefs()).toMatchObject({ x: 80, y: 140 });
  });
  it('treats a sub-threshold move as a click', async () => {
    mount();
    const puck = screen.getByRole('button', { name: '打开 Chief of Staff' });
    drag(puck, 2, 2);
    fireEvent.click(puck);
    expect(await screen.findByRole('dialog')).toBeInTheDocument();
    expect(chief()).toHaveStyle({ right: '20px', bottom: '20px' });
  });
  it('suppresses the click after a drag and accepts the next click', async () => {
    mount();
    const puck = screen.getByRole('button', { name: '打开 Chief of Staff' });
    drag(puck, 0, -80);
    fireEvent.click(puck);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    fireEvent.click(puck);
    expect(await screen.findByRole('dialog')).toBeInTheDocument();
  });
  it('ignores secondary buttons, extra pointers and unmatched pointer events', () => {
    mount();
    const puck = screen.getByRole('button', { name: '打开 Chief of Staff' });
    drag(puck, 0, -100, { button: 2 });
    expect(chief()).toHaveStyle({ bottom: '20px' });
    fireEvent(puck, pointerEvent('pointerdown', 1, 100, 100));
    fireEvent(puck, pointerEvent('pointerdown', 2, 100, 100));
    fireEvent(puck, pointerEvent('pointermove', 2, 100, 0));
    fireEvent(puck, pointerEvent('pointerup', 2, 100, 0));
    expect(chief()).toHaveStyle({ bottom: '20px' });
    fireEvent(puck, pointerEvent('pointermove', 1, 100, 0));
    expect(chief()).toHaveStyle({ bottom: '120px' });
    fireEvent(puck, pointerEvent('pointerup', 1, 100, 0));
    expect(vi.mocked(Element.prototype.setPointerCapture)).toHaveBeenCalledTimes(1);
  });
  it('locks the docked puck to vertical movement and keeps the launcher tucked', () => {
    localStorage.setItem('chief-of-staff:7:1', JSON.stringify({ x: 0, y: 120, docked: true }));
    mount();
    const puck = screen.getByRole('button', { name: '打开 Chief of Staff' });
    drag(puck, 80, -40);
    expect(chief()).toHaveStyle({ right: '0px', bottom: '160px' });
    expect(document.querySelector('.aw-chief-launch')).toHaveClass('aw-chief-tucked');
    expect(storedPrefs()).toMatchObject({ x: 0, y: 160, docked: true });
  });
  it('drag the exposed edge button vertically without undocking, then still toggles it on a later click', async () => {
    localStorage.setItem('chief-of-staff:7:1', JSON.stringify({ x: 0, y: 120, docked: true }));
    mount();
    const dock = screen.getByRole('button', { name: '展开' });
    drag(dock, 0, -60);
    expect(chief()).toHaveStyle({ right: '0px', bottom: '180px' });
    fireEvent.click(dock);
    expect(screen.getByRole('button', { name: '展开' })).toBeInTheDocument();
    expect(storedPrefs()).toMatchObject({ docked: true, x: 0 });
    await new Promise(resolve => setTimeout(resolve, 0));
    fireEvent.click(dock);
    expect(screen.getByRole('button', { name: '贴边收起' })).toBeInTheDocument();
    expect(storedPrefs()).toMatchObject({ docked: false });
  });
  it('moves the expanded panel with the puck and keeps the header draggable', async () => {
    const user = userEvent.setup(); mount();
    await user.click(screen.getByRole('button', { name: '打开 Chief of Staff' }));
    await screen.findByRole('dialog');
    const puck = screen.getByRole('button', { name: '打开 Chief of Staff' });
    drag(puck, 0, 60);
    expect(chief()).toHaveStyle({ right: '20px', bottom: '0px' });
    drag(document.querySelector('.aw-chief-header') as HTMLElement, 0, -100);
    expect(chief()).toHaveStyle({ right: '20px', bottom: '100px' });
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });
  it('keeps the docked launcher at the right edge when the header is dragged', async () => {
    localStorage.setItem('chief-of-staff:7:1', JSON.stringify({ conversationId: 100, x: 0, y: 80, width: 440, height: 620, docked: true }));
    const user = userEvent.setup(); mount();
    await user.click(screen.getByRole('button', { name: '打开 Chief of Staff' }));
    await screen.findByRole('dialog');
    drag(document.querySelector('.aw-chief-header') as HTMLElement, -200, -40);
    expect(chief()).toHaveStyle({ right: '0px', bottom: '116px' });
    expect(storedPrefs()).toMatchObject({ x: 0, docked: true });
    await user.click(screen.getByRole('button', { name: '收起对话' }));
    expect(document.querySelector('.aw-chief-launch')).toHaveClass('aw-chief-tucked');
  });
  it('does not start a drag from header buttons', async () => {
    const user = userEvent.setup(); mount();
    await user.click(screen.getByRole('button', { name: '打开 Chief of Staff' }));
    await screen.findByRole('dialog');
    drag(screen.getByRole('button', { name: '历史会话' }), -120, -80);
    expect(chief()).toHaveStyle({ right: '20px', bottom: '20px' });
  });
  it('drags the grip in both axes', () => {
    mount();
    drag(document.querySelector('.aw-chief-grip') as HTMLElement, -60, -40);
    expect(chief()).toHaveStyle({ right: '80px', bottom: '60px' });
  });
  it('reclamps the stored position on mount and after a viewport resize', () => {
    localStorage.setItem('chief-of-staff:7:1', JSON.stringify({ x: 2000, y: 2000 }));
    mount();
    expect(chief()).toHaveStyle({ right: '1100px', bottom: '720px' });
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 800 });
    Object.defineProperty(window, 'innerHeight', { configurable: true, value: 600 });
    fireEvent(window, new Event('resize'));
    expect(chief()).toHaveStyle({ right: '620px', bottom: '520px' });
    expect(storedPrefs()).toMatchObject({ x: 620, y: 520 });
  });
  it('reclamps and re-docks the launcher when the viewport shrinks', () => {
    localStorage.setItem('chief-of-staff:7:1', JSON.stringify({ x: 300, y: 2000, docked: true }));
    mount();
    expect(chief()).toHaveStyle({ right: '0px', bottom: '720px' });
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 800 });
    Object.defineProperty(window, 'innerHeight', { configurable: true, value: 600 });
    fireEvent(window, new Event('resize'));
    expect(chief()).toHaveStyle({ right: '0px', bottom: '520px' });
  });
  it('stops on pointercancel and accepts a click once suppression clears', async () => {
    mount();
    const puck = screen.getByRole('button', { name: '打开 Chief of Staff' });
    drag(puck, 0, -80, { cancel: true });
    fireEvent(puck, pointerEvent('pointermove', 1, 0, 300));
    expect(chief()).toHaveStyle({ bottom: '100px' });
    await new Promise(resolve => setTimeout(resolve, 0));
    fireEvent.click(puck);
    expect(await screen.findByRole('dialog')).toBeInTheDocument();
  });
  it('opens and closes the panel from the keyboard', async () => {
    const user = userEvent.setup(); mount();
    const puck = screen.getByRole('button', { name: '打开 Chief of Staff' });
    puck.focus();
    await user.keyboard('{Enter}');
    expect(await screen.findByRole('dialog')).toBeInTheDocument();
    await user.keyboard('{Enter}');
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  });
});
describe('Chief of Staff wide view', () => {
  async function openPanel() {
    const user = userEvent.setup(); mount();
    await user.click(screen.getByRole('button', { name: '打开 Chief of Staff' }));
    return { user, panel: await screen.findByRole('dialog') };
  }
  function fullRect(rect: { width: number; height: number; right?: number; bottom?: number }) {
    return { top: 0, left: 0, x: 0, y: 0, toJSON: () => ({}), right: 0, bottom: 0, ...rect } as DOMRect;
  }
  function mockPanelRect(panel: Element, rect: { width: number; height: number; right?: number; bottom?: number }) {
    return vi.spyOn(panel, 'getBoundingClientRect').mockReturnValue(fullRect(rect));
  }
  function emitResize(panel: Element) {
    const observer = TestResizeObserver.instances.at(-1)!;
    act(() => observer.callback([{ target: panel } as unknown as ResizeObserverEntry], observer as unknown as ResizeObserver));
  }
  it('expands to the wide overlay and exits from the header button', async () => {
    const { user, panel } = await openPanel();
    expect(panel).toHaveStyle({ width: '440px', height: '620px' });
    await user.click(wideButton('宽屏模式'));
    expect(panel).toHaveClass('aw-chief-expanded');
    expect(panel.style.width).toBe('');
    expect(panel.style.height).toBe('');
    const exit = wideButton('退出宽屏');
    expect(exit).toHaveAttribute('aria-pressed', 'true');
    expect(screen.queryByRole('button', { name: '打开 Chief of Staff' })).not.toBeInTheDocument();
    expect(JSON.parse(localStorage.getItem('chief-of-staff:7:1') || '{}')).toMatchObject({ expanded: true });
    await user.click(exit);
    expect(panel).not.toHaveClass('aw-chief-expanded');
    expect(panel).toHaveStyle({ width: '440px', height: '620px' });
    expect(screen.getByRole('button', { name: '打开 Chief of Staff' })).toBeInTheDocument();
    expect(JSON.parse(localStorage.getItem('chief-of-staff:7:1') || '{}')).toMatchObject({ expanded: false });
  });
  it('exits wide view on Escape while keeping the panel open and the draft intact', async () => {
    const { user, panel } = await openPanel();
    await user.type(screen.getByRole('textbox', { name: '输入消息' }), 'keep me');
    await user.click(wideButton('宽屏模式'));
    await user.keyboard('{Escape}');
    expect(panel).not.toHaveClass('aw-chief-expanded');
    expect(screen.getByRole('textbox', { name: '输入消息' })).toHaveValue('keep me');
  });
  it('ignores Escape during composition or when another handler consumed it', async () => {
    const { user, panel } = await openPanel();
    await user.click(wideButton('宽屏模式'));
    fireEvent.keyDown(panel, { key: 'Escape', keyCode: 229, isComposing: true });
    expect(panel).toHaveClass('aw-chief-expanded');
    const consumed = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true });
    consumed.preventDefault();
    fireEvent(panel, consumed);
    expect(panel).toHaveClass('aw-chief-expanded');
    await user.keyboard('{Escape}');
    expect(panel).not.toHaveClass('aw-chief-expanded');
  });
  it('restores the wide state after collapsing and reopening the panel', async () => {
    localStorage.setItem('chief-of-staff:7:1', JSON.stringify({ conversationId: 100, expanded: true }));
    const { user, panel } = await openPanel();
    expect(panel).toHaveClass('aw-chief-expanded');
    expect(screen.queryByRole('button', { name: '打开 Chief of Staff' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '收起对话' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '打开 Chief of Staff' }));
    expect(screen.getByRole('dialog')).toHaveClass('aw-chief-expanded');
  });
  it('does not drag the panel from its header in wide view', async () => {
    const { user } = await openPanel();
    await user.click(wideButton('宽屏模式'));
    drag(document.querySelector('.aw-chief-header') as HTMLElement, -120, -80);
    expect(chief()).toHaveStyle({ right: '20px', bottom: '20px' });
  });
  it('hides the wide view switch on narrow viewports', async () => {
    stubMatchMedia(true);
    const { panel } = await openPanel();
    expect(panel).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '宽屏模式' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: '历史会话' })).toBeInTheDocument();
  });
  it('clamps stored sizes to ninety percent of the viewport', async () => {
    localStorage.setItem('chief-of-staff:7:1', JSON.stringify({ width: 2000, height: 2000 }));
    const { panel } = await openPanel();
    expect(panel).toHaveStyle({ width: '1152px', height: '720px' });
  });
  it('persists panel size changes reported by the resize observer', async () => {
    const { panel } = await openPanel();
    mockPanelRect(panel, { width: 500, height: 650 });
    emitResize(panel);
    expect(panel).toHaveStyle({ width: '500px', height: '650px' });
    expect(JSON.parse(localStorage.getItem('chief-of-staff:7:1') || '{}')).toMatchObject({ width: 500, height: 650 });
  });
  it('exits wide view with the size dragged out once the pointer is released', async () => {
    const { user, panel } = await openPanel();
    const rect = mockPanelRect(panel, { width: 1229, height: 736, right: 1260, bottom: 760 });
    await user.click(wideButton('宽屏模式'));
    const clock = vi.spyOn(Date, 'now').mockReturnValue(Date.now() + 1000);
    rect.mockReturnValue(fullRect({ width: 1180, height: 700, right: 1220, bottom: 720 }));
    emitResize(panel);
    // 拖拽中间帧不能立刻退出，否则会丢掉用户最终拖出的尺寸
    expect(panel).toHaveClass('aw-chief-expanded');
    rect.mockReturnValue(fullRect({ width: 1000, height: 700, right: 1200, bottom: 720 }));
    emitResize(panel);
    expect(panel).toHaveClass('aw-chief-expanded');
    clock.mockRestore();
    fireEvent(window, new Event('pointerup'));
    expect(panel).not.toHaveClass('aw-chief-expanded');
    expect(panel).toHaveStyle({ width: '1000px', height: '700px' });
    expect(chief()).toHaveStyle({ right: '80px', bottom: '32px' });
    expect(JSON.parse(localStorage.getItem('chief-of-staff:7:1') || '{}')).toMatchObject({ expanded: false, width: 1000, height: 700, x: 80, y: 32 });
  });
  it('stays wide when the pointer is released without a manual resize', async () => {
    const { user, panel } = await openPanel();
    mockPanelRect(panel, { width: 1229, height: 736, right: 1260, bottom: 760 });
    await user.click(wideButton('宽屏模式'));
    fireEvent(window, new Event('pointerup'));
    expect(panel).toHaveClass('aw-chief-expanded');
  });
  it('keeps wide view when the viewport rather than the panel changes size', async () => {
    const { user, panel } = await openPanel();
    const rect = mockPanelRect(panel, { width: 1229, height: 736, right: 1260, bottom: 760 });
    await user.click(wideButton('宽屏模式'));
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 1440 });
    Object.defineProperty(window, 'innerHeight', { configurable: true, value: 900 });
    fireEvent(window, new Event('resize'));
    rect.mockReturnValue(fullRect({ width: 1382, height: 828, right: 1414, bottom: 864 }));
    emitResize(panel);
    expect(panel).toHaveClass('aw-chief-expanded');
  });
});
describe('wideResizeExit', () => {
  const viewport = { width: 1280, height: 800 };
  it('ignores missing baselines and changes within two pixels', () => {
    expect(wideResizeExit({ width: 1229, height: 736, right: 1260, bottom: 760 }, null, viewport, false)).toBeNull();
    expect(wideResizeExit({ width: 1231, height: 735, right: 1260, bottom: 760 }, { width: 1229, height: 736 }, viewport, false)).toBeNull();
  });
  it('clamps the new size to the normal bounds and keeps the position continuous', () => {
    expect(wideResizeExit({ width: 1000, height: 700, right: 1200, bottom: 720 }, { width: 1229, height: 736 }, viewport, false))
      .toMatchObject({ width: 1000, height: 700, x: 80, y: 32 });
    expect(wideResizeExit({ width: 1400, height: 1000, right: 1280, bottom: 800 }, { width: 1000, height: 600 }, viewport, false))
      .toMatchObject({ width: 1152, height: 720, x: 0, y: 0 });
    expect(wideResizeExit({ width: 300, height: 300, right: 1280, bottom: 790 }, { width: 1000, height: 600 }, viewport, true))
      .toMatchObject({ width: 340, height: 400, x: 0, y: 0 });
  });
});

it('renders seven and two answers under their IN turns after reload and never reopens settled pending cards', async () => {
  localStorage.setItem('chief-of-staff:7:1', JSON.stringify({ conversationId: 100 }));
  const rows = (count: number, turnId: number) => Array.from({ length: count }, (_, i) => ({ requestId: `q-${turnId}-${i}`, turnId,
    mode: 'form', message: `question ${turnId}/${i}`, requestedSchema: '{"type":"object","properties":{"value":{"type":"string","title":"Answer"}}}',
    status: 'ANSWERED', gmtCreate: '', answerJson: JSON.stringify({ value: `exact answer ${turnId}/${i}` }), deliveryStatus: 'WRITTEN' as const }));
  const cards = [...rows(7, 200), ...rows(2, 202)];
  vi.mocked(api.getConversation).mockResolvedValue({ ...conversation, questionHistory: cards, answerDeliveries: cards,
    pendingElicitations: cards.map(card => ({ ...card, status: 'PENDING' })),
    turns: [{ id: 200, direction: 'IN', content: 'First request', status: 'SUCCESS', error: null, gmtCreate: '' },
      { id: 201, direction: 'OUT', content: 'First result', status: 'SUCCESS', error: null, gmtCreate: '' },
      { id: 202, direction: 'IN', content: 'Second request', status: 'SUCCESS', error: null, gmtCreate: '' }] });
  const user = userEvent.setup(); const view = mount();
  await user.click(screen.getByRole('button', { name: '打开 Chief of Staff' }));
  for (const card of cards) expect(await screen.findAllByText(JSON.parse(card.answerJson).value)).toHaveLength(1);
  expect(screen.queryByText('回答已写入执行器，等待后续进展')).not.toBeInTheDocument();
  expect(screen.queryByTestId('elicitation-wizard-q-200-0')).not.toBeInTheDocument();
  expect(screen.getByText('exact answer 200/0').closest('section')).toHaveTextContent('First request');
  expect(screen.getByText('exact answer 202/0').closest('section')).toHaveTextContent('Second request');
  view.unmount(); mount();
  await user.click(screen.getByRole('button', { name: '打开 Chief of Staff' }));
  for (const card of cards) expect(await screen.findAllByText(JSON.parse(card.answerJson).value)).toHaveLength(1);
});

it('restores expired original context without sending and respects Chief edit permissions and processing', async () => {
  localStorage.setItem('chief-of-staff:7:1', JSON.stringify({ conversationId: 100 }));
  const expired = { requestId: 'expired', turnId: 200, mode: 'form', message: 'Expired question', requestedSchema: '{"type":"object","properties":{"value":{"type":"string","title":"Question detail"}}}', status: 'EXPIRED', gmtCreate: '' };
  const data = { ...conversation, questionHistory: [expired], pendingElicitations: [{ ...expired, status: 'PENDING' }],
    turns: [{ id: 200, direction: 'IN', content: 'Actual original request', status: 'CANCELED', error: null, gmtCreate: '' },
      { id: 201, direction: 'OUT', content: 'persisted fallback', status: 'CANCELED', error: null, gmtCreate: '', display: { content: { code: 'QUESTION_EXPIRED' as const, args: {} } } }] };
  vi.mocked(api.getConversation).mockResolvedValue(data);
  const user = userEvent.setup(); mount();
  await user.click(screen.getByRole('button', { name: '打开 Chief of Staff' }));
  const input = await screen.findByRole('textbox', { name: '输入消息' });
  const restore = await screen.findByRole('button', { name: '继续回答' });
  expect(screen.getByText('因长时间未回答而结束')).toBeInTheDocument();
  expect(screen.queryByTestId('elicitation-wizard-expired')).not.toBeInTheDocument();
  expect(screen.queryByText('persisted fallback')).not.toBeInTheDocument();
  await user.type(input, 'Keep this draft');
  await user.click(restore); await user.click(restore);
  expect((input as HTMLTextAreaElement).value).toContain('Keep this draft\n\n');
  expect((input as HTMLTextAreaElement).value).toContain('Actual original request');
  expect((input as HTMLTextAreaElement).value).toContain('Question detail');
  expect((input as HTMLTextAreaElement).value.match(/Actual original request/g)).toHaveLength(1);
  expect(api.sendMessage).not.toHaveBeenCalled(); expect(api.replyElicitation).not.toHaveBeenCalled();
  act(() => useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'Project', description: '' }, 'READ_ONLY'));
  expect(restore).toBeDisabled();
  act(() => useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'Project', description: '' }, 'ADMIN'));
  const query = clients[0].getQueryCache().findAll().find(q => q.state.data === data)!;
  act(() => clients[0].setQueryData(query.queryKey, { ...data, processingStatus: 'PROCESSING', processingTurnId: 202 }));
  await waitFor(() => expect(restore).toBeDisabled());
  act(() => clients[0].setQueryData(query.queryKey, data));
  await waitFor(() => expect(restore).toBeEnabled());
  await user.click(screen.getByRole('button', { name: '发送' }));
  await waitFor(() => expect(api.sendMessage).toHaveBeenCalledOnce());
  expect(api.replyElicitation).not.toHaveBeenCalled();
});

it('suppresses a stale Chief wizard after live-only resolution and resets identity scope on conversation switch', async () => {
  localStorage.setItem('chief-of-staff:7:1', JSON.stringify({ conversationId: 100 }));
  const pending = { requestId: 'reused-request', turnId: 200, mode: 'form', message: 'Live question', requestedSchema: '{"type":"object","properties":{"value":{"type":"string"}}}', status: 'PENDING', gmtCreate: '' };
  const first = { ...conversation, acpInteractionSupported: true, processingStatus: 'PROCESSING', processingTurnId: 200,
    pendingElicitations: [pending], turns: [{ id: 200, direction: 'IN', content: 'First live IN', status: 'PROCESSING', error: null, gmtCreate: '' }] };
  const second = { ...first, id: 101, title: 'Second conversation', pendingElicitations: [{ ...pending, message: 'Second pending question' }] };
  vi.mocked(api.getConversation).mockImplementation(async id => id === 100 ? first : second);
  vi.mocked(api.listConversations).mockResolvedValue([second]);
  const row = (id: number, eventType: string, data: Record<string, unknown>) => ({ id, conversationId: 100, turnId: 200, dispatchAttempt: 1,
    eventSeq: id, chunkIndex: 0, chunkCount: 1, eventType, payloadFragment: JSON.stringify({ data }), gmtCreate: '' });
  vi.mocked(api.getTurnEventPage).mockImplementation(async conversationId => ({ rows: conversationId === 100 ? [
    row(1, 'acp_elicitation', { requestId: pending.requestId, message: pending.message }),
    row(2, 'acp_elicitation_resolved', { requestId: pending.requestId, action: 'accept', status: 'ANSWERED', content: { value: 'live answer' } }),
  ] : [], nextAfterId: 2, hasMore: false }));
  const user = userEvent.setup(); mount();
  await user.click(screen.getByRole('button', { name: '打开 Chief of Staff' }));
  expect(await screen.findByText('live answer')).toBeInTheDocument();
  await waitFor(() => expect(screen.queryByTestId('elicitation-wizard-reused-request')).not.toBeInTheDocument());
  await user.click(screen.getByRole('button', { name: '历史会话' }));
  await user.click(await screen.findByRole('button', { name: 'Second conversation' }));
  expect(await screen.findByTestId('elicitation-wizard-reused-request')).toHaveTextContent('Second pending question');
  expect(screen.queryByText('live answer')).not.toBeInTheDocument();
});

describe('Chief of Staff guided setup', () => {
  it.each([false, true])('generates immediately after creating the executor without waiting for polling (older poll: %s)', async (olderPoll) => {
    const initial = { ...ready, executorId: null, executorConfigured: false, launchConfigured: false, ready: false };
    const configured = { ...ready, ready: false };
    vi.mocked(api.setupStatus).mockResolvedValue(initial);
    vi.mocked(api.completeSetup).mockResolvedValue(configured);
    vi.mocked(buildExecutorLaunchCommand).mockResolvedValue(launchCommand);
    const completed = vi.fn();
    const unsubscribe = subscribeGuideEvents(event => {
      if (event.type === 'substep-completed') completed();
    });
    publishGuideRuntime({ active: true, workspaceId: 1, stage: 1, substep: 1, capabilityReady: true });
    mount();
    act(() => openChiefPanel({ expanded: false }));
    await screen.findByRole('button', { name: '补齐配置' });
    let resolveOlderPoll: ((value: api.SetupStatus) => void) | undefined;
    if (olderPoll) {
      const pendingPoll = new Promise<api.SetupStatus>(resolve => { resolveOlderPoll = resolve; });
      vi.mocked(api.setupStatus).mockReturnValueOnce(pendingPoll);
      act(() => { void clients[0].refetchQueries({ queryKey: ['chiefOfStaff', 7, 1, 'setup'], exact: true }); });
      await waitFor(() => expect(resolveOlderPoll).toBeDefined());
    }
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: '补齐配置' }));
    await waitFor(() => expect(completed).toHaveBeenCalledTimes(1));
    // An older setup GET must not erase the executor returned by the completed POST.
    await act(async () => { resolveOlderPoll?.(initial); });
    act(() => publishGuideRuntime({ substep: 2 }));
    await user.click(screen.getByRole('button', { name: '确认选择' }));
    expect(completed).toHaveBeenCalledTimes(2);
    act(() => publishGuideRuntime({ substep: 3 }));
    await user.click(screen.getByRole('button', { name: '生成启动命令' }));
    await waitFor(() => expect(buildExecutorLaunchCommand).toHaveBeenCalledWith(20, { os: 'posix' }));
    expect(completed).toHaveBeenCalledTimes(3);
    act(() => publishGuideRuntime({ substep: 4 }));
    expect(await screen.findByText('cmd-x')).toBeInTheDocument();
    expect(screen.queryByText('操作未成功，请重试；仍停留在当前步骤。')).not.toBeInTheDocument();
    unsubscribe();
  });

  it('renders only the current guided substep and hides the composer during phase 1', async () => {
    vi.mocked(api.setupStatus).mockResolvedValue({ ...ready, executorConfigured: false, launchConfigured: false, ready: false });
    const events: unknown[] = [];
    const unsubscribe = subscribeGuideEvents(event => events.push(event));
    publishGuideRuntime({ active: true, workspaceId: 1, stage: 1, substep: 0, capabilityReady: true });
    mount();
    openChiefPanel({ expanded: false });
    expect(await screen.findByText('选择执行客户端')).toBeInTheDocument();
    expect(screen.getByRole('combobox', { name: '执行客户端' })).toBeInTheDocument();
    expect(screen.queryByRole('textbox', { name: '输入消息' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '历史会话' })).not.toBeInTheDocument();
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: '确认选择' }));
    expect(events).toContainEqual({ type: 'substep-completed' });
    unsubscribe();
  });

  it('prefills the initialization prompt and sends it with the reserved identity', async () => {
    publishGuideRuntime({ active: true, workspaceId: 1, stage: 3, substep: 0, capabilityReady: true });
    const events: unknown[] = [];
    const unsubscribe = subscribeGuideEvents(event => events.push(event));
    mount();
    openChiefPanel({
      expanded: true,
      conversationId: 100,
      clientMessageId: 'reserved-1',
      prefill: '请初始化当前工作空间，并引导我完成软件研发交付所需的配置',
    });
    const input = await screen.findByRole('textbox', { name: '输入消息' });
    expect(input).toHaveValue('请初始化当前工作空间，并引导我完成软件研发交付所需的配置');
    const user = userEvent.setup();
    await waitFor(() => expect(screen.getByRole('button', { name: '发送' })).toBeEnabled());
    await user.click(screen.getByRole('button', { name: '发送' }));
    await waitFor(() => expect(api.sendMessage).toHaveBeenCalledWith(100,
      '请初始化当前工作空间，并引导我完成软件研发交付所需的配置', 'reserved-1', '/workitems/128'));
    await waitFor(() => expect(events).toContainEqual({
      type: 'send-succeeded', conversationId: 100, clientMessageId: 'reserved-1',
    }));
    unsubscribe();
  });

  it('never overwrites an existing draft when the guide asks to prefill', async () => {
    mount();
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: '打开 Chief of Staff' }));
    const input = await screen.findByRole('textbox', { name: '输入消息' });
    await user.type(input, 'my own draft');
    const results: boolean[] = [];
    openChiefPanel({ expanded: true, conversationId: 100, prefill: '请初始化当前工作空间，并引导我完成软件研发交付所需的配置', onPrefillResult: accepted => results.push(accepted) });
    await waitFor(() => expect(results).toEqual([false]));
    expect(screen.getByRole('textbox', { name: '输入消息' })).toHaveValue('my own draft');
  });

  it('does not leave expanded mode on Escape while the guide is active', async () => {
    publishGuideRuntime({ active: true, workspaceId: 1, stage: 3, substep: 0, capabilityReady: true });
    mount();
    openChiefPanel({ expanded: true, conversationId: 100, clientMessageId: 'reserved-1' });
    const panel = await screen.findByRole('dialog');
    expect(panel).toHaveClass('aw-chief-expanded');
    fireEvent.keyDown(window, { key: 'Escape' });
    expect(screen.getByRole('dialog')).toHaveClass('aw-chief-expanded');
  });

  it('reuses one conversation and one client message id while preparing the guided send', async () => {
    mount();
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: '打开 Chief of Staff' }));
    await screen.findByText('有什么可以帮你？');
    const first = await prepareGuideSend(null);
    const second = await prepareGuideSend(null);
    expect(api.createConversation).toHaveBeenCalledTimes(1);
    expect(second).toEqual(first);
    expect(first.conversationId).toBe(100);
    expect(first.clientMessageId).toMatch(/[0-9a-f-]{8,}/);
    const existing = await prepareGuideSend(55);
    expect(existing.conversationId).toBe(55);
    expect(api.createConversation).toHaveBeenCalledTimes(1);
  });
});

const PROMPT_ZH = '请初始化当前工作空间，并引导我完成软件研发交付所需的配置。';
const PROMPT_EN = 'Please initialize the current workspace and guide me through the configuration required for software delivery.';
const launchCommand: ExecutorLaunchCommandVO = {
  executorId: 20,
  clientKind: 'QODER_CLI',
  provider: 'qoder',
  memoryMode: 'platform',
  model: null,
  reasoningEffort: null,
  contextWindow: null,
  wsUrl: 'ws://localhost',
  runtimeVersion: '1.0.0',
  os: 'posix',
  debug: false,
  shell: null,
  command: 'cmd-x',
};

describe('Chief of Staff guided prompt and setup states', () => {
  it('follows the language for an untouched prompt but keeps an edited draft', async () => {
    publishGuideRuntime({ active: true, workspaceId: 1, stage: 3, substep: 0, capabilityReady: true });
    mount();
    openChiefPanel({ expanded: true, conversationId: 100, clientMessageId: 'message-1', prefill: PROMPT_ZH });
    const textarea = () => document.querySelector('.aw-chief-input-shell textarea') as HTMLTextAreaElement;
    await waitFor(() => expect(textarea()?.value).toBe(PROMPT_ZH));

    await act(async () => { await i18n.changeLanguage('en-US'); });
    await waitFor(() => expect(textarea().value).toBe(PROMPT_EN));

    const user = userEvent.setup();
    await user.type(textarea(), ' keep me');
    const edited = `${PROMPT_EN} keep me`;
    expect(textarea().value).toBe(edited);
    await act(async () => { await i18n.changeLanguage('zh-CN'); });
    expect(textarea().value).toBe(edited);
  });

  it('stops the two minute wait with a retry that resets the window', async () => {
    publishGuideRuntime({ active: true, workspaceId: 1, stage: 1, substep: 0, capabilityReady: true });
    mount();
    openChiefPanel({ expanded: false });
    await screen.findByText('选择执行客户端');
    vi.useFakeTimers();
    act(() => {
      publishGuideRuntime({ active: true, workspaceId: 1, stage: 1, substep: 5, capabilityReady: true });
    });
    expect(screen.getByRole('heading', { name: '等待 Chief 执行器上线' })).toBeInTheDocument();

    act(() => { vi.advanceTimersByTime(125000); });
    expect(screen.getByText(/暂未检测到执行器在线可用/)).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: /重\s*试/ }));
    expect(screen.queryByText(/暂未检测到执行器在线可用/)).not.toBeInTheDocument();
  });

  it('closes the panel when the guide requests it', async () => {
    publishGuideRuntime({ active: true, workspaceId: 1, stage: 3, substep: 0, capabilityReady: true });
    mount();
    openChiefPanel({ expanded: true, conversationId: 100, clientMessageId: 'message-1' });
    expect(await screen.findByRole('dialog')).toBeInTheDocument();

    act(() => closeChiefPanel());

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('rejects preparing a send after the workspace changed', async () => {
    let resolveCreate: ((value: api.Conversation) => void) | null = null;
    vi.mocked(api.createConversation).mockReturnValue(new Promise<api.Conversation>(resolve => { resolveCreate = resolve; }));
    mount();
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: '打开 Chief of Staff' }));
    await screen.findByText('有什么可以帮你？');

    const pending = prepareGuideSend(null);
    act(() => useAuthStore.getState().setCurrentWorkspace({ id: 2, name: 'Other', description: '' }, 'ADMIN'));
    resolveCreate!({ ...conversation, id: 200 });

    await expect(pending).rejects.toThrow('workspace changed');
  });

  it('rejects preparing a send when Chief is not configured', async () => {
    vi.mocked(api.setupStatus).mockResolvedValue({ ...ready, agentId: null });
    mount();
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: '打开 Chief of Staff' }));
    await screen.findByText('有什么可以帮你？');

    await expect(prepareGuideSend(null)).rejects.toThrow('Chief is not configured');
  });

  it('emits setup-ready only from the real readiness state', async () => {
    const events: Array<{ type: string }> = [];
    const unsubscribe = subscribeGuideEvents(event => events.push(event));
    publishGuideRuntime({ active: true, workspaceId: 1, stage: 1, substep: 0, capabilityReady: true });
    mount();
    openChiefPanel({ expanded: false });

    await screen.findByText('选择执行客户端');
    await waitFor(() => expect(events.some(event => event.type === 'setup-ready')).toBe(true));
    unsubscribe();
  });

  it('keeps the draft and reports a failed guided send', async () => {
    vi.mocked(api.sendMessage).mockRejectedValue(new Error('offline'));
    const events: Array<{ type: string }> = [];
    const unsubscribe = subscribeGuideEvents(event => events.push(event));
    publishGuideRuntime({ active: true, workspaceId: 1, stage: 3, substep: 0, capabilityReady: true });
    mount();
    openChiefPanel({ expanded: true, conversationId: 100, clientMessageId: 'message-1', prefill: PROMPT_ZH });
    await waitFor(() => expect(screen.getByRole('button', { name: '发送' })).toBeEnabled());

    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: '发送' }));

    await waitFor(() => expect(events).toContainEqual({ type: 'send-failed' }));
    expect((document.querySelector('.aw-chief-input-shell textarea') as HTMLTextAreaElement).value).toBe(PROMPT_ZH);
    unsubscribe();
  });

  it('shows the client selector and completes setup for an unconfigured executor', async () => {
    vi.mocked(api.setupStatus).mockResolvedValue({ ...ready, executorConfigured: false, launchConfigured: false, ready: false });
    mount();
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: '打开 Chief of Staff' }));

    expect(await screen.findByRole('combobox', { name: '执行器类型' })).toBeInTheDocument();
    expect(screen.queryByRole('combobox', { name: '运行系统' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '一键补全配置' }));
    await waitFor(() => expect(api.completeSetup).toHaveBeenCalledWith('QODER_CLI'));
  });

  it('offers launch command generation when setup is configured but not ready', async () => {
    vi.mocked(api.setupStatus).mockResolvedValue({ ...ready, ready: false });
    vi.mocked(buildExecutorLaunchCommand).mockResolvedValue(launchCommand);
    mount();
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: '打开 Chief of Staff' }));

    expect(screen.queryByRole('button', { name: '一键补全配置' })).not.toBeInTheDocument();
    await user.click(await screen.findByRole('button', { name: '获取启动命令' }));
    await waitFor(() => expect(buildExecutorLaunchCommand).toHaveBeenCalledWith(20, { os: 'posix' }));
    expect(await screen.findByText('cmd-x')).toBeInTheDocument();
  });
});

describe('Chief of Staff action plan timeline', () => {
  const plan = (id: string, turnId: number, status = 'COMPLETED'): api.ActionPlan => ({
    id, turnId, actionsJson: '[{"tool":"example","arguments":{"workspaceId":1}}]', payloadHash: `hash-${id}`,
    status, resultJson: null, expiresAt: '2099-01-01T00:00:00Z',
  });
  const turns = [
    { id: 200, direction: 'IN', content: 'First question', status: 'SUCCESS', gmtCreate: '' },
    { id: 201, direction: 'OUT', content: 'First answer', status: 'SUCCESS', gmtCreate: '' },
    { id: 202, direction: 'IN', content: 'Second question', status: 'SUCCESS', gmtCreate: '' },
    { id: 203, direction: 'OUT', content: 'Second answer', status: 'SUCCESS', gmtCreate: '' },
  ];
  async function open(data: Partial<api.Conversation>, plans: api.ActionPlan[]) {
    localStorage.setItem('chief-of-staff:7:1', JSON.stringify({ conversationId: 100 }));
    vi.mocked(api.getConversation).mockResolvedValue({ ...conversation, turns, ...data } as api.Conversation);
    vi.mocked(api.getPlans).mockResolvedValue(plans);
    const user = userEvent.setup(); mount();
    await user.click(screen.getByRole('button', { name: '打开 Chief of Staff' }));
    await screen.findByText('First answer');
    return user;
  }
  const card = (id: string) => document.querySelector(`[data-plan-id="${id}"]`) as HTMLElement;

  it('renders each action plan under the reply of its inbound turn', async () => {
    await open({}, [plan('20', 202), plan('10', 200)]);
    const firstQuestion = screen.getByText('First question').closest('section')!;
    const secondQuestion = screen.getByText('Second question').closest('section')!;
    expect(firstQuestion.textContent).not.toContain('操作计划');
    expect(card('10').closest('section')).toBe(screen.getByText('First answer').closest('section'));
    expect(card('20').closest('section')).toBe(screen.getByText('Second answer').closest('section'));
    expect(card('10').compareDocumentPosition(secondQuestion) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(secondQuestion.compareDocumentPosition(card('20')) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('keeps plans of the same turn in ascending id order', async () => {
    await open({}, [plan('30', 200), plan('9', 200)]);
    expect([...document.querySelectorAll('[data-plan-id]')].map(node => node.getAttribute('data-plan-id'))).toEqual(['9', '30']);
  });

  it('anchors a plan that points at an outbound turn directly after it', async () => {
    await open({}, [plan('15', 203)]);
    expect(card('15').closest('section')).toBe(screen.getByText('Second answer').closest('section'));
  });

  it('falls back to the trailing group when no loaded turn owns the plan', async () => {
    await open({}, [plan('7', 999)]);
    const orphans = screen.getByTestId('chief-orphan-plans');
    expect(orphans.querySelector('[data-plan-id="7"]')).not.toBeNull();
    expect(orphans.textContent).toContain('操作计划');
    expect(screen.getByText('Second answer').compareDocumentPosition(orphans) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('jumps from the pending notice to the earliest pending plan and hides after confirmation', async () => {
    const latest = plan('9', 200, 'PENDING');
    const earliest = plan('5', 202, 'PENDING');
    const user = await open({}, [latest, earliest]);
    const notice = (await screen.findByText('2 条操作计划等待确认')).closest('.aw-chief-pending-notice') as HTMLElement;
    expect(notice).toHaveAttribute('role', 'status');
    const scrollSpy = vi.mocked(Element.prototype.scrollIntoView);
    scrollSpy.mockClear();
    await user.click(within(notice).getByRole('button', { name: '查看' }));
    expect(scrollSpy).toHaveBeenCalledTimes(1);
    expect(card('5').contains(document.activeElement)).toBe(true);
    expect(document.activeElement).toHaveAttribute('aria-label', '收起操作计划');
    const completed = { ...earliest, status: 'COMPLETED', resultJson: '[]' };
    vi.mocked(api.resolvePlan).mockResolvedValue(completed);
    vi.mocked(api.getPlans).mockResolvedValue([{ ...latest, status: 'COMPLETED' }, completed]);
    await user.click(within(card('5')).getByRole('button', { name: '确认执行' }));
    await waitFor(() => expect(screen.queryByText(/条操作计划等待确认/)).not.toBeInTheDocument());
  });

  it('hides the pending notice in history mode', async () => {
    const user = await open({}, [plan('5', 200, 'PENDING')]);
    await screen.findByText('1 条操作计划等待确认');
    await user.click(screen.getByRole('button', { name: '历史会话' }));
    expect(screen.queryByText(/条操作计划等待确认/)).not.toBeInTheDocument();
  });

  it('hides the pending notice for an archived conversation', async () => {
    await open({ archivedAt: '2026-01-01T00:00:00Z' }, [plan('5', 200, 'PENDING')]);
    expect(screen.queryByText(/条操作计划等待确认/)).not.toBeInTheDocument();
  });
});
