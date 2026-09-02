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
  updateMemberAccess,
  updateMemberIdentityTags,
} from './api';
import type { WorkspaceAccessLevel } from '@/shared/types/common';

const MEMBERS_QUERY_KEY = ['members'] as const;
const CURRENT_MEMBERSHIP_QUERY_KEY = ['current-membership'] as const;
const ACCESS_REQUESTS_QUERY_KEY_PREFIX = ['workspace-access-requests'] as const;

export function accessRequestsQueryKey(status: string) {
  return [...ACCESS_REQUESTS_QUERY_KEY_PREFIX, status] as const;
}

function showMutationError(error: unknown) {
  message.error(error instanceof Error ? error.message : '操作失败');
}

export function useCurrentMembership() {
  const currentWorkspace = useAuthStore((state) => state.currentWorkspace);
  const setCurrentWorkspace = useAuthStore((state) => state.setCurrentWorkspace);
  const query = useQuery({
    queryKey: CURRENT_MEMBERSHIP_QUERY_KEY,
    queryFn: getCurrentMembership,
  });

  useEffect(() => {
    if (currentWorkspace && query.data) {
      setCurrentWorkspace(currentWorkspace, query.data.accessLevel);
    }
  }, [currentWorkspace, query.data, setCurrentWorkspace]);

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
    mutationFn: (userId: number) => addMember(userId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: MEMBERS_QUERY_KEY });
      queryClient.invalidateQueries({ queryKey: ['member-candidates'] });
      message.success('成员已添加');
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
      message.success('成员已移除');
    },
    onError: showMutationError,
  });
}

export function useUpdateMemberAccess() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ userId, accessLevel }: { userId: number; accessLevel: WorkspaceAccessLevel }) =>
      updateMemberAccess(userId, accessLevel),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: MEMBERS_QUERY_KEY });
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
      queryClient.invalidateQueries({ queryKey: CURRENT_MEMBERSHIP_QUERY_KEY });
      void queryClient.fetchQuery({
        queryKey: CURRENT_MEMBERSHIP_QUERY_KEY,
        queryFn: getCurrentMembership,
      }).then((membership) => {
        const auth = useAuthStore.getState();
        if (auth.currentWorkspace) {
          auth.setCurrentWorkspace(auth.currentWorkspace, membership.accessLevel);
        }
      }).catch(() => {
        message.warning('Owner 已移交，请刷新页面同步当前访问等级');
      });
      message.success('Owner 已移交');
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
      message.success('已通过该申请');
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
      message.success('已拒绝该申请');
    },
    onError: showMutationError,
  });
}
