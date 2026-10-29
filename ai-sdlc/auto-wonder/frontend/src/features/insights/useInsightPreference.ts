import { useState } from 'react';
import dayjs, { type Dayjs } from 'dayjs';
import { useAuthStore } from '@/shared/auth/store';
import { writeViewPreference } from '@/shared/lib/viewPreference';

// Workspace-specific filters must not leak into another workspace or account.
export function useInsightPreference<T>(name: string, fallback: T | (() => T), valid: (value: unknown) => boolean) {
  const scope = useAuthStore(s => `${s.user?.id ?? 'anonymous'}:${s.currentWorkspace?.id ?? 'none'}`);
  const key = `aw:insights:v1:${scope}:${name}`;
  const read = (): T => {
    try {
      const raw = localStorage.getItem(key);
      if (raw !== null) {
        const value: unknown = JSON.parse(raw);
        if (valid(value)) return value as T;
      }
    } catch { /* Invalid or unavailable browser storage falls back to defaults. */ }
    return typeof fallback === 'function' ? (fallback as () => T)() : fallback;
  };
  const [state, setState] = useState(() => ({ key, value: read() }));
  const current = state.key === key ? state : { key, value: read() };
  if (state.key !== key) setState(current);
  const setValue = (value: T) => {
    setState({ key, value });
    writeViewPreference(key, JSON.stringify(value ?? null));
  };
  return [current.value, setValue] as const;
}

export const INSIGHT_WORK_TYPES = ['ALL', 'REQ', 'BUG', 'TASK'] as const;
export type InsightWorkType = (typeof INSIGHT_WORK_TYPES)[number];

export function useInsightWorkType() {
  return useInsightPreference<InsightWorkType>('participation.workType', 'ALL',
    value => typeof value === 'string' && (INSIGHT_WORK_TYPES as readonly string[]).includes(value));
}

// 工单交付统计区的类型筛选与人机协作的全局筛选刻意使用不同偏好键，互不读写。
export function useInsightDeliveryWorkType() {
  return useInsightPreference<InsightWorkType>('delivery.workType', 'ALL',
    value => typeof value === 'string' && (INSIGHT_WORK_TYPES as readonly string[]).includes(value));
}

export function useInsightDateRange(name: string, fallback: () => [Dayjs, Dayjs], maxDays: number, latest: Dayjs, earliest?: Dayjs) {
  const [dates, setDates] = useInsightPreference<[string, string]>(name,
    () => fallback().map(date => date.format('YYYY-MM-DD')) as [string, string],
    value => Array.isArray(value) && value.length === 2
      && value.every(date => typeof date === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(date)
        && dayjs(date).isValid() && dayjs(date).format('YYYY-MM-DD') === date)
      && value[0] <= value[1] && !dayjs(value[1]).isAfter(latest, 'day')
      && dayjs(value[1]).diff(dayjs(value[0]), 'day') < maxDays
      && (!earliest || !dayjs(value[0]).isBefore(earliest, 'day')));
  return [dates.map(date => dayjs(date)) as [Dayjs, Dayjs],
    (value: [Dayjs, Dayjs]) => setDates(value.map(date => date.format('YYYY-MM-DD')) as [string, string])] as const;
}
