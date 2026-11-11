import { apiClient } from '@/shared/api/client';
import type { ClarificationConversation, ClarificationTurnEvent } from '@/features/workitem/clarification/types';
export interface ConversationImage { id: string; name: string; contentType: string; size: number; url: string; }
export interface Conversation extends ClarificationConversation { title: string | null; archivedAt: string | null; turns: (ClarificationConversation['turns'][number] & { images?: ConversationImage[] })[]; }
export interface SetupStatus { agentId: number | null; executorId: number | null; agentConfigured: boolean; skillsConfigured: boolean; executorConfigured: boolean; launchConfigured: boolean; ready: boolean; missingSkills: string[]; }
export interface ActionPlan { id: string; turnId: number; actionsJson: string; payloadHash: string; status: string; resultJson: string | null; expiresAt: string; }
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

export function initializationContinuation(plan: ActionPlan): string | null {
  if (!['COMPLETED', 'FAILED'].includes(plan.status) || !plan.resultJson) return null;
  try {
    const results = JSON.parse(plan.resultJson);
    const workspace = Array.isArray(results) && results.find(item =>
      (item?.tool === 'autowonder.initialize_workspace' || item?.tool === 'autowonder.resume_workspace_initialization')
      && ((typeof item.result?.runId === 'number' && Number.isSafeInteger(item.result.runId) && item.result.runId > 0)
        || (typeof item.result?.runId === 'string' && /^[1-9]\d*$/.test(item.result.runId))));
    if (workspace) {
      return `[AutoWonder 初始化续接 ${plan.id}] 请调用 autowonder.get_workspace_initialization 查询同一批次 runId=${workspace.result.runId}。只有查询结果状态为 SUCCEEDED 时，才能确认初始化完成，并根据该批次记录为本次小队的执行器调用 autowonder.build_executor_launch_command，直接展示工具实际返回的启动命令，不要编造命令。其他状态或查询失败时不得声称初始化完成，请说明当前状态和下一步；不得自动重试或恢复初始化。不要重复初始化、导入模板或创建小队。按此前对话语言回复。`;
    }
    const outcome = plan.status === 'COMPLETED' && Array.isArray(results) && results.find(item =>
      (item?.tool === 'autowonder.initialize_harness' || item?.tool === 'autowonder.resume_harness_initialization')
      && item.result?.status === 'SUCCEEDED' && /^\d+$/.test(String(item.result.onboardingId)));
    return outcome
      ? `[AutoWonder 初始化续接 ${plan.id}] 模板已导入。请读取引导记录 ${outcome.result.onboardingId}，继续项目配置并直接展示下一步。不要重复导入模板。按此前对话语言回复。`
      : null;
  } catch {
    return null;
  }
}

export async function continueInitialization(id: number, planId: string, content: string, failedTurnId?: number) {
  await sendMessage(id, content, `initialization-continuation:${planId}:${failedTurnId ?? 'first'}`, null);
}
export async function replyElicitation(id: number, requestId: string, action: string, content?: Record<string, unknown>) { await apiClient.post(`${base}/${id}/elicitations/${requestId}/reply`, { action, content: content == null ? null : JSON.stringify(content) }); }

export async function getTurnEvents(id: number, turnId: number) { return (await apiClient.get<ClarificationTurnEvent[]>(`${base}/${id}/turns/${turnId}/events`)).data; }

export async function uploadImage(id: number, file: File) { const form = new FormData(); form.append('file', file); return (await apiClient.post<ConversationImage>(`${base}/${id}/images`, form, { transformRequest: [(data, headers) => { headers.delete?.('Content-Type'); delete headers['Content-Type']; delete headers['content-type']; return data; }] })).data; }

export interface TurnEventPage { rows: ClarificationTurnEvent[]; nextAfterId: number; hasMore: boolean; }
export async function getTurnEventPage(id: number, turnId: number, afterId: number): Promise<TurnEventPage> {
  return (await apiClient.get<TurnEventPage>(`${base}/${id}/turns/${turnId}/events/page`, { params: { afterId } })).data;
}
