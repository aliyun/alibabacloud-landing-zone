import { describe, it, expect } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { ExecutionListPage } from './ExecutionListPage';

const ROW = {
  id: 312,
  workitemId: 312,
  sdlcStepId: 1,
  agentId: 20,
  agentVersionId: 30,
  executorId: 40,
  status: 'SUCCEEDED',
  attempt: 1,
  resultSummary: '构建通过, PR 已创建',
  error: null,
  packageOssRef: null,
  gmtCreate: '2026-07-11T14:22:00',
  gmtModified: '2026-07-11T14:22:40',
  workitemTitle: '登录页重构',
  agentName: '前端开发',
  agentVersionNo: 7,
  executorName: 'dev-01',
  artifacts: null,
};

const LONG_MARKDOWN = [
  '### 执行结果',
  '',
  '- 构建通过',
  '- 已创建 PR',
  '',
  '```text',
  'very long output',
  '```',
].join('\n');

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <ExecutionListPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('ExecutionListPage', () => {
  it('renders a dispatch row from the API', async () => {
    server.use(
      http.get('/api/dispatches', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: { list: [ROW], total: 1, page: 1, pageSize: 50 },
      })),
      http.get('/api/agents', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [],
      })),
    );
    renderPage();
    expect(await screen.findByText('执行记录')).toBeInTheDocument();
    expect(await screen.findByText('前端开发')).toBeInTheDocument();
    expect(screen.getByText('SUCCEEDED')).toBeInTheDocument();
  });

  it('opens the detail drawer on row action click', async () => {
    server.use(
      http.get('/api/dispatches', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: { list: [ROW], total: 1, page: 1, pageSize: 50 },
      })),
      http.get('/api/agents', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [],
      })),
      http.get('/api/dispatches/312', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: { ...ROW, resultSummary: LONG_MARKDOWN, artifacts: [] },
      })),
    );
    renderPage();
    const detail = await screen.findByText('详情');
    fireEvent.click(detail);
    await waitFor(() =>
      expect(screen.getByText('执行详情 · #312')).toBeInTheDocument(),
    );
    expect(screen.getByText('结果摘要')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '执行结果' })).toBeInTheDocument();
  });

  it('keeps long result summaries compact and opens markdown preview', async () => {
    server.use(
      http.get('/api/dispatches', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: { list: [{ ...ROW, resultSummary: LONG_MARKDOWN }], total: 1, page: 1, pageSize: 50 },
      })),
      http.get('/api/agents', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [],
      })),
    );
    renderPage();

    const preview = await screen.findByRole('button', { name: /查看完整结果/ });
    expect(preview).toBeInTheDocument();
    fireEvent.click(preview);

    expect(await screen.findByText('完整结果')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '执行结果' })).toBeInTheDocument();
    expect(screen.getByText('very long output')).toBeInTheDocument();
  });

  it('sends time_range=7d when segment changed', async () => {
    let lastUrl = '';
    server.use(
      http.get('/api/dispatches', ({ request }) => {
        lastUrl = request.url;
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: { list: [], total: 0, page: 1, pageSize: 50 },
        });
      }),
      http.get('/api/agents', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [],
      })),
    );
    renderPage();
    await screen.findByText('执行记录');
    fireEvent.click(screen.getByText('近 7 天'));
    await waitFor(() => expect(lastUrl).toContain('time_range=7d'));
  });
});
