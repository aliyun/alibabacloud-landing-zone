import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import * as api from './api';
import { listWorkitems } from '@/features/workitem/api';

import { useAuthStore } from '@/shared/auth/store';

export function useAgentList(page: number, size: number, status?: string, kind?: api.AgentKind, squadIds?: number[]) {
  return useQuery({
    queryKey: ['agents', page, size, status, kind, squadIds],
    queryFn: () => api.listAgents({ page, size, status, kind, squadIds }),
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
      queryClient.invalidateQueries({ queryKey: ['pendingReviews'] });
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
      queryClient.invalidateQueries({ queryKey: ['pendingReviews'] });
      queryClient.invalidateQueries({ queryKey: ['agents', 'reviews', 'count'] });
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
      queryClient.invalidateQueries({ queryKey: ['pendingReviews'] });
      queryClient.invalidateQueries({ queryKey: ['agents', 'reviews', 'count'] });
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
  return useQuery({
    queryKey: ['pendingReviews'],
    queryFn: api.listPendingReviews,
  });
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
    queryKey: ['agents', 'reviews', 'count'],
    queryFn: api.getPendingReviewCount,
    refetchInterval: 60_000,
    staleTime: 60_000,
    refetchOnWindowFocus: false,
    enabled: hasAccess('READ_WRITE'),
  });
}
