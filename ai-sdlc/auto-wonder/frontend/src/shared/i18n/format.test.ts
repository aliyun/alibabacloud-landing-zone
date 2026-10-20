import { afterEach, expect, it } from 'vitest';
import { i18n } from './index';
import { formatDateTime } from './format';

afterEach(async () => { await i18n.changeLanguage('zh-CN'); });

it('uses a neutral invalid-date fallback and preserves valid locale and timezone options', async () => {
  const invalid = new Date(NaN);
  const valid = new Date('2026-10-01T00:00:00Z');
  const options: Intl.DateTimeFormatOptions = { timeZone: 'America/Los_Angeles', year: 'numeric', month: 'long', day: 'numeric', hour: 'numeric' };
  for (const locale of ['zh-CN', 'en-US']) {
    await i18n.changeLanguage(locale);
    expect(formatDateTime('')).toBe('—');
    expect(formatDateTime('malformed timestamp')).toBe('—');
    expect(formatDateTime(invalid)).toBe('—');
    expect(formatDateTime(valid, options)).toBe(new Intl.DateTimeFormat(locale, options).format(valid));
    expect(valid.toISOString()).toBe('2026-10-01T00:00:00.000Z');
    expect(formatDateTime(0, options)).toBe(new Intl.DateTimeFormat(locale, options).format(new Date(0)));
  }
});
