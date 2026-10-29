import { describe, expect, it } from 'vitest';
import dayjs from 'dayjs';
import {
  EMPTY_AUDIT_FILTERS,
  isAuditActorType,
  normalizeRange,
  readInitialFilters,
  toQueryFilters,
  toShanghaiBounds,
} from './auditFilters';

const params = (search: string) => new URLSearchParams(search);

describe('isAuditActorType', () => {
  it('accepts known actor types only', () => {
    expect(isAuditActorType('HUMAN')).toBe(true);
    expect(isAuditActorType('AGENT')).toBe(true);
    expect(isAuditActorType('SYSTEM')).toBe(true);
    expect(isAuditActorType('')).toBe(false);
    expect(isAuditActorType('human')).toBe(false);
    expect(isAuditActorType('ROBOT')).toBe(false);
  });
});

describe('normalizeRange', () => {
  it('keeps complete ranges', () => {
    const start = dayjs('2026-07-01');
    const end = dayjs('2026-07-07');
    expect(normalizeRange([start, end])).toEqual([start, end]);
  });

  it('drops null, empty and half-open ranges', () => {
    expect(normalizeRange(null)).toBeNull();
    expect(normalizeRange([dayjs('2026-07-01'), null])).toBeNull();
    expect(normalizeRange([null, dayjs('2026-07-07')])).toBeNull();
  });
});

describe('readInitialFilters', () => {
  it('reads every deep-link parameter and converts shanghai instants to calendar dates', () => {
    const filters = readInitialFilters(params(
      'actorType=AGENT&module=WORKITEM&action=DELETE&riskLevel=high&targetType=workitem'
      + '&actorId=42&targetId=7&keyword=%E5%8F%91%E5%B8%83'
      + '&startTime=2026-06-30T16:00:00.000Z&endTime=2026-07-02T15:59:59.999Z',
    ));

    expect(filters.actorType).toBe('AGENT');
    expect(filters.module).toBe('WORKITEM');
    expect(filters.action).toBe('DELETE');
    expect(filters.riskLevel).toBe('high');
    expect(filters.targetType).toBe('workitem');
    expect(filters.actorId).toBe(42);
    expect(filters.targetId).toBe(7);
    expect(filters.keyword).toBe('发布');
    expect(filters.range?.[0].format('YYYY-MM-DD')).toBe('2026-07-01');
    expect(filters.range?.[1].format('YYYY-MM-DD')).toBe('2026-07-02');
  });

  it('falls back to empty filters and normalizes invalid values', () => {
    const filters = readInitialFilters(params('actorType=ROBOT&actorId=abc&targetId=-3'));

    expect(filters.actorType).toBe('');
    expect(filters.module).toBe('');
    expect(filters.actorId).toBeUndefined();
    expect(filters.targetId).toBeUndefined();
    expect(filters.range).toBeNull();
    expect(filters.keyword).toBe('');
  });

  it.each(['0', '1.5', 'abc', ''])('drops actor id %s that is not a positive integer', (raw) => {
    expect(readInitialFilters(params(`actorId=${raw}`)).actorId).toBeUndefined();
  });

  it('drops half-open and invalid time ranges', () => {
    expect(readInitialFilters(params('startTime=2026-07-01T00:00:00.000Z')).range).toBeNull();
    expect(readInitialFilters(params('startTime=not-a-date&endTime=2026-07-01T00:00:00.000Z')).range).toBeNull();
  });
});

describe('toShanghaiBounds', () => {
  it('maps a calendar range to inclusive Asia/Shanghai instants', () => {
    expect(toShanghaiBounds([dayjs('2026-07-01'), dayjs('2026-07-07')])).toEqual({
      startTime: '2026-06-30T16:00:00.000Z',
      endTime: '2026-07-07T15:59:59.999Z',
    });
  });

  it('returns no bounds without a range', () => {
    expect(toShanghaiBounds(null)).toEqual({});
  });
});

describe('toQueryFilters', () => {
  it('drops empty selections and blank keywords', () => {
    const filters = toQueryFilters(EMPTY_AUDIT_FILTERS, '   ');

    expect(filters.actorType).toBeUndefined();
    expect(filters.module).toBeUndefined();
    expect(filters.action).toBeUndefined();
    expect(filters.riskLevel).toBeUndefined();
    expect(filters.targetType).toBeUndefined();
    expect(filters.actorId).toBeUndefined();
    expect(filters.targetId).toBeUndefined();
    expect(filters.keyword).toBeUndefined();
    expect('startTime' in filters).toBe(false);
  });

  it('keeps selections, ids, trimmed keyword and time bounds', () => {
    const filters = toQueryFilters({
      ...EMPTY_AUDIT_FILTERS,
      actorType: 'AGENT',
      module: 'WORKITEM',
      action: 'DELETE',
      riskLevel: 'high',
      targetType: 'workitem',
      actorId: 7,
      targetId: 42,
      range: [dayjs('2026-07-01'), dayjs('2026-07-01')],
    }, '  发布  ');

    expect(filters).toEqual({
      actorType: 'AGENT',
      module: 'WORKITEM',
      action: 'DELETE',
      riskLevel: 'high',
      targetType: 'workitem',
      actorId: 7,
      targetId: 42,
      keyword: '发布',
      startTime: '2026-06-30T16:00:00.000Z',
      endTime: '2026-07-01T15:59:59.999Z',
    });
  });
});
