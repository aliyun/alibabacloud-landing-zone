import { i18n } from '@/shared/i18n';
import { describe, expect, it } from 'vitest';
import type { SquadOption } from '@/features/squad/squadGrouping';
import type { SdlcId, SdlcTemplate } from './api';
import { buildSdlcGroupViews, compareSdlc } from './squadView';

function sdlc(
  id: SdlcId,
  status: SdlcTemplate['status'] = 'ENABLED',
  squadIds: number[] | null = [],
): SdlcTemplate {
  return {
    id,
    name: `sdlc-${id}`,
    description: '',
    workType: null,
    status,
    isDefault: 0,
    entryStepId: null,
    version: 1,
    gmtCreate: '2026-10-28',
    steps: [],
    squadIds,
  };
}

const squadOptions: SquadOption[] = [
  { value: 7, label: 'Squad A' },
  { value: 8, label: 'Squad B' },
];
const nameById = new Map([
  [7, 'Squad A'],
  [8, 'Squad B'],
]);

function build(
  items: SdlcTemplate[],
  selectedSquadIds: number[] = [],
  options: SquadOption[] = squadOptions,
  names: ReadonlyMap<number, string> = nameById,
) {
  return buildSdlcGroupViews({ items, squadOptions: options, nameById: names, selectedSquadIds });
}

describe('buildSdlcGroupViews', () => {
  it('orders groups by size desc, then by name, and keeps 未分组 last', () => {
    const views = build([
      sdlc(1, 'ENABLED', [7]),
      sdlc(2, 'ENABLED', [8]),
      sdlc(3, 'ENABLED', [8]),
      sdlc(4, 'ENABLED', []),
    ]);

    expect(views.map((view) => view.squadId)).toEqual([8, 7, null]);
    expect(views[0].items).toHaveLength(2);
    expect(views[2].key).toBe('squad-unsquaded');
  });

  it('breaks size ties by squad name and puts empty squads behind squads with workflows', () => {
    const views = build(
      [sdlc(1, 'ENABLED', [8]), sdlc(2, 'ENABLED', [7])],
      [],
      [...squadOptions, { value: 9, label: 'Squad C' }],
      new Map([...nameById, [9, 'Squad C']]),
    );

    expect(views.map((view) => view.label)).toEqual(['Squad A', 'Squad B', 'Squad C']);
    expect(views[2].items).toEqual([]);
  });

  it('shows a workflow in every owning squad group', () => {
    const shared = sdlc(1, 'ENABLED', [7, 8]);
    const views = build([shared]);

    expect(views).toHaveLength(2);
    expect(views[0].items.map((item) => item.id)).toEqual([1]);
    expect(views[1].items.map((item) => item.id)).toEqual([1]);
  });

  it('omits the ungrouped bucket when every workflow belongs to a squad', () => {
    const views = build([sdlc(1, 'ENABLED', [7])]);

    expect(views.some((view) => view.squadId === null)).toBe(false);
  });

  it('scopes groups to the selected squads and drops the ungrouped bucket while filtering', () => {
    const views = build(
      [sdlc(1, 'ENABLED', [7, 8]), sdlc(2, 'ENABLED', [8]), sdlc(3, 'ENABLED', [])],
      [7],
    );

    expect(views.map((view) => view.squadId)).toEqual([7]);
    expect(views[0].items.map((item) => item.id)).toEqual([1]);
  });

  it('keeps a selected squad without workflows as an empty group', () => {
    const views = build([sdlc(1, 'ENABLED', [7])], [9]);

    expect(views.map((view) => view.squadId)).toEqual([9]);
    expect(views[0].items).toEqual([]);
    expect(views[0].label).toBe(i18n.t('squadFallback', { ns: 'squad', id: 9 }));
  });

  it('sorts enabled before draft before disabled, newest id first within a status', () => {
    const views = build([
      sdlc(1, 'DISABLED', [7]),
      sdlc(2, 'DRAFT', [7]),
      sdlc(10, 'ENABLED', [7]),
      sdlc(9, 'ENABLED', [7]),
    ]);

    expect(views[0].items.map((item) => item.id)).toEqual([10, 9, 2, 1]);
  });

  it('sorts workflows in the ungrouped bucket with the same rules', () => {
    const views = build([
      sdlc(1, 'DRAFT', null),
      sdlc(2, 'ENABLED', []),
    ], [], [], new Map());

    expect(views).toHaveLength(1);
    expect(views[0].squadId).toBeNull();
    expect(views[0].items.map((item) => item.id)).toEqual([2, 1]);
  });
});

describe('compareSdlc', () => {
  it('orders snowflake ids by length instead of Number precision', () => {
    expect(compareSdlc(sdlc('334208147726012416'), sdlc('334208147726012415'))).toBeLessThan(0);
    expect(compareSdlc(sdlc('10'), sdlc('9'))).toBeLessThan(0);
  });

  it('orders non numeric ids as strings, descending', () => {
    expect(compareSdlc(sdlc('b'), sdlc('a'))).toBeLessThan(0);
    expect(compareSdlc(sdlc('a'), sdlc('a'))).toBe(0);
  });

  it('puts unknown statuses after every known status', () => {
    const unknown = { ...sdlc(1, 'ENABLED'), status: 'constructor' as SdlcTemplate['status'] };
    expect(compareSdlc(sdlc(2, 'DISABLED'), unknown)).toBeLessThan(0);
  });
});