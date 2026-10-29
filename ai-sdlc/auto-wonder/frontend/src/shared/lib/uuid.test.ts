import { afterEach, describe, expect, it } from 'vitest';
import { createUuidV4 } from './uuid';

const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const originalCrypto = Object.getOwnPropertyDescriptor(globalThis, 'crypto');

function stubCrypto(value: unknown) {
  Object.defineProperty(globalThis, 'crypto', { configurable: true, value });
}

afterEach(() => {
  if (originalCrypto) {
    Object.defineProperty(globalThis, 'crypto', originalCrypto);
  } else {
    Reflect.deleteProperty(globalThis, 'crypto');
  }
});

describe('createUuidV4', () => {
  it('prefers crypto.randomUUID when it is available', () => {
    stubCrypto({ randomUUID: () => '00000000-0000-4000-8000-000000000000' });
    expect(createUuidV4()).toBe('00000000-0000-4000-8000-000000000000');
  });

  it('builds a v4 UUID from getRandomValues when randomUUID is unavailable', () => {
    stubCrypto({
      getRandomValues: (bytes: Uint8Array) => {
        bytes.set(Array.from({ length: 16 }, (_, index) => index));
        return bytes;
      },
    });
    expect(createUuidV4()).toBe('00010203-0405-4607-8809-0a0b0c0d0e0f');
  });

  it('falls back to a timestamp id when Web Crypto is unavailable', () => {
    stubCrypto(undefined);
    const value = createUuidV4();
    expect(value.startsWith('fallback-')).toBe(true);
    expect(value).not.toMatch(UUID_V4);
  });
});
