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

export interface OrgInfo {
  id: number;
  name: string;
  description: string;
}

export type OrgAccessLevel = 'READ_ONLY' | 'READ_WRITE' | 'ADMIN';

export interface LoginResponse {
  userId: number;
  accessToken: string;
  refreshToken: string;
  user: UserInfo;
}

export interface SwitchOrgResponse {
  accessToken: string;
  accessLevel: OrgAccessLevel;
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
  ORG_NOT_MEMBER: '11001',
  ORG_ACCESS_INSUFFICIENT: '12008',
  NOT_FOUND: '10404',
  CONFLICT: '10409',
  RATE_LIMITED: '10429',
} as const;
