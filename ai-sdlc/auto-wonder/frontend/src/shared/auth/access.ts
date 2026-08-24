import type { WorkspaceAccessLevel } from '@/shared/types/common';

const ACCESS_LEVEL_RANK: Record<WorkspaceAccessLevel, number> = {
  READ_ONLY: 0,
  READ_WRITE: 1,
  ADMIN: 2,
};

export const ACCESS_LEVEL_LABEL: Record<WorkspaceAccessLevel, string> = {
  READ_ONLY: '只读权限',
  READ_WRITE: '读写权限',
  ADMIN: '管理员权限',
};

export function isWorkspaceAccessLevel(value: unknown): value is WorkspaceAccessLevel {
  return value === 'READ_ONLY' || value === 'READ_WRITE' || value === 'ADMIN';
}

export function allows(current: WorkspaceAccessLevel | null, required: WorkspaceAccessLevel): boolean {
  return current !== null && ACCESS_LEVEL_RANK[current] >= ACCESS_LEVEL_RANK[required];
}
