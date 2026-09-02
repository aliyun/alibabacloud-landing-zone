export interface ApiResult<T> {
  success: boolean;
  code: string;
  message: string;
  data: T;
  traceId: string | null;
}

export interface PageResult<T> {
  list: T[];
  total: number;
  pageNum: number;
  pageSize: number;
}

export interface UserInfo {
  id: number;
  username: string;
  nickname: string;
  email: string;
}

export interface WorkspaceInfo {
  id: number;
  name: string;
  description: string;
}

export type WorkspaceAccessLevel = 'READ_ONLY' | 'READ_WRITE' | 'ADMIN';

export interface WorkspaceListItem {
  id: number;
  name: string;
  description: string;
  membershipStatus: 'MEMBER' | 'NOT_MEMBER' | 'PENDING';
  accessLevel: WorkspaceAccessLevel | null;
  pendingRequestId?: number | null;
}

export interface LoginResponse {
  userId: number;
  accessToken: string;
  refreshToken: string;
  user: UserInfo;
}

export interface SwitchWorkspaceResponse {
  accessToken: string;
  accessLevel: WorkspaceAccessLevel;
}

export class ApiError extends Error {
  code: string;
  traceId: string | null;

  constructor(code: string, message: string, traceId: string | null) {
    super(message);
    this.name = 'ApiError';
    this.code = code;
    this.traceId = traceId;
  }
}

export const ErrorCodes = {
  UNAUTHORIZED: '10401',
  NO_PERMISSION: '10403',
  WORKSPACE_NOT_MEMBER: '11001',
  WORKSPACE_ACCESS_INSUFFICIENT: '12008',
  NOT_FOUND: '10404',
  CONFLICT: '10409',
  RATE_LIMITED: '10429',
} as const;
