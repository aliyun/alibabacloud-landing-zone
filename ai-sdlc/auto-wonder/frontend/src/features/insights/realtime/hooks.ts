import { useQuery } from '@tanstack/react-query';
import {
  getAgentRunning,
  getRealtimeDashboard,
  getTodayCompleted,
  getWeekCompleted,
  getRunningAll,
} from './api';
import { useInsightMetrics, useInsightWorkers } from '../hooks';
import { useInsightPreference } from '../useInsightPreference';
import type { TimeRange } from '../types';

export type RefreshInterval = 10000 | 15000 | 30000 | false;

// 成本数据用独立于 audit 的偏好 key，且不接 refetchInterval：看板 10/15/30s 的自动刷新
// 不应放大后端聚合压力，成本只在初次进入、筛选变化或手动刷新时取数。
export function useRealtimeCost() {
  const [workerId, setWorkerId] = useInsightPreference<number | undefined>(
    'realtime.cost.worker',
    undefined,
    (value) => typeof value === 'number' && Number.isSafeInteger(value) && value > 0,
  );
  const [timeRange, setTimeRange] = useInsightPreference<TimeRange>(
    'realtime.cost.range',
    '30d',
    (value) => value === '7d' || value === '30d' || value === '90d',
  );
  const metricsQuery = useInsightMetrics(workerId, timeRange);
  const workersQuery = useInsightWorkers();

  return {
    workerId,
    setWorkerId,
    timeRange,
    setTimeRange,
    workers: workersQuery.data ?? [],
    metrics: metricsQuery.data,
    isLoading: metricsQuery.isLoading,
    isError: metricsQuery.isError,
    error: (metricsQuery.error as Error | null) ?? null,
    refetch: metricsQuery.refetch,
  };
}

export function useRealtimeDashboard(refreshInterval: RefreshInterval) {
  return useQuery({
    queryKey: ['dashboard-realtime'],
    queryFn: getRealtimeDashboard,
    refetchInterval: refreshInterval,
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

export function useRunningAll(enabled: boolean) {
  return useQuery({
    queryKey: ['dashboard-running-all'],
    queryFn: getRunningAll,
    enabled,
    staleTime: 0,
  });
}
