import { i18n } from '@/shared/i18n';
import { apiClient } from '@/shared/api/client';

export interface Repo {
  id: number;
  name: string;
  url: string;
  defaultBranch: string | null;
  description: string | null;
  scanStatus: string | null;
  version: number;
  gmtCreate: string;
}

export interface RepoConclusion {
  id: number;
  repoId: number;
  purpose: string | null;
  keyBusiness: string | null;
  upstreams: string | null;
  downstreams: string | null;
  summaryMd: string | null;
  aiSessionId: string | number | null;
  version: number;
  gmtCreate: string;
}

export interface UpdateConclusionRequest {
  purpose?: string;
  keyBusiness?: string;
  upstreams?: string;
  downstreams?: string;
  summaryMd?: string;
}

export interface CreateRepoRequest {
  name: string;
  url: string;
  defaultBranch?: string;
  description?: string;
}

export interface UpdateRepoRequest {
  name?: string;
  url?: string;
  defaultBranch?: string | null;
  description?: string | null;
}

export interface RepoRelation {
  id: number;
  fromRepoId: number;
  toRepoId: number;
  relationType: string;
  description: string | null;
  aiSessionId: string | number | null;
  gmtCreate: string;
}

export interface CreateRelationRequest {
  fromRepoId: number;
  toRepoId: number;
  relationType: string;
  description?: string;
}

export const RELATION_TYPES = [
  { value: 'FRONTEND', get label() { return i18n.t('repo:relation.frontend'); } },
  { value: 'BACKEND', get label() { return i18n.t('repo:relation.backend'); } },
  { value: 'CLIENT_SERVER', get label() { return i18n.t('repo:relation.clientServer'); } },
  { value: 'SERVER_CLIENT', get label() { return i18n.t('repo:relation.serverClient'); } },
  { value: 'GATEWAY', get label() { return i18n.t('repo:relation.gateway'); } },
  { value: 'DEPENDENCY', get label() { return i18n.t('repo:relation.dependency'); } },
  { value: 'SERVICE', get label() { return i18n.t('repo:relation.service'); } },
  { value: 'OTHER', get label() { return i18n.t('repo:relation.other'); } },
] as const;

export async function listRepos(params: { page: number; size: number }): Promise<Repo[]> {
  const resp = await apiClient.get<Repo[]>('/api/repos', { params });
  return resp.data;
}

export async function createRepo(data: CreateRepoRequest): Promise<Repo> {
  const resp = await apiClient.post<Repo>('/api/repos', data);
  return resp.data;
}

export async function getRepo(id: string | number): Promise<Repo> {
  const resp = await apiClient.get<Repo>(`/api/repos/${id}`);
  return resp.data;
}

export async function updateRepo(id: string | number, data: UpdateRepoRequest): Promise<Repo> {
  const resp = await apiClient.put<Repo>(`/api/repos/${id}`, data);
  return resp.data;
}

export async function triggerScan(repoId: string | number): Promise<void> {
  await apiClient.post(`/api/repos/${repoId}/scan`);
}

export async function getConclusion(repoId: string | number): Promise<RepoConclusion | null> {
  const resp = await apiClient.get<RepoConclusion>(`/api/repos/${repoId}/conclusion`);
  return resp.data;
}

export async function updateConclusion(repoId: string | number, data: UpdateConclusionRequest): Promise<RepoConclusion> {
  const resp = await apiClient.put<RepoConclusion>(`/api/repos/${repoId}/conclusion`, data);
  return resp.data;
}

export async function listRelations(repoId?: string | number): Promise<RepoRelation[]> {
  const resp = await apiClient.get<RepoRelation[]>('/api/repos/relations', {
    params: repoId ? { repoId } : undefined,
  });
  return resp.data;
}

export async function createRelation(data: CreateRelationRequest): Promise<RepoRelation> {
  const resp = await apiClient.post<RepoRelation>('/api/repos/relations', data);
  return resp.data;
}

export async function deleteRelation(id: number): Promise<void> {
  await apiClient.delete(`/api/repos/relations/${id}`);
}

export async function deleteRepo(id: string | number): Promise<void> {
  await apiClient.delete(`/api/repos/${id}`);
}
