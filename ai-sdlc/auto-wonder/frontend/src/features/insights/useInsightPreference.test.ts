import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import dayjs from 'dayjs';
import { useInsightDateRange, useInsightPreference } from './useInsightPreference';

const scope = vi.hoisted(() => ({ user: { id: 1 }, currentWorkspace: { id: 2 } }));
vi.mock('@/shared/auth/store', () => ({ useAuthStore: (selector: (s: typeof scope) => unknown) => selector(scope) }));
beforeEach(() => { localStorage.clear(); scope.currentWorkspace.id = 2; });
afterEach(() => { vi.restoreAllMocks(); });
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
  vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('blocked'); });
  act(() => result.current[1]('WEEK'));
  expect(result.current[0]).toBe('WEEK');
  vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('blocked'); });
  expect(renderHook(() => useInsightPreference('granularity', 'DAY', valid)).result.current[0]).toBe('DAY');
});

it('restores date objects and rejects invalid, reversed, future or expired participation ranges', () => {
  const fallback = (): [dayjs.Dayjs, dayjs.Dayjs] => [dayjs('2026-09-01'), dayjs('2026-09-25')];
  const key = 'aw:insights:v1:1:2:participation.dates';
  const useRange = () => useInsightDateRange('participation.dates', fallback, 181, dayjs('2026-09-25'));
  const first = renderHook(useRange);
  act(() => first.result.current[1]([dayjs('2026-09-10'), dayjs('2026-09-20')]));
  first.unmount();
  expect(renderHook(useRange).result.current[0][0].format('YYYY-MM-DD')).toBe('2026-09-10');
  for (const range of [['2026-02-30', '2026-09-25'], ['2026-09-20', '2026-09-10'], ['2026-09-10', '2026-09-26'], ['2025-01-01', '2025-01-10']]) {
    localStorage.setItem(key, JSON.stringify(range));
    expect(renderHook(useRange).result.current[0][0].format('YYYY-MM-DD')).toBe('2026-09-01');
  }
});
