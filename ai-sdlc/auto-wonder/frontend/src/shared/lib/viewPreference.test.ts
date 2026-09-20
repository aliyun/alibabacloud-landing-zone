import { afterEach, describe, it, expect, vi } from 'vitest';
import { readViewPreference, writeViewPreference } from './viewPreference';

const KEY = 'autowonder.test.view';
const ALLOWED = ['list', 'grouped'] as const;

describe('readViewPreference', () => {
  afterEach(() => {
    window.localStorage.removeItem(KEY);
    vi.restoreAllMocks();
  });

  it('returns the stored value when it is in the allowed list', () => {
    window.localStorage.setItem(KEY, 'list');

    expect(readViewPreference(KEY, ALLOWED, 'grouped')).toBe('list');
  });

  it('falls back when nothing is stored', () => {
    expect(readViewPreference(KEY, ALLOWED, 'grouped')).toBe('grouped');
  });

  it('falls back when the stored value is not in the allowed list', () => {
    window.localStorage.setItem(KEY, 'bogus');

    expect(readViewPreference(KEY, ALLOWED, 'grouped')).toBe('grouped');
  });

  it('falls back silently when localStorage read throws', () => {
    vi.spyOn(window.localStorage, 'getItem').mockImplementation(() => {
      throw new Error('denied');
    });

    expect(readViewPreference(KEY, ALLOWED, 'grouped')).toBe('grouped');
  });
});

describe('writeViewPreference', () => {
  afterEach(() => {
    window.localStorage.removeItem(KEY);
    vi.restoreAllMocks();
  });

  it('writes the value to localStorage', () => {
    writeViewPreference(KEY, 'list');

    expect(window.localStorage.getItem(KEY)).toBe('list');
  });

  it('swallows localStorage write failures silently', () => {
    vi.spyOn(window.localStorage, 'setItem').mockImplementation(() => {
      throw new Error('denied');
    });

    expect(() => writeViewPreference(KEY, 'list')).not.toThrow();
  });
});
