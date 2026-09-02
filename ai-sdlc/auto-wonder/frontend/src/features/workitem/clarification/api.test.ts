import { afterEach, describe, expect, it, vi } from 'vitest';
import { apiClient } from '@/shared/api/client';
import { cancelClarificationTurn, submitClarificationTurn } from './api';

vi.mock('@/shared/api/client', () => ({
  apiClient: {
    post: vi.fn(),
  },
}));

const originalCrypto = Object.getOwnPropertyDescriptor(globalThis, 'crypto');

describe('clarification api', () => {
  afterEach(() => {
    vi.restoreAllMocks();
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
      },
    );
  });

  it('cancels a turn via the per-turn cancel endpoint', async () => {
    vi.mocked(apiClient.post).mockResolvedValue({ data: null } as never);

    await cancelClarificationTurn(10011, 10010, 77);

    expect(apiClient.post).toHaveBeenCalledWith(
      '/api/workitems/10011/clarification-conversations/10010/turns/77/cancel',
    );
  });
});
