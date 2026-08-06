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

export interface ChangePasswordParams {
  oldPassword: string;
  newPassword: string;
}

export async function changePassword(params: ChangePasswordParams): Promise<void> {
  await apiClient.put<void>('/api/users/me/password', params);
}
