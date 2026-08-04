import { useEffect } from 'react';
import { message } from 'antd';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useAuthStore } from '@/shared/auth/store';
import {
  addMember,
  getCurrentMembership,
  listMembers,
  removeMember,
  searchMemberCandidates,
  transferOwner,
  updateMemberAccess,
  updateMemberIdentityTags,
} from './api';
import type { OrgAccessLevel } from '@/shared/types/common';

const MEMBERS_QUERY_KEY = ['members'] as const;
const CURRENT_MEMBERSHIP_QUERY_KEY = ['current-membership'] as const;

function showMutationError(error: unknown) {
  message.error(error instanceof Error ? error.message : '操作失败');
}

export function useCurrentMembership() {
  const currentOrg = useAuthStore((state) => state.currentOrg);
  const setCurrentOrg = useAuthStore((state) => state.setCurrentOrg);
  const query = useQuery({
    queryKey: CURRENT_MEMBERSHIP_QUERY_KEY,
    queryFn: getCurrentMembership,
  });

  useEffect(() => {
    if (currentOrg && query.data) {
      setCurrentOrg(currentOrg, query.data.accessLevel);
    }
  }, [currentOrg, query.data, setCurrentOrg]);

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
    mutationFn: ({ userId, accessLevel }: { userId: number; accessLevel: OrgAccessLevel }) =>
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
        if (auth.currentOrg) {
          auth.setCurrentOrg(auth.currentOrg, membership.accessLevel);
        }
      }).catch(() => {
        message.warning('Owner 已移交，请刷新页面同步当前访问等级');
      });
      message.success('Owner 已移交');
    },
    onError: showMutationError,
  });
}
