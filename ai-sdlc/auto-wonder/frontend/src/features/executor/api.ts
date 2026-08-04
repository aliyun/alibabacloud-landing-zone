import { apiClient } from '@/shared/api/client';

export interface ExecutorVO {
  id: number;
  agentId: number;
  agentName: string | null;
  name: string;
  status: string;
  clientKind: string;
  lastConnectIp: string | null;
  lastHeartbeat: string | null;
  gmtCreate: string;
}

export interface IssuedExecutorVO {
  id: number;
  agentId: number;
  name: string;
  token: string;
}

export interface CreateExecutorRequest {
  name: string;
  clientKind: string;
}

export async function listExecutors(agentId?: number): Promise<ExecutorVO[]> {
  const resp = await apiClient.get<ExecutorVO[]>(agentId ? `/api/agents/${agentId}/executors` : '/api/executors');
  return resp.data;
}

export async function createExecutor(agentId: number, params: CreateExecutorRequest): Promise<IssuedExecutorVO> {
  const resp = await apiClient.post<IssuedExecutorVO>(`/api/agents/${agentId}/executors`, params);
  return resp.data;
}

export async function getExecutorToken(id: number): Promise<string> {
  const resp = await apiClient.get<string>(`/api/executors/${id}/token`);
  return resp.data;
}

export async function deleteExecutor(id: number): Promise<void> {
  await apiClient.delete(`/api/executors/${id}`);
}
