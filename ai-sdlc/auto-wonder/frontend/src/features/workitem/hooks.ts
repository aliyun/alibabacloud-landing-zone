import { transitionWarningText } from './runtimeLabels';
import { message } from 'antd';
import { i18n } from '@/shared/i18n';
import { keepPreviousData, useQuery, useQueries, useMutation, useQueryClient } from '@tanstack/react-query';
import * as api from './api';
import type { WorkitemQuery, WorkitemStatusCategory, RecycledWorkitemQuery } from './api';
import type { Workitem } from '@/shared/types/workitem';

export function useWorkitemList(query: WorkitemQuery, options?: { enabled?: boolean }) {
  return useQuery({
    queryKey: ['workitems', query],
    queryFn: () => api.listWorkitems(query),
    enabled: options?.enabled ?? true,
  });
}

/** 看板每列的服务端查询结果：total 是该状态的真实总数，与当前加载条数无关。 */
export interface WorkitemKanbanColumnResult {
  key: WorkitemStatusCategory;
  items: Workitem[];
  total: number;
  isLoading: boolean;
  hasMore: boolean;
}

/**
 * 看板按状态列分别向服务端查询：每列自己带 statusCategory 过滤，
 * 因此列内容只取决于该状态下的工单，不会随「全量列表的页码」变化。
 */
export function useWorkitemKanbanColumns(
  base: Omit<WorkitemQuery, 'page' | 'size' | 'statusCategory'>,
  columnKeys: WorkitemStatusCategory[],
  columnSizes: Record<string, number>,
  defaultSize: number,
  enabled: boolean,
): WorkitemKanbanColumnResult[] {
  const results = useQueries({
    queries: columnKeys.map((key) => {
      const query: WorkitemQuery = {
        ...base,
        statusCategory: key,
        page: 1,
        size: columnSizes[key] ?? defaultSize,
      };
      return {
        queryKey: ['workitems', query],
        queryFn: () => api.listWorkitems(query),
        enabled,
        refetchInterval: 5000,
      };
    }),
  });

  return columnKeys.map((key, index) => {
    const result = results[index];
    const items = result?.data?.list ?? [];
    const total = result?.data?.total ?? 0;
    return {
      key,
      items,
      total,
      isLoading: !!result?.isLoading,
      hasMore: items.length > 0 && items.length < total,
    };
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
      queryClient.invalidateQueries({ queryKey: ['workitem', variables.workitemId, 'delivery-progress'] });
    },
  });
}

export function useAssignWorkitem() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, assigneeType = 'AGENT', assigneeRef, sdlcId, squadId, scheduledStartAt, restartToken }: {
      id: number | string;
      assigneeType?: 'AGENT' | 'HUMAN';
      assigneeRef: number;
      sdlcId?: number;
      squadId?: number;
      scheduledStartAt?: string;
      restartToken?: string;
    }) => api.assignWorkitem(id, assigneeType, assigneeRef, sdlcId, squadId, scheduledStartAt, restartToken),
    onSuccess: (_data, variables) => {
      queryClient.invalidateQueries({ queryKey: ['workitem', variables.id] });
      queryClient.invalidateQueries({ queryKey: ['workitem', variables.id, 'delivery-progress'] });
      queryClient.invalidateQueries({ queryKey: ['workitem', variables.id, 'participants'] });
      queryClient.invalidateQueries({ queryKey: ['workitem', variables.id, 'unified-timeline'] });
      queryClient.invalidateQueries({ queryKey: ['workitems'] });
    },
  });
}

function invalidateWorkitemCaches(queryClient: ReturnType<typeof useQueryClient>, id: number | string) {
  queryClient.invalidateQueries({ queryKey: ['workitem', id] });
  queryClient.invalidateQueries({ queryKey: ['workitem', id, 'delivery-progress'] });
  queryClient.invalidateQueries({ queryKey: ['workitem', id, 'unified-timeline'] });
  queryClient.invalidateQueries({ queryKey: ['workitems'] });
}

export function useUpdateScheduledStart(workitemId: number | string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (params: { scheduledStartAt?: string | null; executeNow?: boolean }) =>
      api.updateScheduledStart(workitemId, params),
    onSuccess: (_data, variables) => {
      message.success(variables.executeNow ? i18n.t('workitem:messages.executedNow') : variables.scheduledStartAt ? i18n.t('workitem:messages.scheduledUpdated') : i18n.t('workitem:messages.scheduledCancelled'));
      invalidateWorkitemCaches(queryClient, workitemId);
    },
  });
}

export function useUpdateWorkitemTags(workitemId: number | string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ tags }: { tags: string[] }) => api.updateWorkitemTags(workitemId, tags),
    onSuccess: () => {
      message.success(i18n.t('workitem:messages.tagsUpdated'));
      queryClient.invalidateQueries({ queryKey: ['workitem', workitemId] });
      queryClient.invalidateQueries({ queryKey: ['workitems'] });
    },
  });
}

export function useUpdateWorkitemContent() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, title, contentMd }: { id: number | string; title: string; contentMd: string }) =>
      api.updateWorkitemContent(id, title, contentMd),
    onSuccess: (data, variables) => {
      message.success(i18n.t('workitem:messages.contentSaved'));
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
      message.success(i18n.t('workitem:messages.deleted'));
      queryClient.invalidateQueries({ queryKey: ['workitems'] });
      queryClient.invalidateQueries({ queryKey: ['workitems-recycled'] });
    },
    onError: (error: Error) => {
      message.error(error.message || i18n.t('workitem:messages.deleteFailed'));
    },
  });
}

export function useRecycledWorkitemList(query: RecycledWorkitemQuery, options?: { enabled?: boolean }) {
  return useQuery({
    queryKey: ['workitems-recycled', query],
    queryFn: () => api.listRecycledWorkitems(query),
    enabled: options?.enabled ?? true,
  });
}

export function useRestoreWorkitem() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id }: { id: number | string }) => api.restoreWorkitem(id),
    onSuccess: () => {
      message.success(i18n.t('workitem:messages.restored'));
      queryClient.invalidateQueries({ queryKey: ['workitems-recycled'] });
      queryClient.invalidateQueries({ queryKey: ['workitems'] });
    },
    onError: (error: Error) => {
      message.error(error.message || i18n.t('workitem:messages.restoreFailed'));
    },
  });
}

export function useTransitionWorkitem() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, toNodeId }: { id: number | string; toNodeId: number }) =>
      api.transitionWorkitem(id, toNodeId),
    onSuccess: (data, variables) => {
      message.success(i18n.t('workitem:messages.transitioned'));
      // 越界流转放行但提示（规格 3.3）：警告由服务端在响应中下发。
      if (data?.transitionWarning) {
        message.warning(transitionWarningText(data));
      }
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
      message.success(i18n.t('workitem:messages.syncCompleted', { imported: result.imported, updated: result.updated, commentsImported: result.commentsImported }));
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
    // 查询键随输入变化时保留上一批候选人，候选菜单在请求完成前不被清空。
    placeholderData: keepPreviousData,
  });
}

export function useWatchers(workitemId: number | string) {
  return useQuery({
    queryKey: ['workitem', workitemId, 'watchers'],
    queryFn: () => api.getWatchers(workitemId),
    enabled: !!workitemId,
  });
}

/**
 * 关注/取消关注。传入当前关注态 watched：已关注则取消，未关注则关注。
 * 后端幂等，成功后回填详情页、关注人列表与列表页缓存。
 */
export function useToggleWatch() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, watched }: { id: number | string; watched: boolean }) =>
      watched ? api.unwatchWorkitem(id) : api.watchWorkitem(id),
    onSuccess: (state, variables) => {
      message.success(state.watched ? i18n.t('workitem:messages.watched') : i18n.t('workitem:messages.unwatched'));
      queryClient.invalidateQueries({ queryKey: ['workitem', variables.id] });
      queryClient.invalidateQueries({ queryKey: ['workitem', variables.id, 'watchers'] });
      queryClient.invalidateQueries({ queryKey: ['workitems'] });
    },
    onError: (error: Error) => {
      message.error(error.message || i18n.t('workitem:messages.operationFailed'));
    },
  });
}

export function useDeliveryProgress(workitemId: number | string, statusCategory?: string | null) {
  return useQuery({
    queryKey: ['workitem', workitemId, 'delivery-progress'],
    queryFn: () => api.getDeliveryProgress(workitemId),
    retry: false,
    enabled: !!workitemId,
    refetchInterval: (query) => {
      if (query.state.status === 'error') return 30000;
      const data = query.state.data;
      const hasActive = data?.agents?.some((a) => a.status === 'active')
        || data?.executions?.some((execution) => ['PENDING', 'PACKAGING', 'DISPATCHED', 'ACKED', 'RUNNING', 'PAUSING', 'PAUSE_FAILED', 'WAITING_FOR_PAUSE'].includes(execution.status ?? ''));
      return hasActive || statusCategory === 'IN_PROGRESS' ? 5000 : 30000;
    },
  });
}

export function useContinueDispatch(workitemId: number | string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ dispatchId }: { dispatchId: number }) => api.continueDispatch(workitemId, dispatchId),
    onSuccess: () => {
      message.success(i18n.t('workitem:messages.continued'));
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
      message.success(i18n.t('workitem:messages.pauseRequested'));
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
      message.success(i18n.t('workitem:messages.documentsUploaded'));
      queryClient.invalidateQueries({ queryKey: ['workitem', workitemId, 'requirement-documents'] });
    },
    onError: (error: Error) => {
      message.error(error.message || i18n.t('workitem:messages.documentUploadFailed'));
    },
  });
}

export function useDeleteRequirementDocument(workitemId: number | string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ artifactId }: { artifactId: number | string }) => api.deleteRequirementDocument(workitemId, artifactId),
    onSuccess: () => {
      message.success(i18n.t('workitem:messages.documentDeleted'));
      queryClient.invalidateQueries({ queryKey: ['workitem', workitemId, 'requirement-documents'] });
    },
    onError: (error: Error) => {
      message.error(error.message || i18n.t('workitem:messages.documentDeleteFailed'));
    },
  });
}
