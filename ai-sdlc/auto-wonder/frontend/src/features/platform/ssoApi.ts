import { apiClient } from '@/shared/api/client';

export type FederationProtocol = 'SAML2' | 'OIDC';

/** 平台管理员视角的 IdP 实例；enabled 为 1/0，与后端字段一致。 */
export interface FederationIdp {
  id: number;
  name: string;
  description: string | null;
  enabled: number;
}

/** 平台管理员视角的协议连接；密钥只以掩码与 configured 标志出现。 */
export interface FederationConnection {
  id: number;
  idpId: number;
  idpName: string | null;
  name: string;
  protocol: FederationProtocol | string;
  enabled: number;
  issuer: string | null;
  samlSsoUrl: string | null;
  samlCert: string | null;
  oidcAuthorizeUrl: string | null;
  oidcTokenUrl: string | null;
  oidcJwksUrl: string | null;
  clientId: string | null;
  clientSecretMasked: string | null;
  clientSecretConfigured: boolean;
  scopes: string | null;
  subjectClaim: string | null;
  nameClaim: string | null;
  emailClaim: string | null;
  /** 认证结果中飞书 user_id 的属性名；空表示不同步。 */
  feishuUserIdClaim: string | null;
  /** 首次登录未绑定时自动创建内部普通账号。 */
  autoProvision: boolean;
  /** 平台单行配置派生的唯一默认登录入口。 */
  defaultLogin: boolean;
  allowedTenants: string | null;
  sortOrder: number | null;
}

/** 平台认证配置：注册开关与全平台唯一默认连接。 */
export interface PlatformAuthConfig {
  registrationEnabled: boolean;
  defaultProviderId: number | null;
}

export interface UpdatePlatformAuthConfigParams {
  registrationEnabled?: boolean;
  defaultProviderId?: number;
  clearDefaultProvider?: boolean;
}

/** 普通用户可见的只读概览：字段由后端白名单投影，不含端点、证书、客户端 ID 或密钥。 */
export interface FederationOverviewIdp {
  id: number;
  name: string;
  enabled: number;
}

export interface FederationOverviewConnection {
  id: number;
  idpId: number;
  idpName: string | null;
  name: string;
  protocol: FederationProtocol | string;
  enabled: number;
  clientSecretConfigured: boolean;
}

export interface FederationOverview {
  idps: FederationOverviewIdp[];
  connections: FederationOverviewConnection[];
}

export interface CreateIdpParams {
  name: string;
  description?: string | null;
}

export interface UpdateIdpParams {
  name?: string;
  description?: string | null;
  enabled?: number;
}

export interface CreateConnectionParams {
  idpId: number;
  protocol: FederationProtocol;
  name: string;
  enabled?: number;
  issuer: string;
  samlSsoUrl?: string | null;
  samlCert?: string | null;
  oidcAuthorizeUrl?: string | null;
  oidcTokenUrl?: string | null;
  oidcJwksUrl?: string | null;
  clientId?: string | null;
  clientSecret?: string | null;
  scopes?: string | null;
  subjectClaim?: string | null;
  nameClaim?: string | null;
  emailClaim?: string | null;
  feishuUserIdClaim?: string | null;
  autoProvision?: number;
  defaultLogin?: boolean;
  allowedTenants?: string | null;
}

/** idpId 与 protocol 建立后不可修改，更新请求只携带其余字段。 */
export interface UpdateConnectionParams {
  name?: string;
  enabled?: number;
  issuer?: string;
  samlSsoUrl?: string | null;
  samlCert?: string | null;
  oidcAuthorizeUrl?: string | null;
  oidcTokenUrl?: string | null;
  oidcJwksUrl?: string | null;
  clientId?: string | null;
  clientSecret?: string | null;
  scopes?: string | null;
  subjectClaim?: string | null;
  nameClaim?: string | null;
  emailClaim?: string | null;
  feishuUserIdClaim?: string | null;
  autoProvision?: number;
  defaultLogin?: boolean;
  allowedTenants?: string | null;
}

export const FEDERATION_IDPS_QUERY_KEY = ['platform', 'federation', 'idps'] as const;
export const FEDERATION_CONNECTIONS_QUERY_KEY = ['platform', 'federation', 'connections'] as const;
export const FEDERATION_OVERVIEW_QUERY_KEY = ['platform', 'federation', 'overview'] as const;
export const FEDERATION_AUTH_CONFIG_QUERY_KEY = ['platform', 'federation', 'auth-config'] as const;
export const PLATFORM_IM_SELECTION_QUERY_KEY = ['platform', 'im-channels', 'selection'] as const;

export interface PlatformImSelection {
  provider: string;
}

/** 当前平台消息通道：飞书映射仅在 FEISHU 时生效。 */
export async function fetchPlatformImSelection(): Promise<PlatformImSelection> {
  const resp = await apiClient.get<PlatformImSelection>('/api/platform/im-channels/selection');
  return resp.data;
}

const IDPS_PATH = '/api/platform/federation/idps';
const CONNECTIONS_PATH = '/api/platform/federation/connections';
const OVERVIEW_PATH = '/api/platform/federation/overview';
const AUTH_CONFIG_PATH = '/api/platform/federation/auth-config';

export async function fetchPlatformAuthConfig(): Promise<PlatformAuthConfig> {
  const resp = await apiClient.get<PlatformAuthConfig>(AUTH_CONFIG_PATH);
  return resp.data;
}

export async function updatePlatformAuthConfig(
  params: UpdatePlatformAuthConfigParams,
): Promise<PlatformAuthConfig> {
  const resp = await apiClient.put<PlatformAuthConfig>(AUTH_CONFIG_PATH, params);
  return resp.data;
}

/** 已登录用户均可读取；仅返回白名单概览，管理面 CRUD 仍走管理员端点。 */
export async function fetchFederationOverview(): Promise<FederationOverview> {
  const resp = await apiClient.get<FederationOverview>(OVERVIEW_PATH);
  return resp.data;
}

export async function fetchFederationIdps(): Promise<FederationIdp[]> {
  const resp = await apiClient.get<FederationIdp[]>(IDPS_PATH);
  return resp.data;
}

export async function createFederationIdp(params: CreateIdpParams): Promise<FederationIdp> {
  const resp = await apiClient.post<FederationIdp>(IDPS_PATH, params);
  return resp.data;
}

export async function updateFederationIdp(id: number, params: UpdateIdpParams): Promise<FederationIdp> {
  const resp = await apiClient.put<FederationIdp>(`${IDPS_PATH}/${id}`, params);
  return resp.data;
}

export async function fetchFederationConnections(): Promise<FederationConnection[]> {
  const resp = await apiClient.get<FederationConnection[]>(CONNECTIONS_PATH);
  return resp.data;
}

export async function createFederationConnection(params: CreateConnectionParams): Promise<FederationConnection> {
  const resp = await apiClient.post<FederationConnection>(CONNECTIONS_PATH, params);
  return resp.data;
}

export async function updateFederationConnection(
  id: number,
  params: UpdateConnectionParams,
): Promise<FederationConnection> {
  const resp = await apiClient.put<FederationConnection>(`${CONNECTIONS_PATH}/${id}`, params);
  return resp.data;
}

/** 回调地址与后端一致，以部署公网地址为基准，不使用当前访问 origin。 */
export function federationCallbackUrl(connectionId: number, protocol: string, baseUrl: string): string {
  const suffix = protocol === 'SAML2' ? '/acs' : '/callback';
  return `${baseUrl}/api/auth/sso/${connectionId}${suffix}`;
}
