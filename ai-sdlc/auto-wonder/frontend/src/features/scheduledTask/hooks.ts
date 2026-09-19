import { message } from 'antd';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import * as api from './api';
import type { CreateScheduledTaskBody, ScheduledTaskListQuery, UpdateScheduledTaskBody } from './types';

export const scheduledTaskCapabilityQueryKey = ['capabilities', 'scheduled-task'] as const;

export function useScheduledTaskCapability() {
  return useQuery({
    queryKey: scheduledTaskCapabilityQueryKey,
    queryFn: api.getScheduledTaskCapability,
    staleTime: 60_000,
    retry: false,
  });
}

export function useScheduledTaskList(query: ScheduledTaskListQuery = {}) {
  return useQuery({ queryKey: ['scheduled-tasks', query], queryFn: () => api.listScheduledTasks(query) });
}

export function useScheduledTask(id?: number) {
  return useQuery({ queryKey: ['scheduled-tasks', id], queryFn: () => api.getScheduledTask(id!), enabled: Boolean(id) });
}

export function useCreateScheduledTask() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: CreateScheduledTaskBody) => api.createScheduledTask(body),
    onSuccess: () => client.invalidateQueries({ queryKey: ['scheduled-tasks'] }),
  });
}

export function useUpdateScheduledTask(id: number) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: UpdateScheduledTaskBody) => api.updateScheduledTask(id, body),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ['scheduled-tasks'] });
      client.invalidateQueries({ queryKey: ['scheduled-tasks', id] });
    },
  });
}

export function useRunScheduledTaskNow() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ id, version, requestId }: { id: number; version: number; requestId: string }) => api.runScheduledTaskNow(id, version, requestId),
    onSuccess: (_run, variables) => {
      message.success('已创建运行实例');
      client.invalidateQueries({ queryKey: ['scheduled-tasks'] });
      client.invalidateQueries({ queryKey: ['scheduled-task-runs', variables.id] });
    },
  });
}

export function useDeleteScheduledTask() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ id, version }: { id: number; version: number }) => api.deleteScheduledTask(id, version),
    onSuccess: () => {
      message.success('定时任务已删除');
      client.invalidateQueries({ queryKey: ['scheduled-tasks'] });
    },
  });
}

export function useUploadScheduledTaskDocuments() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ id, files }: { id: number; files: File[] }) => api.uploadScheduledTaskDocuments(id, files),
    onSuccess: (_data, variables) => { message.success('需求文档已上传'); client.invalidateQueries({ queryKey: ['scheduled-tasks', variables.id, 'documents'] }); },
  });
}

export function useDeleteScheduledTaskDocument() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ id, artifactId }: { id: number; artifactId: number }) => api.deleteScheduledTaskDocument(id, artifactId),
    onSuccess: (_data, variables) => { message.success('附件已删除'); client.invalidateQueries({ queryKey: ['scheduled-tasks', variables.id, 'documents'] }); },
  });
}

export function useScheduledTaskRunParticipants(runId?: number) {
  return useQuery({
    queryKey: ['scheduled-task-run', runId, 'participants'],
    queryFn: () => api.getScheduledTaskRunParticipants(runId!),
    enabled: Boolean(runId),
  });
}

/** 仅在评论框进入 @ 候选态时拉取，避免未使用 @ 的页面断言被额外请求干扰；查询透传给后端以便召回默认 limit 之外的成员。 */
export function useScheduledTaskRunMentionCandidates(runId?: number, q?: string | null, enabled = false) {
  return useQuery({
    queryKey: ['scheduled-task-run', runId, 'mention-candidates', q || ''],
    queryFn: () => api.getScheduledTaskRunMentionCandidates(runId!, q || undefined),
    enabled: Boolean(runId) && enabled,
    staleTime: 60_000,
  });
}

export function useScheduledTaskRunDeliveryProgress(runId?: number) {
  return useQuery({
    queryKey: ['scheduled-task-run', runId, 'delivery-progress'],
    queryFn: () => api.getScheduledTaskRunDeliveryProgress(runId!),
    enabled: Boolean(runId),
  });
}
