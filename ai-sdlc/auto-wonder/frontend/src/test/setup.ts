import '@testing-library/jest-dom';
import { Blob, File } from 'node:buffer';
import { createRequire } from 'node:module';
import { useId } from 'react';
import { fetch, FormData, Headers, Request, Response } from 'undici';

// Ant Design loads rc-util through CommonJS, so an ESM vi.mock cannot intercept it.
// Its test-only fixed ID breaks aria-labelledby when multiple modals are mounted.
// Match the normal React 18 behavior: stable, unique IDs, with explicit IDs preserved.
const require = createRequire(import.meta.url);
require('rc-util/lib/hooks/useId').default = function useAccessibleId(id?: string) {
  const reactId = useId();
  return id || reactId;
};

Object.assign(globalThis, {
  Blob,
  File,
  fetch,
  FormData,
  Headers,
  Request,
  Response,
});

if (!globalThis.localStorage) {
  const store = new Map<string, string>();
  Object.defineProperty(globalThis, 'localStorage', {
    configurable: true,
    value: {
      getItem: (key: string) => store.get(key) ?? null,
      setItem: (key: string, value: string) => store.set(key, String(value)),
      removeItem: (key: string) => store.delete(key),
      clear: () => store.clear(),
      key: (index: number) => Array.from(store.keys())[index] ?? null,
      get length() {
        return store.size;
      },
    },
  });
}

const { server } = await import('./mocks/server');

// zustand persist 的 rehydrate 是异步的：等它完成后再开始跑用例，
// 避免迟到的 rehydrate 用空 localStorage 覆盖测试中设置的 auth 状态。
const { useAuthStore } = await import('@/shared/auth/store');
if ('persist' in useAuthStore && typeof useAuthStore.persist?.rehydrate === 'function') {
  await Promise.race([
    useAuthStore.persist.rehydrate(),
    new Promise((resolve) => setTimeout(resolve, 500)),
  ]);
  useAuthStore.getState().clear();
}

// Mock window.matchMedia for antd responsive components
Object.defineProperty(window, 'matchMedia', {
  writable: true,
  value: (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => false,
  }),
});

const originalGetComputedStyle = window.getComputedStyle.bind(window);
window.getComputedStyle = (element: Element, pseudoElt?: string | null) =>
  originalGetComputedStyle(element, pseudoElt ? undefined : pseudoElt);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
