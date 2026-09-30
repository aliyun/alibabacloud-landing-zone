import { useEffect, useImperativeHandle, useRef } from 'react';
import { Editor, InputRule } from '@tiptap/core';
import { EditorContent, useEditor } from '@tiptap/react';
import StarterKit from '@tiptap/starter-kit';
import Placeholder from '@tiptap/extension-placeholder';
import Table from '@tiptap/extension-table';
import TableRow from '@tiptap/extension-table-row';
import TableCell from '@tiptap/extension-table-cell';
import TableHeader from '@tiptap/extension-table-header';
import Link from '@tiptap/extension-link';
import TaskList from '@tiptap/extension-task-list';
import TaskItem from '@tiptap/extension-task-item';
import { Markdown } from 'tiptap-markdown';
import { shouldSendOnKey, type SendMode } from './sendMode';
import './markdownComposer.css';

export interface MarkdownComposerHandle {
  focus: (options?: { preventScroll?: boolean }) => void;
  editor: Editor | null;
}

interface MarkdownComposerProps {
  /** Markdown 源文本（受控）。内部编辑 → onChange 回流；外部赋值（预填/清空）→ 同步进编辑器。 */
  value: string;
  onChange: (markdown: string) => void;
  /** 按当前发送方式偏好触发发送；是否真的发送由父级守卫（回复中/加载中等）。 */
  onSendKey: () => void;
  sendMode: SendMode;
  disabled?: boolean;
  placeholder?: string;
  /** null = 自动高度（minRows 高度起步，maxHeight 封顶滚动）；数字 = 手动固定高。 */
  height: number | null;
  minHeight?: number;
  maxHeight?: number;
  handleRef?: React.MutableRefObject<MarkdownComposerHandle | null>;
}

/** 编辑器容器上暴露实例，测试用它驱动内容与断言，不必模拟真实击键。 */
function useExposeEditor(rootRef: React.RefObject<HTMLDivElement | null>, editor: Editor | null) {
  useEffect(() => {
    const el = rootRef.current;
    if (!el) return;
    (el as HTMLDivElement & { __composerEditor?: Editor | null }).__composerEditor = editor;
  }, [rootRef, editor]);
}

/**
 * 钉钉文档式的 Markdown「即输即渲染」编辑器：敲 `# `/`* `/`> `/``` 等
 * 立刻转成对应块级元素，`**粗体**`、`` `代码` `` 等行内语法在输入闭合时生效；
 * 粘贴的 Markdown 文本同样解析。文档模型与 Markdown 字符串双向同步
 * （tiptap-markdown），发送出去的仍是纯 Markdown 源文本。
 *
 * 发送键沿用既有偏好（sendMode）：
 * - shift-enter：Enter 换行（继续列表等结构），Shift+Enter 发送；
 * - enter：Enter 发送，Shift+Enter 换行；光标在列表项内时 Enter 保留默认行为
 *   （继续/退出列表），此时用发送按钮发送。
 * IME 组合期（isComposing）一律不当作发送。
 */
export function MarkdownComposer({
  value,
  onChange,
  onSendKey,
  sendMode,
  disabled = false,
  placeholder = '',
  height,
  minHeight = 132,
  maxHeight,
  handleRef,
}: MarkdownComposerProps) {
  const rootRef = useRef<HTMLDivElement>(null);
  const lastEmittedRef = useRef<string>('');
  const editorRef = useRef<Editor | null>(null);
  const sendModeRef = useRef(sendMode);
  sendModeRef.current = sendMode;

  const editor = useEditor({
    extensions: [
      StarterKit,
      Placeholder.configure({ placeholder }),
      Table.configure({ resizable: false }),
      TableRow,
      TableCell,
      TableHeader,
      Link.extend({
        addInputRules() {
          return [new InputRule({
            find: /\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)$/,
            handler: ({ state, range, match }) => {
              state.tr.replaceWith(range.from, range.to,
                state.schema.text(match[1], [this.type.create({ href: match[2] })]));
            },
          })];
        },
      }).configure({ openOnClick: false }),
      TaskList,
      TaskItem.configure({ nested: true }),
      Markdown.configure({
        html: false,
        linkify: true,
        breaks: false,
        transformPastedText: true,
        transformCopiedText: false,
      }),
    ],
    content: value,
    editable: !disabled,
    onUpdate: ({ editor: current, transaction }) => {
      // setEditable 的 dispatch 未带 preventUpdate：disabled 切换（回复中/创建中）会以
      // 旧内容触发一次 update，把刚被外部清空的草稿写回 state。这类「非内容变化」的事务跳过。
      if (transaction.getMeta('preventUpdate') || !current.isEditable) return;
      const md = current.storage.markdown.getMarkdown();
      lastEmittedRef.current = md;
      onChange(md);
    },
  });

  useEffect(() => {
    if (!editor) return;
    editor.setEditable(!disabled);
  }, [editor, disabled]);

  // 外部赋值同步：仅当 markdown 与编辑器当前序列化结果不一致时写回，
  // 避免打字回流（onChange → value prop）造成光标重置。
  useEffect(() => {
    if (!editor) return;
    // value 就是编辑器刚发上来的值（lastEmitted）：说明这次重渲来自 onUpdate 回流，
    // 而非外部赋值。此时即便 getMarkdown() 与 value 有瞬时不一致（序列化时点差异），
    // 也绝不能把编辑器写回旧值——那会与 onUpdate 形成回写循环，把刚清空的输入复活。
    if (value === lastEmittedRef.current) return;
    if (value === editor.storage.markdown.getMarkdown()) return;
    // setContent 不触发 onUpdate（emitUpdate=false），lastEmitted 不会自行前进；
    // 外部赋值若不记账，发送后清空（value 回到上上次的外部值 ''）会被首个
    // 判定误认成回流而跳过，预填的提示语就留在编辑器里发不出去也不消失。
    lastEmittedRef.current = value;
    editor.commands.setContent(value, false);
  }, [editor, value]);

  editorRef.current = editor ?? null;

  useImperativeHandle(handleRef, () => ({
    focus: (options?: { preventScroll?: boolean }) => {
      const view = editor?.view;
      if (!view) return;
      view.focus();
      if (!options?.preventScroll && typeof view.dom.scrollIntoView === 'function') {
        view.dom.scrollIntoView({ block: 'nearest' });
      }
    },
    editor: editor ?? null,
  }), [editor]);

  useExposeEditor(rootRef, editor);

  const sendGuard = (e: React.KeyboardEvent) => {
    if (e.nativeEvent.isComposing) return;
    // 回车发送模式下，光标在列表项内时回车保留「继续/退出列表」的编辑行为
    if (sendModeRef.current === 'enter' && e.key === 'Enter' && !e.shiftKey
        && selectionInListItem(editor)) {
      return;
    }
    if (!shouldSendOnKey(sendModeRef.current, e)) return;
    e.preventDefault();
    e.stopPropagation();
    onSendKey();
  };

  return (
    <div
      ref={rootRef}
      data-testid="clarification-composer-input"
      data-disabled={disabled ? 'true' : 'false'}
      className={`aw-markdown-composer${disabled ? ' is-disabled' : ''}`}
      onClick={(event) => {
        if (!disabled && !(event.target as HTMLElement).closest('.ProseMirror')) {
          editor?.view.focus();
        }
      }}
      // capture 阶段拦截发送键，抢在 ProseMirror keymap 之前
      onKeyDownCapture={sendGuard}
      style={height == null
        ? { minHeight, maxHeight: maxHeight ?? undefined, overflowY: 'auto' }
        : { height, overflowY: 'auto' }}
    >
      {/* 隐藏镜像 textarea：仅承载 placeholder/value/disabled 语义供测试断言
          （findByPlaceholderText / toHaveValue / toBeDisabled / fireEvent.change），
          同时兜底无障碍与表单语义。不可聚焦、不参与布局。 */}
      <textarea
        data-testid="clarification-composer-mirror"
        aria-hidden="true"
        tabIndex={-1}
        placeholder={placeholder}
        value={value}
        disabled={disabled}
        readOnly
        style={{
          position: 'absolute', width: 1, height: 1,
          padding: 0, border: 0, margin: 0,
          overflow: 'hidden', clip: 'rect(0 0 0 0)', clipPath: 'inset(50%)',
          whiteSpace: 'nowrap', opacity: 0,
        }}
      />
      <EditorContent editor={editor} />
    </div>
  );
}

function selectionInListItem(editor: Editor | null): boolean {
  if (!editor) return false;
  const { $from } = editor.state.selection;
  for (let depth = $from.depth; depth > 0; depth -= 1) {
    if ($from.node(depth).type.name === 'listItem') return true;
  }
  return false;
}
