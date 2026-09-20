export const UNSQUADED_KEY = 'squad-unsquaded';
export const UNSQUADED_LABEL = '未分组';

export interface SquadOption {
  value: number;
  label: string;
}

export interface SquadGroup<T> {
  key: string;
  squadId: number | null;
  label: string;
  items: T[];
}

export function buildSquadNameById(squads: readonly SquadOption[]): Map<number, string> {
  const nameById = new Map<number, string>();
  for (const squad of squads) {
    if (squad.value != null && squad.label) {
      nameById.set(squad.value, squad.label);
    }
  }
  return nameById;
}

/**
 * A resource belonging to several squads shows up once per squad, so no group looks empty;
 * the flat list itself is still deduplicated by the server, so totals are never double counted.
 */
export function groupBySquad<T>(
  items: readonly T[],
  readSquadIds: (item: T) => readonly number[] | null | undefined,
  squadNameById: ReadonlyMap<number, string>,
): SquadGroup<T>[] {
  const groups = new Map<string, SquadGroup<T>>();

  const groupFor = (key: string, squadId: number | null, label: string): SquadGroup<T> => {
    let group = groups.get(key);
    if (!group) {
      group = { key, squadId, label, items: [] };
      groups.set(key, group);
    }
    return group;
  };

  for (const item of items) {
    const squadIds = [...new Set(readSquadIds(item) ?? [])];
    if (squadIds.length === 0) {
      groupFor(UNSQUADED_KEY, null, UNSQUADED_LABEL).items.push(item);
      continue;
    }
    for (const squadId of squadIds) {
      // A squad deleted after the list was fetched has no name; keep the row visible instead of
      // silently reclassifying it as unaffiliated.
      const label = squadNameById.get(squadId) ?? `小队 #${squadId}`;
      groupFor(`squad-${squadId}`, squadId, label).items.push(item);
    }
  }

  return [...groups.values()].sort((left, right) => {
    if (left.key === UNSQUADED_KEY) return 1;
    if (right.key === UNSQUADED_KEY) return -1;
    return left.label.localeCompare(right.label, 'zh-CN');
  });
}
