import { apiClient } from '@/shared/api/client';
import type { WorkspaceAccessLevel } from '@/shared/types/common';

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
}

export interface NotifyPrefItem {
  type: string;
  inApp: boolean;
  dingtalk: boolean;
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

export async function addMember(userId: number): Promise<void> {
  await apiClient.post('/api/workspaces/current/members', { userId });
}

export async function removeMember(userId: number): Promise<void> {
  await apiClient.delete(`/api/workspaces/current/members/${userId}`);
}

export async function updateMemberAccess(
  userId: number,
  accessLevel: WorkspaceAccessLevel,
): Promise<void> {
  await apiClient.put(`/api/workspaces/current/members/${userId}/access-level`, { accessLevel });
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
