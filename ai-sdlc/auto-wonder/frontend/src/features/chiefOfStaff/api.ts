import { apiClient } from '@/shared/api/client';
import type { ClarificationConversation, ClarificationTurnEvent } from '@/features/workitem/clarification/types';
export interface ConversationImage { id: string; name: string; contentType: string; size: number; url: string; }
export interface Conversation extends ClarificationConversation { title: string | null; archivedAt: string | null; turns: (ClarificationConversation['turns'][number] & { images?: ConversationImage[] })[]; }
export interface SetupStatus { agentId: number | null; executorId: number | null; agentConfigured: boolean; skillsConfigured: boolean; executorConfigured: boolean; launchConfigured: boolean; ready: boolean; missingSkills: string[]; }
export interface ActionPlan { id: string; actionsJson: string; payloadHash: string; status: string; resultJson: string | null; expiresAt: string; }
const base = '/api/platform/conversations';
export async function setupStatus() { return (await apiClient.get<SetupStatus>('/api/platform-agent/setup')).data; }
export async function completeSetup(clientKind: string) { return (await apiClient.post<SetupStatus>('/api/platform-agent/setup', { clientKind })).data; }
export async function listConversations(archived: boolean, page: number) { return (await apiClient.get<Conversation[]>(base, { params: { archived, page, pageSize: 50 } })).data; }
export async function createConversation(agentId: number) { return (await apiClient.post<Conversation>(base, { agentId })).data; }
export async function getConversation(id: number) { return (await apiClient.get<Conversation>(`${base}/${id}`)).data; }
export async function patchConversation(id: number, body: { title?: string; archived?: boolean }) { return (await apiClient.patch<Conversation>(`${base}/${id}`, body)).data; }
export async function sendMessage(id: number, content: string, clientMessageId: string, pagePath: string | null, imageIds?: string[]) { await apiClient.post(`${base}/${id}/turns`, { content, clientMessageId, pagePath, imageIds }); }
export async function cancelTurn(id: number, turnId: number) { await apiClient.post(`${base}/${id}/turns/${turnId}/cancel`); }
export async function getEvents(id: number, afterId: number) { return (await apiClient.get<ClarificationTurnEvent[]>(`${base}/${id}/events`, { params: { afterId } })).data; }
export async function getPlans(id: number) { return (await apiClient.get<ActionPlan[]>(`${base}/${id}/action-plans`)).data; }
export async function resolvePlan(id: number, planId: string, payloadHash: string, confirm: boolean) { return (await apiClient.post<ActionPlan>(`${base}/${id}/action-plans/${planId}/resolve`, { confirm, payloadHash })).data; }
export async function replyElicitation(id: number, requestId: string, action: string, content?: Record<string, unknown>) { await apiClient.post(`${base}/${id}/elicitations/${requestId}/reply`, { action, content: content == null ? null : JSON.stringify(content) }); }

export async function getTurnEvents(id: number, turnId: number) { return (await apiClient.get<ClarificationTurnEvent[]>(`${base}/${id}/turns/${turnId}/events`)).data; }

export async function uploadImage(id: number, file: File) { const form = new FormData(); form.append('file', file); return (await apiClient.post<ConversationImage>(`${base}/${id}/images`, form, { transformRequest: [(data, headers) => { headers.delete?.('Content-Type'); delete headers['Content-Type']; delete headers['content-type']; return data; }] })).data; }

export interface TurnEventPage { rows: ClarificationTurnEvent[]; nextAfterId: number; hasMore: boolean; }
export async function getTurnEventPage(id: number, turnId: number, afterId: number): Promise<TurnEventPage> {
  return (await apiClient.get<TurnEventPage>(`${base}/${id}/turns/${turnId}/events/page`, { params: { afterId } })).data;
}
