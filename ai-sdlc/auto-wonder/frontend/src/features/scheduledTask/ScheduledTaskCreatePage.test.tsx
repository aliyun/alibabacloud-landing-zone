import { beforeEach, describe, expect, it } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { useAuthStore } from '@/shared/auth/store';
import { initialStatusForCreate, ScheduledTaskCreatePage } from './ScheduledTaskCreatePage';

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/scheduled-tasks/new']}>
        <Routes>
          <Route path="/scheduled-tasks/new" element={<ScheduledTaskCreatePage />} />
          <Route path="/scheduled-tasks/:id" element={<div>任务详情</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('ScheduledTaskCreatePage', () => {
  it('keeps a document-bearing task paused until documents are attached', () => {
    expect(initialStatusForCreate('ACTIVE', 1)).toBe('PAUSED');
    expect(initialStatusForCreate('PAUSED', 1)).toBe('PAUSED');
  });
  beforeEach(() => {
    useAuthStore.getState().clear();
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_WRITE');
    server.use(http.get('/api/scheduled-tasks/preview', () => HttpResponse.json({ success: true, code: '0', message: '', data: ['2026-08-12T18:00:00Z'] })));
  });

  it('submits canonical cron and selected squad agent', async () => {
    let lastBody: Record<string, unknown> | null = null;
    server.use(
      http.get('/api/squads', () => HttpResponse.json({ success: true, code: '0', message: '', data: [{ id: 1, name: '研发小队', description: '', memberCount: 1, gmtCreate: '' }] })),
      http.get('/api/squads/1/members', () => HttpResponse.json({ success: true, code: '0', message: '', data: [{ agentId: 11, agentName: '回归工程师', roleCode: 'TEST', sdlcId: null }] })),
      http.post('/api/scheduled-tasks', async ({ request }) => {
        lastBody = await request.json() as Record<string, unknown>;
        return HttpResponse.json({ success: true, code: '0', message: '', data: { id: 9, ...lastBody, status: 'ACTIVE', version: 0 } });
      }),
    );

    renderPage();
    const user = userEvent.setup();
    await user.type(screen.getByLabelText('任务名称'), '主干夜间回归');
    await user.type(screen.getByLabelText('任务指令'), '每天对主干进行回归并汇总结果。');
    await user.click(screen.getByLabelText('小队'));
    await user.click(await screen.findByText('研发小队'));
    await user.click(screen.getByLabelText('首个数字人'));
    await user.click(await screen.findByText('回归工程师'));
    await user.click(screen.getByText('每天'));
    await user.click(screen.getByRole('button', { name: '创建并启用' }));

    await waitFor(() => expect(lastBody).toMatchObject({
      name: '主干夜间回归', squadId: 1, initialAgentId: 11,
      scheduleType: 'CRON', cronExpression: '0 0 2 * * *',
      timezone: 'Asia/Shanghai', sessionMode: 'ISOLATED', overlapPolicy: 'SKIP',
    }));
    expect(await screen.findByText('任务详情')).toBeInTheDocument();
  });

  it('renders the server-authoritative preview and selected Agent SDLC context', async () => {
    server.use(
      http.get('/api/squads', () => HttpResponse.json({ success: true, code: '0', message: '', data: [{ id: 1, name: '研发小队', description: '', memberCount: 1, gmtCreate: '' }] })),
      http.get('/api/squads/1/members', () => HttpResponse.json({ success: true, code: '0', message: '', data: [{ agentId: 11, agentName: '回归工程师', roleCode: 'TEST', sdlcName: '回归流程', sdlcSteps: [{ id: 100, stepOrder: 1, name: '执行回归', handlerType: 'AGENT', handlerRoleRef: null }] }] })),
      http.get('/api/scheduled-tasks/preview', ({ request }) => {
        const params = new URL(request.url).searchParams;
        expect(params.get('cronExpression')).toBe('0 0 2 * * *');
        expect(params.get('timezone')).toBe('Asia/Shanghai');
        expect(params.get('count')).toBe('5');
        return HttpResponse.json({ success: true, code: '0', message: '', data: ['2026-08-12T18:00:00Z', '2026-08-13T18:00:00Z'] });
      }),
    );
    renderPage();
    const expected = new Date('2026-08-12T18:00:00Z').toLocaleString('zh-CN');
    expect(await screen.findByText(expected)).toBeInTheDocument();
  });
});
