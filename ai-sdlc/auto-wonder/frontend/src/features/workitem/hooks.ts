import { message } from 'antd';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import * as api from './api';
import type { WorkitemQuery } from './api';

export function useWorkitemList(query: WorkitemQuery) {
  return useQuery({
    queryKey: ['workitems', query],
    queryFn: () => api.listWorkitems(query),
  });
}

export function useWorkitem(id: number | string) {
  return useQuery({
    queryKey: ['workitem', id],
    queryFn: () => api.getWorkitem(id),
    enabled: !!id,
  });
}

export function useTimeline(workitemId: number | string) {
  return useQuery({
    queryKey: ['workitem', workitemId, 'timeline'],
    queryFn: () => api.getTimeline(workitemId),
    enabled: !!workitemId,
  });
}

export function useComments(workitemId: number | string) {
  return useQuery({
    queryKey: ['workitem', workitemId, 'comments'],
    queryFn: () => api.getComments(workitemId),
    enabled: !!workitemId,
  });
}

export function useCreateWorkitem() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: api.createWorkitem,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['workitems'] }),
  });
}

export function useAddComment() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ workitemId, contentMd, targetAgentIds = [], targetHumanIds = [] }: {
      workitemId: number | string; contentMd: string; targetAgentIds?: number[]; targetHumanIds?: number[];
    }) => api.addComment(workitemId, contentMd, targetAgentIds, targetHumanIds),
    onSuccess: (_data, variables) => {
      queryClient.invalidateQueries({ queryKey: ['workitem', variables.workitemId, 'comments'] });
      queryClient.invalidateQueries({ queryKey: ['workitem', variables.workitemId, 'unified-timeline'] });
      queryClient.invalidateQueries({ queryKey: ['workitem', variables.workitemId, 'participants'] });
    },
  });
}

export function useAssignWorkitem() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, assigneeRef, sdlcId, squadId }: {
      id: number | string;
      assigneeRef: number;
      sdlcId?: number;
      squadId?: number;
    }) => api.assignWorkitem(id, 'AGENT', assigneeRef, sdlcId, squadId),
    onSuccess: (_data, variables) => {
      queryClient.invalidateQueries({ queryKey: ['workitem', variables.id] });
      queryClient.invalidateQueries({ queryKey: ['workitem', variables.id, 'delivery-progress'] });
      queryClient.invalidateQueries({ queryKey: ['workitem', variables.id, 'participants'] });
      queryClient.invalidateQueries({ queryKey: ['workitem', variables.id, 'unified-timeline'] });
    },
  });
}

export function useUpdateWorkitemContent() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, title, contentMd }: { id: number | string; title: string; contentMd: string }) =>
      api.updateWorkitemContent(id, title, contentMd),
    onSuccess: (data, variables) => {
      message.success('工单内容已保存');
      queryClient.setQueryData(['workitem', variables.id], data);
      queryClient.invalidateQueries({ queryKey: ['workitem', variables.id, 'unified-timeline'] });
      queryClient.invalidateQueries({ queryKey: ['workitems'] });
    },
  });
}

export function useDeleteWorkitem() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id }: { id: number | string }) => api.deleteWorkitem(id),
    onSuccess: () => {
      message.success('工单已删除');
      queryClient.invalidateQueries({ queryKey: ['workitems'] });
    },
    onError: (error: Error) => {
      message.error(error.message || '删除失败');
    },
  });
}

export function useTransitionWorkitem() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, toNodeId }: { id: number | string; toNodeId: number }) =>
      api.transitionWorkitem(id, toNodeId),
    onSuccess: (_data, variables) => {
      message.success('状态已流转');
      queryClient.invalidateQueries({ queryKey: ['workitem', variables.id] });
      queryClient.invalidateQueries({ queryKey: ['workitem', variables.id, 'delivery-progress'] });
      queryClient.invalidateQueries({ queryKey: ['workitem', variables.id, 'unified-timeline'] });
      queryClient.invalidateQueries({ queryKey: ['workitems'] });
    },
  });
}

export function useSyncExternalWorkitem() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id }: { id: number | string }) => api.syncExternalWorkitem(id),
    onSuccess: (result, variables) => {
      message.success(`同步完成：新增 ${result.imported}，更新 ${result.updated}，评论 ${result.commentsImported}`);
      queryClient.invalidateQueries({ queryKey: ['workitem', variables.id] });
      queryClient.invalidateQueries({ queryKey: ['workitem', variables.id, 'delivery-progress'] });
      queryClient.invalidateQueries({ queryKey: ['workitem', variables.id, 'participants'] });
      queryClient.invalidateQueries({ queryKey: ['workitem', variables.id, 'unified-timeline'] });
      queryClient.invalidateQueries({ queryKey: ['workitems'] });
    },
  });
}

export function useUnifiedTimeline(workitemId: number | string) {
  return useQuery({
    queryKey: ['workitem', workitemId, 'unified-timeline'],
    queryFn: () => api.getUnifiedTimeline(workitemId),
    enabled: !!workitemId,
    refetchInterval: (query) => query.state.data?.some((item) =>
      item.interactions?.some((interaction) => interaction.status === 'QUEUED' || interaction.status === 'DELIVERED'))
      ? 2000 : 30000,
  });
}

export function useParticipants(workitemId: number | string) {
  return useQuery({
    queryKey: ['workitem', workitemId, 'participants'],
    queryFn: () => api.getParticipants(workitemId),
    enabled: !!workitemId,
  });
}

export function useMentionCandidates(workitemId: number | string, query?: string | null) {
  return useQuery({
    queryKey: ['workitem', workitemId, 'mention-candidates', query || ''],
    queryFn: () => api.getMentionCandidates(workitemId, query || undefined),
    enabled: !!workitemId,
  });
}

export function useDeliveryProgress(workitemId: number | string) {
  return useQuery({
    queryKey: ['workitem', workitemId, 'delivery-progress'],
    queryFn: () => api.getDeliveryProgress(workitemId),
    enabled: !!workitemId,
    refetchInterval: (query) => {
      const data = query.state.data;
      const hasActive = data?.agents?.some((a) => a.status === 'active');
      return hasActive ? 5000 : 30000;
    },
  });
}

export function useContinueDispatch(workitemId: number | string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ dispatchId }: { dispatchId: number }) => api.continueDispatch(workitemId, dispatchId),
    onSuccess: () => {
      message.success('已创建恢复执行');
      queryClient.invalidateQueries({ queryKey: ['workitem', workitemId, 'delivery-progress'] });
      queryClient.invalidateQueries({ queryKey: ['workitem', workitemId, 'unified-timeline'] });
    },
  });
}

export function usePauseDispatch(workitemId: number | string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ dispatchId }: { dispatchId: number }) => api.pauseDispatch(workitemId, dispatchId),
    onSuccess: () => {
      message.success('已请求安全暂停，正在保存检查点');
      queryClient.invalidateQueries({ queryKey: ['workitem', workitemId, 'delivery-progress'] });
      queryClient.invalidateQueries({ queryKey: ['workitem', workitemId, 'unified-timeline'] });
    },
  });
}

export function useClarification(workitemId: number | string) {
  return useQuery({
    queryKey: ['workitem', workitemId, 'clarification'],
    queryFn: () => api.getClarification(workitemId),
    enabled: !!workitemId,
  });
}

export function useArtifacts(workitemId: number | string) {
  return useQuery({
    queryKey: ['workitem', workitemId, 'artifacts'],
    queryFn: () => api.getArtifacts(workitemId),
    enabled: !!workitemId,
  });
}

export function useRequirementDocuments(workitemId: number | string) {
  return useQuery({
    queryKey: ['workitem', workitemId, 'requirement-documents'],
    queryFn: () => api.getRequirementDocuments(workitemId),
    enabled: !!workitemId,
  });
}

export function useUploadRequirementDocuments(workitemId: number | string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ files }: { files: File[] }) => api.uploadRequirementDocuments(workitemId, files),
    onSuccess: () => {
      message.success('需求文档已上传');
      queryClient.invalidateQueries({ queryKey: ['workitem', workitemId, 'requirement-documents'] });
    },
    onError: (error: Error) => {
      message.error(error.message || '需求文档上传失败');
    },
  });
}

export function useDeleteRequirementDocument(workitemId: number | string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ artifactId }: { artifactId: number | string }) => api.deleteRequirementDocument(workitemId, artifactId),
    onSuccess: () => {
      message.success('需求文档已删除');
      queryClient.invalidateQueries({ queryKey: ['workitem', workitemId, 'requirement-documents'] });
    },
    onError: (error: Error) => {
      message.error(error.message || '需求文档删除失败');
    },
  });
}
