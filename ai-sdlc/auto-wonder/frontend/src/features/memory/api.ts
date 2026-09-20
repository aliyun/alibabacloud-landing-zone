import { apiClient } from '@/shared/api/client';

export interface Memory {
  id: number;
  scope: 'ORG' | 'SQUAD' | 'AGENT';
  ownerRef: number | null;
  type: 'FACT' | 'RULE' | 'PREFERENCE';
  title: string | null;
  contentMd: string | null;
  status: 'ADOPTED' | 'PENDING' | 'REJECTED';
  source: string | null;
  sourceRef: string | null;
  version: number;
  gmtCreate: string;
}

export interface CreateMemoryParams {
  scope: string;
  ownerRef?: number;
  type: string;
  title?: string;
  contentMd: string;
}

export interface UpdateMemoryParams {
  title?: string;
  contentMd: string;
  type?: string;
  scope?: string;
  ownerRef?: number;
}

export interface ReviewMemoryParams {
  decision: 'ADOPT' | 'REJECT';
  editedContentMd?: string;
  editedType?: Memory['type'];
  comment?: string;
  scope?: string;
  ownerRef?: number;
}

export interface MemoryListFilters {
  scope?: string;
  ownerRef?: number;
  type?: string;
  status?: string;
}

export async function listMemories(params: MemoryListFilters & {
  page?: number;
  size?: number;
}): Promise<Memory[]> {
  const resp = await apiClient.get<Memory[]>('/api/memories', { params });
  return resp.data;
}

export async function countMemories(params: MemoryListFilters): Promise<number> {
  const resp = await apiClient.get<number>('/api/memories/count', { params });
  return resp.data;
}

export interface MemoryGroup {
  scope: 'ORG' | 'SQUAD' | 'AGENT';
  ownerRef: number | null;
  ownerName: string | null;
  total: number;
  memories: Memory[];
}

export async function listMemoryGroups(params: MemoryListFilters & {
  page?: number;
  size?: number;
}): Promise<MemoryGroup[]> {
  const resp = await apiClient.get<MemoryGroup[]>('/api/memories/grouped', { params });
  return resp.data;
}

export async function countMemoryGroups(params: MemoryListFilters): Promise<number> {
  const resp = await apiClient.get<number>('/api/memories/grouped/count', { params });
  return resp.data;
}

export async function getMemory(id: number): Promise<Memory> {
  const resp = await apiClient.get<Memory>(`/api/memories/${id}`);
  return resp.data;
}

export async function createMemory(params: CreateMemoryParams): Promise<Memory> {
  const resp = await apiClient.post<Memory>('/api/memories', params);
  return resp.data;
}

export async function updateMemory(id: number, params: UpdateMemoryParams): Promise<Memory> {
  const resp = await apiClient.put<Memory>(`/api/memories/${id}`, params);
  return resp.data;
}

export async function deleteMemory(id: number): Promise<void> {
  await apiClient.delete(`/api/memories/${id}`);
}

export async function reviewMemory(id: number, params: ReviewMemoryParams): Promise<void> {
  await apiClient.post(`/api/memories/${id}/review`, params);
}

export async function listPendingReviews(params?: {
  page?: number;
  size?: number;
}): Promise<Memory[]> {
  const resp = await apiClient.get<Memory[]>('/api/memories/reviews', { params });
  return resp.data;
}

export async function getPendingReviewCount(): Promise<number> {
  const resp = await apiClient.get<number>('/api/memories/reviews/count');
  return resp.data;
}
