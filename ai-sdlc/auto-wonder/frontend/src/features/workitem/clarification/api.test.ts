import { afterEach, describe, expect, it, vi } from 'vitest';
import { apiClient } from '@/shared/api/client';
import { useAuthStore } from '@/shared/auth/store';
import {
  cancelClarificationTurn,
  deleteClarificationAttachment,
  downloadClarificationAttachment,
  fetchClarificationAttachmentPreview,
  getClarificationTurnEvents,
  replyClarificationElicitation,
  submitClarificationTurn,
  uploadClarificationAttachment,
} from './api';

vi.mock('@/shared/api/client', () => ({
  apiClient: {
    get: vi.fn(),
    post: vi.fn(),
    delete: vi.fn(),
  },
}));

const originalCrypto = Object.getOwnPropertyDescriptor(globalThis, 'crypto');
const originalFetch = globalThis.fetch;
const originalCreateObjectUrl = Object.getOwnPropertyDescriptor(URL, 'createObjectURL');
const originalRevokeObjectUrl = Object.getOwnPropertyDescriptor(URL, 'revokeObjectURL');

describe('clarification api', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.useRealTimers();
    globalThis.fetch = originalFetch;
    useAuthStore.setState({ accessToken: null });
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
    if (originalCrypto) {
      Object.defineProperty(globalThis, 'crypto', originalCrypto);
    } else {
      Reflect.deleteProperty(globalThis, 'crypto');
    }
  });

  it('submits a UUID when randomUUID is unavailable in an HTTP context', async () => {
    Object.defineProperty(globalThis, 'crypto', {
      configurable: true,
      value: {
        getRandomValues: (bytes: Uint8Array) => {
          bytes.set(Array.from({ length: 16 }, (_, index) => index));
          return bytes;
        },
      },
    });
    vi.mocked(apiClient.post).mockResolvedValue({ data: null } as never);

    await submitClarificationTurn(10011, 10010, '请澄清需求');

    expect(apiClient.post).toHaveBeenCalledWith(
      '/api/workitems/10011/clarification-conversations/10010/turns',
      {
        content: '请澄清需求',
        clientMessageId: '00010203-0405-4607-8809-0a0b0c0d0e0f',
        attachmentIds: [],
      },
    );
  });

  it('submits attachments with the caller-provided idempotency key', async () => {
    vi.mocked(apiClient.post).mockResolvedValue({ data: null } as never);

    await submitClarificationTurn(10011, 10010, '', {
      attachmentIds: [5, 6],
      clientMessageId: 'retry-key',
    });

    expect(apiClient.post).toHaveBeenCalledWith(
      '/api/workitems/10011/clarification-conversations/10010/turns',
      { content: '', clientMessageId: 'retry-key', attachmentIds: [5, 6] },
    );
  });

  it('falls back to a timestamp id when the crypto API is missing', async () => {
    Object.defineProperty(globalThis, 'crypto', { configurable: true, value: undefined });
    vi.mocked(apiClient.post).mockResolvedValue({ data: null } as never);

    await submitClarificationTurn(10011, 10010, 'hi');

    const body = vi.mocked(apiClient.post).mock.calls[0][1] as { clientMessageId: string };
    expect(body.clientMessageId).toMatch(/^fallback-/);
  });

  it('uploads an attachment as multipart and lets the browser set the boundary', async () => {
    vi.mocked(apiClient.post).mockResolvedValue({
      data: { id: 5, name: 'a.png', contentType: 'image/png', size: 3, previewable: true },
    } as never);
    const file = new File(['abc'], 'a.png', { type: 'image/png' });
    // undici 的 FormData 不把 jsdom File 当 Blob，读回内容会变成 "[object File]"；断言 append 边界。
    const appendSpy = vi.spyOn(FormData.prototype, 'append');

    await expect(uploadClarificationAttachment(10011, 10010, file)).resolves.toMatchObject({ id: 5 });

    const [url, body, config] = vi.mocked(apiClient.post).mock.calls[0];
    expect(url).toBe('/api/workitems/10011/clarification-conversations/10010/attachments');
    expect(body).toBeInstanceOf(FormData);
    expect(appendSpy).toHaveBeenCalledWith('file', file);
    type HeadersMap = Map<string, string>;
    const headers: HeadersMap = new Map<string, string>([
      ['Content-Type', 'application/json'],
      ['authorization', 'Bearer token'],
    ]);
    const transform = (config as unknown as {
      transformRequest: Array<(data: unknown, headers: HeadersMap) => unknown>;
    }).transformRequest[0];
    const data = new FormData();
    expect(transform(data, headers)).toBe(data);
    expect(headers.has('Content-Type')).toBe(false);
    expect(headers.get('authorization')).toBe('Bearer token');
  });

  it('deletes an attachment by id', async () => {
    vi.mocked(apiClient.delete).mockResolvedValue({ data: null } as never);

    await deleteClarificationAttachment(10011, 10010, 5);

    expect(apiClient.delete).toHaveBeenCalledWith(
      '/api/workitems/10011/clarification-conversations/10010/attachments/5',
    );
  });

  it('fetches a preview blob with the bearer token', async () => {
    useAuthStore.setState({ accessToken: 'token-1' });
    const blob = new Blob(['img'], { type: 'image/png' });
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, blob: () => Promise.resolve(blob) });
    globalThis.fetch = fetchMock as unknown as typeof fetch;

    await expect(fetchClarificationAttachmentPreview(10011, 10010, 5)).resolves.toBe(blob);

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/workitems/10011/clarification-conversations/10010/attachments/5/preview',
      expect.objectContaining({
        headers: expect.objectContaining({ Authorization: 'Bearer token-1' }),
      }),
    );
  });

  it('surfaces the server text when a preview request fails', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue({
      ok: false,
      text: () => Promise.resolve('附件不存在'),
    }) as unknown as typeof fetch;

    await expect(fetchClarificationAttachmentPreview(10011, 10010, 5))
      .rejects.toThrow('附件不存在');
  });

  it('downloads with an object URL and releases it asynchronously', async () => {
    const blob = new Blob(['file'], { type: 'application/octet-stream' });
    globalThis.fetch = vi.fn().mockResolvedValue({
      ok: true,
      blob: () => Promise.resolve(blob),
    }) as unknown as typeof fetch;
    const createObjectURL = vi.fn().mockReturnValue('blob:file');
    const revokeObjectURL = vi.fn();
    Object.defineProperty(URL, 'createObjectURL', { configurable: true, value: createObjectURL });
    Object.defineProperty(URL, 'revokeObjectURL', { configurable: true, value: revokeObjectURL });
    vi.useFakeTimers();
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});

    await downloadClarificationAttachment(10011, 10010, { id: 5, name: 'report.pdf' });
    vi.runAllTimers();

    expect(click).toHaveBeenCalledTimes(1);
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:file');
    vi.useRealTimers();
  });

  it('cancels a turn via the per-turn cancel endpoint', async () => {
    vi.mocked(apiClient.post).mockResolvedValue({ data: null } as never);

    await cancelClarificationTurn(10011, 10010, 77);

    expect(apiClient.post).toHaveBeenCalledWith(
      '/api/workitems/10011/clarification-conversations/10010/turns/77/cancel',
    );
  });
});

describe('clarification acp api', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('replies to a card on the per-request reply endpoint with a serialised answer', async () => {
    vi.mocked(apiClient.post).mockResolvedValue({ data: null } as never);

    await replyClarificationElicitation(10011, 10010, 'a1b2c3', 'accept', { q0: '方案A' });

    expect(apiClient.post).toHaveBeenCalledWith(
      '/api/workitems/10011/clarification-conversations/10010/elicitations/a1b2c3/reply',
      { action: 'accept', content: '{"q0":"方案A"}' },
    );
  });

  it('sends a null content for decline and escapes the requestId', async () => {
    vi.mocked(apiClient.post).mockResolvedValue({ data: null } as never);

    await replyClarificationElicitation(10011, 10010, 'a/b', 'decline');

    expect(apiClient.post).toHaveBeenCalledWith(
      '/api/workitems/10011/clarification-conversations/10010/elicitations/a%2Fb/reply',
      { action: 'decline', content: null },
    );
  });

  it('fetches all events of a single turn', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({ data: [] } as never);

    await expect(getClarificationTurnEvents(10011, 10010, 77)).resolves.toEqual([]);

    expect(apiClient.get).toHaveBeenCalledWith(
      '/api/workitems/10011/clarification-conversations/10010/turns/77/events',
    );
  });
});
