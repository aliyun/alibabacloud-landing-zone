export interface ExternalPrincipal {
  id: number;
  provider: string;
  subjectId: string;
  subjectType: 'USER' | 'BOT' | 'SERVICE' | string;
  displayName: string | null;
  mappedUserId: number | null;
}

export interface ExternalPrincipalRelation {
  sourceKey: string;
  displayName: string;
  principals: ExternalPrincipal[];
}

export interface ExternalCollaboration {
  provider: string;
  externalProjectId: string;
  externalWorkitemId: string;
  externalUrl: string | null;
  sourceStatusId: string | null;
  sourceStatusName: string | null;
  sourceLifecycle: 'ACTIVE' | 'CLOSED' | 'DELETED' | 'UNAVAILABLE' | string;
  reporter: ExternalPrincipal | null;
  businessOwner: ExternalPrincipal | null;
  principalRelations: ExternalPrincipalRelation[];
  lastSyncAt: string | null;
  syncStatus: 'HEALTHY' | 'DELAYED' | 'ACTION_REQUIRED' | string;
  lastErrorCode: string | null;
  lastError: string | null;
}

export interface Workitem {
  id: number;
  workType: string;
  title: string;
  contentMd: string;
  templateId: number | null;
  statusNodeId: number | null;
  statusName: string | null;
  sdlcId: number | null;
  sdlcName: string | null;
  assigneeType: 'HUMAN' | 'AGENT';
  assigneeRef: number | null;
  assigneeName: string | null;
  assigneeDisplayName?: string | null;
  creatorId?: number | null;
  creatorName?: string | null;
  creatorDisplayName?: string | null;
  priority: number;
  version: number;
  gmtCreate: string;
  gmtModified: string;
  health?: 'OK' | 'STUCK' | null;
  healthReason?: string | null;
  pendingDecision?: boolean | null;
  sourceType?: 'NATIVE' | 'EXTERNAL' | string | null;
  sourceProvider?: string | null;
  sourceUrl?: string | null;
  deletable?: boolean | null;
  deletableReason?: string | null;
  externalCollaboration?: ExternalCollaboration | null;
  /** Source-side creator for an imported workitem; creatorId remains the local import operator. */
  sourceCreator?: ExternalPrincipal | null;
}

export interface TimelineEvent {
  id: number;
  eventType: string;
  fromVal: string | null;
  toVal: string | null;
  actorType: string;
  actorRef: number | null;
  actorName?: string | null;
  actorDisplayName?: string | null;
  fromValDisplay?: string | null;
  toValDisplay?: string | null;
  detailJson: string | null;
  gmtCreate: string;
}

export interface Comment {
  id: number;
  workitemId: number;
  authorType: string;
  authorRef: number;
  contentMd: string;
  gmtCreate: string;
}

export interface WorkitemDetail {
  id: number;
  workType: string;
  title: string;
  contentMd: string;
  templateId: number | null;
  statusNodeId: number | null;
  statusName: string | null;
  sdlcId: number | null;
  sdlcName: string | null;
  assigneeType: 'HUMAN' | 'AGENT';
  assigneeRef: number | null;
  assigneeName: string | null;
  assigneeDisplayName?: string | null;
  creatorId?: number | null;
  creatorName?: string | null;
  creatorDisplayName?: string | null;
  priority: number;
  version: number;
  gmtCreate: string;
  gmtModified: string;
  health?: 'OK' | 'STUCK' | null;
  healthReason?: string | null;
  pendingDecision?: boolean | null;
  sourceType?: 'NATIVE' | 'EXTERNAL' | string | null;
  deletable?: boolean | null;
  deletableReason?: string | null;
  externalCollaboration?: ExternalCollaboration | null;
}

export interface Participant {
  userId: number;
  targetType?: 'AGENT' | 'HUMAN' | string | null;
  name: string;
  displayId?: string | null;
  role: string;
  roleName: string;
  isAgent: boolean;
  online: boolean;
  status?: string | null;
  executorStatus?: string | null;
}

export interface DeliveryStep {
  stepId: number;
  stepKey?: string | null;
  name: string;
  status: 'done' | 'active' | 'paused' | 'pending' | 'failed' | 'reused' | 'skipped' | 'stale';
  planStatus?: 'RUN' | 'REUSED' | 'SKIPPED' | null;
  sourceAttempt?: number | null;
  executorName: string | null;
  error?: string | null;
  subSteps: SubStep[] | null;
  durationMs: number | null;
  attempts: DispatchAttempt[] | null;
}

export interface DispatchAttempt {
  dispatchId: number;
  executorName: string | null;
  status: string | null;
  resumeMode?: string | null;
  error?: string | null;
  startedAt: string | null;
  durationMs: number | null;
  canContinue?: boolean;
  canPause?: boolean;
}

export interface SubStep {
  name: string;
  status: 'done' | 'active' | 'pending' | 'failed';
}

export interface AgentDeliveryProgress {
  agentId: number;
  agentName: string;
  status: 'finished' | 'active' | 'paused' | 'pending' | 'failed';
  durationMs: number | null;
  currentActivity?: string | null;
  steps: DeliveryStep[];
}

export interface DeliveryProgress {
  steps: DeliveryStep[];
  agents?: AgentDeliveryProgress[] | null;
  workflowPlan?: WorkflowPlan | null;
  processGraph?: ProcessGraph | null;
}

export interface ProcessGraphNode {
  key: string;
  dispatchId?: number | null;
  agentId?: number | null;
  agentName: string;
  stepId?: number | null;
  stepName?: string | null;
  status?: string | null;
  startedAt?: string | null;
  durationMs?: number | null;
  error?: string | null;
  triggerCommentId?: number | null;
}

export interface ProcessGraphEdge {
  sourceKey: string;
  targetKey: string;
  type: 'HANDOFF' | 'COMMENT_REWORK' | 'CONTINUE' | 'HUMAN_HANDOFF';
  sourceDispatchId?: number | null;
  targetDispatchId?: number | null;
  commentId?: number | null;
  label: string;
}

export interface ProcessGraph {
  nodes: ProcessGraphNode[];
  edges: ProcessGraphEdge[];
}

export interface RuntimeTraceTokenUsage {
  /** Older persisted traces may not carry this marker. */
  available?: boolean;
  availability?: string | null;
  source?: string | null;
  credits?: number | null;
  inputTokens: number;
  outputTokens: number;
  reasoningTokens: number;
  cacheReadTokens: number;
  cacheWriteTokens: number;
  totalTokens: number;
}

export interface RuntimeTraceBoundary {
  eventId?: string | null;
  kind: string;
  eventTime?: string | null;
  time?: string | null;
  type?: string | null;
  label?: string | null;
  detail?: Record<string, unknown> | null;
}

export interface RuntimeTraceContextFile {
  role: string;
  name: string;
  mediaType?: string | null;
  sizeBytes?: number | null;
  sha256?: string | null;
  contentRef: string;
  previewable: boolean;
}

export interface RuntimeTraceObservation {
  observationId: string;
  parentObservationId?: string | null;
  type: 'AGENT' | 'MCP' | 'BASH' | 'CLI' | 'TOOL' | 'SKILL' | string;
  name?: string | null;
  status?: string | null;
  startedAt?: string | null;
  endedAt?: string | null;
  durationMs?: number | null;
  model?: string | null;
  input?: unknown;
  output?: unknown;
  error?: unknown;
  orphan?: boolean;
  usage?: RuntimeTraceTokenUsage;
  children: RuntimeTraceObservation[];
}

export interface RuntimeTraceSpan {
  spanId: string;
  parentSpanId?: string | null;
  kind: 'LLM' | 'THINKING' | 'SKILL' | 'BASH' | 'CLI' | 'MCP' | 'TOOL' | 'GUIDANCE' | 'ARTIFACT' | string;
  name?: string | null;
  status?: string | null;
  startedAt?: string | null;
  endedAt?: string | null;
  durationMs?: number | null;
  model?: string | null;
  inputSummary?: string | null;
  outputSummary?: string | null;
  input?: unknown;
  output?: string | null;
  content?: string | null;
  errorCategory?: string | null;
  tokenUsage: RuntimeTraceTokenUsage;
  eventIds: string[];
}

export interface RuntimeTraceTurn {
  traceId?: string | null;
  turnId: string;
  stepId?: string | null;
  stepName?: string | null;
  status?: string | null;
  startedAt?: string | null;
  endedAt?: string | null;
  durationMs?: number | null;
  prompt?: string | null;
  systemPrompt?: string | null;
  output?: string | null;
  providerCoverage?: string | null;
  usage?: RuntimeTraceTokenUsage;
  contextFiles?: RuntimeTraceContextFile[];
  observations?: RuntimeTraceObservation[];
  tokenUsage: RuntimeTraceTokenUsage;
  eventIds: string[];
  spans: RuntimeTraceSpan[];
}

export interface RuntimeTraceSession {
  sessionId: string;
  parentSessionId?: string | null;
  provider?: string | null;
  status?: string | null;
  startedAt?: string | null;
  endedAt?: string | null;
  durationMs?: number | null;
  tokenUsage: RuntimeTraceTokenUsage;
  eventIds: string[];
  boundaries: RuntimeTraceBoundary[];
  turns: RuntimeTraceTurn[];
}

export interface RuntimeTraceEvent {
  eventId?: string | null;
  seq?: number | null;
  eventType: string;
  eventTime?: string | null;
  detail: Record<string, unknown>;
}

export interface RuntimeTrace {
  schemaVersion?: string | null;
  source?: 'OSS' | 'LIVE' | string | null;
  dispatchId: number;
  runtimeId?: string | null;
  provider?: string | null;
  changed: boolean;
  lastSeq?: number | null;
  tokenUsage: RuntimeTraceTokenUsage;
  events: RuntimeTraceEvent[];
  sessions: RuntimeTraceSession[];
}

export interface WorkflowPlanStep {
  stepKey?: string | null;
  name?: string | null;
  planStatus: 'RUN' | 'REUSED' | 'SKIPPED';
  sourceAttempt?: number | null;
}

export interface WorkflowPlan {
  revision: number;
  agentId?: number | null;
  agentName?: string | null;
  targetStepId: string;
  reason?: string | null;
  sourceGuidanceIds?: number[] | null;
  steps: WorkflowPlanStep[];
}

export interface TimelineItem {
  id: number;
  type: 'comment' | 'system';
  authorId: number | null;
  authorName: string | null;
  authorType: string;
  isAgent: boolean;
  content: string;
  gmtCreate: string;
  sourceProvider?: string | null;
  sourceExternalWorkitemId?: string | null;
  sourceExternalUrl?: string | null;
  interactions?: Array<{
    guidanceId: number;
    targetAgentId: number;
    targetAgentName: string;
    status: 'QUEUED' | 'DELIVERED' | 'APPLIED' | 'FAILED';
    error?: string | null;
    replyCommentId?: number | null;
    replyContent?: string | null;
    repliedAt?: string | null;
  }>;
}

export interface Clarification {
  id: number;
  workitemId: number;
  contentMd: string;
  gmtCreate: string;
  gmtModified: string;
}

export interface Artifact {
  id: number;
  workitemId: number;
  dispatchId: number | null;
  name: string;
  type: string;
  size: number | null;
  gmtCreate: string;
}
