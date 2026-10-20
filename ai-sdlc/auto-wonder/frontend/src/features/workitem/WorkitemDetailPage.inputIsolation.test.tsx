import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { useAuthStore } from '@/shared/auth/store';

const { renderCounts } = vi.hoisted(() => ({ renderCounts: { header: 0, timeline: 0 } }));

vi.mock('./components/WorkitemHeader', () => ({
  WorkitemHeader: () => {
    renderCounts.header += 1;
    return <div data-testid="probe-workitem-header" />;
  },
}));

vi.mock('./components/UnifiedTimeline', () => ({
  UnifiedTimeline: () => {
    renderCounts.timeline += 1;
    return <div data-testid="probe-unified-timeline" />;
  },
}));

import { WorkitemDetailPage } from './WorkitemDetailPage';

const mockWorkitem = {
  id: '1', workType: 'REQ', title: '跨境支付重构', contentMd: '# 背景\n重构支付',
  templateId: '1', statusNodeId: '1', statusName: '开发中', statusCategory: 'IN_PROGRESS', sdlcId: '10',
  sdlcName: '标准流程', assigneeType: 'AGENT', assigneeRef: '100',
  assigneeName: 'Coder-01', priority: 1, version: 3,
  gmtCreate: '2026-07-01T10:00:00Z', gmtModified: '2026-07-09T12:00:00Z',
};

const mockParticipants = [
  { userId: '100', name: 'Coder-01', role: 'DEV', roleName: '开发', isAgent: true, online: true },
  { userId: '200', name: '张三', role: 'REVIEWER', roleName: '评审', isAgent: false, online: false },
];

function setupHandlers() {
  return [
    http.get('/api/workitems/1', () => HttpResponse.json({
      success: true, code: '0', message: '', traceId: null, data: mockWorkitem,
    })),
    http.get('/api/workitems/1/dispatch-requests', () => HttpResponse.json({ success: true, code: '0', data: [] })),
    http.get('/api/workitems/1/unified-timeline', () => HttpResponse.json({
      success: true, code: '0', message: '', traceId: null,
      data: [{ id: '1', type: 'comment', authorId: '100', authorName: 'Coder-01', authorType: 'AGENT', isAgent: true, content: '已完成', gmtCreate: '2026-07-09T11:00:00Z' }],
    })),
    http.get('/api/workitems/1/participants', () => HttpResponse.json({
      success: true, code: '0', message: '', traceId: null, data: mockParticipants,
    })),
    http.get('/api/workitems/1/mention-candidates', () => HttpResponse.json({
      success: true, code: '0', message: '', traceId: null, data: mockParticipants,
    })),
    http.get('/api/workitems/1/delivery-progress', () => HttpResponse.json({
      success: true, code: '0', message: '', traceId: null,
      data: { steps: [{ stepId: '1', name: '需求分析', status: 'done', executorName: null, subSteps: null }] },
    })),
    http.get('/api/workitems/1/clarification', () => HttpResponse.json({
      success: true, code: '0', message: '', traceId: null, data: null,
    })),
    http.get('/api/workitems/1/artifacts', () => HttpResponse.json({
      success: true, code: '0', message: '', traceId: null, data: [],
    })),
    http.get('/api/workitems/1/requirement-documents', () => HttpResponse.json({
      success: true, code: '0', message: '', traceId: null, data: [],
    })),
    http.get('/api/status-templates/1', () => HttpResponse.json({
      success: true, code: '0', message: '', traceId: null,
      data: { id: 1, workType: 'REQ', name: '需求默认流程', isDefault: true, gmtCreate: '2026-07-01T10:00:00Z', gmtModified: '2026-07-01T10:00:00Z', nodes: [], transitions: [] },
    })),
    http.get('/api/workitems/:id/recovery', () => HttpResponse.json({
      success: true, code: '0', data: { closed: false, executions: [] },
    })),
  ];
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/workitems/1']}>
        <Routes>
          <Route path="/workitems/:id" element={<WorkitemDetailPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('WorkitemDetailPage composer input isolation', () => {
  beforeEach(() => {
    renderCounts.header = 0;
    renderCounts.timeline = 0;
    useAuthStore.getState().clear();
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_WRITE');
    server.use(...setupHandlers());
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('typing a mention query only re-renders the composer, not the page or timeline', async () => {
    renderPage();

    expect(await screen.findByTestId('probe-unified-timeline')).toBeInTheDocument();
    const composer = await screen.findByPlaceholderText('输入评论，键入 @ 选择成员...');
    await act(async () => { await new Promise((resolve) => setTimeout(resolve, 100)); });
    const headerRenders = renderCounts.header;
    const timelineRenders = renderCounts.timeline;
    expect(headerRenders).toBeGreaterThan(0);
    expect(timelineRenders).toBeGreaterThan(0);

    const user = userEvent.setup();
    await user.type(composer, '@Coder');

    expect(await screen.findByRole('menuitem', { name: /Coder-01/ })).toBeInTheDocument();
    await waitFor(() => expect(renderCounts.timeline).toBe(timelineRenders));
    expect(renderCounts.header).toBe(headerRenders);
  });
});
