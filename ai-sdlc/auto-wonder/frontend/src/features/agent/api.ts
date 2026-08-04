import { apiClient } from '@/shared/api/client';

export type AgentStatus = 'DRAFT' | 'PENDING_REVIEW' | 'ONLINE' | 'OFFLINE';
export type EvolutionMode = 'MANUAL' | 'ASSISTED' | 'AUTO_PROPOSAL';

export interface Agent {
  id: number;
  name: string;
  avatarUrl: string | null;
  status: AgentStatus;
  onlineVersionId: number | null;
  editingVersionId: number | null;
  latestVersionNo: number | null;
  version: number;
  gmtCreate: string;
  roleName?: string | null;
  roleCode?: string | null;
  businessBackground?: string | null;
  responsibilities?: string | null;
  executorOnlineCount?: number;
  executorTotalCount?: number;
  skillCount?: number;
  memoryCount?: number;
  repoPermCount?: number;
}

export interface AgentVersionSummary {
  id: number;
  versionNo: number;
  status: string;
  roleName: string;
  gmtCreate: string;
}

export interface AgentVersion {
  id: number;
  agentId: number;
  versionNo: number;
  status: 'DRAFT' | 'PENDING_REVIEW' | 'APPROVED' | 'REJECTED' | 'ONLINE' | 'ROLLED_BACK';
  roleName: string;
  roleCode: string;
  businessBackground: string;
  responsibilities: string;
  sdlcId: number | null;
  identityJson: string | null;
  evolutionMode?: EvolutionMode | null;
  reviewerId: number | null;
  reviewComment: string | null;
  reviewedAt: string | null;
  version: number;
  gmtCreate: string;
  repoPerms?: RepoPermItem[];
  skills?: SkillItem[];
  memoryRefs?: MemoryRefItem[];
}

export interface UpdateConfigRequest {
  roleName?: string;
  roleCode?: string;
  businessBackground?: string;
  responsibilities?: string;
  sdlcId?: number | null;
  evolutionMode?: EvolutionMode;
}

export interface CreateAgentRequest {
  name: string;
  avatarUrl?: string;
  roleName: string;
  roleCode: string;
  businessBackground?: string;
  responsibilities?: string;
}

export interface RepoPermItem {
  repoId: string;
  repoName?: string;
  permLevel: string;
}

export interface SkillItem {
  skillId: number;
  skillName?: string;
}

export interface MemoryRefItem {
  memoryId: number;
  source: string;
}

export async function listAgents(params: { page: number; size: number; status?: string }): Promise<Agent[]> {
  const resp = await apiClient.get<Agent[]>('/api/agents', { params });
  return resp.data;
}

export async function getAgent(id: number): Promise<Agent> {
  const resp = await apiClient.get<Agent>(`/api/agents/${id}`);
  return resp.data;
}

export async function createAgent(params: CreateAgentRequest): Promise<Agent> {
  const resp = await apiClient.post<Agent>('/api/agents', params);
  return resp.data;
}

export async function listVersions(agentId: number): Promise<AgentVersionSummary[]> {
  const resp = await apiClient.get<AgentVersionSummary[]>(`/api/agents/${agentId}/versions`);
  return resp.data;
}

export async function getVersion(agentId: number, versionNo: number): Promise<AgentVersion> {
  const resp = await apiClient.get<AgentVersion>(`/api/agents/${agentId}/versions/${versionNo}`);
  return resp.data;
}

export async function editConfig(agentId: number, config: UpdateConfigRequest): Promise<AgentVersion> {
  const resp = await apiClient.put<AgentVersion>(`/api/agents/${agentId}/config`, config);
  return resp.data;
}

export async function submitForReview(agentId: number): Promise<Agent> {
  const resp = await apiClient.post<Agent>(`/api/agents/${agentId}/submit`);
  return resp.data;
}

export async function approveAgent(agentId: number, comment?: string): Promise<Agent> {
  const resp = await apiClient.post<Agent>(`/api/agents/${agentId}/approve`, { comment });
  return resp.data;
}

export async function rejectAgent(agentId: number, comment: string): Promise<Agent> {
  const resp = await apiClient.post<Agent>(`/api/agents/${agentId}/reject`, { comment });
  return resp.data;
}

export async function rollback(agentId: number, versionNo: number): Promise<Agent> {
  const resp = await apiClient.post<Agent>(`/api/agents/${agentId}/rollback`, { versionNo });
  return resp.data;
}

export async function offlineAgent(agentId: number): Promise<Agent> {
  const resp = await apiClient.post<Agent>(`/api/agents/${agentId}/offline`);
  return resp.data;
}

export async function onlineAgent(agentId: number): Promise<Agent> {
  const resp = await apiClient.post<Agent>(`/api/agents/${agentId}/online`);
  return resp.data;
}

export async function deleteAgent(agentId: number): Promise<void> {
  await apiClient.delete(`/api/agents/${agentId}`);
}

// --- Repo permissions ---
export async function addRepoPerm(agentId: number, repoId: string, permLevel: string): Promise<void> {
  await apiClient.post(`/api/agents/${agentId}/repos`, { repoId, permLevel });
}

export async function removeRepoPerm(agentId: number, repoId: string): Promise<void> {
  await apiClient.delete(`/api/agents/${agentId}/repos/${repoId}`);
}

// --- Skills ---
export async function addSkill(agentId: number, skillId: number): Promise<void> {
  await apiClient.post(`/api/agents/${agentId}/skills`, { skillId });
}

export async function removeSkill(agentId: number, skillId: number): Promise<void> {
  await apiClient.delete(`/api/agents/${agentId}/skills/${skillId}`);
}

// --- Memory refs ---
export async function addMemoryRef(agentId: number, memoryId: number, source: string): Promise<void> {
  await apiClient.post(`/api/agents/${agentId}/memories`, { memoryId, source });
}

export async function removeMemoryRef(agentId: number, memoryId: number): Promise<void> {
  await apiClient.delete(`/api/agents/${agentId}/memories/${memoryId}`);
}

export async function listAgentMemories(agentId: number): Promise<MemoryRefItem[]> {
  const resp = await apiClient.get<MemoryRefItem[]>(`/api/agents/${agentId}/memories`);
  return resp.data;
}

// --- Review list (pending agents) ---
export async function listPendingReviews(): Promise<Agent[]> {
  const resp = await apiClient.get<Agent[]>('/api/agents', { params: { status: 'PENDING_REVIEW', page: 1, size: 100 } });
  return resp.data;
}

export async function getPendingReviewCount(): Promise<number> {
  const resp = await apiClient.get<number>('/api/agents/reviews/count');
  return resp.data;
}
