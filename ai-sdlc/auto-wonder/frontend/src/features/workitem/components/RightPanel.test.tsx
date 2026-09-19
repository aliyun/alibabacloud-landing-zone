import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { RightPanel } from './RightPanel';
import { useAuthStore } from '@/shared/auth/store';

vi.mock('../clarification/WorkitemClarificationPanel', () => ({
  WorkitemClarificationPanel: ({
    onAgentConfirmed,
    fullscreen,
    initialAgentId,
    initialConversationId,
    onContextChange,
  }: {
    onAgentConfirmed?: () => void;
    fullscreen?: boolean;
    initialAgentId?: number | null;
    initialConversationId?: number | null;
    onContextChange?: (context: {
      agentId?: number | null;
      conversationId?: number | null;
    }) => void;
  }) => (
    <div
      data-testid="clarification-panel-stub"
      data-fullscreen={fullscreen ? 'true' : 'false'}
      data-initial-agent={initialAgentId == null ? '' : String(initialAgentId)}
      data-initial-conversation={initialConversationId == null ? '' : String(initialConversationId)}
    >
      <button type="button" onClick={() => onAgentConfirmed?.()}>stub-confirm-agent</button>
      <button type="button" onClick={() => onContextChange?.({ agentId: 42, conversationId: 101 })}>
        stub-report-context
      </button>
    </div>
  ),
}));

// jsdom 没有 PointerEvent 构造器，fireEvent.pointer* 会丢失坐标，用 MouseEvent 派生 pointer 事件
function firePointer(type: 'pointerdown' | 'pointermove' | 'pointerup', target: HTMLElement, coords: { clientX?: number; clientY?: number } = {}) {
  fireEvent(target, new MouseEvent(type, { bubbles: true, cancelable: true, ...coords }));
}

function renderPanel(props: Partial<Parameters<typeof RightPanel>[0]> = {}) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <RightPanel
        workitemId="1"
        participants={[]}
        steps={[]}
        artifacts={[]}
        {...props}
      />
    </QueryClientProvider>,
  );
}

async function enterClarifyMode() {
  await userEvent.click(screen.getByRole('button', { name: /AI 需求澄清/ }));
  const box = await screen.findByTestId('clarify-resize-box');
  vi.spyOn(box, 'getBoundingClientRect').mockReturnValue({ width: 400, height: 500 } as DOMRect);
  vi.spyOn(box.parentElement!, 'getBoundingClientRect').mockReturnValue({ height: 900 } as DOMRect);
  return box;
}

describe('RightPanel resize handles', () => {
  beforeEach(() => {
    useAuthStore.getState().clear();
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_WRITE');
    if (!Element.prototype.setPointerCapture) {
      Element.prototype.setPointerCapture = vi.fn();
    }
  });

  it('does not render resize handles in progress mode', () => {
    renderPanel();

    expect(screen.getByRole('button', { name: /AI 需求澄清/ })).toBeInTheDocument();
    expect(screen.queryByTestId('resize-handle-horizontal')).not.toBeInTheDocument();
    expect(screen.queryByTestId('resize-handle-vertical')).not.toBeInTheDocument();
  });

  it('renders only the height resize handle in clarify mode; the width handle lives at page container level', async () => {
    renderPanel();

    await userEvent.click(screen.getByRole('button', { name: /AI 需求澄清/ }));

    expect(await screen.findByTestId('clarification-panel-stub')).toBeInTheDocument();
    expect(screen.queryByTestId('resize-handle-horizontal')).not.toBeInTheDocument();
    expect(screen.getByTestId('resize-handle-vertical')).toBeInTheDocument();
    // 底部锚定：变矮时底边不动、顶边下移
    expect(screen.getByTestId('clarify-resize-box')).toHaveStyle('margin-top: auto');
  });

  it('adjusts and clamps the clarify panel height while dragging the top-edge handle', async () => {
    renderPanel();
    const box = await enterClarifyMode();

    const heightHandle = screen.getByTestId('resize-handle-vertical');
    firePointer('pointerdown', heightHandle, { clientX: 100, clientY: 300 });
    firePointer('pointermove', heightHandle, { clientX: 100, clientY: 100 });
    // 向上拖 200px：500 + 200，且不超过内容区满高 900px
    expect(box.style.height).toBe('700px');
    firePointer('pointermove', heightHandle, { clientX: 100, clientY: 800 });
    // 拖出范围钳制到下限 280px
    expect(box.style.height).toBe('280px');
    firePointer('pointerup', heightHandle, { clientX: 100, clientY: 800 });
  });

  it('restores default size after returning to progress mode and re-entering clarify', async () => {
    const onModeChange = vi.fn();
    renderPanel({ onModeChange });
    const box = await enterClarifyMode();

    const heightHandle = screen.getByTestId('resize-handle-vertical');
    firePointer('pointerdown', heightHandle, { clientX: 100, clientY: 300 });
    firePointer('pointermove', heightHandle, { clientX: 100, clientY: 100 });
    expect(box.style.height).toBe('700px');
    firePointer('pointerup', heightHandle, { clientX: 100, clientY: 100 });
    expect(onModeChange).toHaveBeenLastCalledWith('clarify');

    await userEvent.click(screen.getByRole('button', { name: /返回进度/ }));
    expect(screen.queryByTestId('resize-handle-vertical')).not.toBeInTheDocument();
    expect(screen.queryByTestId('clarify-resize-box')).not.toBeInTheDocument();
    expect(onModeChange).toHaveBeenLastCalledWith('progress');

    await userEvent.click(screen.getByRole('button', { name: /AI 需求澄清/ }));
    const reopened = await screen.findByTestId('clarify-resize-box');
    expect(reopened.style.width).toBe('100%');
    expect(reopened.style.height).toBe('100%');
  });

  it('paints the clarify container on the white surface in both docked and fullscreen layouts', async () => {
    renderPanel();
    const box = await enterClarifyMode();

    // 「灰色底、死气沉沉」是本次改造要消灭的首要观感问题，而停靠态的白底此前没有任何断言：
    // 把 backgroundColor 从停靠分支删掉，全套测试依旧全绿。这条就是那道守卫。
    // 刻意写字面值而不是引用 CLARIFICATION_THEME.surface：只拿常量和自己比，
    // 令牌被改回灰色时这里还是绿的（同 theme.test.ts 的取舍）。
    expect(box).toHaveStyle({ position: 'relative', backgroundColor: '#ffffff' });

    await userEvent.click(screen.getByRole('button', { name: '全屏' }));
    expect(screen.getByTestId('clarify-resize-box')).toHaveStyle({
      position: 'fixed',
      backgroundColor: '#ffffff',
    });
  });
});

describe('RightPanel clarify fullscreen', () => {
  beforeEach(() => {
    useAuthStore.getState().clear();
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_WRITE');
    if (!Element.prototype.setPointerCapture) {
      Element.prototype.setPointerCapture = vi.fn();
    }
  });

  it('shows a low-key fullscreen toggle in clarify mode and toggles fullscreen layout', async () => {
    renderPanel();
    await enterClarifyMode();

    const fullscreenButton = screen.getByRole('button', { name: '全屏' });
    expect(fullscreenButton).toBeInTheDocument();
    // 自动全屏后这个按钮主要用于退出，降级为极简 text 图标按钮
    expect(fullscreenButton).toHaveClass('ant-btn-text');
    expect(fullscreenButton).not.toHaveClass('ant-btn-primary');

    await userEvent.click(fullscreenButton);
    const box = screen.getByTestId('clarify-resize-box');
    expect(box).toHaveStyle({ position: 'fixed', zIndex: 1000 });
    expect(screen.queryByTestId('resize-handle-vertical')).not.toBeInTheDocument();
    expect(screen.getByTestId('clarification-panel-stub')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: '退出全屏' }));
    const restored = screen.getByTestId('clarify-resize-box');
    expect(restored).toHaveStyle('position: relative');
    expect(screen.getByTestId('resize-handle-vertical')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '全屏' })).toBeInTheDocument();
  });

  it('exits fullscreen with the Escape key', async () => {
    renderPanel();
    await enterClarifyMode();

    await userEvent.click(screen.getByRole('button', { name: '全屏' }));
    expect(screen.getByTestId('clarify-resize-box')).toHaveStyle('position: fixed');

    fireEvent.keyDown(window, { key: 'Escape' });
    const box = screen.getByTestId('clarify-resize-box');
    expect(box).toHaveStyle('position: relative');
    expect(screen.getByTestId('resize-handle-vertical')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '全屏' })).toBeInTheDocument();
  });

  it('resets fullscreen state after returning to progress mode and re-entering clarify', async () => {
    renderPanel();
    await enterClarifyMode();

    await userEvent.click(screen.getByRole('button', { name: '全屏' }));
    expect(screen.getByTestId('clarify-resize-box')).toHaveStyle('position: fixed');

    await userEvent.click(screen.getByRole('button', { name: /返回进度/ }));
    await userEvent.click(screen.getByRole('button', { name: /AI 需求澄清/ }));
    const reopened = await screen.findByTestId('clarify-resize-box');
    expect(reopened).toHaveStyle('position: relative');
    expect(screen.getByTestId('resize-handle-vertical')).toBeInTheDocument();
  });

  it('enters fullscreen automatically once the panel reports a confirmed agent', async () => {
    renderPanel();
    await enterClarifyMode();

    // 选人之前是普通右侧面板
    expect(screen.getByTestId('clarify-resize-box')).toHaveStyle('position: relative');

    await userEvent.click(screen.getByRole('button', { name: 'stub-confirm-agent' }));

    expect(screen.getByTestId('clarify-resize-box')).toHaveStyle({ position: 'fixed', zIndex: 1000 });
    expect(screen.queryByTestId('resize-handle-vertical')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: '退出全屏' })).toBeInTheDocument();

    // 再次上报（如「切换数字人」后重新选人）应幂等地保持全屏，而不是被切走
    await userEvent.click(screen.getByRole('button', { name: 'stub-confirm-agent' }));
    expect(screen.getByTestId('clarify-resize-box')).toHaveStyle('position: fixed');
  });

  it('still allows exiting fullscreen after it was entered automatically', async () => {
    renderPanel();
    await enterClarifyMode();

    await userEvent.click(screen.getByRole('button', { name: 'stub-confirm-agent' }));
    expect(screen.getByTestId('clarify-resize-box')).toHaveStyle('position: fixed');

    fireEvent.keyDown(window, { key: 'Escape' });
    expect(screen.getByTestId('clarify-resize-box')).toHaveStyle('position: relative');
  });

  it('does not re-enter fullscreen when the panel re-reports a confirmed agent after the user pressed Escape', async () => {
    renderPanel();
    await enterClarifyMode();

    await userEvent.click(screen.getByRole('button', { name: 'stub-confirm-agent' }));
    expect(screen.getByTestId('clarify-resize-box')).toHaveStyle('position: fixed');

    // 用户主动退出全屏，想要回到窄面板
    fireEvent.keyDown(window, { key: 'Escape' });
    expect(screen.getByTestId('clarify-resize-box')).toHaveStyle('position: relative');

    // 轮询让 agents 短暂缺失又恢复，面板内部守卫复位后会再次上报；
    // 此时不能把用户硬拽回他刚刚主动退出的全屏
    await userEvent.click(screen.getByRole('button', { name: 'stub-confirm-agent' }));
    expect(screen.getByTestId('clarify-resize-box')).toHaveStyle('position: relative');
    expect(screen.getByTestId('resize-handle-vertical')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '全屏' })).toBeInTheDocument();
  });

  it('forgets the deliberate exit after leaving and re-entering clarify mode', async () => {
    renderPanel();
    await enterClarifyMode();

    await userEvent.click(screen.getByRole('button', { name: 'stub-confirm-agent' }));
    fireEvent.keyDown(window, { key: 'Escape' });
    await userEvent.click(screen.getByRole('button', { name: 'stub-confirm-agent' }));
    expect(screen.getByTestId('clarify-resize-box')).toHaveStyle('position: relative');

    await userEvent.click(screen.getByRole('button', { name: /返回进度/ }));
    await userEvent.click(screen.getByRole('button', { name: /AI 需求澄清/ }));
    const reopened = await screen.findByTestId('clarify-resize-box');
    expect(reopened).toHaveStyle('position: relative');

    // 退出意图只在本次澄清会话内有效，重入后应重新自动全屏
    await userEvent.click(screen.getByRole('button', { name: 'stub-confirm-agent' }));
    expect(screen.getByTestId('clarify-resize-box')).toHaveStyle('position: fixed');
  });

  it('still enters fullscreen when the user clicks the toggle after a deliberate Escape exit', async () => {
    renderPanel();
    await enterClarifyMode();

    await userEvent.click(screen.getByRole('button', { name: 'stub-confirm-agent' }));
    fireEvent.keyDown(window, { key: 'Escape' });
    expect(screen.getByTestId('clarify-resize-box')).toHaveStyle('position: relative');

    await userEvent.click(screen.getByRole('button', { name: '全屏' }));
    expect(screen.getByTestId('clarify-resize-box')).toHaveStyle({ position: 'fixed', zIndex: 1000 });
  });

  it('re-enters fullscreen automatically after returning to progress and picking an agent again', async () => {
    renderPanel();
    await enterClarifyMode();
    await userEvent.click(screen.getByRole('button', { name: 'stub-confirm-agent' }));
    expect(screen.getByTestId('clarify-resize-box')).toHaveStyle('position: fixed');

    await userEvent.click(screen.getByRole('button', { name: /返回进度/ }));
    await userEvent.click(screen.getByRole('button', { name: /AI 需求澄清/ }));
    const reopened = await screen.findByTestId('clarify-resize-box');
    // 重入时全屏状态已重置
    expect(reopened).toHaveStyle('position: relative');

    await userEvent.click(screen.getByRole('button', { name: 'stub-confirm-agent' }));
    expect(screen.getByTestId('clarify-resize-box')).toHaveStyle('position: fixed');
  });

  it('tells the clarification panel whether it is rendered fullscreen', async () => {
    renderPanel();
    await enterClarifyMode();

    expect(screen.getByTestId('clarification-panel-stub')).toHaveAttribute('data-fullscreen', 'false');

    await userEvent.click(screen.getByRole('button', { name: 'stub-confirm-agent' }));
    expect(screen.getByTestId('clarification-panel-stub')).toHaveAttribute('data-fullscreen', 'true');

    fireEvent.keyDown(window, { key: 'Escape' });
    expect(screen.getByTestId('clarification-panel-stub')).toHaveAttribute('data-fullscreen', 'false');
  });
});

describe('RightPanel refresh restore (工单 53035)', () => {
  beforeEach(() => {
    useAuthStore.getState().clear();
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_WRITE');
    if (!Element.prototype.setPointerCapture) {
      Element.prototype.setPointerCapture = vi.fn();
    }
  });

  it('opens straight into fullscreen clarify when the URL says so', async () => {
    // 刷新后必须停在全屏澄清页，而不是先回到进度再让用户点两次
    renderPanel({ initialMode: 'clarify', initialFullscreen: true });

    const box = await screen.findByTestId('clarify-resize-box');
    expect(box).toHaveStyle({ position: 'fixed', zIndex: 1000 });
    expect(screen.queryByTestId('resize-handle-vertical')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: '退出全屏' })).toBeInTheDocument();
    expect(screen.getByTestId('clarification-panel-stub')).toHaveAttribute('data-fullscreen', 'true');
  });

  it('opens into docked clarify when the URL restores clarify without fullscreen', async () => {
    renderPanel({ initialMode: 'clarify' });

    const box = await screen.findByTestId('clarify-resize-box');
    expect(box).toHaveStyle('position: relative');
    expect(screen.getByTestId('resize-handle-vertical')).toBeInTheDocument();
    expect(screen.getByTestId('clarification-panel-stub')).toHaveAttribute('data-fullscreen', 'false');
  });

  it('stays on the progress panel when nothing is restored', () => {
    renderPanel({ initialMode: 'progress', initialFullscreen: false });

    expect(screen.getByRole('button', { name: /AI 需求澄清/ })).toBeInTheDocument();
    expect(screen.queryByTestId('clarify-resize-box')).not.toBeInTheDocument();
  });

  it('ignores a restored fullscreen flag while in progress mode', () => {
    // writeClarifyView 会在 progress 下抹掉 fullscreen，这里守住组件侧的同一契约
    renderPanel({ initialMode: 'progress', initialFullscreen: true });

    expect(screen.queryByTestId('clarify-resize-box')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /AI 需求澄清/ })).toBeInTheDocument();
  });

  it('passes the restored agent and conversation down to the panel', async () => {
    renderPanel({
      initialMode: 'clarify',
      clarifyContext: { agentId: 42, conversationId: 101 },
    });

    const stub = await screen.findByTestId('clarification-panel-stub');
    expect(stub).toHaveAttribute('data-initial-agent', '42');
    expect(stub).toHaveAttribute('data-initial-conversation', '101');
  });

  it('hands the panel empty restore ids when the URL carries none', async () => {
    renderPanel({ initialMode: 'clarify' });

    const stub = await screen.findByTestId('clarification-panel-stub');
    expect(stub).toHaveAttribute('data-initial-agent', '');
    expect(stub).toHaveAttribute('data-initial-conversation', '');
  });

  it('reports every fullscreen transition upward so the page can write it to the URL', async () => {
    const onFullscreenChange = vi.fn();
    renderPanel({ onFullscreenChange });
    await enterClarifyMode();
    expect(onFullscreenChange).not.toHaveBeenCalled();

    await userEvent.click(screen.getByRole('button', { name: '全屏' }));
    expect(onFullscreenChange).toHaveBeenLastCalledWith(true);

    await userEvent.click(screen.getByRole('button', { name: '退出全屏' }));
    expect(onFullscreenChange).toHaveBeenLastCalledWith(false);

    await userEvent.click(screen.getByRole('button', { name: '全屏' }));
    expect(onFullscreenChange).toHaveBeenLastCalledWith(true);

    fireEvent.keyDown(window, { key: 'Escape' });
    expect(onFullscreenChange).toHaveBeenLastCalledWith(false);
  });

  it('reports the automatic fullscreen entry upward as well', async () => {
    // 选定数字人后的自动全屏若不上报，刷新回来就退化成窄面板
    const onFullscreenChange = vi.fn();
    renderPanel({ onFullscreenChange });
    await enterClarifyMode();

    await userEvent.click(screen.getByRole('button', { name: 'stub-confirm-agent' }));

    expect(screen.getByTestId('clarify-resize-box')).toHaveStyle('position: fixed');
    expect(onFullscreenChange).toHaveBeenLastCalledWith(true);
  });

  it('does not report fullscreen again after the user deliberately exited', async () => {
    const onFullscreenChange = vi.fn();
    renderPanel({ onFullscreenChange });
    await enterClarifyMode();

    await userEvent.click(screen.getByRole('button', { name: 'stub-confirm-agent' }));
    expect(onFullscreenChange).toHaveBeenLastCalledWith(true);

    fireEvent.keyDown(window, { key: 'Escape' });
    expect(onFullscreenChange).toHaveBeenLastCalledWith(false);
    const callsAfterExit = onFullscreenChange.mock.calls.length;

    await userEvent.click(screen.getByRole('button', { name: 'stub-confirm-agent' }));
    expect(onFullscreenChange).toHaveBeenCalledTimes(callsAfterExit);
  });

  it('forwards panel context changes upward', async () => {
    const onClarifyContextChange = vi.fn();
    renderPanel({ initialMode: 'clarify', onClarifyContextChange });

    await userEvent.click(await screen.findByRole('button', { name: 'stub-report-context' }));

    expect(onClarifyContextChange).toHaveBeenCalledWith({ agentId: 42, conversationId: 101 });
  });

  it('reports progress mode without a separate fullscreen reset', async () => {
    // 返回进度时由页面按 mode=progress 一次性抹掉 fullscreen/agent/conversation，
    // 再多发一次 onFullscreenChange(false) 只会让 URL 多写一轮
    const onModeChange = vi.fn();
    const onFullscreenChange = vi.fn();
    renderPanel({ initialMode: 'clarify', initialFullscreen: true, onModeChange, onFullscreenChange });

    await userEvent.click(await screen.findByRole('button', { name: /返回进度/ }));

    expect(onModeChange).toHaveBeenLastCalledWith('progress');
    expect(onFullscreenChange).not.toHaveBeenCalled();
    expect(screen.queryByTestId('clarify-resize-box')).not.toBeInTheDocument();
  });
});

describe('RightPanel page-driven mode switch (工单 53315)', () => {
  type PanelProps = Parameters<typeof RightPanel>[0];

  /** 页面级入口是通过改 URL（initialMode）驱动面板的，所以要能带着同样的 provider 重渲染。 */
  function renderPanelRerenderable(initialProps: Partial<PanelProps> = {}) {
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });
    const baseProps: PanelProps = {
      workitemId: '1', participants: [], steps: [], artifacts: [], ...initialProps,
    };
    const renderWith = (props: PanelProps) => (
      <QueryClientProvider client={queryClient}>
        <RightPanel {...props} />
      </QueryClientProvider>
    );
    const view = render(renderWith(baseProps));
    return {
      ...view,
      rerenderWith: (patch: Partial<PanelProps>) => view.rerender(renderWith({ ...baseProps, ...patch })),
    };
  }

  beforeEach(() => {
    useAuthStore.getState().clear();
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_WRITE');
  });

  it('follows the page into clarify mode after mount', async () => {
    // 「启动交付」前的澄清引导点确认后只改 URL，面板挂载早于这次点击，必须跟着切过去
    const panel = renderPanelRerenderable({ initialMode: 'progress' });
    expect(screen.getByRole('button', { name: /AI 需求澄清/ })).toBeInTheDocument();

    panel.rerenderWith({ initialMode: 'clarify' });

    expect(await screen.findByTestId('clarify-resize-box')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /返回进度/ })).toBeInTheDocument();
  });

  it('leaves clarify and drops fullscreen when the page drives it back to progress', async () => {
    const panel = renderPanelRerenderable({ initialMode: 'clarify', initialFullscreen: true });
    expect(await screen.findByTestId('clarify-resize-box')).toHaveStyle({ position: 'fixed' });

    panel.rerenderWith({ initialMode: 'progress', initialFullscreen: false });

    expect(screen.queryByTestId('clarify-resize-box')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /AI 需求澄清/ })).toBeInTheDocument();
  });

  it('keeps an internally entered clarify mode when the caller never reports mode changes', async () => {
    // ref 守卫要守住的回归：没接 onModeChange 的调用方 initialMode 永远不变，
    // 拿 mode 去比对会在下一次重渲染时把用户从澄清态拽回进度态
    const panel = renderPanelRerenderable();

    await userEvent.click(screen.getByRole('button', { name: /AI 需求澄清/ }));
    expect(await screen.findByTestId('clarify-resize-box')).toBeInTheDocument();

    panel.rerenderWith({ stepsLoading: true });

    expect(screen.getByTestId('clarify-resize-box')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /AI 需求澄清/ })).not.toBeInTheDocument();
  });
});

// 详情页右侧面板负责把 WatcherList 装配进来，此前没有断言证明它真的挂载并按
// 面板的 workitemId 拉取关注人；这里用真实 useWatchers（未 mock hooks）验证装配链路。
describe('RightPanel 关注人列表装配', () => {
  beforeEach(() => {
    useAuthStore.getState().clear();
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_WRITE');
  });

  it('渲染关注人列表并按面板 workitemId 拉取数据', async () => {
    const requested: string[] = [];
    server.use(
      http.get('/api/workitems/:workitemId/watchers', ({ params }) => {
        requested.push(String(params.workitemId));
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: [
            { userId: 200, name: '林一', displayId: '20000', role: 'HUMAN', roleName: '关注人', isAgent: false, online: false, status: '0' },
          ],
        });
      }),
    );

    renderPanel({ workitemId: '77' });

    expect(await screen.findByTestId('workitem-watcher-list')).toBeInTheDocument();
    await waitFor(() => expect(requested).toContain('77'));
    expect(screen.getByText('林一')).toBeInTheDocument();
  });
});
