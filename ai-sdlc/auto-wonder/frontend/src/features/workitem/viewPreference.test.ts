import { afterEach, describe, expect, it, vi } from 'vitest';
import { readWorkitemViewPreference, writeWorkitemViewPreference } from './viewPreference';

describe('workitem view preference', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    window.localStorage.clear();
  });

  it('defaults to kanban when nothing is stored', () => {
    expect(readWorkitemViewPreference()).toBe('kanban');
  });

  it('remembers the latest choice for both views', () => {
    writeWorkitemViewPreference('table');
    expect(window.localStorage.getItem('autowonder.workitems.view')).toBe('table');
    expect(readWorkitemViewPreference()).toBe('table');

    writeWorkitemViewPreference('kanban');
    expect(window.localStorage.getItem('autowonder.workitems.view')).toBe('kanban');
    expect(readWorkitemViewPreference()).toBe('kanban');
  });

  it('falls back to kanban for invalid stored values', () => {
    window.localStorage.setItem('autowonder.workitems.view', 'gallery');
    expect(readWorkitemViewPreference()).toBe('kanban');
    window.localStorage.setItem('autowonder.workitems.view', '');
    expect(readWorkitemViewPreference()).toBe('kanban');
  });

  it('tolerates unavailable storage on both read and write', () => {
    const getItem = vi.spyOn(Storage.prototype, 'getItem')
      .mockImplementation(() => { throw new Error('storage blocked'); });
    expect(readWorkitemViewPreference()).toBe('kanban');
    expect(getItem).toHaveBeenCalledWith('autowonder.workitems.view');
    getItem.mockRestore();

    const setItem = vi.spyOn(Storage.prototype, 'setItem')
      .mockImplementation(() => { throw new Error('storage blocked'); });
    expect(() => writeWorkitemViewPreference('table')).not.toThrow();
    expect(setItem).toHaveBeenCalledWith('autowonder.workitems.view', 'table');
    setItem.mockRestore();
    expect(window.localStorage.getItem('autowonder.workitems.view')).toBeNull();
  });
});
