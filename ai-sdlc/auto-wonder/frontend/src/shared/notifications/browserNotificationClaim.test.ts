import { describe, expect, it, vi } from 'vitest';
import {
  CLAIM_RETENTION_MS,
  claimBrowserNotification,
  clarificationClaimKey,
  createIndexedDbClaimStore,
  notificationClaimKey,
  type ClaimRecord,
  type ClaimStore,
} from './browserNotificationClaim';

type Handler = ((event?: unknown) => void) | null;

class FakeRequest<T = unknown> {
  result!: T;
  error: unknown = null;
  onsuccess: Handler = null;
  onerror: Handler = null;
  onupgradeneeded: Handler = null;
}

class FakeStoreData {
  records = new Map<string, ClaimRecord>();
}

class FakeTransaction {
  oncomplete: Handler = null;
  onerror: Handler = null;
  onabort: Handler = null;

  private pending = 0;
  private failed = false;

  constructor(private data: FakeStoreData) {}

  objectStore() {
    return new FakeObjectStore(this);
  }

  abort() {
    if (this.failed) return;
    this.failed = true;
    this.onabort?.();
  }

  addRequest(value: ClaimRecord): FakeRequest<string> {
    const request = new FakeRequest<string>();
    this.pending += 1;
    setTimeout(() => {
      if (this.data.records.has(value.key)) {
        request.error = { name: 'ConstraintError' };
        request.onerror?.();
        this.settleOperation();
        this.abort();
        return;
      }
      this.data.records.set(value.key, value);
      request.result = value.key;
      request.onsuccess?.();
      this.settleOperation();
    }, 0);
    return request;
  }

  cursorRequest(): FakeRequest<{ value: ClaimRecord; delete: () => void; continue: () => void } | null> {
    const request = new FakeRequest<{ value: ClaimRecord; delete: () => void; continue: () => void } | null>();
    this.pending += 1;
    const entries = Array.from(this.data.records.entries());
    let index = 0;
    const step = () => {
      if (index >= entries.length) {
        request.result = null;
        request.onsuccess?.();
        this.settleOperation();
        return;
      }
      const [key, value] = entries[index];
      index += 1;
      request.result = {
        value,
        delete: () => {
          this.data.records.delete(key);
        },
        continue: () => {
          setTimeout(step, 0);
        },
      };
      request.onsuccess?.();
    };
    setTimeout(step, 0);
    return request;
  }

  private settleOperation() {
    this.pending -= 1;
    if (this.pending !== 0) return;
    setTimeout(() => {
      if (!this.failed) this.oncomplete?.();
    }, 0);
  }
}

class FakeObjectStore {
  constructor(private transaction: FakeTransaction) {}

  add(value: ClaimRecord) {
    return this.transaction.addRequest(value);
  }

  openCursor() {
    return this.transaction.cursorRequest();
  }
}

class FakeDatabase {
  stores = new Map<string, FakeStoreData>();
  objectStoreNames = {
    contains: (name: string) => this.stores.has(name),
  };

  createObjectStore(name: string) {
    this.stores.set(name, new FakeStoreData());
    return {};
  }

  transaction(name: string) {
    const data = this.stores.get(name);
    if (!data) throw new Error(`missing store ${name}`);
    return new FakeTransaction(data);
  }

  close() {}
}

class FakeFactory {
  database = new FakeDatabase();
  failOpen = false;
  openCalls = 0;

  open = () => {
    this.openCalls += 1;
    const request = new FakeRequest<FakeDatabase>();
    setTimeout(() => {
      if (this.failOpen) {
        request.error = new Error('open failed');
        request.onerror?.();
        return;
      }
      request.result = this.database;
      if (this.database.stores.size === 0) request.onupgradeneeded?.();
      request.onsuccess?.();
    }, 0);
    return request;
  };
}

function factoryAdapter(): { factory: FakeFactory; store: ClaimStore } {
  const factory = new FakeFactory();
  const store = createIndexedDbClaimStore(factory as unknown as IDBFactory);
  if (!store) throw new Error('expected an indexeddb backed store');
  return { factory, store };
}

const NOW = Date.UTC(2026, 9, 29, 8, 0, 0);

describe('dedupe keys', () => {
  it('scopes system notifications by workspace, recipient and id', () => {
    expect(notificationClaimKey(10002, 10018, 9)).toBe('notification:10002:10018:9');
  });

  it('scopes clarification reminders by workitem, conversation and request', () => {
    expect(clarificationClaimKey(10002, 10018, 100, 7, 'req-1'))
      .toBe('clarification:10002:10018:100:7:req-1');
  });
});

describe('createIndexedDbClaimStore', () => {
  it('returns null when the runtime has no indexedDB', () => {
    expect(createIndexedDbClaimStore(undefined)).toBeNull();
  });
});

describe('claimBrowserNotification', () => {
  it('returns false without a usable key or store', async () => {
    const store: ClaimStore = { add: vi.fn().mockResolvedValue(true), prune: vi.fn() };
    await expect(claimBrowserNotification('', NOW, store)).resolves.toBe(false);
    await expect(claimBrowserNotification('k', NOW, null)).resolves.toBe(false);
    expect(store.add).not.toHaveBeenCalled();
  });

  it('claims a new key exactly once', async () => {
    const { store } = factoryAdapter();

    await expect(claimBrowserNotification('notification:1:2:3', NOW, store)).resolves.toBe(true);
    await expect(claimBrowserNotification('notification:1:2:3', NOW, store)).resolves.toBe(false);
  });

  it('keeps different keys independent', async () => {
    const { store } = factoryAdapter();

    await expect(claimBrowserNotification('a', NOW, store)).resolves.toBe(true);
    await expect(claimBrowserNotification('b', NOW, store)).resolves.toBe(true);
  });

  it('prunes records older than the retention window', async () => {
    const { factory, store } = factoryAdapter();

    await expect(claimBrowserNotification('old', NOW, store)).resolves.toBe(true);
    await expect(claimBrowserNotification('fresh', NOW + CLAIM_RETENTION_MS + 1, store)).resolves.toBe(true);

    const records = Array.from(factory.database.stores.get('claims')?.records.keys() ?? []);
    expect(records).toEqual(['fresh']);
  });

  it('degrades to false when the store throws', async () => {
    const store: ClaimStore = {
      add: vi.fn().mockRejectedValue(new Error('quota')),
      prune: vi.fn(),
    };
    await expect(claimBrowserNotification('k', NOW, store)).resolves.toBe(false);
  });

  it('keeps the claim when pruning fails', async () => {
    const store: ClaimStore = {
      add: vi.fn().mockResolvedValue(true),
      prune: vi.fn().mockRejectedValue(new Error('prune failed')),
    };
    await expect(claimBrowserNotification('k', NOW, store)).resolves.toBe(true);
  });

  it('degrades to false when indexedDB cannot be opened', async () => {
    const factory = new FakeFactory();
    factory.failOpen = true;
    const store = createIndexedDbClaimStore(factory as unknown as IDBFactory);

    await expect(claimBrowserNotification('k', NOW, store)).resolves.toBe(false);
  });
});

describe('concurrent claims', () => {
  it('shares one database open and claims different keys independently', async () => {
    const { factory, store } = factoryAdapter();

    const results = await Promise.all([
      claimBrowserNotification('notification:1:2:3', NOW, store),
      claimBrowserNotification('notification:1:2:4', NOW, store),
    ]);

    expect(results).toEqual([true, true]);
    // 并发认领若各自 open，会在首次升级时相互竞争并丢一条浏览器通知。
    expect(factory.openCalls).toBe(1);
  });

  it('lets exactly one concurrent claim win for the same key', async () => {
    const { store } = factoryAdapter();

    const results = await Promise.all([
      claimBrowserNotification('notification:1:2:3', NOW, store),
      claimBrowserNotification('notification:1:2:3', NOW, store),
    ]);

    expect(results.filter(Boolean)).toHaveLength(1);
  });
});
