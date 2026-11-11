import { describe, expect, it } from 'vitest';
import {
  REMINDER_MAX_ITEMS,
  REMINDER_TTL_MS,
  createReminderQueueState,
  reduceReminderQueue,
  reminderRemainingMs,
  type ReminderQueueEvent,
  type ReminderQueueState,
} from './reminderQueue';

const T0 = 1_800_000_000_000;

function apply(state: ReminderQueueState, ...events: ReminderQueueEvent[]): ReminderQueueState {
  return events.reduce(reduceReminderQueue, state);
}

function received(notificationId: number, now: number): ReminderQueueEvent {
  return { type: 'received', notificationId, now };
}

describe('reminderQueue', () => {
  it('keeps a single reminder for 60 seconds and removes it exactly at the deadline', () => {
    const atT0 = apply(createReminderQueueState(), received(1, T0));
    expect(atT0.items.map((item) => item.notificationId)).toEqual([1]);

    const beforeDeadline = apply(atT0, { type: 'tick', now: T0 + REMINDER_TTL_MS - 1 });
    expect(beforeDeadline.items).toHaveLength(1);

    const atDeadline = apply(beforeDeadline, { type: 'tick', now: T0 + REMINDER_TTL_MS });
    expect(atDeadline.items).toHaveLength(0);
  });

  it('stacks multiple reminders collapsed and newest first', () => {
    const state = apply(
      createReminderQueueState(),
      received(1, T0),
      received(2, T0 + 1000),
      received(3, T0 + 2000),
    );

    expect(state.expanded).toBe(false);
    expect(state.items.map((item) => item.notificationId)).toEqual([3, 2, 1]);
  });

  it('ignores a replayed notification id', () => {
    const once = apply(createReminderQueueState(), received(7, T0));
    const twice = apply(once, received(7, T0 + 10));

    expect(twice).toBe(once);
  });

  it('pauses expiry while expanded and collapses after 60 idle seconds', () => {
    const stacked = apply(
      createReminderQueueState(),
      received(1, T0),
      received(2, T0 + 1000),
      { type: 'expand', now: T0 + 2000 },
    );

    // 展开期间暂停移除：闲置未满 60 秒时，即使原本的到期时间已过也保留。
    const stillExpanded = apply(stacked, { type: 'tick', now: T0 + 2000 + REMINDER_TTL_MS - 1 });
    expect(stillExpanded.expanded).toBe(true);
    expect(stillExpanded.items).toHaveLength(2);

    const collapsed = apply(stillExpanded, { type: 'tick', now: T0 + 2000 + REMINDER_TTL_MS });
    expect(collapsed.expanded).toBe(false);
    expect(collapsed.lastInteractionAt).toBeNull();
    expect(collapsed.items.every((item) => item.expiresAt === T0 + 2000 + REMINDER_TTL_MS + REMINDER_TTL_MS))
      .toBe(true);

    const gone = apply(collapsed, { type: 'tick', now: T0 + 2000 + 2 * REMINDER_TTL_MS });
    expect(gone.items).toHaveLength(0);
  });

  it('does not reset the idle timer when new reminders arrive while expanded', () => {
    const expanded = apply(
      createReminderQueueState(),
      received(1, T0),
      received(2, T0),
      { type: 'expand', now: T0 },
    );

    const withNew = apply(expanded, received(3, T0 + 30_000));

    expect(withNew.expanded).toBe(true);
    expect(withNew.lastInteractionAt).toBe(T0);
    expect(withNew.items.map((item) => item.notificationId)).toEqual([3, 2, 1]);
  });

  it('resets the idle timer on user interaction and ignores it while collapsed', () => {
    const expanded = apply(
      createReminderQueueState(),
      received(1, T0),
      received(2, T0),
      { type: 'expand', now: T0 },
      { type: 'interact', now: T0 + 40_000 },
    );

    expect(expanded.lastInteractionAt).toBe(T0 + 40_000);
    expect(apply(expanded, { type: 'tick', now: T0 + 99_000 }).expanded).toBe(true);
    expect(apply(expanded, { type: 'tick', now: T0 + 100_000 }).expanded).toBe(false);

    const collapsed = createReminderQueueState();
    expect(apply(collapsed, { type: 'interact', now: T0 })).toBe(collapsed);
  });

  it('gives the remaining reminder a fresh 60 seconds when dismissing leaves only one', () => {
    const expanded = apply(
      createReminderQueueState(),
      received(1, T0),
      received(2, T0),
      { type: 'expand', now: T0 },
    );

    const single = apply(expanded, { type: 'dismiss', notificationId: 2, now: T0 + 10_000 });

    expect(single.expanded).toBe(false);
    expect(single.items).toHaveLength(1);
    expect(single.items[0].expiresAt).toBe(T0 + 10_000 + REMINDER_TTL_MS);
  });

  it('clears everything when the queue is dismissed or emptied', () => {
    const stacked = apply(createReminderQueueState(), received(1, T0), received(2, T0));

    expect(apply(stacked, { type: 'dismissAll' })).toEqual(createReminderQueueState());
    expect(apply(stacked, { type: 'clear' })).toEqual(createReminderQueueState());
    expect(apply(apply(stacked, { type: 'dismiss', notificationId: 1, now: T0 }),
      { type: 'dismiss', notificationId: 2, now: T0 })).toEqual(createReminderQueueState());
  });

  it('ignores dismissal of an unknown reminder', () => {
    const stacked = apply(createReminderQueueState(), received(1, T0));
    expect(apply(stacked, { type: 'dismiss', notificationId: 99, now: T0 })).toBe(stacked);
  });

  it('keeps at most the 100 newest reminders', () => {
    let state = createReminderQueueState();
    for (let index = 1; index <= REMINDER_MAX_ITEMS + 1; index += 1) {
      state = apply(state, received(index, T0));
    }

    expect(state.items).toHaveLength(REMINDER_MAX_ITEMS);
    expect(state.items[0].notificationId).toBe(REMINDER_MAX_ITEMS + 1);
    expect(state.items.some((item) => item.notificationId === 1)).toBe(false);
  });

  it('settles every expired reminder after a throttled background tab resumes', () => {
    const stacked = apply(createReminderQueueState(), received(1, T0), received(2, T0 + 10));

    const resumed = apply(stacked, { type: 'tick', now: T0 + 10 * REMINDER_TTL_MS });

    expect(resumed.items).toHaveLength(0);
  });

  it('ignores expand and tick on an empty queue', () => {
    const empty = createReminderQueueState();
    expect(apply(empty, { type: 'expand', now: T0 })).toBe(empty);
    expect(apply(empty, { type: 'tick', now: T0 })).toBe(empty);
  });

  it('reports the next deadline for the countdown label', () => {
    const single = apply(createReminderQueueState(), received(1, T0));
    expect(reminderRemainingMs(single, T0)).toBe(REMINDER_TTL_MS);
    expect(reminderRemainingMs(single, T0 + 15_000)).toBe(REMINDER_TTL_MS - 15_000);
    expect(reminderRemainingMs(single, T0 + REMINDER_TTL_MS + 1)).toBe(0);

    const expanded = apply(single, received(2, T0), { type: 'expand', now: T0 + 5000 });
    expect(reminderRemainingMs(expanded, T0 + 5000)).toBe(REMINDER_TTL_MS);
    expect(reminderRemainingMs(expanded, T0 + 5000 + 20_000)).toBe(REMINDER_TTL_MS - 20_000);

    expect(reminderRemainingMs(createReminderQueueState(), T0)).toBe(0);
  });
});
