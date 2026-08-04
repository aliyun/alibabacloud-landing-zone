import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { message } from 'antd';
import type { WorkType } from './types';
import * as api from './api';

export function useTemplates(workType: WorkType) {
  return useQuery({
    queryKey: ['status-templates', workType],
    queryFn: () => api.listTemplates(workType),
  });
}

export function useTemplateDetail(id: number | null) {
  return useQuery({
    queryKey: ['status-template-detail', id],
    queryFn: () => api.getTemplateDetail(id!),
    enabled: id != null,
  });
}

export function useCreateTemplate(workType: WorkType) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: api.createTemplate,
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['status-templates', workType] }); message.success('模版创建成功'); },
  });
}

export function useUpdateTemplate(workType: WorkType) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, data }: { id: number; data: Parameters<typeof api.updateTemplate>[1] }) => api.updateTemplate(id, data),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['status-templates', workType] }); message.success('已保存'); },
  });
}

export function useDeleteTemplate(workType: WorkType) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: api.deleteTemplate,
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['status-templates', workType] }); message.success('已删除'); },
  });
}

export function useCreateNode(templateId: number) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (data: Parameters<typeof api.createNode>[1]) => api.createNode(templateId, data),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['status-template-detail', templateId] }); message.success('节点已添加'); },
  });
}

export function useUpdateNode(templateId: number) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ nodeId, data }: { nodeId: number; data: Parameters<typeof api.updateNode>[2] }) => api.updateNode(templateId, nodeId, data),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['status-template-detail', templateId] }); message.success('已保存'); },
  });
}

export function useDeleteNode(templateId: number) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (nodeId: number) => api.deleteNode(templateId, nodeId),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['status-template-detail', templateId] }); message.success('节点已删除'); },
  });
}

export function useCreateTransition(templateId: number) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (data: Parameters<typeof api.createTransition>[1]) => api.createTransition(templateId, data),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['status-template-detail', templateId] }); message.success('流转已添加'); },
  });
}

export function useUpdateTransition(templateId: number) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ tid, data }: { tid: number; data: Parameters<typeof api.updateTransition>[2] }) => api.updateTransition(templateId, tid, data),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['status-template-detail', templateId] }); message.success('已保存'); },
  });
}

export function useDeleteTransition(templateId: number) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (tid: number) => api.deleteTransition(templateId, tid),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['status-template-detail', templateId] }); message.success('流转已删除'); },
  });
}
