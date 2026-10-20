import { i18n } from '@/shared/i18n';
import { useEffect } from 'react';
import { message } from 'antd';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useAuthStore } from '@/shared/auth/store';
import {
  addMember,
  approveAccessRequest,
  getCurrentMembership,
  listAccessRequests,
  listMembers,
  rejectAccessRequest,
  removeMember,
  searchMemberCandidates,
  transferOwner,
  updateMemberRole,
  listWorkspaceRoles,
  createWorkspaceRole,
  updateWorkspaceRole,
  deleteWorkspaceRole,
  type WorkspaceRoleRequest,
  updateMemberIdentityTags,
} from './api';

const ROLES_QUERY_KEY = ['workspace-roles'] as const;
const MEMBERS_QUERY_KEY = ['members'] as const;
const CURRENT_MEMBERSHIP_QUERY_KEY = ['current-membership'] as const;
const ACCESS_REQUESTS_QUERY_KEY_PREFIX = ['workspace-access-requests'] as const;

export function accessRequestsQueryKey(status: string) {
  return [...ACCESS_REQUESTS_QUERY_KEY_PREFIX, status] as const;
}

function showMutationError(error: unknown) {
  message.error(error instanceof Error ? error.message : i18n.t('settings:operationFailed'));
}

export function useCurrentMembership() {
  const currentWorkspace = useAuthStore((state) => state.currentWorkspace);
  const setCurrentWorkspace = useAuthStore((state) => state.setCurrentWorkspace);
  const setReadOnlyDomains = useAuthStore((state) => state.setReadOnlyDomains);
  const query = useQuery({
    queryKey: CURRENT_MEMBERSHIP_QUERY_KEY,
    queryFn: getCurrentMembership,
  });

  useEffect(() => {
    if (currentWorkspace && query.data) {
      setCurrentWorkspace(currentWorkspace, query.data.accessLevel);
      setReadOnlyDomains(query.data.readOnlyDomains ?? []);
    }
  }, [currentWorkspace, query.data, setCurrentWorkspace, setReadOnlyDomains]);

  return query;
}

export function useMembers() {
  return useQuery({ queryKey: MEMBERS_QUERY_KEY, queryFn: listMembers });
}

export function useMemberCandidates(keyword: string) {
  const trimmed = keyword.trim();
  return useQuery({
    queryKey: ['member-candidates', trimmed],
    queryFn: () => searchMemberCandidates(trimmed),
    enabled: trimmed.length > 0,
  });
}

export function useAddMember() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ userId, roleId }: { userId: number; roleId?: number }) => addMember(userId, roleId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: MEMBERS_QUERY_KEY });
      queryClient.invalidateQueries({ queryKey: ROLES_QUERY_KEY });
      queryClient.invalidateQueries({ queryKey: ['member-candidates'] });
      message.success(i18n.t('settings:memberAdded'));
    },
    onError: showMutationError,
  });
}

export function useRemoveMember() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (userId: number) => removeMember(userId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: MEMBERS_QUERY_KEY });
      queryClient.invalidateQueries({ queryKey: ROLES_QUERY_KEY });
      message.success(i18n.t('settings:memberRemoved'));
    },
    onError: showMutationError,
  });
}

export function useUpdateMemberRole() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ userId, roleId }: { userId: number; roleId: number }) =>
      updateMemberRole(userId, roleId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: MEMBERS_QUERY_KEY });
      queryClient.invalidateQueries({ queryKey: ROLES_QUERY_KEY });
      queryClient.invalidateQueries({ queryKey: CURRENT_MEMBERSHIP_QUERY_KEY });
    },
    onError: showMutationError,
  });
}

export function useUpdateMemberIdentityTags() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ userId, identityTags }: { userId: number; identityTags: string[] }) =>
      updateMemberIdentityTags(userId, identityTags),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: MEMBERS_QUERY_KEY });
      queryClient.invalidateQueries({ queryKey: ROLES_QUERY_KEY });
    },
    onError: showMutationError,
  });
}

export function useTransferOwner() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (targetUserId: number) => transferOwner(targetUserId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: MEMBERS_QUERY_KEY });
      queryClient.invalidateQueries({ queryKey: ROLES_QUERY_KEY });
      queryClient.invalidateQueries({ queryKey: CURRENT_MEMBERSHIP_QUERY_KEY });
      void queryClient.fetchQuery({
        queryKey: CURRENT_MEMBERSHIP_QUERY_KEY,
        queryFn: getCurrentMembership,
      }).then((membership) => {
        const auth = useAuthStore.getState();
        if (auth.currentWorkspace) {
          auth.setCurrentWorkspace(auth.currentWorkspace, membership.accessLevel);
          auth.setReadOnlyDomains(membership.readOnlyDomains ?? []);
        }
      }).catch(() => {
        message.warning(i18n.t('settings:ownerSyncWarning'));
      });
      message.success(i18n.t('settings:ownerTransferred'));
    },
    onError: showMutationError,
  });
}

export function useAccessRequests(status: string) {
  return useQuery({
    queryKey: accessRequestsQueryKey(status),
    queryFn: () => listAccessRequests(status),
  });
}

export function useApproveAccessRequest() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (requestId: number) => approveAccessRequest(requestId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ACCESS_REQUESTS_QUERY_KEY_PREFIX });
      queryClient.invalidateQueries({ queryKey: MEMBERS_QUERY_KEY });
      queryClient.invalidateQueries({ queryKey: ROLES_QUERY_KEY });
      message.success(i18n.t('settings:requestApproved'));
    },
    onError: showMutationError,
  });
}

export function useRejectAccessRequest() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ requestId, reason }: { requestId: number; reason?: string }) =>
      rejectAccessRequest(requestId, reason),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ACCESS_REQUESTS_QUERY_KEY_PREFIX });
      message.success(i18n.t('settings:requestRejected'));
    },
    onError: showMutationError,
  });
}

export function useWorkspaceRoles() {
  return useQuery({ queryKey: ROLES_QUERY_KEY, queryFn: listWorkspaceRoles });
}

export function useSaveWorkspaceRole() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, request }: { id?: number; request: WorkspaceRoleRequest }) =>
      id === undefined ? createWorkspaceRole(request) : updateWorkspaceRole(id, request),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ROLES_QUERY_KEY });
      queryClient.invalidateQueries({ queryKey: MEMBERS_QUERY_KEY });
      queryClient.invalidateQueries({ queryKey: CURRENT_MEMBERSHIP_QUERY_KEY });
      message.success(i18n.t('settings:roleSaved'));
    },
    onError: showMutationError,
  });
}

export function useDeleteWorkspaceRole() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, version }: { id: number; version: number }) => deleteWorkspaceRole(id, version),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ROLES_QUERY_KEY });
      message.success(i18n.t('settings:roleDeleted'));
    },
    onError: showMutationError,
  });
}
