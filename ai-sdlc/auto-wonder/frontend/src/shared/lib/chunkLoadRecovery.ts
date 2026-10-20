import { lazy, type ComponentType, type LazyExoticComponent } from 'react';

export const CHUNK_RELOAD_GUARD_KEY = 'autowonder.chunk-reload-at';
export const CHUNK_RELOAD_COOLDOWN_MS = 10_000;

const CHUNK_LOAD_ERROR_PATTERNS = [
  /failed to fetch dynamically imported module/i,
  /error loading dynamically imported module/i,
  /importing a module script failed/i,
  /loading chunk [\w-]+ failed/i,
];

export interface ChunkReloadOptions {
  storage?: Pick<Storage, 'getItem' | 'setItem'> | null;
  reload?: () => void;
  now?: () => number;
}

export function isChunkLoadError(error: unknown): boolean {
  if (error !== null && typeof error === 'object' && (error as { name?: unknown }).name === 'ChunkLoadError') {
    return true;
  }
  const message = error instanceof Error ? error.message : typeof error === 'string' ? error : '';
  return CHUNK_LOAD_ERROR_PATTERNS.some((pattern) => pattern.test(message));
}

function sessionStorageOrNull(): Pick<Storage, 'getItem' | 'setItem'> | null {
  try {
    return window.sessionStorage;
  } catch {
    return null;
  }
}

export function reloadOnceForChunkLoadError(options: ChunkReloadOptions = {}): boolean {
  const storage = options.storage === undefined ? sessionStorageOrNull() : options.storage;
  if (!storage) return false;
  const now = options.now?.() ?? Date.now();
  const lastReloadAt = Number(storage.getItem(CHUNK_RELOAD_GUARD_KEY));
  if (Number.isFinite(lastReloadAt) && now - lastReloadAt < CHUNK_RELOAD_COOLDOWN_MS) {
    return false;
  }
  try {
    storage.setItem(CHUNK_RELOAD_GUARD_KEY, String(now));
  } catch {
    // Without a persisted guard a failing chunk would reload forever.
    return false;
  }
  (options.reload ?? (() => window.location.reload()))();
  return true;
}

export function lazyWithChunkReload<Props>(
  loader: () => Promise<{ default: ComponentType<Props> }>,
  options: ChunkReloadOptions = {},
): LazyExoticComponent<ComponentType<Props>> {
  return lazy(async () => {
    try {
      return await loader();
    } catch (error) {
      if (isChunkLoadError(error) && reloadOnceForChunkLoadError(options)) {
        // The document is navigating away; hold Suspense instead of flashing the error page.
        return new Promise<never>(() => {});
      }
      throw error;
    }
  });
}

export function installChunkLoadRecovery(options: ChunkReloadOptions = {}): () => void {
  const onPreloadError = (event: Event) => {
    if (reloadOnceForChunkLoadError(options)) {
      event.preventDefault();
    }
  };
  window.addEventListener('vite:preloadError', onPreloadError);
  return () => window.removeEventListener('vite:preloadError', onPreloadError);
}
