import dayjs, { type Dayjs } from 'dayjs';
import type { AuditLogFilters } from './api';

export type AuditActorType = '' | 'HUMAN' | 'AGENT' | 'SYSTEM';

export interface AuditFilterState {
  actorType: AuditActorType;
  module: string;
  action: string;
  riskLevel: string;
  targetType: string;
  actorId?: number;
  targetId?: number;
  range: [Dayjs, Dayjs] | null;
  keyword: string;
}

const ACTOR_TYPES = ['HUMAN', 'AGENT', 'SYSTEM'] as const;

export function isAuditActorType(value: string): value is Exclude<AuditActorType, ''> {
  return (ACTOR_TYPES as readonly string[]).includes(value);
}

export const EMPTY_AUDIT_FILTERS: AuditFilterState = {
  actorType: '',
  module: '',
  action: '',
  riskLevel: '',
  targetType: '',
  actorId: undefined,
  targetId: undefined,
  range: null,
  keyword: '',
};

export function normalizeRange(dates: [Dayjs | null, Dayjs | null] | null): [Dayjs, Dayjs] | null {
  return dates && dates[0] && dates[1] ? [dates[0], dates[1]] : null;
}

function positiveInteger(value: string | null): number | undefined {
  if (!value) {
    return undefined;
  }
  const parsed = Number(value);
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : undefined;
}

const SHANGHAI_OFFSET_MS = 8 * 60 * 60 * 1000;

/** ISO instant 是上海时区的日边界，取日期时必须先换算到 UTC+8，避免浏览器时区偏移一天。 */
function shanghaiDate(iso: string): Dayjs {
  const instant = new Date(iso);
  if (Number.isNaN(instant.getTime())) {
    return dayjs(iso);
  }
  return dayjs(new Date(instant.getTime() + SHANGHAI_OFFSET_MS).toISOString().slice(0, 10));
}

function shanghaiRange(start: string | null, end: string | null): [Dayjs, Dayjs] | null {
  const first = start ? shanghaiDate(start) : null;
  const last = end ? shanghaiDate(end) : null;
  return first && last && first.isValid() && last.isValid() ? [first, last] : null;
}

/** 初始筛选读自深链参数，保留旧页 actorId / targetId 能力。 */
export function readInitialFilters(params: URLSearchParams): AuditFilterState {
  const actorType = params.get('actorType');
  return {
    actorType: ACTOR_TYPES.find((item) => item === actorType) ?? '',
    module: params.get('module') ?? '',
    action: params.get('action') ?? '',
    riskLevel: params.get('riskLevel') ?? '',
    targetType: params.get('targetType') ?? '',
    actorId: positiveInteger(params.get('actorId')),
    targetId: positiveInteger(params.get('targetId')),
    range: shanghaiRange(params.get('startTime'), params.get('endTime')),
    keyword: params.get('keyword') ?? '',
  };
}

/** 日期粒度双端含，按 Asia/Shanghai 映射为 ISO instant。 */
export function toShanghaiBounds(range: [Dayjs, Dayjs] | null): Pick<AuditLogFilters, 'startTime' | 'endTime'> {
  if (!range) {
    return {};
  }
  return {
    startTime: new Date(`${range[0].format('YYYY-MM-DD')}T00:00:00+08:00`).toISOString(),
    endTime: new Date(`${range[1].format('YYYY-MM-DD')}T23:59:59.999+08:00`).toISOString(),
  };
}

export function toQueryFilters(
  state: AuditFilterState,
  keyword: string,
): Omit<AuditLogFilters, 'page' | 'size'> {
  return {
    actorType: state.actorType || undefined,
    module: state.module || undefined,
    action: state.action || undefined,
    riskLevel: state.riskLevel || undefined,
    targetType: state.targetType || undefined,
    actorId: state.actorId,
    targetId: state.targetId,
    keyword: keyword.trim() || undefined,
    ...toShanghaiBounds(state.range),
  };
}
