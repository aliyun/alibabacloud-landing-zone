import { act, cleanup, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, expect, it } from 'vitest';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { i18n } from '@/shared/i18n';
import RealtimeDashboard from './RealtimeDashboard';
import type { OverviewData, PipelineHealth } from './api';

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

const baseOverview: OverviewData = {
  kpi: { todayCreatedWorkitems: 3, runningWorkitems: 2, todayCompletedTasks: 12, weekCompletedTasks: 57 },
  inventory: { byLifecycle: { init: 1, inProgress: 2, done: 3, canceled: 4 }, byType: { req: 1, task: 2, bug: 3 } },
  generatedAt: FIXED_INSTANT,
};

const pipeline: PipelineHealth = {
  range: 'today',
  successRate: 80,
  successSub: { succeeded: 8, total: 10 },
  failedOrTimeout: 1,
  rework: { rate: 25, reworkedWorkitems: 2, executedWorkitems: 8, retryCount: 2 },
  avgWorkitemDurationMinutes: 90,
  generatedAt: FIXED_INSTANT,
};

const realtime = {
  kpi: { runningDispatches: 2, todayCompletedTasks: 12, weekCompletedTasks: 57, avgTaskDurationMinutes: 90, inProgressWorkitems: 9, queuedDispatches: 3, activeSquads: 2, onlineAgents: 6, avgLoad: 0.67 },
  inventory: { byLifecycle: { init: 1, inProgress: 2, done: 3, canceled: 4 }, byType: { req: 1, task: 2, bug: 3 } },
  health: { successRate: 80, failedOrTimeout: 1, retries: 2, avgDurationMinutes: 90 },
  squads: [], workstations: [], runningFeed: [], recentFeed: [], generatedAt: FIXED_INSTANT,
};
const ok = (value: unknown) => HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: value });

function mockOverview(generatedAt: unknown) {
  server.use(
    http.get('/api/dashboard/overview', () => ok({ ...baseOverview, generatedAt })),
    http.get('/api/dashboard/pipeline-health', () => ok(pipeline)),
    http.get('/api/dashboard/realtime', ({ request }) => {
      const url = new URL(request.url);
      if (!url.searchParams.has('range')) return ok(realtime);
      const range = Number(url.searchParams.get('range'));
      return ok({
        kpi: { cost: { range, agentId: 'all', total: 10, perTask: 5, perDay: 1.43 } },
        costComposition: {
          range, scope: 'ALL_AGENTS', total: 10,
          sources: [{ key: 'WORKITEM_EXECUTION', name: '工单执行', credits: 10, share: 1 }],
        },
      });
    }),
    http.get('/api/insights/workers', () => ok([])),
  );
}

function routed(element: React.ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const router = createMemoryRouter([{ path: '/insights', element }], { initialEntries: ['/insights'] });
  return render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>);
}

function overviewRegion() {
  return within(screen.getByRole('region', { name: '工单概况' }));
}

beforeEach(async () => { localStorage.clear(); await i18n.changeLanguage('zh-CN'); });
afterEach(async () => { cleanup(); await i18n.changeLanguage('zh-CN'); });

it('renders the backend instant in the browser local timezone and keeps it across languages', async () => {
  const expected = zoneExpectation();
  mockOverview(FIXED_INSTANT);
  routed(<RealtimeDashboard />);
  const panel = within(await screen.findByRole('region', { name: '工单概况' }));

  const stamp = await panel.findByText(/刷新时间/, { selector: '.insight-stamp' });
  await waitFor(() => expect(stamp).toHaveTextContent(`刷新时间 ${expected.fixed}`));
  await act(async () => { await i18n.changeLanguage('en-US'); });
  expect(stamp).toHaveTextContent(`Updated at ${expected.fixed}`);
});

it('renders the cross-day rollover of the same instant in the browser local timezone', async () => {
  const expected = zoneExpectation();
  mockOverview(CROSS_DAY_INSTANT);
  routed(<RealtimeDashboard />);
  const stamp = await overviewRegion().findByText(/刷新时间/, { selector: '.insight-stamp' });
  await waitFor(() => expect(stamp).toHaveTextContent(`刷新时间 ${expected.crossDay}`));
});

it('keeps the placeholder when the backend omits generatedAt', async () => {
  mockOverview(undefined);
  routed(<RealtimeDashboard />);
  await screen.findByText('正在运行');
  expect(overviewRegion().getByText(/刷新时间/, { selector: '.insight-stamp' })).toHaveTextContent('刷新时间 —');
});

it('keeps rendering an unparsable generatedAt verbatim', async () => {
  mockOverview('not-a-date');
  routed(<RealtimeDashboard />);
  await waitFor(() => expect(overviewRegion().getByText(/刷新时间/, { selector: '.insight-stamp' })).toHaveTextContent('刷新时间 not-a-date'));
});
