import { apiClient } from '@/shared/api/client';
import type { LoginResponse, UserInfo } from '@/shared/types/common';
import type { AdministratorContact } from '@/shared/types/administrator';

export interface LoginParams {
  username: string;
  password: string;
}

export interface RegisterParams {
  username: string;
  password: string;
  email: string;
  nickname: string;
}

export async function login(params: LoginParams): Promise<LoginResponse> {
  const resp = await apiClient.post<LoginResponse>('/api/auth/login', params);
  return resp.data;
}

export interface RegisterResult extends UserInfo {
  reviewRequired: boolean;
  administratorContacts: AdministratorContact[] | null;
}

export async function register(params: RegisterParams): Promise<RegisterResult> {
  const resp = await apiClient.post<RegisterResult>('/api/auth/register', params);
  return resp.data;
}

export async function logout(refreshToken: string): Promise<void> {
  await apiClient.post('/api/auth/logout', { refreshToken });
}

// Keyed by user id so a newly logged-in account never reads the previous
// account's cached workspace list.
export function myWorkspacesQueryKey(userId: number | null) {
  return ['workspaces', 'mine', userId] as const;
}
