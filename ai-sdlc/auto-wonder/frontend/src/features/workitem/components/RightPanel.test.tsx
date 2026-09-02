import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RightPanel } from './RightPanel';
import { useAuthStore } from '@/shared/auth/store';

vi.mock('../clarification/WorkitemClarificationPanel', () => ({
  WorkitemClarificationPanel: () => <div data-testid="clarification-panel-stub" />,
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
});

describe('RightPanel clarify fullscreen', () => {
  beforeEach(() => {
    useAuthStore.getState().clear();
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_WRITE');
    if (!Element.prototype.setPointerCapture) {
      Element.prototype.setPointerCapture = vi.fn();
    }
  });

  it('shows a prominent fullscreen button in clarify mode and toggles fullscreen layout', async () => {
    renderPanel();
    await enterClarifyMode();

    const fullscreenButton = screen.getByRole('button', { name: '全屏' });
    expect(fullscreenButton).toBeInTheDocument();
    expect(fullscreenButton).toHaveClass('ant-btn-primary');
    expect(fullscreenButton).toHaveClass('ant-btn-lg');

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
});
