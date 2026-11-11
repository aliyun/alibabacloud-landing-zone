import { apiClient } from '@/shared/api/client';

export type WorkspaceGuideStatus = 'PENDING' | 'IN_PROGRESS' | 'SKIPPED' | 'COMPLETED';

export interface WorkspaceGuide {
  eligible: boolean;
  status: WorkspaceGuideStatus | null;
  stage: number | null;
  substep: number | null;
  conversationId: number | null;
  clientMessageId: string | null;
  version: number | null;
  globallyDisabled: boolean;
  /** 57450 初始化能力是否已随平台技能目录安装；缺失时不能宣称可接力。 */
  capabilityReady: boolean;
}

export interface UpdateWorkspaceGuideBody {
  status: 'IN_PROGRESS' | 'COMPLETED';
  stage: number;
  substep: number;
  version: number;
  conversationId?: number | null;
  clientMessageId?: string | null;
}

const base = '/api/workspace-guide';

export async function getWorkspaceGuide(): Promise<WorkspaceGuide> {
  return (await apiClient.get<WorkspaceGuide>(base)).data;
}

export async function advanceWorkspaceGuide(body: UpdateWorkspaceGuideBody): Promise<WorkspaceGuide> {
  return (await apiClient.put<WorkspaceGuide>(base, body)).data;
}

export async function skipWorkspaceGuide(version: number): Promise<WorkspaceGuide> {
  return (await apiClient.post<WorkspaceGuide>(`${base}/skip`, { version })).data;
}

export function workspaceGuideQueryKey(workspaceId: number | null | undefined, userId?: number | null) {
  return ['workspace-guide', workspaceId, userId ?? null] as const;
}

/** 账号级「不再提示」偏好；与普通跳过分开，存在用户设置里跨设备生效。 */
export const GUIDE_DISABLED_SETTING_KEY = 'workspace_guide.disabled';
