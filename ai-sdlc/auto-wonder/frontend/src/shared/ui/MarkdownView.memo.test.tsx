import { useState } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { act, render, screen } from '@testing-library/react';
import type { Artifact } from '@/shared/types/workitem';

const { markdownRenderMock } = vi.hoisted(() => ({ markdownRenderMock: vi.fn() }));

vi.mock('react-markdown', () => ({
  default: ({ children }: { children?: unknown }) => {
    markdownRenderMock();
    return <div data-testid="markdown-body">{String(children ?? '')}</div>;
  },
}));

import { MarkdownView } from './MarkdownView';

describe('MarkdownView render isolation', () => {
  beforeEach(() => {
    markdownRenderMock.mockClear();
  });

  it('skips markdown parsing when the parent re-renders with stable props', () => {
    const mentionNames = ['蔡何'];
    const artifacts: Artifact[] = [];

    function Parent() {
      const [tick, setTick] = useState(0);
      return (
        <div>
          <button type="button" onClick={() => setTick((current) => current + 1)}>tick</button>
          <span data-testid="tick">{tick}</span>
          <MarkdownView content="稳定内容 @蔡何" mentionNames={mentionNames} artifacts={artifacts} />
        </div>
      );
    }

    render(<Parent />);
    expect(markdownRenderMock).toHaveBeenCalledTimes(1);

    act(() => { screen.getByRole('button', { name: 'tick' }).click(); });
    act(() => { screen.getByRole('button', { name: 'tick' }).click(); });

    expect(screen.getByTestId('tick')).toHaveTextContent('2');
    expect(markdownRenderMock).toHaveBeenCalledTimes(1);
  });

  it('re-renders when the content prop changes', () => {
    const { rerender } = render(<MarkdownView content="一" />);
    expect(markdownRenderMock).toHaveBeenCalledTimes(1);

    rerender(<MarkdownView content="二" />);

    expect(markdownRenderMock).toHaveBeenCalledTimes(2);
    expect(screen.getByTestId('markdown-body')).toHaveTextContent('二');
  });
});
