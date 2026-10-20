import { apiClient } from '@/shared/api/client';

/** 当前账号已绑定的外部身份；issuer/subject 是绑定键，仅本人可见。 */
export interface FederatedIdentity {
  id: number;
  providerId: number;
  providerName: string;
  protocol: string;
  issuer: string;
  subject: string;
  displayName: string | null;
  email: string | null;
  lastLoginAt: string | null;
  gmtCreate: string | null;
}

export const USER_FEDERATED_IDENTITIES_QUERY_KEY = ['user-federated-identities'] as const;

export async function listMyFederatedIdentities(): Promise<FederatedIdentity[]> {
  const resp = await apiClient.get<FederatedIdentity[]>('/api/users/me/federated-identities');
  return resp.data;
}

/** 已登录用户认领绑定票据：把已验证的企业身份绑定到当前会话账号。 */
export async function claimFederatedIdentity(ticket: string): Promise<FederatedIdentity> {
  const resp = await apiClient.post<FederatedIdentity>('/api/users/me/federated-identities/claim', { ticket });
  return resp.data;
}

/** 受保护的绑定发起：返回 IdP 授权地址，页面整页跳转后由回调建立绑定。 */
export async function startFederatedBinding(providerId: number): Promise<string> {
  const resp = await apiClient.post<{ authorizeUrl: string }>('/api/users/me/federated-identities/bind-start', { providerId });
  return resp.data.authorizeUrl;
}

export async function unbindFederatedIdentity(identityId: number): Promise<void> {
  await apiClient.delete<void>(`/api/users/me/federated-identities/${identityId}`);
}
