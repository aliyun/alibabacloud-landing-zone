import { ApiError, ErrorCodes } from '@/shared/types/common';

export interface WorkitemOwningWorkspace {
  workspaceId: number;
  workspaceName: string;
}

/**
 * 从 13023（工单属于其他项目）错误载荷解析归属项目。
 *
 * 服务端只在用户对归属项目仍有权限时才下发该码与载荷；载荷缺失、类型非法时返回 null，
 * 调用方回退到普通「工单不存在」错误态，不猜测目标项目。
 */
export function readWorkitemOwningWorkspace(error: unknown): WorkitemOwningWorkspace | null {
  if (!(error instanceof ApiError) || error.code !== ErrorCodes.WORKITEM_IN_OTHER_WORKSPACE) {
    return null;
  }
  const data = error.data as { workspaceId?: unknown; workspaceName?: unknown } | null | undefined;
  if (!data || typeof data !== 'object') return null;
  const workspaceId = typeof data.workspaceId === 'string' ? Number(data.workspaceId) : data.workspaceId;
  if (typeof workspaceId !== 'number' || !Number.isSafeInteger(workspaceId) || workspaceId <= 0) {
    return null;
  }
  if (typeof data.workspaceName !== 'string' || !data.workspaceName.trim()) return null;
  return { workspaceId, workspaceName: data.workspaceName };
}
