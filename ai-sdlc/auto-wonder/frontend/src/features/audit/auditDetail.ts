import dayjs from 'dayjs';
import type { AuditLog } from './api';

export interface AuditDetail {
  actorType?: string;
  triggerType?: string;
  triggerSource?: string;
  eventType?: string;
  [key: string]: unknown;
}

export function parseDetail(record: AuditLog): AuditDetail {
  const raw = record.detailJson || record.detail;
  if (!raw) {
    return {};
  }
  try {
    const parsed = JSON.parse(raw);
    return parsed && typeof parsed === 'object' ? parsed : {};
  } catch {
    return {};
  }
}

export function detailText(record: AuditLog): string {
  const detail = parseDetail(record);
  if (Object.keys(detail).length === 0) {
    return record.detail || record.detailJson || '-';
  }
  const fields = ['message', 'error', 'stepName', 'path', 'status'];
  const summary = fields
    .filter((field) => detail[field] !== undefined && detail[field] !== null && detail[field] !== '')
    .map((field) => `${field}: ${String(detail[field])}`)
    .join('；');
  return summary || String(detail.eventType || '-');
}

export function triggerText(record: AuditLog): string {
  const detail = parseDetail(record);
  const parts = [detail.triggerType, detail.triggerSource].filter(Boolean).map(String);
  return parts.length > 0 ? parts.join(' / ') : '-';
}

export function auditTime(value: string | null | undefined): string {
  if (!value) {
    return '-';
  }
  const parsed = dayjs(value);
  return parsed.isValid() ? parsed.format('YYYY-MM-DD HH:mm:ss') : '-';
}
