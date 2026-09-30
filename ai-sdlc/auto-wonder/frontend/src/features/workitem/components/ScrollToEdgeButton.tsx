import { useEffect, useState, useCallback } from 'react';
import { Button } from 'antd';
import { VerticalAlignTopOutlined, VerticalAlignBottomOutlined } from '@ant-design/icons';
import type { CSSProperties } from 'react';

interface ScrollToEdgeButtonProps {
  containerRef: React.RefObject<HTMLElement>;
}

const THRESHOLD = 8;
const BUTTON_SIZE = 22;

const CONTROL_STYLE: CSSProperties = {
  width: BUTTON_SIZE,
  height: BUTTON_SIZE,
  minWidth: BUTTON_SIZE,
  padding: 0,
  fontSize: 12,
  background: 'var(--aw-panel)',
  borderColor: 'var(--aw-border)',
  boxShadow: '0 2px 8px rgba(0, 0, 0, 0.12)',
};

/** 居中在「内容右边框」与「滚动条」之间：
 *  经典滚动条占布局宽度（offsetWidth - clientWidth > 0），叠加滚动条不占（= 0），
 *  两种形态下都等于：滚动条宽 + (右侧轨道 - 按钮) / 2。 */
function computeCenteredRight(el: HTMLElement): number {
  const scrollbarWidth = Math.max(0, el.offsetWidth - el.clientWidth);
  const gutter = parseFloat(getComputedStyle(el).paddingRight) || 0;
  return scrollbarWidth + Math.max(0, (gutter - BUTTON_SIZE) / 2);
}

export function ScrollToEdgeButton({ containerRef }: ScrollToEdgeButtonProps) {
  const [state, setState] = useState<{ scrollable: boolean; atTop: boolean; atBottom: boolean }>({
    scrollable: false,
    atTop: true,
    atBottom: false,
  });
  const [rightOffset, setRightOffset] = useState(0);

  const measure = useCallback(() => {
    const el = containerRef.current;
    if (!el) return;
    const { scrollHeight, clientHeight, scrollTop } = el;
    const scrollable = scrollHeight > clientHeight;
    const atTop = scrollTop <= THRESHOLD;
    const atBottom = scrollTop + clientHeight >= scrollHeight - THRESHOLD;
    setState({ scrollable, atTop, atBottom });
    setRightOffset(computeCenteredRight(el));
  }, [containerRef]);

  useEffect(() => {
    const el = containerRef.current;
    if (!el) return;
    measure();
    el.addEventListener('scroll', measure);
    return () => el.removeEventListener('scroll', measure);
  }, [containerRef, measure]);

  if (!state.scrollable) return null;

  const scrollToTop = () => {
    containerRef.current?.scrollTo({ top: 0, behavior: 'smooth' });
  };

  const scrollToBottom = () => {
    const el = containerRef.current;
    if (el) el.scrollTo({ top: el.scrollHeight, behavior: 'smooth' });
  };

  return (
    <div
      data-testid="scroll-edge-controls"
      style={{
        position: 'absolute',
        right: rightOffset,
        bottom: 12,
        display: 'flex',
        flexDirection: 'column',
        gap: 8,
      }}
    >
      <Button
        shape="circle"
        aria-label="回到顶部"
        title="回到顶部"
        icon={<VerticalAlignTopOutlined />}
        disabled={state.atTop}
        style={CONTROL_STYLE}
        onClick={scrollToTop}
      />
      <Button
        shape="circle"
        aria-label="到达底部"
        title="到达底部"
        icon={<VerticalAlignBottomOutlined />}
        disabled={state.atBottom}
        style={CONTROL_STYLE}
        onClick={scrollToBottom}
      />
    </div>
  );
}
