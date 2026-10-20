import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, cleanup, within, fireEvent } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, useNavigate } from 'react-router-dom';
import { useAuthStore } from '@/shared/auth/store';
import { ChiefOfStaff } from './ChiefOfStaff';
import * as api from './api';
vi.mock('./api');
vi.mock('@/shared/realtime/useRealtime', () => ({ useRealtime: vi.fn() }));
const ready: api.SetupStatus = { agentId: 10, executorId: 20, agentConfigured: true, skillsConfigured: true, executorConfigured: true, launchConfigured: true, ready: true, missingSkills: [] };
const conversation: api.Conversation = { id: 100, agentId: 10, agentName: 'Chief of Staff', channelConversationId: 'private', title: 'My conversation', archivedAt: null, status: 'ACTIVE', executorOnline: true, streamingSupported: true, cancelSupported: true, cliSessionRef: null, processingStatus: null, processingTurnId: null, lastTurnAt: null, gmtCreate: '', turns: [] };
let clients: QueryClient[] = [];
function NextPage() { const navigate = useNavigate(); return <button onClick={() => navigate('/workitems/129')}>Next workitem</button>; }
function mount() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } }); clients.push(client);
  return render(<QueryClientProvider client={client}><MemoryRouter initialEntries={['/workitems/128']}><NextPage /><ChiefOfStaff /></MemoryRouter></QueryClientProvider>);
}
beforeEach(() => {
  localStorage.clear(); vi.resetAllMocks(); useAuthStore.getState().clear();
  useAuthStore.getState().setUser({ id: 7, username: 'owner', nickname: 'Owner', email: '' });
  useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'Project', description: '' }, 'ADMIN');
  vi.mocked(api.setupStatus).mockResolvedValue(ready);
  vi.mocked(api.createConversation).mockResolvedValue(conversation);
  vi.mocked(api.getConversation).mockResolvedValue(conversation);
  vi.mocked(api.listConversations).mockResolvedValue([]);
  vi.mocked(api.getPlans).mockResolvedValue([]);
  vi.mocked(api.getTurnEvents).mockResolvedValue([]);
  vi.mocked(api.sendMessage).mockResolvedValue(undefined);
});
afterEach(() => { cleanup(); clients.forEach(client => client.clear()); clients = []; });
describe('Chief of Staff', () => {
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
  it('copies every completed message without a reply action', async () => {
    localStorage.setItem('chief-of-staff:7:1', JSON.stringify({ conversationId: 100, x: 24, y: 24, width: 440, height: 620 }));
    vi.mocked(api.getConversation).mockResolvedValue({ ...conversation, turns: [{ id: 200, direction: 'OUT', content: 'Completed response', status: 'COMPLETED', gmtCreate: '' }] } as api.Conversation);
    const user = userEvent.setup(); mount();
    await user.click(screen.getByRole('button', { name: '打开 Chief of Staff' }));
    // Node Blob is required by the HTTP fixtures; jsdom FileReader cannot read it.
    // Observe the browser clipboard API boundary instead of user-event's readback shim.
    // eslint-disable-next-line no-restricted-properties -- Test the clipboard API boundary through the real fallback-capable helper.
    const writeText = vi.spyOn(navigator.clipboard, 'writeText').mockResolvedValue(undefined);
    await user.click(await screen.findByRole('button', { name: '复制消息' }));
    expect(writeText).toHaveBeenCalledWith('Completed response');
    expect(screen.queryByRole('button', { name: '回复这条消息' })).not.toBeInTheDocument();
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
    expect(launcher.querySelector('.anticon')).not.toBeNull();
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
    vi.mocked(api.getTurnEvents).mockResolvedValue([{ id: 1, conversationId: 100, turnId: 200, dispatchAttempt: 1, eventSeq: 1, chunkIndex: 0, chunkCount: 1, eventType: 'tool_result', payloadFragment: JSON.stringify({ tool: 'autowonder.get_workitem', callId: 'call-1', status: 'completed', output: 'Workitem details' }), gmtCreate: '' }]);
    vi.mocked(api.getPlans).mockResolvedValue([{ id: 'plan-1', actionsJson: '[]', payloadHash: 'hash', status: 'PENDING', resultJson: null, expiresAt: '2099-01-01T00:00:00Z' }]);
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
