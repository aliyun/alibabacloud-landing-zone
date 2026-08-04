import { apiClient } from '@/shared/api/client';

export const DINGTALK_DEFAULT_BASE_URL = 'https://api.dingtalk.com';

export type BindingStatus = 'ENABLED' | 'DISABLED';
export type TransportMode = 'HTTP_CALLBACK' | 'STREAM';
export type StreamEnv = 'ONLINE';
export type StreamStatus = 'CONNECTED' | 'CONNECTING' | 'FAILED' | 'NOT_CONNECTED';

export interface DingTalkBinding {
  id: number;
  appKey: string;
  appSecretMasked: string;
  robotCode: string;
  agentId: number;
  transportMode: TransportMode;
  streamEnv: StreamEnv;
  streamStatus: StreamStatus;
  streamError: string | null;
  streamStatusUpdatedAt: number | null;
  baseUrl: string | null;
  regionId: string | null;
  status: BindingStatus;
  lastSuccessAt: string | null;
  lastError: string | null;
  callbackUrl: string | null;
}

export interface DingTalkBindingRequest {
  appKey: string;
  appSecret?: string;
  robotCode: string;
  agentId: number;
  transportMode?: TransportMode;
  streamEnv?: StreamEnv;
  callbackToken?: string;
  baseUrl?: string;
  regionId?: string;
  status?: BindingStatus;
}

const BASE = '/api/integrations/dingtalk/bindings';

export async function listDingTalkBindings(): Promise<DingTalkBinding[]> {
  const resp = await apiClient.get<DingTalkBinding[]>(BASE);
  return resp.data;
}

export async function createDingTalkBinding(
  body: DingTalkBindingRequest,
): Promise<DingTalkBinding> {
  const resp = await apiClient.post<DingTalkBinding>(BASE, body);
  return resp.data;
}

export async function updateDingTalkBinding(
  id: number,
  body: DingTalkBindingRequest,
): Promise<DingTalkBinding> {
  const resp = await apiClient.put<DingTalkBinding>(`${BASE}/${id}`, body);
  return resp.data;
}

export async function deleteDingTalkBinding(id: number): Promise<void> {
  await apiClient.delete(`${BASE}/${id}`);
}
