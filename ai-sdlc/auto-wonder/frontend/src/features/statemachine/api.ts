import { apiClient } from '@/shared/api/client';
import type { StatusTemplate, TemplateDetail, StatusNode, StatusTransition, WorkType } from './types';

// --- Templates ---

export async function listTemplates(workType: WorkType): Promise<StatusTemplate[]> {
  const resp = await apiClient.get<StatusTemplate[]>('/api/status-templates', { params: { workType } });
  return resp.data;
}

export async function getTemplateDetail(id: number): Promise<TemplateDetail> {
  const resp = await apiClient.get<TemplateDetail>(`/api/status-templates/${id}`);
  return resp.data;
}

export async function createTemplate(data: { workType: string; name: string }): Promise<StatusTemplate> {
  const resp = await apiClient.post<StatusTemplate>('/api/status-templates', data);
  return resp.data;
}

export async function updateTemplate(id: number, data: { name?: string; isDefault?: boolean }): Promise<StatusTemplate> {
  const resp = await apiClient.put<StatusTemplate>(`/api/status-templates/${id}`, data);
  return resp.data;
}

export async function deleteTemplate(id: number): Promise<void> {
  await apiClient.delete(`/api/status-templates/${id}`);
}

// --- Nodes ---

export async function listNodes(templateId: number): Promise<StatusNode[]> {
  const resp = await apiClient.get<StatusNode[]>(`/api/status-templates/${templateId}/nodes`);
  return resp.data;
}

export async function createNode(templateId: number, data: {
  code: string; name: string; category: string; sort: number;
}): Promise<StatusNode> {
  const resp = await apiClient.post<StatusNode>(`/api/status-templates/${templateId}/nodes`, data);
  return resp.data;
}

export async function updateNode(templateId: number, nodeId: number, data: {
  code?: string; name?: string; category?: string; sort?: number;
}): Promise<StatusNode> {
  const resp = await apiClient.put<StatusNode>(`/api/status-templates/${templateId}/nodes/${nodeId}`, data);
  return resp.data;
}

export async function deleteNode(templateId: number, nodeId: number): Promise<void> {
  await apiClient.delete(`/api/status-templates/${templateId}/nodes/${nodeId}`);
}

// --- Transitions ---

export async function listTransitions(templateId: number): Promise<StatusTransition[]> {
  const resp = await apiClient.get<StatusTransition[]>(`/api/status-templates/${templateId}/transitions`);
  return resp.data;
}

export async function createTransition(templateId: number, data: {
  fromNodeId: number; toNodeId: number; name: string;
}): Promise<StatusTransition> {
  const resp = await apiClient.post<StatusTransition>(`/api/status-templates/${templateId}/transitions`, data);
  return resp.data;
}

export async function updateTransition(templateId: number, transitionId: number, data: {
  fromNodeId?: number; toNodeId?: number; name?: string;
}): Promise<StatusTransition> {
  const resp = await apiClient.put<StatusTransition>(`/api/status-templates/${templateId}/transitions/${transitionId}`, data);
  return resp.data;
}

export async function deleteTransition(templateId: number, transitionId: number): Promise<void> {
  await apiClient.delete(`/api/status-templates/${templateId}/transitions/${transitionId}`);
}
