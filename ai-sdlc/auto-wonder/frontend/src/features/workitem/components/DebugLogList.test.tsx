import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { DebugLogList } from './DebugLogList';

function renderList(workitemId = '200') {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <ConfigProvider locale={zhCN}>
        <DebugLogList workitemId={workitemId} />
      </ConfigProvider>
    </QueryClientProvider>,
  );
}

const ok = (data: unknown) =>
  HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data });

describe('DebugLogList', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('renders nothing when the workitem has no debug logs', async () => {
    server.use(http.get('/api/debug-logs', () => ok([])));

    const { container } = renderList();

    await waitFor(() => expect(container.firstChild).toBeNull());
  });

  it('lists every round with status, size and download link', async () => {
    server.use(http.get('/api/debug-logs', () => ok([
      {
        id: 1, sourceType: 'WORKITEM', sourceId: 200, dispatchId: 900, agentId: 400,
        runNo: 1, dispatchStatus: 'SUCCEEDED', objectKey: 'debug/200/DevAgent-run-1.log.gz',
        sizeBytes: 2048, sha256: null, truncated: false, uploadChannel: 'DIRECT',
        status: 'UPLOADED', errorMessage: null, gmtCreate: '2026-09-04',
        downloadUrl: 'https://oss/get?sig=1',
      },
      {
        id: 2, sourceType: 'WORKITEM', sourceId: 200, dispatchId: 901, agentId: 400,
        runNo: 2, dispatchStatus: 'CANCELED', objectKey: 'debug/200/DevAgent-run-2.log.gz',
        sizeBytes: 3145728, sha256: null, truncated: true, uploadChannel: 'RELAY',
        status: 'FAILED', errorMessage: 'presign rejected', gmtCreate: '2026-09-04',
        downloadUrl: null,
      },
    ])));

    renderList();

    expect(await screen.findByText('第 1 轮')).toBeInTheDocument();
    expect(screen.getByText('第 2 轮')).toBeInTheDocument();
    expect(screen.getByText('截断')).toBeInTheDocument();
    expect(screen.getByText('presign rejected')).toBeInTheDocument();
    expect(screen.getByText(/3.0 MB/)).toBeInTheDocument();

    const downloads = screen.getAllByRole('link', { name: /下\s?载/ });
    expect(downloads).toHaveLength(1);
    expect(downloads[0]).toHaveAttribute('href', 'https://oss/get?sig=1');
  });

  /**
   * 强制契约（S9/S12 评审）：UPLOADED 行的 errorMessage 是 Writer Close 收尾注记，
   * 必须渲染为 warning 语义而非 danger；FAILED 行才用 danger；
   * UPLOADED 且 errorMessage 为 null 的行不渲染任何错误文本；
   * dispatch_status 可能是非终态临时值（RUNNING，relay 补插路径），原样展示；
   * downloadUrl 为 null 的行不渲染下载链接。
   */
  it('shows UPLOADED close note as warning, FAILED error as danger, and RUNNING status verbatim', async () => {
    server.use(http.get('/api/debug-logs', () => ok([
      {
        id: 3, sourceType: 'WORKITEM', sourceId: 200, dispatchId: 902, agentId: 400,
        runNo: 3, dispatchStatus: 'RUNNING', objectKey: 'debug/200/DevAgent-run-3.log.gz',
        sizeBytes: 1024, sha256: null, truncated: false, uploadChannel: 'RELAY',
        status: 'UPLOADED', errorMessage: 'writer close note', gmtCreate: '2026-09-04',
        downloadUrl: 'https://oss/get?sig=3',
      },
      {
        id: 4, sourceType: 'WORKITEM', sourceId: 200, dispatchId: 903, agentId: 400,
        runNo: 4, dispatchStatus: 'FAILED', objectKey: 'debug/200/DevAgent-run-4.log.gz',
        sizeBytes: null, sha256: null, truncated: false, uploadChannel: null,
        status: 'FAILED', errorMessage: 'relay rejected', gmtCreate: '2026-09-04',
        downloadUrl: null,
      },
      {
        id: 5, sourceType: 'WORKITEM', sourceId: 200, dispatchId: 904, agentId: 400,
        runNo: 5, dispatchStatus: 'SUCCEEDED', objectKey: 'debug/200/DevAgent-run-5.log.gz',
        sizeBytes: 2048, sha256: null, truncated: false, uploadChannel: 'DIRECT',
        status: 'UPLOADED', errorMessage: null, gmtCreate: '2026-09-04',
        downloadUrl: 'https://oss/get?sig=5',
      },
    ])));

    const { container } = renderList();

    const note = await screen.findByText('writer close note');
    expect(note).toHaveClass('ant-typography-warning');
    expect(note).not.toHaveClass('ant-typography-danger');

    const failed = screen.getByText('relay rejected');
    expect(failed).toHaveClass('ant-typography-danger');

    // UPLOADED 且 errorMessage 为 null 的行（第 5 轮）不渲染任何错误文本
    expect(screen.getByText('第 5 轮')).toBeInTheDocument();
    expect(container.querySelectorAll('.ant-typography-warning')).toHaveLength(1);
    expect(container.querySelectorAll('.ant-typography-danger')).toHaveLength(1);

    // 非终态 dispatch_status 原样展示，不按白名单过滤
    expect(screen.getByText('RUNNING')).toBeInTheDocument();

    // downloadUrl 为 null 的 FAILED 行没有下载链接
    const downloads = screen.getAllByRole('link', { name: /下\s?载/ });
    expect(downloads).toHaveLength(2);
    expect(downloads[0]).toHaveAttribute('href', 'https://oss/get?sig=3');
    expect(downloads[1]).toHaveAttribute('href', 'https://oss/get?sig=5');
  });
});
