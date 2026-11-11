/**
 * 浏览器（系统）通知的唯一入口。只在「标签页不可见 + 已授权 + 环境支持 + 身份仍匹配」
 * 且跨标签页认领成功时才构造，任何失败都只返回 null，不能影响业务与站内提醒。
 */
import { claimBrowserNotification } from './browserNotificationClaim';

export type BrowserNotificationPermission = 'default' | 'granted' | 'denied' | 'unsupported';

export interface NotificationIdentity {
  workspaceId: number | null;
  userId: number | null;
}

export interface BrowserNotificationDeps {
  permission?: () => BrowserNotificationPermission;
  visibility?: () => DocumentVisibilityState;
  claim?: (key: string, now: number) => Promise<boolean>;
  construct?: (title: string, options: NotificationOptions) => Notification;
  resolveIdentity?: () => NotificationIdentity | null;
  now?: () => number;
  focus?: () => void;
}

export interface ShowBrowserNotificationInput {
  key: string;
  title: string;
  body: string;
  workspaceId: number;
  userId: number;
  onClick: () => void;
}

function notificationApi(): typeof Notification | null {
  if (typeof window === 'undefined' || !('Notification' in window)) return null;
  const api = (window as Window & { Notification?: typeof Notification }).Notification;
  return api ?? null;
}

function normalizePermission(value: unknown): BrowserNotificationPermission {
  if (value === 'granted' || value === 'denied') return value;
  return 'default';
}

export function getBrowserNotificationPermission(): BrowserNotificationPermission {
  const api = notificationApi();
  if (!api) return 'unsupported';
  return normalizePermission(api.permission);
}

/** 只能由用户显式点击触发：浏览器只在手势内接受授权请求。 */
export async function requestBrowserNotificationPermission(): Promise<BrowserNotificationPermission> {
  const api = notificationApi();
  if (!api) return 'unsupported';
  try {
    const result = await api.requestPermission();
    return normalizePermission(result ?? getBrowserNotificationPermission());
  } catch {
    // 部分浏览器在不安全上下文会抛错；回落到当前真实权限，不阻塞业务。
    return getBrowserNotificationPermission();
  }
}

export async function showBrowserNotification(
  input: ShowBrowserNotificationInput,
  deps: BrowserNotificationDeps = {},
): Promise<Notification | null> {
  const permissionOf = deps.permission ?? getBrowserNotificationPermission;
  if (permissionOf() !== 'granted') return null;

  const visibilityOf = deps.visibility
    ?? (() => (typeof document === 'undefined' ? 'visible' : document.visibilityState));
  if (visibilityOf() !== 'hidden') return null;

  if (!input.key || input.workspaceId <= 0 || input.userId <= 0) return null;

  // 账号或空间在等待期间被切换时，不能把提醒也串到新身份上。
  if (deps.resolveIdentity) {
    const identity = deps.resolveIdentity();
    if (!identity
      || identity.workspaceId !== input.workspaceId
      || identity.userId !== input.userId) {
      return null;
    }
  }

  const claim = deps.claim ?? ((key: string, now: number) => claimBrowserNotification(key, now));
  let claimed = false;
  try {
    claimed = await claim(input.key, (deps.now ?? Date.now)());
  } catch {
    return null;
  }
  if (!claimed) return null;

  try {
    const construct = deps.construct
      ?? ((title: string, options: NotificationOptions) => new Notification(title, options));
    const notification = construct(input.title, { body: input.body });
    notification.onclick = () => {
      try {
        (deps.focus ?? (() => window.focus()))();
      } catch {
        /* 聚焦失败仍要继续导航 */
      }
      input.onClick();
    };
    return notification;
  } catch {
    // 构造通知抛错（权限被撤销、平台限制）时静默跳过，站内提醒照常工作。
    return null;
  }
}
