import { describe, it, expect, beforeEach } from 'vitest';
import {
  clearClarificationPrefill,
  readClarificationPrefill,
  writeClarificationPrefill,
} from './prefill';

describe('clarification prefill', () => {
  beforeEach(() => {
    window.localStorage.clear();
  });

  it('round-trips a squad+agent selection per workitem', () => {
    writeClarificationPrefill('12345', { squadId: 7, agentId: 42 });
    const value = readClarificationPrefill('12345');
    expect(value).not.toBeNull();
    expect(value!.squadId).toBe(7);
    expect(value!.agentId).toBe(42);
  });

  it('isolates prefill per workitem', () => {
    writeClarificationPrefill('1', { squadId: 7, agentId: 42 });
    expect(readClarificationPrefill('2')).toBeNull();
  });

  it('returns null when storage is empty', () => {
    expect(readClarificationPrefill('999')).toBeNull();
  });

  it('clear removes the stored value', () => {
    writeClarificationPrefill('1', { squadId: 7, agentId: 42 });
    clearClarificationPrefill('1');
    expect(readClarificationPrefill('1')).toBeNull();
  });
});
