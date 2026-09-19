import { describe, it, expect } from 'vitest';
import {
  UNSQUADED_KEY,
  UNSQUADED_LABEL,
  buildSquadNameById,
  groupBySquad,
} from './squadGrouping';

interface Row {
  id: number;
  squadIds?: number[] | null;
}

const readSquadIds = (row: Row) => row.squadIds;

describe('buildSquadNameById', () => {
  it('indexes squads by id', () => {
    const nameById = buildSquadNameById([
      { value: 7, label: '前端小队' },
      { value: 8, label: '测试小队' },
    ]);

    expect(nameById.get(7)).toBe('前端小队');
    expect(nameById.get(8)).toBe('测试小队');
    expect(nameById.size).toBe(2);
  });

  it('skips entries without a usable id or label', () => {
    const nameById = buildSquadNameById([
      { value: 7, label: '' },
      { value: null as unknown as number, label: '无 id' },
      { value: 9, label: '有效' },
    ]);

    expect([...nameById.keys()]).toEqual([9]);
  });
});

describe('groupBySquad', () => {
  it('puts resources without a squad into the ungrouped bucket', () => {
    const groups = groupBySquad(
      [{ id: 1, squadIds: [] }, { id: 2, squadIds: null }, { id: 3 }],
      readSquadIds,
      new Map(),
    );

    expect(groups).toHaveLength(1);
    expect(groups[0].key).toBe(UNSQUADED_KEY);
    expect(groups[0].label).toBe(UNSQUADED_LABEL);
    expect(groups[0].squadId).toBeNull();
    expect(groups[0].items.map((row) => row.id)).toEqual([1, 2, 3]);
  });

  it('shows a resource under every squad it belongs to', () => {
    const groups = groupBySquad(
      [{ id: 1, squadIds: [7, 8] }],
      readSquadIds,
      buildSquadNameById([{ value: 7, label: 'Beta' }, { value: 8, label: 'Alpha' }]),
    );

    expect(groups.map((group) => group.label)).toEqual(['Alpha', 'Beta']);
    expect(groups.every((group) => group.items.length === 1)).toBe(true);
  });

  it('counts a resource once per squad even when membership rows repeat', () => {
    const groups = groupBySquad(
      [{ id: 1, squadIds: [7, 7, 7] }],
      readSquadIds,
      buildSquadNameById([{ value: 7, label: '前端小队' }]),
    );

    expect(groups).toHaveLength(1);
    expect(groups[0].items.map((row) => row.id)).toEqual([1]);
  });

  it('keeps a squad whose name could not be resolved instead of hiding it', () => {
    const groups = groupBySquad([{ id: 1, squadIds: [42] }], readSquadIds, new Map());

    expect(groups[0].label).toBe('小队 #42');
    expect(groups[0].squadId).toBe(42);
    expect(groups[0].key).toBe('squad-42');
  });

  it('sorts the ungrouped bucket last so real squads stay on top', () => {
    const groups = groupBySquad(
      [{ id: 1 }, { id: 2, squadIds: [8] }, { id: 3, squadIds: [7] }],
      readSquadIds,
      buildSquadNameById([{ value: 7, label: 'B 队' }, { value: 8, label: 'A 队' }]),
    );

    expect(groups.map((group) => group.label)).toEqual(['A 队', 'B 队', UNSQUADED_LABEL]);
    expect(groups[2].items.map((row) => row.id)).toEqual([1]);
  });

  it('does not mutate the input rows', () => {
    const rows: Row[] = [{ id: 1, squadIds: [7, 7] }];
    groupBySquad(rows, readSquadIds, new Map());

    expect(rows[0].squadIds).toEqual([7, 7]);
  });

  it('returns no groups for an empty list', () => {
    expect(groupBySquad([], readSquadIds, new Map())).toEqual([]);
  });
});
