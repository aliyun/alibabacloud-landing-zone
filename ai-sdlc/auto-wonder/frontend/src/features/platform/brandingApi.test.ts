import { describe, it, expect } from 'vitest';
import { DEFAULT_BRANDING } from './brandingApi';

describe('DEFAULT_BRANDING', () => {
  it('pins the recommended runtime version consumers fall back to', () => {
    expect(DEFAULT_BRANDING.recommendedRuntimeVersion).toBe('0.2.138');
  });
});
