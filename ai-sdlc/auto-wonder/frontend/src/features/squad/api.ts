import { apiClient } from '@/shared/api/client';
import type { PageResult } from '@/shared/types/common';

export interface Squad {
  id: number;
  name: string;
  description: string;
  ownerId?: number | null;
  memberCount: number;
  roleCount?: number;
  executorOnlineCount?: number;
  executorTotalCount?: number;
  sdlcCount?: number;
  memberAgentIds?: number[] | null;
  debugLogEnabled?: boolean;
  sdlcs?: SquadSdlcSummary[] | null;
  executors?: SquadExecutorSummary[] | null;
  gmtCreate: string;
}

export interface SquadSdlcSummary {
  id: number;
  name: string;
  workType: string | null;
  status: string | null;
}

export interface SquadExecutorSummary {
  id: number;
  agentId: number | null;
  agentName: string | null;
  name: string;
  status: string;
  clientKind: string | null;
  lastHeartbeat: string | null;
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
  /** STANDARD=普通数字员工 / PLATFORM=平台数字人 */
  agentKind?: 'STANDARD' | 'PLATFORM';
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

export const SQUAD_PAGE_SIZE = 100;

// GET /api/squads is paginated, so one wide page silently drops every squad past the cap: those
// squads then vanish from the filter dropdown and from the grouped view without any hint.
export async function listAllSquads(pageSize = SQUAD_PAGE_SIZE): Promise<Squad[]> {
  const first = await listSquads({ pageNum: 1, pageSize });
  const byId = new Map<number, Squad>(first.list.map((squad) => [squad.id, squad]));
  let pageNum = 2;
  while (byId.size < first.total) {
    const next = await listSquads({ pageNum, pageSize });
    const sizeBefore = byId.size;
    next.list.forEach((squad) => byId.set(squad.id, squad));
    // Stops as soon as a page adds nothing new, so an overstated total cannot loop forever.
    if (byId.size === sizeBefore) break;
    pageNum += 1;
  }
  return Array.from(byId.values());
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

// GET /api/squads returns memberAgentIds as null; only the detail endpoint populates it.
export async function listSquadsWithMembers(pageSize = SQUAD_PAGE_SIZE): Promise<Squad[]> {
  const squads = await listAllSquads(pageSize);
  if (squads.length === 0) {
    return [];
  }
  return Promise.all(squads.map(squad => getSquad(squad.id)));
}

export async function createSquad(params: { name: string; description?: string }): Promise<Squad> {
  const resp = await apiClient.post<Squad>('/api/squads', params);
  return resp.data;
}

/**
 * 更新小队。后端 `PUT /api/squads/{id}` 混合了 PUT/PATCH 语义：
 * `name`/`description`/`owner_id` 无条件覆盖，只有 `debug_log_enabled` 走 COALESCE。
 * 因此调用方必须整对象提交（带上当前小队的 name/description/ownerId），
 * 只发单个字段会把未提交列写成 null（`name` 非空列会直接 500）。
 */
export async function updateSquad(
  id: number,
  data: { name?: string; description?: string; ownerId?: number | null; debugLogEnabled?: boolean },
): Promise<Squad> {
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
