import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { getInsightMetrics, getInsightAudit, getInsightWorkers, getHumanAgentParticipation, getHumanAgentSlowTail, forceRefreshParticipation, getRequirementDelivery } from './api';
import { putMySetting, deleteMySetting } from '@/features/profile/userSettingApi';
import type { TimeRange, Granularity } from './types';

/** 处理时长目标落在用户级偏好，键与后端 RequirementDeliveryService.TARGET_SETTING_KEY 一致。 */
export const REQUIREMENT_DELIVERY_TARGET_KEY = 'insights.requirementDelivery.targetHours';

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

export function useRequirementDelivery(startDate: string, endDate: string) {
  return useQuery({
    queryKey: ['requirement-delivery', startDate, endDate],
    queryFn: () => getRequirementDelivery({ startDate, endDate }),
    staleTime: 5 * 60 * 1000,
  });
}

/** 保存或清除处理时长目标后失效整组区间查询，让达标口径按新目标立即重算。 */
export function useSaveRequirementDeliveryTarget() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (hours: number | null) => {
      if (hours == null) {
        await deleteMySetting(REQUIREMENT_DELIVERY_TARGET_KEY);
      } else {
        await putMySetting(REQUIREMENT_DELIVERY_TARGET_KEY, String(hours));
      }
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['requirement-delivery'] });
    },
  });
}
