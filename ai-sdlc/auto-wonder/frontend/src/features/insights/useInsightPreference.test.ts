import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import dayjs from 'dayjs';
import { useInsightDateRange, useInsightDeliveryWorkType, useInsightPreference, useInsightWorkType } from './useInsightPreference';

const scope = vi.hoisted(() => ({ user: { id: 1 }, currentWorkspace: { id: 2 } }));
vi.mock('@/shared/auth/store', () => ({ useAuthStore: (selector: (s: typeof scope) => unknown) => selector(scope) }));
beforeEach(() => { localStorage.clear(); scope.currentWorkspace.id = 2; });
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });
const valid = (v: unknown) => v === 'DAY' || v === 'WEEK';

it('restores selections after remount and isolates changes between workspaces', () => {
  const first = renderHook(() => useInsightPreference('granularity', 'DAY', valid));
  act(() => first.result.current[1]('WEEK'));
  first.unmount();
  const next = renderHook(() => useInsightPreference('granularity', 'DAY', valid));
  expect(next.result.current[0]).toBe('WEEK');
  scope.currentWorkspace.id = 3;
  next.rerender();
  expect(next.result.current[0]).toBe('DAY');
  act(() => next.result.current[1]('DAY'));
  scope.currentWorkspace.id = 2;
  next.rerender();
  expect(next.result.current[0]).toBe('WEEK');
});

it('falls back for corrupt values and keeps controls usable when storage is blocked', () => {
  localStorage.setItem('aw:insights:v1:1:2:granularity', 'broken json');
  const { result } = renderHook(() => useInsightPreference('granularity', 'DAY', valid));
  expect(result.current[0]).toBe('DAY');
  const storage = localStorage;
  const blockedWrite = vi.fn(() => { throw new Error('blocked'); });
  // Storage may expose own methods (Node fallback) or proxy-backed prototype methods (jsdom).
  vi.stubGlobal('localStorage', { getItem: storage.getItem.bind(storage), setItem: blockedWrite });
  act(() => result.current[1]('WEEK'));
  expect(result.current[0]).toBe('WEEK');
  expect(blockedWrite).toHaveBeenCalledWith('aw:insights:v1:1:2:granularity', JSON.stringify('WEEK'));
  expect(storage.getItem('aw:insights:v1:1:2:granularity')).toBe('broken json');
  vi.unstubAllGlobals();
  localStorage.setItem('aw:insights:v1:1:2:granularity', JSON.stringify('WEEK'));
  const blockedRead = vi.fn(() => { throw new Error('blocked'); });
  vi.stubGlobal('localStorage', { getItem: blockedRead });
  expect(renderHook(() => useInsightPreference('granularity', 'DAY', valid)).result.current[0]).toBe('DAY');
  expect(blockedRead).toHaveBeenCalledWith('aw:insights:v1:1:2:granularity');
});

it('restores date objects and rejects invalid, reversed, future or expired participation ranges', () => {
  const fallback = (): [dayjs.Dayjs, dayjs.Dayjs] => [dayjs('2026-09-01'), dayjs('2026-09-25')];
  const key = 'aw:insights:v1:1:2:participation.dates';
  const useRange = () => useInsightDateRange('participation.dates', fallback, 181, dayjs('2026-09-25'), dayjs('2026-09-25').subtract(180, 'day'));
  const first = renderHook(useRange);
  act(() => first.result.current[1]([dayjs('2026-09-10'), dayjs('2026-09-20')]));
  first.unmount();
  expect(renderHook(useRange).result.current[0][0].format('YYYY-MM-DD')).toBe('2026-09-10');
  for (const range of [['2026-02-30', '2026-09-25'], ['2026-09-20', '2026-09-10'], ['2026-09-10', '2026-09-26'], ['2025-01-01', '2025-01-10']]) {
    localStorage.setItem(key, JSON.stringify(range));
    expect(renderHook(useRange).result.current[0][0].format('YYYY-MM-DD')).toBe('2026-09-01');
  }
});

it('accepts a participation range that ends today once the upper bound is released', () => {
  const fallback = (): [dayjs.Dayjs, dayjs.Dayjs] => [dayjs('2026-09-01'), dayjs('2026-09-25')];
  const key = 'aw:insights:v1:1:2:participation.dates';
  const useRange = () => useInsightDateRange('participation.dates', fallback, 181, dayjs('2026-09-25'), dayjs('2026-09-25').subtract(180, 'day'));

  localStorage.setItem(key, JSON.stringify(['2026-09-10', '2026-09-25']));
  expect(renderHook(useRange).result.current[0][1].format('YYYY-MM-DD')).toBe('2026-09-25');
});

it('applies an optional earliest bound and keeps wider ranges for callers without one', () => {
  const fallback = (): [dayjs.Dayjs, dayjs.Dayjs] => [dayjs('2026-09-01'), dayjs('2026-09-25')];
  const latest = dayjs('2026-09-25');
  const useBounded = () => useInsightDateRange('members.dates', fallback, 181, latest, latest.subtract(180, 'day'));
  localStorage.setItem('aw:insights:v1:1:2:members.dates', JSON.stringify(['2026-03-01', '2026-09-25']));
  expect(renderHook(useBounded).result.current[0][0].format('YYYY-MM-DD')).toBe('2026-09-01');
  localStorage.setItem('aw:insights:v1:1:2:members.dates', JSON.stringify(['2026-03-29', '2026-09-25']));
  expect(renderHook(useBounded).result.current[0][0].format('YYYY-MM-DD')).toBe('2026-03-29');
  const useUnbounded = () => useInsightDateRange('stages.dates', fallback, 366, latest);
  localStorage.setItem('aw:insights:v1:1:2:stages.dates', JSON.stringify(['2025-09-26', '2026-09-25']));
  expect(renderHook(useUnbounded).result.current[0][0].format('YYYY-MM-DD')).toBe('2025-09-26');
});

it('persists the work type filter and rejects unknown or legacy values', () => {
  const key = 'aw:insights:v1:1:2:participation.workType';
  expect(renderHook(useInsightWorkType).result.current[0]).toBe('ALL');

  localStorage.setItem(key, JSON.stringify('BUG'));
  expect(renderHook(useInsightWorkType).result.current[0]).toBe('BUG');

  const { result } = renderHook(useInsightWorkType);
  act(() => result.current[1]('TASK'));
  expect(localStorage.getItem(key)).toBe(JSON.stringify('TASK'));

  for (const invalid of ['EPIC', '', 42]) {
    localStorage.setItem(key, JSON.stringify(invalid));
    expect(renderHook(useInsightWorkType).result.current[0]).toBe('ALL');
  }
});

it('persists the delivery work type under its own key and falls back for unknown values', () => {
  const key = 'aw:insights:v1:1:2:delivery.workType';
  const participationKey = 'aw:insights:v1:1:2:participation.workType';
  expect(renderHook(useInsightDeliveryWorkType).result.current[0]).toBe('ALL');

  // 人机协作的类型偏好不得串到工单交付统计区，反之亦然。
  localStorage.setItem(participationKey, JSON.stringify('BUG'));
  expect(renderHook(useInsightDeliveryWorkType).result.current[0]).toBe('ALL');

  localStorage.setItem(key, JSON.stringify('TASK'));
  expect(renderHook(useInsightDeliveryWorkType).result.current[0]).toBe('TASK');

  const { result } = renderHook(useInsightDeliveryWorkType);
  act(() => result.current[1]('REQ'));
  expect(localStorage.getItem(key)).toBe(JSON.stringify('REQ'));
  expect(localStorage.getItem(participationKey)).toBe(JSON.stringify('BUG'));

  for (const invalid of ['EPIC', 'ALL ', '', 42]) {
    localStorage.setItem(key, JSON.stringify(invalid));
    expect(renderHook(useInsightDeliveryWorkType).result.current[0]).toBe('ALL');
  }
});
