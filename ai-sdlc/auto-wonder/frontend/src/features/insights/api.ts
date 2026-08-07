import { apiClient } from '@/shared/api/client';
import type { InsightMetrics, InsightAuditPage, InsightWorker, TimeRange, Granularity, HumanAgentParticipation, HumanAgentSlowTailPage } from './types';

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

export async function getHumanAgentParticipation(params: {
  startDate: string;
  endDate: string;
  granularity: Granularity;
}): Promise<HumanAgentParticipation> {
  const resp = await apiClient.get<HumanAgentParticipation>('/api/insights/human-agent-participation', {
    params: { start_date: params.startDate, end_date: params.endDate, granularity: params.granularity },
  });
  return resp.data;
}

export async function getHumanAgentSlowTail(params: {
  startDate: string;
  endDate: string;
  page: number;
  pageSize: number;
}): Promise<HumanAgentSlowTailPage> {
  const resp = await apiClient.get<HumanAgentSlowTailPage>('/api/insights/human-agent-participation/slowest', {
    params: { start_date: params.startDate, end_date: params.endDate, page: params.page, page_size: params.pageSize },
  });
  return resp.data;
}

export async function forceRefreshParticipation(): Promise<void> {
  await apiClient.post('/api/insights/human-agent-participation/refresh');
}
