import { apiClient } from '@/shared/api/client';
import type { PageResult } from '@/shared/types/common';
import { csvParam } from '@/shared/api/csvParam';

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
  /** Non-deleted step count; the list endpoint returns this instead of steps. */
  stepCount?: number;
  squadIds?: number[] | null;
  squadNames?: string[] | null;
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

export async function listSdlcTemplates(params: {
  page: number;
  size: number;
  workType?: string;
  status?: string;
  squadIds?: number[];
}): Promise<SdlcTemplate[]> {
  const resp = await apiClient.get<SdlcTemplate[]>('/api/sdlcs', {
    params: { ...params, squadIds: csvParam(params.squadIds) },
  });
  return resp.data;
}

export async function listSdlcTemplatesPage(
  params: Parameters<typeof listSdlcTemplates>[0],
): Promise<PageResult<SdlcTemplate>> {
  const resp = await apiClient.get<PageResult<SdlcTemplate>>('/api/sdlcs/page', {
    params: { ...params, squadIds: csvParam(params.squadIds) },
  });
  return resp.data;
}

export const SDLC_FULL_PAGE_SIZE = 100;

/**
 * The grouped view needs every workflow before it can group by squad: a single page splits one
 * squad across pages. `/api/sdlcs/page` caps size at 100 server-side, so walk the pages until the
 * reported total is covered.
 */
export async function listAllSdlcTemplates(params: {
  workType?: string;
  status?: string;
  squadIds?: number[];
} = {}): Promise<{ items: SdlcTemplate[]; total: number }> {
  const first = await listSdlcTemplatesPage({ ...params, page: 1, size: SDLC_FULL_PAGE_SIZE });
  const byId = new Map<string, SdlcTemplate>();
  for (const row of first.list) byId.set(String(row.id), row);
  let pageNum = 2;
  while (byId.size < first.total) {
    const next = await listSdlcTemplatesPage({ ...params, page: pageNum, size: SDLC_FULL_PAGE_SIZE });
    const sizeBefore = byId.size;
    for (const row of next.list) byId.set(String(row.id), row);
    // Stops as soon as a page adds nothing new, so an overstated total cannot loop forever.
    if (byId.size === sizeBefore) break;
    pageNum += 1;
  }
  return { items: Array.from(byId.values()), total: first.total };
}

/**
 * Selectable workflow options for agent create/edit. Walks the plain list endpoint because that is
 * the contract already used by the agent forms; the server caps a page at 100, so a workflow past
 * the first page would otherwise be impossible to bind.
 */
export async function listAllSdlcTemplatesFlat(): Promise<SdlcTemplate[]> {
  const byId = new Map<string, SdlcTemplate>();
  let page = 1;
  for (;;) {
    const raw = await listSdlcTemplates({ page, size: SDLC_FULL_PAGE_SIZE });
    // Older responses wrap the array in `list`; accept both shapes so a tolerant mock keeps working.
    const batch: SdlcTemplate[] = Array.isArray(raw) ? raw : ((raw as unknown as { list?: SdlcTemplate[] }).list ?? []);
    const sizeBefore = byId.size;
    for (const row of batch) byId.set(String(row.id), row);
    if (batch.length < SDLC_FULL_PAGE_SIZE || byId.size === sizeBefore) {
      return Array.from(byId.values());
    }
    page += 1;
  }
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

// 更新是 PATCH 语义：未携带的字段后端保留原值，传空串才会清空可空字段。
// 顺序由 reorderSteps 维护，因此这里不含 stepOrder。
export interface UpdateStepParams {
  name?: string;
  kind?: string;
  instructionMd?: string;
  checklistJson?: string;
  gatePolicyJson?: string;
  required?: boolean;
  timeoutSeconds?: number | null;
  retryBudget?: number | null;
  code?: string;
  handlerType?: string;
  handlerRoleRef?: string;
  statusOnEnterCode?: string;
  onSuccess?: string;
  onFail?: string;
}

export async function updateStep(sdlcId: SdlcId, stepId: SdlcId, data: UpdateStepParams): Promise<SdlcStep> {
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
