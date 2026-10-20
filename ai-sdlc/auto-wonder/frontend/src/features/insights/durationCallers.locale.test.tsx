import { afterEach, beforeEach, expect, it } from 'vitest';
import { act, cleanup, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { i18n } from '@/shared/i18n';
import ActivityFeed from './realtime/ActivityFeed';
import WorkstationWall from './realtime/WorkstationWall';
import { SlowTailTable } from './components/SlowTailTable';
import type { RecentTask } from './realtime/api';

const running = { dispatchId: 1, agentId: 2, agentName: '<b>数字员工</b>', workitemId: 3, workitemTitle: '<strong>工单</strong>', stepName: '<em>阶段</em>', runningMinutes: 1 };
const finishedAt = '2026-09-25T12:30:00Z';
beforeEach(async () => { await i18n.changeLanguage('zh-CN'); });
afterEach(async () => { cleanup(); await i18n.changeLanguage('zh-CN'); });
function routed(element: React.ReactNode) {
  const router = createMemoryRouter([{ path: '/', element }]);
  return render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><RouterProvider router={router} /></QueryClientProvider>);
}

it('updates each cached routed long-duration consumer while retaining expanded tasks and literal authored names', async () => {
  let requests = 0;
  server.use(http.get('/api/dashboard/agents/2/running', () => { requests++; return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: [running] }); }));
  const recent: RecentTask[] = [{ dispatchId: 4, agentName: running.agentName, workitemTitle: running.workitemTitle, status: 'SUCCEEDED', durationMinutes: 2, finishedAt },
    { dispatchId: 5, agentName: null, workitemTitle: null, status: 'toString' as RecentTask['status'], durationMinutes: 1, finishedAt: 'invalid-date' }];
  const { container } = routed(<><ActivityFeed running={[running]} recent={recent} /><WorkstationWall workstations={[{ agentId: 2, name: running.agentName!, avatarUrl: null, runningTasks: 1, busy: true }]} /></>);
  const station = screen.getByRole('button', { name: /数字员工/ });
  await userEvent.click(station);
  expect(await screen.findByText(/<em>阶段<\/em>/)).toBeInTheDocument();
  await act(async () => { await i18n.changeLanguage('en-US'); });
  expect(screen.getByText('Live activity')).toBeInTheDocument();
  expect(screen.getByText('Agent workstations')).toBeInTheDocument();
  expect(station).toHaveAttribute('aria-expanded', 'true');
  expect(screen.getAllByText('1 minute').length).toBeGreaterThan(1);
  expect(screen.getByText('2 minutes')).toBeInTheDocument();
  expect(screen.getByText('toString')).toBeInTheDocument();
  expect(screen.getAllByText(running.workitemTitle).length).toBeGreaterThan(0);
  expect(container.querySelector('strong strong, em')).toBeNull();
  expect(requests).toBe(1);
  const time = container.querySelector(`time[datetime="${finishedAt}"]`)!;
  expect(time).toHaveTextContent(new Intl.DateTimeFormat('en-US', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }).format(new Date(finishedAt)));
  expect(container.querySelector('time[datetime="invalid-date"]')).toHaveTextContent('—');
  await act(async () => { await i18n.changeLanguage('zh-CN'); });
  expect(station).toHaveAttribute('aria-expanded', 'true');
  expect(screen.getByText('实时活动流')).toBeInTheDocument();
  expect(requests).toBe(1);
});

it('switches a cached slow-tail table and plural total in place without rewriting data or refetching', async () => {
  let requests = 0;
  server.use(http.get('/api/insights/human-agent-participation/slowest', () => {
    requests++;
    return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: { tailSize: 1, total: 1, page: 1, pageSize: 10, items: [{ workitemId: 9, title: '<b>报告</b>', completedAt: finishedAt, totalDurationSeconds: 3600, humanDurationSeconds: 1800, agentDurationSeconds: 1800 }] } });
  }));
  routed(<SlowTailTable startDate="2026-09-01" endDate="2026-09-25" />);
  expect(await screen.findByText('<b>报告</b>')).toBeInTheDocument();
  const table = screen.getByRole('table');
  await act(async () => { await i18n.changeLanguage('en-US'); });
  expect(screen.getByRole('table')).toBe(table);
  expect(within(table).getByRole('columnheader', { name: 'Human responsibility' })).toBeInTheDocument();
  expect(screen.getByText('1 work item')).toBeInTheDocument();
  expect(within(table).getByText('1h')).toBeInTheDocument();
  expect(within(table).getByText('<b>报告</b>')).toBeInTheDocument();
  expect(requests).toBe(1);
});
