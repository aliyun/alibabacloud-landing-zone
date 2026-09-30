import { describe, expect, it } from 'vitest';
import {
  STATUS_COLUMNS,
  statusCategoryOf,
  getPriorityMeta,
  priorityMap,
  UNKNOWN_PRIORITY,
  type WorkitemStatusInput,
} from './constants';

describe('priority display mapping', () => {
  it('maps stored values 0-3 to Chinese labels with distinguishable colors', () => {
    expect(priorityMap[0]).toEqual({ color: 'red', label: '紧急' });
    expect(priorityMap[1]).toEqual({ color: 'orange', label: '高' });
    expect(priorityMap[2]).toEqual({ color: 'blue', label: '中' });
    expect(priorityMap[3]).toEqual({ color: 'default', label: '低' });
    expect(new Set([0, 1, 2, 3].map(p => priorityMap[p].color)).size).toBe(4);
  });

  it('resolves every known value through getPriorityMeta', () => {
    expect(getPriorityMeta(0).label).toBe('紧急');
    expect(getPriorityMeta(1).label).toBe('高');
    expect(getPriorityMeta(2).label).toBe('中');
    expect(getPriorityMeta(3).label).toBe('低');
  });

  it('shows 未知优先级 for unknown values instead of mapping them to low priority', () => {
    expect(getPriorityMeta(4)).toBe(UNKNOWN_PRIORITY);
    expect(getPriorityMeta(-1).label).toBe('未知优先级');
    expect(getPriorityMeta(99).color).toBe(UNKNOWN_PRIORITY.color);
    expect(getPriorityMeta(4).label).not.toBe(priorityMap[3].label);
  });
});

describe('statusCategoryOf', () => {
  it('returns the server-provided category as-is', () => {
    expect(statusCategoryOf({ statusCategory: 'NEW' })).toBe('NEW');
    expect(statusCategoryOf({ statusCategory: 'IN_PROGRESS' })).toBe('IN_PROGRESS');
    expect(statusCategoryOf({ statusCategory: 'PENDING_DECISION' })).toBe('PENDING_DECISION');
    expect(statusCategoryOf({ statusCategory: 'DONE' })).toBe('DONE');
    expect(statusCategoryOf({ statusCategory: 'CANCELED' })).toBe('CANCELED');
  });

  it('falls back to NEW when the server category is missing', () => {
    expect(statusCategoryOf({})).toBe('NEW');
    expect(statusCategoryOf({ statusCategory: null })).toBe('NEW');
    expect(statusCategoryOf({ statusCategory: undefined })).toBe('NEW');
    expect(statusCategoryOf(undefined as WorkitemStatusInput | undefined)).toBe('NEW');
  });

  it('never classifies by status name（规格 3.1：名称仅展示）', () => {
    const namedItem = { statusName: '开发中' } as unknown as WorkitemStatusInput;
    expect(statusCategoryOf(namedItem)).toBe('NEW');
  });
});

describe('STATUS_COLUMNS', () => {
  it('keeps exactly the four visible columns；CANCELED 不设列（规格 3.1 优先级 0）', () => {
    expect(STATUS_COLUMNS.map(col => col.key)).toEqual(
      ['NEW', 'IN_PROGRESS', 'PENDING_DECISION', 'DONE'],
    );
  });
});
