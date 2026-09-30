import { apiClient } from '@/shared/api/client';

export interface UserImIdentity {
  provider: 'DINGTALK' | string;
  externalUserId: string;
  configured: boolean;
  platformReady: boolean;
  testAvailable: boolean;
}

export interface UpdateDingTalkIdentityParams {
  externalUserId: string;
}

export const USER_IM_IDENTITIES_QUERY_KEY = ['user-im-identities'] as const;

export async function listMyImIdentities(): Promise<UserImIdentity[]> {
  const resp = await apiClient.get<UserImIdentity[]>('/api/users/me/im-identities');
  return resp.data;
}

export async function updateMyDingTalkIdentity(params: UpdateDingTalkIdentityParams): Promise<UserImIdentity> {
  const resp = await apiClient.put<UserImIdentity>('/api/users/me/im-identities/dingtalk', params);
  return resp.data;
}

export async function sendMyDingTalkIdentityTest(): Promise<void> {
  await apiClient.post<void>('/api/users/me/im-identities/dingtalk/test');
}

export async function updateMyFeishuIdentity(params: UpdateDingTalkIdentityParams): Promise<UserImIdentity> {
  const resp = await apiClient.put<UserImIdentity>('/api/users/me/im-identities/feishu', params);
  return resp.data;
}

export async function sendMyFeishuIdentityTest(): Promise<void> {
  await apiClient.post<void>('/api/users/me/im-identities/feishu/test');
}

export interface ChangePasswordParams {
  oldPassword: string;
  newPassword: string;
}

export interface MyProfile {
  id: number;
  username: string;
  nickname: string | null;
  email: string | null;
  phone: string | null;
}

export interface UpdateMyProfileParams {
  nickname: string;
  email: string;
  phone: string;
}

export const USER_PROFILE_QUERY_KEY = ['user-profile'] as const;

export async function getMyProfile(): Promise<MyProfile> {
  const resp = await apiClient.get<MyProfile>('/api/users/me/profile');
  return resp.data;
}

export async function updateMyProfile(params: UpdateMyProfileParams): Promise<MyProfile> {
  const resp = await apiClient.put<MyProfile>('/api/users/me/profile', params);
  return resp.data;
}

export async function changePassword(params: ChangePasswordParams): Promise<void> {
  await apiClient.put<void>('/api/users/me/password', params);
}

export interface DeactivationStatus {
  pending: boolean;
  deactivatedAt: string | null;
  coolingOffExpiresAt: string | null;
  revoked: boolean;
}

export const DEACTIVATION_STATUS_QUERY_KEY = ['user-deactivation-status'] as const;

export async function getDeactivationStatus(): Promise<DeactivationStatus> {
  const resp = await apiClient.get<DeactivationStatus>('/api/users/me/deactivation');
  return resp.data;
}

export async function initiateDeactivation(confirmUsername: string): Promise<void> {
  await apiClient.post<void>('/api/users/me/deactivation', { confirmUsername });
}

export async function revokeDeactivation(): Promise<void> {
  await apiClient.post<void>('/api/users/me/deactivation/revoke');
}
