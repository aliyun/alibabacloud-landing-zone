import { apiClient } from '@/shared/api/client';

export interface PlatformBranding {
  platformName: string;
  logoUrl: string;
  themeKey: string;
  primaryColor: string;
  domain: string | null;
  mcpBaseUrl: string;
  recommendedRuntimeVersion: string;
  deploymentVersion: string;
  communityEdition: boolean;
  canManage: boolean;
}

export interface UpdatePlatformBrandingParams {
  platformName: string;
  themeKey: string;
  primaryColor: string;
  domain?: string | null;
}

export interface PlatformImChannel {
  provider: 'DINGTALK' | 'FEISHU' | string;
  selected?: boolean;
  enabled: boolean;
  appKey: string;
  robotCode: string;
  secretConfigured: boolean;
  ready: boolean;
}

export interface UpdateDingTalkImChannelParams {
  enabled: boolean;
  appKey: string;
  appSecret: string;
  robotCode: string;
}

export interface ImSelection {
  provider: 'DINGTALK' | 'FEISHU' | string;
  generation: number;
  activatedAt: string | null;
}

export interface GroupChatConfig {
  provider: 'DINGTALK' | 'FEISHU' | string;
  configured: boolean;
  ready: boolean;
  desiredEnabled: boolean;
  selected: boolean;
  appKey: string | null;
  robotCode: string | null;
  secretConfigured: boolean;
  verificationTokenConfigured: boolean;
  encryptKeyConfigured: boolean;
  version: number | null;
  routeVersion: number;
  selectionGeneration: number;
  status: 'ACTIVE' | 'DRAINING' | 'PAUSED' | 'DISABLED' | 'ERROR' | string;
  drainingCount: number;
  failedCount: number;
  unknownCount: number;
}

/** Workspace-visible summary of the selected shared group-chat bot; never contains secrets. */
export interface GroupChatPublicConfig {
  provider: 'DINGTALK' | 'FEISHU' | string;
  configured: boolean;
  ready: boolean;
  desiredEnabled: boolean;
  appKey: string | null;
  robotCode: string | null;
  selectionGeneration: number;
  status: 'ACTIVE' | 'DRAINING' | 'PAUSED' | 'DISABLED' | 'ERROR' | string;
  drainingCount: number;
  failedCount: number;
  unknownCount: number;
}

export interface UpdateGroupChatConfigParams {
  appKey?: string;
  appId?: string;
  appSecret?: string;
  robotCode?: string;
  verificationToken?: string;
  encryptKey?: string;
  clearEncryptKey?: boolean;
  enabled: boolean;
  version?: number | null;
}

export const DEFAULT_BRANDING: PlatformBranding = {
  platformName: 'AutoWonder',
  logoUrl: '/logo.svg',
  themeKey: 'aliyun-orange',
  primaryColor: '#f97316',
  domain: null,
  mcpBaseUrl: '',
  recommendedRuntimeVersion: '0.3.30',
  deploymentVersion: 'x.x.x',
  communityEdition: false,
  canManage: false,
};

export const BRANDING_QUERY_KEY = ['platform-branding', 'public'] as const;
export const BRANDING_ADMIN_QUERY_KEY = ['platform-branding', 'admin'] as const;
export const PLATFORM_IM_CHANNELS_QUERY_KEY = ['platform-im-channels'] as const;
export const IM_SELECTION_QUERY_KEY = ['platform-im-selection'] as const;
export const GROUP_CHAT_PUBLIC_QUERY_KEY = ['im-group-config-public'] as const;
export const groupChatQueryKey = (provider: string) => ['platform-im-group-chat', provider] as const;

export const THEME_PRESETS = [
  { key: 'aliyun-orange', name: '阿里橙', primaryColor: '#f97316' },
  { key: 'ocean-blue', name: '海洋蓝', primaryColor: '#2563eb' },
  { key: 'jade-green', name: '翡翠绿', primaryColor: '#059669' },
  { key: 'indigo', name: '靛蓝', primaryColor: '#4f46e5' },
  { key: 'rose', name: '玫瑰红', primaryColor: '#e11d48' },
  { key: 'cyan', name: '青蓝', primaryColor: '#0891b2' },
  { key: 'amber', name: '琥珀', primaryColor: '#d97706' },
  { key: 'violet', name: '紫罗兰', primaryColor: '#7c3aed' },
  { key: 'graphite', name: '石墨', primaryColor: '#374151' },
  { key: 'teal', name: '松石绿', primaryColor: '#0f766e' },
] as const;

export async function getPublicBranding(): Promise<PlatformBranding> {
  const resp = await apiClient.get<PlatformBranding>('/api/platform/branding/public');
  return resp.data;
}

export async function getAdminBranding(): Promise<PlatformBranding> {
  const resp = await apiClient.get<PlatformBranding>('/api/platform/branding');
  return resp.data;
}

export async function updateBranding(params: UpdatePlatformBrandingParams): Promise<PlatformBranding> {
  const resp = await apiClient.put<PlatformBranding>('/api/platform/branding', params);
  return resp.data;
}

export async function getPlatformImChannels(): Promise<PlatformImChannel[]> {
  const resp = await apiClient.get<PlatformImChannel[]>('/api/platform/im-channels');
  return resp.data;
}

export async function updateDingTalkImChannel(params: UpdateDingTalkImChannelParams): Promise<PlatformImChannel> {
  const resp = await apiClient.put<PlatformImChannel>('/api/platform/im-channels/dingtalk', params);
  return resp.data;
}

export async function updateFeishuImChannel(params: UpdateDingTalkImChannelParams): Promise<PlatformImChannel> {
  const resp = await apiClient.put<PlatformImChannel>('/api/platform/im-channels/feishu', params);
  return resp.data;
}

export function selectedImProvider(channels: PlatformImChannel[]): string {
  return channels.find((channel) => channel.selected)?.provider
    ?? channels.find((channel) => channel.enabled)?.provider ?? 'DINGTALK';
}

export async function getImSelection(): Promise<ImSelection> {
  const resp = await apiClient.get<ImSelection>('/api/platform/im-channels/selection');
  return resp.data;
}

export async function selectImProvider(provider: string, expectedGeneration: number): Promise<ImSelection> {
  const resp = await apiClient.put<ImSelection>('/api/platform/im-channels/selection', {
    provider,
    expectedGeneration,
  });
  return resp.data;
}

export async function getGroupChatConfig(provider: string): Promise<GroupChatConfig> {
  const resp = await apiClient.get<GroupChatConfig>(`/api/platform/im-channels/${provider}/group-chat`);
  return resp.data;
}

export async function getPublicGroupChatConfig(): Promise<GroupChatPublicConfig> {
  const resp = await apiClient.get<GroupChatPublicConfig>('/api/integrations/channels/group-config');
  return resp.data;
}

export async function updateGroupChatConfig(
  provider: string,
  params: UpdateGroupChatConfigParams,
): Promise<GroupChatConfig> {
  const resp = await apiClient.put<GroupChatConfig>(
    `/api/platform/im-channels/${provider}/group-chat`,
    params,
  );
  return resp.data;
}

export async function uploadBrandingLogo(file: File): Promise<{ logoUrl: string }> {
  const form = new FormData();
  form.append('file', file);
  const resp = await apiClient.post<{ logoUrl: string }>('/api/platform/branding/logo', form, {
    headers: { 'Content-Type': 'multipart/form-data' },
  });
  return resp.data;
}
