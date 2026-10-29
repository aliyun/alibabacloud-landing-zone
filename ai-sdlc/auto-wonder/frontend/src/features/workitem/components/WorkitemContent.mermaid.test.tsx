import { render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { WorkitemContent } from './WorkitemContent';

const mermaidMock = vi.hoisted(() => ({
  initialize: vi.fn(),
  parse: vi.fn(),
  render: vi.fn(),
}));

vi.mock('mermaid', () => ({ default: mermaidMock }));

vi.mock('@/shared/lib/clipboard', () => ({
  copyTextToClipboard: vi.fn(),
}));

vi.mock('@/shared/auth/useAccessCommand', () => ({
  useAccessCommand: () => (_mode: string, _label: string, action: () => void) => action(),
}));

describe('WorkitemContent mermaid entry', () => {
  beforeEach(() => {
    mermaidMock.initialize.mockReset();
    mermaidMock.parse.mockReset();
    mermaidMock.render.mockReset();
    mermaidMock.parse.mockResolvedValue({ diagramType: 'flowchart-v2' });
    mermaidMock.render.mockResolvedValue({ svg: '<svg xmlns="http://www.w3.org/2000/svg" />' });
  });

  it('renders mermaid fences in the workitem body', async () => {
    render(<WorkitemContent title="Mermaid 工单" contentMd={'## 图形\n\n```mermaid\nflowchart TD\n  A-->B\n```'} />);

    await waitFor(() => expect(screen.getByTestId('mermaid-diagram')).toHaveAttribute('data-status', 'rendered'));
    expect(mermaidMock.render).toHaveBeenCalledWith(expect.any(String), 'flowchart TD\n  A-->B');
    expect(screen.getByRole('heading', { name: '图形' })).toBeInTheDocument();
  });
});
