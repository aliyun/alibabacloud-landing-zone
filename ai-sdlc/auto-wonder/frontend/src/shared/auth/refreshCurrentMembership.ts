import axios from 'axios';
import { isWorkspaceAccessLevel } from './access';
import { useAuthStore } from './store';
import { ErrorCodes, type ApiResult } from '@/shared/types/common';

interface CurrentMembership {
  accessLevel: unknown;
}

const refreshesInFlight = new Map<string, Promise<void>>();

function clearInvalidMembership(code: unknown, token: string, workspaceId: number): void {
  const current = useAuthStore.getState();
  if (
    code === ErrorCodes.WORKSPACE_NOT_MEMBER
    && current.accessToken === token
    && current.currentWorkspace?.id === workspaceId
  ) {
    current.clearCurrentWorkspace();
  }
}

export function refreshCurrentMembership(): Promise<void> {
  const snapshot = useAuthStore.getState();
  if (!snapshot.accessToken || !snapshot.currentWorkspace) {
    return Promise.resolve();
  }

  const token = snapshot.accessToken;
  const workspaceId = snapshot.currentWorkspace.id;
  const refreshKey = `${token}:${workspaceId}`;
  const existingRefresh = refreshesInFlight.get(refreshKey);
  if (existingRefresh) {
    return existingRefresh;
  }

  const refresh = axios.get<ApiResult<CurrentMembership>>(
    '/api/workspaces/current/membership',
    { headers: { Authorization: `Bearer ${token}` } },
  ).then((response) => {
    const body = response.data;
    const current = useAuthStore.getState();
    if (!body.success) {
      clearInvalidMembership(body.code, token, workspaceId);
      return;
    }
    if (
      isWorkspaceAccessLevel(body.data?.accessLevel)
      && current.accessToken === token
      && current.currentWorkspace?.id === workspaceId
    ) {
      current.setAccessLevel(body.data.accessLevel);
    }
  }).catch((error: unknown) => {
    if (axios.isAxiosError<ApiResult<unknown>>(error)) {
      clearInvalidMembership(error.response?.data?.code, token, workspaceId);
    }
    // Transient refresh failures leave the last known snapshot unchanged.
  }).finally(() => {
    if (refreshesInFlight.get(refreshKey) === refresh) {
      refreshesInFlight.delete(refreshKey);
    }
  });
  refreshesInFlight.set(refreshKey, refresh);

  return refresh;
}
