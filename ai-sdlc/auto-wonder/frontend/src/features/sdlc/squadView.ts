import { i18n } from '@/shared/i18n';
import {
  groupBySquad,
  type SquadGroup,
  type SquadOption,
} from '@/features/squad/squadGrouping';
import type { SdlcId, SdlcTemplate } from './api';

export interface SdlcGroupView {
  key: string;
  squadId: number | null;
  label: string;
  items: SdlcTemplate[];
}

const STATUS_WEIGHT = new Map<string, number>([['ENABLED', 0], ['DRAFT', 1], ['DISABLED', 2]]);
const UNKNOWN_STATUS_WEIGHT = 3;

function isNumericId(value: string): boolean {
  return /^\d+$/.test(value);
}

function compareIdDesc(left: SdlcId, right: SdlcId): number {
  const leftId = String(left);
  const rightId = String(right);
  if (leftId === rightId) return 0;
  // Snowflake ids exceed Number.MAX_SAFE_INTEGER, so numeric ids compare by length first.
  if (isNumericId(leftId) && isNumericId(rightId) && leftId.length !== rightId.length) {
    return rightId.length - leftId.length;
  }
  return leftId < rightId ? 1 : -1;
}

/** Enabled first, then draft, then disabled; unknown statuses last; newest id first within a status. */
export function compareSdlc(left: SdlcTemplate, right: SdlcTemplate): number {
  const leftWeight = STATUS_WEIGHT.get(left.status) ?? UNKNOWN_STATUS_WEIGHT;
  const rightWeight = STATUS_WEIGHT.get(right.status) ?? UNKNOWN_STATUS_WEIGHT;
  if (leftWeight !== rightWeight) return leftWeight - rightWeight;
  return compareIdDesc(left.id, right.id);
}

/**
 * Builds the grouped view for the SDLC page on top of the shared `groupBySquad`:
 * filtering scopes the groups to the selected squads, every squad of the workspace gets a group
 * (empty ones included), and the ungrouped bucket always goes last.
 */
export function buildSdlcGroupViews(args: {
  items: readonly SdlcTemplate[];
  squadOptions: readonly SquadOption[];
  nameById: ReadonlyMap<number, string>;
  selectedSquadIds: readonly number[];
}): SdlcGroupView[] {
  const { items, squadOptions, nameById, selectedSquadIds } = args;
  const selected = new Set(selectedSquadIds);

  const squadGroups = new Map<number, SquadGroup<SdlcTemplate>>();
  let ungrouped: SquadGroup<SdlcTemplate> | null = null;
  for (const group of groupBySquad(items, (sdlc) => sdlc.squadIds, nameById)) {
    if (group.squadId == null) {
      if (selected.size === 0) ungrouped = group;
      continue;
    }
    if (selected.size > 0 && !selected.has(group.squadId)) continue;
    squadGroups.set(group.squadId, group);
  }

  const targetSquadIds = selected.size > 0
    ? Array.from(selected)
    : squadOptions.map((option) => option.value);
  for (const squadId of targetSquadIds) {
    if (squadGroups.has(squadId)) continue;
    squadGroups.set(squadId, {
      key: `squad-${squadId}`,
      squadId,
      label: nameById.get(squadId) ?? i18n.t('squadFallback', { ns: 'squad', id: squadId }),
      items: [],
    });
  }

  const views: SdlcGroupView[] = Array.from(squadGroups.values()).map((group) => ({
    key: group.key,
    squadId: group.squadId,
    label: group.label,
    items: [...group.items].sort(compareSdlc),
  }));

  views.sort((left, right) => {
    if (left.items.length !== right.items.length) return right.items.length - left.items.length;
    return left.label.localeCompare(right.label, 'zh-CN');
  });

  if (ungrouped) {
    views.push({
      key: ungrouped.key,
      squadId: null,
      label: ungrouped.label,
      items: [...ungrouped.items].sort(compareSdlc),
    });
  }
  return views;
}