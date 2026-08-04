import { useQuery } from '@tanstack/react-query';
import { getInsightMetrics, getInsightAudit, getInsightWorkers } from './api';
import type { TimeRange } from './types';

export function useInsightMetrics(workerId: number | undefined, timeRange: TimeRange) {
  return useQuery({
    queryKey: ['insight-metrics', workerId, timeRange],
    queryFn: () => getInsightMetrics({ workerId, timeRange }),
    staleTime: 5 * 60 * 1000,
  });
}

export function useInsightAudit(
  page: number,
  pageSize: number,
  riskLevel: string,
  workerId: number | undefined,
  workerName: string,
  timeRange: TimeRange,
) {
  return useQuery({
    queryKey: ['insight-audit', page, pageSize, riskLevel, workerId, workerName, timeRange],
    queryFn: () => getInsightAudit({ page, pageSize, riskLevel: riskLevel || undefined, workerId, workerName, timeRange }),
    staleTime: 60 * 1000,
  });
}

export function useInsightWorkers() {
  return useQuery({
    queryKey: ['insight-workers'],
    queryFn: getInsightWorkers,
    staleTime: 10 * 60 * 1000,
  });
}
