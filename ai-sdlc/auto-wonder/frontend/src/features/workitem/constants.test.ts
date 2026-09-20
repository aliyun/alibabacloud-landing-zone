import { describe, expect, it } from 'vitest';
import { classifyStatus, classifyWorkitemStatus, getPriorityMeta, priorityMap, UNKNOWN_PRIORITY } from './constants';

describe('priority display mapping', () => {
  it('maps stored values 0-3 to Chinese labels with distinguishable colors', () => {
    expect(priorityMap[0]).toEqual({ color: '#ff4d4f', label: '紧急' });
    expect(priorityMap[1]).toEqual({ color: '#fa8c16', label: '高' });
    expect(priorityMap[2]).toEqual({ color: '#1890ff', label: '中' });
    expect(priorityMap[3]).toEqual({ color: '#8c8c8c', label: '低' });
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

describe('workitem status classification', () => {
  it('keeps status-name based classification for non-human assignments', () => {
    expect(classifyStatus('开发中')).toBe('IN_PROGRESS');
    expect(classifyWorkitemStatus({ statusName: '开发中', pendingDecision: false })).toBe('IN_PROGRESS');
  });

  it('classifies backend-marked pending decision workitems before status-name matching', () => {
    expect(classifyWorkitemStatus({ statusName: '开发中', pendingDecision: true })).toBe('PENDING_DECISION');
    expect(classifyWorkitemStatus({ statusName: '验证中', pendingDecision: true })).toBe('PENDING_DECISION');
  });

  it('keeps released workitems done even if stale data marks pending decision', () => {
    expect(classifyWorkitemStatus({ statusName: '已发布', pendingDecision: true })).toBe('DONE');
    expect(classifyWorkitemStatus({ statusName: 'DONE', pendingDecision: true })).toBe('DONE');
    expect(classifyWorkitemStatus({ statusName: 'Fixed', pendingDecision: true })).toBe('DONE');
    expect(classifyWorkitemStatus({ statusName: 'PUBLISHED', pendingDecision: true })).toBe('DONE');
  });

  it('does not infer pending decision without the backend marker', () => {
    expect(classifyWorkitemStatus({ statusName: '待处理', pendingDecision: false })).toBe('NEW');
    expect(classifyWorkitemStatus({ statusName: '已发布', pendingDecision: false })).toBe('DONE');
  });
});
