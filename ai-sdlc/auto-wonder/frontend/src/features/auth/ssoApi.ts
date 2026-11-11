import { apiClient } from '@/shared/api/client';
import type { LoginResponse } from '@/shared/types/common';
import { isAuthEntryPath, sanitizeReturnPath } from '@/shared/auth/returnPath';

/** 登录页可见的一条已启用连接；登录页只按该列表渲染入口，不识别具体厂商。 */
export interface SsoProvider {
  providerId: number;
  name: string;
  protocol: string;
  idpName: string;
}

/** 登录页公开配置：注册开关与平台唯一默认连接；不含端点、证书等管理面信息。 */
export interface SsoLoginConfig {
  registrationEnabled: boolean;
  defaultProviderId: number | null;
}

export interface SsoBindParams {
  ticket: string;
  username: string;
  password: string;
}

export const SSO_PROVIDERS_QUERY_KEY = ['auth', 'sso', 'providers'] as const;
export const SSO_LOGIN_CONFIG_QUERY_KEY = ['auth', 'sso', 'config'] as const;

export async function fetchSsoProviders(): Promise<SsoProvider[]> {
  const resp = await apiClient.get<SsoProvider[]>('/api/auth/sso/providers');
  return resp.data;
}

export async function fetchSsoLoginConfig(): Promise<SsoLoginConfig> {
  const resp = await apiClient.get<SsoLoginConfig>('/api/auth/sso/config');
  return resp.data;
}

/** 发起登录的站内地址；回跳目标只在通过站内路径校验后才带入。 */
export function ssoStartUrl(providerId: number, next?: string | null): string {
  const base = `/api/auth/sso/${providerId}/start`;
  const safe = sanitizeReturnPath(next);
  // 登录、注册与 SSO 完成页不作为回跳目标，与服务端校验保持一致。
  return safe && !isAuthEntryPath(safe) ? `${base}?next=${encodeURIComponent(safe)}` : base;
}

/** 浏览器整页跳转到 IdP；单独成函数便于测试替换，不改变任何鉴权语义。 */
export function beginSsoLogin(url: string): void {
  window.location.assign(url);
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
