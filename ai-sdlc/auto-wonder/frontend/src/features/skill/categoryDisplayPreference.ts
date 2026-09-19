import { readViewPreference, writeViewPreference } from '@/shared/lib/viewPreference';

// 「按分类展示」是按用户 + 项目（工作空间）维度记住的展示偏好，首次默认关闭；
// 与工单澄清降噪等既有 UI 偏好同一套 localStorage 做法。
const KEY_PREFIX = 'autowonder.skills.groupByCategory';
const OPTIONS = ['off', 'on'] as const;

function scopedKey(workspaceId: number | null | undefined, userId: number | null | undefined): string {
  return `${KEY_PREFIX}.${workspaceId ?? 'none'}.${userId ?? 'none'}`;
}

export function isCategoryGroupingEnabled(
  workspaceId: number | null | undefined,
  userId: number | null | undefined,
): boolean {
  return readViewPreference(scopedKey(workspaceId, userId), OPTIONS, 'off') === 'on';
}

export function setCategoryGroupingEnabled(
  workspaceId: number | null | undefined,
  userId: number | null | undefined,
  enabled: boolean,
): void {
  writeViewPreference(scopedKey(workspaceId, userId), enabled ? 'on' : 'off');
}
