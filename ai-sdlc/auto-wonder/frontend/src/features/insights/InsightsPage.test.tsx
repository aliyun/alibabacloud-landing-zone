import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { InsightsPage } from './InsightsPage';

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <InsightsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

// The page defaults to the realtime tab, which mounts RealtimeDashboard and
// fires /api/dashboard/realtime on render. These tests target the audit tab,
// so stub the realtime poll to keep MSW's strict handler check happy.
const realtimeHandler = http.get('/api/dashboard/realtime', () =>
  HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: null }),
);

describe('InsightsPage', () => {
  it('links worker and time filters into audit requests', async () => {
    const metricsRequests: string[] = [];
    const auditRequests: string[] = [];
    server.use(
      realtimeHandler,
      http.get('/api/insights/metrics', ({ request }) => {
        metricsRequests.push(new URL(request.url).search);
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: {
            cost: { totalTokens: 100000, avgTokensPerTask: 5000, dailyAvg: 14000, trend: [10, 12, 11, 13, 14, 12, 15] },
            efficiency: { completionRate: 80, totalTasks: 20, completedTasks: 16, avgDurationMinutes: 30, trend: [70, 72, 75, 77, 78, 79, 80] },
            stability: { successRate: 90, retryCount: 2, blockedCount: 1, trend: [88, 89, 90, 91, 90, 89, 90] },
            security: { highRiskOps: 1, complianceRate: 98, auditBlocks: 0, trend: [0, 1, 0, 1, 0, 0, 1] },
          },
        });
      }),
      http.get('/api/insights/workers', () => {
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: [{ id: 12, name: '验收数字员工' }],
        });
      }),
      http.get('/api/insights/audit', ({ request }) => {
        auditRequests.push(new URL(request.url).search);
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: {
            items: [
              { timestamp: '2026-07-12 12:00:00', worker: '验收数字员工', eventType: 'REJECT', detail: 'aone#1 评审驳回', riskLevel: 'medium' },
            ],
            total: 1,
          },
        });
      }),
    );

    const user = userEvent.setup();
    renderPage();
    expect(await screen.findByText('数据洞察')).toBeInTheDocument();

    await user.click(screen.getByText('执行审计'));
    await user.click(screen.getByRole('combobox'));
    const workerOptions = await screen.findAllByText('验收数字员工');
    await user.click(workerOptions[workerOptions.length - 1]);
    await user.click(await screen.findByText('近7天'));

    expect(await screen.findByText('评审驳回')).toBeInTheDocument();
    const latestMetricsRequest = metricsRequests[metricsRequests.length - 1];
    const latestAuditRequest = auditRequests[auditRequests.length - 1];
    expect(latestMetricsRequest).toContain('worker_id=12');
    expect(latestMetricsRequest).toContain('time_range=7d');
    expect(latestAuditRequest).toContain('worker_id=12');
    expect(latestAuditRequest).toContain('time_range=7d');
  });

  it('keeps audit pagination when worker and time filters change', async () => {
    const auditRequests: string[] = [];
    server.use(
      realtimeHandler,
      http.get('/api/insights/metrics', () => {
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: {
            cost: { totalTokens: 100000, avgTokensPerTask: 5000, dailyAvg: 14000, trend: [10, 12, 11, 13, 14, 12, 15] },
            efficiency: { completionRate: 80, totalTasks: 20, completedTasks: 16, avgDurationMinutes: 30, trend: [70, 72, 75, 77, 78, 79, 80] },
            stability: { successRate: 90, retryCount: 2, blockedCount: 1, trend: [88, 89, 90, 91, 90, 89, 90] },
            security: { highRiskOps: 1, complianceRate: 98, auditBlocks: 0, trend: [0, 1, 0, 1, 0, 0, 1] },
          },
        });
      }),
      http.get('/api/insights/workers', () => {
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: [{ id: 12, name: '验收数字员工' }],
        });
      }),
      http.get('/api/insights/audit', ({ request }) => {
        const url = new URL(request.url);
        auditRequests.push(url.search);
        const page = Number(url.searchParams.get('page') ?? '1');
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: {
            items: [
              { timestamp: `2026-07-12 12:00:0${page}`, worker: '验收数字员工', eventType: 'REJECT', detail: `aone#${page} 评审驳回`, riskLevel: 'medium' },
            ],
            total: 51,
          },
        });
      }),
    );

    const user = userEvent.setup();
    renderPage();
    expect(await screen.findByText('数据洞察')).toBeInTheDocument();

    await user.click(screen.getByText('执行审计'));
    await user.click(screen.getByRole('combobox'));
    const workerOptions = await screen.findAllByText('验收数字员工');
    await user.click(workerOptions[workerOptions.length - 1]);
    await user.click(await screen.findByText('近7天'));
    await user.click(await screen.findByTitle('2'));

    expect(await screen.findByText('评审驳回')).toBeInTheDocument();
    expect(auditRequests[auditRequests.length - 1]).toContain('page=2');
    expect(auditRequests[auditRequests.length - 1]).toContain('worker_id=12');
    expect(auditRequests[auditRequests.length - 1]).toContain('time_range=7d');
  });
});
