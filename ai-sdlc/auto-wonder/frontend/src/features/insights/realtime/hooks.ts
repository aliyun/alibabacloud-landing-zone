import { useQuery } from '@tanstack/react-query';
import {
  getAgentRunning,
  getRealtimeDashboard,
  getTodayCompleted,
  getWeekCompleted,
  getRunningAll,
} from './api';

export type RefreshInterval = 10000 | 15000 | 30000 | false;

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
