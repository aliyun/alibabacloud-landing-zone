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
  /** runtime 是否声明 CONVERSATION_ACP_INTERACTION_V1（不支持时前端不给出卡片交互入口） */
  acpInteractionSupported?: boolean;
  cliSessionRef: string | null;
  processingStatus: string | null;
  processingTurnId: number | null;
  lastTurnAt: string | null;
  gmtCreate: string;
  turns: ClarificationTurn[];
  /** 仅详情接口填充：刷新页面后据此恢复未解决的问答卡片 */
  pendingElicitations?: ClarificationElicitation[] | null;
  /** 上次探针缓存的斜杠命令快照（详情接口返回，可能缺省）。 */
  availableCommands?: AcpSlashCommand[] | null;
}

export interface ClarificationElicitation {
  requestId: string;
  turnId: number;
  mode: string | null;
  message: string | null;
  /** ACP requestedSchema 的 JSON 字符串，前端自行解析后按 JSON Schema 渲染 */
  requestedSchema: string | null;
  status: string;
  gmtCreate: string;
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

export type AcpPlanPriority = 'high' | 'medium' | 'low';
export type AcpPlanStatus = 'pending' | 'in_progress' | 'completed';

export interface AcpPlanEntry {
  content: string;
  priority: AcpPlanPriority;
  status: AcpPlanStatus;
}

export type AcpToolKind =
  | 'execute'
  | 'read'
  | 'edit'
  | 'delete'
  | 'move'
  | 'search'
  | 'think'
  | 'fetch'
  | 'other';

export interface AcpToolLocation {
  path: string;
  line?: number;
}

export interface AcpToolDiff {
  path: string;
  oldText?: string | null;
  newText?: string;
}

export interface AcpSlashCommand {
  name: string;
  description?: string;
  input?: { hint?: string } | null;
}

export type AcpElicitationAction = 'accept' | 'decline' | 'cancel';

/**
 * 所有 acp_* 事件与增强后的 tool_use / tool_result 共用一个 data 载荷袋：
 * 各事件类型只填自己那几个字段，前端按 eventType 取用。
 */
export interface AcpEventData {
  entries?: AcpPlanEntry[];
  availableCommands?: AcpSlashCommand[];
  requestId?: string;
  mode?: string;
  message?: string;
  toolCallId?: string;
  requestedSchema?: unknown;
  action?: AcpElicitationAction;
  content?: Record<string, unknown>;
  kind?: AcpToolKind;
  title?: string;
  locations?: AcpToolLocation[];
  diffs?: AcpToolDiff[];
  terminalOutput?: string;
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
  data?: AcpEventData | null;
}

export interface ConversationRealtimeEvent {
  conversationId: number;
  turnId: number;
  eventSeq: number;
  eventType: string;
  payload: ProviderEventPayload | null;
}
