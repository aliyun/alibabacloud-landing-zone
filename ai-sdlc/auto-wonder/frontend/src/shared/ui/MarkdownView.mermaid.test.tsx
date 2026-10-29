import { render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { Artifact } from '@/shared/types/workitem';
import { MarkdownView } from './MarkdownView';

const mermaidMock = vi.hoisted(() => ({
  initialize: vi.fn(),
  parse: vi.fn(),
  render: vi.fn(),
}));

vi.mock('mermaid', () => ({ default: mermaidMock }));

const artifact: Artifact = {
  id: 11,
  workitemId: 1,
  dispatchId: 2,
  name: 'report.md',
  type: 'DELIVERABLE',
  size: 10,
  gmtCreate: '2026-01-01T00:00:00Z',
};

const fence = (source: string, marker = '```') => `${marker}mermaid\n${source}\n${marker}`;

describe('MarkdownView mermaid fences', () => {
  beforeEach(() => {
    mermaidMock.initialize.mockReset();
    mermaidMock.parse.mockReset();
    mermaidMock.render.mockReset();
    mermaidMock.parse.mockResolvedValue({ diagramType: 'flowchart-v2' });
    mermaidMock.render.mockImplementation(async (_id: string, code: string) => ({ svg: `<svg data-source="${code.replace(/"/g, '&quot;')}" />` }));
  });

  it('renders all six supported diagram types as diagrams instead of source code', async () => {
    const sources = [
      'flowchart TD\n  A["提交需求"] --> B["查看图形"]',
      'sequenceDiagram\n  A->>B: 查看图形',
      'mindmap\n  root((需求))\n    图形',
      'stateDiagram-v2\n  [*] --> 待修复',
      'classDiagram\n  class 工单\n  工单 : 修复()',
      'erDiagram\n  工单 ||--o{ 文档 : 包含',
    ];

    render(<MarkdownView content={sources.map((source) => fence(source)).join('\n\n')} />);

    await waitFor(() => expect(screen.getAllByTestId('mermaid-diagram')).toHaveLength(6));
    await waitFor(() => {
      expect(screen.getAllByTestId('mermaid-diagram').map((node) => node.getAttribute('data-status')))
        .toEqual(['rendered', 'rendered', 'rendered', 'rendered', 'rendered', 'rendered']);
    });

    expect(mermaidMock.render.mock.calls.map((call: unknown[]) => call[1])).toEqual(sources);
    expect(document.querySelector('pre code.language-mermaid')).toBeNull();
  });

  it('supports tilde fences and leaves a trailing newline out of the source', async () => {
    const source = 'flowchart TD\n  A-->B';

    render(<MarkdownView content={`~~~mermaid\n${source}\n~~~`} />);

    await waitFor(() => expect(mermaidMock.render).toHaveBeenCalledWith(expect.any(String), source));
  });

  it('keeps text and other language fences plus inline code unchanged', () => {
    const { container } = render(
      <MarkdownView content={'```text\nflowchart TD\n  A-->B\n```\n\n```js\nconst a = 1;\n```\n\n行内 `code`'} />,
    );

    expect(screen.queryByTestId('mermaid-diagram')).toBeNull();
    expect(mermaidMock.render).not.toHaveBeenCalled();

    const fences = Array.from(container.querySelectorAll('pre code'));
    expect(fences).toHaveLength(2);
    expect(fences[0]).toHaveTextContent('flowchart TD');
    expect(fences[1]).toHaveTextContent('const a = 1;');
    expect(container.querySelectorAll('code')).toHaveLength(3);
  });

  it('protects diagram source from mention highlighting and artifact path linking', async () => {
    const source = 'flowchart TD\n  A["@成员 report.md"] --> B';

    render(<MarkdownView content={fence(source)} mentionNames={['成员']} artifacts={[artifact]} />);

    await waitFor(() => expect(mermaidMock.render).toHaveBeenCalledWith(expect.any(String), source));
    expect(document.querySelector('[data-type="mention"]')).toBeNull();
    expect(screen.queryByTestId('artifact-inline-link')).toBeNull();
  });

  it('keeps mention highlighting outside diagrams', async () => {
    render(<MarkdownView content={`@成员 查看\n\n${fence('flowchart TD\n  A-->B')}`} mentionNames={['成员']} />);

    await waitFor(() => expect(mermaidMock.render).toHaveBeenCalledTimes(1));
    expect(screen.getByText('@成员')).toBeInTheDocument();
  });

  it('passes untrusted markup inside a fence to mermaid as plain source', async () => {
    const source = 'flowchart TD\n  A["<img src=x onerror=alert(1)>"] --> B';

    render(<MarkdownView content={fence(source)} />);

    await waitFor(() => expect(mermaidMock.render).toHaveBeenCalledWith(expect.any(String), source));
    expect(document.querySelector('script')).toBeNull();
    expect(document.querySelector('img')).toBeNull();
  });

  it('isolates an invalid diagram and keeps neighbouring content and diagrams rendered', async () => {
    mermaidMock.parse.mockImplementation(async (text: string) => {
      if (text.includes('broken')) throw new Error('parse failed');
      return { diagramType: 'flowchart-v2' };
    });

    render(
      <MarkdownView
        content={`${fence('flowchart TD\n  A-->B')}\n\n${fence('broken mermaid')}\n\nD07 结束`}
      />,
    );

    await waitFor(() => {
      expect(screen.getAllByTestId('mermaid-diagram').map((node) => node.getAttribute('data-status')))
        .toEqual(['rendered', 'error']);
    });
    expect(screen.getByRole('alert')).toHaveTextContent('Mermaid 图形渲染失败');
    expect(screen.getByText('broken mermaid')).toBeInTheDocument();
    expect(screen.getByText('D07 结束')).toBeInTheDocument();
  });

  it('keeps headings, tables, task lists and links while a diagram renders', async () => {
    render(
      <MarkdownView
        content={`# 标题\n\n${fence('flowchart TD\n  A-->B')}\n\n| 列 | 值 |\n| --- | --- |\n| 一 | 二 |\n\n- [ ] 待办\n\n[链接](https://example.com)\n\n结束段落`}
      />,
    );

    await waitFor(() => expect(screen.getByTestId('mermaid-diagram')).toHaveAttribute('data-status', 'rendered'));
    expect(screen.getByRole('heading', { name: '标题' })).toBeInTheDocument();
    expect(screen.getByRole('table')).toBeInTheDocument();
    expect(screen.getByRole('checkbox')).toBeDisabled();
    expect(screen.getByRole('link', { name: '链接' })).toHaveAttribute('href', 'https://example.com');
    expect(screen.getByText('结束段落')).toBeInTheDocument();
  });

  it('renders an updated diagram when the content prop changes', async () => {
    const { rerender } = render(<MarkdownView content={fence('flowchart TD\n  A-->B')} />);
    await waitFor(() => expect(mermaidMock.render).toHaveBeenCalledTimes(1));

    rerender(<MarkdownView content={fence('flowchart LR\n  C-->D')} />);

    await waitFor(() => {
      const calls = mermaidMock.render.mock.calls;
      expect(calls[calls.length - 1][1]).toBe('flowchart LR\n  C-->D');
    });
  });

  it('still renders ordinary pre blocks that are not built from a code element', () => {
    const { container } = render(<MarkdownView content={'<pre>raw block</pre>'} />);

    expect(screen.queryByTestId('mermaid-diagram')).toBeNull();
    expect(container.querySelector('pre')).toHaveTextContent('raw block');
  });

  it('keeps artifact links for ordinary inline code', () => {
    render(<MarkdownView content={'`report.md`'} artifacts={[artifact]} />);

    expect(screen.getByTestId('artifact-inline-link')).toHaveTextContent('report.md');
  });
});
