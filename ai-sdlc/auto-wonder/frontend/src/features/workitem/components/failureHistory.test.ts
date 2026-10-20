import { describe, expect, it } from 'vitest';
import { failureHistoryOf, parseFailureHistory } from './failureHistory';
import type { RuntimeTraceEvent } from '@/shared/types/workitem';

const incidentHistory = {
  truncated: false,
  rounds: [
    {
      phase: 'routing_preflight', attemptIndex: 1, provider: 'qoder', model: 'dmodel',
      sessionId: '689186f5-a6bd-4816-9fa1-6bff68e7b896',
      startedAt: '2026-10-15T03:11:09.100Z', endedAt: '2026-10-15T03:21:07.331Z', durationMs: 598231,
      status: 'failed', error: 'qoder turn failed: RESOURCE_LIMIT 10605 model_queued Queuing failed',
      diagnostics: [{
        timestamp: '2026-10-15T03:21:07.331Z', source: 'provider_log', category: 'business',
        code: '10605', subCode: 'model_queued', businessCode: 'RESOURCE_LIMIT', httpStatus: 200,
        message: 'Queuing failed', requestId: 'f203f510-c793-4e82-b64d-18c879abb08c',
        serverRequestId: 'e0a61fb9-725f-4b21-b9ac-ce708c58c054', retryable: true, willRetry: true,
        occurrences: 2, firstSeen: '2026-10-15T03:21:07.331Z', lastSeen: '2026-10-15T03:21:08.140Z',
      }],
    },
    {
      phase: 'routing_preflight', attemptIndex: 2, provider: 'qoder', model: 'dmodel',
      sessionId: 'aa2db1e7-4256-49eb-ae7b-6faea387b7e0', status: 'failed',
      error: 'qoder semantic inactivity timeout after 10m0s',
      diagnostics: [{
        source: 'provider_rpc', category: 'timeout', code: 'timeout',
        message: 'no first payload within 10m0s', requestId: 'a3203242-026f-43ab-b92b-1ccc700155c4',
        serverRequestId: '345785b7-b5b3-4c9f-b250-a0bd39f770b3',
      }],
    },
  ],
};

function event(eventType: string, detail: Record<string, unknown>): RuntimeTraceEvent {
  return { eventId: `${eventType}-1`, eventType, eventTime: '2026-10-15T03:41:07.331Z', detail };
}

describe('parseFailureHistory', () => {
  it('keeps every failed round and diagnostic field of the incident payload', () => {
    const history = parseFailureHistory(incidentHistory);
    expect(history).not.toBeNull();
    expect(history?.rounds).toHaveLength(2);
    const [first, second] = history!.rounds!;
    expect(first.sessionId).toBe('689186f5-a6bd-4816-9fa1-6bff68e7b896');
    expect(first.model).toBe('dmodel');
    expect(first.error).toContain('RESOURCE_LIMIT 10605 model_queued Queuing failed');
    const diagnostic = first.diagnostics![0];
    expect(diagnostic.source).toBe('provider_log');
    expect(diagnostic.category).toBe('business');
    expect(diagnostic.code).toBe('10605');
    expect(diagnostic.subCode).toBe('model_queued');
    expect(diagnostic.businessCode).toBe('RESOURCE_LIMIT');
    expect(diagnostic.httpStatus).toBe(200);
    expect(diagnostic.requestId).toBe('f203f510-c793-4e82-b64d-18c879abb08c');
    expect(diagnostic.serverRequestId).toBe('e0a61fb9-725f-4b21-b9ac-ce708c58c054');
    expect(diagnostic.retryable).toBe(true);
    expect(diagnostic.willRetry).toBe(true);
    expect(diagnostic.occurrences).toBe(2);
    expect(diagnostic.lastSeen).toBe('2026-10-15T03:21:08.140Z');
    expect(second.error).toContain('semantic inactivity timeout');
    expect(second.diagnostics![0].requestId).toBe('a3203242-026f-43ab-b92b-1ccc700155c4');
  });

  it('drops malformed rounds and diagnostics instead of guessing values', () => {
    const history = parseFailureHistory({
      rounds: ['not-a-round', 42, null, { error: 'kept round' }, {}],
      truncated: 'yes',
      omittedRounds: '3',
    });
    expect(history?.rounds).toHaveLength(1);
    expect(history?.rounds![0].error).toBe('kept round');
    expect(history?.rounds![0].diagnostics).toEqual([]);
    expect(history?.truncated).toBeUndefined();
    expect(history?.omittedRounds).toBeUndefined();
  });

  it('keeps only well-formed diagnostics and no fabricated fields', () => {
    const history = parseFailureHistory({ rounds: [{ error: 'x', diagnostics: ['raw text', { message: 'kept' }, { unknown: 1 }] }] });
    expect(history?.rounds![0].diagnostics).toHaveLength(1);
    expect(history?.rounds![0].diagnostics![0].message).toBe('kept');
    expect(history?.rounds![0].diagnostics![0].code).toBeUndefined();
  });

  it('returns null for absent or unusable payloads', () => {
    expect(parseFailureHistory(undefined)).toBeNull();
    expect(parseFailureHistory(null)).toBeNull();
    expect(parseFailureHistory('rounds')).toBeNull();
    expect(parseFailureHistory([{ error: 'x' }])).toBeNull();
    expect(parseFailureHistory({ rounds: 'broken' })).toBeNull();
    expect(parseFailureHistory({ rounds: [{}, null] })).toBeNull();
  });
});

describe('failureHistoryOf', () => {
  it('returns the newest history-bearing event, so the terminal snapshot wins', () => {
    const events = [
      event('turn.failed', { failureHistory: { rounds: [{ attemptIndex: 1, error: 'first round only' }] } }),
      event('llm.failed', { error: 'no history here' }),
      event('dispatch.failed', { error: 'terminal', failureHistory: incidentHistory }),
    ];
    const history = failureHistoryOf(events);
    expect(history?.rounds).toHaveLength(2);
    expect(history?.rounds![1].error).toContain('semantic inactivity timeout');
  });

  it('returns null when no event carries a usable history', () => {
    expect(failureHistoryOf(undefined)).toBeNull();
    expect(failureHistoryOf([])).toBeNull();
    expect(failureHistoryOf([event('dispatch.failed', { error: 'legacy failure' })])).toBeNull();
    expect(failureHistoryOf([event('dispatch.failed', { failureHistory: { rounds: [] } })])).toBeNull();
  });
});
