import { useRef, useState } from 'react';
import type { PointerEvent as ReactPointerEvent } from 'react';

export type ResizeDirection = 'horizontal' | 'vertical';

export interface ResizeHandleProps {
  /** horizontal = 左边缘拖宽度；vertical = 上边缘拖高度（底部锚定） */
  direction: ResizeDirection;
  /** 当前尺寸（px） */
  value: number;
  /** 可选：pointerdown 时实时测量当前尺寸，优先于可能滞后的 value */
  measureValue?: () => number;
  /** 可选：pointerdown 时实时测量上限，优先于可能滞后的 max */
  measureMax?: () => number;
  min: number;
  max: number;
  onChange: (value: number) => void;
  /** 可选：双击手柄回调（如恢复自动模式）；不传则与现状一致 */
  onDoubleClick?: () => void;
  'aria-label'?: string;
}

const clamp = (value: number, min: number, max: number) =>
  Math.min(Math.max(value, min), max);

export function ResizeHandle({ direction, value, measureValue, measureMax, min, max, onChange, onDoubleClick, ...rest }: ResizeHandleProps) {
  const [hovered, setHovered] = useState(false);
  const [dragging, setDragging] = useState(false);
  const startRef = useRef<{ pointer: number; size: number; max: number } | null>(null);

  const horizontal = direction === 'horizontal';

  const handlePointerDown = (event: ReactPointerEvent<HTMLDivElement>) => {
    event.preventDefault();
    startRef.current = {
      pointer: horizontal ? event.clientX : event.clientY,
      size: measureValue ? measureValue() : value,
      // 拖拽开始时实测上限（如面板当前高度 × 比例），避免渲染期闭包值滞后
      max: measureMax ? measureMax() : max,
    };
    event.currentTarget.setPointerCapture(event.pointerId);
    setDragging(true);
    document.body.style.userSelect = 'none';
  };

  const handlePointerMove = (event: ReactPointerEvent<HTMLDivElement>) => {
    const start = startRef.current;
    if (!start) return;
    const current = horizontal ? event.clientX : event.clientY;
    // 左缘向左拖变宽、上缘向上拖变高，因此用起始尺寸减去位移量
    const next = clamp(start.size - (current - start.pointer), min, start.max);
    onChange(next);
  };

  const endDrag = () => {
    if (!startRef.current) return;
    startRef.current = null;
    setDragging(false);
    document.body.style.userSelect = '';
  };

  return (
    <div
      role="separator"
      aria-label={rest['aria-label'] ?? (horizontal ? '调整面板宽度' : '调整面板高度')}
      data-testid={horizontal ? 'resize-handle-horizontal' : 'resize-handle-vertical'}
      onPointerDown={handlePointerDown}
      onPointerMove={handlePointerMove}
      onPointerUp={endDrag}
      onPointerCancel={endDrag}
      onDoubleClick={onDoubleClick}
      onPointerEnter={() => setHovered(true)}
      onPointerLeave={() => setHovered(false)}
      style={{
        position: 'absolute',
        ...(horizontal
          ? { left: 0, top: 0, bottom: 0, width: 8, cursor: 'col-resize' }
          : { left: 0, right: 0, top: 0, height: 8, cursor: 'row-resize' }),
        touchAction: 'none',
        zIndex: 10,
      }}
    >
      <div
        aria-hidden
        style={{
          position: 'absolute',
          background: '#1677ff',
          opacity: dragging ? 0.9 : hovered ? 0.45 : 0,
          transition: 'opacity 0.15s ease',
          pointerEvents: 'none',
          ...(horizontal
            ? { left: 3, top: 0, bottom: 0, width: 2 }
            : { left: 0, right: 0, top: 3, height: 2 }),
        }}
      />
    </div>
  );
}
