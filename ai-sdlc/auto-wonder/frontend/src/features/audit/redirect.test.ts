import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { buildAuditLogRedirect } from './redirect';

const searchOf = (target: string) => new URLSearchParams(target.split('?')[1] ?? '');

describe('buildAuditLogRedirect', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-07-15T03:00:00Z'));
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('returns the plain audit log path without any filter', () => {
    expect(buildAuditLogRedirect({})).toBe('/audit-logs');
    expect(buildAuditLogRedirect({ timeRange: 'latest', riskLevel: '' })).toBe('/audit-logs');
  });

  it('maps worker, time range and risk level onto audit log filters', () => {
    const target = buildAuditLogRedirect({ workerId: 40015, timeRange: '7d', riskLevel: 'high' });

    expect(target.startsWith('/audit-logs?')).toBe(true);
    const search = searchOf(target);
    expect(search.get('actorType')).toBe('AGENT');
    expect(search.get('actorId')).toBe('40015');
    expect(search.get('riskLevel')).toBe('high');
    expect(search.get('startTime')).toBe('2026-07-08T16:00:00.000Z');
    expect(search.get('endTime')).toBe('2026-07-15T15:59:59.999Z');
  });

  it('keeps risk level without a worker and ignores zero-day ranges', () => {
    const search = searchOf(buildAuditLogRedirect({ timeRange: '0d', riskLevel: 'medium' }));

    expect(search.get('actorType')).toBeNull();
    expect(search.get('actorId')).toBeNull();
    expect(search.get('riskLevel')).toBe('medium');
    expect(search.get('startTime')).toBeNull();
    expect(search.get('endTime')).toBeNull();
  });

  it('maps a 90 day window starting at the same calendar day', () => {
    const search = searchOf(buildAuditLogRedirect({ workerId: 12, timeRange: '90d' }));

    expect(search.get('actorId')).toBe('12');
    expect(search.get('riskLevel')).toBeNull();
    expect(search.get('startTime')).toBe('2026-04-16T16:00:00.000Z');
    expect(search.get('endTime')).toBe('2026-07-15T15:59:59.999Z');
  });

  it('omits both actor params when no worker id is stored', () => {
    const search = searchOf(buildAuditLogRedirect({ timeRange: '30d', riskLevel: 'high' }));

    expect(search.get('actorType')).toBeNull();
    expect(search.get('actorId')).toBeNull();
    expect(search.get('riskLevel')).toBe('high');
    expect(search.get('startTime')).not.toBeNull();
    expect(search.get('endTime')).not.toBeNull();
  });

  it('keeps only the risk level when the time range is missing', () => {
    const search = searchOf(buildAuditLogRedirect({ riskLevel: 'medium' }));

    expect(search.get('riskLevel')).toBe('medium');
    expect(search.get('startTime')).toBeNull();
    expect(search.get('endTime')).toBeNull();
    expect(search.get('actorType')).toBeNull();
    expect(search.get('actorId')).toBeNull();
  });

  it('drops an empty risk level', () => {
    const search = searchOf(buildAuditLogRedirect({ workerId: 12, timeRange: '7d', riskLevel: '' }));

    expect(search.get('actorId')).toBe('12');
    expect(search.get('riskLevel')).toBeNull();
  });
});
