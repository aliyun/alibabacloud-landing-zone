import { apiClient } from '@/shared/api/client';
import type { InsightMetrics, InsightAuditPage, InsightWorker, TimeRange } from './types';

export async function getInsightMetrics(params: {
  workerId?: number;
  timeRange: TimeRange;
}): Promise<InsightMetrics> {
  const resp = await apiClient.get<InsightMetrics>('/api/insights/metrics', {
    params: { worker_id: params.workerId || undefined, time_range: params.timeRange },
  });
  return resp.data;
}

export async function getInsightAudit(params: {
  page: number;
  pageSize: number;
  riskLevel?: string;
  workerId?: number;
  workerName?: string;
  timeRange: TimeRange;
}): Promise<InsightAuditPage> {
  const resp = await apiClient.get<InsightAuditPage>('/api/insights/audit', {
    params: {
      page: params.page,
      page_size: params.pageSize,
      risk_level: params.riskLevel || undefined,
      worker_id: params.workerId,
      time_range: params.timeRange,
    },
  });
  return resp.data;
}

export async function getInsightWorkers(): Promise<InsightWorker[]> {
  const resp = await apiClient.get<InsightWorker[]>('/api/insights/workers');
  return resp.data;
}
