import { describe, it, expect } from 'vitest';
import { parseExecutorAgentParam } from './executorAgentParam';

function params(query: string) {
  return new URLSearchParams(query);
}

describe('parseExecutorAgentParam', () => {
  it('returns nothing special for an absent or empty agent id', () => {
    expect(parseExecutorAgentParam(params(''))).toEqual({ invalid: false, entryView: false });
    expect(parseExecutorAgentParam(params('view=agent'))).toEqual({ invalid: false, entryView: true });
    expect(parseExecutorAgentParam(params('agentId='))).toEqual({ invalid: false, entryView: false });
  });

  it('accepts a positive integer id and trims surrounding spaces', () => {
    expect(parseExecutorAgentParam(params('agentId=7&view=agent'))).toEqual({ agentId: 7, invalid: false, entryView: true });
    expect(parseExecutorAgentParam(params('agentId=%207%20'))).toEqual({ agentId: 7, invalid: false, entryView: false });
  });

  it('rejects non-numeric, zero, negative, fractional and unsafe ids', () => {
    for (const raw of ['abc', '0', '-3', '1.5', '90071992547409930']) {
      expect(parseExecutorAgentParam(params(`agentId=${raw}`))).toEqual({ invalid: true, entryView: false });
    }
  });

  it('only treats the exact agent view value as the entry view', () => {
    expect(parseExecutorAgentParam(params('view=squad')).entryView).toBe(false);
    expect(parseExecutorAgentParam(params('view=AGENT')).entryView).toBe(false);
  });
});
