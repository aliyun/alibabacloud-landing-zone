import { describe, it, expect } from 'vitest';
import dayjs from 'dayjs';
import {
  EMPTY_CREATED_TIME_RANGE,
  buildCreatedTimeRangePreset,
  hasCreatedTimeRange,
  isCreatedTimeRangeInvalid,
  toCreatedTimeQuery,
  type CreatedTimeRangeDraft,
} from './createdTimeFilter';

function range(from: string | null, to: string | null): CreatedTimeRangeDraft {
  return { from: from ? dayjs(from) : null, to: to ? dayjs(to) : null };
}

const localDayStart = (value: string) => dayjs(value).startOf('day').toDate().toISOString();
const localNextDayStart = (value: string) =>
  dayjs(value).startOf('day').add(1, 'day').toDate().toISOString();

describe('createdTimeFilter', () => {
  it('adds no query bounds and reports no condition when nothing is selected', () => {
    expect(hasCreatedTimeRange(EMPTY_CREATED_TIME_RANGE)).toBe(false);
    expect(toCreatedTimeQuery(EMPTY_CREATED_TIME_RANGE)).toEqual({});
  });

  it('maps a full range to an inclusive lower bound and an exclusive next-day upper bound', () => {
    const query = toCreatedTimeQuery(range('2026-10-01', '2026-10-10'));

    expect(query.createdFrom).toBe(localDayStart('2026-10-01'));
    expect(query.createdTo).toBe(localNextDayStart('2026-10-10'));
    // 结束日全天都在区间内：排他上界必须晚于结束日当天起点
    expect(new Date(query.createdTo!).getTime()).toBeGreaterThan(new Date(localDayStart('2026-10-10')).getTime());
  });

  it('supports a start-only condition', () => {
    const query = toCreatedTimeQuery(range('2026-10-01', null));

    expect(query.createdFrom).toBe(localDayStart('2026-10-01'));
    expect('createdTo' in query).toBe(false);
  });

  it('supports an end-only condition', () => {
    const query = toCreatedTimeQuery(range(null, '2026-10-10'));

    expect(query.createdTo).toBe(localNextDayStart('2026-10-10'));
    expect('createdFrom' in query).toBe(false);
  });

  it('reports a single-sided range as an applied condition', () => {
    expect(hasCreatedTimeRange(range('2026-10-01', null))).toBe(true);
    expect(hasCreatedTimeRange(range(null, '2026-10-10'))).toBe(true);
    expect(hasCreatedTimeRange(range('2026-10-01', '2026-10-10'))).toBe(true);
  });

  it('rejects a range whose start day is later than its end day and submits nothing', () => {
    expect(isCreatedTimeRangeInvalid(range('2026-10-10', '2026-10-01'))).toBe(true);
    expect(toCreatedTimeQuery(range('2026-10-10', '2026-10-01'))).toEqual({});
  });

  it('keeps a single-day range and single-sided ranges valid', () => {
    expect(isCreatedTimeRangeInvalid(range('2026-10-01', '2026-10-01'))).toBe(false);
    expect(isCreatedTimeRangeInvalid(range('2026-10-10', null))).toBe(false);
    expect(isCreatedTimeRangeInvalid(range(null, '2026-10-01'))).toBe(false);
    expect(isCreatedTimeRangeInvalid(EMPTY_CREATED_TIME_RANGE)).toBe(false);
  });

  it('compares the two bounds by day, ignoring the time of day', () => {
    expect(isCreatedTimeRangeInvalid({
      from: dayjs('2026-10-01T23:00:00'),
      to: dayjs('2026-10-01T00:30:00'),
    })).toBe(false);
  });
});

describe('buildCreatedTimeRangePreset', () => {
  const today = dayjs('2026-10-09T15:30:00');

  it('builds the last-week preset as today plus the six previous days', () => {
    const preset = buildCreatedTimeRangePreset('lastWeek', today);

    expect(preset.from?.format('YYYY-MM-DD')).toBe('2026-10-03');
    expect(preset.to?.format('YYYY-MM-DD')).toBe('2026-10-09');
  });

  it('builds the last-month preset as today plus the twenty-nine previous days', () => {
    const preset = buildCreatedTimeRangePreset('lastMonth', today);

    expect(preset.from?.format('YYYY-MM-DD')).toBe('2026-09-10');
    expect(preset.to?.format('YYYY-MM-DD')).toBe('2026-10-09');
  });

  it('normalises both bounds to the start of the day', () => {
    const preset = buildCreatedTimeRangePreset('lastWeek', today);

    expect(preset.from?.format('HH:mm:ss.SSS')).toBe('00:00:00.000');
    expect(preset.to?.format('HH:mm:ss.SSS')).toBe('00:00:00.000');
  });

  it('rolls the presets across month and year boundaries', () => {
    const acrossMonth = buildCreatedTimeRangePreset('lastWeek', dayjs('2026-03-01'));
    expect(acrossMonth.from?.format('YYYY-MM-DD')).toBe('2026-02-23');
    expect(acrossMonth.to?.format('YYYY-MM-DD')).toBe('2026-03-01');

    const acrossYear = buildCreatedTimeRangePreset('lastMonth', dayjs('2026-01-05'));
    expect(acrossYear.from?.format('YYYY-MM-DD')).toBe('2025-12-07');
    expect(acrossYear.to?.format('YYYY-MM-DD')).toBe('2026-01-05');
  });

  it('produces a valid condition that feeds the query conversion with the shared bounds', () => {
    const preset = buildCreatedTimeRangePreset('lastWeek', today);

    expect(hasCreatedTimeRange(preset)).toBe(true);
    expect(isCreatedTimeRangeInvalid(preset)).toBe(false);
    const query = toCreatedTimeQuery(preset);
    expect(query.createdFrom).toBe(localDayStart('2026-10-03'));
    expect(query.createdTo).toBe(localNextDayStart('2026-10-09'));
  });

  it('falls back to the current day when no reference day is supplied', () => {
    const preset = buildCreatedTimeRangePreset('lastWeek');

    expect(preset.to?.format('YYYY-MM-DD')).toBe(dayjs().format('YYYY-MM-DD'));
  });
});
