import { describe, expect, it } from 'vitest';
import { isTerminalInteractionExecution } from './interactionExecution';

describe('interaction execution terminal statuses', () => {
  it('treats every finished dispatch status as terminal', () => {
    for (const status of ['SUCCEEDED', 'FAILED', 'TIMEOUT', 'CANCELED']) {
      expect(isTerminalInteractionExecution(status)).toBe(true);
    }
  });

  it('keeps running and unknown execution states non-terminal', () => {
    for (const status of ['PENDING', 'QUEUED', 'DISPATCHED', 'ACKED', 'RUNNING', 'PAUSING', 'UNKNOWN', '', undefined, null]) {
      expect(isTerminalInteractionExecution(status)).toBe(false);
    }
  });
});
