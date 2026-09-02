import { describe, expect, it } from 'vitest';
import { classifyStatus, classifyWorkitemStatus } from './constants';

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
