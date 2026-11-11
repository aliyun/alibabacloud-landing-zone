/**
 * 右下角短暂提醒的纯状态机。时间全部由事件注入，便于用虚拟时钟断言计时规则，
 * 也不依赖后台计时器是否被浏览器节流。
 */

export const REMINDER_TTL_MS = 60_000;
export const REMINDER_MAX_ITEMS = 100;

export interface ReminderItem {
  notificationId: number;
  expiresAt: number;
}

export interface ReminderQueueState {
  /** 最新的排在最前。 */
  items: ReminderItem[];
  expanded: boolean;
  /** 展开态下最后一次用户操作时间；收起态为 null。 */
  lastInteractionAt: number | null;
}

export type ReminderQueueEvent =
  | { type: 'received'; notificationId: number; now: number }
  | { type: 'expand'; now: number }
  | { type: 'interact'; now: number }
  | { type: 'collapse'; now: number }
  | { type: 'dismiss'; notificationId: number; now: number }
  | { type: 'dismissAll' }
  | { type: 'tick'; now: number }
  | { type: 'clear' };

export function createReminderQueueState(): ReminderQueueState {
  return { items: [], expanded: false, lastInteractionAt: null };
}

function withFreshExpiry(state: ReminderQueueState, now: number): ReminderQueueState {
  return {
    items: state.items.map((item) => ({ ...item, expiresAt: now + REMINDER_TTL_MS })),
    expanded: false,
    lastInteractionAt: null,
  };
}

export function reduceReminderQueue(
  state: ReminderQueueState,
  event: ReminderQueueEvent,
): ReminderQueueState {
  switch (event.type) {
    case 'received': {
      // 同一 ID 的重放（重连、StrictMode）只入队一次。
      if (state.items.some((item) => item.notificationId === event.notificationId)) {
        return state;
      }
      const items = [
        { notificationId: event.notificationId, expiresAt: event.now + REMINDER_TTL_MS },
        ...state.items,
      ].slice(0, REMINDER_MAX_ITEMS);
      return { ...state, items };
    }
    case 'expand':
      if (!state.items.length) return state;
      return { ...state, expanded: true, lastInteractionAt: event.now };
    case 'interact':
      if (!state.expanded) return state;
      return { ...state, lastInteractionAt: event.now };
    case 'collapse':
      if (!state.items.length) {
        return { ...state, expanded: false, lastInteractionAt: null };
      }
      return withFreshExpiry(state, event.now);
    case 'dismiss': {
      const items = state.items.filter((item) => item.notificationId !== event.notificationId);
      if (items.length === state.items.length) return state;
      if (!items.length) return createReminderQueueState();
      if (state.expanded && items.length < 2) {
        return withFreshExpiry({ ...state, items }, event.now);
      }
      return { ...state, items };
    }
    case 'dismissAll':
    case 'clear':
      return createReminderQueueState();
    case 'tick': {
      if (!state.items.length) return state;
      if (state.expanded) {
        // 展开期间暂停移除；闲置满 60 秒才收起，收起时统一续期。
        if (state.lastInteractionAt == null) return state;
        if (event.now - state.lastInteractionAt < REMINDER_TTL_MS) return state;
        return withFreshExpiry(state, event.now);
      }
      const items = state.items.filter((item) => item.expiresAt > event.now);
      if (items.length === state.items.length) return state;
      return { ...state, items };
    }
    default:
      return state;
  }
}

/** 收起态取最早到期，展开态取闲置截止；用于倒计时展示。 */
export function reminderRemainingMs(state: ReminderQueueState, now: number): number {
  if (!state.items.length) return 0;
  if (state.expanded) {
    if (state.lastInteractionAt == null) return REMINDER_TTL_MS;
    return Math.max(0, state.lastInteractionAt + REMINDER_TTL_MS - now);
  }
  const earliest = Math.min(...state.items.map((item) => item.expiresAt));
  return Math.max(0, earliest - now);
}
