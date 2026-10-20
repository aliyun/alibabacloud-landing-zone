import { act, render } from '@testing-library/react';
import { useState } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { UnifiedTimeline } from './UnifiedTimeline';
import type { TimelineItem } from '@/shared/types/workitem';

const { markdownRenderSpy } = vi.hoisted(() => ({
  markdownRenderSpy: vi.fn<(content: string) => void>(),
}));

vi.mock('@/shared/ui/MarkdownView', () => ({
  MarkdownView: ({ content }: { content: string }) => {
    markdownRenderSpy(content);
    return <div data-testid="markdown-mock">{content}</div>;
  },
}));

function makeComment(id: number, content: string): TimelineItem {
  return {
    id,
    type: 'comment',
    authorId: 10,
    authorName: '蔡何',
    authorType: 'HUMAN',
    isAgent: false,
    content,
    gmtCreate: '2026-08-07T07:00:00Z',
  };
}

describe('UnifiedTimeline parent re-render containment', () => {
  it('does not re-render existing comments when the parent updates with stable props', () => {
    let items = [makeComment(2, '评论 2'), makeComment(1, '评论 1')];
    let bump: () => void = () => undefined;

    function Harness() {
      const [, setTick] = useState(0);
      bump = () => setTick((current) => current + 1);
      return <UnifiedTimeline items={items} />;
    }

    render(<Harness />);
    expect(markdownRenderSpy.mock.calls.map(([content]) => content)).toEqual(['评论 2', '评论 1']);
    markdownRenderSpy.mockClear();

    act(() => { bump(); });
    act(() => { bump(); });
    expect(markdownRenderSpy).not.toHaveBeenCalled();

    items = [makeComment(3, '评论 3'), ...items];
    act(() => { bump(); });

    expect(markdownRenderSpy).toHaveBeenCalledTimes(1);
    expect(markdownRenderSpy).toHaveBeenCalledWith('评论 3');
  });
});
