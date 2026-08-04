import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import * as api from './api';
import type { CreateMemoryParams, Memory, UpdateMemoryParams, ReviewMemoryParams } from './api';
import { useAuthStore } from '@/shared/auth/store';

export function useMemoryList(params: {
  page?: number;
  size?: number;
  scope?: string;
  ownerRef?: number;
  type?: string;
  status?: string;
}) {
  return useQuery({
    queryKey: ['memories', params],
    queryFn: () => api.listMemories(params),
  });
}

export function usePendingReviews(params?: { page?: number; size?: number }) {
  return useQuery({
    queryKey: ['memories', 'reviews', params],
    queryFn: () => api.listPendingReviews(params),
  });
}

export function useCreateMemory() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (params: CreateMemoryParams) => api.createMemory(params),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['memories'] }),
  });
}

export function useUpdateMemory() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, params }: { id: number; params: UpdateMemoryParams }) =>
      api.updateMemory(id, params),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['memories'] }),
  });
}

export function useDeleteMemory() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => api.deleteMemory(id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['memories'] }),
  });
}

export function useReviewMemory() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, params }: { id: number; params: ReviewMemoryParams }) =>
      api.reviewMemory(id, params),
    onSuccess: (_data, { id }) => {
      // 审核台局部刷新：从所有 pending-reviews 列表缓存中原地移除已审核项，
      // 避免整页 refetch 导致所有卡片联动与网格重排抖动。
      const reviewQueries = queryClient
        .getQueryCache()
        .findAll({ queryKey: ['memories', 'reviews'] })
        .filter((q) => {
          const key = q.queryKey;
          return key.length >= 2 && key[0] === 'memories' && key[1] === 'reviews' && key[2] !== 'count';
        });
      for (const q of reviewQueries) {
        queryClient.setQueryData<Memory[] | undefined>(q.queryKey, (prev) =>
          prev ? prev.filter((m) => m.id !== id) : prev,
        );
      }
      // 通用记忆列表与计数仍需保持一致，做完整 invalidate。
      queryClient.invalidateQueries({ queryKey: ['memories'] });
      queryClient.invalidateQueries({ queryKey: ['memories', 'reviews', 'count'] });
    },
  });
}

export function useMemoryPendingReviewCount() {
  const hasAccess = useAuthStore((s) => s.hasAccess);
  return useQuery({
    queryKey: ['memories', 'reviews', 'count'],
    queryFn: api.getPendingReviewCount,
    refetchInterval: 60_000,
    staleTime: 60_000,
    refetchOnWindowFocus: false,
    enabled: hasAccess('READ_WRITE'),
  });
}
