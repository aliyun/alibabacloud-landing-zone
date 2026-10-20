import { apiClient } from '@/shared/api/client';

export type UserAdminStatus = 'NORMAL' | 'DELETED' | 'DEACTIVATED';
export type UserAdminStatusFilter = UserAdminStatus | 'ALL';
export type UserAdminTypeFilter = 'ALL' | 'USER' | 'ADMIN';
export type WorkspaceAccessLevel = 'READ_ONLY' | 'READ_WRITE' | 'ADMIN';

export interface UserAdminUser {
  id: number;
  username: string;
  nickname: string | null;
  email: string | null;
  phone: string | null;
  status: UserAdminStatus;
  isAdmin: boolean | null;
  createdAt: string | null;
  lastLoginAt: string | null;
  deletedAt: string | null;
  deletedById: number | null;
  deletedByName: string | null;
  editable: boolean | null;
  restorable: boolean | null;
}

export interface UserAdminList {
  totalAll: number;
  total: number;
  pageNum: number;
  pageSize: number;
  list: UserAdminUser[];
}

export interface UserAdminWorkspace {
  workspaceId: number;
  name: string;
  accessLevel: WorkspaceAccessLevel;
  isOwner: boolean | null;
  joinedAt: string | null;
}

export interface UserAdminImIdentity {
  provider: string;
  externalUserId: string | null;
  configured: boolean;
  platformReady: boolean;
  testAvailable: boolean;
}

export interface UserAdminListParams {
  keyword: string;
  status: UserAdminStatusFilter;
  type: UserAdminTypeFilter;
  page: number;
  size: number;
}

export interface UserAdminProfileUpdate {
  nickname: string;
  email: string;
  phone: string;
}

export interface UserAdminPasswordReset {
  newPassword: string;
  confirmPassword: string;
}

export const USER_ADMIN_QUERY_KEY_ROOT = ['platform-users'] as const;
export const USER_ADMIN_LIST_QUERY_KEY = ['platform-users', 'list'] as const;

export function userAdminListQueryKey(params: UserAdminListParams) {
  return [...USER_ADMIN_LIST_QUERY_KEY, params.keyword, params.status, params.type, params.page, params.size] as const;
}

export function userAdminDetailQueryKey(userId: number) {
  return ['platform-users', 'detail', userId] as const;
}

export function userAdminWorkspacesQueryKey(userId: number) {
  return ['platform-users', 'workspaces', userId] as const;
}

export function userAdminImQueryKey(userId: number) {
  return ['platform-users', 'im-identity', userId] as const;
}

const USER_ADMIN_PATH = '/api/platform/users';

export async function getUserAdminList(params: UserAdminListParams): Promise<UserAdminList> {
  const keyword = params.keyword.trim();
  const resp = await apiClient.get<UserAdminList>(USER_ADMIN_PATH, {
    params: {
      ...(keyword ? { keyword } : {}),
      ...(params.status !== 'ALL' ? { status: params.status } : {}),
      ...(params.type !== 'ALL' ? { type: params.type } : {}),
      page: params.page,
      size: params.size,
    },
  });
  return resp.data;
}

export async function getUserAdminDetail(userId: number): Promise<UserAdminUser> {
  const resp = await apiClient.get<UserAdminUser>(`${USER_ADMIN_PATH}/${userId}`);
  return resp.data;
}

export async function updateUserAdminProfile(
  userId: number,
  payload: UserAdminProfileUpdate,
): Promise<UserAdminUser> {
  const resp = await apiClient.put<UserAdminUser>(`${USER_ADMIN_PATH}/${userId}/profile`, payload);
  return resp.data;
}

export async function resetUserAdminPassword(
  userId: number,
  payload: UserAdminPasswordReset,
): Promise<void> {
  await apiClient.post(`${USER_ADMIN_PATH}/${userId}/password`, payload);
}

export async function deleteUserAdmin(userId: number, confirmUsername: string): Promise<void> {
  await apiClient.post(`${USER_ADMIN_PATH}/${userId}/delete`, { confirmUsername });
}

export async function restoreUserAdmin(userId: number): Promise<void> {
  await apiClient.post(`${USER_ADMIN_PATH}/${userId}/restore`);
}

export async function listUserAdminWorkspaces(userId: number): Promise<UserAdminWorkspace[]> {
  const resp = await apiClient.get<UserAdminWorkspace[]>(`${USER_ADMIN_PATH}/${userId}/workspaces`);
  return resp.data;
}

export async function addUserAdminWorkspace(
  userId: number,
  workspaceId: number,
  accessLevel: WorkspaceAccessLevel,
): Promise<UserAdminWorkspace[]> {
  const resp = await apiClient.post<UserAdminWorkspace[]>(`${USER_ADMIN_PATH}/${userId}/workspaces`, {
    workspaceId,
    accessLevel,
  });
  return resp.data;
}

export async function updateUserAdminWorkspaceLevel(
  userId: number,
  workspaceId: number,
  accessLevel: WorkspaceAccessLevel,
): Promise<UserAdminWorkspace[]> {
  const resp = await apiClient.put<UserAdminWorkspace[]>(
    `${USER_ADMIN_PATH}/${userId}/workspaces/${workspaceId}`,
    { accessLevel },
  );
  return resp.data;
}

export async function removeUserAdminWorkspace(
  userId: number,
  workspaceId: number,
): Promise<UserAdminWorkspace[]> {
  const resp = await apiClient.delete<UserAdminWorkspace[]>(
    `${USER_ADMIN_PATH}/${userId}/workspaces/${workspaceId}`,
  );
  return resp.data;
}

export async function listUserAdminImIdentities(userId: number): Promise<UserAdminImIdentity[]> {
  const resp = await apiClient.get<UserAdminImIdentity[]>(`${USER_ADMIN_PATH}/${userId}/im-identity`);
  return resp.data;
}

export async function updateUserAdminImIdentity(
  userId: number,
  provider: string,
  externalUserId: string,
): Promise<UserAdminImIdentity> {
  const resp = await apiClient.put<UserAdminImIdentity>(
    `${USER_ADMIN_PATH}/${userId}/im-identity/${encodeURIComponent(provider)}`,
    { externalUserId },
  );
  return resp.data;
}

export async function sendUserAdminImTest(userId: number, provider: string): Promise<void> {
  await apiClient.post(`${USER_ADMIN_PATH}/${userId}/im-identity/${encodeURIComponent(provider)}/test`);
}
