import { apiClient } from '@/shared/api/client';
import type { ClarificationConversation, ClarificationTurnEvent } from './types';

const base = (workitemId: number | string) =>
  `/api/workitems/${workitemId}/clarification-conversations`;

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
  const clientMessageId = crypto.randomUUID();
  await apiClient.post(`${base(workitemId)}/${conversationId}/turns`, {
    content,
    clientMessageId,
  });
}
