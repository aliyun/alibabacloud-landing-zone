import { act, cleanup, render, screen } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { i18n } from '@/shared/i18n';
import { InsightsPage } from './InsightsPage';
import * as preferences from './useInsightPreference';

// The retained metrics render branch has no public tab option. Select its existing
// default at the preference boundary; queries, model, components and router remain real.
vi.mock('./useInsightPreference', async importOriginal => {
 const actual = await importOriginal<typeof preferences>();
 return { ...actual, useInsightPreference: <T,>(name: string, fallback: T | (() => T), valid: (value: unknown) => boolean) => actual.useInsightPreference(name, name === 'tab' ? 'metrics' as T : fallback, valid) };
});
afterEach(async () => { cleanup(); await i18n.changeLanguage('zh-CN'); });
it('recomputes the actual cached metric page model without remounting cards or refetching on locale change', async () => {
 localStorage.clear(); await i18n.changeLanguage('zh-CN'); let requests = 0;
 const ok = (data: unknown) => HttpResponse.json({ success: true, data });
 server.use(http.get('/api/insights/workers', () => { requests++; return ok([]); }), http.get('/api/insights/audit', () => { requests++; return ok({ items: [], total: 0 }); }), http.get('/api/insights/metrics', () => { requests++; return ok({ cost: { totalTokens: 100000, avgTokensPerTask: 5000, dailyAvg: 14000, trend: [10], totalCredits: 82.61, avgCreditsPerTask: 12.5, dailyAvgCredits: 2.75, creditsTrend: [1] }, efficiency: { completionRate: 80, totalTasks: 20, completedTasks: 16, avgDurationMinutes: 30, trend: [80] }, stability: { successRate: 90, retryCount: 2, blockedCount: 1, trend: [90] }, security: { highRiskOps: 1, complianceRate: 98, auditBlocks: 3, trend: [1] } }); }));
 const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
 const router = createMemoryRouter([{ path: '/insights', element: <InsightsPage /> }], { initialEntries: ['/insights'] });
 render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>);
 const title = await screen.findByText('链路阻塞'); const card = title.parentElement!.parentElement; const cost = screen.getByText('82.61'); const count = requests;
 await act(async () => { await i18n.changeLanguage('en-US'); });
 expect(screen.getByText('Workflow blockage')).toBe(title); expect(title.parentElement!.parentElement).toBe(card); expect(screen.getByText('82.61')).toBe(cost);
 expect(screen.getByText('Development Agent')).toBeInTheDocument(); expect(screen.getAllByText('Owner decision').length).toBeGreaterThan(0);
 expect(screen.getByText('Retries, rejections and high-risk operations produced 1 anomaly signal over Last 30 days.')).toBeInTheDocument();
 expect(requests).toBe(count);
 await act(async () => { await i18n.changeLanguage('zh-CN'); }); expect(screen.getByText('链路阻塞')).toBe(title);
});
