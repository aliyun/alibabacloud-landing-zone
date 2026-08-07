import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { getInsightMetrics, getInsightAudit, getInsightWorkers, getHumanAgentParticipation, getHumanAgentSlowTail, forceRefreshParticipation } from './api';
import type { TimeRange, Granularity } from './types';

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

export function useHumanAgentParticipation(startDate: string, endDate: string, granularity: Granularity) {
  return useQuery({
    queryKey: ['human-agent-participation', startDate, endDate, granularity],
    queryFn: () => getHumanAgentParticipation({ startDate, endDate, granularity }),
    staleTime: 10 * 60 * 1000,
  });
}

export function useHumanAgentSlowTail(startDate: string, endDate: string, page: number, pageSize: number) {
  return useQuery({
    queryKey: ['human-agent-slow-tail', startDate, endDate, page, pageSize],
    queryFn: () => getHumanAgentSlowTail({ startDate, endDate, page, pageSize }),
    staleTime: 10 * 60 * 1000,
  });
}

export function useForceRefreshParticipation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: forceRefreshParticipation,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['human-agent-participation'] });
      queryClient.invalidateQueries({ queryKey: ['human-agent-slow-tail'] });
    },
  });
}
