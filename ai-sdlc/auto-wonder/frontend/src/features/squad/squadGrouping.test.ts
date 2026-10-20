import { i18n } from '@/shared/i18n';
import { describe, it, expect } from 'vitest';
import {
  UNSQUADED_KEY,
  getUnsquadedLabel,
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
    expect(groups[0].label).toBe(getUnsquadedLabel());
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

    expect(groups.map((group) => group.label)).toEqual(['A 队', 'B 队', getUnsquadedLabel()]);
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


it('uses the current locale without changing keys, memberships or authored names', async () => {
  const rows = [{ id: 1 }, { id: 2, squadIds: [42] }, { id: 3, squadIds: [7] }];
  await i18n.changeLanguage('zh-CN');
  const zh = groupBySquad(rows, readSquadIds, new Map([[7, '<b>自定义小队</b>']]));
  await i18n.changeLanguage('en-US');
  const en = groupBySquad(rows, readSquadIds, new Map([[7, '<b>自定义小队</b>']]));
  expect(en.find(group => group.key === 'squad-unsquaded')?.label).toBe('Ungrouped');
  expect(en.find(group => group.key === 'squad-42')?.label).toBe('Squad #42');
  expect(en.find(group => group.key === 'squad-7')?.label).toBe('<b>自定义小队</b>');
  expect(en.map(({ key, items }) => [key, items])).toEqual(zh.map(({ key, items }) => [key, items]));
  await i18n.changeLanguage('zh-CN');
});
