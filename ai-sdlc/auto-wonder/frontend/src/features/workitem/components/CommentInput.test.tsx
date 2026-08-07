import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';

vi.mock('../hooks', () => ({
  useAddComment: () => ({ mutate: vi.fn(), isPending: false }),
}));

vi.mock('@/shared/auth/useAccessCommand', () => ({
  useAccessCommand: () => vi.fn((_required: unknown, _action: string, command: () => unknown) => command()),
}));

import { CommentInput } from './CommentInput';
import type { Participant } from '@/shared/types/workitem';

function buildCandidates(count: number): Participant[] {
  return Array.from({ length: count }, (_, index) => ({
    userId: index + 1,
    name: `成员${index + 1}`,
    role: 'DEV',
    roleName: '开发',
    isAgent: index % 2 === 0,
    online: true,
  }));
}

function openMentionMenu() {
  const textarea = screen.getByPlaceholderText('输入评论，键入 @ 选择成员...') as HTMLTextAreaElement;
  fireEvent.change(textarea, { target: { value: '@', selectionStart: 1 } });
  fireEvent.keyUp(textarea);
  return screen.getByRole('menu');
}

describe('CommentInput mention menu', () => {
  it('limits the popup height and enables scrolling with many candidates', () => {
    render(<CommentInput workitemId="1" mentionCandidates={buildCandidates(15)} />);
    const menu = openMentionMenu();
    expect(menu.style.overflowY).toBe('auto');
    const maxHeight = Number.parseInt(menu.style.maxHeight, 10);
    expect(maxHeight).toBeGreaterThan(0);
    expect(maxHeight).toBeLessThanOrEqual(400);
  });

  it('still renders every candidate inside the scrollable menu', () => {
    render(<CommentInput workitemId="1" mentionCandidates={buildCandidates(15)} />);
    openMentionMenu();
    expect(screen.getAllByRole('menuitem')).toHaveLength(15);
  });
});
