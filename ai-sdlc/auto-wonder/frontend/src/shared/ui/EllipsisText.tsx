import { Tooltip } from 'antd';
import { useLayoutEffect, useRef, useState, type CSSProperties, type ReactNode } from 'react';

interface Props {
  children: ReactNode;
  /** 溢出时悬停展示的内容，默认为 children 本身 */
  tooltip?: ReactNode;
  /** 省略行数，默认单行 */
  lines?: number;
  placement?: 'top' | 'topLeft' | 'topRight' | 'bottom' | 'bottomLeft' | 'bottomRight';
  style?: CSSProperties;
}

/** 单行/多行省略文本；仅当内容实际被截断时才显示悬停提示。 */
export function EllipsisText({ children, tooltip, lines = 1, placement = 'top', style }: Props) {
  const ref = useRef<HTMLSpanElement>(null);
  const [overflow, setOverflow] = useState(false);

  useLayoutEffect(() => {
    const el = ref.current;
    if (!el) return;
    const measure = () => {
      // rc-table 重建行/隐藏时 RO 会以 0×0 触发，会把已判定的溢出状态误清掉，这里直接忽略。
      if (el.clientWidth === 0 && el.clientHeight === 0) return;
      const next = lines === 1 ? el.scrollWidth > el.clientWidth : el.scrollHeight > el.clientHeight;
      setOverflow(prev => (prev === next ? prev : next));
    };
    measure();
    if (typeof ResizeObserver === 'undefined') return;
    const ro = new ResizeObserver(measure);
    ro.observe(el);
    return () => ro.disconnect();
  }, [lines]);

  const span = (
    <span
      ref={ref}
      style={{
        display: 'block',
        minWidth: 0,
        ...(lines === 1
          ? { overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }
          : { overflow: 'hidden', display: '-webkit-box', WebkitBoxOrient: 'vertical', WebkitLineClamp: lines }),
        ...style,
      }}
    >
      {children}
    </span>
  );

  return overflow ? (
    <Tooltip title={tooltip ?? children} placement={placement}>
      {span}
    </Tooltip>
  ) : span;
}
