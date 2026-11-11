import { describe, expect, it } from 'vitest';
import { smoothTrend } from './trend';

describe('delivery trend', () => {
  it('does not invent samples or extrapolate partial buckets', () => {
    expect(smoothTrend([1, null, 4])).toEqual([null, null, null]);
    const fitted = smoothTrend([99, 1, 5, 2, 8, 4], [true, false, false, false, false, false]);
    expect(fitted[0]).toBeNull();
    expect(fitted.slice(1).every(v => v != null && v >= 0)).toBe(true);
  });
  it('fits locally rather than drawing a straight global regression', () => {
    const values = smoothTrend([1, 2, 5, 10, 6, 2, 1]);
    expect(values[3]!).toBeGreaterThan(values[0]!);
    expect(values[3]!).toBeGreaterThan(values[6]!);
  });
});
