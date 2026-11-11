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

/** One detected frontend application (a profile snapshot) with its traceable evidence. */
export interface FrontendProjectProfile {
  id: number;
  repoId: number;
  probeId: number;
  branch: string | null;
  commitSha: string;
  appRoot: string;
  packageName: string | null;
  detectorVersion: string;
  language: string | null;
  packageManager: string | null;
  looksLikeLibrary: boolean;
  stale: boolean;
  frameworks: string[];
  buildTools: string[];
  uiLibraries: string[];
  routers: string[];
  i18nLibraries: string[];
  testTools: string[];
  inferences: string[];
  unknowns: string[];
  evidence: { fact: string; filePath: string }[];
}

/** A durable probe run; QUEUED/RUNNING are pollable, the rest are terminal. */
export interface FrontendProjectProbe {
  id: number;
  repoId: number;
  repoName: string | null;
  status: string;
  detectorVersion: string;
  branch: string | null;
  commitSha: string | null;
  candidateCount: number | null;
  error: string | null;
  startedAt: string | null;
  finishedAt: string | null;
  gmtCreate: string | null;
  profiles: FrontendProjectProfile[];
}

export const PROBE_ACTIVE_STATUSES = ['QUEUED', 'RUNNING'] as const;

/** Server-side probe lifecycle states; kept here so no component hardcodes a status string. */
export const PROBE_STATUS = {
  QUEUED: 'QUEUED',
  RUNNING: 'RUNNING',
  SUCCEEDED: 'SUCCEEDED',
  FAILED: 'FAILED',
  CANCELED: 'CANCELED',
} as const;

export type ProbeStatusValue = typeof PROBE_STATUS[keyof typeof PROBE_STATUS];

export function isProbeActive(status: string | null | undefined): boolean {
  return status === PROBE_STATUS.QUEUED || status === PROBE_STATUS.RUNNING;
}

export async function probeFrontendProjects(repoId: string | number): Promise<FrontendProjectProbe> {
  const resp = await apiClient.post<FrontendProjectProbe>(`/api/repos/${repoId}/frontend-projects/probe`);
  return resp.data;
}

export async function getLatestFrontendProjectProbe(
  repoId: string | number,
): Promise<FrontendProjectProbe | null> {
  const resp = await apiClient.get<FrontendProjectProbe | null>(
    `/api/repos/${repoId}/frontend-projects/latest`,
  );
  return resp.data;
}

export async function listFrontendProjectProfiles(
  repoId: string | number,
): Promise<FrontendProjectProfile[]> {
  const resp = await apiClient.get<FrontendProjectProfile[]>(`/api/repos/${repoId}/frontend-projects`);
  return resp.data;
}

export async function listFrontendProjectProbes(
  repoId: string | number,
): Promise<FrontendProjectProbe[]> {
  const resp = await apiClient.get<FrontendProjectProbe[]>(
    `/api/repos/${repoId}/frontend-projects/probes`,
  );
  return resp.data;
}

export async function getFrontendProjectProbe(
  probeId: string | number,
): Promise<FrontendProjectProbe> {
  const resp = await apiClient.get<FrontendProjectProbe>(
    `/api/repos/frontend-projects/probes/${probeId}`,
  );
  return resp.data;
}

/** Discover repositories in the active workspace; the backend reuses prior probe runs. */
export async function discoverFrontendProjects(): Promise<FrontendProjectProbe[]> {
  const resp = await apiClient.post<FrontendProjectProbe[]>('/api/repos/frontend-projects/discover');
  return resp.data;
}
