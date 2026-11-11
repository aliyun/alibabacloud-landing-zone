import { describe, expect, it } from 'vitest';
import dayjs from 'dayjs';
import {
  mergeWorkitemListFilters,
  parseWorkitemListSearchParams,
  toWorkitemListSearchParams,
  WORKITEM_LIST_URL_PARAM,
  type WorkitemListUrlFilters,
} from './listUrlState';
import { TAG_CONDITION_LIMIT } from './tagConditionFilter';

function emptyAdvancedFilter(): WorkitemListUrlFilters['advancedFilter'] {
  return {
    created: { from: null, to: null },
    creatorIds: [],
    tagConditions: [],
    squadIds: [],
    unassignedSquad: false,
  };
}

function filters(overrides: Partial<WorkitemListUrlFilters> = {}): WorkitemListUrlFilters {
  return { scope: 'ALL', advancedFilter: emptyAdvancedFilter(), ...overrides };
}

describe('parseWorkitemListSearchParams', () => {
  it('returns defaults and the fallback scope for an empty query', () => {
    const parsed = parseWorkitemListSearchParams(new URLSearchParams());
    expect(parsed).toEqual(filters());
    expect(parseWorkitemListSearchParams(new URLSearchParams(), 'WATCHED').scope).toBe('WATCHED');
  });

  it('reads every supported filter from the query', () => {
    const parsed = parseWorkitemListSearchParams(new URLSearchParams({
      scope: 'COMMENTED',
      workType: 'BUG',
      statusCategory: 'CANCELED',
      keyword: '登录 失败',
      tag: 'backend',
      createdFrom: '2026-11-01',
      createdTo: '2026-11-03',
      creatorIds: '7,9,7',
      tagConditions: JSON.stringify([{ op: 'OR', tags: ['a', 'b'] }, { op: 'NOT', tags: ['c'] }]),
      unassignedSquad: 'true',
    }));

    expect(parsed.scope).toBe('COMMENTED');
    expect(parsed.workType).toBe('BUG');
    expect(parsed.statusCategory).toBe('CANCELED');
    expect(parsed.keyword).toBe('登录 失败');
    expect(parsed.tag).toBe('backend');
    expect(parsed.advancedFilter.created.from?.format('YYYY-MM-DD')).toBe('2026-11-01');
    expect(parsed.advancedFilter.created.to?.format('YYYY-MM-DD')).toBe('2026-11-03');
    expect(parsed.advancedFilter.creatorIds).toEqual([7, 9]);
    expect(parsed.advancedFilter.tagConditions).toEqual([
      { rowId: 1, op: 'OR', tags: ['a', 'b'] },
      { rowId: 2, op: 'NOT', tags: ['c'] },
    ]);
    expect(parsed.advancedFilter.unassignedSquad).toBe(true);
    expect(parsed.advancedFilter.squadIds).toEqual([]);
  });

  it('drops unsupported enum values, malformed dates and broken payloads', () => {
    const parsed = parseWorkitemListSearchParams(new URLSearchParams({
      scope: 'SOMETHING',
      workType: 'FOO',
      statusCategory: 'BROKEN',
      createdFrom: '2026-02-30',
      createdTo: 'not-a-date',
      creatorIds: '8,abc,-1,0,8,3.5',
      tagConditions: '{not json',
      squadIds: 'x,4,4',
    }), 'PENDING');

    expect(parsed.scope).toBe('PENDING');
    expect(parsed.workType).toBeUndefined();
    expect(parsed.statusCategory).toBeUndefined();
    expect(parsed.advancedFilter.created.from).toBeNull();
    expect(parsed.advancedFilter.created.to).toBeNull();
    expect(parsed.advancedFilter.creatorIds).toEqual([8]);
    expect(parsed.advancedFilter.tagConditions).toEqual([]);
    expect(parsed.advancedFilter.squadIds).toEqual([4]);
  });

  it('drops an over-long tag condition payload that would bypass the GET length guard', () => {
    const tooLong = JSON.stringify([{ op: 'AND', tags: ['x'.repeat(4100)] }]);
    expect(parseWorkitemListSearchParams(new URLSearchParams({
      tagConditions: tooLong,
    })).advancedFilter.tagConditions).toEqual([]);
  });

  it('keeps only well-formed tag conditions and caps the list', () => {
    expect(parseWorkitemListSearchParams(new URLSearchParams({
      tagConditions: '{}',
    })).advancedFilter.tagConditions).toEqual([]);

    const parsed = parseWorkitemListSearchParams(new URLSearchParams({
      tagConditions: JSON.stringify([
        { op: 'XOR', tags: ['a'] },
        { op: 'AND', tags: [] },
        { op: 'AND' },
        { op: 'AND', tags: [1, '  ', 'ok'] },
        'nope',
        { op: 'OR', tags: ['fine'] },
      ]),
    }));
    expect(parsed.advancedFilter.tagConditions).toEqual([
      { rowId: 1, op: 'AND', tags: ['ok'] },
      { rowId: 2, op: 'OR', tags: ['fine'] },
    ]);

    const tooMany = Array.from({ length: TAG_CONDITION_LIMIT + 3 }, () => ({ op: 'AND', tags: ['t'] }));
    const capped = parseWorkitemListSearchParams(new URLSearchParams({
      tagConditions: JSON.stringify(tooMany),
    }));
    expect(capped.advancedFilter.tagConditions).toHaveLength(TAG_CONDITION_LIMIT);
  });

  it('ignores blank keyword and tag values', () => {
    const parsed = parseWorkitemListSearchParams(new URLSearchParams({ keyword: '', tag: '' }));
    expect(parsed.keyword).toBeUndefined();
    expect(parsed.tag).toBeUndefined();
  });

  it('accepts a single-sided creation time bound', () => {
    const onlyFrom = parseWorkitemListSearchParams(new URLSearchParams({ createdFrom: '2026-10-01' }));
    expect(onlyFrom.advancedFilter.created.from?.format('YYYY-MM-DD')).toBe('2026-10-01');
    expect(onlyFrom.advancedFilter.created.to).toBeNull();

    const onlyTo = parseWorkitemListSearchParams(new URLSearchParams({ createdTo: '2026-10-10' }));
    expect(onlyTo.advancedFilter.created.from).toBeNull();
    expect(onlyTo.advancedFilter.created.to?.format('YYYY-MM-DD')).toBe('2026-10-10');
  });
});

describe('toWorkitemListSearchParams', () => {
  it('serializes the whole filter set under the server parameter names', () => {
    const params = toWorkitemListSearchParams(filters({
      scope: 'WATCHED',
      workType: 'REQ',
      statusCategory: 'IN_PROGRESS',
      keyword: '巡检',
      tag: 'backend',
      advancedFilter: {
        created: { from: dayjs('2026-11-01'), to: dayjs('2026-11-03') },
        creatorIds: [3, 5],
        tagConditions: [{ rowId: 1, op: 'AND', tags: ['a'] }],
        squadIds: [11],
        unassignedSquad: false,
      },
    }));

    expect(params.get(WORKITEM_LIST_URL_PARAM.scope)).toBe('WATCHED');
    expect(params.get(WORKITEM_LIST_URL_PARAM.workType)).toBe('REQ');
    expect(params.get(WORKITEM_LIST_URL_PARAM.statusCategory)).toBe('IN_PROGRESS');
    expect(params.get(WORKITEM_LIST_URL_PARAM.keyword)).toBe('巡检');
    expect(params.get(WORKITEM_LIST_URL_PARAM.tag)).toBe('backend');
    expect(params.get(WORKITEM_LIST_URL_PARAM.createdFrom)).toBe('2026-11-01');
    expect(params.get(WORKITEM_LIST_URL_PARAM.createdTo)).toBe('2026-11-03');
    expect(params.get(WORKITEM_LIST_URL_PARAM.creatorIds)).toBe('3,5');
    expect(JSON.parse(params.get(WORKITEM_LIST_URL_PARAM.tagConditions) ?? '[]')).toEqual([{ op: 'AND', tags: ['a'] }]);
    expect(params.get(WORKITEM_LIST_URL_PARAM.squadIds)).toBe('11');
    expect(params.has(WORKITEM_LIST_URL_PARAM.unassignedSquad)).toBe(false);
  });

  it('always keeps the scope and omits empty conditions', () => {
    expect(toWorkitemListSearchParams(filters()).toString()).toBe('scope=ALL');
  });

  it('prefers the unassigned squad flag over explicit squad ids', () => {
    const params = toWorkitemListSearchParams(filters({
      advancedFilter: { ...emptyAdvancedFilter(), squadIds: [1, 2], unassignedSquad: true },
    }));
    expect(params.get(WORKITEM_LIST_URL_PARAM.unassignedSquad)).toBe('true');
    expect(params.has(WORKITEM_LIST_URL_PARAM.squadIds)).toBe(false);
  });

  it('round-trips through the query string', () => {
    const source = filters({
      scope: 'ASSIGNED',
      workType: 'TASK',
      statusCategory: 'DONE',
      keyword: '<raw 用户>',
      tag: '前端',
      advancedFilter: {
        created: { from: dayjs('2026-10-01'), to: dayjs('2026-10-10') },
        creatorIds: [12],
        tagConditions: [{ rowId: 1, op: 'IN', tags: ['x', 'y'] }],
        squadIds: [],
        unassignedSquad: true,
      },
    });
    const parsed = parseWorkitemListSearchParams(
      new URLSearchParams(toWorkitemListSearchParams(source).toString()),
    );
    expect(parsed.scope).toBe('ASSIGNED');
    expect(parsed.workType).toBe('TASK');
    expect(parsed.statusCategory).toBe('DONE');
    expect(parsed.keyword).toBe('<raw 用户>');
    expect(parsed.tag).toBe('前端');
    expect(parsed.advancedFilter.created.from?.format('YYYY-MM-DD')).toBe('2026-10-01');
    expect(parsed.advancedFilter.created.to?.format('YYYY-MM-DD')).toBe('2026-10-10');
    expect(parsed.advancedFilter.creatorIds).toEqual([12]);
    expect(parsed.advancedFilter.tagConditions).toEqual([{ rowId: 1, op: 'IN', tags: ['x', 'y'] }]);
    expect(parsed.advancedFilter.unassignedSquad).toBe(true);
    expect(parsed.advancedFilter.squadIds).toEqual([]);
  });
});

describe('mergeWorkitemListFilters', () => {
  it('keeps untouched conditions and clears only explicitly undefined ones', () => {
    const current = filters({
      scope: 'WATCHED',
      workType: 'BUG',
      statusCategory: 'DONE',
      keyword: '巡检',
      tag: 'backend',
    });

    expect(mergeWorkitemListFilters(current, {})).toEqual(current);

    const cleared = mergeWorkitemListFilters(current, { keyword: undefined, statusCategory: undefined });
    expect(cleared.scope).toBe('WATCHED');
    expect(cleared.workType).toBe('BUG');
    expect(cleared.tag).toBe('backend');
    expect(cleared.keyword).toBeUndefined();
    expect(cleared.statusCategory).toBeUndefined();
  });

  it('replaces scope and advanced filter without mutating the current conditions', () => {
    const current = filters({ scope: 'ALL' });
    const nextAdvanced = { ...emptyAdvancedFilter(), creatorIds: [7] };

    const merged = mergeWorkitemListFilters(current, { scope: 'WATCHED', advancedFilter: nextAdvanced });

    expect(merged.scope).toBe('WATCHED');
    expect(merged.advancedFilter).toBe(nextAdvanced);
    expect(current.scope).toBe('ALL');
    expect(current.advancedFilter.creatorIds).toEqual([]);
  });
});
