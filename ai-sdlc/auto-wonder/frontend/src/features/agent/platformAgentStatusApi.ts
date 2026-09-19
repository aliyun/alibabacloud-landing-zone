import { apiClient } from '@/shared/api/client';

export type PlatformAgentState = 'OK' | 'OFFLINE' | 'NOT_CONFIGURED';

export interface PlatformAgentStatus {
  state: PlatformAgentState;
  agentId: number | null;
  executorCount: number;
  onlineExecutorCount: number;
}

export function platformAgentStatusQueryKey(workspaceId: number | null) {
  return ['platform-agent', 'status', workspaceId] as const;
}

export async function getPlatformAgentStatus(): Promise<PlatformAgentStatus> {
  const resp = await apiClient.get<PlatformAgentStatus>('/api/platform-agent/status');
  return resp.data;
}
