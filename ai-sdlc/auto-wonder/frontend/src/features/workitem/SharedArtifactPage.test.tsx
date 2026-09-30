import { afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { SharedArtifactPage } from './SharedArtifactPage';

const token = `awshare_${'a'.repeat(43)}`;
const endpoint = `/api/share/workitems/${token}/requests/41`;
const ready = () => new Response(JSON.stringify({ status: 'SHARED', name: 'deliverables/report.md', size: 1000 }), { headers: { 'Content-Type': 'application/json' } });
function mount(value = token) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[`/api/share/workitems/${value}/requests/41?preview`]}><Routes>
        <Route path="/api/share/workitems/:token/:kind/:id" element={<SharedArtifactPage />} />
      </Routes></MemoryRouter>
    </QueryClientProvider>,
  );
}
afterEach(() => { vi.unstubAllGlobals(); });

describe('anonymous artifact preview', () => {
  it('renders sanitized Markdown tables without login and keeps original-file download', async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(ready()).mockResolvedValueOnce(new Response('# QA report\n\n| Result | State |\n|---|---|\n| Review | Passed |\n\n<script>window.pwned=true</script>'));
    vi.stubGlobal('fetch', fetchMock);
    const { container } = mount();
    expect(await screen.findByRole('heading', { name: 'QA report' })).toBeInTheDocument();
    expect(screen.getByRole('table')).toHaveTextContent('Passed');
    expect(container.querySelector('script')).toBeNull();
    expect(screen.getByRole('link', { name: /下载原文件/ })).toHaveAttribute('href', `${endpoint}?download=true`);
    expect(fetchMock).toHaveBeenNthCalledWith(1, `${endpoint}?metadata`, expect.objectContaining({ credentials: 'omit', headers: { Accept: 'application/json' }, referrerPolicy: 'no-referrer' }));
    expect(fetchMock).toHaveBeenNthCalledWith(2, endpoint, expect.objectContaining({ credentials: 'omit', headers: { Accept: 'application/octet-stream' } }));
  });

  it('polls pending metadata then displays the same link without fetching pending bytes', async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({ status: 'PENDING', name: 'report.md', size: null }), { status: 202 }))
      .mockResolvedValueOnce(ready()).mockResolvedValueOnce(new Response('# Ready now'));
    vi.stubGlobal('fetch', fetchMock);
    mount();
    expect(await screen.findByText('文件正在生成')).toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole('link', { name: /下载原文件/ })).not.toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('heading', { name: 'Ready now' })).toBeInTheDocument(), { timeout: 6500 });
    expect(screen.queryByText('文件正在生成')).not.toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledTimes(3);
  }, 8000);

  it('shows unavailable shares in place without redirecting to login or loading content', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response('', { status: 404 }));
    vi.stubGlobal('fetch', fetchMock);
    mount();
    expect(await screen.findByText('链接无效或产物已下线')).toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole('link', { name: /下载原文件/ })).not.toBeInTheDocument();
  });

  it('rejects malformed capabilities before making requests', async () => {
    const fetchMock = vi.fn(); vi.stubGlobal('fetch', fetchMock);
    mount('invalid');
    expect(await screen.findByText('链接无效或产物已下线')).toBeInTheDocument();
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
