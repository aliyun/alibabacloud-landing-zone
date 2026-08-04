import { apiClient } from '@/shared/api/client';
import type { LoginResponse, UserInfo } from '@/shared/types/common';

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

export async function register(params: RegisterParams): Promise<UserInfo> {
  const resp = await apiClient.post<UserInfo>('/api/auth/register', params);
  return resp.data;
}

export async function logout(refreshToken: string): Promise<void> {
  await apiClient.post('/api/auth/logout', { refreshToken });
}
