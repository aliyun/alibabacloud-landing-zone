import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { ResizeHandle } from './ResizeHandle';

beforeEach(() => {
  if (!Element.prototype.setPointerCapture) {
    Element.prototype.setPointerCapture = vi.fn();
  }
  document.body.style.userSelect = '';
});

// jsdom 没有 PointerEvent 构造器，fireEvent.pointer* 会丢失坐标，用 MouseEvent 派生 pointer 事件
function firePointer(type: 'pointerdown' | 'pointermove' | 'pointerup' | 'pointercancel', target: HTMLElement, coords: { clientX?: number; clientY?: number } = {}) {
  fireEvent(target, new MouseEvent(type, { bubbles: true, cancelable: true, ...coords }));
}

describe('ResizeHandle', () => {
  it('computes width as start width minus deltaX for the horizontal (left edge) handle', () => {
    const onChange = vi.fn();
    render(
      <ResizeHandle direction="horizontal" value={400} min={320} max={720} onChange={onChange} />,
    );

    const handle = screen.getByTestId('resize-handle-horizontal');
    firePointer('pointerdown', handle, { clientX: 500, clientY: 200 });
    firePointer('pointermove', handle, { clientX: 440, clientY: 200 });

    // 向左拖 60px → 400 + 60
    expect(onChange).toHaveBeenLastCalledWith(460);

    firePointer('pointermove', handle, { clientX: 530, clientY: 200 });
    // 向右拖 30px → 400 - 30
    expect(onChange).toHaveBeenLastCalledWith(370);
  });

  it('computes height as start height minus deltaY for the vertical (top edge) handle', () => {
    const onChange = vi.fn();
    render(
      <ResizeHandle direction="vertical" value={500} min={280} max={900} onChange={onChange} />,
    );

    const handle = screen.getByTestId('resize-handle-vertical');
    firePointer('pointerdown', handle, { clientX: 100, clientY: 300 });
    firePointer('pointermove', handle, { clientX: 100, clientY: 220 });

    // 向上拖 80px → 500 + 80
    expect(onChange).toHaveBeenLastCalledWith(580);

    firePointer('pointermove', handle, { clientX: 100, clientY: 350 });
    // 向下拖 50px → 500 - 50
    expect(onChange).toHaveBeenLastCalledWith(450);
  });

  it('clamps values to the min/max range', () => {
    const onChange = vi.fn();
    render(
      <ResizeHandle direction="horizontal" value={400} min={320} max={720} onChange={onChange} />,
    );

    const handle = screen.getByTestId('resize-handle-horizontal');
    firePointer('pointerdown', handle, { clientX: 500, clientY: 0 });

    firePointer('pointermove', handle, { clientX: 0, clientY: 0 });
    expect(onChange).toHaveBeenLastCalledWith(720);

    firePointer('pointermove', handle, { clientX: 1000, clientY: 0 });
    expect(onChange).toHaveBeenLastCalledWith(320);
  });

  it('disables text selection while dragging and restores it on pointer up', () => {
    render(
      <ResizeHandle direction="horizontal" value={400} min={320} max={720} onChange={vi.fn()} />,
    );

    const handle = screen.getByTestId('resize-handle-horizontal');
    firePointer('pointerdown', handle, { clientX: 500, clientY: 0 });
    expect(document.body.style.userSelect).toBe('none');

    firePointer('pointerup', handle, { clientX: 480, clientY: 0 });
    expect(document.body.style.userSelect).toBe('');
  });

  it('stops reporting changes after the drag ends', () => {
    const onChange = vi.fn();
    render(
      <ResizeHandle direction="vertical" value={500} min={280} max={900} onChange={onChange} />,
    );

    const handle = screen.getByTestId('resize-handle-vertical');
    firePointer('pointerdown', handle, { clientX: 0, clientY: 300 });
    firePointer('pointerup', handle, { clientX: 0, clientY: 300 });
    firePointer('pointermove', handle, { clientX: 0, clientY: 100 });

    expect(onChange).not.toHaveBeenCalled();
  });

  it('exposes resize cursors on the edge hot zones', () => {
    render(
      <>
        <ResizeHandle direction="horizontal" value={400} min={320} max={720} onChange={vi.fn()} />
        <ResizeHandle direction="vertical" value={500} min={280} max={900} onChange={vi.fn()} />
      </>,
    );

    expect(screen.getByTestId('resize-handle-horizontal')).toHaveStyle({ cursor: 'col-resize' });
    expect(screen.getByTestId('resize-handle-vertical')).toHaveStyle({ cursor: 'row-resize' });
  });

  it('invokes the optional onDoubleClick callback when the handle is double clicked', () => {
    const onDoubleClick = vi.fn();
    render(
      <ResizeHandle
        direction="vertical"
        value={500}
        min={280}
        max={900}
        onChange={vi.fn()}
        onDoubleClick={onDoubleClick}
      />,
    );

    fireEvent.doubleClick(screen.getByTestId('resize-handle-vertical'));
    expect(onDoubleClick).toHaveBeenCalledTimes(1);
  });

  it('prefers the measureMax value measured at drag start over a stale max prop', () => {
    const onChange = vi.fn();
    render(
      <ResizeHandle
        direction="vertical"
        value={100}
        min={32}
        max={120}
        measureMax={() => 240}
        onChange={onChange}
      />,
    );

    const handle = screen.getByTestId('resize-handle-vertical');
    firePointer('pointerdown', handle, { clientX: 0, clientY: 300 });
    // 向上拖 200px → 100 + 200 = 300 → 被实测上限 240 钳制，而非滞后的 max=120
    firePointer('pointermove', handle, { clientX: 0, clientY: 100 });
    expect(onChange).toHaveBeenLastCalledWith(240);
  });
});
