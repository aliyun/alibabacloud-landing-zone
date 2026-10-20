import { act, cleanup, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, expect, it } from 'vitest';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { i18n } from '@/shared/i18n';
import RealtimeDashboard from './RealtimeDashboard';
import type { RealtimeDashboard as DashboardData } from './api';

// 固定 instant 的期望展示值按运行环境时区取字面量，不从待测的 formatDateTime/Intl 推导。
// 验收要求本文件分别在 TZ=UTC 与 TZ=Asia/Shanghai 进程下运行：
//   TZ=UTC:          2026-10-13T15:00:00Z -> 15:00:00；2026-10-13T17:00:00Z -> 17:00:00
//   TZ=Asia/Shanghai: 2026-10-13T15:00:00Z -> 23:00:00；2026-10-13T17:00:00Z -> 次日 01:00:00（跨日）
const FIXED_INSTANT = '2026-10-13T15:00:00Z';
const CROSS_DAY_INSTANT = '2026-10-13T17:00:00Z';
const LITERAL_BY_ZONE: Record<string, { fixed: string; crossDay: string }> = {
  UTC: { fixed: '15:00:00', crossDay: '17:00:00' },
  'Asia/Shanghai': { fixed: '23:00:00', crossDay: '01:00:00' },
};

function zoneExpectation(): { fixed: string; crossDay: string } {
  const zone = Intl.DateTimeFormat().resolvedOptions().timeZone;
  const known = LITERAL_BY_ZONE[zone];
  if (known) return known;
  const hms = (iso: string) => {
    const instant = new Date(iso);
    return new Date(instant.getTime() - instant.getTimezoneOffset() * 60_000).toISOString().slice(11, 19);
  };
  return { fixed: hms(FIXED_INSTANT), crossDay: hms(CROSS_DAY_INSTANT) };
}

const baseData: Omit<DashboardData, 'generatedAt'> = {
  kpi: { runningDispatches: 2, todayCompletedTasks: 12, weekCompletedTasks: 57, avgTaskDurationMinutes: 90, inProgressWorkitems: 9, queuedDispatches: 3, activeSquads: 2, onlineAgents: 6, avgLoad: 0.67 },
  inventory: { byLifecycle: { init: 1, inProgress: 2, done: 3, canceled: 4 }, byType: { req: 1, task: 2, bug: 3 } },
  health: { successRate: 80, failedOrTimeout: 1, retries: 2, avgDurationMinutes: 90 },
  squads: [], workstations: [], runningFeed: [], recentFeed: [],
};
const cost = { totalTokens: 100000, avgTokensPerTask: 5000, dailyAvg: 14000, trend: [10], totalCredits: 82.61, avgCreditsPerTask: 12.5, dailyAvgCredits: 2.75, creditsTrend: [1, 2] };
const metrics = { cost, efficiency: { completionRate: 80, totalTasks: 20, completedTasks: 16, avgDurationMinutes: 30, trend: [80] }, stability: { successRate: 90, retryCount: 2, blockedCount: 1, trend: [90] }, security: { highRiskOps: 1, complianceRate: 98, auditBlocks: 3, trend: [1] } };
const ok = (value: unknown) => HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: value });

function mockDashboard(generatedAt: unknown) {
  server.use(
    http.get('/api/dashboard/realtime', () => ok({ ...baseData, generatedAt })),
    http.get('/api/insights/workers', () => ok([])),
    http.get('/api/insights/metrics', () => ok(metrics)),
  );
}

function routed(element: React.ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const router = createMemoryRouter([{ path: '/insights', element }], { initialEntries: ['/insights'] });
  return render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>);
}
beforeEach(async () => { localStorage.clear(); await i18n.changeLanguage('zh-CN'); });
afterEach(async () => { cleanup(); await i18n.changeLanguage('zh-CN'); });

it('renders the backend instant in the browser local timezone and keeps it across languages', async () => {
  const expected = zoneExpectation();
  mockDashboard(FIXED_INSTANT);
  routed(<RealtimeDashboard />);
  expect(await screen.findByText(`刷新时间 ${expected.fixed}`)).toBeInTheDocument();
  await act(async () => { await i18n.changeLanguage('en-US'); });
  expect(screen.getByText(`Updated at ${expected.fixed}`)).toBeInTheDocument();
});

it('renders the cross-day rollover of the same instant in the browser local timezone', async () => {
  const expected = zoneExpectation();
  mockDashboard(CROSS_DAY_INSTANT);
  routed(<RealtimeDashboard />);
  expect(await screen.findByText(`刷新时间 ${expected.crossDay}`)).toBeInTheDocument();
});

it('keeps the placeholder when the backend omits generatedAt', async () => {
  mockDashboard(undefined);
  routed(<RealtimeDashboard />);
  await screen.findByText('正在运行');
  expect(screen.getByText('刷新时间 —')).toBeInTheDocument();
});

it('keeps rendering an unparsable generatedAt verbatim', async () => {
  mockDashboard('not-a-date');
  routed(<RealtimeDashboard />);
  expect(await screen.findByText('刷新时间 not-a-date')).toBeInTheDocument();
});
