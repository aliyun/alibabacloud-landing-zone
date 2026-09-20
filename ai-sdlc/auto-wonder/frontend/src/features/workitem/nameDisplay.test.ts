import { describe, expect, it } from 'vitest';
import { displayNameWithoutId, stripAssigneeIdSuffix } from './nameDisplay';

describe('stripAssigneeIdSuffix', () => {
  it('strips half-width and full-width id suffixes with optional spaces', () => {
    expect(stripAssigneeIdSuffix('蔡何(10000)')).toBe('蔡何');
    expect(stripAssigneeIdSuffix('蔡何（10000）')).toBe('蔡何');
    expect(stripAssigneeIdSuffix('淘飞 (10018)')).toBe('淘飞');
    expect(stripAssigneeIdSuffix('AW全栈开发(40013) ')).toBe('AW全栈开发');
    expect(stripAssigneeIdSuffix('真人（ 40013 ）')).toBe('真人');
  });

  it('keeps bracket content that is not a pure id suffix', () => {
    expect(stripAssigneeIdSuffix('张三(研发)')).toBe('张三(研发)');
    expect(stripAssigneeIdSuffix('外部协作(前端)')).toBe('外部协作(前端)');
    expect(stripAssigneeIdSuffix('蔡何')).toBe('蔡何');
    expect(stripAssigneeIdSuffix('张三(P0)')).toBe('张三(P0)');
  });

  it('falls back to the original name when stripping leaves nothing', () => {
    expect(stripAssigneeIdSuffix('(10000)')).toBe('(10000)');
    expect(stripAssigneeIdSuffix('（10000）')).toBe('（10000）');
  });
});

describe('displayNameWithoutId', () => {
  it('prefers the display name and strips its id suffix', () => {
    expect(displayNameWithoutId('蔡何(10000)', 'caihe')).toBe('蔡何');
    expect(displayNameWithoutId('AW全栈开发(40013)', null)).toBe('AW全栈开发');
    expect(displayNameWithoutId(undefined, '未带后缀')).toBe('未带后缀');
  });

  it('falls back to the login name and strips it too', () => {
    expect(displayNameWithoutId(null, '淘飞(10018)')).toBe('淘飞');
    expect(displayNameWithoutId('', 'fallback')).toBe('fallback');
  });

  it('returns null when both names are empty', () => {
    expect(displayNameWithoutId(null, null)).toBeNull();
    expect(displayNameWithoutId(undefined, undefined)).toBeNull();
    expect(displayNameWithoutId('', '')).toBeNull();
  });
});
