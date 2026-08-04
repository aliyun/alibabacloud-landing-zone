import { describe, it, expect } from 'vitest';
import {
  groupPendingDecisionsByAssignee,
  isMyPendingDecision,
  isPendingDecision,
} from './decisionGrouping';
import type { Workitem } from '@/shared/types/workitem';

function mk(partial: Partial<Workitem>): Workitem {
  return {
    id: 1,
    workType: 'REQ',
    title: 't',
    contentMd: '',
    templateId: null,
    statusNodeId: null,
    statusName: '开发中',
    sdlcId: null,
    sdlcName: null,
    assigneeType: 'HUMAN',
    assigneeRef: null,
    assigneeName: null,
    assigneeDisplayName: null,
    creatorId: null,
    creatorName: null,
    creatorDisplayName: null,
    priority: 3,
    version: 1,
    gmtCreate: '',
    gmtModified: '',
    ...partial,
  } as Workitem;
}

describe('groupPendingDecisionsByAssignee', () => {
  it('groups only pending-decision workitems by assignee identity', () => {
    const items = [
      mk({ id: 1, pendingDecision: true, assigneeType: 'HUMAN', assigneeRef: 10, assigneeDisplayName: '张三' }),
      mk({ id: 2, pendingDecision: true, assigneeType: 'HUMAN', assigneeRef: 10, assigneeName: '张三' }),
      mk({ id: 3, pendingDecision: true, assigneeType: 'HUMAN', assigneeRef: 20, assigneeDisplayName: '李四' }),
      mk({ id: 4, pendingDecision: false, assigneeType: 'HUMAN', assigneeRef: 10, assigneeDisplayName: '张三' }),
    ];
    const groups = groupPendingDecisionsByAssignee(items);
    expect(groups).toHaveLength(2);
    // 数量多的组在前
    expect(groups[0].label).toBe('张三');
    expect(groups[0].items).toHaveLength(2);
    expect(groups[1].label).toBe('李四');
    expect(groups[1].items).toHaveLength(1);
  });

  it('puts unassigned pending decisions into 未指派 group', () => {
    const items = [
      mk({ id: 1, pendingDecision: true, assigneeType: 'HUMAN', assigneeRef: null, assigneeName: null, assigneeDisplayName: null }),
    ];
    const groups = groupPendingDecisionsByAssignee(items);
    expect(groups).toHaveLength(1);
    expect(groups[0].label).toBe('未指派');
  });

  it('keeps distinct assignees with the same display name in separate groups', () => {
    const items = [
      mk({ id: 1, pendingDecision: true, assigneeType: 'HUMAN', assigneeRef: 10, assigneeDisplayName: '同名' }),
      mk({ id: 2, pendingDecision: true, assigneeType: 'HUMAN', assigneeRef: 20, assigneeDisplayName: '同名' }),
    ];
    const groups = groupPendingDecisionsByAssignee(items);
    expect(groups).toHaveLength(2);
  });

  it('returns empty array when no pending decisions', () => {
    expect(groupPendingDecisionsByAssignee([mk({ pendingDecision: false })])).toEqual([]);
    expect(groupPendingDecisionsByAssignee([])).toEqual([]);
  });
});

describe('isPendingDecision', () => {
  it('true when backend marker set', () => {
    expect(isPendingDecision(mk({ pendingDecision: true, statusName: '开发中' }))).toBe(true);
  });
  it('false when not pending and not decision-named status', () => {
    expect(isPendingDecision(mk({ pendingDecision: false, statusName: '开发中' }))).toBe(false);
  });
});

describe('isMyPendingDecision', () => {
  it('matches current user human assignment', () => {
    const item = mk({ pendingDecision: true, assigneeType: 'HUMAN', assigneeRef: 42 });
    expect(isMyPendingDecision(item, 42)).toBe(true);
  });
  it('rejects other assignee', () => {
    const item = mk({ pendingDecision: true, assigneeType: 'HUMAN', assigneeRef: 42 });
    expect(isMyPendingDecision(item, 7)).toBe(false);
  });
  it('rejects agent assignment even if ref matches', () => {
    const item = mk({ pendingDecision: true, assigneeType: 'AGENT', assigneeRef: 42 });
    expect(isMyPendingDecision(item, 42)).toBe(false);
  });
  it('rejects non-pending', () => {
    const item = mk({ pendingDecision: false, assigneeType: 'HUMAN', assigneeRef: 42 });
    expect(isMyPendingDecision(item, 42)).toBe(false);
  });
  it('returns false when userId is null/undefined', () => {
    const item = mk({ pendingDecision: true, assigneeType: 'HUMAN', assigneeRef: 42 });
    expect(isMyPendingDecision(item, null)).toBe(false);
    expect(isMyPendingDecision(item, undefined)).toBe(false);
  });
});
