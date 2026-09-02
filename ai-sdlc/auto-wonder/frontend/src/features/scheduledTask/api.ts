import { apiClient } from '@/shared/api/client';
import type { Artifact, Comment, Workitem } from '@/shared/types/workitem';
import type { CreateScheduledTaskBody, OffsetPage, ScheduledTask, ScheduledTaskCapability, ScheduledTaskListQuery, ScheduledTaskRun, UpdateScheduledTaskBody } from './types';

export async function getScheduledTaskCapability(): Promise<ScheduledTaskCapability> {
  const resp = await apiClient.get<ScheduledTaskCapability>('/api/capabilities/scheduled-task');
  return resp.data;
}

export async function listScheduledTasks(query: ScheduledTaskListQuery = {}): Promise<OffsetPage<ScheduledTask>> {
  const resp = await apiClient.get<ScheduledTask[] | OffsetPage<ScheduledTask>>('/api/scheduled-tasks', { params: query });
  const data = resp.data;
  if (Array.isArray(data)) return { list: data, total: data.length, offset: query.offset ?? 0, size: query.size ?? data.length };
  return { ...data, offset: query.offset ?? 0, size: query.size ?? data.pageSize ?? data.list.length };
}

export async function previewScheduledTask(cronExpression: string, timezone: string, count = 5): Promise<string[]> {
  const resp = await apiClient.get<string[]>('/api/scheduled-tasks/preview', { params: { cronExpression, timezone, count } });
  return resp.data;
}
export async function getScheduledTaskSummary(params: Pick<ScheduledTaskListQuery, 'status' | 'squadId' | 'keyword'>): Promise<{ running: number; today: number; success30d: number; completed30d: number; attention: number }> { const resp = await apiClient.get('/api/scheduled-tasks/summary', { params }); return resp.data; }

export async function getScheduledTask(id: number): Promise<ScheduledTask> {
  const resp = await apiClient.get<ScheduledTask>(`/api/scheduled-tasks/${id}`);
  return resp.data;
}
export async function getScheduledTaskDocuments(id: number): Promise<Artifact[]> { const resp = await apiClient.get<Artifact[]>(`/api/scheduled-tasks/${id}/documents`); return resp.data; }
export async function getScheduledTaskHealth(id: number): Promise<{ completed30d: number; success30d: number }> { const resp = await apiClient.get(`/api/scheduled-tasks/${id}/health`); return resp.data; }

export async function createScheduledTask(body: CreateScheduledTaskBody): Promise<ScheduledTask> {
  const resp = await apiClient.post<ScheduledTask>('/api/scheduled-tasks', body);
  return resp.data;
}

export async function updateScheduledTask(id: number, body: UpdateScheduledTaskBody): Promise<ScheduledTask> {
  const resp = await apiClient.put<ScheduledTask>(`/api/scheduled-tasks/${id}`, body);
  return resp.data;
}

export async function transitionScheduledTask(id: number, action: 'enable' | 'pause' | 'archive', version: number): Promise<ScheduledTask> {
  const resp = await apiClient.post<ScheduledTask>(`/api/scheduled-tasks/${id}/${action}`, undefined, { params: { version } });
  return resp.data;
}

export async function runScheduledTaskNow(id: number, version: number, requestId: string): Promise<ScheduledTaskRun> {
  const resp = await apiClient.post<ScheduledTaskRun>(`/api/scheduled-tasks/${id}/run-now`, { version, requestId });
  return resp.data;
}

export async function listScheduledTaskRuns(id: number, size = 20, offset = 0): Promise<ScheduledTaskRun[]> {
  const resp = await apiClient.get<ScheduledTaskRun[]>(`/api/scheduled-tasks/${id}/runs`, { params: { size, offset } });
  return resp.data;
}

export async function getScheduledTaskRun(id: number): Promise<ScheduledTaskRun> { const resp = await apiClient.get<ScheduledTaskRun>(`/api/scheduled-task-runs/${id}`); return resp.data; }
export async function getScheduledTaskRunComments(id: number): Promise<Comment[]> { const resp = await apiClient.get<Comment[]>(`/api/scheduled-task-runs/${id}/comments`); return resp.data; }
export async function addScheduledTaskRunComment(id: number, contentMd: string): Promise<Comment> { const resp = await apiClient.post<Comment>(`/api/scheduled-task-runs/${id}/comments`, { contentMd }); return resp.data; }
export async function getScheduledTaskRunArtifacts(id: number): Promise<Artifact[]> { const resp = await apiClient.get<Artifact[]>(`/api/scheduled-task-runs/${id}/artifacts`); return resp.data; }
export async function getScheduledTaskRunEvents(id: number): Promise<ScheduledRunEvent[]> { const resp = await apiClient.get<ScheduledRunEvent[]>(`/api/scheduled-task-runs/${id}/events`); return resp.data; }
export async function getDerivedWorkitems(id: number): Promise<Workitem[]> { const resp = await apiClient.get<Workitem[]>(`/api/scheduled-task-runs/${id}/derived-workitems`); return resp.data; }
export async function transitionScheduledTaskRun(id: number, action: 'pause' | 'resume' | 'cancel', version: number): Promise<ScheduledTaskRun> { const resp = await apiClient.post<ScheduledTaskRun>(`/api/scheduled-task-runs/${id}/${action}`, undefined, { params: { version } }); return resp.data; }

export interface ScheduledRunEvent { id: number; eventType?: string; type?: string; payload?: string | Record<string, unknown> | null; gmtCreate?: string; }

export async function uploadScheduledTaskDocuments(id: number, files: File[]): Promise<Artifact[]> {
  const formData = new FormData();
  files.forEach((file) => formData.append('files', file));
  const resp = await apiClient.post<Artifact[]>(`/api/scheduled-tasks/${id}/documents`, formData, {
    transformRequest: [(data, headers) => {
      headers?.delete?.('Content-Type');
      if (headers) { delete headers['Content-Type']; delete headers['content-type']; }
      return data;
    }],
  });
  return resp.data;
}

export async function deleteScheduledTaskDocument(id: number, artifactId: number): Promise<void> {
  await apiClient.delete(`/api/scheduled-tasks/${id}/documents/${artifactId}`);
}

export async function getScheduledTaskRunParticipants(id: number): Promise<import('@/shared/types/workitem').Participant[]> {
  const resp = await apiClient.get<import('@/shared/types/workitem').Participant[]>(`/api/scheduled-task-runs/${id}/participants`);
  return resp.data;
}

export async function getScheduledTaskRunDeliveryProgress(id: number): Promise<import('@/shared/types/workitem').DeliveryProgress> {
  const resp = await apiClient.get<import('@/shared/types/workitem').DeliveryProgress>(`/api/scheduled-task-runs/${id}/delivery-progress`);
  return resp.data;
}
