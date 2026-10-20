import { i18n, normalizeLocale } from './index';
export function formatDateTime(value: string | number | Date, options?: Intl.DateTimeFormatOptions): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '—';
  return new Intl.DateTimeFormat(normalizeLocale(i18n.language), options ?? { dateStyle: 'medium', timeStyle: 'short' }).format(date);
}
export function formatNumber(value: number, options?: Intl.NumberFormatOptions): string {
  return new Intl.NumberFormat(normalizeLocale(i18n.language), options).format(value);
}
