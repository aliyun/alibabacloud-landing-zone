/**
 * Clarification squad/agent prefill persistence.
 *
 * Remembers the last squad+agent the user picked in the AI clarification
 * panel per workitem, so the Start Delivery modal can preselect them.
 */

export interface ClarificationPrefill {
  squadId: number;
  agentId: number;
  updatedAt: number;
}

const KEY_PREFIX = 'aw:clarification-prefill:';
const TTL_MS = 1000 * 60 * 60 * 24 * 30; // 30 days

function storage(): Storage | null {
  try {
    return typeof window !== 'undefined' ? window.localStorage : null;
  } catch {
    return null;
  }
}

function keyFor(workitemId: number | string): string {
  return `${KEY_PREFIX}${workitemId}`;
}

export function readClarificationPrefill(
  workitemId: number | string,
): ClarificationPrefill | null {
  const store = storage();
  if (!store) return null;
  const raw = store.getItem(keyFor(workitemId));
  if (!raw) return null;
  try {
    const parsed = JSON.parse(raw) as ClarificationPrefill;
    if (
      typeof parsed.squadId !== 'number' ||
      typeof parsed.agentId !== 'number' ||
      Date.now() - parsed.updatedAt > TTL_MS
    ) {
      store.removeItem(keyFor(workitemId));
      return null;
    }
    return parsed;
  } catch {
    store.removeItem(keyFor(workitemId));
    return null;
  }
}

export function writeClarificationPrefill(
  workitemId: number | string,
  prefill: Omit<ClarificationPrefill, 'updatedAt'>,
): void {
  const store = storage();
  if (!store) return;
  const payload: ClarificationPrefill = {
    ...prefill,
    updatedAt: Date.now(),
  };
  try {
    store.setItem(keyFor(workitemId), JSON.stringify(payload));
  } catch {
    // Storage full or disabled; ignore.
  }
}

export function clearClarificationPrefill(workitemId: number | string): void {
  storage()?.removeItem(keyFor(workitemId));
}
