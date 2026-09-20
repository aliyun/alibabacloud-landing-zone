import { apiClient } from '@/shared/api/client';

/**
 * 用户级偏好配置（后端 `user_setting` 表）。
 *
 * key 是开放命名空间，任何用户级偏好都复用这一组接口，不再为单个偏好加专用端点。
 * 值统一是 JSON 文本：标量写 `"shift-enter"`，复杂结构写对象，后端只校验可解析与长度。
 */
export interface UserSetting {
  key: string;
  valueJson: string | null;
}

export function userSettingQueryKey(key: string): readonly ['user-setting', string] {
  return ['user-setting', key] as const;
}

export const USER_SETTINGS_QUERY_KEY = ['user-settings'] as const;

export async function listMySettings(): Promise<UserSetting[]> {
  const resp = await apiClient.get<UserSetting[]>('/api/users/me/settings');
  return resp.data;
}

/** 未设置过时后端返回 `{ key, valueJson: null }`，不是 404，调用方不需要处理缺失分支。 */
export async function getMySetting(key: string): Promise<UserSetting> {
  const resp = await apiClient.get<UserSetting>(
    `/api/users/me/settings/${encodeURIComponent(key)}`,
  );
  return resp.data;
}

export async function putMySetting(key: string, valueJson: string | null): Promise<UserSetting> {
  const resp = await apiClient.put<UserSetting>(
    `/api/users/me/settings/${encodeURIComponent(key)}`,
    { valueJson },
  );
  return resp.data;
}

export async function deleteMySetting(key: string): Promise<void> {
  await apiClient.delete<void>(`/api/users/me/settings/${encodeURIComponent(key)}`);
}
