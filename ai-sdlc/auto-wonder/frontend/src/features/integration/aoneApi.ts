import { apiClient } from '@/shared/api/client';

export interface AoneBindingRequest {
  baseUrl?: string;
  clientKey: string;
  accessSecret: string;
  regionId?: string;
  externalProjectId?: string;
  externalProjectName?: string;
  writebackStaffId?: string;
  pollIntervalSeconds?: number;
  enabled?: boolean;
}

export interface AoneBinding {
  id: string;
  provider: string;
  externalProjectId: string;
  externalProjectName: string | null;
  baseUrl: string;
  clientKey: string;
  credentialMasked: string;
  regionId: string;
  writebackStaffId: string | null;
  pollIntervalSeconds: number;
  enabled: boolean;
  lastSuccessAt: string | null;
  lastError: string | null;
  reusedExistingBinding?: boolean;
  statusTemplateSynced?: boolean;
}

export interface AoneTestConnectionResult {
  success: boolean;
  message: string;
  checks: string[];
}

export interface AoneSyncResult {
  imported: number;
  updated: number;
  commentsImported: number;
  workitemIds: string[];
}

export interface ExternalProject {
  externalId: string;
  name: string | null;
  rawJson: string;
}

export interface PageResult<T> {
  items: T[];
  page: number;
  pageSize: number;
  totalCount: number;
}

export interface IntegrationCapabilities {
  aoneEnabled: boolean;
}

export async function getIntegrationCapabilities(): Promise<IntegrationCapabilities> {
  const resp = await apiClient.get<IntegrationCapabilities>('/api/integrations/capabilities');
  return resp.data;
}

export async function listAoneBindings(): Promise<AoneBinding[]> {
  const resp = await apiClient.get<AoneBinding[]>('/api/integrations/aone/bindings', {
    params: { page: 1, size: 50 },
  });
  return resp.data;
}

export async function testAoneConnection(data: AoneBindingRequest): Promise<AoneTestConnectionResult> {
  const resp = await apiClient.post<AoneTestConnectionResult>('/api/integrations/aone/bindings/test', data);
  return resp.data;
}

export async function createAoneBinding(data: AoneBindingRequest): Promise<AoneBinding> {
  const resp = await apiClient.post<AoneBinding>('/api/integrations/aone/bindings', data);
  return resp.data;
}

export async function searchAoneProjects(data: AoneBindingRequest, query: string): Promise<PageResult<ExternalProject>> {
  const resp = await apiClient.post<PageResult<ExternalProject>>('/api/integrations/aone/projects/search', data, {
    params: { q: query, page: 1, size: 20 },
  });
  return resp.data;
}

export async function syncAoneNow(bindingId: string, issueIds: string[]): Promise<AoneSyncResult> {
  const resp = await apiClient.post<AoneSyncResult>(`/api/integrations/aone/bindings/${bindingId}/sync-now`, {
    issueIds,
  });
  return resp.data;
}

export async function dispatchAoneOutbox(): Promise<number> {
  const resp = await apiClient.post<number>('/api/integrations/aone/outbox/dispatch-now');
  return resp.data;
}
