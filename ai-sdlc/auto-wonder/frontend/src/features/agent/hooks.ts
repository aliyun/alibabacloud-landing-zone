import { useEffect, useRef } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import * as api from './api';
import { listWorkitems } from '@/features/workitem/api';

import { useAuthStore } from '@/shared/auth/store';

/** 审核角标与审核列表是两份独立缓存，键在此统一定义，避免两处漂移后再次出现不一致 */
export const PENDING_REVIEW_COUNT_QUERY_KEY = ['agents', 'reviews', 'count'] as const;
export const PENDING_REVIEWS_QUERY_KEY = ['pendingReviews'] as const;

export function useAgentList(status?: string, kind?: api.AgentKind, squadIds?: number[]) {
  return useQuery({
    queryKey: ['agents', status, kind, squadIds],
    queryFn: () => api.listAllAgents({ status, kind, squadIds }),
  });
}

export function useAgent(id: number) {
  const workspaceId = useAuthStore((state) => state.currentWorkspace?.id);
  return useQuery({
    queryKey: ['agent', id, 'detail', workspaceId],
    queryFn: () => api.getAgent(id),
    enabled: id > 0 && Boolean(workspaceId),
  });
}

export function useAgentVersions(agentId: number) {
  return useQuery({
    queryKey: ['agent', agentId, 'versions'],
    queryFn: () => api.listVersions(agentId),
    enabled: agentId > 0,
  });
}

export function useAgentVersion(agentId: number, versionNo: number) {
  const workspaceId = useAuthStore((state) => state.currentWorkspace?.id);
  return useQuery({
    queryKey: ['agent', agentId, 'version', versionNo, workspaceId],
    queryFn: () => api.getVersion(agentId, versionNo),
    enabled: agentId > 0 && versionNo > 0 && Boolean(workspaceId),
  });
}

export function useCreateAgent() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: api.createAgent,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['agents'] }),
  });
}

export function useEditConfig() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ agentId, config }: { agentId: number; config: api.UpdateConfigRequest }) =>
      api.editConfig(agentId, config),
    onSuccess: (_d, v) => {
      queryClient.invalidateQueries({ queryKey: ['agents'] });
      queryClient.invalidateQueries({ queryKey: ['agent', v.agentId] });
      queryClient.invalidateQueries({ queryKey: ['agent', v.agentId, 'versions'] });
      queryClient.invalidateQueries({ queryKey: ['agent', v.agentId, 'version'] });
    },
  });
}

export function useUpdateAgent() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, payload }: { id: number; payload: api.UpdateAgentRequest }) =>
      api.updateAgent(id, payload),
    onSuccess: (_d, v) => {
      queryClient.invalidateQueries({ queryKey: ['agents'] });
      queryClient.invalidateQueries({ queryKey: ['agent', v.id] });
      queryClient.invalidateQueries({ queryKey: ['agent', v.id, 'versions'] });
      queryClient.invalidateQueries({ queryKey: ['agent', v.id, 'version'] });
    },
  });
}

export function useSubmitForReview() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (agentId: number) => api.submitForReview(agentId),
    onSuccess: (_d, agentId) => {
      queryClient.invalidateQueries({ queryKey: ['agent', agentId] });
      queryClient.invalidateQueries({ queryKey: ['agents'] });
      queryClient.invalidateQueries({ queryKey: PENDING_REVIEWS_QUERY_KEY });
    },
  });
}

export function useApproveAgent() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ agentId, comment }: { agentId: number; comment?: string }) =>
      api.approveAgent(agentId, comment),
    onSuccess: (_d, v) => {
      queryClient.invalidateQueries({ queryKey: ['agent', v.agentId] });
      queryClient.invalidateQueries({ queryKey: ['agents'] });
      queryClient.invalidateQueries({ queryKey: PENDING_REVIEWS_QUERY_KEY });
      queryClient.invalidateQueries({ queryKey: PENDING_REVIEW_COUNT_QUERY_KEY });
    },
  });
}

export function useRejectAgent() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ agentId, comment }: { agentId: number; comment: string }) =>
      api.rejectAgent(agentId, comment),
    onSuccess: (_d, v) => {
      queryClient.invalidateQueries({ queryKey: ['agent', v.agentId] });
      queryClient.invalidateQueries({ queryKey: ['agents'] });
      queryClient.invalidateQueries({ queryKey: PENDING_REVIEWS_QUERY_KEY });
      queryClient.invalidateQueries({ queryKey: PENDING_REVIEW_COUNT_QUERY_KEY });
    },
  });
}

export function useRollback() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ agentId, versionNo }: { agentId: number; versionNo: number }) =>
      api.rollback(agentId, versionNo),
    onSuccess: (_d, v) => queryClient.invalidateQueries({ queryKey: ['agent', v.agentId] }),
  });
}

export function useOfflineAgent() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (agentId: number) => api.offlineAgent(agentId),
    onSuccess: (_d, agentId) => {
      queryClient.invalidateQueries({ queryKey: ['agent', agentId] });
      queryClient.invalidateQueries({ queryKey: ['agents'] });
    },
  });
}

export function useOnlineAgent() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (agentId: number) => api.onlineAgent(agentId),
    onSuccess: (_d, agentId) => {
      queryClient.invalidateQueries({ queryKey: ['agent', agentId] });
      queryClient.invalidateQueries({ queryKey: ['agents'] });
    },
  });
}

export function useDeleteAgent() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (agentId: number) => api.deleteAgent(agentId),
    onSuccess: (_d, agentId) => {
      queryClient.invalidateQueries({ queryKey: ['agent', agentId] });
      queryClient.invalidateQueries({ queryKey: ['agents'] });
    },
  });
}

export function useAddRepoPerm() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ agentId, repoId, permLevel, allowedBranchPatterns }: { agentId: number; repoId: number; permLevel: string; allowedBranchPatterns?: string[] }) =>
      api.addRepoPerm(agentId, repoId, permLevel, allowedBranchPatterns),
    onSuccess: (_d, v) => queryClient.invalidateQueries({ queryKey: ['agent', v.agentId] }),
  });
}

export function useRemoveRepoPerm() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ agentId, repoId }: { agentId: number; repoId: number }) =>
      api.removeRepoPerm(agentId, repoId),
    onSuccess: (_d, v) => queryClient.invalidateQueries({ queryKey: ['agent', v.agentId] }),
  });
}

export function useAddSkill() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ agentId, skillId }: { agentId: number; skillId: number }) =>
      api.addSkill(agentId, skillId),
    onSuccess: (_d, v) => queryClient.invalidateQueries({ queryKey: ['agent', v.agentId] }),
  });
}

export function useRemoveSkill() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ agentId, skillId }: { agentId: number; skillId: number }) =>
      api.removeSkill(agentId, skillId),
    onSuccess: (_d, v) => queryClient.invalidateQueries({ queryKey: ['agent', v.agentId] }),
  });
}

export function useAddMemoryRef() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ agentId, memoryId, source }: { agentId: number; memoryId: number; source: string }) =>
      api.addMemoryRef(agentId, memoryId, source),
    onSuccess: (_d, v) => queryClient.invalidateQueries({ queryKey: ['agent', v.agentId] }),
  });
}

export function useRemoveMemoryRef() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ agentId, memoryId }: { agentId: number; memoryId: number }) =>
      api.removeMemoryRef(agentId, memoryId),
    onSuccess: (_d, v) => queryClient.invalidateQueries({ queryKey: ['agent', v.agentId] }),
  });
}

export function useAddEnvironmentVariableRef() {
  return useMutation({
    mutationFn: ({ agentId, environmentVariableId }: { agentId: number; environmentVariableId: number }) =>
      api.addEnvironmentVariableRef(agentId, environmentVariableId),
  });
}

export function useRemoveEnvironmentVariableRef() {
  return useMutation({
    mutationFn: ({ agentId, environmentVariableId }: { agentId: number; environmentVariableId: number }) =>
      api.removeEnvironmentVariableRef(agentId, environmentVariableId),
  });
}

export function usePendingReviews() {
  const queryClient = useQueryClient();
  const query = useQuery({
    queryKey: PENDING_REVIEWS_QUERY_KEY,
    queryFn: api.listPendingReviews,
    staleTime: 0,
    refetchOnMount: 'always',
    refetchOnWindowFocus: true,
  });

  // 只订阅角标缓存、不额外发请求（角标查询由侧栏常驻维护）；
  // 需要 disabled 观察者才能在角标轮询/聚焦刷新后感知新值。
  const { data: pendingCount } = useQuery({
    queryKey: PENDING_REVIEW_COUNT_QUERY_KEY,
    queryFn: api.getPendingReviewCount,
    enabled: false,
  });

  // 列表刷新后若与角标缓存不一致，让角标重取服务端真值。
  // 反向联动见下方 effect：两处都只在双方数据都已缓存且仍不一致时触发失效，避免请求环。
  const pendingList = query.data;
  useEffect(() => {
    if (!pendingList) return;
    const cachedCount = queryClient.getQueryData<number>(PENDING_REVIEW_COUNT_QUERY_KEY);
    if (typeof cachedCount === 'number' && cachedCount !== pendingList.length) {
      void queryClient.invalidateQueries({ queryKey: PENDING_REVIEW_COUNT_QUERY_KEY });
    }
  }, [pendingList, queryClient]);

  // 角标轮询/聚焦刷新后若与已缓存的列表长度不一致，让列表重取服务端真值。
  // 只响应角标值的实际变化（首次值与相同值不触发）；联动只存在于审核页内，天然单实例。
  const lastObservedCount = useRef<number | undefined>(undefined);
  useEffect(() => {
    if (typeof pendingCount !== 'number') return;
    const previousCount = lastObservedCount.current;
    lastObservedCount.current = pendingCount;
    if (previousCount === undefined || previousCount === pendingCount) return;
    if (!pendingList || pendingList.length === pendingCount) return;
    void queryClient.invalidateQueries({ queryKey: PENDING_REVIEWS_QUERY_KEY });
  }, [pendingCount, pendingList, queryClient]);

  return query;
}

export function useAgentWorkitems(agentId: number) {
  return useQuery({
    queryKey: ['agent', agentId, 'workitems'],
    queryFn: async () => {
      const page = await listWorkitems({ page: 1, size: 100, assigneeType: 'AGENT', assigneeRef: agentId });
      return page.list;
    },
    enabled: agentId > 0,
  });
}

export function useAgentMemories(agentId: number) {
  return useQuery({
    queryKey: ['agent', agentId, 'memories'],
    queryFn: () => api.listAgentMemories(agentId),
    enabled: agentId > 0,
  });
}

export function useAgentPendingReviewCount() {
  const hasAccess = useAuthStore((s) => s.hasAccess);
  return useQuery({
    queryKey: PENDING_REVIEW_COUNT_QUERY_KEY,
    queryFn: api.getPendingReviewCount,
    refetchInterval: 60_000,
    staleTime: 0,
    refetchOnWindowFocus: true,
    enabled: hasAccess('READ_WRITE'),
  });
}
