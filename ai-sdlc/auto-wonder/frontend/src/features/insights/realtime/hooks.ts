import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import {
  getAgentRunning,
  getOverview,
  getPipelineHealth,
  getRealtimeCost,
  getRealtimeDashboard,
  getTodayCompleted,
  getWeekCompleted,
  getRunningWorkitems,
} from './api';
import type { CostComposition, PipelineRange } from './api';
import { useInsightWorkers } from '../hooks';
import { useInsightPreference } from '../useInsightPreference';
import type { TimeRange } from '../types';

// 成本数据用独立于 audit 的偏好 key，且不接 refetchInterval：看板 10/15/30s 的自动刷新
// 不应放大后端聚合压力，成本只在初次进入、筛选变化或手动刷新时取数。
// 统计块按「员工 × 日期」取 kpi.cost；来源构成只随日期刷新，切员工时保留已有快照。
export function useRealtimeCost() {
  const [workerId, setWorkerId] = useInsightPreference<number | undefined>(
    'realtime.cost.worker',
    undefined,
    (value) => typeof value === 'number' && Number.isSafeInteger(value) && value > 0,
  );
  const [timeRange, setTimeRange] = useInsightPreference<TimeRange>(
    'realtime.cost.range',
    '7d',
    (value) => value === '7d' || value === '30d' || value === '90d',
  );
  const range = Number(timeRange.slice(0, -1));
  const costQuery = useQuery({
    queryKey: ['dashboard-realtime-cost', range, workerId ?? 'all'],
    queryFn: () => getRealtimeCost(range, workerId ?? 'all'),
    placeholderData: keepPreviousData,
    staleTime: 5 * 60 * 1000,
  });
  const workersQuery = useInsightWorkers();
  const responseComposition = costQuery.data?.costComposition ?? null;
  const [composition, setComposition] = useState<CostComposition | null>(null);

  // 服务端对同一 range 恒定返回全员口径；只有日期变化才覆盖快照，
  // 切员工时即使响应里带着 costComposition 也显式丢弃。
  useEffect(() => {
    if (!responseComposition) return;
    setComposition(previous => (previous?.range === responseComposition.range ? previous : responseComposition));
  }, [responseComposition]);

  return {
    workerId,
    setWorkerId,
    timeRange,
    setTimeRange,
    workers: workersQuery.data ?? [],
    cost: costQuery.data?.kpi?.cost ?? undefined,
    composition,
    isLoading: costQuery.isLoading,
    isError: costQuery.isError,
    error: (costQuery.error as Error | null) ?? null,
    refetch: costQuery.refetch,
  };
}

export function useOverviewDashboard() {
  return useQuery({
    queryKey: ['dashboard-overview'],
    queryFn: getOverview,
    refetchInterval: 30000,
    staleTime: 0,
  });
}

export function usePipelineHealth(range: PipelineRange) {
  return useQuery({
    queryKey: ['dashboard-pipeline-health', range],
    queryFn: () => getPipelineHealth(range),
    placeholderData: keepPreviousData,
    staleTime: 0,
  });
}

export function useRealtimeDashboard() {
  return useQuery({
    queryKey: ['dashboard-realtime'],
    queryFn: getRealtimeDashboard,
    refetchInterval: 15000,
    staleTime: 0,
  });
}

export function useAgentRunning(agentId: number | null) {
  return useQuery({
    queryKey: ['dashboard-agent-running', agentId],
    queryFn: () => getAgentRunning(agentId as number),
    enabled: agentId != null,
    staleTime: 0,
  });
}

export function useTodayCompleted(enabled: boolean) {
  return useQuery({
    queryKey: ['dashboard-completed-today'],
    queryFn: getTodayCompleted,
    enabled,
    staleTime: 0,
  });
}

export function useWeekCompleted(enabled: boolean) {
  return useQuery({
    queryKey: ['dashboard-completed-week'],
    queryFn: getWeekCompleted,
    enabled,
    staleTime: 0,
  });
}

export function useRunningWorkitems(enabled: boolean) {
  return useQuery({
    queryKey: ['dashboard-running-workitems'],
    queryFn: getRunningWorkitems,
    enabled,
    staleTime: 0,
  });
}
