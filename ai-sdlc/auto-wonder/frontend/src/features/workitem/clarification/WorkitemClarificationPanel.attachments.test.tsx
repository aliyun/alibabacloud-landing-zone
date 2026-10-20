import { i18n } from '@/shared/i18n';
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { useAuthStore } from '@/shared/auth/store';
import { WorkitemClarificationPanel } from './WorkitemClarificationPanel';
import { writeClarificationPrefill } from './prefill';

type RealtimeCallback = (event: { type: string; payload: unknown }) => void;
const realtime = vi.hoisted(() => ({ callback: null as RealtimeCallback | null }));

vi.mock('@/shared/realtime/useRealtime', () => ({
  useRealtime: (_channel: unknown, opts: { onEvent: RealtimeCallback }) => {
    realtime.callback = opts.onEvent;
  },
}));

const originalCreateObjectUrl = Object.getOwnPropertyDescriptor(URL, 'createObjectURL');
const originalRevokeObjectUrl = Object.getOwnPropertyDescriptor(URL, 'revokeObjectURL');
const originalFetch = globalThis.fetch;
const originalAnchorClick = HTMLAnchorElement.prototype.click;

function renderPanel() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <WorkitemClarificationPanel workitemId="100" />
    </QueryClientProvider>,
  );
}

/** 会话自动选中且详情加载完毕前，输入框禁用、上传会静默丢弃，先等面板就绪。 */
async function waitForPanelReady() {
  const composer = await screen.findByTestId('clarification-composer-input');
  await waitFor(() => {
    expect(screen.getByTestId('clarification-conversation-select')).toHaveTextContent('会话 #1');
    expect(composer).toHaveAttribute('data-disabled', 'false');
  });
}

function mockSquads() {
  server.use(
    http.get('/api/squads', () =>
      HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: {
          list: [{ id: 9, name: '交付小队', description: '', memberCount: 1, gmtCreate: '' }],
          total: 1, pageNum: 1, pageSize: 100,
        },
      }),
    ),
    http.get('/api/squads/:squadId/members', () =>
      HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [{ agentId: 42, agentName: 'Agent-X', roleCode: 'AW_FS_DEV' }],
      }),
    ),
  );
}

function conversation(overrides: Record<string, unknown> = {}) {
  return {
    id: 1, agentId: 42, agentName: 'Agent-X', channelConversationId: 'ch-1',
    status: 'ACTIVE', executorOnline: true, streamingSupported: true,
    cliSessionRef: null, processingStatus: null, processingTurnId: null,
    lastTurnAt: '2026-01-01T00:00:00', gmtCreate: '2026-01-01T00:00:00',
    turns: [],
    ...overrides,
  };
}

function mockConversation(conversationData: ReturnType<typeof conversation>,
  all: ReturnType<typeof conversation>[] = [conversationData]) {
  server.use(
    http.get('/api/workitems/:workitemId/clarification-conversations', () =>
      HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: all }),
    ),
    http.get('/api/workitems/:workitemId/clarification-conversations/:conversationId', () =>
      HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: conversationData }),
    ),
    http.get('/api/workitems/:workitemId/clarification-conversations/:conversationId/events', () =>
      HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: [] }),
    ),
  );
}

function uploadedAttachment(overrides: Record<string, unknown> = {}) {
  return {
    id: 501, name: 'shot.png', contentType: 'image/png', size: 3,
    previewable: true, gmtCreate: '2026-01-01T00:00:00', ...overrides,
  };
}

function successfulUpload(attachment = uploadedAttachment()) {
  return http.post(
    '/api/workitems/:workitemId/clarification-conversations/:conversationId/attachments',
    () => HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: attachment }),
  );
}

function selectFiles(container: HTMLElement, files: File[]) {
  const input = within(container).getByTestId('clarification-file-input') as HTMLInputElement;
  Object.defineProperty(input, 'files', { configurable: true, value: files });
  fireEvent.change(input);
}

async function typeComposer(value: string) {
  const root = await screen.findByTestId('clarification-composer-input');
  const editor = (root as HTMLElement & { __composerEditor?: import('@tiptap/core').Editor }).__composerEditor;
  if (!editor) throw new Error('composer editor not mounted');
  await act(async () => {
    editor.commands.setContent(value, true);
  });
  return editor;
}

function largeFile(name: string): File {
  const file = new File(['x'], name, { type: 'application/octet-stream' });
  Object.defineProperty(file, 'size', { value: 20 * 1024 * 1024 + 1 });
  return file;
}

describe('WorkitemClarificationPanel attachments', () => {
  let createObjectURL: ReturnType<typeof vi.fn>;
  let revokeObjectURL: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    window.localStorage.clear();
    realtime.callback = null;
    useAuthStore.setState({ accessLevel: 'READ_WRITE', readOnlyDomains: [] });
    createObjectURL = vi.fn().mockImplementation((value: File | Blob) =>
      value instanceof File ? `blob:${value.name}` : 'blob:history.png');
    revokeObjectURL = vi.fn();
    Object.defineProperty(URL, 'createObjectURL', { configurable: true, value: createObjectURL });
    Object.defineProperty(URL, 'revokeObjectURL', { configurable: true, value: revokeObjectURL });
    HTMLAnchorElement.prototype.click = vi.fn();
    writeClarificationPrefill('100', { squadId: 9, agentId: 42 });
    mockSquads();
  });

  afterEach(() => {
    // 先卸载：卸载会释放 object URL，必须赶在还原/删除 URL 方法之前执行。
    cleanup();
    vi.restoreAllMocks();
    globalThis.fetch = originalFetch;
    HTMLAnchorElement.prototype.click = originalAnchorClick;
    useAuthStore.setState({ accessLevel: null, readOnlyDomains: [] });
    if (originalCreateObjectUrl) {
      Object.defineProperty(URL, 'createObjectURL', originalCreateObjectUrl);
    } else {
      Reflect.deleteProperty(URL, 'createObjectURL');
    }
    if (originalRevokeObjectUrl) {
      Object.defineProperty(URL, 'revokeObjectURL', originalRevokeObjectUrl);
    } else {
      Reflect.deleteProperty(URL, 'revokeObjectURL');
    }
  });

  it('uploads a selected file immediately and sends an attachment-only message', async () => {
    mockConversation(conversation());
    let uploadCalls = 0;
    let submitted: { content?: string; attachmentIds?: number[] } | null = null;
    server.use(
      http.post(
        '/api/workitems/:workitemId/clarification-conversations/:conversationId/attachments',
        () => {
          uploadCalls += 1;
          return HttpResponse.json({
            success: true, code: '0', message: '', traceId: null, data: uploadedAttachment(),
          });
        },
      ),
      http.post('/api/workitems/:workitemId/clarification-conversations/:conversationId/turns',
        async ({ request }) => {
          submitted = await request.json() as typeof submitted;
          return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: null });
        }),
    );
    const view = renderPanel();
    await waitForPanelReady();
    // 预填草稿会随消息一起提交，本用例要验证「仅附件」消息先清空输入。
    await typeComposer('');

    selectFiles(view.container, [new File(['png'], 'shot.png', { type: 'image/png' })]);

    expect(await screen.findByTestId('clarification-pending-attachments')).toBeInTheDocument();
    expect(screen.getByRole('img', { name: 'shot.png' })).toHaveAttribute('src', 'blob:shot.png');
    await waitFor(() => expect(uploadCalls).toBe(1));
    const sendButton = await screen.findByRole('button', { name: '发送消息' });
    await waitFor(() => expect(sendButton).toBeEnabled());

    fireEvent.click(sendButton);

    await waitFor(() => expect(submitted).not.toBeNull());
    expect(submitted!.content).toBe('');
    expect(submitted!.attachmentIds).toEqual([501]);
    await waitFor(() => expect(screen.queryByTestId('clarification-pending-attachments')).toBeNull());
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:shot.png');
  });

  it('reuses the same client message id when retrying a failed send', async () => {
    mockConversation(conversation());
    const keys: string[] = [];
    let attempts = 0;
    server.use(
      successfulUpload(),
      http.post('/api/workitems/:workitemId/clarification-conversations/:conversationId/turns',
        async ({ request }) => {
          attempts += 1;
          const body = await request.json() as { clientMessageId?: string };
          keys.push(body.clientMessageId ?? '');
          if (attempts === 1) {
            return HttpResponse.json(
              { success: false, code: 'SEND_FAILED', message: '发送失败', traceId: null, data: null },
              { status: 500 },
            );
          }
          return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: null });
        }),
    );
    const view = renderPanel();
    await waitForPanelReady();
    selectFiles(view.container, [new File(['png'], 'shot.png', { type: 'image/png' })]);
    await waitFor(() => expect(screen.getByRole('button', { name: '发送消息' })).toBeEnabled());

    fireEvent.click(screen.getByRole('button', { name: '发送消息' }));
    await waitFor(() => expect(attempts).toBe(1));
    // 失败后附件草稿仍在，直接重试必须复用同一个幂等键。
    expect(screen.getByTestId('clarification-pending-attachments')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('button', { name: '发送消息' })).toBeEnabled());
    fireEvent.click(screen.getByRole('button', { name: '发送消息' }));

    await waitFor(() => expect(attempts).toBe(2));
    expect(keys[0]).toBeTruthy();
    expect(keys[1]).toBe(keys[0]);
  });

  it('blocks sending while an upload is in flight and reports failures inline', async () => {
    mockConversation(conversation());
    let turnCalls = 0;
    let resolveUpload: (() => void) | undefined;
    server.use(
      http.post(
        '/api/workitems/:workitemId/clarification-conversations/:conversationId/attachments',
        async () => {
          await new Promise<void>((resolve) => { resolveUpload = resolve; });
          return HttpResponse.json({
            success: true, code: '0', message: '', traceId: null, data: uploadedAttachment(),
          });
        },
      ),
      http.post('/api/workitems/:workitemId/clarification-conversations/:conversationId/turns', () => {
        turnCalls += 1;
        return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: null });
      }),
    );
    const view = renderPanel();
    await waitForPanelReady();
    selectFiles(view.container, [new File(['pdf'], 'report.pdf', { type: 'application/pdf' })]);
    await screen.findByText('上传中');
    await typeComposer('附件还在上传');

    fireEvent.keyDown(screen.getByPlaceholderText('输入消息...'), {
      key: 'Enter', code: 'Enter', shiftKey: true,
    });
    expect(await screen.findByText(i18n.t('workitem:clarification.attachmentSendBlocked'))).toBeInTheDocument();
    expect(turnCalls).toBe(0);
    expect(screen.getByRole('button', { name: '发送消息' })).toBeDisabled();

    await act(async () => { resolveUpload?.(); });
    await waitFor(() => expect(screen.queryByText('上传中')).toBeNull());
    expect(screen.getByRole('button', { name: '发送消息' })).toBeEnabled();
  });

  it('removes an in-flight upload cleanly and releases previews on unmount', async () => {
    mockConversation(conversation());
    let release: (() => void) | undefined;
    server.use(
      http.post(
        '/api/workitems/:workitemId/clarification-conversations/:conversationId/attachments',
        async () => {
          await new Promise<void>((resolve) => { release = resolve; });
          return HttpResponse.json({
            success: true, code: '0', message: '', traceId: null, data: uploadedAttachment(),
          });
        },
      ),
    );
    const view = renderPanel();
    await waitForPanelReady();
    selectFiles(view.container, [new File(['png'], 'shot.png', { type: 'image/png' })]);
    await screen.findByText('上传中');

    fireEvent.click(screen.getByRole('button', { name: '移除附件 shot.png' }));
    await waitFor(() => expect(screen.queryByTestId('clarification-pending-attachments')).toBeNull());
    await act(async () => { release?.(); });
    expect(screen.queryByText(i18n.t('workitem:clarification.attachmentUploadFailed'))).toBeNull();

    selectFiles(view.container, [new File(['png'], 'shot.png', { type: 'image/png' })]);
    await screen.findByText('上传中');
    revokeObjectURL.mockClear();
    act(() => { view.unmount(); });
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:shot.png');
  });

  it('shows an inline upload error and keeps the attachment out of the send', async () => {
    mockConversation(conversation());
    let turnCalls = 0;
    server.use(
      http.post(
        '/api/workitems/:workitemId/clarification-conversations/:conversationId/attachments',
        () => HttpResponse.json(
          { success: false, code: 'UPLOAD_FAILED', message: '空间不足', traceId: null, data: null },
          { status: 500 },
        ),
      ),
      http.post('/api/workitems/:workitemId/clarification-conversations/:conversationId/turns', () => {
        turnCalls += 1;
        return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: null });
      }),
    );
    const view = renderPanel();
    await waitForPanelReady();
    selectFiles(view.container, [new File(['pdf'], 'report.pdf', { type: 'application/pdf' })]);

    expect(await screen.findByText('空间不足')).toBeInTheDocument();
    expect(screen.getByText('report.pdf')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '发送消息' })).toBeDisabled();

    fireEvent.keyDown(screen.getByPlaceholderText('输入消息...'), {
      key: 'Enter', code: 'Enter', shiftKey: true,
    });
    expect(turnCalls).toBe(0);
  });

  it('rejects files above the 20MB limit before uploading', async () => {
    mockConversation(conversation());
    let uploadCalls = 0;
    server.use(
      http.post(
        '/api/workitems/:workitemId/clarification-conversations/:conversationId/attachments',
        () => {
          uploadCalls += 1;
          return HttpResponse.json({
            success: true, code: '0', message: '', traceId: null,
            data: uploadedAttachment({ id: 504, name: 'ok.txt', contentType: 'text/plain', previewable: false }),
          });
        },
      ),
    );
    const view = renderPanel();
    await waitForPanelReady();

    selectFiles(view.container, [largeFile('huge.bin'), new File(['ok'], 'ok.txt')]);

    expect(await screen.findByText(i18n.t('workitem:clarification.attachmentTooLarge', {
      name: 'huge.bin', max: 20,
    }))).toBeInTheDocument();
    await waitFor(() => expect(uploadCalls).toBe(1));
    expect(screen.getByText('ok.txt')).toBeInTheDocument();
    expect(screen.queryByText('huge.bin')).toBeNull();
  });

  it('uploads pasted images instead of leaving them untracked', async () => {
    mockConversation(conversation());
    let uploadCalls = 0;
    server.use(
      http.post(
        '/api/workitems/:workitemId/clarification-conversations/:conversationId/attachments',
        () => {
          uploadCalls += 1;
          return HttpResponse.json({
            success: true, code: '0', message: '', traceId: null,
            data: uploadedAttachment({ id: 502, name: 'paste.png' }),
          });
        },
      ),
    );
    renderPanel();
    await waitForPanelReady();
    const editor = await typeComposer('');
    const view = editor.view;
    const clipboardData = {
      files: [],
      items: [{
        kind: 'file',
        getAsFile: () => new File(['png'], 'image.png', { type: 'image/png' }),
      }],
      getData: () => '',
    };
    let handled: boolean | undefined;
    view.someProp('handlePaste', (handler) => {
      handled = handler(view, { clipboardData } as unknown as ClipboardEvent, null as never) === true;
      return true;
    });

    expect(handled).toBe(true);
    await waitFor(() => expect(uploadCalls).toBe(1));
    // 剪贴板图片会改成不重名的新文件名，落库名以服务端返回为准。
    expect(await screen.findByText('paste.png')).toBeInTheDocument();
  });

  it('shows a drop overlay, uploads dropped files, and rejects directories', async () => {
    mockConversation(conversation());
    let uploadCalls = 0;
    server.use(
      http.post(
        '/api/workitems/:workitemId/clarification-conversations/:conversationId/attachments',
        () => {
          uploadCalls += 1;
          return HttpResponse.json({
            success: true, code: '0', message: '', traceId: null,
            data: uploadedAttachment({ id: 503, name: 'dropped.pdf', previewable: false, contentType: 'application/pdf' }),
          });
        },
      ),
    );
    renderPanel();
    await waitForPanelReady();
    const composer = screen.getByTestId('clarification-composer');

    fireEvent.dragEnter(composer, {
      dataTransfer: { types: ['Files'], files: [new File(['pdf'], 'dropped.pdf')] },
    });
    expect(screen.getByTestId('clarification-drag-overlay')).toBeInTheDocument();
    fireEvent.dragLeave(composer, {
      dataTransfer: { types: ['Files'], files: [new File(['pdf'], 'dropped.pdf')] },
    });
    expect(screen.queryByTestId('clarification-drag-overlay')).toBeNull();

    fireEvent.dragEnter(composer, {
      dataTransfer: { types: ['Files'], files: [new File(['pdf'], 'dropped.pdf')] },
    });
    fireEvent.drop(composer, {
      dataTransfer: {
        types: ['Files'],
        files: [new File(['pdf'], 'dropped.pdf', { type: 'application/pdf' })],
      },
    });

    expect(screen.queryByTestId('clarification-drag-overlay')).toBeNull();
    await waitFor(() => expect(uploadCalls).toBe(1));
    expect(await screen.findByText('dropped.pdf')).toBeInTheDocument();

    fireEvent.drop(composer, {
      dataTransfer: {
        types: ['Files'],
        files: [],
        items: [{ kind: 'file', webkitGetAsEntry: () => ({ isDirectory: true }) }],
      },
    });
    expect(await screen.findByText(i18n.t('shared:upload.directory'))).toBeInTheDocument();
    expect(uploadCalls).toBe(1);
  });

  it('deletes an uploaded attachment and keeps it when deletion fails', async () => {
    mockConversation(conversation());
    let deleteCalls = 0;
    const uploadedNames = ['first.png', 'second.png'];
    server.use(
      http.post(
        '/api/workitems/:workitemId/clarification-conversations/:conversationId/attachments',
        () => HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: uploadedAttachment({
            id: 600 + uploadedNames.length,
            name: uploadedNames.shift() ?? 'fallback.png',
          }),
        }),
      ),
      http.delete(
        '/api/workitems/:workitemId/clarification-conversations/:conversationId/attachments/:attachmentId',
        () => {
          deleteCalls += 1;
          if (deleteCalls === 1) {
            return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: true });
          }
          return HttpResponse.json(
            { success: false, code: 'DELETE_FAILED', message: '', traceId: null, data: null },
            { status: 500 },
          );
        },
      ),
    );
    const view = renderPanel();
    await waitForPanelReady();

    selectFiles(view.container, [new File(['png'], 'first.png', { type: 'image/png' })]);
    await waitFor(() => expect(screen.getByRole('button', { name: '移除附件 first.png' })).toBeEnabled());
    fireEvent.click(screen.getByRole('button', { name: '移除附件 first.png' }));
    await waitFor(() => expect(screen.queryByText('first.png')).toBeNull());
    expect(deleteCalls).toBe(1);

    selectFiles(view.container, [new File(['png'], 'second.png', { type: 'image/png' })]);
    await waitFor(() => expect(screen.getByRole('button', { name: '移除附件 second.png' })).toBeEnabled());
    fireEvent.click(screen.getByRole('button', { name: '移除附件 second.png' }));
    expect(await screen.findByText(i18n.t('workitem:clarification.attachmentDeleteFailed'))).toBeInTheDocument();
    expect(screen.getByText('second.png')).toBeInTheDocument();
  });

  it('renders history attachments with thumbnails and one-click download', async () => {
    mockConversation(conversation({
      turns: [{
        id: 7, direction: 'IN', content: '', status: 'SUCCESS', error: null,
        gmtCreate: '2026-01-01T00:00:00',
        attachments: [
          { id: 11, name: 'history.png', contentType: 'image/png', size: 2048, previewable: true },
          { id: 12, name: 'notes.pdf', contentType: 'application/pdf', size: 4096, previewable: false },
        ],
      }],
    }));
    const downloadCalls: string[] = [];
    globalThis.fetch = vi.fn().mockImplementation((input: RequestInfo | URL) => {
      const url = String(input);
      if (url.endsWith('/preview')) {
        return Promise.resolve({
          ok: true,
          blob: () => Promise.resolve(new Blob(['img'], { type: 'image/png' })),
        });
      }
      downloadCalls.push(url);
      return Promise.resolve({
        ok: true,
        blob: () => Promise.resolve(new Blob(['pdf'], { type: 'application/pdf' })),
      });
    }) as unknown as typeof fetch;

    renderPanel();
    await waitForPanelReady();

    const image = await screen.findByRole('img', { name: 'history.png' });
    expect(image).toHaveAttribute('src', 'blob:history.png');
    expect(screen.getByText('notes.pdf')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '下载附件 notes.pdf' }));
    await waitFor(() => expect(downloadCalls.some((url) => url.endsWith('/download'))).toBe(true));
    expect(HTMLAnchorElement.prototype.click).toHaveBeenCalled();
  });

  it('clears pending attachments when switching to another conversation', async () => {
    const first = conversation({ lastTurnAt: '2026-01-02T00:00:00', gmtCreate: '2026-01-02T00:00:00' });
    const second = conversation({ id: 2, channelConversationId: 'ch-2' });
    mockConversation(first, [first, second]);
    server.use(successfulUpload(uploadedAttachment({ id: 700, name: 'draft.png' })));
    const view = renderPanel();
    await waitForPanelReady();
    selectFiles(view.container, [new File(['png'], 'draft.png', { type: 'image/png' })]);
    expect(await screen.findByText('draft.png')).toBeInTheDocument();

    const selector = screen.getByTestId('clarification-conversation-select');
    fireEvent.mouseDown(within(selector).getByRole('combobox'));
    fireEvent.click(await screen.findByText(/会话 #2/));

    await waitFor(() => expect(screen.queryByText('draft.png')).toBeNull());
  });
});
