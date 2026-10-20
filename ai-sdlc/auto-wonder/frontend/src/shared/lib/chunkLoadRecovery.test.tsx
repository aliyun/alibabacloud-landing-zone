import { Component, Suspense, type ReactNode } from 'react';
import { render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  CHUNK_RELOAD_COOLDOWN_MS,
  CHUNK_RELOAD_GUARD_KEY,
  installChunkLoadRecovery,
  isChunkLoadError,
  lazyWithChunkReload,
  reloadOnceForChunkLoadError,
} from './chunkLoadRecovery';

const NOW = 1_000_000;

function memoryStorage(initial: Record<string, string> = {}) {
  const store = new Map(Object.entries(initial));
  return {
    getItem: (key: string) => store.get(key) ?? null,
    setItem: (key: string, value: string) => { store.set(key, value); },
  };
}

class TestErrorBoundary extends Component<{ children: ReactNode }, { failed: boolean }> {
  state = { failed: false };

  static getDerivedStateFromError() {
    return { failed: true };
  }

  render() {
    return this.state.failed ? <div>error-boundary</div> : this.props.children;
  }
}

afterEach(() => {
  vi.restoreAllMocks();
});

describe('isChunkLoadError', () => {
  it('recognizes browser dynamic import failure messages', () => {
    expect(isChunkLoadError(new TypeError('Failed to fetch dynamically imported module: https://community.example/assets/ParticipationChart-2e7bf15f.js'))).toBe(true);
    expect(isChunkLoadError(new TypeError('error loading dynamically imported module'))).toBe(true);
    expect(isChunkLoadError(new Error('Importing a module script failed.'))).toBe(true);
  });

  it('recognizes chunk errors reported by name or webpack message', () => {
    const error = new Error('failed');
    error.name = 'ChunkLoadError';
    expect(isChunkLoadError(error)).toBe(true);
    expect(isChunkLoadError('Loading chunk 42 failed.')).toBe(true);
  });

  it('ignores unrelated failures and empty values', () => {
    expect(isChunkLoadError(new Error('Request failed with status 500'))).toBe(false);
    expect(isChunkLoadError(undefined)).toBe(false);
    expect(isChunkLoadError(42)).toBe(false);
  });
});

describe('reloadOnceForChunkLoadError', () => {
  it('reloads once and records the attempt in session storage', () => {
    const storage = memoryStorage();
    const reload = vi.fn();
    expect(reloadOnceForChunkLoadError({ storage, reload, now: () => NOW })).toBe(true);
    expect(reload).toHaveBeenCalledTimes(1);
    expect(storage.getItem(CHUNK_RELOAD_GUARD_KEY)).toBe(String(NOW));
  });

  it('does not reload again inside the cooldown window', () => {
    const storage = memoryStorage({ [CHUNK_RELOAD_GUARD_KEY]: String(NOW) });
    const reload = vi.fn();
    expect(reloadOnceForChunkLoadError({ storage, reload, now: () => NOW + CHUNK_RELOAD_COOLDOWN_MS - 1 })).toBe(false);
    expect(reload).not.toHaveBeenCalled();
  });

  it('allows a new reload after the cooldown window', () => {
    const storage = memoryStorage({ [CHUNK_RELOAD_GUARD_KEY]: String(NOW) });
    const reload = vi.fn();
    expect(reloadOnceForChunkLoadError({ storage, reload, now: () => NOW + CHUNK_RELOAD_COOLDOWN_MS })).toBe(true);
    expect(reload).toHaveBeenCalledTimes(1);
    expect(storage.getItem(CHUNK_RELOAD_GUARD_KEY)).toBe(String(NOW + CHUNK_RELOAD_COOLDOWN_MS));
  });

  it('treats an unreadable guard value as missing', () => {
    const storage = memoryStorage({ [CHUNK_RELOAD_GUARD_KEY]: 'not-a-number' });
    const reload = vi.fn();
    expect(reloadOnceForChunkLoadError({ storage, reload, now: () => NOW })).toBe(true);
  });

  it('never reloads without usable session storage', () => {
    const reload = vi.fn();
    expect(reloadOnceForChunkLoadError({ storage: null, reload, now: () => NOW })).toBe(false);
    expect(reload).not.toHaveBeenCalled();
  });

  it('never reloads when the guard cannot be persisted', () => {
    const reload = vi.fn();
    const storage = {
      getItem: () => null,
      setItem: () => { throw new Error('quota exceeded'); },
    };
    expect(reloadOnceForChunkLoadError({ storage, reload, now: () => NOW })).toBe(false);
    expect(reload).not.toHaveBeenCalled();
  });

  it('reads the guard from session storage by default', () => {
    window.sessionStorage.clear();
    window.sessionStorage.setItem(CHUNK_RELOAD_GUARD_KEY, String(NOW));
    const reload = vi.fn();
    expect(reloadOnceForChunkLoadError({ reload, now: () => NOW + 1 })).toBe(false);
    expect(reload).not.toHaveBeenCalled();
    window.sessionStorage.clear();
  });
});

describe('lazyWithChunkReload', () => {
  it('renders the lazy component when the chunk loads', async () => {
    const LazyView = lazyWithChunkReload(async () => ({ default: () => <div>chunk-loaded</div> }));
    render(<Suspense fallback={<div>loading</div>}><LazyView /></Suspense>);
    expect(await screen.findByText('chunk-loaded')).toBeInTheDocument();
  });

  it('reloads once and keeps suspending when a lazy chunk fails to load', async () => {
    const storage = memoryStorage();
    const reload = vi.fn();
    const LazyView = lazyWithChunkReload(async (): Promise<{ default: () => JSX.Element }> => {
      throw new TypeError('Failed to fetch dynamically imported module: https://community.example/assets/ParticipationChart-2e7bf15f.js');
    }, { storage, reload, now: () => NOW });
    render(
      <TestErrorBoundary>
        <Suspense fallback={<div>loading</div>}><LazyView /></Suspense>
      </TestErrorBoundary>,
    );
    await waitFor(() => expect(reload).toHaveBeenCalledTimes(1));
    expect(screen.getByText('loading')).toBeInTheDocument();
    expect(screen.queryByText('error-boundary')).not.toBeInTheDocument();
  });

  it('enters the error boundary when a chunk keeps failing after the automatic reload', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    const storage = memoryStorage({ [CHUNK_RELOAD_GUARD_KEY]: String(NOW) });
    const reload = vi.fn();
    const LazyView = lazyWithChunkReload(async (): Promise<{ default: () => JSX.Element }> => {
      throw new TypeError('Failed to fetch dynamically imported module');
    }, { storage, reload, now: () => NOW + 1 });
    render(
      <TestErrorBoundary>
        <Suspense fallback={<div>loading</div>}><LazyView /></Suspense>
      </TestErrorBoundary>,
    );
    expect(await screen.findByText('error-boundary')).toBeInTheDocument();
    expect(reload).not.toHaveBeenCalled();
  });

  it('propagates unrelated lazy errors without reloading', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    const reload = vi.fn();
    const LazyView = lazyWithChunkReload(async (): Promise<{ default: () => JSX.Element }> => {
      throw new Error('module evaluation failed');
    }, { storage: memoryStorage(), reload, now: () => NOW });
    render(
      <TestErrorBoundary>
        <Suspense fallback={<div>loading</div>}><LazyView /></Suspense>
      </TestErrorBoundary>,
    );
    expect(await screen.findByText('error-boundary')).toBeInTheDocument();
    expect(reload).not.toHaveBeenCalled();
  });
});

describe('installChunkLoadRecovery', () => {
  it('reloads once on vite preload errors, then lets later failures surface', () => {
    const storage = memoryStorage();
    const reload = vi.fn();
    const uninstall = installChunkLoadRecovery({ storage, reload, now: () => NOW });

    const first = new Event('vite:preloadError', { cancelable: true });
    window.dispatchEvent(first);
    expect(first.defaultPrevented).toBe(true);
    expect(reload).toHaveBeenCalledTimes(1);

    const second = new Event('vite:preloadError', { cancelable: true });
    window.dispatchEvent(second);
    expect(second.defaultPrevented).toBe(false);
    expect(reload).toHaveBeenCalledTimes(1);

    uninstall();
    const third = new Event('vite:preloadError', { cancelable: true });
    window.dispatchEvent(third);
    expect(third.defaultPrevented).toBe(false);
    expect(reload).toHaveBeenCalledTimes(1);
  });
});
