import { i18n } from '@/shared/i18n';

function splitMinutes(seconds: number): { h: number; m: number } {
  const totalMin = Math.round(seconds / 60);
  return { h: Math.floor(totalMin / 60), m: totalMin % 60 };
}

export function formatDuration(seconds: number): string {
  if (seconds < 60) return i18n.t('duration.second', { count: seconds });
  const { h, m } = splitMinutes(seconds);
  if (h === 0) return i18n.t('duration.minute', { count: Math.round(seconds / 60) });
  const hours = i18n.t('duration.hour', { count: h });
  return m > 0 ? i18n.t('duration.hoursMinutes', { hours, minutes: i18n.t('duration.remainderMinute', { count: m }) }) : hours;
}

export function formatDurationCompact(seconds: number): string {
  if (seconds < 60) return `${seconds}s`;
  const { h, m } = splitMinutes(seconds);
  if (h === 0) return `${Math.round(seconds / 60)}m`;
  return m > 0 ? `${h}h ${m}m` : `${h}h`;
}

export function formatMinutes(minutes: number): string {
  return formatDuration(minutes * 60);
}

export function formatMinutesCompact(minutes: number): string {
  return formatDurationCompact(minutes * 60);
}
