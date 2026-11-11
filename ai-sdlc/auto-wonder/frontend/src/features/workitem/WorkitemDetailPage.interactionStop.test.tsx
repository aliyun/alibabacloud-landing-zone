import { cleanup, fireEvent, render, screen, waitFor, within, act } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Modal } from 'antd';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { WorkitemDetailPage } from './WorkitemDetailPage';
import { useAuthStore } from '@/shared/auth/store';
import { i18n } from '@/shared/i18n';
import type { TimelineItem } from '@/shared/types/workitem';

const mockWorkitem = {
  id: '1', workType: 'REQ', title: '评论区逐条停止', contentMd: '# 背景',
  templateId: null, statusNodeId: null, statusName: '开发中', sdlcId: '10',
  sdlcName: '标准流程', assigneeType: 'AGENT', assigneeRef: '100',
  assigneeName: 'Coder-01', priority: 1, version: 3,
  gmtCreate: '2026-10-29T06:00:00Z', gmtModified: '2026-10-29T06:00:00Z',
};

function ok<T>(data: T) {
  return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data });
}

const timelineItem: TimelineItem = {
  id: 1,
  type: 'comment',
  authorId: 7,
  authorName: '淘飞',
  authorType: 'HUMAN',
  isAgent: false,
  content: '请检查接口兼容性',
  gmtCreate: '2026-10-29T06:00:00Z',
  interactions: [{
    guidanceId: 9,
    dispatchId: 91,
    executionStatus: 'RUNNING',
    targetAgentId: 40013,
    targetAgentName: 'AW全栈开发',
    status: 'DELIVERED',
    controlEligible: true,
    cancelRequested: false,
    stopPending: false,
    control: { end: { allowed: true }, forceEnd: { allowed: false } },
  }],
};

function pageHandlers(items: TimelineItem[]) {
  return [
    http.get('/api/workitems/1/dispatch-requests', () => ok([])),
    http.get('/api/workitems/1', () => ok(mockWorkitem)),
    http.get('/api/workitems/1/unified-timeline/window', () => ok(items)),
    http.get('/api/workitems/1/participants', () => ok([])),
    http.get('/api/workitems/1/mention-candidates', () => ok([])),
    http.get('/api/workitems/1/delivery-progress', () => ok({ steps: [] })),
    http.get('/api/workitems/1/clarification', () => ok(null)),
    http.get('/api/workitems/1/artifacts', () => ok([])),
    http.get('/api/workitems/1/requirement-documents', () => ok([])),
    http.get('/api/workitems/1/recovery', () => ok({ closed: false, executions: [] })),
    http.get('/api/dispatches/:dispatchId/runtime-trace/events/window', () => ok({ changed: true, lastSeq: 1, events: [] })),
  ];
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/workitems/1']}>
        <Routes>
          <Route path="/workitems/:id" element={<WorkitemDetailPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('WorkitemDetailPage comment interaction stop wiring', () => {
  beforeEach(() => {
    useAuthStore.getState().clear();
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_WRITE');
  });

  afterEach(async () => {
    Modal.destroyAll();
    cleanup();
    await i18n.changeLanguage('zh-CN');
  });

  it('ends only the dispatch bound to the clicked comment interaction', async () => {
    const posts: unknown[] = [];
    server.use(...pageHandlers([timelineItem]),
      http.post('/api/workitems/1/recovery', async ({ request }) => {
        posts.push(await request.json());
        return ok({ closed: false, executions: [] });
      }));

    renderPage();

    const timeline = await screen.findByTestId('workitem-timeline-section');
    await userEvent.click(within(timeline).getByRole('button', { name: '结束本次执行' }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getAllByText('结束执行 #91？').length).toBeGreaterThan(0);
    await userEvent.click(within(dialog).getByRole('button', { name: '结束本次执行' }));

    await waitFor(() => expect(posts).toHaveLength(1));
    expect(posts[0]).toMatchObject({ action: 'cancel', dispatchId: 91, force: false });
    await act(async () => {});
  });

  it('hides the interaction control from read-only members', async () => {
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_ONLY');
    server.use(...pageHandlers([timelineItem]));

    renderPage();

    expect(await screen.findByText('请检查接口兼容性')).toBeInTheDocument();
    const timeline = screen.getByTestId('workitem-timeline-section');
    expect(within(timeline).queryByRole('button', { name: '结束本次执行' })).not.toBeInTheDocument();
  });

  it('shows the interaction control to ADMIN members', async () => {
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'ADMIN');
    server.use(...pageHandlers([timelineItem]));

    renderPage();

    const timeline = await screen.findByTestId('workitem-timeline-section');
    expect(within(timeline).getByRole('button', { name: '结束本次执行' })).toBeInTheDocument();
  });

  it('refetches the timeline and reports the card-local failure when the stop request fails', async () => {
    // 非活动态卡片：避免 2s 快轮询干扰「失败后是否回读时间线」的计数断言。
    const failedItem: TimelineItem = {
      ...timelineItem,
      interactions: [{ ...timelineItem.interactions![0], status: 'FAILED' }],
    };
    let timelineCalls = 0;
    server.use(...pageHandlers([failedItem]));
    server.use(
      http.get('/api/workitems/1/unified-timeline/window', () => {
        timelineCalls += 1;
        return ok([failedItem]);
      }),
      http.post('/api/workitems/1/recovery', () => HttpResponse.json(
        { success: false, code: '500', message: '执行状态已变化', traceId: null, data: null },
        { status: 500 },
      )),
    );

    renderPage();

    const timeline = await screen.findByTestId('workitem-timeline-section');
    await waitFor(() => expect(timelineCalls).toBe(1));
    await userEvent.click(within(timeline).getByRole('button', { name: '结束本次执行' }));
    const dialog = await screen.findByRole('dialog');
    await userEvent.click(within(dialog).getByRole('button', { name: '结束本次执行' }));

    await waitFor(() => expect(within(timeline).getByRole('alert'))
      .toHaveTextContent('结束请求失败。当前状态未确认，请刷新状态后重试。'));
    await waitFor(() => expect(timelineCalls).toBe(2));
  });

  it('keeps pending state per dispatch when two cards stop concurrently', async () => {
    const twoCards: TimelineItem = {
      ...timelineItem,
      interactions: [
        { ...timelineItem.interactions![0], guidanceId: 9, dispatchId: 91 },
        { ...timelineItem.interactions![0], guidanceId: 10, dispatchId: 92, targetAgentName: 'AW代码评审工程师' },
      ],
    };
    const posts: Array<{ dispatchId: number }> = [];
    const resolvers = new Map<number, () => void>();
    server.use(...pageHandlers([twoCards]),
      http.post('/api/workitems/1/recovery', async ({ request }) => {
        const body = await request.json() as { dispatchId: number };
        posts.push(body);
        await new Promise<void>((resolve) => { resolvers.set(body.dispatchId, resolve); });
        return ok({ closed: false, executions: [] });
      }));

    renderPage();

    const timeline = await screen.findByTestId('workitem-timeline-section');
    const stopButtons = () => within(timeline).getAllByRole('button', { name: '结束本次执行' });
    expect(stopButtons()).toHaveLength(2);

    await userEvent.click(stopButtons()[0]);
    let dialogs = await screen.findAllByRole('dialog');
    await userEvent.click(within(dialogs[dialogs.length - 1]).getByRole('button', { name: '结束本次执行' }));
    await waitFor(() => expect(posts).toHaveLength(1));

    // 第一个确认框在请求完成前保持打开并 loading；直接派发点击打开第二张卡片的确认框。
    fireEvent.click(stopButtons()[1]);
    await waitFor(() => expect(screen.getAllByRole('dialog')).toHaveLength(2));
    dialogs = screen.getAllByRole('dialog');
    await userEvent.click(within(dialogs[dialogs.length - 1]).getByRole('button', { name: '结束本次执行' }));
    await waitFor(() => expect(posts).toHaveLength(2));
    expect(posts.map((post) => post.dispatchId)).toEqual([91, 92]);
    await waitFor(() => expect(stopButtons()[0].className).toContain('ant-btn-loading'));
    expect(stopButtons()[1].className).toContain('ant-btn-loading');

    const releaseFirst = resolvers.get(91)!;
    await act(async () => { releaseFirst(); });
    await waitFor(() => expect(stopButtons()[0].className).not.toContain('ant-btn-loading'));
    expect(stopButtons()[1].className).toContain('ant-btn-loading');

    const releaseSecond = resolvers.get(92)!;
    await act(async () => { releaseSecond(); });
    await waitFor(() => expect(stopButtons()[1].className).not.toContain('ant-btn-loading'));
  });
});
