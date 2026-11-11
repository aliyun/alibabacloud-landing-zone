/**
 * 用户活动判定：只有页面可见且最近确有真实交互，才把续期算作“前台使用”。
 * 隐藏标签页与闲置页面上的定时轮询、自动刷新一律按后台处理，不顺延会话。
 */
export const USER_ACTIVITY_WINDOW_MS = 5 * 60_000;

const ACTIVITY_EVENTS = ['pointerdown', 'keydown', 'wheel', 'touchstart'] as const;

let lastActivityAt: number | null = null;
let trackingInstalled = false;

/** 记录一次用户交互（也供测试显式设置活动时间）。 */
export function markUserActivity(now: number = Date.now()): void {
  lastActivityAt = now;
}

export function hasRecentUserActivity(now: number = Date.now()): boolean {
  return lastActivityAt !== null && now - lastActivityAt <= USER_ACTIVITY_WINDOW_MS;
}

/** 前台 = 页面可见且最近有用户交互；缺省（隐藏/闲置）按后台。 */
export function isUserActive(now: number = Date.now()): boolean {
  if (typeof document !== 'undefined' && document.visibilityState !== 'visible') return false;
  return hasRecentUserActivity(now);
}

export function installUserActivityTracking(): void {
  if (trackingInstalled || typeof window === 'undefined') return;
  trackingInstalled = true;
  const onActivity = () => markUserActivity();
  for (const event of ACTIVITY_EVENTS) {
    window.addEventListener(event, onActivity, { capture: true, passive: true });
  }
}
