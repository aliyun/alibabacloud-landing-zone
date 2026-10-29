import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { useAuthStore } from '@/shared/auth/store';
import type { Artifact } from '@/shared/types/workitem';
import { ArtifactPreviewModal } from './ArtifactPreviewModal';

const mermaidMock = vi.hoisted(() => ({
  initialize: vi.fn(),
  parse: vi.fn(),
  render: vi.fn(),
}));

vi.mock('mermaid', () => ({ default: mermaidMock }));

const artifact: Artifact = {
  id: 7,
  workitemId: 1,
  dispatchId: 2,
  name: 'requirements/design.md',
  type: 'REQUIREMENT',
  size: 100,
  gmtCreate: '2026-07-28T10:00:00Z',
};

describe('ArtifactPreviewModal mermaid entry', () => {
  beforeEach(() => {
    mermaidMock.initialize.mockReset();
    mermaidMock.parse.mockReset();
    mermaidMock.render.mockReset();
    mermaidMock.parse.mockResolvedValue({ diagramType: 'flowchart-v2' });
    mermaidMock.render.mockResolvedValue({ svg: '<svg xmlns="http://www.w3.org/2000/svg" />' });
    useAuthStore.getState().setTokens('access-token', 'refresh-token');
    vi.stubGlobal('fetch', vi.fn(async () => new Response('```mermaid\nflowchart TD\n  A-->B\n```', { status: 200 })));
    server.use(http.get('/api/artifacts/7/download', () => HttpResponse.json({
      success: true, code: '0', message: '', traceId: null, data: 'https://oss.example/design.md',
    })));
  });

  afterEach(() => {
    useAuthStore.getState().clear();
    vi.unstubAllGlobals();
  });

  it('renders mermaid diagrams inside requirement document previews', async () => {
    render(<ArtifactPreviewModal open artifact={artifact} onClose={() => undefined} />);

    await waitFor(() => expect(screen.getByTestId('mermaid-diagram')).toHaveAttribute('data-status', 'rendered'));
    expect(mermaidMock.render).toHaveBeenCalledWith(expect.any(String), 'flowchart TD\n  A-->B');
  });
});
