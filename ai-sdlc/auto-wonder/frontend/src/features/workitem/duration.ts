import { i18n } from '@/shared/i18n';

export function formatDuration(ms: number | null | undefined): string {
  if (ms == null || ms < 0) {
    return '';
  }
  const totalSeconds = Math.round(ms / 1000);
  if (totalSeconds < 60) {
    return i18n.t('workitem:duration.seconds', { seconds: totalSeconds });
  }
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  if (minutes < 60) {
    return seconds > 0 ? i18n.t('workitem:duration.minutesSeconds', { minutes, seconds }) : i18n.t('workitem:duration.minutes', { minutes });
  }
  const hours = Math.floor(minutes / 60);
  const remMinutes = minutes % 60;
  return remMinutes > 0 ? i18n.t('workitem:duration.hoursMinutes', { hours, minutes: remMinutes }) : i18n.t('workitem:duration.hours', { hours });
}
