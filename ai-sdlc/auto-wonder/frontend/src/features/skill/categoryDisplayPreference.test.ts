import { afterEach, describe, expect, it } from 'vitest';
import {
  isCategoryGroupingEnabled, setCategoryGroupingEnabled,
} from './categoryDisplayPreference';

const KEY_USER_10002_7 = 'autowonder.skills.groupByCategory.10002.7';
const KEY_USER_10002_8 = 'autowonder.skills.groupByCategory.10002.8';
const KEY_GUEST = 'autowonder.skills.groupByCategory.none.none';
const KEY_WORKSPACE_ONLY = 'autowonder.skills.groupByCategory.10002.none';

afterEach(() => {
  window.localStorage.clear();
});

describe('isCategoryGroupingEnabled', () => {
  it('defaults to off when nothing is stored', () => {
    expect(isCategoryGroupingEnabled(10002, 7)).toBe(false);
  });

  it('reads the value scoped by both workspace and user', () => {
    window.localStorage.setItem(KEY_USER_10002_7, 'on');

    expect(isCategoryGroupingEnabled(10002, 7)).toBe(true);
    // 换用户或换项目都读不到对方的偏好
    expect(isCategoryGroupingEnabled(10002, 8)).toBe(false);
    expect(isCategoryGroupingEnabled(10003, 7)).toBe(false);
  });

  it('falls back to the none-scoped key when workspace or user is unknown', () => {
    window.localStorage.setItem(KEY_GUEST, 'on');

    expect(isCategoryGroupingEnabled(null, null)).toBe(true);
    expect(isCategoryGroupingEnabled(undefined, undefined)).toBe(true);
    expect(isCategoryGroupingEnabled(10002, null)).toBe(false);
  });

  it('uses the none placeholder only for the missing dimension', () => {
    window.localStorage.setItem(KEY_WORKSPACE_ONLY, 'on');

    expect(isCategoryGroupingEnabled(10002, null)).toBe(true);
    expect(isCategoryGroupingEnabled(10002, 7)).toBe(false);
  });

  it('ignores stored values outside the on/off whitelist', () => {
    window.localStorage.setItem(KEY_USER_10002_7, 'bogus');

    expect(isCategoryGroupingEnabled(10002, 7)).toBe(false);
  });
});

describe('setCategoryGroupingEnabled', () => {
  it('persists on and off for the given workspace and user', () => {
    setCategoryGroupingEnabled(10002, 7, true);
    expect(window.localStorage.getItem(KEY_USER_10002_7)).toBe('on');

    setCategoryGroupingEnabled(10002, 7, false);
    expect(window.localStorage.getItem(KEY_USER_10002_7)).toBe('off');
    expect(isCategoryGroupingEnabled(10002, 7)).toBe(false);
  });

  it('round-trips through the reader without leaking into other scopes', () => {
    setCategoryGroupingEnabled(10002, 7, true);

    expect(isCategoryGroupingEnabled(10002, 7)).toBe(true);
    expect(isCategoryGroupingEnabled(10002, 8)).toBe(false);
    expect(isCategoryGroupingEnabled(10003, 7)).toBe(false);
    expect(window.localStorage.getItem(KEY_USER_10002_8)).toBeNull();
  });

  it('writes to the none placeholder when identifiers are missing', () => {
    setCategoryGroupingEnabled(null, undefined, true);

    expect(window.localStorage.getItem(KEY_GUEST)).toBe('on');
    expect(isCategoryGroupingEnabled(null, undefined)).toBe(true);
  });
});
