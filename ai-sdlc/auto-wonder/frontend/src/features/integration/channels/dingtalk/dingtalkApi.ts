import { apiClient } from '@/shared/api/client';

export const DINGTALK_DEFAULT_BASE_URL = 'https://api.dingtalk.com';

export type BindingStatus = 'ENABLED' | 'DISABLED';
export type BindingMode = 'SHARED' | 'LEGACY';
export type TransportMode = 'HTTP_CALLBACK' | 'STREAM';
export type StreamEnv = 'ONLINE';
export type StreamStatus = 'CONNECTED' | 'CONNECTING' | 'FAILED' | 'NOT_CONNECTED';

export interface DingTalkBinding {
  id: number;
  bindingMode: BindingMode;
  platformConfigId: number | null;
  groupId: string | null;
  routeVersion: number;
  version: number;
  appKey: string;
  appSecretMasked: string | null;
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

/** Legacy standalone-robot payload; only accepted when editing a stored LEGACY binding. */
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

/** Shared group-chat payload; only needs the external group and the linked digital worker. */
export interface DingTalkSharedBindingRequest {
  bindingMode?: 'SHARED';
  groupId: string;
  agentId: number;
  status?: BindingStatus;
  version?: number;
}

export type DingTalkBindingPayload = DingTalkBindingRequest | DingTalkSharedBindingRequest;

const BASE = '/api/integrations/dingtalk/bindings';

export async function listDingTalkBindings(): Promise<DingTalkBinding[]> {
  const resp = await apiClient.get<DingTalkBinding[]>(BASE);
  return resp.data;
}

export async function createDingTalkBinding(
  body: DingTalkBindingPayload,
): Promise<DingTalkBinding> {
  const resp = await apiClient.post<DingTalkBinding>(BASE, body);
  return resp.data;
}

export async function updateDingTalkBinding(
  id: number,
  body: DingTalkBindingPayload,
): Promise<DingTalkBinding> {
  const resp = await apiClient.put<DingTalkBinding>(`${BASE}/${id}`, body);
  return resp.data;
}

export async function deleteDingTalkBinding(id: number): Promise<void> {
  await apiClient.delete(`${BASE}/${id}`);
}
