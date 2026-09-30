import { describe, it, expect, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MarkdownComposer } from './MarkdownComposer';

describe('MarkdownComposer', () => {
  it('renders placeholder and mirrors value for tests', async () => {
    const onChange = vi.fn();
    render(
      <MarkdownComposer
        value="初始内容"
        onChange={onChange}
        onSendKey={() => {}}
        sendMode="shift-enter"
        placeholder="输入消息..."
        height={null}
      />,
    );
    expect(await screen.findByPlaceholderText('输入消息...')).toBeInTheDocument();
    expect(screen.getByPlaceholderText('输入消息...')).toHaveValue('初始内容');
    expect(document.querySelector('.ProseMirror')).not.toBeNull();
  });

  it('keeps untrusted attribute payloads inert through the public Markdown value boundary', async () => {
    const payload = '<a href="https://example.com" onclick="alert(1)">raw</a>\n\n'
      + '`{"__proto__":{"onmouseover":"alert(1)"}}`\n\n[unsafe](javascript:alert(1))';
    const props = { onChange: () => {}, onSendKey: () => {}, sendMode: 'shift-enter' as const, height: null };
    const { rerender } = render(<MarkdownComposer {...props} value={payload} />);
    const assertInert = () => {
      const content = document.querySelector('.ProseMirror')!;
      expect(content.textContent).toContain('__proto__');
      expect(content.querySelector('script, [onclick], [onmouseover], a[href^="javascript:"]')).toBeNull();
      expect(Object.prototype).not.toHaveProperty('onmouseover');
    };
    assertInert();
    rerender(<MarkdownComposer {...props} value={'Updated\n\n' + payload} />);
    await waitFor(() => expect(document.querySelector('.ProseMirror')).toHaveTextContent('Updated'));
    assertInert();
  });

  it('focuses the editor when clicking empty space inside the input', () => {
    render(<MarkdownComposer value="" onChange={() => {}} onSendKey={() => {}} sendMode="shift-enter" height={180} />);
    fireEvent.click(screen.getByTestId('clarification-composer-input'));
    expect(document.activeElement).toHaveClass('ProseMirror');
  });

  it('markdown shortcuts turn * + space into a list item', () => {
    const onChange = vi.fn();
    render(
      <MarkdownComposer
        value=""
        onChange={onChange}
        onSendKey={() => {}}
        sendMode="shift-enter"
        placeholder="输入消息..."
        height={null}
      />,
    );
    const editorEl = screen.getByTestId('clarification-composer-input') as HTMLElement & {
      __composerEditor?: import('@tiptap/core').Editor;
    };
    const editor = editorEl.__composerEditor;
    expect(editor).toBeTruthy();
    // 模拟逐字输入走 ProseMirror 的 handleTextInput，命中 markdown 输入规则
    const view = editor!.view;
    const type = (text: string) => {
      const { from, to } = view.state.selection;
      view.someProp('handleTextInput', (f) => {
        f(view, from, to, text, () => view.state.tr);
        return false;
      });
      view.dispatch(view.state.tr.insertText(text, from, to));
    };
    '* 项目一'.split('').forEach(type);
    expect(document.querySelector('ul li')).not.toBeNull();
    const last = onChange.mock.calls.at(-1)?.[0] as string;
    expect(last.replace(/\s+/g, '')).toBe('-项目一');
  });

  it('writing markdown renders document structure and reports markdown', () => {
    const onChange = vi.fn();
    render(
      <MarkdownComposer
        value=""
        onChange={onChange}
        onSendKey={() => {}}
        sendMode="shift-enter"
        placeholder="输入消息..."
        height={null}
      />,
    );
    const editorEl = screen.getByTestId('clarification-composer-input') as HTMLElement & {
      __composerEditor?: import('@tiptap/core').Editor;
    };
    const editor = editorEl.__composerEditor;
    expect(editor).toBeTruthy();
    editor!.commands.setContent('# 标题\n\n- 列表项', true);
    expect(onChange).toHaveBeenCalled();
    const last = onChange.mock.calls.at(-1)?.[0] as string;
    expect(last).toContain('标题');
    expect(last).toContain('列表项');
    expect(document.querySelector('h1')).not.toBeNull();
    expect(document.querySelector('ul li')).not.toBeNull();
  });

  it('preserves the Markdown syntax displayed by chat messages', () => {
    render(<MarkdownComposer value="" onChange={() => {}} onSendKey={() => {}} sendMode="shift-enter" height={null} />);
    const root = screen.getByTestId('clarification-composer-input') as HTMLElement & {
      __composerEditor?: import('@tiptap/core').Editor;
    };
    const editor = root.__composerEditor!;
    editor.commands.setContent('[文档](https://example.com) 和 ~~删除~~\n\n- [x] 完成\n- [ ] 待办\n\n| A | B |\n| --- | --- |\n| x | y |', true);
    const markdown = editor.storage.markdown.getMarkdown();
    expect(markdown).toContain('[文档](https://example.com)');
    expect(markdown).toContain('~~删除~~');
    expect(markdown).toContain('[x] 完成');
    expect(markdown).toContain('[ ] 待办');
    expect(markdown).toContain('| A | B |');
  });

  it('keeps a Markdown link typed into the composer', () => {
    render(<MarkdownComposer value="" onChange={() => {}} onSendKey={() => {}} sendMode="shift-enter" height={null} />);
    const root = screen.getByTestId('clarification-composer-input') as HTMLElement & {
      __composerEditor?: import('@tiptap/core').Editor;
    };
    const editor = root.__composerEditor!;
    const view = editor.view;
    for (const text of '[文档](https://example.com)') {
      const { from, to } = view.state.selection;
      const handled = view.someProp('handleTextInput', (handler) =>
        handler(view, from, to, text, () => view.state.tr));
      if (!handled) view.dispatch(view.state.tr.insertText(text, from, to));
    }
    expect(editor.storage.markdown.getMarkdown()).toContain('[文档](https://example.com)');
    editor.commands.clearContent();
    for (const text of '[x] 完成') {
      const { from, to } = view.state.selection;
      const handled = view.someProp('handleTextInput', (handler) =>
        handler(view, from, to, text, () => view.state.tr));
      if (!handled) view.dispatch(view.state.tr.insertText(text, from, to));
    }
    expect(editor.storage.markdown.getMarkdown()).toContain('[x] 完成');
  });

  it('clears the editor when an externally prefilled value is later cleared', async () => {
    const onChange = vi.fn();
    const props = {
      onChange,
      onSendKey: () => {},
      sendMode: 'shift-enter' as const,
      placeholder: '输入消息...',
      height: null,
    };
    const view = render(<MarkdownComposer value="" {...props} />);
    const prompt = '请通过平台 MCP 读取工单 #100，与我进行需求澄清。';

    // 外部预填（未经 onUpdate）→ 编辑器出现提示语
    view.rerender(<MarkdownComposer value={prompt} {...props} />);
    const mirror = await screen.findByTestId('clarification-composer-mirror') as HTMLTextAreaElement;
    await waitFor(() => expect(mirror).toHaveValue(prompt));

    // 发送后外部清空 → 编辑器必须跟着清空，提示语不能残留
    view.rerender(<MarkdownComposer value="" {...props} />);
    await waitFor(() => expect(mirror).toHaveValue(''));
    const editorEl = screen.getByTestId('clarification-composer-input') as HTMLElement & {
      __composerEditor?: import('@tiptap/core').Editor;
    };
    expect(editorEl.__composerEditor!.getText()).toBe('');
  });
});
