import { apiClient } from '@/shared/api/client';
import type { WorkspaceAccessLevel, WorkspaceDomain } from '@/shared/types/common';

// --- System Settings ---

export interface SettingItem {
  key: string;
  valueJson: string;
  secret: boolean;
}

export interface SettingVO {
  key: string;
  valueJson: string;
  secret: boolean;
}

export async function listSettingsByGroup(group: string): Promise<SettingVO[]> {
  const resp = await apiClient.get<SettingVO[]>(`/api/settings/${group}`);
  return resp.data;
}

export async function updateSettings(group: string, items: SettingItem[]): Promise<void> {
  await apiClient.put(`/api/settings/${group}`, { items });
}

// --- AI Usage / Quota ---

export interface AiQuotaVO {
  periodType: string;
  maxCalls?: number | null;
  maxTokens?: number | null;
  concurrencyLimit?: number | null;
}

export interface UpdateQuotaRequest {
  maxCalls?: number | null;
  maxTokens?: number | null;
  concurrencyLimit?: number | null;
}

export interface AiUsageVO {
  period: string;
  scene: string;
  callCount: number;
  inputTokens: number;
  outputTokens: number;
}

export async function getAiQuota(): Promise<AiQuotaVO> {
  const resp = await apiClient.get<AiQuotaVO>('/api/ai-usage/quota');
  return resp.data;
}

export async function updateAiQuota(req: UpdateQuotaRequest): Promise<void> {
  await apiClient.put('/api/ai-usage/quota', req);
}

export async function listAiUsage(): Promise<AiUsageVO[]> {
  const resp = await apiClient.get<AiUsageVO[]>('/api/ai-usage');
  return resp.data;
}

// --- Notification Preferences ---

export interface NotifyPrefVO {
  type: string;
  inApp: boolean;
  dingtalk: boolean;
  feishu?: boolean;
}

export interface NotifyPrefItem {
  type: string;
  inApp: boolean;
  dingtalk: boolean;
  feishu?: boolean;
}

export async function listNotifyPrefs(): Promise<NotifyPrefVO[]> {
  const resp = await apiClient.get<NotifyPrefVO[]>('/api/notifications/prefs');
  return resp.data;
}

export async function updateNotifyPrefs(items: NotifyPrefItem[]): Promise<void> {
  await apiClient.put('/api/notifications/prefs', { items });
}

// --- workspace Membership ---

export interface MemberVO {
  userId: number;
  username: string;
  email: string;
  nickname: string;
  joinedAt: string;
  owner: boolean;
  accessLevel: WorkspaceAccessLevel;
  identityTags: string[];
  roleId: number;
  roleCode: string | null;
  roleName: string | null;
  readOnlyDomains: WorkspaceDomain[];
}

export type CurrentMembershipVO = MemberVO;

export interface MemberCandidateVO {
  userId: number;
  username: string;
  email: string;
  nickname: string;
}

export async function getCurrentMembership(): Promise<CurrentMembershipVO> {
  const resp = await apiClient.get<CurrentMembershipVO>('/api/workspaces/current/membership');
  return resp.data;
}

export async function listMembers(): Promise<MemberVO[]> {
  const resp = await apiClient.get<MemberVO[]>('/api/workspaces/current/members');
  return resp.data;
}

export async function searchMemberCandidates(keyword: string): Promise<MemberCandidateVO[]> {
  const resp = await apiClient.get<MemberCandidateVO[]>('/api/workspaces/current/member-candidates', { params: { keyword } });
  return resp.data;
}

export async function addMember(userId: number, roleId?: number): Promise<void> {
  await apiClient.post('/api/workspaces/current/members', { userId, roleId });
}

export async function removeMember(userId: number): Promise<void> {
  await apiClient.delete(`/api/workspaces/current/members/${userId}`);
}

export async function updateMemberRole(
  userId: number,
  roleId: number,
): Promise<void> {
  await apiClient.put(`/api/workspaces/current/members/${userId}/role`, { roleId });
}

export async function updateMemberIdentityTags(
  userId: number,
  identityTags: string[],
): Promise<void> {
  await apiClient.put(`/api/workspaces/current/members/${userId}/identity-tags`, { identityTags });
}

export async function transferOwner(targetUserId: number): Promise<void> {
  await apiClient.post('/api/workspaces/current/owner/transfer', { targetUserId });
}

// --- Workspace Access Requests ---

export interface AccessRequestVO {
  id: number;
  tenantId: number;
  requesterId: number;
  requesterName: string | null;
  requestedLevel: WorkspaceAccessLevel;
  status: 'PENDING' | 'APPROVED' | 'REJECTED';
  reviewerId: number | null;
  reviewerName: string | null;
  rejectReason: string | null;
  gmtCreate: string;
}

export async function listAccessRequests(status: string): Promise<AccessRequestVO[]> {
  const resp = await apiClient.get<AccessRequestVO[]>('/api/workspaces/current/access-requests', {
    params: { status },
  });
  return resp.data;
}

export async function approveAccessRequest(requestId: number): Promise<void> {
  await apiClient.post(`/api/workspaces/current/access-requests/${requestId}/approve`);
}

export async function rejectAccessRequest(requestId: number, reason?: string): Promise<void> {
  await apiClient.post(`/api/workspaces/current/access-requests/${requestId}/reject`, { reason });
}

export interface WorkspaceRoleVO {
  id: number;
  code: string | null;
  name: string | null;
  administrator: boolean;
  builtIn: boolean;
  readOnlyDomains: WorkspaceDomain[];
  version: number;
  memberCount: number;
}

export interface WorkspaceRoleRequest {
  name?: string;
  readOnlyDomains: WorkspaceDomain[];
  version?: number;
}

export async function listWorkspaceRoles(): Promise<WorkspaceRoleVO[]> {
  return (await apiClient.get<WorkspaceRoleVO[]>('/api/workspaces/current/roles')).data;
}

export async function createWorkspaceRole(request: WorkspaceRoleRequest): Promise<WorkspaceRoleVO> {
  return (await apiClient.post<WorkspaceRoleVO>('/api/workspaces/current/roles', request)).data;
}

export async function updateWorkspaceRole(id: number, request: WorkspaceRoleRequest): Promise<WorkspaceRoleVO> {
  return (await apiClient.put<WorkspaceRoleVO>(`/api/workspaces/current/roles/${id}`, request)).data;
}

export async function deleteWorkspaceRole(id: number, version: number): Promise<void> {
  await apiClient.delete(`/api/workspaces/current/roles/${id}`, { params: { version } });
}
