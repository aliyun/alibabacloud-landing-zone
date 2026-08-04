export interface StatusMeta {
  label: string;
  color: string;
}

const META: Record<string, StatusMeta> = {
  SUCCEEDED: { label: 'SUCCEEDED', color: 'success' },
  FAILED: { label: 'FAILED', color: 'error' },
  RUNNING: { label: 'RUNNING', color: 'orange' },
  PENDING: { label: 'PENDING', color: 'default' },
  PACKAGING: { label: 'PACKAGING', color: 'default' },
  DISPATCHED: { label: 'DISPATCHED', color: 'default' },
  ACKED: { label: 'ACKED', color: 'default' },
  TIMEOUT: { label: 'TIMEOUT', color: 'default' },
  CANCELED: { label: 'CANCELED', color: 'default' },
};

export function statusMeta(status: string): StatusMeta {
  return META[status] ?? { label: status, color: 'default' };
}

export const HAPPY_PATH: string[] = ['PENDING', 'PACKAGING', 'DISPATCHED', 'ACKED', 'RUNNING', 'SUCCEEDED'];
export const FAILURE_STATES: string[] = ['FAILED', 'TIMEOUT', 'CANCELED'];

export const ACCENT = '#ff6a00';
