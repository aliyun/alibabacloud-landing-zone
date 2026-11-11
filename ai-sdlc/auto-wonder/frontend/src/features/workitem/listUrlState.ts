import dayjs from 'dayjs';
import type { Dayjs } from 'dayjs';
import type { WorkitemStatusCategory } from './api';
import { STATUS_FILTER_COLUMNS, workTypeMap } from './constants';
import { EMPTY_CREATED_TIME_RANGE } from './createdTimeFilter';
import type { WorkitemAdvancedFilterState } from './components/WorkitemAdvancedFilter';
import {
  TAG_CONDITION_LIMIT,
  TAG_CONDITION_OPS,
  isTagConditionsTooLong,
  serializeTagConditions,
  type WorkitemTagConditionDraft,
  type WorkitemTagConditionOp,
} from './tagConditionFilter';

/** 列表页顶部归属范围，与后端 pendingDecisionOnly / mineScope 对应。 */
export type WorkitemListScope = 'ALL' | 'PENDING' | 'CREATED' | 'ASSIGNED' | 'WATCHED' | 'COMMENTED';

const WORKITEM_LIST_SCOPES: readonly WorkitemListScope[] = [
  'ALL', 'PENDING', 'CREATED', 'ASSIGNED', 'WATCHED', 'COMMENTED',
];

/**
 * URL 参数名与服务端 /api/workitems 查询参数保持一致，便于对照和手工拼链接。两处例外：
 * - scope 是 UI 侧的范围聚合，后端用 pendingDecisionOnly / mineScope 表达；
 * - 创建时间在 URL 中是页面所选日期（YYYY-MM-DD），请求时由 toCreatedTimeQuery 换算成 ISO 边界。
 */
export const WORKITEM_LIST_URL_PARAM = {
  scope: 'scope',
  workType: 'workType',
  statusCategory: 'statusCategory',
  keyword: 'keyword',
  tag: 'tag',
  createdFrom: 'createdFrom',
  createdTo: 'createdTo',
  creatorIds: 'creatorIds',
  tagConditions: 'tagConditions',
  squadIds: 'squadIds',
  unassignedSquad: 'unassignedSquad',
} as const;

export interface WorkitemListUrlFilters {
  scope: WorkitemListScope;
  workType?: string;
  statusCategory?: WorkitemStatusCategory;
  keyword?: string;
  tag?: string;
  advancedFilter: WorkitemAdvancedFilterState;
}

/** 单次筛选变更；显式传入 undefined 表示清除该条件，未出现的键保持原值。 */
export interface WorkitemListFilterPatch {
  scope?: WorkitemListScope;
  workType?: string;
  statusCategory?: WorkitemStatusCategory;
  keyword?: string;
  tag?: string;
  advancedFilter?: WorkitemAdvancedFilterState;
}

/**
 * 把一次筛选变更合并到当前条件上。筛选 handler 以此为基准同步写地址，
 * 连续操作（例如改范围后立刻清空关键词）不依赖 React Router 尚未提交的地址。
 */
export function mergeWorkitemListFilters(
  current: WorkitemListUrlFilters,
  patch: WorkitemListFilterPatch,
): WorkitemListUrlFilters {
  return {
    scope: patch.scope ?? current.scope,
    workType: 'workType' in patch ? patch.workType : current.workType,
    statusCategory: 'statusCategory' in patch ? patch.statusCategory : current.statusCategory,
    keyword: 'keyword' in patch ? patch.keyword : current.keyword,
    tag: 'tag' in patch ? patch.tag : current.tag,
    advancedFilter: patch.advancedFilter ?? current.advancedFilter,
  };
}

const DATE_ONLY = /^\d{4}-\d{2}-\d{2}$/;
const ID_ONLY = /^\d+$/;

function emptyAdvancedFilter(): WorkitemAdvancedFilterState {
  return {
    created: { ...EMPTY_CREATED_TIME_RANGE },
    creatorIds: [],
    tagConditions: [],
    squadIds: [],
    unassignedSquad: false,
  };
}

function parseText(raw: string | null): string | undefined {
  return raw === null || raw === '' ? undefined : raw;
}

function parseScope(raw: string | null, fallback: WorkitemListScope): WorkitemListScope {
  return WORKITEM_LIST_SCOPES.includes(raw as WorkitemListScope) ? raw as WorkitemListScope : fallback;
}

function parseWorkType(raw: string | null): string | undefined {
  return raw !== null && Object.prototype.hasOwnProperty.call(workTypeMap, raw) ? raw : undefined;
}

function parseStatusCategory(raw: string | null): WorkitemStatusCategory | undefined {
  return STATUS_FILTER_COLUMNS.some((column) => column.key === raw)
    ? raw as WorkitemStatusCategory
    : undefined;
}

/** 逗号分隔的本地 ID 列表：非正整数、重复项静默丢弃。 */
function parseIdList(raw: string | null): number[] {
  if (!raw) {
    return [];
  }
  const ids = new Set<number>();
  raw.split(',').forEach((part) => {
    const value = part.trim();
    if (!ID_ONLY.test(value)) {
      return;
    }
    const id = Number(value);
    if (Number.isSafeInteger(id) && id > 0) {
      ids.add(id);
    }
  });
  return Array.from(ids);
}

/** URL 里的创建时间是页面所选日期，回填成当天起点的草稿，与高级筛选的日期语义一致。 */
function parseDateBound(raw: string | null): Dayjs | null {
  if (!raw || !DATE_ONLY.test(raw)) {
    return null;
  }
  const parsed = dayjs(raw);
  return parsed.isValid() && parsed.format('YYYY-MM-DD') === raw ? parsed.startOf('day') : null;
}

function parseTagConditions(raw: string | null): WorkitemTagConditionDraft[] {
  if (!raw) {
    return [];
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return [];
  }
  if (!Array.isArray(parsed)) {
    return [];
  }
  const conditions: WorkitemTagConditionDraft[] = [];
  for (const item of parsed) {
    if (conditions.length >= TAG_CONDITION_LIMIT) {
      break;
    }
    if (!item || typeof item !== 'object') {
      continue;
    }
    const { op, tags } = item as { op?: unknown; tags?: unknown };
    if (typeof op !== 'string' || !TAG_CONDITION_OPS.includes(op as WorkitemTagConditionOp)) {
      continue;
    }
    if (!Array.isArray(tags)) {
      continue;
    }
    const cleanTags = tags.filter((tag): tag is string => typeof tag === 'string' && tag.trim() !== '');
    if (cleanTags.length === 0) {
      continue;
    }
    conditions.push({ rowId: conditions.length + 1, op: op as WorkitemTagConditionOp, tags: cleanTags });
  }
  // 与高级筛选弹窗共用同一长度护栏，避免超长分享链接绕过 GET 请求上限
  return isTagConditionsTooLong(conditions) ? [] : conditions;
}

function parseAdvancedFilter(params: URLSearchParams): WorkitemAdvancedFilterState {
  const advancedFilter = emptyAdvancedFilter();
  const createdFrom = parseDateBound(params.get(WORKITEM_LIST_URL_PARAM.createdFrom));
  const createdTo = parseDateBound(params.get(WORKITEM_LIST_URL_PARAM.createdTo));
  if (createdFrom) {
    advancedFilter.created.from = createdFrom;
  }
  if (createdTo) {
    advancedFilter.created.to = createdTo;
  }
  advancedFilter.creatorIds = parseIdList(params.get(WORKITEM_LIST_URL_PARAM.creatorIds));
  advancedFilter.tagConditions = parseTagConditions(params.get(WORKITEM_LIST_URL_PARAM.tagConditions));
  if (params.get(WORKITEM_LIST_URL_PARAM.unassignedSquad) === 'true') {
    advancedFilter.unassignedSquad = true;
  } else {
    advancedFilter.squadIds = parseIdList(params.get(WORKITEM_LIST_URL_PARAM.squadIds));
  }
  return advancedFilter;
}

/**
 * 从地址栏解析列表筛选条件。非法枚举、非法日期和损坏的标签条件 JSON 一律静默丢弃，
 * 保证手工改坏的分享链接仍能打开页面并退化为更宽松的查询。
 */
export function parseWorkitemListSearchParams(
  params: URLSearchParams,
  fallbackScope: WorkitemListScope = 'ALL',
): WorkitemListUrlFilters {
  return {
    scope: parseScope(params.get(WORKITEM_LIST_URL_PARAM.scope), fallbackScope),
    workType: parseWorkType(params.get(WORKITEM_LIST_URL_PARAM.workType)),
    statusCategory: parseStatusCategory(params.get(WORKITEM_LIST_URL_PARAM.statusCategory)),
    keyword: parseText(params.get(WORKITEM_LIST_URL_PARAM.keyword)),
    tag: parseText(params.get(WORKITEM_LIST_URL_PARAM.tag)),
    advancedFilter: parseAdvancedFilter(params),
  };
}

/** 固定顺序序列化，空条件不写入；scope 始终保留，让分享链接不受接收方本地偏好影响。 */
export function toWorkitemListSearchParams(filters: WorkitemListUrlFilters): URLSearchParams {
  const params = new URLSearchParams();
  const { scope, workType, statusCategory, keyword, tag, advancedFilter } = filters;
  params.set(WORKITEM_LIST_URL_PARAM.scope, scope);
  if (workType) {
    params.set(WORKITEM_LIST_URL_PARAM.workType, workType);
  }
  if (statusCategory) {
    params.set(WORKITEM_LIST_URL_PARAM.statusCategory, statusCategory);
  }
  if (keyword) {
    params.set(WORKITEM_LIST_URL_PARAM.keyword, keyword);
  }
  if (tag) {
    params.set(WORKITEM_LIST_URL_PARAM.tag, tag);
  }
  if (advancedFilter.created.from) {
    params.set(WORKITEM_LIST_URL_PARAM.createdFrom, advancedFilter.created.from.format('YYYY-MM-DD'));
  }
  if (advancedFilter.created.to) {
    params.set(WORKITEM_LIST_URL_PARAM.createdTo, advancedFilter.created.to.format('YYYY-MM-DD'));
  }
  if (advancedFilter.creatorIds.length > 0) {
    params.set(WORKITEM_LIST_URL_PARAM.creatorIds, advancedFilter.creatorIds.join(','));
  }
  const tagConditions = serializeTagConditions(advancedFilter.tagConditions);
  if (tagConditions) {
    params.set(WORKITEM_LIST_URL_PARAM.tagConditions, tagConditions);
  }
  if (advancedFilter.unassignedSquad) {
    params.set(WORKITEM_LIST_URL_PARAM.unassignedSquad, 'true');
  } else if (advancedFilter.squadIds.length > 0) {
    params.set(WORKITEM_LIST_URL_PARAM.squadIds, advancedFilter.squadIds.join(','));
  }
  return params;
}
