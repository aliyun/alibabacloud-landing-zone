import { useEffect, useState, useCallback } from 'react';
import { FloatButton } from 'antd';
import { VerticalAlignTopOutlined, VerticalAlignBottomOutlined } from '@ant-design/icons';

interface ScrollToEdgeButtonProps {
  containerRef: React.RefObject<HTMLElement>;
}

const THRESHOLD = 8;

export function ScrollToEdgeButton({ containerRef }: ScrollToEdgeButtonProps) {
  const [state, setState] = useState<{ scrollable: boolean; atTop: boolean; atBottom: boolean }>({
    scrollable: false,
    atTop: true,
    atBottom: false,
  });

  const measure = useCallback(() => {
    const el = containerRef.current;
    if (!el) return;
    const { scrollHeight, clientHeight, scrollTop } = el;
    const scrollable = scrollHeight > clientHeight;
    const atTop = scrollTop <= THRESHOLD;
    const atBottom = scrollTop + clientHeight >= scrollHeight - THRESHOLD;
    setState({ scrollable, atTop, atBottom });
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
    <FloatButton.Group
      style={{ right: 'calc(clamp(340px, 28vw, 420px) + 24px)', bottom: 24 }}
    >
      <FloatButton
        icon={<span title="回到顶部"><VerticalAlignTopOutlined /></span>}
        disabled={state.atTop}
        onClick={scrollToTop}
      />
      <FloatButton
        icon={<span title="到达底部"><VerticalAlignBottomOutlined /></span>}
        disabled={state.atBottom}
        onClick={scrollToBottom}
      />
    </FloatButton.Group>
  );
}
