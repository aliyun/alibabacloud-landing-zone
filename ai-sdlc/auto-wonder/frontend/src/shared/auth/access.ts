import type { OrgAccessLevel } from '@/shared/types/common';

const ACCESS_LEVEL_RANK: Record<OrgAccessLevel, number> = {
  READ_ONLY: 0,
  READ_WRITE: 1,
  ADMIN: 2,
};

export const ACCESS_LEVEL_LABEL: Record<OrgAccessLevel, string> = {
  READ_ONLY: '只读权限',
  READ_WRITE: '读写权限',
  ADMIN: '管理员权限',
};

export function isOrgAccessLevel(value: unknown): value is OrgAccessLevel {
  return value === 'READ_ONLY' || value === 'READ_WRITE' || value === 'ADMIN';
}

export function allows(current: OrgAccessLevel | null, required: OrgAccessLevel): boolean {
  return current !== null && ACCESS_LEVEL_RANK[current] >= ACCESS_LEVEL_RANK[required];
}
