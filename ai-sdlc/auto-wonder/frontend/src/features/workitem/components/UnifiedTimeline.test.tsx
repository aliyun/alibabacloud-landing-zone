import { i18n } from '@/shared/i18n';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { act, render, screen } from '@testing-library/react';
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

describe('UnifiedTimeline batching and render containment', () => {
  // 后端 unified-timeline 按 gmtCreate DESC, id DESC 返回，数组头是最新评论。
  function makeManyComments(count: number): TimelineItem[] {
    return Array.from({ length: count }, (_, index) => makeComment({
      id: count - index,
      content: `评论 ${count - index}`,
    }));
  }

  it('renders only the newest 50 entries and loads earlier ones 50 at a time', async () => {
    render(<UnifiedTimeline items={makeManyComments(120)} />);

    const initialEntries = screen.getAllByTestId('timeline-item');
    expect(initialEntries).toHaveLength(50);
    expect(initialEntries[0]).toHaveTextContent('评论 120');
    expect(initialEntries[49]).toHaveTextContent('评论 71');
    expect(screen.queryByText('评论 70')).not.toBeInTheDocument();
    expect(screen.queryByText('评论 1')).not.toBeInTheDocument();

    const loadEarlier = screen.getByTestId('timeline-load-earlier');
    expect(loadEarlier).toHaveTextContent('加载更早的 50 条评论');
    await userEvent.click(loadEarlier);

    const expandedEntries = screen.getAllByTestId('timeline-item');
    expect(expandedEntries).toHaveLength(100);
    expect(expandedEntries[0]).toHaveTextContent('评论 120');
    expect(screen.getByText('评论 21')).toBeInTheDocument();
    expect(screen.queryByText('评论 20')).not.toBeInTheDocument();
    expect(screen.getByTestId('timeline-load-earlier')).toHaveTextContent('加载更早的 20 条评论');

    await userEvent.click(screen.getByTestId('timeline-load-earlier'));

    expect(screen.getAllByTestId('timeline-item')).toHaveLength(120);
    expect(screen.getByText('评论 1')).toBeInTheDocument();
    expect(screen.queryByTestId('timeline-load-earlier')).not.toBeInTheDocument();
  });

  it('keeps the expanded window and shows a newly arrived comment at the head', async () => {
    const { rerender } = render(<UnifiedTimeline items={makeManyComments(120)} />);
    await userEvent.click(screen.getByTestId('timeline-load-earlier'));
    expect(screen.getAllByTestId('timeline-item')).toHaveLength(100);

    rerender(<UnifiedTimeline items={makeManyComments(121)} />);

    const entries = screen.getAllByTestId('timeline-item');
    expect(entries).toHaveLength(100);
    expect(entries[0]).toHaveTextContent('评论 121');
    expect(screen.getByText('评论 22')).toBeInTheDocument();
    expect(screen.queryByText('评论 21')).not.toBeInTheDocument();
  });

  it('does not offer the load-earlier control for timelines within the first page', () => {
    render(<UnifiedTimeline items={makeManyComments(50)} />);

    expect(screen.getAllByTestId('timeline-item')).toHaveLength(50);
    expect(screen.getByText('评论 50')).toBeInTheDocument();
    expect(screen.getByText('评论 1')).toBeInTheDocument();
    expect(screen.queryByTestId('timeline-load-earlier')).not.toBeInTheDocument();
  });

  it('marks every rendered entry as content-visibility contained', () => {
    render(<UnifiedTimeline items={makeManyComments(2)} />);

    for (const item of screen.getAllByTestId('timeline-item')) {
      const style = item.getAttribute('style') ?? '';
      expect(style).toContain('content-visibility: auto');
      expect(style).toContain('contain-intrinsic-size: auto 120px');
    }
  });

  it('renders the load-earlier control after the visible entries', () => {
    render(<UnifiedTimeline items={makeManyComments(120)} />);

    const entries = screen.getAllByTestId('timeline-item');
    const control = screen.getByTestId('timeline-load-earlier');
    expect(entries[entries.length - 1].compareDocumentPosition(control) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
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

it('updates cached finite system events and keeps legacy, unknown and authored same-looking content raw', async () => {
  const base = { type: 'system' as const, authorId: 10, authorName: '工单已创建', authorType: 'HUMAN', isAgent: false, gmtCreate: '2026-08-07T07:00:00Z' };
  const items: TimelineItem[] = [
    { ...base, id: 1, content: '工单已创建: <b>raw</b>', eventType: 'CREATE', toValDisplay: '<b>raw</b>' },
    { ...base, id: 2, content: '工单已创建', eventType: 'UNKNOWN' },
    { ...base, id: 3, content: '工单已创建' },
    { ...base, id: 4, type: 'comment', content: '工单已创建' },
  ];
  render(<UnifiedTimeline items={items} />);
  await act(async () => { await i18n.changeLanguage('en-US'); });
  expect(screen.getByText('Work item created: <b>raw</b>')).toBeInTheDocument();
  expect(screen.getAllByText('工单已创建').length).toBeGreaterThanOrEqual(3);
  expect(document.querySelector('b')).toBeNull();
  await act(async () => { await i18n.changeLanguage('zh-CN'); });
});

it('renders fresh Aone source and raw operator parameters safely while legacy same-prefix content remains raw', async () => {
  const base = { id: 1, type: 'system' as const, authorId: null, authorName: null, authorType: 'SYSTEM', isAgent: false, content: '已从 Aone 工单导入', gmtCreate: '2026-08-07T07:00:00Z', sourceProvider: 'AONE', sourceExternalWorkitemId: '<b>42</b>', sourceExternalUrl: 'https://aone.example/42' };
  render(<UnifiedTimeline items={[{ ...base, eventType: 'AONE_IMPORT' }, { ...base, id: 2, content: '已从 Aone 工单 raw legacy' }, { ...base, id: 3, eventType: 'STATUS_CHANGE', fromValDisplay: '工单已创建', toValDisplay: '<b>raw</b>', operatorDisplayName: '操作人 <b>raw</b>' }]} />);
  await act(async () => { await i18n.changeLanguage('en-US'); });
  expect(screen.getByRole('link', { name: '<b>42</b>' })).toHaveAttribute('href', 'https://aone.example/42');
  expect(screen.getByText('Imported from Aone work item', { exact: false })).toBeInTheDocument();
  expect(screen.getByText('已从 Aone 工单 raw legacy')).toBeInTheDocument();
  expect(screen.getByText('Work item status changed: 工单已创建 → <b>raw</b> (operator: 操作人 <b>raw</b>)')).toBeInTheDocument();
  expect(document.querySelector('b')).toBeNull();
  await act(async () => { await i18n.changeLanguage('zh-CN'); });
});

it('switches held external fallback names and values without a new query or losing a draft, preserving authored collisions', async () => {
  const { QueryClient, QueryClientProvider } = await import('@tanstack/react-query');
  const { useUnifiedTimeline } = await import('../hooks');
  const { server } = await import('@/test/mocks/server');
  const { http, HttpResponse } = await import('msw');
  let requests = 0;
  const fallback = { provider: 'AONE', subjectId: '<b>123456789012345</b>', displayName: null };
  const raw = 'Aone 用户（staffId: <b>123456789012345</b>）';
  const items = [
    makeComment({ id: 601, authorName: raw, authorType: 'EXTERNAL', ...{ authorNameFallback: fallback } }),
    makeComment({ id: 602, authorName: raw, authorType: 'EXTERNAL' }),
    makeComment({ id: 603, authorName: '<b>authored</b>', authorType: 'EXTERNAL', ...{ authorNameFallback: { ...fallback, displayName: '<b>authored</b>' } } }),
    { ...makeComment({ id: 604 }), type: 'system' as const, eventType: 'EXTERNAL_BUSINESS_OWNER_CHANGE', fromValDisplay: raw, toValDisplay: raw, ...{ fromValNameFallback: fallback, toValNameFallback: fallback } },
  ];
  server.use(http.get('/api/workitems/601/unified-timeline', () => {
    requests += 1; return HttpResponse.json({ success: true, code: '0', message: '', data: items });
  }));
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  function Held() { const { data = [] } = useUnifiedTimeline(601); return <><input aria-label="raw draft" defaultValue="draft" /><UnifiedTimeline items={data} /></>; }
  const view = render(<QueryClientProvider client={client}><Held /></QueryClientProvider>);
  await screen.findAllByText(raw);
  await userEvent.type(screen.getByRole('textbox', { name: 'raw draft' }), ' preserved');
  await act(async () => { await i18n.changeLanguage('en-US'); });
  expect(screen.getByText('Aone user (staffId: <b>123456789012345</b>)')).toBeInTheDocument();
  expect(screen.getByText('External business owner changed: Aone user (staffId: <b>123456789012345</b>) → Aone user (staffId: <b>123456789012345</b>)')).toBeInTheDocument();
  expect(screen.getByText(raw)).toBeInTheDocument();
  expect(screen.getByText('<b>authored</b>')).toBeInTheDocument();
  expect(view.container.querySelector('b')).toBeNull();
  expect(screen.getByRole('textbox', { name: 'raw draft' })).toHaveValue('draft preserved');
  expect(requests).toBe(1);
  await act(async () => { await i18n.changeLanguage('zh-CN'); });
  expect(screen.getAllByText(raw)).toHaveLength(2);
  expect(requests).toBe(1);
  view.unmount(); client.clear();
});
