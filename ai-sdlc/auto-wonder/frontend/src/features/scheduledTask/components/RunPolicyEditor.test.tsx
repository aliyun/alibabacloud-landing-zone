import { describe, expect, it } from 'vitest';
import { normalizeRunPolicy } from './RunPolicyEditor';

describe('normalizeRunPolicy', () => {
  it('disallows ALLOW and requires positive affinity for continuous sessions', () => {
    expect(normalizeRunPolicy({ sessionMode: 'CONTINUOUS', overlapPolicy: 'ALLOW', affinityTimeoutSeconds: 0, startDeadlineSeconds: 0 }))
      .toEqual({ sessionMode: 'CONTINUOUS', overlapPolicy: 'SKIP', affinityTimeoutSeconds: 1, startDeadlineSeconds: 1 });
  });
});
