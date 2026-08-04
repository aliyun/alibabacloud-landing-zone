import { apiClient } from '@/shared/api/client';

export interface AuditLog {
  id: number;
  module: string;
  action: string;
  actorId: number;
  actorType?: string | null;
  actorName?: string | null;
  targetType: string | null;
  targetId: number | null;
  detail?: string | null;
  detailJson?: string | null;
  gmtCreate: string;
}

export interface AuditLogFilters {
  page: number;
  size: number;
  module?: string;
  action?: string;
  actorId?: number;
  targetType?: string;
  targetId?: number;
  startTime?: string;
  endTime?: string;
  keyword?: string;
}

type AuditLogListResponse = AuditLog[] | {
  data?: AuditLog[];
  list?: AuditLog[];
};

function normalizeAuditLogs(value: AuditLogListResponse): AuditLog[] {
  if (Array.isArray(value)) {
    return value;
  }
  if (Array.isArray(value.list)) {
    return value.list;
  }
  if (Array.isArray(value.data)) {
    return value.data;
  }
  return [];
}

export async function listAuditLogs(params: AuditLogFilters): Promise<AuditLog[]> {
  const resp = await apiClient.get<AuditLogListResponse>('/api/audit-logs', { params });
  return normalizeAuditLogs(resp.data);
}

export async function countAuditLogs(params: Omit<AuditLogFilters, 'page' | 'size'>): Promise<number> {
  const resp = await apiClient.get<number>('/api/audit-logs/count', { params });
  return resp.data;
}
