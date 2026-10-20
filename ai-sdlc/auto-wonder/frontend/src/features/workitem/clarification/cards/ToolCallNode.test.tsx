import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { ToolCallNode } from './ToolCallNode';
import type { TimelineToolCall } from '../timeline';

const call = (over: Partial<TimelineToolCall> = {}): TimelineToolCall => ({
  callId: 'c1',
  tool: 'Bash',
  status: 'completed',
  ...over,
});

describe('ToolCallNode', () => {
  it('shows the ACP title instead of the raw tool name when available', () => {
    render(<ToolCallNode tool={call({ toolKind: 'execute', title: '运行单元测试' })} />);
    expect(screen.getByText('运行单元测试')).toBeInTheDocument();
    expect(screen.getByTestId('tool-icon-execute')).toBeInTheDocument();
  });

  it('ellipsizes a long title while exposing its complete value on hover', () => {
    const title = '/Users/honeyfamily/autowonder_workspaces_qoder_10073/conversations/conv_79/.autowonder/capabilities/hash/package/repos.json';
    render(<ToolCallNode tool={call({ toolKind: 'read', title })} />);

    const titleNode = screen.getByTestId('tool-call-title');
    expect(titleNode).toHaveTextContent(title);
    expect(titleNode).toHaveAttribute('title', title);
    expect(titleNode).toHaveStyle({
      flex: '1',
      minWidth: '0',
      whiteSpace: 'nowrap',
      overflow: 'hidden',
      textOverflow: 'ellipsis',
    });
  });

  it('keeps a short title readable and status visible', () => {
    render(<ToolCallNode tool={call({ toolKind: 'read', title: '读取 repo-map.json' })} />);

    expect(screen.getByTestId('tool-call-title')).toHaveTextContent('读取 repo-map.json');
    expect(screen.getByText('已完成')).toBeInTheDocument();
  });

  it('falls back to the tool name and a generic icon without ACP data', () => {
    render(<ToolCallNode tool={call({ tool: 'MysteryTool' })} />);
    expect(screen.getByText('MysteryTool')).toBeInTheDocument();
    expect(screen.getByTestId('tool-icon-other')).toBeInTheDocument();
  });

  it('maps every declared ACP kind to its own icon', () => {
    const kinds = ['execute', 'read', 'edit', 'delete', 'move', 'search', 'think', 'fetch', 'other'] as const;
    for (const kind of kinds) {
      const view = render(<ToolCallNode tool={call({ toolKind: kind })} />);
      expect(screen.getByTestId(`tool-icon-${kind}`)).toBeInTheDocument();
      view.unmount();
    }
  });

  it('falls back to the generic icon for a kind outside the declared enum', () => {
    // toolKind 是执行器原样透传的 JSON，ACP 扩枚举时不能渲染出一个没有图标的空位
    render(<ToolCallNode tool={call({ toolKind: 'teleport' as never })} />);
    expect(screen.getByTestId('tool-icon-other')).toBeInTheDocument();
  });

  it('tags the call status so failures stand out', () => {
    const failed = render(<ToolCallNode tool={call({ status: 'failed' })} />);
    expect(screen.getByText('失败')).toBeInTheDocument();
    expect(failed.container.querySelector('.ant-tag-red')).not.toBeNull();
    failed.unmount();

    const done = render(<ToolCallNode tool={call({ status: 'completed' })} />);
    expect(done.container.querySelector('.ant-tag-green')).not.toBeNull();
    done.unmount();

    const running = render(<ToolCallNode tool={call({ status: 'running' })} />);
    expect(running.container.querySelector('.ant-tag')).not.toBeNull();
  });

  // F23：diff 是「Agent 改了什么」的唯一凭据，必须展示路径与前后文本。
  it('renders diffs with their path and both sides of the change', () => {
    render(
      <ToolCallNode
        tool={call({
          toolKind: 'edit',
          title: '编辑 hooks.ts',
          diffs: [
            { path: 'src/hooks.ts', oldText: 'const a = 1;', newText: 'const a = 2;' },
            { path: 'src/new.ts', oldText: null, newText: 'created' },
          ],
        })}
      />,
    );

    const first = screen.getByTestId('tool-diff-0');
    expect(first).toHaveTextContent('src/hooks.ts');
    expect(screen.getByTestId('tool-diff-old-0')).toHaveTextContent('const a = 1;');
    expect(screen.getByTestId('tool-diff-new-0')).toHaveTextContent('const a = 2;');

    // 新建文件没有 oldText，不能渲染出一个空的删除块
    expect(screen.getByTestId('tool-diff-1')).toHaveTextContent('src/new.ts');
    expect(screen.queryByTestId('tool-diff-old-1')).toBeNull();
    expect(screen.getByTestId('tool-diff-new-1')).toHaveTextContent('created');
  });

  it('renders a diff with only an old side when content was deleted', () => {
    render(
      <ToolCallNode
        tool={call({ diffs: [{ path: 'src/gone.ts', oldText: 'bye' }] })}
      />,
    );
    expect(screen.getByTestId('tool-diff-old-0')).toHaveTextContent('bye');
    expect(screen.queryByTestId('tool-diff-new-0')).toBeNull();
  });

  // F24：终端输出走非标准 _meta，渲染成终端块才看得懂。
  it('renders terminal output as a terminal block', () => {
    render(<ToolCallNode tool={call({ toolKind: 'execute', terminalOutput: '$ ls\nREADME.md' })} />);
    const terminal = screen.getByTestId('tool-terminal-output');
    expect(terminal).toHaveTextContent('README.md');
    expect(terminal.tagName).toBe('PRE');
  });

  it('renders locations, input and output details when present', () => {
    render(
      <ToolCallNode
        tool={call({
          toolKind: 'read',
          locations: [{ path: 'src/a.ts', line: 12 }, { path: 'src/b.ts' }],
          input: '{"path":"src/a.ts"}',
          output: 'file content',
        })}
      />,
    );

    expect(screen.getByTestId('tool-locations')).toHaveTextContent('src/a.ts:12');
    expect(screen.getByTestId('tool-locations')).toHaveTextContent('src/b.ts');
    expect(screen.getByTestId('tool-input')).toHaveTextContent('src/a.ts');
    expect(screen.getByTestId('tool-output')).toHaveTextContent('file content');
  });

  it('renders nothing extra for a bare call', () => {
    render(<ToolCallNode tool={call()} />);
    expect(screen.queryByTestId('tool-locations')).toBeNull();
    expect(screen.queryByTestId('tool-input')).toBeNull();
    expect(screen.queryByTestId('tool-output')).toBeNull();
    expect(screen.queryByTestId('tool-terminal-output')).toBeNull();
    expect(screen.queryByTestId('tool-diff-0')).toBeNull();
  });

  it('ignores an empty diff array instead of rendering an empty diff section', () => {
    render(<ToolCallNode tool={call({ diffs: [] })} />);
    expect(screen.queryByTestId('tool-diffs')).toBeNull();
  });
});
