import { apiClient } from '@/shared/api/client';
import { useAuthStore } from '@/shared/auth/store';
import { resolvePostLoginPath } from '@/shared/auth/returnPath';
import type { SwitchWorkspaceResponse } from '@/shared/types/common';

/**
 * 会话失效时保留了空间上下文，重新登录后先切回该空间，才能拿到带空间声明的访问令牌并
 * 恢复原页面。空间已不可用（删除/停用/非成员）时清掉绑定并退回空间选择页。
 */
export async function resolvePostLoginNavigation(from: unknown): Promise<string> {
  const target = resolvePostLoginPath(from);
  const workspace = useAuthStore.getState().currentWorkspace;
  if (!workspace) return target;
  try {
    const resp = await apiClient.post<SwitchWorkspaceResponse>(
      `/api/workspaces/${workspace.id}/switch`,
    );
    useAuthStore.getState().setAccessToken(resp.data.accessToken);
    useAuthStore.getState().setCurrentWorkspace(workspace, resp.data.accessLevel);
    return target;
  } catch {
    useAuthStore.getState().clearCurrentWorkspace();
    return '/workspaces';
  }
}
