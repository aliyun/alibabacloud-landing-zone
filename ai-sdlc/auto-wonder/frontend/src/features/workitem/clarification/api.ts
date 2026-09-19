import { apiClient } from '@/shared/api/client';
import type { ClarificationConversation, ClarificationTurnEvent } from './types';

const base = (workitemId: number | string) =>
  `/api/workitems/${workitemId}/clarification-conversations`;

function createClientMessageId(): string {
  const webCrypto = globalThis.crypto;
  if (typeof webCrypto?.randomUUID === 'function') {
    return webCrypto.randomUUID();
  }
  if (typeof webCrypto?.getRandomValues === 'function') {
    const bytes = webCrypto.getRandomValues(new Uint8Array(16));
    bytes[6] = (bytes[6] & 0x0f) | 0x40;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    const hex = Array.from(bytes, (value) => value.toString(16).padStart(2, '0')).join('');
    return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
  }
  return `fallback-${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;
}

export async function listClarificationConversations(
  workitemId: number | string,
  agentId: number,
): Promise<ClarificationConversation[]> {
  const resp = await apiClient.get<ClarificationConversation[]>(base(workitemId), {
    params: { agentId },
  });
  return resp.data;
}

export async function getOrCreateClarificationConversation(
  workitemId: number | string,
  agentId: number,
): Promise<ClarificationConversation> {
  const resp = await apiClient.post<ClarificationConversation>(base(workitemId), { agentId });
  return resp.data;
}

export async function getClarificationConversation(
  workitemId: number | string,
  conversationId: number,
): Promise<ClarificationConversation> {
  const resp = await apiClient.get<ClarificationConversation>(
    `${base(workitemId)}/${conversationId}`,
  );
  return resp.data;
}

export async function submitClarificationTurn(
  workitemId: number | string,
  conversationId: number,
  content: string,
): Promise<void> {
  const clientMessageId = createClientMessageId();
  await apiClient.post(`${base(workitemId)}/${conversationId}/turns`, {
    content,
    clientMessageId,
  });
}

export async function cancelClarificationTurn(
  workitemId: number | string,
  conversationId: number,
  turnId: number,
): Promise<void> {
  await apiClient.post(
    `${base(workitemId)}/${conversationId}/turns/${turnId}/cancel`,
  );
}

/** content 以字符串承载：requestedSchema 是任意 JSON Schema，答案结构由 Agent 决定，
 *  原样透传回执行器最安全，服务端与前端都不重排。 */
export async function replyClarificationElicitation(
  workitemId: number | string,
  conversationId: number,
  requestId: string,
  action: 'accept' | 'decline',
  content?: Record<string, unknown>,
): Promise<void> {
  await apiClient.post(
    `${base(workitemId)}/${conversationId}/elicitations/${encodeURIComponent(requestId)}/reply`,
    { action, content: content ? JSON.stringify(content) : null },
  );
}

/** 打开会话时触发带外命令探针：服务端去重后拉起执行器抓取斜杠命令，
 *  结果经实时 acp_commands 事件回推；失败静默，下次打开重试。 */
export async function refreshClarificationCommands(
  workitemId: number | string,
  conversationId: number,
): Promise<void> {
  await apiClient.post(`${base(workitemId)}/${conversationId}/commands/refresh`);
}

/** 现有 GET /events 只支持 afterId 且上限 200，取不全一轮，故单开按轮次端点。 */
export async function getClarificationTurnEvents(
  workitemId: number | string,
  conversationId: number,
  turnId: number,
): Promise<ClarificationTurnEvent[]> {
  const resp = await apiClient.get<ClarificationTurnEvent[]>(
    `${base(workitemId)}/${conversationId}/turns/${turnId}/events`,
  );
  return resp.data;
}
