import { describe, it, expect } from 'vitest';
import { csvParam } from './csvParam';

describe('csvParam', () => {
  it('joins values with commas so Spring binds them to a List', () => {
    expect(csvParam([7, 8])).toBe('7,8');
    expect(csvParam([7])).toBe('7');
    expect(csvParam(['a', 'b'])).toBe('a,b');
  });

  it('omits the parameter entirely when nothing is selected', () => {
    expect(csvParam([])).toBeUndefined();
    expect(csvParam(null)).toBeUndefined();
    expect(csvParam(undefined)).toBeUndefined();
  });
});
