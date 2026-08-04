import { apiClient } from '@/shared/api/client';

export interface Skill {
  id: number;
  type: 'MCP' | 'SKILL' | 'PLUGIN';
  name: string;
  installSpec: string;
  description: string;
  sourceType?: 'INSTALL_SPEC' | 'OSS_ZIP';
  packageOssRef?: string;
  packageFileName?: string;
  packageSize?: number;
  packageMd5?: string;
  version: number;
  gmtCreate: string;
  gmtModified?: string;
  modifierId?: number;
  modifierName?: string;
}

export interface SkillPackageInspectResult {
  name: string;
  description: string;
  fileName: string;
  packageSize: number;
}

export interface SkillConnectionTestResult {
  success: boolean;
  message: string;
  durationMs?: number;
}

export async function listSkills(params: {
  page: number;
  size: number;
  type?: string;
}): Promise<Skill[]> {
  const resp = await apiClient.get<Skill[]>('/api/skills', { params });
  return resp.data;
}

export async function getSkill(id: number): Promise<Skill> {
  const resp = await apiClient.get<Skill>(`/api/skills/${id}`);
  return resp.data;
}

export async function testSkillConnection(id: number, executorId?: number): Promise<SkillConnectionTestResult> {
  const resp = await apiClient.post<SkillConnectionTestResult>(`/api/skills/${id}/connection-test`, undefined, {
    params: executorId ? { executorId } : undefined,
  });
  return resp.data;
}

export async function createSkill(data: {
  type: string;
  name: string;
  installSpec: string;
  description?: string;
}): Promise<Skill> {
  const resp = await apiClient.post<Skill>('/api/skills', data);
  return resp.data;
}

export async function inspectSkillPackage(file: File): Promise<SkillPackageInspectResult> {
  const formData = new FormData();
  formData.append('file', file);
  const resp = await apiClient.post<SkillPackageInspectResult>('/api/skills/package/inspect', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
  });
  return resp.data;
}

export async function createSkillFromPackage(file: File, metadata?: { type?: string; name?: string; description?: string; providers?: string[] }): Promise<Skill> {
  const formData = new FormData();
  formData.append('file', file);
  if (metadata?.type) formData.append('type', metadata.type);
  if (metadata?.name) formData.append('name', metadata.name);
  if (metadata?.description) formData.append('description', metadata.description);
  metadata?.providers?.forEach((provider) => formData.append('providers', provider));
  const resp = await apiClient.post<Skill>('/api/skills/package', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
  });
  return resp.data;
}

export async function updateSkillPackage(id: number, file: File, metadata?: { name?: string; description?: string; providers?: string[] }): Promise<Skill> {
  const formData = new FormData();
  formData.append('file', file);
  if (metadata?.name) formData.append('name', metadata.name);
  if (metadata?.description) formData.append('description', metadata.description);
  metadata?.providers?.forEach((provider) => formData.append('providers', provider));
  const resp = await apiClient.put<Skill>(`/api/skills/${id}/package`, formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
  });
  return resp.data;
}

export async function updateSkill(id: number, data: {
  name?: string;
  installSpec?: string;
  description?: string;
}): Promise<Skill> {
  const resp = await apiClient.put<Skill>(`/api/skills/${id}`, data);
  return resp.data;
}

export async function deleteSkill(id: number): Promise<void> {
  await apiClient.delete(`/api/skills/${id}`);
}
