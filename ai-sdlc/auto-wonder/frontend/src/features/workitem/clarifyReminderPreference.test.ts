import { afterEach, describe, it, expect, vi } from 'vitest';
import { isClarifyReminderSuppressed, suppressClarifyReminder } from './clarifyReminderPreference';

/** 与 clarifyReminderPreference.ts 内部的 key 保持一致；沿用 WorkitemListPage.test.tsx 直接写字面量的做法，
 *  避免为了测试把存储 key 导出成公共 API。 */
const KEY = 'autowonder.workitems.clarifyReminder';

describe('clarifyReminderPreference', () => {
  afterEach(() => {
    window.localStorage.removeItem(KEY);
    vi.restoreAllMocks();
  });

  it('is not suppressed before the user ever opts out', () => {
    expect(isClarifyReminderSuppressed()).toBe(false);
  });

  it('is suppressed after suppressClarifyReminder', () => {
    suppressClarifyReminder();

    expect(isClarifyReminderSuppressed()).toBe(true);
    expect(window.localStorage.getItem(KEY)).toBe('suppressed');
  });

  it('falls back to reminding when the stored value is not a known option', () => {
    window.localStorage.setItem(KEY, 'bogus');

    expect(isClarifyReminderSuppressed()).toBe(false);
  });

  it('treats the explicit remind value as not suppressed', () => {
    window.localStorage.setItem(KEY, 'remind');

    expect(isClarifyReminderSuppressed()).toBe(false);
  });

  it('degrades to reminding when reading throws, instead of breaking the detail page', () => {
    vi.spyOn(window.localStorage, 'getItem').mockImplementation(() => {
      throw new Error('denied');
    });

    expect(isClarifyReminderSuppressed()).toBe(false);
  });

  it('swallows write failures silently', () => {
    vi.spyOn(window.localStorage, 'setItem').mockImplementation(() => {
      throw new Error('denied');
    });

    expect(() => suppressClarifyReminder()).not.toThrow();
  });
});
