import { apiClient } from '@/shared/api/client';

export interface FeishuBinding {
  id: number;
  appId: string;
  agentId: number;
  status: 'ENABLED' | 'DISABLED';
  version: number;
  encryptKeyConfigured: boolean;
  callbackUrl: string;
  lastSuccessAt: string | null;
  lastError: string | null;
}
export interface FeishuBindingRequest {
  appId: string;
  agentId: number;
  status?: 'ENABLED' | 'DISABLED';
  version?: number;
  appSecret?: string;
  verificationToken?: string;
  encryptKey?: string;
  clearEncryptKey?: boolean;
}
const BASE = '/api/integrations/feishu/bindings';
export async function listFeishuBindings(): Promise<FeishuBinding[]> {
  return (await apiClient.get<FeishuBinding[]>(BASE)).data;
}
export async function saveFeishuBinding(id: number | undefined, body: FeishuBindingRequest): Promise<FeishuBinding> {
  return (id == null ? await apiClient.post<FeishuBinding>(BASE, body) : await apiClient.put<FeishuBinding>(`${BASE}/${id}`, body)).data;
}
export async function deleteFeishuBinding(id: number): Promise<void> {
  await apiClient.delete(`${BASE}/${id}`);
}
