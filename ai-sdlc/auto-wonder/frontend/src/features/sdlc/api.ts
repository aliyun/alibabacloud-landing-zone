import { apiClient } from '@/shared/api/client';

export type SdlcId = string | number;

export interface SdlcTemplate {
  id: SdlcId;
  name: string;
  description: string;
  workType: string | null;
  status: 'DRAFT' | 'ENABLED' | 'DISABLED';
  isDefault: number;
  entryStepId: SdlcId | null;
  version: number;
  gmtCreate: string;
  steps: SdlcStep[];
}

export interface SdlcStep {
  id: SdlcId;
  sdlcId: SdlcId;
  stepOrder: number;
  name: string;
  kind: string | null;
  instructionMd: string | null;
  checklistJson: string | null;
  gatePolicyJson: string | null;
  required: boolean;
  timeoutSeconds: number | null;
  retryBudget: number | null;
}

export async function listSdlcTemplates(params: { page: number; size: number; workType?: string; status?: string }): Promise<SdlcTemplate[]> {
  const resp = await apiClient.get<SdlcTemplate[]>('/api/sdlcs', { params });
  return resp.data;
}

export async function getSdlcTemplate(id: SdlcId): Promise<SdlcTemplate> {
  const resp = await apiClient.get<SdlcTemplate>(`/api/sdlcs/${id}`);
  return resp.data;
}

export async function createSdlcTemplate(data: { name: string; description?: string; workType?: string }): Promise<SdlcTemplate> {
  const resp = await apiClient.post<SdlcTemplate>('/api/sdlcs', data);
  return resp.data;
}

export async function updateSdlcTemplate(id: SdlcId, data: { name?: string; description?: string; workType?: string }): Promise<SdlcTemplate> {
  const resp = await apiClient.put<SdlcTemplate>(`/api/sdlcs/${id}`, data);
  return resp.data;
}

export async function deleteSdlcTemplate(id: SdlcId): Promise<void> {
  await apiClient.delete(`/api/sdlcs/${id}`);
}

export async function enableSdlcTemplate(id: SdlcId): Promise<SdlcTemplate> {
  const resp = await apiClient.post<SdlcTemplate>(`/api/sdlcs/${id}/enable`);
  return resp.data;
}

export async function disableSdlcTemplate(id: SdlcId): Promise<void> {
  await apiClient.post(`/api/sdlcs/${id}/disable`);
}

export interface CreateStepParams {
  stepOrder?: number;
  name: string;
  kind?: string;
  instructionMd?: string;
  checklistJson?: string;
  gatePolicyJson?: string;
  required?: boolean;
  timeoutSeconds?: number | null;
  retryBudget?: number | null;
}

export async function addStep(sdlcId: SdlcId, data: CreateStepParams): Promise<SdlcStep> {
  const resp = await apiClient.post<SdlcStep>(`/api/sdlcs/${sdlcId}/steps`, data);
  return resp.data;
}

export async function updateStep(sdlcId: SdlcId, stepId: SdlcId, data: Partial<CreateStepParams>): Promise<SdlcStep> {
  const resp = await apiClient.put<SdlcStep>(`/api/sdlcs/${sdlcId}/steps/${stepId}`, data);
  return resp.data;
}

export async function deleteStep(sdlcId: SdlcId, stepId: SdlcId): Promise<void> {
  await apiClient.delete(`/api/sdlcs/${sdlcId}/steps/${stepId}`);
}

export async function reorderSteps(sdlcId: SdlcId, stepIds: SdlcId[]): Promise<void> {
  await apiClient.put(`/api/sdlcs/${sdlcId}/steps/reorder`, { stepIds });
}

// --- Squad Template Gallery ---

export interface SquadTemplateItem {
  id: number;
  name: string;
  description: string;
  squadSize: number;
  icon: string | null;
  tags: string[];
  system: boolean;
}

export interface ApplyResult {
  squadId: number;
  agents: { agentId: number; roleName: string; roleCode: string }[];
}

export async function listSquadTemplates(): Promise<SquadTemplateItem[]> {
  const resp = await apiClient.get<SquadTemplateItem[]>('/api/squad-templates');
  return resp.data;
}

export async function applySquadTemplate(id: number): Promise<ApplyResult> {
  const resp = await apiClient.post<ApplyResult>(`/api/squad-templates/${id}/apply`);
  return resp.data;
}

export interface SquadTemplateDetail {
  id: number;
  name: string;
  description: string;
  squadSize: number;
  icon: string | null;
  tags: string[];
  system: boolean;
  squad: { name: string; description: string };
  agents: SquadTemplateAgent[];
}

export interface SquadTemplateAgent {
  name: string;
  roleCode: string;
  roleName: string;
  responsibilities: string;
  sdlc: {
    name: string;
    description: string;
    steps: { order: number; name: string; kind: string }[];
  };
}

export async function getSquadTemplateDetail(id: number): Promise<SquadTemplateDetail> {
  const resp = await apiClient.get<SquadTemplateDetail>(`/api/squad-templates/${id}`);
  return resp.data;
}
