export type AiSessionStatus =
  | 'QUEUED'
  | 'RUNNING'
  | 'WAIT_USER'
  | 'COMPLETED'
  | 'FAILED'
  | 'CANCELED';

export type AiMessageRole = 'USER' | 'AI' | 'SYSTEM';

export interface AiMessage {
  id: number;
  sessionId: number;
  role: AiMessageRole;
  content: string;
  gmtCreate: string;
}

export interface AiSession {
  id: number;
  scene: string;
  bizRefType: string;
  bizRefId: string | number;
  status: AiSessionStatus;
  resultJson: string | null;
  error: string | null;
  gmtCreate: string;
  messages: AiMessage[];
}

export type AiSessionVOResponse = AiSession;

export interface AiStreamEvent {
  type: 'ai_token' | 'ai_message_done' | 'session_status' | 'delta' | 'status' | 'result';
  sessionId?: number;
  token?: string;
  text?: string;
  message?: AiMessage;
  status?: AiSessionStatus;
  resultJson?: string;
}

// ---- result_json shapes (aligned to backend scene adapters) ----

export interface RepoScanResult {
  purpose: string;
  keyBusiness: string[];
  upstreams: string[];
  downstreams: string[];
  summaryMd: string;
}

export type MemoryItemType = '项目知识' | '工程规则' | '经验' | '偏好' | '避坑';

export interface MemoryImportItem {
  type: MemoryItemType;
  title: string;
  contentMd: string;
}

export interface MemoryImportResult {
  items: MemoryImportItem[];
}

export type SdlcStepKind = 'analysis' | 'implementation' | 'test' | 'review' | 'artifact' | 'handoff' | 'cleanup';

export interface SdlcGenStep {
  order: number;
  name: string;
  kind?: SdlcStepKind | string;
  instructionMd: string;
  checklist?: string[];
  gatePolicy?: Record<string, unknown>;
  required?: boolean;
  timeoutSeconds?: number;
  retryBudget?: number;
}

export interface SdlcGenResult {
  name: string;
  description?: string;
  steps: SdlcGenStep[];
}

export interface AgentConfigRecommendations {
  executors?: string[];
  skills?: string[];
  memories?: string[];
  workflows?: string[];
}

export interface AgentConfigGenResult {
  name: string;
  avatarUrl?: string;
  roleName: string;
  roleCode: string;
  businessBackground: string;
  responsibilities: string;
  missingFields?: string[];
  clarifyingQuestions?: string[];
  recommendations?: AgentConfigRecommendations;
}
