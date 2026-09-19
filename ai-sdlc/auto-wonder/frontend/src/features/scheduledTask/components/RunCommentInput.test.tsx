import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';

let mockCandidates: ScheduledRunMentionCandidate[] = [];
const mentionCandidatesMock = vi.fn();

vi.mock('../hooks', () => ({
  useScheduledTaskRunMentionCandidates: (...args: unknown[]) => mentionCandidatesMock(...args),
}));

import { RunCommentInput } from './RunCommentInput';
import type { ScheduledRunCommentBody, ScheduledRunMentionCandidate } from '../types';

function buildCandidates(): ScheduledRunMentionCandidate[] {
  return [
    { userId: 12, targetType: 'AGENT', name: '缺陷分析数字人', displayId: '40013', isAgent: true, online: true, mentionable: true, mentionDisabledReason: null },
    { userId: 21, targetType: 'AGENT', name: '归档数字人', displayId: '40021', isAgent: true, online: false, mentionable: false, mentionDisabledReason: '不在本次运行的冻结快照中，无法 @ 触发执行' },
    { userId: 5, targetType: 'HUMAN', name: '张三', displayId: null, isAgent: false, online: true, mentionable: true, mentionDisabledReason: null },
  ];
}

function getTextArea(): HTMLTextAreaElement {
  return screen.getByPlaceholderText(/评论本次运行/) as HTMLTextAreaElement;
}

function openMentionMenu(value: string): HTMLTextAreaElement {
  const textarea = getTextArea();
  fireEvent.change(textarea, { target: { value, selectionStart: value.length } });
  fireEvent.keyUp(textarea);
  return textarea;
}

function submitPayload(onSubmit: ReturnType<typeof vi.fn>): ScheduledRunCommentBody {
  return onSubmit.mock.calls[0][0] as ScheduledRunCommentBody;
}

beforeEach(() => {
  mockCandidates = buildCandidates();
  mentionCandidatesMock.mockReset();
  mentionCandidatesMock.mockImplementation(() => ({ data: mockCandidates, isLoading: false }));
});

describe('RunCommentInput mention menu', () => {
  it('loads mention candidates only after the @ trigger appears', () => {
    render(<RunCommentInput runId={10482} onSubmit={vi.fn()} />);
    expect(mentionCandidatesMock).toHaveBeenLastCalledWith(10482, null, false);
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();

    openMentionMenu('@');
    expect(mentionCandidatesMock).toHaveBeenLastCalledWith(10482, '', true);
    expect(screen.getByRole('menu')).toBeInTheDocument();
  });

  it('selects a frozen agent with Enter and submits it as targetAgentIds', () => {
    const onSubmit = vi.fn();
    render(<RunCommentInput runId={10482} onSubmit={onSubmit} />);
    const textarea = openMentionMenu('@');

    const items = screen.getAllByRole('menuitem');
    expect(items).toHaveLength(3);
    expect(items[0]).toHaveAttribute('aria-current', 'true');

    fireEvent.keyDown(textarea, { key: 'Enter' });
    expect(textarea.value).toBe('@缺陷分析数字人 ');
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '发送' }));
    expect(onSubmit).toHaveBeenCalledTimes(1);
    expect(submitPayload(onSubmit)).toEqual({
      contentMd: '@缺陷分析数字人',
      targetAgentIds: [12],
      targetHumanIds: [],
    });
  });

  it('selects a human with the mouse and only submits targetHumanIds', () => {
    mockCandidates = [
      { userId: 5, targetType: 'HUMAN', name: '张三', displayId: null, isAgent: false, online: true, mentionable: true, mentionDisabledReason: null },
    ];
    const onSubmit = vi.fn();
    render(<RunCommentInput runId={10482} onSubmit={onSubmit} />);
    openMentionMenu('@');

    fireEvent.click(screen.getByRole('menuitem', { name: /张三/ }));
    expect(getTextArea().value).toBe('@张三 ');

    fireEvent.click(screen.getByRole('button', { name: '发送' }));
    expect(submitPayload(onSubmit)).toEqual({
      contentMd: '@张三',
      targetAgentIds: [],
      targetHumanIds: [5],
    });
  });

  it('greys out non-frozen participants with a reason and skips them in keyboard navigation', () => {
    const onSubmit = vi.fn();
    render(<RunCommentInput runId={10482} onSubmit={onSubmit} />);
    const textarea = openMentionMenu('@');

    const items = screen.getAllByRole('menuitem');
    const disabledItem = items[1];
    expect(disabledItem).toBeDisabled();
    expect(disabledItem).toHaveAttribute('aria-disabled', 'true');
    expect(disabledItem.textContent).toContain('不在本次运行的冻结快照中，无法 @ 触发执行');
    expect(disabledItem).not.toHaveAttribute('aria-current');

    fireEvent.click(disabledItem);
    expect(textarea.value).toBe('@');

    fireEvent.keyDown(textarea, { key: 'ArrowDown' });
    const afterArrowDown = screen.getAllByRole('menuitem');
    expect(afterArrowDown[1]).not.toHaveAttribute('aria-current');
    expect(afterArrowDown[2]).toHaveAttribute('aria-current', 'true');

    fireEvent.keyDown(textarea, { key: 'Enter' });
    expect(textarea.value).toBe('@张三 ');

    fireEvent.click(screen.getByRole('button', { name: '发送' }));
    expect(submitPayload(onSubmit)).toEqual({
      contentMd: '@张三',
      targetAgentIds: [],
      targetHumanIds: [5],
    });
  });

  it('wraps around with ArrowUp and closes the menu with Escape', () => {
    render(<RunCommentInput runId={10482} onSubmit={vi.fn()} />);
    const textarea = openMentionMenu('@');
    expect(screen.getAllByRole('menuitem')[0]).toHaveAttribute('aria-current', 'true');

    fireEvent.keyDown(textarea, { key: 'ArrowUp' });
    const items = screen.getAllByRole('menuitem');
    expect(items[items.length - 1]).toHaveAttribute('aria-current', 'true');

    fireEvent.keyDown(textarea, { key: 'Escape' });
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
    expect(textarea.value).toBe('@');
  });

  it('filters candidates by the typed query and forwards it to the candidate endpoint', () => {
    render(<RunCommentInput runId={10482} onSubmit={vi.fn()} />);
    openMentionMenu('@张');
    expect(mentionCandidatesMock).toHaveBeenLastCalledWith(10482, '张', true);

    const items = screen.getAllByRole('menuitem');
    expect(items).toHaveLength(1);
    expect(items[0].textContent).toContain('张三');
  });

  it('submits a plain comment with empty targets', () => {
    const onSubmit = vi.fn();
    render(<RunCommentInput runId={10482} onSubmit={onSubmit} />);
    const textarea = getTextArea();

    fireEvent.change(textarea, { target: { value: '请补充失败日志', selectionStart: 7 } });
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '发送' }));
    expect(submitPayload(onSubmit)).toEqual({
      contentMd: '请补充失败日志',
      targetAgentIds: [],
      targetHumanIds: [],
    });
    expect(textarea.value).toBe('');
  });

  it('drops the target once the mention text is removed before submitting', () => {
    const onSubmit = vi.fn();
    render(<RunCommentInput runId={10482} onSubmit={onSubmit} />);
    const textarea = openMentionMenu('@');
    fireEvent.keyDown(textarea, { key: 'Enter' });
    expect(textarea.value).toBe('@缺陷分析数字人 ');

    fireEvent.change(textarea, { target: { value: '好的', selectionStart: 2 } });
    fireEvent.click(screen.getByRole('button', { name: '发送' }));

    expect(submitPayload(onSubmit)).toEqual({
      contentMd: '好的',
      targetAgentIds: [],
      targetHumanIds: [],
    });
  });

  it('sends the comment with Enter when the mention menu is closed', () => {
    const onSubmit = vi.fn();
    render(<RunCommentInput runId={10482} onSubmit={onSubmit} />);
    const textarea = getTextArea();

    fireEvent.change(textarea, { target: { value: '继续观察', selectionStart: 4 } });
    fireEvent.keyDown(textarea, { key: 'Enter' });

    expect(submitPayload(onSubmit)).toEqual({
      contentMd: '继续观察',
      targetAgentIds: [],
      targetHumanIds: [],
    });
  });
});
