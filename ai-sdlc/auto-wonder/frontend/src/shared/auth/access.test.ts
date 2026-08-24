import { describe, expect, it } from 'vitest';
import { allows } from './access';

describe('workspace access levels', () => {
  it('orders read only below read write below admin', () => {
    expect(allows('READ_ONLY', 'READ_ONLY')).toBe(true);
    expect(allows('READ_ONLY', 'READ_WRITE')).toBe(false);
    expect(allows('READ_WRITE', 'READ_ONLY')).toBe(true);
    expect(allows('READ_WRITE', 'READ_WRITE')).toBe(true);
    expect(allows('READ_WRITE', 'ADMIN')).toBe(false);
    expect(allows('ADMIN', 'READ_ONLY')).toBe(true);
    expect(allows('ADMIN', 'READ_WRITE')).toBe(true);
    expect(allows('ADMIN', 'ADMIN')).toBe(true);
  });
});
