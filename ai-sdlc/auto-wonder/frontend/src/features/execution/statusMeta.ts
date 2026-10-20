import { i18n } from '@/shared/i18n';

export interface StatusMeta {
  label: string;
  color: string;
}

const META = {
  SUCCEEDED: { key: 'status.SUCCEEDED', color: 'success' },
  FAILED: { key: 'status.FAILED', color: 'error' },
  RUNNING: { key: 'status.RUNNING', color: 'orange' },
  PENDING: { key: 'status.PENDING', color: 'default' },
  PACKAGING: { key: 'status.PACKAGING', color: 'default' },
  DISPATCHED: { key: 'status.DISPATCHED', color: 'default' },
  ACKED: { key: 'status.ACKED', color: 'default' },
  TIMEOUT: { key: 'status.TIMEOUT', color: 'default' },
  CANCELED: { key: 'status.CANCELED', color: 'default' },
} as const;

export function statusMeta(status: string): StatusMeta {
  if (!Object.prototype.hasOwnProperty.call(META, status)) return { label: status, color: 'default' };
  const meta = META[status as keyof typeof META];
  return { label: i18n.t(`execution:${meta.key}`), color: meta.color };
}

export const HAPPY_PATH: string[] = ['PENDING', 'PACKAGING', 'DISPATCHED', 'ACKED', 'RUNNING', 'SUCCEEDED'];
export const FAILURE_STATES: string[] = ['FAILED', 'TIMEOUT', 'CANCELED'];

export const ACCENT = 'var(--aw-accent-text)';
