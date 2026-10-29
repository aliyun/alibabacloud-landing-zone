import { message } from 'antd';
import { useNavigate } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { apiClient } from '@/shared/api/client';
import { useAuthStore } from '@/shared/auth/store';
import { ApiError, type SwitchWorkspaceResponse, type WorkspaceInfo } from '@/shared/types/common';
import { refreshTenantScopedQueries } from '@/features/workitem/queryCache';

/**
 * 切换当前工作空间并跳转：switch → 刷新 token/当前空间 → 失效租户级查询 → navigate。
 * 工作空间列表页与空间级看板下钻共用同一份实现，避免切换流程两处漂移。
 */
export function useSwitchWorkspace() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const setAccessToken = useAuthStore((state) => state.setAccessToken);
  const setCurrentWorkspace = useAuthStore((state) => state.setCurrentWorkspace);

  return async (workspace: WorkspaceInfo, destination: string = '/'): Promise<boolean> => {
    try {
      const resp = await apiClient.post<SwitchWorkspaceResponse>(`/api/workspaces/${workspace.id}/switch`);
      const { accessToken, accessLevel } = resp.data;
      setAccessToken(accessToken);
      setCurrentWorkspace(workspace, accessLevel);
      await refreshTenantScopedQueries(queryClient);
      navigate(destination);
      return true;
    } catch (e) {
      if (e instanceof ApiError) {
        message.error(e.message);
      }
      return false;
    }
  };
}
