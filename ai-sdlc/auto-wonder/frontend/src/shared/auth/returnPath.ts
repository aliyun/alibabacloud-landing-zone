export const AUTH_RETURN_PATH_KEY = 'aw-auth-return-path';

const MAX_RETURN_PATH_LENGTH = 2048;
const INTERNAL_ORIGIN = 'https://autowonder.invalid';
const AUTH_ENTRY_PATHS = ['/login', '/register', '/sso'];

/**
 * Only an absolute same-site path may be restored after login: it must start with a single
 * slash, carry no scheme/host, no backslashes (browsers treat them as separators) and no
 * control characters. Everything else falls back to the workspace select page.
 */
export function sanitizeReturnPath(raw: unknown): string | null {
  if (typeof raw !== 'string') return null;
  const value = raw.trim();
  if (!value || value.length > MAX_RETURN_PATH_LENGTH) return null;
  if (!value.startsWith('/') || value.startsWith('//')) return null;
  if (value.includes('\\')) return null;
  for (const character of value) {
    const code = character.charCodeAt(0);
    if (code <= 0x1f || code === 0x7f) return null;
  }
  try {
    const url = new URL(value, INTERNAL_ORIGIN);
    if (url.origin !== INTERNAL_ORIGIN) return null;
    const normalized = `${url.pathname}${url.search}${url.hash}`;
    // URL normalization can turn dot segments into a protocol-relative path
    // (e.g. /..//evil.com -> //evil.com), so re-check the normalized result.
    if (!normalized.startsWith('/') || normalized.startsWith('//')) return null;
    return normalized;
  } catch {
    return null;
  }
}

export function isAuthEntryPath(path: string): boolean {
  const pathname = path.split(/[?#]/, 1)[0];
  return AUTH_ENTRY_PATHS.some((entry) => pathname === entry || pathname.startsWith(`${entry}/`));
}

/** 会话失效跳转前记住原页面；登录页自身不作为恢复目标。 */
export function rememberReturnPath(path: string): void {
  const safe = sanitizeReturnPath(path);
  if (!safe || isAuthEntryPath(safe) || typeof window === 'undefined') return;
  try {
    window.sessionStorage.setItem(AUTH_RETURN_PATH_KEY, safe);
  } catch {
    // Storage can be unavailable (private mode); restoring is best-effort.
  }
}

/** 只读取不消费：登录页据此把目标页带入 SSO 发起地址，正式导航时再消费。 */
export function peekReturnPath(): string | null {
  if (typeof window === 'undefined') return null;
  try {
    return sanitizeReturnPath(window.sessionStorage.getItem(AUTH_RETURN_PATH_KEY));
  } catch {
    return null;
  }
}

/** 读取并清除恢复目标，避免下一次登录误用旧路径。 */
export function consumeReturnPath(): string | null {
  if (typeof window === 'undefined') return null;
  try {
    const stored = window.sessionStorage.getItem(AUTH_RETURN_PATH_KEY);
    window.sessionStorage.removeItem(AUTH_RETURN_PATH_KEY);
    return sanitizeReturnPath(stored);
  } catch {
    return null;
  }
}

/** 路由守卫带来的 location.state.from（不消费存储）。 */
function stateReturnPath(from: unknown): string | null {
  if (!from || typeof from !== 'object') return null;
  const location = from as { pathname?: unknown; search?: unknown; hash?: unknown };
  const pathname = typeof location.pathname === 'string' ? location.pathname : '';
  const search = typeof location.search === 'string' ? location.search : '';
  const hash = typeof location.hash === 'string' ? location.hash : '';
  return sanitizeReturnPath(pathname + search + hash);
}

/** 登录页发起 SSO 前使用的目标：优先路由状态，其次失效前记住的路径；都不消费存储。 */
export function currentReturnTarget(from: unknown): string | null {
  return stateReturnPath(from) ?? peekReturnPath();
}

/** 路由守卫带来的 location.state.from 优先，其次才是失效跳转前记住的路径。 */
export function resolvePostLoginPath(from: unknown): string {
  const fromState = stateReturnPath(from);
  const stored = consumeReturnPath();
  return fromState ?? stored ?? '/workspaces';
}
