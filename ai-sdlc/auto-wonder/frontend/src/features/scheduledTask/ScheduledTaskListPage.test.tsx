import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { ScheduledTaskListPage } from './ScheduledTaskListPage';

describe('ScheduledTaskListPage', () => {
  it('renders task status, next execution and run-now action', async () => {
    server.use(
      http.get('/api/scheduled-tasks', () => HttpResponse.json({ success: true, code: '0', message: '', data: { list: [{ id: 7, name: '凌晨回归', squadId: 1, initialAgentId: 11, status: 'ACTIVE', scheduleType: 'CRON', cronExpression: '0 0 2 * * *', timezone: 'Asia/Shanghai', nextFireAt: '2026-08-12T18:00:00Z', version: 3 }], total: 41, pageNum: 0, pageSize: 20 } })),
      http.get('/api/squads', () => HttpResponse.json({ success: true, code: '0', message: '', data: [] })),
      http.get('/api/agents', () => HttpResponse.json({ success: true, code: '0', message: '', data: [] })),
      http.get('/api/scheduled-tasks/7/runs', () => HttpResponse.json({ success: true, code: '0', message: '', data: [{ id: 80, scheduledTaskId: 7, status: 'SUCCEEDED' }] })),
      http.get('/api/scheduled-tasks/summary', () => HttpResponse.json({ success: true, code: '0', message: '', data: { running: 2, today: 3, success30d: 8, attention: 1 } })),
    );
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<QueryClientProvider client={queryClient}><MemoryRouter><ScheduledTaskListPage /></MemoryRouter></QueryClientProvider>);

    expect(await screen.findByText('凌晨回归')).toBeInTheDocument();
    expect(screen.getByText('启用中')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '立即运行' })).toBeInTheDocument();
    expect(await screen.findByText('成功')).toBeInTheDocument();
    expect(screen.getByText(/共 41 个/)).toBeInTheDocument();
    expect(await screen.findByText(/运行中 2/)).toBeInTheDocument();
  });
});
