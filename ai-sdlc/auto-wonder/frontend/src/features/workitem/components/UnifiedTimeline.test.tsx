import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { UnifiedTimeline } from './UnifiedTimeline';
import { copyTextToClipboard } from '@/shared/lib/clipboard';
import type { Participant, TimelineItem } from '@/shared/types/workitem';

vi.mock('@/shared/lib/clipboard', () => ({
  copyTextToClipboard: vi.fn(),
}));

const copyMock = vi.mocked(copyTextToClipboard);

function makeComment(overrides: Partial<TimelineItem>): TimelineItem {
  return {
    id: 1,
    type: 'comment',
    authorId: 10,
    authorName: '蔡何',
    authorType: 'HUMAN',
    isAgent: false,
    content: '评论内容',
    gmtCreate: '2026-08-07T07:00:00Z',
    ...overrides,
  };
}

describe('UnifiedTimeline copy entries', () => {
  beforeEach(() => {
    copyMock.mockReset();
    copyMock.mockResolvedValue(true);
  });

  it('copies only the clicked main comment, not other comments', async () => {
    render(
      <UnifiedTimeline
        items={[
          makeComment({ id: 1, content: '第一条评论正文' }),
          makeComment({ id: 2, content: '第二条评论正文' }),
        ]}
      />,
    );

    const [firstCopy] = screen.getAllByRole('button', { name: '复制评论' });
    await userEvent.click(firstCopy);
    await userEvent.click(await screen.findByText('复制原始 Markdown'));

    expect(copyMock).toHaveBeenCalledTimes(1);
    expect(copyMock).toHaveBeenCalledWith('第一条评论正文');
  });

  it('copies only the clicked agent reply, not the parent comment', async () => {
    render(
      <UnifiedTimeline
        items={[
          makeComment({
            id: 1,
            content: '父评论正文',
            interactions: [{
              guidanceId: 9,
              targetAgentId: 40013,
              targetAgentName: '全栈开发',
              status: 'APPLIED',
              replyContent: '数字人回复正文',
              repliedAt: '2026-08-07T08:00:00Z',
            }],
          }),
        ]}
      />,
    );

    await userEvent.click(screen.getByRole('button', { name: '复制回复' }));
    await userEvent.click(await screen.findByText('复制原始 Markdown'));

    expect(copyMock).toHaveBeenCalledTimes(1);
    expect(copyMock).toHaveBeenCalledWith('数字人回复正文');
  });

  it('hides the copy entry for comments with empty content', () => {
    render(
      <UnifiedTimeline
        items={[makeComment({ id: 1, content: '   ' })]}
      />,
    );

    expect(screen.queryByRole('button', { name: '复制评论' })).not.toBeInTheDocument();
  });
});

describe('UnifiedTimeline null participant names', () => {
  it('renders comments without crashing when a participant name is null', () => {
    const participants: Participant[] = [
      {
        userId: 10000,
        targetType: 'HUMAN',
        name: '蔡何',
        displayId: '10000',
        role: 'HUMAN',
        roleName: '真人',
        isAgent: false,
        online: false,
      },
      {
        userId: 40013,
        targetType: 'AGENT',
        name: null as unknown as string,
        displayId: '40013',
        role: 'AGENT',
        roleName: '开发小队成员',
        isAgent: true,
        online: false,
      },
    ];

    render(
      <UnifiedTimeline
        items={[makeComment({ content: '请 @蔡何 确认结论' })]}
        participants={participants}
      />,
    );

    expect(screen.getByText('@蔡何')).toBeInTheDocument();
    expect(screen.getByText(/确认结论/)).toBeInTheDocument();
  });
});

describe('UnifiedTimeline operator display', () => {
  function makeSystemItem(overrides: Partial<TimelineItem>): TimelineItem {
    return {
      id: 100,
      type: 'system',
      authorId: 10000,
      authorName: '蔡何(10000)',
      authorType: 'HUMAN',
      isAgent: false,
      content: '工单状态已变更: verifying → closed （操作人：蔡何(10000)）',
      gmtCreate: '2026-08-07T07:00:00Z',
      ...overrides,
    };
  }

  it('renders the operator on status change and assignment events', () => {
    render(
      <UnifiedTimeline
        items={[
          makeSystemItem({ id: 100 }),
          makeSystemItem({
            id: 101,
            authorId: 40015,
            authorName: 'AW测试工程师(40015)',
            authorType: 'AGENT',
            isAgent: true,
            content: '交付负责人已变更: AW测试工程师(40015) → 蔡何(10000) （操作人：AW测试工程师(40015)）',
          }),
        ]}
      />,
    );

    expect(screen.getByText('工单状态已变更: verifying → closed （操作人：蔡何(10000)）')).toBeInTheDocument();
    expect(screen.getByText('交付负责人已变更: AW测试工程师(40015) → 蔡何(10000) （操作人：AW测试工程师(40015)）')).toBeInTheDocument();
  });
});
