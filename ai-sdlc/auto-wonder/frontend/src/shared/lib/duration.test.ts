import { describe, expect, it } from 'vitest';
import { formatDurationCompact, formatDurationZh, formatMinutesCompact, formatMinutesZh } from './duration';

describe('formatDurationZh', () => {
  it('formats sub-minute values as seconds', () => {
    expect(formatDurationZh(0)).toBe('0秒');
    expect(formatDurationZh(59)).toBe('59秒');
  });

  it('formats sub-hour values as minutes', () => {
    expect(formatDurationZh(60)).toBe('1分钟');
    expect(formatDurationZh(90)).toBe('2分钟');
    expect(formatDurationZh(3540)).toBe('59分钟');
  });

  it('rounds minutes up into hours instead of emitting 60分', () => {
    expect(formatDurationZh(3599)).toBe('1小时');
    expect(formatDurationZh(3570)).toBe('1小时');
    expect(formatDurationZh(7199)).toBe('2小时');
  });

  it('formats multi-hour values with remainder minutes', () => {
    expect(formatDurationZh(3600)).toBe('1小时');
    expect(formatDurationZh(3660)).toBe('1小时1分');
    expect(formatDurationZh(7260)).toBe('2小时1分');
    expect(formatDurationZh(27240)).toBe('7小时34分');
  });
});

describe('formatDurationCompact', () => {
  it('formats sub-minute values as seconds', () => {
    expect(formatDurationCompact(0)).toBe('0s');
    expect(formatDurationCompact(59)).toBe('59s');
  });

  it('formats sub-hour values as minutes', () => {
    expect(formatDurationCompact(60)).toBe('1m');
    expect(formatDurationCompact(3540)).toBe('59m');
  });

  it('rounds minutes up into hours instead of emitting 60m', () => {
    expect(formatDurationCompact(3599)).toBe('1h');
    expect(formatDurationCompact(7199)).toBe('2h');
  });

  it('formats multi-hour values with remainder minutes using a space', () => {
    expect(formatDurationCompact(3600)).toBe('1h');
    expect(formatDurationCompact(3900)).toBe('1h 5m');
    expect(formatDurationCompact(7260)).toBe('2h 1m');
  });
});

describe('minutes-based wrappers', () => {
  it('formatMinutesZh converts hours when >= 60 minutes', () => {
    expect(formatMinutesZh(34)).toBe('34分钟');
    expect(formatMinutesZh(60)).toBe('1小时');
    expect(formatMinutesZh(90)).toBe('1小时30分');
  });

  it('formatMinutesCompact converts hours when >= 60 minutes', () => {
    expect(formatMinutesCompact(34)).toBe('34m');
    expect(formatMinutesCompact(60)).toBe('1h');
    expect(formatMinutesCompact(90)).toBe('1h 30m');
  });
});
