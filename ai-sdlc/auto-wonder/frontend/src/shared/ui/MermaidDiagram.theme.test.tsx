import { render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { MermaidDiagram } from './MermaidDiagram';

const mermaidMock = vi.hoisted(() => ({
  initialize: vi.fn(),
  parse: vi.fn(),
  render: vi.fn(),
}));

vi.mock('mermaid', () => ({ default: mermaidMock }));

vi.mock('@/shared/theme/AppearanceProvider', () => ({
  useAppearance: () => ({ mode: 'dark', resolved: 'dark', setMode: () => undefined }),
}));

describe('MermaidDiagram theme', () => {
  beforeEach(() => {
    mermaidMock.initialize.mockReset();
    mermaidMock.parse.mockReset();
    mermaidMock.render.mockReset();
    mermaidMock.parse.mockResolvedValue({ diagramType: 'flowchart-v2' });
    mermaidMock.render.mockResolvedValue({ svg: '<svg xmlns="http://www.w3.org/2000/svg" />' });
  });

  it('renders with the dark mermaid theme when the app resolves dark', async () => {
    render(<MermaidDiagram code={'flowchart TD\n  A-->B'} />);

    await waitFor(() => expect(mermaidMock.render).toHaveBeenCalledTimes(1));
    expect(mermaidMock.initialize).toHaveBeenCalledWith(expect.objectContaining({ theme: 'dark', securityLevel: 'strict' }));
    expect(screen.getByTestId('mermaid-diagram')).toHaveAttribute('data-status', 'rendered');
  });
});
