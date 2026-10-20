import { apiClient } from '@/shared/api/client';
import type { LoginResponse } from '@/shared/types/common';

/** 登录页可见的一条已启用连接；登录页只按该列表渲染入口，不识别具体厂商。 */
export interface SsoProvider {
  providerId: number;
  name: string;
  protocol: string;
  idpName: string;
}

export interface SsoBindParams {
  ticket: string;
  username: string;
  password: string;
}

export const SSO_PROVIDERS_QUERY_KEY = ['auth', 'sso', 'providers'] as const;

export async function fetchSsoProviders(): Promise<SsoProvider[]> {
  const resp = await apiClient.get<SsoProvider[]>('/api/auth/sso/providers');
  return resp.data;
}

/** 一次性票据换 AW 会话；票据短时有效且只能消费一次。 */
export async function exchangeSsoTicket(ticket: string): Promise<LoginResponse> {
  const resp = await apiClient.post<LoginResponse>('/api/auth/sso/session', { ticket });
  return resp.data;
}

/** 首次登录绑定：用已有 AW 账号的用户名密码认领已验证的企业身份，成功后直接建立会话。 */
export async function bindSsoIdentity(params: SsoBindParams): Promise<LoginResponse> {
  const resp = await apiClient.post<LoginResponse>('/api/auth/sso/bind', params);
  return resp.data;
}
