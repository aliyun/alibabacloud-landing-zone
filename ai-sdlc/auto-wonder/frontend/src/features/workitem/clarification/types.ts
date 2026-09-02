export interface ClarificationConversation {
  id: number;
  agentId: number;
  agentName: string | null;
  channelConversationId: string;
  status: string;
  executorOnline: boolean;
  streamingSupported: boolean;
  /** runtime 是否支持 CONVERSATION_TURN_CANCEL 协议（不支持时前端隐藏「终止响应」按钮） */
  cancelSupported: boolean;
  cliSessionRef: string | null;
  processingStatus: string | null;
  processingTurnId: number | null;
  lastTurnAt: string | null;
  gmtCreate: string;
  turns: ClarificationTurn[];
}

export interface ClarificationTurn {
  id: number;
  direction: string;
  content: string;
  status: string;
  error: string | null;
  gmtCreate: string;
}

export interface ClarificationTurnEvent {
  id: number;
  conversationId: number;
  turnId: number;
  dispatchAttempt: number;
  eventSeq: number;
  chunkIndex: number;
  chunkCount: number;
  eventType: string;
  payloadFragment: string;
  gmtCreate: string;
}

export interface ProviderEventPayload {
  type: string;
  content?: string;
  tool?: string;
  callId?: string;
  status?: string;
  sessionId?: string;
  turnId?: string;
  model?: string;
  input?: Record<string, unknown>;
  output?: string;
  durationMs?: number;
  errorCategory?: string;
}

export interface ConversationRealtimeEvent {
  conversationId: number;
  turnId: number;
  eventSeq: number;
  eventType: string;
  payload: ProviderEventPayload | null;
}
