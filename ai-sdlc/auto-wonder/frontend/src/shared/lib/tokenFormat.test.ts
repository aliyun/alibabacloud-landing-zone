import { describe, it, expect } from 'vitest';
import { formatTokenCount, formatCredits, formatWithCommas } from './tokenFormat';

describe('formatTokenCount', () => {
  it('returns "0" for null/undefined/negative', () => {
    expect(formatTokenCount(null)).toBe('0');
    expect(formatTokenCount(undefined)).toBe('0');
    expect(formatTokenCount(-1)).toBe('0');
  });

  it('returns raw number for values < 1000', () => {
    expect(formatTokenCount(0)).toBe('0');
    expect(formatTokenCount(37)).toBe('37');
    expect(formatTokenCount(999)).toBe('999');
  });

  it('formats thousands with K suffix', () => {
    expect(formatTokenCount(1000)).toBe('1K');
    expect(formatTokenCount(1500)).toBe('1.5K');
    expect(formatTokenCount(18300)).toBe('18.3K');
    expect(formatTokenCount(999999)).toBe('1000K');
  });

  it('formats millions with M suffix', () => {
    expect(formatTokenCount(1_000_000)).toBe('1M');
    expect(formatTokenCount(2_100_000)).toBe('2.1M');
  });

  it('trims trailing .0', () => {
    expect(formatTokenCount(2000)).toBe('2K');
    expect(formatTokenCount(3_000_000)).toBe('3M');
  });
});

describe('formatCredits', () => {
  it('returns "0" for null/undefined/zero/negative', () => {
    expect(formatCredits(null)).toBe('0');
    expect(formatCredits(undefined)).toBe('0');
    expect(formatCredits(0)).toBe('0');
    expect(formatCredits(-1)).toBe('0');
  });

  it('returns "<0.01" for very small values', () => {
    expect(formatCredits(0.001)).toBe('<0.01');
    expect(formatCredits(0.009)).toBe('<0.01');
  });

  it('formats normal values with up to 2 decimals', () => {
    expect(formatCredits(1.5)).toBe('1.5');
    expect(formatCredits(2.39)).toBe('2.39');
    expect(formatCredits(3.0)).toBe('3');
    expect(formatCredits(0.10)).toBe('0.1');
  });
});

describe('formatWithCommas', () => {
  it('formats numbers with thousands separators', () => {
    expect(formatWithCommas(0)).toBe('0');
    expect(formatWithCommas(1000)).toBe('1,000');
    expect(formatWithCommas(87665)).toBe('87,665');
    expect(formatWithCommas(1234567)).toBe('1,234,567');
  });
});
