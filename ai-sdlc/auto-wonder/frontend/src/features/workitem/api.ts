import { apiClient } from '@/shared/api/client';
import { useAuthStore } from '@/shared/auth/store';
import type { PageResult } from '@/shared/types/common';
import type { Workitem, WorkitemDetail, TimelineEvent, Comment, Participant, DeliveryProgress, TimelineItem, Clarification, Artifact, RuntimeTrace, RuntimeTraceObservation, RuntimeTraceTurn } from '@/shared/types/workitem';

export type WorkitemStatusCategory = 'NEW' | 'IN_PROGRESS' | 'PENDING_DECISION' | 'DONE';

export interface WorkitemQuery {
  page: number;
  size: number;
  workType?: string;
  statusNodeId?: number;
  statusCategory?: WorkitemStatusCategory;
  assigneeType?: string;
  assigneeRef?: number;
  pendingDecisionOnly?: boolean;
  mineScope?: 'CREATED' | 'ASSIGNED';
  keyword?: string;
  tag?: string;
}

export interface CreateWorkitemParams {
  workType: string;
  title: string;
  contentMd: string;
  priority?: number;
  assigneeType?: string;
  assigneeRef?: number | string;
  squadId?: number | string;
  scheduledStartAt?: string;
}

export async function listWorkitems(query: WorkitemQuery): Promise<PageResult<Workitem>> {
  const resp = await apiClient.get<PageResult<Workitem>>('/api/workitems', { params: query });
  return resp.data;
}

export async function getWorkitem(id: number | string): Promise<WorkitemDetail> {
  const resp = await apiClient.get<WorkitemDetail>(`/api/workitems/${id}`);
  return resp.data;
}

export async function createWorkitem(params: CreateWorkitemParams): Promise<Workitem> {
  const resp = await apiClient.post<Workitem>('/api/workitems', params);
  return resp.data;
}

export async function transitionWorkitem(id: number | string, toNodeId: number | string): Promise<Workitem> {
  const resp = await apiClient.post<Workitem>(`/api/workitems/${id}/transition`, { toNodeId });
  return resp.data;
}

export async function assignWorkitem(
  id: number | string,
  assigneeType: string,
  assigneeRef: number | string,
  sdlcId?: number | string,
  squadId?: number | string,
  scheduledStartAt?: string,
): Promise<Workitem> {
  const resp = await apiClient.put<Workitem>(`/api/workitems/${id}/assignee`, {
    assigneeType,
    assigneeRef,
    ...(sdlcId != null ? { sdlcId } : {}),
    ...(squadId != null ? { squadId } : {}),
    ...(scheduledStartAt ? { scheduledStartAt } : {}),
  });
  return resp.data;
}

export async function updateScheduledStart(
  id: number | string,
  params: { scheduledStartAt?: string | null; executeNow?: boolean },
): Promise<Workitem> {
  const resp = await apiClient.put<Workitem>(`/api/workitems/${id}/scheduled-start`, params);
  return resp.data;
}

export async function updateWorkitemTags(id: number | string, tags: string[]): Promise<Workitem> {
  const resp = await apiClient.put<Workitem>(`/api/workitems/${id}/tags`, { tags });
  return resp.data;
}

export async function updateWorkitemContent(id: number | string, title: string, contentMd: string): Promise<Workitem> {
  const resp = await apiClient.put<Workitem>(`/api/workitems/${id}/content`, { title, contentMd });
  return resp.data;
}

export async function deleteWorkitem(id: number | string): Promise<void> {
  await apiClient.delete(`/api/workitems/${id}`);
}

export async function getTimeline(workitemId: number | string): Promise<TimelineEvent[]> {
  const resp = await apiClient.get<TimelineEvent[]>(`/api/workitems/${workitemId}/timeline`);
  return resp.data;
}

export async function getComments(workitemId: number | string): Promise<Comment[]> {
  const resp = await apiClient.get<Comment[]>(`/api/workitems/${workitemId}/comments`);
  return resp.data;
}

export async function addComment(
  workitemId: number | string,
  contentMd: string,
  targetAgentIds: number[] = [],
  targetHumanIds: number[] = [],
): Promise<Comment> {
  const resp = await apiClient.post<Comment>(`/api/workitems/${workitemId}/comments`, {
    contentMd,
    targetAgentIds,
    ...(targetHumanIds.length > 0 ? { targetHumanIds } : {}),
  });
  return resp.data;
}

export interface ExternalSyncResult {
  imported: number;
  updated: number;
  commentsImported: number;
  workitemIds: Array<number | string>;
}

export async function syncExternalWorkitem(workitemId: number | string): Promise<ExternalSyncResult> {
  const resp = await apiClient.post<ExternalSyncResult>(`/api/workitems/${workitemId}/external-sync`);
  return resp.data;
}

export async function getUnifiedTimeline(workitemId: number | string): Promise<TimelineItem[]> {
  const resp = await apiClient.get<TimelineItem[]>(`/api/workitems/${workitemId}/unified-timeline`);
  return resp.data;
}

export async function getParticipants(workitemId: number | string): Promise<Participant[]> {
  const resp = await apiClient.get<Participant[]>(`/api/workitems/${workitemId}/participants`);
  return resp.data;
}

export async function getMentionCandidates(
  workitemId: number | string,
  q?: string,
  limit = 50,
): Promise<Participant[]> {
  const resp = await apiClient.get<Participant[]>(`/api/workitems/${workitemId}/mention-candidates`, {
    params: {
      limit,
      ...(q ? { q } : {}),
    },
  });
  return resp.data;
}

export async function getDeliveryProgress(workitemId: number | string): Promise<DeliveryProgress> {
  const resp = await apiClient.get<DeliveryProgress>(`/api/workitems/${workitemId}/delivery-progress`);
  return resp.data;
}

export async function getRuntimeTrace(dispatchId: number | string, afterSeq?: number | null): Promise<RuntimeTrace> {
  const resp = await apiClient.get<RuntimeTrace>(`/api/dispatches/${dispatchId}/runtime-trace`, {
    params: afterSeq == null ? undefined : { afterSeq },
  });
  return resp.data;
}

export async function getRuntimeTraceTurn(dispatchId: number | string, traceId: string): Promise<RuntimeTraceTurn> {
  const resp = await apiClient.get<RuntimeTraceTurn>(
    `/api/dispatches/${dispatchId}/runtime-trace/turns/${encodeURIComponent(traceId)}`,
  );
  return resp.data;
}

export async function getRuntimeTraceObservation(dispatchId: number | string, observationId: string): Promise<RuntimeTraceObservation> {
  const resp = await apiClient.get<RuntimeTraceObservation>(
    `/api/dispatches/${dispatchId}/runtime-trace/observations/${encodeURIComponent(observationId)}`,
  );
  return resp.data;
}

export async function getRuntimeTraceContext(dispatchId: number | string, contentRef: string): Promise<ArrayBuffer> {
  const token = useAuthStore.getState().accessToken;
  const query = new URLSearchParams({ ref: contentRef });
  const resp = await fetch(`/api/dispatches/${dispatchId}/runtime-trace/context?${query}`, {
    headers: token ? { Authorization: `Bearer ${token}` } : undefined,
  });
  if (!resp.ok) throw new Error(`Context file request failed: ${resp.status}`);
  return resp.arrayBuffer();
}

export async function continueDispatch(workitemId: number | string, dispatchId: number | string): Promise<{ dispatchId: number; attempt: number; status: string }> {
  const resp = await apiClient.post<{ dispatchId: number; attempt: number; status: string }>(
    `/api/workitems/${workitemId}/dispatches/${dispatchId}/continue`,
  );
  return resp.data;
}

export async function pauseDispatch(workitemId: number | string, dispatchId: number | string): Promise<{ dispatchId: number; attempt: number; status: string }> {
  const resp = await apiClient.post<{ dispatchId: number; attempt: number; status: string }>(
    `/api/workitems/${workitemId}/dispatches/${dispatchId}/pause`,
  );
  return resp.data;
}

export async function getClarification(workitemId: number | string): Promise<Clarification | null> {
  try {
    const resp = await apiClient.get<Clarification>(`/api/workitems/${workitemId}/clarification`);
    return resp.data;
  } catch {
    return null;
  }
}

export async function getArtifacts(workitemId: number | string): Promise<Artifact[]> {
  const resp = await apiClient.get<Artifact[]>(`/api/workitems/${workitemId}/artifacts`);
  return resp.data;
}

export async function getRequirementDocuments(workitemId: number | string): Promise<Artifact[]> {
  const resp = await apiClient.get<Artifact[]>(`/api/workitems/${workitemId}/requirement-documents`);
  return resp.data;
}

export async function uploadRequirementDocuments(workitemId: number | string, files: File[]): Promise<Artifact[]> {
  const formData = new FormData();
  for (const file of files) {
    formData.append('files', file);
  }
  const resp = await apiClient.post<Artifact[]>(`/api/workitems/${workitemId}/requirement-documents`, formData, {
    transformRequest: [(data, headers) => {
      if (headers) {
        headers.delete?.('Content-Type');
        delete headers['Content-Type'];
        delete headers['content-type'];
      }
      return data;
    }],
  });
  return resp.data;
}

export async function deleteRequirementDocument(workitemId: number | string, artifactId: number | string): Promise<void> {
  await apiClient.delete(`/api/workitems/${workitemId}/requirement-documents/${artifactId}`);
}

function forceHttpsDownloadUrl(url: string): string {
  return url.startsWith('http://') ? `https://${url.slice('http://'.length)}` : url;
}

export async function getArtifactDownloadUrl(artifactId: number | string): Promise<string> {
  const resp = await apiClient.get<string>(`/api/artifacts/${artifactId}/download`);
  return forceHttpsDownloadUrl(resp.data);
}

export function getArtifactPreviewUrl(artifactId: number | string): string {
  return `/api/artifacts/${artifactId}/preview`;
}

export async function getArtifactPreviewBlob(artifactId: number | string): Promise<Blob> {
  const headers = new Headers();
  const token = useAuthStore.getState().accessToken;
  if (token) {
    headers.set('Authorization', `Bearer ${token}`);
  }
  const resp = await fetch(getArtifactPreviewUrl(artifactId), { headers });
  if (resp.status === 401) {
    useAuthStore.getState().clear();
    window.location.href = '/login';
    throw new Error('HTTP 401');
  }
  if (!resp.ok) {
    throw new Error(`HTTP ${resp.status}`);
  }
  return resp.blob();
}
