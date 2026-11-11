import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { PrdHtmlPreview, PRESET_VIEWPORTS } from './PrdHtmlPreview';
import { useAuthStore } from '@/shared/auth/store';

describe('PrdHtmlPreview', () => {
  beforeEach(() => {
    useAuthStore.getState().setTokens('access-token', 'refresh-token');
    vi.stubGlobal('fetch', vi.fn(async () => new Response('<h1>PRD</h1>', {
      status: 200,
      headers: { 'Content-Type': 'text/html' },
    })));
    Object.defineProperty(URL, 'createObjectURL', { value: vi.fn(() => 'blob:prd-html'), configurable: true });
    Object.defineProperty(URL, 'revokeObjectURL', { value: vi.fn(), configurable: true });
  });

  afterEach(() => {
    useAuthStore.getState().clear();
    vi.unstubAllGlobals();
  });

  it('asks for a placeholder before any version is chosen', () => {
    render(<PrdHtmlPreview workitemId={1} versionId={null} viewport={PRESET_VIEWPORTS.FIT} />);

    expect(screen.getByText('在左侧描述需求或添加截图，设计生成后会自动显示在这里。')).toBeInTheDocument();
    expect(fetch).not.toHaveBeenCalled();
  });

  it('loads the document with the bearer token and shows it in a sandboxed frame', async () => {
    render(<PrdHtmlPreview workitemId={1} versionId={9} viewport={PRESET_VIEWPORTS.FIT} />);

    const frame = await screen.findByTestId('prd-html-preview');
    await waitFor(() => expect(URL.createObjectURL).toHaveBeenCalled());
    expect(frame).toHaveAttribute('src', 'blob:prd-html');
    // Isolation is the whole point: scripts allowed, but NOT same-origin, so the document cannot read
    // host login state or call same-origin APIs.
    expect(frame).toHaveAttribute('sandbox', 'allow-scripts');
    expect(frame.getAttribute('sandbox')).not.toContain('allow-same-origin');
    expect(frame).toHaveAttribute('referrerPolicy', 'no-referrer');

    const [url, init] = vi.mocked(fetch).mock.calls[0];
    expect(url).toBe('/api/workitems/1/prd/versions/9/html');
    expect((init?.headers as Headers).get('Authorization')).toBe('Bearer access-token');
  });

  it('revokes the blob url on unmount so it does not leak', async () => {
    const { unmount } = render(<PrdHtmlPreview workitemId={1} versionId={9} viewport={PRESET_VIEWPORTS.FIT} />);
    await screen.findByTestId('prd-html-preview');
    await waitFor(() => expect(URL.createObjectURL).toHaveBeenCalled());

    unmount();

    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:prd-html');
  });

  it('surfaces a load failure with a retry rather than an empty frame', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('nope', { status: 500 })));
    render(<PrdHtmlPreview workitemId={1} versionId={9} viewport={PRESET_VIEWPORTS.FIT} />);

    expect(await screen.findByText('文档加载失败')).toBeInTheDocument();
    // antd renders the two-character label with an inserted space, so match the accessible name loosely.
    expect(screen.getByRole('button', { name: /重\s*试/ })).toBeInTheDocument();
  });
});
