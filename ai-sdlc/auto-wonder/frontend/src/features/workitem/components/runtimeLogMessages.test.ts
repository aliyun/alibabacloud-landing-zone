import { describe, it, expect } from 'vitest';
import { aggregateRuntimeMessages } from './runtimeLogMessages';
import type { RuntimeTraceEvent } from '@/shared/types/workitem';
const message = (seq: number, text: string, spanId = 's'): RuntimeTraceEvent => ({ seq, eventType: 'agent.message', detail: { type: 'text', contentSummary: text, sessionId: 'session', turnId: 'turn', spanId } });
describe('aggregateRuntimeMessages', () => {
  it('joins deltas without inserting spaces and retains original events without mutation', () => {
    const events = [message(1, '读取'), message(2, ' evidence/'), message(3, 'pending.json')];
    const rows = aggregateRuntimeMessages(events);
    expect(rows).toHaveLength(1);
    expect(rows[0].messageText).toBe('读取 evidence/pending.json');
    expect(rows[0].endSeq).toBe(3);
    expect(rows[0].fragments).toEqual(events);
    expect(events[0].detail.contentSummary).toBe('读取');
  });
  it('does not cross tools, spans, missing sequence numbers or missing stream identity', () => {
    const events = [message(1, 'a'), { seq: 2, eventType: 'mcp.call', detail: {} }, message(3, 'b'), message(4, 'c', 'other'), message(6, 'd', 'other'), { ...message(7, 'e'), detail: { type: 'text', contentSummary: 'e' } }];
    expect(aggregateRuntimeMessages(events)).toHaveLength(6);
  });
  it('recomputes a growing message from refreshed snapshots without duplicate text', () => {
    const events = [message(1, 'a'), message(2, 'b')];
    aggregateRuntimeMessages(events);
    expect(aggregateRuntimeMessages([...events, message(3, 'c')])[0].messageText).toBe('abc');
  });
});
