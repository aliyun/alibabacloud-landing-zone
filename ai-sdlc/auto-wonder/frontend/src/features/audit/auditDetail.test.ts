import { describe, expect, it } from 'vitest';
import type { AuditLog } from './api';
import { auditTime, detailText, parseDetail, triggerText } from './auditDetail';

const record = (overrides: Partial<AuditLog> = {}): AuditLog => ({
  id: 1,
  module: 'WORKITEM',
  action: 'UPDATE',
  actorId: 7,
  actorType: 'HUMAN',
  actorName: '张三',
  targetType: 'workitem',
  targetId: 42,
  detail: null,
  detailJson: null,
  gmtCreate: '2026-07-01T10:11:12',
  ...overrides,
});

describe('parseDetail', () => {
  it('prefers detailJson and falls back to detail', () => {
    expect(parseDetail(record({ detailJson: '{"eventType":"json"}', detail: '{"eventType":"raw"}' })).eventType).toBe('json');
    expect(parseDetail(record({ detail: '{"eventType":"raw"}' })).eventType).toBe('raw');
  });

  it('returns an empty object for missing, invalid or non-object payloads', () => {
    expect(parseDetail(record())).toEqual({});
    expect(parseDetail(record({ detailJson: '{broken' }))).toEqual({});
    expect(parseDetail(record({ detailJson: '123' }))).toEqual({});
    expect(parseDetail(record({ detailJson: 'null' }))).toEqual({});
  });
});

describe('detailText', () => {
  it('joins known fields in order and skips empty ones', () => {
    const text = detailText(record({
      detailJson: '{"message":"更新工单","error":null,"stepName":"编码","path":"","status":200}',
    }));

    expect(text).toBe('message: 更新工单；stepName: 编码；status: 200');
  });

  it('falls back to event type, raw payload and finally a dash', () => {
    expect(detailText(record({ detailJson: '{"eventType":"workitem.update"}' }))).toBe('workitem.update');
    expect(detailText(record({ detail: 'raw payload' }))).toBe('raw payload');
    expect(detailText(record())).toBe('-');
  });
});

describe('triggerText', () => {
  it('renders trigger type and source, and degrades to a dash', () => {
    expect(triggerText(record({ detailJson: '{"triggerType":"EVENT","triggerSource":"USER_CLICK"}' }))).toBe('EVENT / USER_CLICK');
    expect(triggerText(record({ detailJson: '{"triggerSource":"USER_CLICK"}' }))).toBe('USER_CLICK');
    expect(triggerText(record())).toBe('-');
  });
});

describe('auditTime', () => {
  it('formats local timestamps at second precision', () => {
    expect(auditTime('2026-07-01T10:11:12')).toBe('2026-07-01 10:11:12');
  });

  it('renders a dash for missing or invalid values', () => {
    expect(auditTime('not-a-date')).toBe('-');
    expect(auditTime(null)).toBe('-');
    expect(auditTime(undefined)).toBe('-');
  });
});
