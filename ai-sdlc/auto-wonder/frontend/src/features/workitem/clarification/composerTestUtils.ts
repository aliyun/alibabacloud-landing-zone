import { screen, waitFor, act } from '@testing-library/react';
import { expect } from 'vitest';
import type { Editor } from '@tiptap/core';

/** 测试辅助：驱动钉钉式 Markdown 编辑器（替代旧 TextArea 的 fireEvent.change/keyDown）。
 *  Mirror textarea 仅承载 placeholder/value/disabled 断言语义，不再回写编辑器；
 *  内容写入与击键都走真实的编辑器 DOM。 */

export function getComposerEditor(): Editor {
  const root = screen.getByTestId('clarification-composer-input') as HTMLElement & {
    __composerEditor?: Editor;
  };
  const editor = root.__composerEditor;
  if (!editor) throw new Error('composer editor not mounted');
  return editor;
}

/** 写入整段 Markdown（走正常 onUpdate 链，React state 与镜像同步更新）。 */
export async function typeComposer(value: string) {
  const editor = getComposerEditor();
  await act(async () => {
    editor.commands.setContent(value, true);
  });
}

/** 在编辑器 DOM 上派发按键（Enter/Shift+Enter 等，走 onKeyDownCapture 发送守卫）。 */
export function pressComposerKey(init: { key: string; shiftKey?: boolean; isComposing?: boolean }) {
  const pm = document.querySelector('.ProseMirror') as HTMLElement;
  if (!pm) throw new Error('.ProseMirror not mounted');
  pm.dispatchEvent(
    new KeyboardEvent('keydown', {
      key: init.key,
      bubbles: true,
      cancelable: true,
      shiftKey: init.shiftKey,
    }),
  );
}

/** 等 Markdown 编辑器挂载完毕（用于 focus 断言前）。 */
export async function findComposerMirror(): Promise<HTMLTextAreaElement> {
  return await screen.findByPlaceholderText('输入消息...') as HTMLTextAreaElement;
}

/** 编辑器 contenteditable 是否持有焦点。 */
export async function waitForComposerFocus(opts: { focused: boolean }) {
  await waitFor(() => {
    const pm = document.querySelector('.ProseMirror') as HTMLElement | null;
    expect(pm).not.toBeNull();
    const hasFocus = pm?.hasAttribute('tabindex')
      ? document.activeElement === pm || pm?.contains(document.activeElement)
      : pm?.contains(document.activeElement);
    expect(Boolean(hasFocus)).toBe(opts.focused);
  });
}
