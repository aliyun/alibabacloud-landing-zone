import React, { useState } from 'react';
import { createRoot } from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { LocaleProvider } from '../src/shared/i18n/LocaleProvider';
import { UnifiedTimeline } from '../src/features/workitem/components/UnifiedTimeline';
import type { TimelineItem } from '../src/shared/types/workitem';

// 工单时间线分批渲染浏览器 fixture：真实 Chrome 验证默认只渲染最近 50 条、
// 加载更早每次展开 50 条直至全部、展开时视口位置稳定、新评论到达头部可见且
// 不重置已展开数量、不足 50 条不显示按钮、中英文按钮文案。
// 无后端/DB：mock 最新在前评论数据，受控重渲染模拟轮询新评论到达。
// Run: node e2e-tests/workitem-timeline-batch/run.cjs

const TOTAL_ITEMS = 120;

function makeItem(seq: number): TimelineItem {
  return {
    id: seq,
    type: 'comment',
    authorId: 10000,
    authorName: '淘飞',
    authorType: 'HUMAN',
    isAgent: false,
    content: `评论 #${seq}`,
    gmtCreate: new Date(Date.UTC(2026, 9, 1, 0, 0, 0) + seq * 1000).toISOString(),
  };
}

// API 最新在前：id 大者为新评论，位于数组头部。
function makeItems(count: number, newest: number): TimelineItem[] {
  const items: TimelineItem[] = [];
  for (let seq = newest; seq > newest - count; seq -= 1) items.push(makeItem(seq));
  return items;
}

function BatchedTimelineFixture() {
  const [items, setItems] = useState<TimelineItem[]>(() => makeItems(TOTAL_ITEMS, TOTAL_ITEMS));
  const addNewComment = () => {
    setItems((current) => [makeItem(Number(current[0].id) + 1), ...current]);
  };
  return (
    <div data-testid="timeline-batch-fixture" style={{ maxWidth: 860 }}>
      <button type="button" data-testid="fixture-add-new-comment" onClick={addNewComment}>
        add new comment
      </button>
      <div data-testid="timeline-scroll-container" style={{ height: 420, overflowY: 'auto' }}>
        <UnifiedTimeline items={items} />
      </div>
    </div>
  );
}

function ShortTimelineFixture() {
  return (
    <div data-testid="timeline-short-fixture" style={{ maxWidth: 860, marginTop: 24 }}>
      <UnifiedTimeline items={makeItems(30, 30)} />
    </div>
  );
}

const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
createRoot(document.getElementById('root')!).render(
  <LocaleProvider>
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/']}>
        <BatchedTimelineFixture />
        <ShortTimelineFixture />
      </MemoryRouter>
    </QueryClientProvider>
  </LocaleProvider>,
);
