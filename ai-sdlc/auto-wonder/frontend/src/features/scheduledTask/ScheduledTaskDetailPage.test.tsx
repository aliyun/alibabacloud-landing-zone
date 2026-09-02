import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { ScheduledTaskDetailPage } from './ScheduledTaskDetailPage';

describe('ScheduledTaskDetailPage', () => {
  it('shows task policy and paged run history', async () => {
    server.use(
      http.get('/api/scheduled-tasks/901', () => HttpResponse.json({ success: true, code: '0', message: '', data: { id: 901, name: '主干夜间回归', instructionMd: '执行回归', squadId: 2, initialAgentId: 5, status: 'ACTIVE', scheduleType: 'CRON', cronExpression: '0 0 2 * * *', timezone: 'Asia/Shanghai', overlapPolicy: 'SKIP', misfirePolicy: 'FIRE_LATEST', sessionMode: 'ISOLATED', nextFireAt: '2026-08-12T18:00:00Z', version: 1 } })),
      http.get('/api/scheduled-tasks/901/runs', () => HttpResponse.json({ success: true, code: '0', message: '', data: [{ id: 10482, scheduledTaskId: 901, status: 'SUCCEEDED', triggerType: 'SCHEDULED' }] })),
      http.get('/api/scheduled-tasks/901/documents', () => HttpResponse.json({ success: true, code: '0', message: '', data: [] })),
      http.get('/api/scheduled-tasks/901/health', () => HttpResponse.json({ success: true, code: '0', message: '', data: { success30d: 8, completed30d: 10 } })),
      http.get('/api/squads', () => HttpResponse.json({ success: true, code: '0', message: '', data: { list: [{ id: 2, name: '功能增量分析小队' }], total: 1, pageNum: 1, pageSize: 100 } })),
      http.get('/api/agents', () => HttpResponse.json({ success: true, code: '0', message: '', data: [{ id: 5, name: '功能增量分析员' }] })),
    );
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<QueryClientProvider client={client}><MemoryRouter initialEntries={['/scheduled-tasks/901']}><Routes><Route path="/scheduled-tasks/:id" element={<ScheduledTaskDetailPage />} /></Routes></MemoryRouter></QueryClientProvider>);
    expect(await screen.findByText('主干夜间回归')).toBeInTheDocument();
    expect(screen.getByText('运行历史')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Run #10482' })).toHaveAttribute('href', '/scheduled-task-runs/10482');
    expect(await screen.findByText('功能增量分析小队 (2) / 功能增量分析员 (5)')).toBeInTheDocument();
    expect(screen.getByText('← 返回定时任务')).toBeInTheDocument();
    expect(screen.getByText('成功')).toBeInTheDocument();
  });

  it('falls back to raw ids when squad or agent names are unavailable', async () => {
    server.use(
      http.get('/api/scheduled-tasks/901', () => HttpResponse.json({ success: true, code: '0', message: '', data: { id: 901, name: '主干夜间回归', instructionMd: '执行回归', squadId: 2, initialAgentId: 5, status: 'ACTIVE', scheduleType: 'CRON', cronExpression: '0 0 2 * * *', timezone: 'Asia/Shanghai', overlapPolicy: 'SKIP', misfirePolicy: 'FIRE_LATEST', sessionMode: 'ISOLATED', nextFireAt: '2026-08-12T18:00:00Z', version: 1 } })),
      http.get('/api/scheduled-tasks/901/runs', () => HttpResponse.json({ success: true, code: '0', message: '', data: [] })),
      http.get('/api/scheduled-tasks/901/documents', () => HttpResponse.json({ success: true, code: '0', message: '', data: [] })),
      http.get('/api/scheduled-tasks/901/health', () => HttpResponse.json({ success: true, code: '0', message: '', data: { success30d: 0, completed30d: 0 } })),
      http.get('/api/squads', () => HttpResponse.json({ success: true, code: '0', message: '', data: { list: [], total: 0, pageNum: 1, pageSize: 100 } })),
      http.get('/api/agents', () => HttpResponse.json({ success: true, code: '0', message: '', data: [] })),
    );
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<QueryClientProvider client={client}><MemoryRouter initialEntries={['/scheduled-tasks/901']}><Routes><Route path="/scheduled-tasks/:id" element={<ScheduledTaskDetailPage />} /></Routes></MemoryRouter></QueryClientProvider>);
    expect(await screen.findByText('小队 #2 / 数字人 #5')).toBeInTheDocument();
  });
});
