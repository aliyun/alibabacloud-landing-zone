import { render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { FilePreview } from './FilePreview';

const mermaidMock = vi.hoisted(() => ({
  initialize: vi.fn(),
  parse: vi.fn(),
  render: vi.fn(),
}));

vi.mock('mermaid', () => ({ default: mermaidMock }));

const markdown = '# 需求\n\n```mermaid\nflowchart TD\n  A-->B\n```\n\n结束';

describe('FilePreview mermaid entry', () => {
  beforeEach(() => {
    mermaidMock.initialize.mockReset();
    mermaidMock.parse.mockReset();
    mermaidMock.render.mockReset();
    mermaidMock.parse.mockResolvedValue({ diagramType: 'flowchart-v2' });
    mermaidMock.render.mockResolvedValue({ svg: '<svg xmlns="http://www.w3.org/2000/svg" />' });
  });

  it.each(['requirements.md', 'requirements.markdown'])('renders mermaid fences from %s attachments', async (name) => {
    const loadBlob = vi.fn(() => new Response(markdown).blob());

    render(<FilePreview name={name} size={markdown.length} loadBlob={loadBlob} />);

    await waitFor(() => expect(screen.getByTestId('mermaid-diagram')).toHaveAttribute('data-status', 'rendered'));
    expect(mermaidMock.render).toHaveBeenCalledWith(expect.any(String), 'flowchart TD\n  A-->B');
    expect(screen.getByRole('heading', { name: '需求' })).toBeInTheDocument();
    expect(screen.getByText('结束')).toBeInTheDocument();
  });

  it('keeps plain text attachments as code instead of diagrams', async () => {
    const loadBlob = vi.fn(() => new Response(markdown).blob());

    const { container } = render(<FilePreview name="requirements.txt" size={markdown.length} loadBlob={loadBlob} />);

    await waitFor(() => expect(container.querySelector('pre.file-preview-text')).not.toBeNull());
    expect(container.querySelector('pre.file-preview-text')).toHaveTextContent('```mermaid');
    expect(screen.queryByTestId('mermaid-diagram')).toBeNull();
    expect(mermaidMock.render).not.toHaveBeenCalled();
  });

  it('keeps the rest of the document visible when a diagram fails', async () => {
    mermaidMock.parse.mockRejectedValue(new Error('parse failed'));
    const loadBlob = vi.fn(() => new Response('# 需求\n\n```mermaid\nbroken\n```\n\n结束').blob());

    render(<FilePreview name="requirements.md" size={markdown.length} loadBlob={loadBlob} />);

    expect(await screen.findByRole('alert')).toHaveTextContent('Mermaid 图形渲染失败');
    expect(screen.getByRole('heading', { name: '需求' })).toBeInTheDocument();
    expect(screen.getByText('结束')).toBeInTheDocument();
  });
});
