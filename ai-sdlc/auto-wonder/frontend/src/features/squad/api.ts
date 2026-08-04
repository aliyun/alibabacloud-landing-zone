import { apiClient } from '@/shared/api/client';
import type { PageResult } from '@/shared/types/common';

export interface Squad {
  id: number;
  name: string;
  description: string;
  memberCount: number;
  roleCount?: number;
  executorOnlineCount?: number;
  executorTotalCount?: number;
  sdlcCount?: number;
  memberAgentIds?: number[] | null;
  gmtCreate: string;
}

export interface SquadSdlcStep {
  id: number;
  stepOrder: number;
  name: string;
  handlerType: string;
  handlerRoleRef: string | null;
}

export interface SquadMember {
  agentId: number;
  agentName: string;
  roleCode: string;
  roleName?: string;
  responsibilities?: string;
  sdlcId?: number | null;
  sdlcName?: string;
  sdlcSteps?: SquadSdlcStep[];
}

export async function listSquads(params: { pageNum: number; pageSize: number }): Promise<PageResult<Squad>> {
  const resp = await apiClient.get<PageResult<Squad> | Squad[]>('/api/squads', {
    params: { page: params.pageNum, size: params.pageSize },
  });
  if (Array.isArray(resp.data)) {
    const list = resp.data.map(normalizeSquad);
    return {
      list,
      total: list.length,
      pageNum: params.pageNum,
      pageSize: params.pageSize,
    };
  }
  return {
    ...resp.data,
    list: resp.data.list.map(normalizeSquad),
  };
}

function normalizeSquad(squad: Squad): Squad {
  return {
    ...squad,
    memberCount: squad.memberCount ?? squad.memberAgentIds?.length ?? 0,
    roleCount: squad.roleCount ?? 0,
    executorOnlineCount: squad.executorOnlineCount ?? 0,
    executorTotalCount: squad.executorTotalCount ?? 0,
    sdlcCount: squad.sdlcCount ?? 0,
  };
}

export async function getSquad(id: number): Promise<Squad> {
  const resp = await apiClient.get<Squad>(`/api/squads/${id}`);
  return resp.data;
}

export async function createSquad(params: { name: string; description?: string }): Promise<Squad> {
  const resp = await apiClient.post<Squad>('/api/squads', params);
  return resp.data;
}

export async function updateSquad(id: number, data: { name?: string; description?: string }): Promise<Squad> {
  const resp = await apiClient.put<Squad>(`/api/squads/${id}`, data);
  return resp.data;
}

export async function deleteSquad(id: number): Promise<void> {
  await apiClient.delete(`/api/squads/${id}`);
}

export async function getSquadMembers(squadId: number): Promise<SquadMember[]> {
  const resp = await apiClient.get<SquadMember[]>(`/api/squads/${squadId}/members`);
  return resp.data;
}

export async function addSquadMember(squadId: number, agentId: number): Promise<void> {
  await apiClient.post(`/api/squads/${squadId}/members`, { agentIds: [agentId] });
}

export async function removeSquadMember(squadId: number, agentId: number): Promise<void> {
  await apiClient.delete(`/api/squads/${squadId}/members/${agentId}`);
}
