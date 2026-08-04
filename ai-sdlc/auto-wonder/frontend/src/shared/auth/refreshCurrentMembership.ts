import axios from 'axios';
import { isOrgAccessLevel } from './access';
import { useAuthStore } from './store';
import { ErrorCodes, type ApiResult } from '@/shared/types/common';

interface CurrentMembership {
  accessLevel: unknown;
}

const refreshesInFlight = new Map<string, Promise<void>>();

function clearInvalidMembership(code: unknown, token: string, orgId: number): void {
  const current = useAuthStore.getState();
  if (
    code === ErrorCodes.ORG_NOT_MEMBER
    && current.accessToken === token
    && current.currentOrg?.id === orgId
  ) {
    current.clearCurrentOrg();
  }
}

export function refreshCurrentMembership(): Promise<void> {
  const snapshot = useAuthStore.getState();
  if (!snapshot.accessToken || !snapshot.currentOrg) {
    return Promise.resolve();
  }

  const token = snapshot.accessToken;
  const orgId = snapshot.currentOrg.id;
  const refreshKey = `${token}:${orgId}`;
  const existingRefresh = refreshesInFlight.get(refreshKey);
  if (existingRefresh) {
    return existingRefresh;
  }

  const refresh = axios.get<ApiResult<CurrentMembership>>(
    '/api/orgs/current/membership',
    { headers: { Authorization: `Bearer ${token}` } },
  ).then((response) => {
    const body = response.data;
    const current = useAuthStore.getState();
    if (!body.success) {
      clearInvalidMembership(body.code, token, orgId);
      return;
    }
    if (
      isOrgAccessLevel(body.data?.accessLevel)
      && current.accessToken === token
      && current.currentOrg?.id === orgId
    ) {
      current.setAccessLevel(body.data.accessLevel);
    }
  }).catch((error: unknown) => {
    if (axios.isAxiosError<ApiResult<unknown>>(error)) {
      clearInvalidMembership(error.response?.data?.code, token, orgId);
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
