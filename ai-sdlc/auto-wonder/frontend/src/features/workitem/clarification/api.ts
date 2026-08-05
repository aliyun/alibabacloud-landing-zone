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

export async function getClarificationEvents(
  workitemId: number | string,
  conversationId: number,
  afterId: number = 0,
): Promise<ClarificationTurnEvent[]> {
  const resp = await apiClient.get<ClarificationTurnEvent[]>(
    `${base(workitemId)}/${conversationId}/events`,
    { params: { afterId } },
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
