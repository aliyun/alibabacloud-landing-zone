/**
 * Per-page view preference persistence (e.g. list vs grouped). Reading is whitelist-validated and
 * every localStorage access is guarded so privacy-mode or quota failures degrade to the default
 * view instead of breaking the page.
 */
export function readViewPreference<T extends string>(
  key: string,
  allowed: readonly T[],
  fallback: T,
): T {
  try {
    const stored = window.localStorage.getItem(key);
    if (stored !== null && (allowed as readonly string[]).includes(stored)) {
      return stored as T;
    }
  } catch {
    // ignore: localStorage unavailable
  }
  return fallback;
}

export function writeViewPreference(key: string, value: string): void {
  try {
    window.localStorage.setItem(key, value);
  } catch {
    // ignore: localStorage unavailable
  }
}
