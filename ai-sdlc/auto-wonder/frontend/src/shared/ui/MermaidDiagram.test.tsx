import { act, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { MermaidDiagram } from './MermaidDiagram';

const mermaidMock = vi.hoisted(() => ({
  initialize: vi.fn(),
  parse: vi.fn(),
  render: vi.fn(),
}));

vi.mock('mermaid', () => ({ default: mermaidMock }));

const svg = (label: string) => `<svg xmlns="http://www.w3.org/2000/svg" data-label="${label}"><text>${label}</text></svg>`;

describe('MermaidDiagram', () => {
  beforeEach(() => {
    mermaidMock.initialize.mockReset();
    mermaidMock.parse.mockReset();
    mermaidMock.render.mockReset();
    mermaidMock.parse.mockResolvedValue({ diagramType: 'flowchart-v2' });
    mermaidMock.render.mockResolvedValue({ svg: svg('ok') });
  });

  it('renders the generated svg with strict security and a unique render id', async () => {
    const { container } = render(<MermaidDiagram code={'flowchart TD\n  A-->B'} />);

    expect(screen.getByTestId('mermaid-diagram')).toHaveAttribute('data-status', 'loading');

    await waitFor(() => expect(screen.getByTestId('mermaid-diagram')).toHaveAttribute('data-status', 'rendered'));

    expect(mermaidMock.initialize).toHaveBeenCalledWith({
      startOnLoad: false,
      securityLevel: 'strict',
      theme: 'default',
      fontFamily: 'inherit',
    });
    expect(mermaidMock.parse).toHaveBeenCalledWith('flowchart TD\n  A-->B');
    expect(mermaidMock.render).toHaveBeenCalledWith(expect.stringMatching(/^aw-mermaid-\d+-\d+$/), 'flowchart TD\n  A-->B');
    expect(container.querySelector('svg[data-label="ok"]')).not.toBeNull();
  });

  it('falls back to source for an invalid diagram without invoking render', async () => {
    mermaidMock.parse.mockResolvedValue(false);

    render(<MermaidDiagram code="not a diagram" />);

    expect(await screen.findByRole('alert')).toHaveTextContent('Mermaid 图形渲染失败');
    expect(screen.getByTestId('mermaid-diagram')).toHaveAttribute('data-status', 'error');
    expect(screen.getByText('not a diagram')).toBeInTheDocument();
    expect(mermaidMock.render).not.toHaveBeenCalled();
  });

  it('treats a rejected parse as a local error', async () => {
    mermaidMock.parse.mockRejectedValue(new Error('parse failed'));

    render(<MermaidDiagram code="bad syntax" />);

    expect(await screen.findByRole('alert')).toBeInTheDocument();
    expect(mermaidMock.render).not.toHaveBeenCalled();
  });

  it('removes the temporary error node mermaid leaves in the body', async () => {
    let stray: HTMLElement | null = null;
    mermaidMock.render.mockImplementation(async (id: string) => {
      stray = document.createElement('div');
      stray.id = `d${id}`;
      document.body.appendChild(stray);
      throw new Error('render failed');
    });

    render(<MermaidDiagram code="bad" />);
    expect(await screen.findByRole('alert')).toBeInTheDocument();

    expect(stray).not.toBeNull();
    expect(document.getElementById((stray as unknown as HTMLElement).id)).toBeNull();
  });

  it('keeps concurrent diagrams isolated with unique ids and their own output', async () => {
    mermaidMock.render.mockImplementation(async (_id: string, code: string) => ({ svg: svg(code) }));

    const { container } = render(
      <>
        <MermaidDiagram code="first" />
        <MermaidDiagram code="second" />
      </>,
    );

    await waitFor(() => {
      expect(screen.getAllByTestId('mermaid-diagram').map((node) => node.getAttribute('data-status')))
        .toEqual(['rendered', 'rendered']);
    });

    const ids = mermaidMock.render.mock.calls.map((call: unknown[]) => call[0] as string);
    expect(new Set(ids).size).toBe(2);
    expect(container.querySelectorAll('svg[data-label="first"]')).toHaveLength(1);
    expect(container.querySelectorAll('svg[data-label="second"]')).toHaveLength(1);
  });

  it('ignores a late render result after the code changes', async () => {
    let resolveFirst: (value: { svg: string }) => void = () => undefined;
    mermaidMock.render
      .mockImplementationOnce(() => new Promise<{ svg: string }>((resolve) => { resolveFirst = resolve; }))
      .mockResolvedValueOnce({ svg: svg('new') });

    const { container, rerender } = render(<MermaidDiagram code="old" />);
    await waitFor(() => expect(mermaidMock.render).toHaveBeenCalledTimes(1));

    rerender(<MermaidDiagram code="new" />);
    await waitFor(() => expect(container.querySelector('svg[data-label="new"]')).not.toBeNull());

    await act(async () => { resolveFirst({ svg: svg('old') }); });

    expect(container.querySelector('svg[data-label="old"]')).toBeNull();
    expect(container.querySelector('svg[data-label="new"]')).not.toBeNull();
  });

  it('does not commit a result after unmount', async () => {
    let resolveRender: (value: { svg: string }) => void = () => undefined;
    mermaidMock.render.mockImplementation(() => new Promise<{ svg: string }>((resolve) => { resolveRender = resolve; }));

    const { unmount } = render(<MermaidDiagram code="pending" />);
    await waitFor(() => expect(mermaidMock.render).toHaveBeenCalledTimes(1));

    unmount();
    await act(async () => { resolveRender({ svg: svg('late') }); });

    expect(document.querySelector('svg[data-label="late"]')).toBeNull();
  });

  it('does not re-render the diagram when props stay unchanged', async () => {
    const { rerender } = render(<MermaidDiagram code="same" />);
    await waitFor(() => expect(mermaidMock.render).toHaveBeenCalledTimes(1));

    rerender(<MermaidDiagram code="same" />);
    await act(async () => undefined);

    expect(mermaidMock.render).toHaveBeenCalledTimes(1);
  });
});
