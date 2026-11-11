/**
 * 跨标签页浏览器通知去重：同一 origin 的多个标签页会同时收到相同实时事件，
 * 只允许一个标签页真正弹出系统通知。认领必须原子（IndexedDB 唯一键 add），
 * 否则「先读后写」在并发下会两个标签页都成功。
 *
 * 只持久化去重 ID 与创建时间，不存通知正文或凭证；记录 7 天后清理。
 */

const DB_NAME = 'autowonder-browser-notifications';
const DB_VERSION = 1;
const STORE_NAME = 'claims';
export const CLAIM_RETENTION_MS = 7 * 24 * 60 * 60 * 1000;

export interface ClaimRecord {
  key: string;
  createdAt: number;
}

export interface ClaimStore {
  /** 原子写入；键已存在或存储不可用时返回 false。 */
  add(record: ClaimRecord): Promise<boolean>;
  /** 清理过期记录，尽力而为。 */
  prune(cutoff: number): Promise<void>;
}

export function notificationClaimKey(workspaceId: number, userId: number, notificationId: number): string {
  return `notification:${workspaceId}:${userId}:${notificationId}`;
}

export function clarificationClaimKey(
  workspaceId: number,
  userId: number,
  workitemId: number | string,
  conversationId: number | string,
  requestId: string,
): string {
  return `clarification:${workspaceId}:${userId}:${workitemId}:${conversationId}:${requestId}`;
}

let defaultStore: ClaimStore | null | undefined;

function getDefaultClaimStore(): ClaimStore | null {
  if (defaultStore === undefined) {
    defaultStore = createIndexedDbClaimStore(
      typeof globalThis.indexedDB === 'undefined' ? undefined : globalThis.indexedDB,
    );
  }
  return defaultStore;
}

/**
 * 认领一个去重键。存储不可用、键已存在或任何异常都返回 false，
 * 调用方据此跳过浏览器通知但保留正常站内行为。
 */
export async function claimBrowserNotification(
  key: string,
  now: number,
  store: ClaimStore | null = getDefaultClaimStore(),
): Promise<boolean> {
  if (!key || !store) return false;
  let claimed = false;
  try {
    claimed = await store.add({ key, createdAt: now });
  } catch {
    return false;
  }
  if (!claimed) return false;
  try {
    await store.prune(now - CLAIM_RETENTION_MS);
  } catch {
    /* 清理失败不影响已经成功的认领 */
  }
  return true;
}

export function createIndexedDbClaimStore(factory: IDBFactory | undefined): ClaimStore | null {
  if (!factory) return null;
  return {
    add(record: ClaimRecord) {
      return runTransaction(factory, (store, setResult) => {
        const request = store.add(record);
        request.onsuccess = () => setResult(true);
        // 键冲突时事务会中止，最终由 transaction 的 abort 收敛为 false。
        request.onerror = () => setResult(false);
      });
    },
    async prune(cutoff: number) {
      await runTransaction(factory, (store, setResult) => {
        const request = store.openCursor();
        request.onsuccess = () => {
          const cursor = request.result;
          if (!cursor) {
            setResult(true);
            return;
          }
          const value = cursor.value as ClaimRecord | undefined;
          if (value && typeof value.createdAt === 'number' && value.createdAt < cutoff) {
            cursor.delete();
          }
          cursor.continue();
        };
        request.onerror = () => setResult(false);
      });
    },
  };
}

/**
 * 同一 origin 的并发认领共享一次 open/upgrade：各自 open 会在首次升级时相互竞争，
 * 导致后到的认领失败、少发一条浏览器通知（AC07）。连接常驻复用，不按事务关闭。
 */
const sharedDatabases = new WeakMap<IDBFactory, Promise<IDBDatabase>>();

function openDatabase(factory: IDBFactory): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = factory.open(DB_NAME, DB_VERSION);
    request.onupgradeneeded = () => {
      const database = request.result;
      if (!database.objectStoreNames.contains(STORE_NAME)) {
        database.createObjectStore(STORE_NAME, { keyPath: 'key' });
      }
    };
    request.onsuccess = () => {
      const database = request.result;
      // 版本升级时让出连接，并丢弃缓存以便下次重新打开。
      database.onversionchange = () => {
        try {
          database.close();
        } catch {
          /* ignore */
        }
        sharedDatabases.delete(factory);
      };
      resolve(database);
    };
    request.onerror = () => reject(request.error ?? new Error('indexeddb open failed'));
    request.onblocked = () => reject(new Error('indexeddb blocked'));
  });
}

function openSharedDatabase(factory: IDBFactory): Promise<IDBDatabase> {
  const cached = sharedDatabases.get(factory);
  if (cached) return cached;
  const opening = openDatabase(factory).catch((error: unknown) => {
    // 打开失败不缓存，后续认领可以重新尝试。
    sharedDatabases.delete(factory);
    throw error;
  });
  sharedDatabases.set(factory, opening);
  return opening;
}

function runTransaction(
  factory: IDBFactory,
  work: (store: IDBObjectStore, setResult: (value: boolean) => void) => void,
): Promise<boolean> {
  return openSharedDatabase(factory).then(
    (database) => new Promise<boolean>((resolve) => {
      let settled = false;
      let result = false;
      const finish = (value: boolean) => {
        if (settled) return;
        settled = true;
        resolve(value);
      };
      let transaction: IDBTransaction;
      try {
        transaction = database.transaction(STORE_NAME, 'readwrite');
      } catch {
        finish(false);
        return;
      }
      transaction.oncomplete = () => finish(result);
      transaction.onerror = () => finish(false);
      transaction.onabort = () => finish(false);
      try {
        work(transaction.objectStore(STORE_NAME), (value) => {
          result = value;
        });
      } catch {
        result = false;
        try {
          transaction.abort();
        } catch {
          finish(false);
        }
      }
    }),
    () => false,
  );
}
