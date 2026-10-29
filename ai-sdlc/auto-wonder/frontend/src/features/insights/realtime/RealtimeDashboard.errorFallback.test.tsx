import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { i18n } from '@/shared/i18n';
import RealtimeDashboard from './RealtimeDashboard';

vi.mock('./api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('./api')>();
  // 非 Error 的 rejection 不带 message，用于覆盖错误描述的本地化兜底文案。
  return { ...actual, getRealtimeDashboard: () => Promise.reject({ code: 'UNEXPECTED' }) };
});

const ok = (value: unknown) => HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: value });

afterEach(async () => { cleanup(); await i18n.changeLanguage('zh-CN'); });

it('falls back to the localized retry message when the realtime error carries no message', async () => {
  await i18n.changeLanguage('zh-CN');
  server.use(
    http.get('/api/dashboard/overview', () => ok(null)),
    http.get('/api/dashboard/pipeline-health', () => ok(null)),
    http.get('/api/dashboard/realtime', () => ok(null)),
    http.get('/api/insights/workers', () => ok([])),
  );
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const router = createMemoryRouter([{ path: '/insights', element: <RealtimeDashboard /> }], { initialEntries: ['/insights'] });
  render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>);

  expect(await screen.findByText('看板数据加载失败')).toBeInTheDocument();
  expect(screen.getByText('请稍后重试')).toBeInTheDocument();
});
