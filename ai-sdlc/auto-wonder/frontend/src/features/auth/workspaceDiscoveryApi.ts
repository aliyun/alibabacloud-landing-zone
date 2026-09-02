import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiClient } from '@/shared/api/client';
import type {
  PageResult,
  WorkspaceAccessLevel,
  WorkspaceListItem,
} from '@/shared/types/common';

export interface SubmitAccessRequestParams {
  workspaceId: number;
  requestedLevel: WorkspaceAccessLevel;
}

export async function listAllWorkspaces(
  keyword: string,
  page: number,
  size: number,
): Promise<PageResult<WorkspaceListItem>> {
  const trimmed = keyword.trim();
  const resp = await apiClient.get<PageResult<WorkspaceListItem>>('/api/workspaces/all', {
    params: { ...(trimmed ? { keyword: trimmed } : {}), page, size },
  });
  return resp.data;
}

export async function submitAccessRequest(
  { workspaceId, requestedLevel }: SubmitAccessRequestParams,
): Promise<void> {
  await apiClient.post(`/api/workspaces/${workspaceId}/access-requests`, { requestedLevel });
}

export interface CancelAccessRequestParams {
  workspaceId: number;
  requestId: number;
}

export async function cancelAccessRequest(
  { workspaceId, requestId }: CancelAccessRequestParams,
): Promise<void> {
  await apiClient.post(`/api/workspaces/${workspaceId}/access-requests/${requestId}/cancel`);
}

export const ALL_WORKSPACES_QUERY_KEY_PREFIX = ['workspaces', 'all'] as const;

export function allWorkspacesQueryKey(keyword: string, page: number, size: number) {
  return [...ALL_WORKSPACES_QUERY_KEY_PREFIX, keyword, page, size] as const;
}

export function useAllWorkspaces(keyword: string, page: number, size: number) {
  // Trim once, then feed the SAME normalized value to both the key and the request
  // so "terra" and "terra " share one cache entry instead of splitting it.
  const trimmed = keyword.trim();
  return useQuery({
    queryKey: allWorkspacesQueryKey(trimmed, page, size),
    queryFn: () => listAllWorkspaces(trimmed, page, size),
    // keyword/page/size are part of the key, so every debounced keystroke and every
    // page click is a cache miss. Without this the results unmount and the list blanks
    // out mid-typing; the identity placeholder keeps the previous page on screen until
    // the next one resolves (v5 replacement for v4's keepPreviousData).
    placeholderData: (prev) => prev,
    // Overrides the app-wide staleTime of 30s: membershipStatus is the entire point of
    // this list and can change out-of-band (an admin approving a pending request), so a
    // user returning to the tab must see re-verified state rather than a stale cache.
    // Safe now that placeholderData keeps the old rows visible during the refetch.
    staleTime: 0,
  });
}

export function useSubmitAccessRequest() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: submitAccessRequest,
    onSuccess: () => {
      // Prefix match: keyword/page/size are part of the key, so only invalidating
      // the caller's exact key would leave every other cached search/page stale.
      queryClient.invalidateQueries({ queryKey: ALL_WORKSPACES_QUERY_KEY_PREFIX });
    },
  });
}

export function useCancelAccessRequest() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: cancelAccessRequest,
    onSuccess: () => {
      // Same prefix invalidation as submit: the card must flip from PENDING back to
      // NOT_MEMBER (可申请) on whatever search/page key is currently on screen.
      queryClient.invalidateQueries({ queryKey: ALL_WORKSPACES_QUERY_KEY_PREFIX });
    },
  });
}
