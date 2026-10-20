import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act, fireEvent, render, screen, within, waitFor } from '@testing-library/react';
import { message } from 'antd';

const { mutateMock, uploadMock, mentionCandidatesMock, mentionQueries } = vi.hoisted(() => ({
  mutateMock: vi.fn(),
  uploadMock: vi.fn(),
  mentionCandidatesMock: vi.fn(),
  mentionQueries: [] as Array<string | null | undefined>,
}));

vi.mock('../hooks', () => ({
  useAddComment: () => ({ mutate: mutateMock, isPending: false }),
  useUploadRequirementDocuments: () => ({ mutateAsync: uploadMock, isPending: false }),
  useMentionCandidates: (_workitemId: unknown, query: string | null | undefined) => {
    mentionQueries.push(query);
    return { data: mentionCandidatesMock(query) };
  },
}));

vi.mock('@/shared/auth/useAccessCommand', () => ({
  useAccessCommand: () => vi.fn((_required: unknown, _action: string, command: () => unknown) => command()),
}));

import { CommentInput } from './CommentInput';
import { commentAttachmentMarkdown } from './CommentAttachment';
import { MarkdownView } from '@/shared/ui/MarkdownView';
import { ApiError } from '@/shared/types/common';
import type { Artifact, Participant } from '@/shared/types/workitem';

function buildCandidates(count: number): Participant[] {
  return Array.from({ length: count }, (_, index) => ({
    userId: index + 1,
    name: `成员${index + 1}`,
    role: 'DEV',
    roleName: '开发',
    isAgent: index % 2 === 0,
    online: true,
  }));
}

function openMentionMenu() {
  const textarea = screen.getByPlaceholderText('输入评论，键入 @ 选择成员...') as HTMLTextAreaElement;
  fireEvent.change(textarea, { target: { value: '@', selectionStart: 1 } });
  fireEvent.keyUp(textarea);
  return screen.getByRole('menu');
}

function composer() {
  return screen.getByPlaceholderText('输入评论，键入 @ 选择成员...') as HTMLTextAreaElement;
}

function insertAgentMention() {
  const textarea = composer();
  fireEvent.change(textarea, { target: { value: '@', selectionStart: 1 } });
  fireEvent.keyUp(textarea);
  fireEvent.click(screen.getAllByRole('menuitem')[0]);
}

function clickSend() {
  fireEvent.click(screen.getByRole('button', { name: /发\s*送/ }));
}

function latestMutationOptions(): { onSuccess: () => void; onError: (error: unknown) => void } {
  const call = mutateMock.mock.calls.at(-1);
  if (!call) throw new Error('mutate was not called');
  return call[1] as { onSuccess: () => void; onError: (error: unknown) => void };
}

describe('CommentInput mention menu', () => {
  beforeEach(() => {
    mentionCandidatesMock.mockReset();
    mentionQueries.length = 0;
  });

  it('limits the popup height and enables scrolling with many candidates', () => {
    mentionCandidatesMock.mockReturnValue(buildCandidates(15));
    render(<CommentInput workitemId="1" />);
    const menu = openMentionMenu();
    expect(menu.style.overflowY).toBe('auto');
    const maxHeight = Number.parseInt(menu.style.maxHeight, 10);
    expect(maxHeight).toBeGreaterThan(0);
    expect(maxHeight).toBeLessThanOrEqual(400);
  });

  it('still renders every candidate inside the scrollable menu', () => {
    mentionCandidatesMock.mockReturnValue(buildCandidates(15));
    render(<CommentInput workitemId="1" />);
    openMentionMenu();
    expect(screen.getAllByRole('menuitem')).toHaveLength(15);
  });

  it('skips candidates with null names instead of crashing the detail page', () => {
    const candidates: Participant[] = [
      ...buildCandidates(2),
      {
        userId: 40013,
        targetType: 'AGENT',
        name: null as unknown as string,
        displayId: '40013',
        role: 'AGENT',
        roleName: '开发小队成员',
        isAgent: true,
        online: false,
      },
    ];
    mentionCandidatesMock.mockReturnValue(candidates);
    render(<CommentInput workitemId="1" />);
    const menu = openMentionMenu();
    expect(menu).toBeInTheDocument();
    expect(screen.getAllByRole('menuitem')).toHaveLength(2);
  });
});

describe('CommentInput mention candidate loading', () => {
  beforeEach(() => {
    mentionCandidatesMock.mockReset();
    mentionQueries.length = 0;
    mentionCandidatesMock.mockReturnValue(buildCandidates(3));
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('debounces remote candidate requests and filters the held candidates locally meanwhile', () => {
    vi.useFakeTimers();
    render(<CommentInput workitemId="1" />);
    const textarea = composer();

    fireEvent.change(textarea, { target: { value: '@成员', selectionStart: 3 } });
    expect(screen.getAllByRole('menuitem')).toHaveLength(3);
    expect(mentionQueries.filter((query) => query !== null)).toEqual([]);

    fireEvent.change(textarea, { target: { value: '@成员1', selectionStart: 4 } });
    expect(mentionQueries.filter((query) => query !== null)).toEqual([]);
    expect(screen.getAllByRole('menuitem')).toHaveLength(1);

    act(() => { vi.advanceTimersByTime(199); });
    expect(mentionQueries.filter((query) => query !== null)).toEqual([]);

    act(() => { vi.advanceTimersByTime(1); });
    expect(mentionQueries.filter((query) => query !== null)).toEqual(['成员1']);
  });

  it('coalesces rapid keystrokes into the final query', () => {
    vi.useFakeTimers();
    render(<CommentInput workitemId="1" />);
    const textarea = composer();

    for (const [value, caret] of [['@', 1], ['@成', 2], ['@成员', 3]] as const) {
      fireEvent.change(textarea, { target: { value, selectionStart: caret } });
      act(() => { vi.advanceTimersByTime(120); });
    }
    expect(mentionQueries.filter((query) => query !== null)).toEqual([]);

    act(() => { vi.advanceTimersByTime(200); });
    expect(mentionQueries.filter((query) => query !== null)).toEqual(['成员']);
  });

  it('skips state updates when keyup and click derive the same mention query', () => {
    vi.useFakeTimers();
    render(<CommentInput workitemId="1" />);
    const textarea = composer();

    fireEvent.focus(textarea);
    fireEvent.change(textarea, { target: { value: '@成员', selectionStart: 3 } });
    act(() => { vi.advanceTimersByTime(200); });
    const renderedAfterQuery = mentionQueries.length;

    fireEvent.keyUp(textarea);
    fireEvent.keyUp(textarea);
    fireEvent.click(textarea);

    expect(mentionQueries.length).toBe(renderedAfterQuery);
    expect(screen.getAllByRole('menuitem')).toHaveLength(3);
  });

  it('falls back to loaded participants before the first remote candidate result arrives', () => {
    mentionCandidatesMock.mockReturnValue(undefined);
    render(<CommentInput workitemId="1" participants={buildCandidates(2)} />);
    fireEvent.change(composer(), { target: { value: '@成员', selectionStart: 3 } });
    expect(screen.getAllByRole('menuitem')).toHaveLength(2);
  });
});

describe('CommentInput delivery-closed feedback', () => {
  beforeEach(() => {
    mutateMock.mockClear();
  });

  afterEach(() => {
    act(() => {
      message.destroy();
    });
    vi.restoreAllMocks();
  });

  it('explains a closed delivery inline and keeps the draft instead of a generic toast', () => {
    const errorSpy = vi.spyOn(message, 'error').mockImplementation(() => undefined as never);
    render(<CommentInput workitemId="1" participants={buildCandidates(2)} />);
    insertAgentMention();
    clickSend();

    expect(mutateMock).toHaveBeenCalledWith(
      { workitemId: '1', contentMd: '@成员1', targetAgentIds: [1], targetHumanIds: [] },
      expect.any(Object),
    );

    act(() => {
      latestMutationOptions().onError(
        new ApiError('13024', '评论未发送：该工单的交付已关闭', null),
      );
    });

    const alert = screen.getByRole('alert');
    expect(within(alert).getByText('评论未发送：交付已关闭')).toBeInTheDocument();
    expect(within(alert).getByText(/请先重新打开交付/)).toBeInTheDocument();
    expect(composer().value).toBe('@成员1 ');
    expect(errorSpy).not.toHaveBeenCalled();
  });

  it('shows the backend reason for other conflicts without the closed-delivery notice', () => {
    const errorSpy = vi.spyOn(message, 'error').mockImplementation(() => undefined as never);
    render(<CommentInput workitemId="1" participants={buildCandidates(2)} />);
    insertAgentMention();
    clickSend();

    act(() => {
      latestMutationOptions().onError(
        new ApiError('10409', '该 Worker 正在处理评论交互，请稍后再试', null),
      );
    });

    expect(errorSpy).toHaveBeenCalledWith('该 Worker 正在处理评论交互，请稍后再试');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(composer().value).toBe('@成员1 ');
  });

  it('falls back to the retry hint for unknown failures', () => {
    const errorSpy = vi.spyOn(message, 'error').mockImplementation(() => undefined as never);
    render(<CommentInput workitemId="1" participants={buildCandidates(2)} />);
    insertAgentMention();
    clickSend();

    act(() => {
      latestMutationOptions().onError(new Error('network down'));
    });

    expect(errorSpy).toHaveBeenCalledWith('评论发送失败，请重试');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('resubmits after removing the mention and clears the notice on success', () => {
    const errorSpy = vi.spyOn(message, 'error').mockImplementation(() => undefined as never);
    const successSpy = vi.spyOn(message, 'success').mockImplementation(() => undefined as never);
    render(<CommentInput workitemId="1" participants={buildCandidates(2)} />);
    insertAgentMention();
    clickSend();
    act(() => {
      latestMutationOptions().onError(new ApiError('13024', '评论未发送：该工单的交付已关闭', null));
    });
    expect(screen.getByRole('alert')).toBeInTheDocument();

    fireEvent.change(composer(), { target: { value: '移除提及后的普通评论', selectionStart: 10 } });
    clickSend();
    expect(mutateMock.mock.calls.at(-1)?.[0]).toMatchObject({
      contentMd: '移除提及后的普通评论',
      targetAgentIds: [],
      targetHumanIds: [],
    });

    act(() => {
      latestMutationOptions().onSuccess();
    });

    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(composer().value).toBe('');
    expect(successSpy).toHaveBeenCalledWith('评论已发送');
    expect(errorSpy).not.toHaveBeenCalled();
  });

  it('clears a previous closed-delivery notice when a later failure is unrelated', () => {
    const errorSpy = vi.spyOn(message, 'error').mockImplementation(() => undefined as never);
    render(<CommentInput workitemId="1" participants={buildCandidates(2)} />);
    insertAgentMention();
    clickSend();
    act(() => {
      latestMutationOptions().onError(new ApiError('13024', '评论未发送：该工单的交付已关闭', null));
    });
    expect(screen.getByRole('alert')).toBeInTheDocument();

    clickSend();
    act(() => {
      latestMutationOptions().onError(
        new ApiError('10409', '该 Worker 正在处理评论交互，请稍后再试', null),
      );
    });

    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(errorSpy).toHaveBeenCalledWith('该 Worker 正在处理评论交互，请稍后再试');
    expect(composer().value).toBe('@成员1 ');
  });
});


describe('CommentInput attachments', () => {
  const file = (name = 'spec.md', type = 'text/markdown') => new File(['test'], name, { type });
  const artifact = (name = 'spec.md', id = 10): Artifact => ({
    id, name: `requirements/${name}`, workitemId: 1, dispatchId: null,
    type: 'REQUIREMENT_DOC', size: 4, gmtCreate: '2026-10-14T00:00:00Z',
  });
  const select = (...files: File[]) => fireEvent.change(screen.getByTestId('comment-file-input'), { target: { files } });
  beforeEach(() => {
    mutateMock.mockReset(); uploadMock.mockReset();
    uploadMock.mockImplementation(async ({ files }: { files: File[] }) => files.map((f, index) => artifact(f.name, index + 10)));
    vi.spyOn(message, 'error').mockImplementation(() => undefined as never);
    vi.spyOn(message, 'success').mockImplementation(() => undefined as never);
    vi.stubGlobal('URL', Object.assign(URL, { createObjectURL: vi.fn(() => 'blob:preview'), revokeObjectURL: vi.fn() }));
  });
  afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); });

  it('stages multiple files without uploading, allows removing them, and sends attachments without text', async () => {
    render(<CommentInput workitemId="1" />);
    select(file(), file('notes.txt', 'text/plain'));
    expect(uploadMock).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: '移除附件 notes.txt' }));
    clickSend();
    await waitFor(() => expect(mutateMock).toHaveBeenCalledTimes(1));
    expect(uploadMock.mock.calls[0][0].files.map((f: File) => f.name)).toEqual(['spec.md']);
    expect(mutateMock.mock.calls[0][0]).toMatchObject({ contentMd: '` requirements/spec.md `', targetAgentIds: [] });
    act(() => latestMutationOptions().onSuccess());
    expect(screen.queryByRole('button', { name: '移除附件 spec.md' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /发\s*送/ })).toBeDisabled();
  });

  it('waits for upload before sending, blocks duplicate sends, and keeps mention targets', async () => {
    let finish!: (value: Artifact[]) => void;
    uploadMock.mockReturnValue(new Promise<Artifact[]>(resolve => { finish = resolve; }));
    render(<CommentInput workitemId="1" participants={buildCandidates(2)} />);
    insertAgentMention(); select(file()); clickSend();
    fireEvent.keyDown(composer(), { key: 'Enter' });
    expect(uploadMock).toHaveBeenCalledTimes(1);
    expect(mutateMock).not.toHaveBeenCalled();
    expect(composer()).toBeDisabled();
    await act(async () => finish([artifact()]));
    expect(mutateMock.mock.calls[0][0]).toEqual({ workitemId: '1', contentMd: '@成员1\n\n` requirements/spec.md `', targetAgentIds: [1], targetHumanIds: [] });
  });

  it('retains a failed upload draft and retries it without sending a broken reference', async () => {
    uploadMock.mockRejectedValueOnce(new Error('offline'));
    render(<CommentInput workitemId="1" />);
    fireEvent.change(composer(), { target: { value: 'please review' } }); select(file()); clickSend();
    await waitFor(() => expect(composer()).toBeEnabled());
    expect(mutateMock).not.toHaveBeenCalled();
    expect(composer()).toHaveValue('please review');
    expect(screen.getByRole('button', { name: '移除附件 spec.md' })).toBeInTheDocument();
    // The textarea unlocks before Ant Design's effect clears the button's loading state.
    await waitFor(() => expect(screen.getByRole('button', { name: /发\s*送/ })).not.toHaveClass('ant-btn-loading'));
    clickSend(); await waitFor(() => expect(mutateMock).toHaveBeenCalledTimes(1));
    expect(uploadMock).toHaveBeenCalledTimes(2);
  });

  it('reuses uploaded files after comment rejection, even after the document cache refreshes', async () => {
    const { rerender } = render(<CommentInput workitemId="1" />);
    select(file()); clickSend(); await waitFor(() => expect(mutateMock).toHaveBeenCalledTimes(1));
    act(() => latestMutationOptions().onError(new ApiError('13024', 'closed', null)));
    rerender(<CommentInput workitemId="1" documents={[artifact()]} />);
    expect(screen.getByRole('alert')).toBeInTheDocument();
    clickSend(); await waitFor(() => expect(mutateMock).toHaveBeenCalledTimes(2));
    expect(uploadMock).toHaveBeenCalledTimes(1);
    expect(mutateMock.mock.calls[1][0].contentMd).toBe('` requirements/spec.md `');
  });

  it('accepts file drops and clipboard images but leaves ordinary text paste alone', () => {
    const { unmount } = render(<CommentInput workitemId="1" />);
    const zone = screen.getByTestId('comment-attachment-dropzone');
    const transfer = { types: ['Files'], files: [file()] };
    fireEvent.dragEnter(zone, { dataTransfer: transfer });
    expect(zone).toHaveAttribute('data-drag-active', 'true');
    fireEvent.drop(zone, { dataTransfer: transfer });
    expect(zone).toHaveAttribute('data-drag-active', 'false');
    expect(screen.getByText('spec.md')).toBeInTheDocument();
    fireEvent.paste(composer(), { clipboardData: { files: [file('image.png', 'image/png')] } });
    expect(screen.getByRole('img', { name: /^粘贴-/ })).toHaveAttribute('src', 'blob:preview');
    const textPaste = new Event('paste', { bubbles: true, cancelable: true });
    Object.defineProperty(textPaste, 'clipboardData', { value: { types: ['text/plain'], files: [] } });
    fireEvent(composer(), textPaste);
    expect(textPaste.defaultPrevented).toBe(false);
    unmount(); expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:preview');
  });

  it('rejects oversized, duplicate and over-count selections including existing documents', () => {
    const { rerender } = render(<CommentInput workitemId="1" documents={[artifact()]} />);
    select(file());
    const large = file('large.txt'); Object.defineProperty(large, 'size', { value: 5 * 1024 * 1024 + 1 }); select(large);
    expect(message.error).toHaveBeenCalledTimes(2);
    rerender(<CommentInput workitemId="1" documents={Array.from({ length: 10 }, (_, index) => artifact(`${index}.md`, index))} />);
    select(file()); expect(message.error).toHaveBeenCalledTimes(3);
    expect(screen.queryByRole('button', { name: '移除附件 spec.md' })).not.toBeInTheDocument();
    expect(uploadMock).not.toHaveBeenCalled();
  });

  it.each(['sheet.xlsx', 'legacy.xls', 'data.csv', 'custom.backup', 'README'])('stages and sends %s as a comment attachment', async (name) => {
    render(<CommentInput workitemId="1" />);
    select(file(name));
    expect(screen.getByRole('button', { name: `移除附件 ${name}` })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /发送/ }));
    await waitFor(() => expect(uploadMock).toHaveBeenCalled());
    expect(uploadMock.mock.calls[0][0].files[0].name).toBe(name);
  });

  it('rejects directories and the cumulative total size before uploading', () => {
    render(<CommentInput workitemId="1" documents={[{ ...artifact(), size: 20 * 1024 * 1024 }]} />);
    select(file('new.md'));
    fireEvent.drop(screen.getByTestId('comment-attachment-dropzone'), { dataTransfer: {
      types: ['Files'], items: [{ kind: 'file', webkitGetAsEntry: () => ({ isDirectory: true }) }],
    } });
    expect(message.error).toHaveBeenCalledTimes(2);
    expect(uploadMock).not.toHaveBeenCalled();
  });
});


it.each(['需求 [v2].pdf', 'image.png', 'a`b``c.md', '<img src=x onerror=alert(1)>.txt', 'two words.docx'])(
  'renders an attachment path safely and opens the matching artifact: %s', (name) => {
    const artifact: Artifact = { id: 10, name: `requirements/${name}`, workitemId: 1,
      dispatchId: null, type: 'REQUIREMENT_DOC', size: 4, gmtCreate: '2026-10-14T00:00:00Z' };
    const open = vi.fn();
    const { container } = render(<MarkdownView content={commentAttachmentMarkdown(artifact)} artifacts={[artifact]} onArtifactClick={open} />);
    const link = screen.getByTestId('artifact-inline-link');
    expect(link.textContent).toBe(artifact.name);
    expect(container.querySelector('img')).toBeNull();
    fireEvent.click(link); expect(open).toHaveBeenCalledWith(artifact);
  },
);
