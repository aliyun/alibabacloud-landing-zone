import { describe, expect, it } from 'vitest';
import { act, render, screen } from '@testing-library/react';
import { useState } from 'react';
import { UnifiedTimeline } from './UnifiedTimeline';
import type { TimelineItem } from '@/shared/types/workitem';

// 性能对照开关：默认不跑，交付自测时用 VITE_AW_TIMELINE_PERF=1 单独执行，
// 同一文件可复制到基线 worktree 运行以获得 before/after 数据。
const perfEnabled = import.meta.env.VITE_AW_TIMELINE_PERF === '1';

function makeItems(count: number): TimelineItem[] {
  // 与后端一致：最新在前。
  return Array.from({ length: count }, (_, index) => ({
    id: count - index,
    type: 'comment',
    authorId: 10,
    authorName: '蔡何',
    authorType: 'HUMAN',
    isAgent: false,
    content: `第 ${count - index} 条评论：**重点** 与 [链接](https://example.com/${count - index})`,
    gmtCreate: '2026-08-07T07:00:00Z',
  }));
}

describe.runIf(perfEnabled)('UnifiedTimeline 120-comment performance evidence', () => {
  it('reports mount cost and parent re-render cost with stable timeline props', () => {
    const items = makeItems(120);
    let bump: () => void = () => undefined;

    function Harness() {
      const [tick, setTick] = useState(0);
      bump = () => setTick((current) => current + 1);
      return (
        <div>
          <span data-testid="tick">{tick}</span>
          <UnifiedTimeline items={items} />
        </div>
      );
    }

    const mountStart = performance.now();
    const view = render(<Harness />);
    const mountMs = performance.now() - mountStart;
    const renderedComments = view.container.querySelectorAll('.aw-markdown').length;

    const rerenderStart = performance.now();
    for (let index = 0; index < 10; index += 1) {
      act(() => { bump(); });
    }
    const rerenderMs = performance.now() - rerenderStart;

    console.log(
      `[timeline-perf] items=120 mount=${mountMs.toFixed(1)}ms rerender10=${rerenderMs.toFixed(1)}ms renderedComments=${renderedComments}`,
    );
    expect(screen.getByTestId('tick')).toHaveTextContent('10');
    expect(renderedComments).toBe(50);
    expect(rerenderMs).toBeLessThan(500);
  });
});
