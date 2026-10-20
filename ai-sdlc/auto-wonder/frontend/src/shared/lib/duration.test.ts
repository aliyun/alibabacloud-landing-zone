import { afterEach, describe, expect, it } from 'vitest';
import { i18n } from '@/shared/i18n';
import { formatDurationCompact, formatDuration, formatMinutesCompact, formatMinutes } from './duration';

describe('formatDuration', () => {
  it('formats sub-minute values as seconds', () => {
    expect(formatDuration(0)).toBe('0秒');
    expect(formatDuration(59)).toBe('59秒');
  });

  it('formats sub-hour values as minutes', () => {
    expect(formatDuration(60)).toBe('1分钟');
    expect(formatDuration(90)).toBe('2分钟');
    expect(formatDuration(3540)).toBe('59分钟');
  });

  it('rounds minutes up into hours instead of emitting 60分', () => {
    expect(formatDuration(3599)).toBe('1小时');
    expect(formatDuration(3570)).toBe('1小时');
    expect(formatDuration(7199)).toBe('2小时');
  });

  it('formats multi-hour values with remainder minutes', () => {
    expect(formatDuration(3600)).toBe('1小时');
    expect(formatDuration(3660)).toBe('1小时1分');
    expect(formatDuration(7260)).toBe('2小时1分');
    expect(formatDuration(27240)).toBe('7小时34分');
  });
});

afterEach(async () => { await i18n.changeLanguage('zh-CN'); });

it('uses current English singular/plural units while preserving rounding and compact notation', async () => {
  await i18n.changeLanguage('en-US');
  for (const [seconds, expected] of [[0, '0 seconds'], [1, '1 second'], [2, '2 seconds'], [60, '1 minute'], [90, '2 minutes'], [3570, '1 hour'], [3599, '1 hour'], [3660, '1 hour 1 minute'], [3720, '1 hour 2 minutes'], [7199, '2 hours'], [7260, '2 hours 1 minute']] as const) {
    expect(formatDuration(seconds)).toBe(expected);
  }
  expect(formatMinutes(90)).toBe('1 hour 30 minutes');
  expect(formatDurationCompact(3599)).toBe('1h');
  expect(formatMinutesCompact(90)).toBe('1h 30m');
  await i18n.changeLanguage('zh-CN');
  expect(formatDuration(3660)).toBe('1小时1分');
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
  it('formatMinutes converts hours when >= 60 minutes', () => {
    expect(formatMinutes(34)).toBe('34分钟');
    expect(formatMinutes(60)).toBe('1小时');
    expect(formatMinutes(90)).toBe('1小时30分');
  });

  it('formatMinutesCompact converts hours when >= 60 minutes', () => {
    expect(formatMinutesCompact(34)).toBe('34m');
    expect(formatMinutesCompact(60)).toBe('1h');
    expect(formatMinutesCompact(90)).toBe('1h 30m');
  });
});
