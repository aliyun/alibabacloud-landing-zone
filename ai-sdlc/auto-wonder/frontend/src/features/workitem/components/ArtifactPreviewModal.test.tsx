import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { useAuthStore } from '@/shared/auth/store';
import type { Artifact } from '@/shared/types/workitem';
import { ArtifactPreviewModal } from './ArtifactPreviewModal';

const artifact = (name: string): Artifact => ({
  id: 7,
  workitemId: 1,
  dispatchId: 2,
  name,
  type: 'DELIVERABLE',
  size: 100,
  gmtCreate: '2026-07-28T10:00:00Z',
});

describe('ArtifactPreviewModal', () => {
  beforeEach(() => {
    useAuthStore.getState().setTokens('access-token', 'refresh-token');
    vi.stubGlobal('fetch', vi.fn(async () => new Response('# Report\nPASS', { status: 200 })));
    Object.defineProperty(URL, 'createObjectURL', { value: vi.fn(() => 'blob:artifact-preview'), configurable: true });
    Object.defineProperty(URL, 'revokeObjectURL', { value: vi.fn(), configurable: true });
  });

  afterEach(() => {
    useAuthStore.getState().clear();
    vi.unstubAllGlobals();
  });

  function expectPreviewFetch(artifactId = 7) {
    expect(fetch).toHaveBeenCalled();
    const [input, init] = vi.mocked(fetch).mock.calls[0];
    expect(input).toBe(`/api/artifacts/${artifactId}/preview`);
    expect((init?.headers as Headers).get('Authorization')).toBe('Bearer access-token');
  }

  it('fetches and renders markdown artifacts', async () => {
    server.use(http.get('/api/artifacts/7/download', () => HttpResponse.json({
      success: true, code: '0', message: '', traceId: null, data: 'https://oss.example/report.md',
    })));

    render(<ArtifactPreviewModal open artifact={artifact('deliverables/report.md')} onClose={() => undefined} />);

    expect(await screen.findByRole('heading', { name: 'Report' })).toBeInTheDocument();
    expect(screen.getByText('PASS')).toBeInTheDocument();
    expectPreviewFetch();
  });

  it('uses same-origin preview endpoint when download urls are cross-origin', async () => {
    server.use(http.get('/api/artifacts/7/download', () => HttpResponse.json({
      success: true, code: '0', message: '', traceId: null, data: 'http://oss.example/report.md',
    })));

    render(<ArtifactPreviewModal open artifact={artifact('deliverables/report.md')} onClose={() => undefined} />);

    expect(await screen.findByRole('heading', { name: 'Report' })).toBeInTheDocument();
    expectPreviewFetch();
    expect(screen.getByRole('link', { name: /下载/ })).toHaveAttribute('href', 'https://oss.example/report.md');
  });

  it('previews artifacts even when download url loading fails', async () => {
    server.use(http.get('/api/artifacts/7/download', () => HttpResponse.json({
      success: false, code: '10403', message: '无权限', traceId: null, data: null,
    })));

    render(<ArtifactPreviewModal open artifact={artifact('deliverables/report.md')} onClose={() => undefined} />);

    expect(await screen.findByRole('heading', { name: 'Report' })).toBeInTheDocument();
    expectPreviewFetch();
    expect(screen.queryByRole('link', { name: /下载/ })).not.toBeInTheDocument();
  });

  it('fetches and renders jsonl artifacts as text', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('{"event":"step.done"}', { status: 200 })));
    server.use(http.get('/api/artifacts/7/download', () => HttpResponse.json({
      success: true, code: '0', message: '', traceId: null, data: 'https://oss.example/events.jsonl',
    })));

    render(<ArtifactPreviewModal open artifact={artifact('observability/events.jsonl')} onClose={() => undefined} />);

    expect(await screen.findByText('{"event":"step.done"}')).toBeInTheDocument();
    expect(screen.getByText('{"event":"step.done"}').tagName).toBe('PRE');
    expectPreviewFetch();
  });

  it('renders image artifacts from an authenticated preview blob', async () => {
    server.use(http.get('/api/artifacts/7/download', () => HttpResponse.json({
      success: true, code: '0', message: '', traceId: null, data: 'https://oss.example/screenshot.png',
    })));

    render(<ArtifactPreviewModal open artifact={artifact('evidence/screenshot.png')} onClose={() => undefined} />);

    const image = await screen.findByRole('img', { name: 'evidence/screenshot.png' });
    expect(image).toHaveAttribute('src', 'blob:artifact-preview');
    expectPreviewFetch();
    expect(screen.getByRole('link', { name: /下载/ })).toHaveAttribute('href', 'https://oss.example/screenshot.png');
  });

  it('renders video artifacts from an authenticated preview blob', async () => {
    server.use(http.get('/api/artifacts/7/download', () => HttpResponse.json({
      success: true, code: '0', message: '', traceId: null, data: 'https://oss.example/demo.mp4',
    })));

    render(<ArtifactPreviewModal open artifact={artifact('evidence/demo.mp4')} onClose={() => undefined} />);

    const video = await screen.findByTestId('artifact-video-preview');
    expect(video).toHaveAttribute('src', 'blob:artifact-preview');
    expect(video).toHaveAttribute('controls');
    expectPreviewFetch();
    expect(screen.getByRole('link', { name: /下载/ })).toHaveAttribute('href', 'https://oss.example/demo.mp4');
  });

  it('revokes preview object urls created after the modal is closed', async () => {
    let resolveFetch: (response: Response) => void = () => undefined;
    vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>((resolve) => {
      resolveFetch = resolve;
    })));
    server.use(http.get('/api/artifacts/7/download', () => HttpResponse.json({
      success: true, code: '0', message: '', traceId: null, data: 'https://oss.example/demo.mp4',
    })));

    const { rerender } = render(<ArtifactPreviewModal open artifact={artifact('evidence/demo.mp4')} onClose={() => undefined} />);
    rerender(<ArtifactPreviewModal open={false} artifact={null} onClose={() => undefined} />);
    resolveFetch(new Response(new Blob(['video']), { status: 200 }));

    await waitFor(() => expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:artifact-preview'));
  });

  it('shows retry when text preview loading fails', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response('', { status: 500 }))
      .mockResolvedValueOnce(new Response('# Report\nPASS', { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);
    server.use(http.get('/api/artifacts/7/download', () => HttpResponse.json({
      success: true, code: '0', message: '', traceId: null, data: 'https://oss.example/report.md',
    })));

    render(<ArtifactPreviewModal open artifact={artifact('deliverables/report.md')} onClose={() => undefined} />);

    expect(await screen.findByText('产物预览加载失败')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /重\s*试/ }));

    expect(await screen.findByRole('heading', { name: 'Report' })).toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('does not inline preview oversized text artifacts', async () => {
    server.use(http.get('/api/artifacts/7/download', () => HttpResponse.json({
      success: true, code: '0', message: '', traceId: null, data: 'https://oss.example/large.log',
    })));

    render(<ArtifactPreviewModal open artifact={{ ...artifact('logs/large.log'), size: 1024 * 1024 + 1 }} onClose={() => undefined} />);

    expect(await screen.findByText('产物过大，请下载后查看')).toBeInTheDocument();
    await waitFor(() => expect(fetch).not.toHaveBeenCalled());
  });

  it('does not inline preview text artifacts with unknown size', async () => {
    server.use(http.get('/api/artifacts/7/download', () => HttpResponse.json({
      success: true, code: '0', message: '', traceId: null, data: 'https://oss.example/unknown.log',
    })));

    render(<ArtifactPreviewModal open artifact={{ ...artifact('logs/unknown.log'), size: null }} onClose={() => undefined} />);

    expect(await screen.findByText('无法确认产物大小，请下载后查看')).toBeInTheDocument();
    await waitFor(() => expect(fetch).not.toHaveBeenCalled());
  });
});
